"""Per-user library orchestration.

Each request decrypts one library session, builds a short-lived protocol
adapter, runs the upstream calls in a bounded thread pool and drops the object
again. Booking attempts are recorded before the school request is sent, so a
crash leaves an `unknown` attempt that must be reconciled instead of retried.
"""

from __future__ import annotations

import json
import logging
import time
import uuid
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any, Mapping
from zoneinfo import ZoneInfo

from ..config import Settings
from ..crypto import CryptoError, DataCipher, aad, hmac_bytes
from ..sessions import BrowserSession, SessionBackend, LockTimeout, new_browser_session
from ..upstream.bridge import Upstream
from ..upstream.pool import UpstreamPool, UpstreamUnavailable, run_blocking
from .journal import Database, JournalError


logger = logging.getLogger("upcp.library")

CHINA = ZoneInfo("Asia/Shanghai")
BRANCHES = ("tangdaowan", "east", "qiushi")
RECORD_BRANCHES = {"tangdaowan"}
LIBRARY_NAMES = {
    "tangdaowan": "唐岛湾图书馆",
    "east": "古镇口东区图书馆",
    "qiushi": "求是图书馆",
}
CHOICE_FIELDS = ("building", "date", "room", "seat", "start", "end")
BOOK_FIELDS = {"id", "building", "date", "room", "seat", "start", "end"}
CANCEL_FIELDS = {"id", "reservationId"}
CATALOG_PATHS = (set(), *[set(CHOICE_FIELDS[:size]) for size in range(2, 6)])


class LibraryServiceError(Exception):
    def __init__(self, code: str, status: int):
        self.code = code
        self.status = status
        super().__init__(code)


class LibrarySessionExpired(LibraryServiceError):
    def __init__(self):
        super().__init__("library_session_expired", 401)


class LibrarySessionChanged(LibraryServiceError):
    def __init__(self):
        super().__init__("library_session_changed", 409)


class InvalidRequest(LibraryServiceError):
    def __init__(self):
        super().__init__("invalid_request", 400)


class ChallengeRequired(LibraryServiceError):
    def __init__(self):
        super().__init__("challenge_required", 409)


class UpstreamFailure(LibraryServiceError):
    def __init__(self):
        super().__init__("upstream_unavailable", 503)


class LoginRejected(LibraryServiceError):
    def __init__(self, code: str = "invalid_credentials"):
        super().__init__(code, 409 if code == "interaction_required" else 401)


@dataclass(frozen=True)
class AuditContext:
    ip_hash: bytes | None
    ua_hash: bytes | None


@dataclass(frozen=True)
class LoginResult:
    user_id: str
    branch: str
    session: BrowserSession


def _valid_branch(branch: str) -> bool:
    return branch in BRANCHES


def _uuid_text(value: Any) -> str:
    if not isinstance(value, str) or len(value) != 36:
        raise InvalidRequest()
    try:
        parsed = uuid.UUID(value)
    except (ValueError, TypeError, AttributeError):
        raise InvalidRequest() from None
    if str(parsed) != value:
        raise InvalidRequest()
    return value


def _label_lengths(payload: Mapping[str, Any], keys: set[str]) -> None:
    if not isinstance(payload, dict) or set(payload) != keys:
        raise InvalidRequest()
    for value in payload.values():
        if not isinstance(value, str) or not 1 <= len(value) <= 128:
            raise InvalidRequest()


def _catalog_params(params: Mapping[str, list[str]]) -> dict[str, str]:
    if not isinstance(params, Mapping) or len(params) > 6:
        raise InvalidRequest()
    values: dict[str, str] = {}
    for key, entries in params.items():
        if key not in CHOICE_FIELDS or not isinstance(entries, list) or len(entries) != 1:
            raise InvalidRequest()
        value = entries[0]
        if not isinstance(value, str) or not 1 <= len(value) <= 128:
            raise InvalidRequest()
        values[key] = value
    if set(values) not in CATALOG_PATHS:
        raise InvalidRequest()
    return values


