"""Optional integration tests against real Redis and PostgreSQL.

These tests mutate the configured stores, so they also require an explicit
opt-in. Never point them at production or shared instances:

    UPCP_TEST_ALLOW_DANGEROUS=1 \
    UPCP_TEST_REDIS_URL=redis://127.0.0.1:6390/0 \
    UPCP_TEST_DATABASE_URL=postgresql://user@127.0.0.1:55432/upcp_test \
    server/.venv/bin/python -m pytest server/tests/test_redis_postgres.py

Redis keys are namespaced per test run and only those keys are removed; this
file never calls FLUSHDB. PostgreSQL rows are deleted by user id afterwards.
"""

from __future__ import annotations

import asyncio
import dataclasses
import os
import time
import uuid

import pytest

from app.crypto import DataCipher
from app.library.journal import PostgresDatabase
from app.sessions import BrowserSession, LockTimeout, MemoryBackend, RedisBackend, new_browser_session

REDIS_URL = os.environ.get("UPCP_TEST_REDIS_URL", "")
DATABASE_URL = os.environ.get("UPCP_TEST_DATABASE_URL", "")
OPT_IN = os.environ.get("UPCP_TEST_ALLOW_DANGEROUS") == "1"


@pytest.mark.skipif(not (REDIS_URL and OPT_IN), reason="integration stores are not opted in")
def test_redis_sessions_and_lock(settings):
    backend = RedisBackend(REDIS_URL, settings)
    user = str(uuid.uuid4())

    async def scenario():
        session = new_browser_session(user)
        await backend.client.delete(
            f"upcp:sess:{session.id}", f"upcp:sess:user:{user}",
        )
        await backend.save_browser(session)
        loaded = await backend.get_browser(session.id)
        assert loaded is not None and loaded.user_id == user and loaded.csrf == session.csrf
        assert loaded.revision == session.revision
        await backend.drop_browser(session.id)
        assert await backend.get_browser(session.id) is None

        payload = b"\x00encrypted-library-session\x00"
        await backend.set_library(user, "tangdaowan", payload)
        assert await backend.get_library(user, "tangdaowan") == payload
        assert await backend.has_library(user, "tangdaowan") is True
        raw = await backend.client.get(f"upcp:lib:{user}:tangdaowan")
        assert raw == payload
        await backend.drop_library(user, "tangdaowan")
        assert await backend.has_library(user, "tangdaowan") is False

        async with backend.lock(user, ttl=5, wait=1):
            with pytest.raises(LockTimeout):
                async with backend.lock(user, ttl=5, wait=0.2):
                    pass
        async with backend.lock(user, ttl=5, wait=1):
            pass
        # Only remove keys created by this test; never FLUSHDB on a shared store.
        await backend.client.delete(
            f"upcp:sess:{session.id}", f"upcp:sess:user:{user}",
            f"upcp:lib:{user}:tangdaowan", f"upcp:libabs:{user}:tangdaowan",
            f"upcp:lock:user:{user}",
        )
        await backend.close()

    asyncio.run(scenario())


@pytest.mark.skipif(not (DATABASE_URL and OPT_IN), reason="integration stores are not opted in")
def test_postgres_journal_encrypts_and_isolates(settings):
    cipher = DataCipher.from_settings(settings)
    database = PostgresDatabase(DATABASE_URL, cipher, settings.session_secret)
    identity = os.urandom(32)
    user = None
    try:
        database.ensure_schema()
        user = database.get_or_create_user(identity, "v1")
        assert database.get_or_create_user(identity, "v1") == user
        other = database.get_or_create_user(os.urandom(32), "v1")

        attempt = str(uuid.uuid4())
        prepared = {
            "choice": {"seat": "A-1", "date": "2026-11-23"},
            "labels": {"seat": "A-1", "building": "唐岛湾阅览楼"},
        }
        assert database.begin(user, "tangdaowan", attempt, prepared, ["prior-1"]) is True
        assert database.begin(user, "tangdaowan", attempt, prepared, []) is False
        assert database.find(other, "tangdaowan", attempt) is None

        database.set_status(user, "tangdaowan", attempt, "confirmed", "record-1")
        found = database.find(user, "tangdaowan", attempt)
        assert found["status"] == "confirmed" and isinstance(found["status"], str)
        details = database.confirmed_details(user, "tangdaowan")
        assert details and details[0][1] == "record-1"
        assert database.claimed_records(user, "tangdaowan") == {"record-1"}
        assert database.claimed_records(other, "tangdaowan") == set()

        # Raw columns must not contain the plaintext labels.
        import psycopg

        with psycopg.connect(DATABASE_URL) as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT prepared_enc, record_id_enc FROM attempts WHERE id = %s", (attempt,),
                )
                prepared_enc, record_enc = cursor.fetchone()
                assert b"A-1" not in bytes(prepared_enc)
                assert b"record-1" not in bytes(record_enc)
                cursor.execute(
                    "SELECT meta_enc FROM audit WHERE user_id = %s ORDER BY id DESC LIMIT 1", (user,),
                )
                row = cursor.fetchone()
                if row and row[0]:
                    assert b"A-1" not in bytes(row[0])

        database.audit(user, "library_book", b"ip-hash", b"ua-hash", {"seat": "A-1"})
        with psycopg.connect(DATABASE_URL) as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT meta_enc FROM audit WHERE user_id = %s ORDER BY id DESC LIMIT 1", (user,),
                )
                meta_enc = cursor.fetchone()[0]
                assert b"A-1" not in bytes(meta_enc)

        key = database.reservation_key(user, "tangdaowan", "record-1")
        cancel = str(uuid.uuid4())
        assert database.begin_cancellation(user, "tangdaowan", cancel, key) is True
        stored = database.find_cancellation(user, "tangdaowan", cancel)
        assert stored == (key, "unknown") and isinstance(stored[1], str)
        assert database.begin_cancellation(user, "tangdaowan", str(uuid.uuid4()), key) is False
        assert database.reconcile_cancellations(user, "tangdaowan", ["record-1"], []) == {key}

        database.delete_user(user)
        assert database.find(user, "tangdaowan", attempt) is None
        with psycopg.connect(DATABASE_URL) as connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT count(*) FROM attempts WHERE user_id = %s", (user,))
                assert cursor.fetchone()[0] == 0
    finally:
        database.close()
