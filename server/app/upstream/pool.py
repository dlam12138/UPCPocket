"""Bounded thread pool and circuit breaker around the synchronous protocols."""

from __future__ import annotations

import asyncio
import functools
import logging
import time
from typing import Any, Callable

import anyio


logger = logging.getLogger("upcp.pool")


class UpstreamUnavailable(RuntimeError):
    def __init__(self, code: str = "upstream_unavailable"):
        self.code = code
        super().__init__(code)


class UpstreamPool:
    def __init__(
        self,
        *,
        concurrency: int,
        timeout: float,
        failure_threshold: int = 5,
        cooldown: float = 30.0,
    ):
        if concurrency < 1 or timeout <= 0:
            raise ValueError("invalid pool configuration")
        self._semaphore = asyncio.Semaphore(concurrency)
        self._timeout = timeout
        self._threshold = failure_threshold
        self._cooldown = cooldown
        self._lock = asyncio.Lock()
        self._state: dict[str, tuple[int, float]] = {}
        self._abandoned = 0

    async def _check(self, group: str) -> None:
        async with self._lock:
            failures, open_until = self._state.get(group, (0, 0.0))
            if open_until and open_until > time.monotonic():
                raise UpstreamUnavailable("circuit_open")

    async def _record(self, group: str, ok: bool) -> None:
        async with self._lock:
            failures, open_until = self._state.get(group, (0, 0.0))
            if ok:
                self._state[group] = (0, 0.0)
                return
            failures += 1
            if failures >= self._threshold:
                self._state[group] = (0, time.monotonic() + self._cooldown)
            else:
                self._state[group] = (failures, 0.0)

    async def run(
        self,
        group: str,
        function: Callable[..., Any],
        *args: Any,
        expected: tuple[type[BaseException], ...] = (),
    ) -> Any:
        """Run a blocking protocol call with a hard timeout and breaker."""
        await self._check(group)
        await self._semaphore.acquire()
        task = asyncio.create_task(anyio.to_thread.run_sync(
            functools.partial(function, *args), abandon_on_cancel=False,
        ))
        released = {"value": False}

        def release(_task):
            # The thread may outlive a timeout; its concurrency permit is only
            # returned when the thread truly finishes. This prevents unbounded
            # thread growth while still giving callers a bounded wait.
            if not released["value"]:
                released["value"] = True
                self._semaphore.release()

        task.add_done_callback(release)
        try:
            await asyncio.wait({task}, timeout=self._timeout)
        except asyncio.CancelledError:
            raise
        if not task.done():
            self._abandoned += 1
            logger.warning(
                "upstream timeout group=%s abandoned=%d", group, self._abandoned,
            )
            await self._record(group, False)
            raise UpstreamUnavailable("timeout")
        try:
            result = task.result()
        except BaseException as error:
            if expected and isinstance(error, expected):
                raise
            await self._record(group, False)
            raise
        await self._record(group, True)
        return result


async def run_blocking(function: Callable[..., Any], *args: Any) -> Any:
    """Run a short blocking call (database, cache) outside the event loop."""
    return await anyio.to_thread.run_sync(functools.partial(function, *args))