class LibraryLoginService:
    def __init__(
        self,
        settings: Settings,
        store: SessionBackend,
        database: Database,
        cipher: DataCipher,
        pool: UpstreamPool,
        upstream: Upstream,
    ):
        self.settings = settings
        self.store = store
        self.database = database
        self.cipher = cipher
        self.pool = pool
        self.upstream = upstream

    async def login(
        self,
        *,
        branch: str,
        username: str,
        password: str,
        existing: BrowserSession | None,
        audit: AuditContext,
    ) -> LoginResult:
        if not _valid_branch(branch):
            raise InvalidRequest()
        authenticate = (
            self.upstream.authenticate_chaoxing if branch != "tangdaowan"
            else self.upstream.authenticate
        )
        try:
            verified = await self.pool.run(
                f"auth:{branch}", authenticate, username, password,
                expected=(self.upstream.LibraryAuthError,),
            )
        except self.upstream.LibraryAuthError as error:
            code = getattr(error, "code", "invalid_credentials")
            if code == "interaction_required":
                raise LoginRejected("interaction_required") from None
            raise LoginRejected("invalid_credentials") from None
        except UpstreamUnavailable:
            raise UpstreamFailure() from None
        finally:
            del password

        try:
            identity_hmac = self._identity(username)
            user_id = await run_blocking(
                self.database.get_or_create_user, identity_hmac, self.settings.consent_version,
            )
            payload = self._serialize_session(branch, verified)
            encrypted = self.cipher.encrypt_json(
                payload, aad(user_id, "redis", "library", branch),
            )
            await self.store.set_library(user_id, branch, encrypted)
            session = await self._rotate_browser_session(existing, user_id)
            await run_blocking(self.database.touch_user, user_id)
            await self._audit(user_id, "library_login", audit, {"branch": branch})
        except (CryptoError, JournalError) as error:
            logger.warning("login persistence failed: %s", type(error).__name__)
            raise UpstreamFailure() from None
        return LoginResult(user_id=user_id, branch=branch, session=session)

    def _identity(self, username: str) -> bytes:
        return hmac_bytes(self.settings.session_secret, "identity", username.strip())

    async def _rotate_browser_session(
        self, existing: BrowserSession | None, user_id: str,
    ) -> BrowserSession:
        if existing is not None:
            if existing.user_id != user_id:
                await self.store.drop_libraries(existing.user_id)
            await self.store.drop_browser(existing.id)
        session = new_browser_session(user_id)
        await self.store.save_browser(session)
        return session

    @staticmethod
    def _serialize_session(branch: str, verified: Any) -> dict[str, str]:
        if branch == "tangdaowan":
            return {
                "kind": "upc",
                "token": str(verified.token),
                "user_id": str(verified.user_id),
                "username": str(verified.username),
                "name": str(verified.name),
            }
        return {
            "kind": "chaoxing",
            "user_id": str(verified.user_id),
            "username": str(verified.username),
            "cookie": str(getattr(verified, "_office_cookie")),
        }

    async def _audit(
        self, user_id: str | None, action: str, audit: AuditContext,
        meta: Mapping[str, Any] | None = None,
    ) -> None:
        try:
            await run_blocking(
                self.database.audit, user_id, action, audit.ip_hash, audit.ua_hash, meta,
            )
        except Exception as error:  # noqa: BLE001 - audit failure must not corrupt results
            logger.warning("audit write failed: %s", type(error).__name__)


class UserLibraryService:
    def __init__(
        self,
        settings: Settings,
        store: SessionBackend,
        database: Database,
        cipher: DataCipher,
        pool: UpstreamPool,
        upstream: Upstream,
    ):
        self.settings = settings
        self.store = store
        self.database = database
        self.cipher = cipher
        self.pool = pool
        self.upstream = upstream

    # -- session -------------------------------------------------------

    async def state(self, session: BrowserSession, branch: str) -> dict[str, Any]:
        if not _valid_branch(branch):
            raise InvalidRequest()
        if not self.settings.booking_enabled:
            return {"state": "unavailable"}
        if not await self.store.has_library(session.user_id, branch):
            return {"state": "sign_in"}
        return {
            "state": "ready",
            "revision": session.revision,
            "csrf": session.csrf,
            "bookingAvailable": True,
            "recordsAvailable": branch in RECORD_BRANCHES,
        }

    async def logout(self, session: BrowserSession, branch: str, audit: AuditContext) -> None:
        if not _valid_branch(branch):
            raise InvalidRequest()
        await self.store.drop_library(session.user_id, branch)
        await self._audit(session.user_id, "library_logout", audit, {"branch": branch})

    # -- reads ---------------------------------------------------------

    async def catalog(
        self, session: BrowserSession, branch: str, params: Mapping[str, list[str]],
    ) -> dict[str, Any]:
        values = _catalog_params(params)
        library = await self._library(session.user_id, branch)
        try:
            return await self.pool.run(
                self._group(branch), library.catalog, values,
                expected=(self.upstream.LibraryError,),
            )
        except self.upstream.LibraryError as error:
            raise await self._map_error(session.user_id, branch, error) from None
        except UpstreamUnavailable:
            raise UpstreamFailure() from None

    async def reservations(
        self, session: BrowserSession, branch: str,
        attempt: str | None, cancel_attempt: str | None,
    ) -> dict[str, Any]:
        if branch not in RECORD_BRANCHES:
            return {"available": False}
        attempt_id = _uuid_text(attempt) if attempt else None
        cancel_id = _uuid_text(cancel_attempt) if cancel_attempt else None
        user_id = session.user_id
        library = await self._library(user_id, branch)
        try:
            records, present, cancelled = await self._call(library.read_reservations, branch)
        except self.upstream.LibraryError as error:
            raise await self._map_error(user_id, branch, error) from None
        pending = await self._reconcile(user_id, branch, records)
        cancel_pending = await run_blocking(
            self.database.reconcile_cancellations, user_id, branch, present, cancelled,
        )
        result: dict[str, Any] = {
            "pending": pending,
            "cancellationPending": bool(cancel_pending),
            "records": [],
        }
        for record in records:
            visible = {
                key: record[key] for key in ("id", "date", "start", "end", "building", "room", "seat")
            }
            visible["cancellable"] = record["cancellable"]
            visible["cancelPending"] = await run_blocking(
                self.database.reservation_key, user_id, branch, record["id"],
            ) in cancel_pending
            result["records"].append(visible)
        if attempt_id:
            outcome = await run_blocking(self.database.find, user_id, branch, attempt_id)
            if outcome:
                result["attempt"] = outcome
        if cancel_id:
            row = await run_blocking(self.database.find_cancellation, user_id, branch, cancel_id)
            if row:
                status = row[1]
                result["cancellation"] = {
                    "id": cancel_id, "status": "unknown" if status == "submitted" else status,
                }
        return result

    # -- writes --------------------------------------------------------

    async def book(
        self, session: BrowserSession, branch: str, payload: Mapping[str, Any],
        audit: AuditContext,
    ) -> dict[str, Any]:
        if not _valid_branch(branch):
            raise InvalidRequest()
        if not self.settings.booking_enabled:
            raise LibraryServiceError("booking_disabled", 403)
        selection = dict(payload)
        _label_lengths(selection, BOOK_FIELDS)
        attempt = _uuid_text(selection.pop("id"))
        user_id = session.user_id
        try:
            async with self.store.lock(user_id):
                previous = await run_blocking(self.database.find, user_id, branch, attempt)
                if previous is not None:
                    status = "unknown" if previous["status"] == "confirmed" else previous["status"]
                    return {"status": status}
                library = await self._library(user_id, branch)
                try:
                    records = [] if branch not in RECORD_BRANCHES else await self._call(
                        library.reservations, branch,
                    )
                    prepared = await self._call(library.prepare_booking, branch, selection)
                    if self.upstream.matching_record(prepared, records, set(), existing=True):
                        await run_blocking(
                            self.database.finish_without_sending, user_id, branch, attempt, "rejected",
                        )
                        await self._audit(user_id, "library_book", audit, {"branch": branch, "status": "rejected"})
                        return {"status": "rejected"}
                except self.upstream.LibraryError as error:
                    if error.status == 401:
                        await self.store.drop_library(user_id, branch)
                        raise LibrarySessionExpired() from None
                    status = "challenge" if getattr(error, "code", "") == "captcha_required" else "rejected"
                    await run_blocking(
                        self.database.finish_without_sending, user_id, branch, attempt, status,
                    )
                    await self._audit(user_id, "library_book", audit, {"branch": branch, "status": status})
                    return {"status": status}
                except UpstreamUnavailable:
                    raise UpstreamFailure() from None
                if not await run_blocking(
                    self.database.begin, user_id, branch, attempt, prepared, records,
                ):
                    previous = await run_blocking(self.database.find, user_id, branch, attempt)
                    status = "rejected"
                    if previous:
                        status = "unknown" if previous["status"] == "confirmed" else previous["status"]
                    else:
                        await run_blocking(
                            self.database.finish_without_sending, user_id, branch, attempt, status,
                        )
                    return {"status": status}
                try:
                    status = await self._call(library.submit_booking, branch, prepared)
                except self.upstream.LibraryError as error:
                    if error.status == 401:
                        await self.store.drop_library(user_id, branch)
                        raise LibrarySessionExpired() from None
                    status = "unknown"
                if status not in {"submitted", "rejected", "unknown", "accepted", "challenge"}:
                    status = "unknown"
                await run_blocking(self.database.set_status, user_id, branch, attempt, status)
                await self._audit(user_id, "library_book", audit, {"branch": branch, "status": status})
                return {"status": status}
        except LockTimeout:
            raise UpstreamFailure() from None

    async def cancel(
        self, session: BrowserSession, branch: str, payload: Mapping[str, Any],
        audit: AuditContext,
    ) -> dict[str, Any]:
        if branch not in RECORD_BRANCHES:
            raise LibraryServiceError("unknown_route", 404)
        if not self.settings.booking_enabled:
            raise LibraryServiceError("booking_disabled", 403)
        fields = dict(payload)
        _label_lengths(fields, CANCEL_FIELDS)
        attempt = _uuid_text(fields["id"])
        reservation = fields["reservationId"]
        if not self.upstream.path_id(reservation):
            raise InvalidRequest()
        user_id = session.user_id
        try:
            async with self.store.lock(user_id):
                journal = self.database
                key = await run_blocking(journal.reservation_key, user_id, branch, reservation)
                previous = await run_blocking(journal.find_cancellation, user_id, branch, attempt)
                if previous is not None:
                    saved_key, status = previous
                    if saved_key != key:
                        raise InvalidRequest()
                    return {"status": "unknown" if status == "submitted" else status}
                library = await self._library(user_id, branch)
                try:
                    records, present, cancelled = await self._call(library.read_reservations, branch)
                except self.upstream.LibraryError as error:
                    if error.status == 401:
                        await self.store.drop_library(user_id, branch)
                        raise LibrarySessionExpired() from None
                    await run_blocking(
                        journal.begin_cancellation, user_id, branch, attempt, key, "rejected",
                    )
                    return {"status": "rejected"}
                pending = await run_blocking(
                    journal.reconcile_cancellations, user_id, branch, present, cancelled,
                )
                if key in pending:
                    return {"status": "unknown"}
                if not any(
                    record["id"] == reservation and record["cancellable"] for record in records
                ):
                    await run_blocking(
                        journal.begin_cancellation, user_id, branch, attempt, key, "rejected",
                    )
                    return {"status": "rejected"}
                if not await run_blocking(
                    journal.begin_cancellation, user_id, branch, attempt, key,
                ):
                    return {"status": "unknown"}
                try:
                    status = await self._call(library.cancel_reservation, branch, reservation)
                except self.upstream.LibraryError as error:
                    if error.status == 401:
                        await self.store.drop_library(user_id, branch)
                        raise LibrarySessionExpired() from None
                    status = "unknown"
                await run_blocking(journal.set_cancellation, user_id, branch, attempt, status)
                if status != "rejected":
                    status = "unknown"
                    try:
                        _, present, cancelled = await self._call(library.read_reservations, branch)
                    except self.upstream.LibraryError as error:
                        if error.status == 401:
                            await self.store.drop_library(user_id, branch)
                    else:
                        await run_blocking(
                            journal.reconcile_cancellations, user_id, branch, present, cancelled,
                        )
                        settled = await run_blocking(
                            journal.find_cancellation, user_id, branch, attempt,
                        )
                        if settled and settled[1] in {"cancelled", "absent"}:
                            status = settled[1]
                await self._audit(
                    user_id, "library_cancel", audit,
                    {"branch": branch, "status": status},
                )
                return {"status": status}
        except LockTimeout:
            raise UpstreamFailure() from None

    # -- internals -----------------------------------------------------

    async def _reconcile(self, user_id: str, branch: str, records: list[dict[str, Any]]) -> bool:
        journal = self.database
        matching = self.upstream.matching_record
        claimed = await run_blocking(journal.claimed_records, user_id, branch)
        candidates = []
        for attempt, prepared_json, prior_ids in await run_blocking(
            journal.unsettled, user_id, branch,
        ):
            prepared = json.loads(prepared_json)
            record_id = matching(prepared, records, claimed | set(prior_ids))
            candidates.append((attempt, prepared, record_id))
        occurrences = Counter(record_id for _, _, record_id in candidates if record_id)
        pending = False
        for attempt, prepared, record_id in candidates:
            if record_id and occurrences[record_id] == 1:
                await run_blocking(
                    journal.set_status, user_id, branch, attempt, "confirmed", record_id,
                )
            elif self._booking_ended(prepared):
                await run_blocking(journal.set_status, user_id, branch, attempt, "elapsed")
            else:
                pending = True
        records_by_id = {record["id"]: record for record in records}
        for attempt, record_id, details in await run_blocking(
            journal.confirmed_details, user_id, branch,
        ):
            prepared = json.loads(details)
            record = records_by_id.get(record_id)
            if record is not None:
                labels = prepared["labels"]
                record.update({key: labels[key] for key in ("building", "room", "seat")})
            elif self._booking_ended(prepared):
                await run_blocking(journal.forget_details, user_id, branch, attempt)
        return pending

    @staticmethod
    def _booking_ended(prepared: Mapping[str, Any]) -> bool:
        try:
            date = prepared["choice"]["date"]
            label = prepared["labels"]["end"]
            end = datetime.fromisoformat(
                f"{date}T{'00:00' if label == '24:00' else label}",
            ).replace(tzinfo=CHINA)
            if label == "24:00":
                end += timedelta(days=1)
        except (KeyError, ValueError, TypeError):
            return False
        return end.timestamp() + 3600 < time.time()

    async def _library(self, user_id: str, branch: str) -> Any:
        payload = await self.store.get_library(user_id, branch)
        if payload is None:
            raise LibrarySessionExpired()
        try:
            data = self.cipher.decrypt_json(payload, aad(user_id, "redis", "library", branch))
        except CryptoError:
            await self.store.drop_library(user_id, branch)
            raise LibrarySessionExpired() from None
        if not isinstance(data, dict):
            await self.store.drop_library(user_id, branch)
            raise LibrarySessionExpired()
        try:
            if branch == "tangdaowan":
                session = self.upstream.LibrarySession(
                    str(data["token"]), str(data["user_id"]), str(data["username"]), str(data["name"]),
                )
                return self.upstream.UpcLibrary(session)
            session = self.upstream.ChaoxingSession(
                str(data["user_id"]), str(data["username"]), str(data["cookie"]),
            )
            return self.upstream.ChaoxingLibrary(branch, session)
        except (KeyError, TypeError, ValueError):
            await self.store.drop_library(user_id, branch)
            raise LibrarySessionExpired() from None

    async def _call(self, function, branch: str, *args):
        try:
            return await self.pool.run(
                self._group(branch), function, *args,
                expected=(self.upstream.LibraryError,),
            )
        except UpstreamUnavailable:
            raise UpstreamFailure() from None

    @staticmethod
    def _group(branch: str) -> str:
        return f"library:{branch}"

    async def _map_error(self, user_id: str, branch: str, error: Exception) -> LibraryServiceError:
        status = getattr(error, "status", 502)
        if status == 401:
            await self.store.drop_library(user_id, branch)
            return LibrarySessionExpired()
        if status == 400:
            return InvalidRequest()
        if getattr(error, "code", "") == "captcha_required":
            return ChallengeRequired()
        return UpstreamFailure()

    async def _audit(
        self, user_id: str | None, action: str, audit: AuditContext,
        meta: Mapping[str, Any] | None = None,
    ) -> None:
        try:
            await run_blocking(
                self.database.audit, user_id, action, audit.ip_hash, audit.ua_hash, meta,
            )
        except Exception as error:  # noqa: BLE001
            logger.warning("audit write failed: %s", type(error).__name__)
