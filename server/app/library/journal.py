"""Booking journal and user identity store.

PostgreSQL is authoritative in production. Every attempt is keyed by a
client-generated UUID, so a retry returns known state instead of sending a
second request to the school. Choice details, prior record IDs and the school
record ID are AES-GCM encrypted with a per-user AAD.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import re
import uuid
from pathlib import Path
from typing import Any, Iterable, Mapping, Protocol

import psycopg
from psycopg_pool import ConnectionPool

from ..config import ConfigError, Settings
from ..crypto import CryptoError, DataCipher, aad


ATTEMPT_STATUSES = {"unknown", "submitted", "accepted", "confirmed", "rejected", "challenge", "elapsed"}
CANCELLATION_STATUSES = {"unknown", "submitted", "cancelled", "absent", "rejected"}
MIGRATION = Path(__file__).resolve().parents[2] / "migrations" / "001_init.sql"
MIGRATION_LOCK = 8_675_309


class JournalError(RuntimeError):
    pass


class Database(Protocol):
    def ping(self) -> bool: ...

    def ensure_schema(self) -> None: ...

    def get_or_create_user(self, identity_hmac: bytes, consent_version: str) -> str: ...

    def touch_user(self, user_id: str) -> None: ...

    def delete_user(self, user_id: str) -> None: ...

    def audit(
        self, user_id: str | None, action: str,
        ip_hash: bytes | None, ua_hash: bytes | None, meta: Mapping[str, Any] | None = None,
    ) -> None: ...

    def find(self, user_id: str, branch: str, attempt: str) -> dict[str, Any] | None: ...

    def unsettled(self, user_id: str, branch: str) -> list[tuple[str, str, list[str]]]: ...

    def begin(
        self, user_id: str, branch: str, attempt: str,
        prepared: Mapping[str, Any], record_ids: Iterable[str],
    ) -> bool: ...

    def finish_without_sending(self, user_id: str, branch: str, attempt: str, status: str) -> None: ...

    def set_status(
        self, user_id: str, branch: str, attempt: str, status: str, record_id: str | None = None,
    ) -> None: ...

    def confirmed_details(self, user_id: str, branch: str) -> list[tuple[str, str, str]]: ...

    def claimed_records(self, user_id: str, branch: str) -> set[str]: ...

    def forget_details(self, user_id: str, branch: str, attempt: str) -> None: ...

    def reservation_key(self, user_id: str, branch: str, reservation: str) -> bytes: ...

    def find_cancellation(self, user_id: str, branch: str, attempt: str) -> tuple[bytes, str] | None: ...

    def begin_cancellation(
        self, user_id: str, branch: str, attempt: str, reservation_key: bytes, status: str = "unknown",
    ) -> bool: ...

    def set_cancellation(self, user_id: str, branch: str, attempt: str, status: str) -> None: ...

    def reconcile_cancellations(
        self, user_id: str, branch: str, present_ids: Iterable[str], cancelled_ids: Iterable[str],
    ) -> set[bytes]: ...

    def cleanup(self, attempts_days: int, audit_days: int) -> None: ...

    def close(self) -> None: ...


def _text(value: Any) -> str:
    """Normalize text columns; SQL_ASCII databases may come back as bytes."""
    if isinstance(value, bytes):
        return value.decode("utf-8", "replace")
    return str(value)


def _uuid(value: str) -> str:
    try:
        return str(uuid.UUID(value))
    except (ValueError, TypeError, AttributeError):
        raise JournalError("invalid attempt id") from None


class _CipherMixin:
    cipher: DataCipher
    secret: bytes

    def _aad(self, user_id: str, *parts: str) -> bytes:
        return aad(user_id, *parts)

    def _encrypt_json(self, user_id: str, column: str, value: object) -> bytes:
        return self.cipher.encrypt_json(value, self._aad(user_id, "attempts", column))

    def _decrypt_json(self, user_id: str, column: str, token: bytes) -> object:
        return self.cipher.decrypt_json(token, self._aad(user_id, "attempts", column))

    def _reservation_hmac(self, user_id: str, branch: str, reservation: str) -> bytes:
        message = f"cancel\x1f{user_id}\x1f{branch}\x1f{reservation}".encode("utf-8")
        return hmac.new(self.secret, message, hashlib.sha256).digest()


class PostgresDatabase(_CipherMixin):
    def __init__(self, url: str, cipher: DataCipher, secret: bytes, *, max_connections: int = 8):
        self.url = url
        self.cipher = cipher
        self.secret = secret
        self.pool = ConnectionPool(
            conninfo=url, min_size=0, max_size=max(2, max_connections),
            open=True, timeout=10, kwargs={"autocommit": True},
        )

    def ping(self) -> bool:
        try:
            with self.pool.connection() as connection:
                with connection.cursor() as cursor:
                    cursor.execute("SELECT 1")
                    return cursor.fetchone() == (1,)
        except Exception:
            return False

    def ensure_schema(self) -> None:
        if not MIGRATION.is_file():
            raise ConfigError("database migration file is missing")
        sql = MIGRATION.read_text(encoding="utf-8")
        try:
            with self.pool.connection() as connection:
                with connection.cursor() as cursor:
                    # Multiple uvicorn workers start together; serialize the
                    # idempotent DDL so concurrent CREATE ... IF NOT EXISTS does
                    # not race on pg_type/pg_class.
                    cursor.execute("SELECT pg_advisory_lock(%s)", (MIGRATION_LOCK,))
                    try:
                        cursor.execute(sql)
                    finally:
                        cursor.execute("SELECT pg_advisory_unlock(%s)", (MIGRATION_LOCK,))
        except psycopg.Error:
            raise ConfigError("cannot apply database schema") from None

    # -- users ---------------------------------------------------------

    def get_or_create_user(self, identity_hmac: bytes, consent_version: str) -> str:
        with self.pool.connection() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    INSERT INTO users (id, identity_hmac, consent_version, created_at, last_seen_at)
                    VALUES (gen_random_uuid(), %s, %s, now(), now())
                    ON CONFLICT (identity_hmac) DO UPDATE
                    SET consent_version = EXCLUDED.consent_version, last_seen_at = now()
                    RETURNING id
                    """,
                    (identity_hmac, consent_version),
                )
                return str(cursor.fetchone()[0])

    def touch_user(self, user_id: str) -> None:
        self._execute("UPDATE users SET last_seen_at = now() WHERE id = %s", (_uuid(user_id),))

    def delete_user(self, user_id: str) -> None:
        self._execute("DELETE FROM users WHERE id = %s", (_uuid(user_id),))

    # -- audit ---------------------------------------------------------

    def audit(
        self, user_id: str | None, action: str,
        ip_hash: bytes | None, ua_hash: bytes | None, meta: Mapping[str, Any] | None = None,
    ) -> None:
        if not re.fullmatch(r"[a-z_]{3,48}", action):
            raise JournalError("invalid audit action")
        meta_enc = None
        if meta is not None:
            meta_enc = self.cipher.encrypt_json(meta, aad(user_id or "anonymous", "audit", "meta"))
        self._execute(
            "INSERT INTO audit (user_id, action, ip_hash, ua_hash, meta_enc) VALUES (%s, %s, %s, %s, %s)",
            (_uuid(user_id) if user_id else None, action, ip_hash, ua_hash, meta_enc),
        )

    # -- attempts ------------------------------------------------------

    def find(self, user_id: str, branch: str, attempt: str) -> dict[str, Any] | None:
        row = self._fetchone(
            "SELECT status, record_id_enc FROM attempts WHERE user_id = %s AND branch = %s AND id = %s",
            (_uuid(user_id), branch, _uuid(attempt)),
        )
        if row is None:
            return None
        status, record_enc = row
        status = _text(status)
        result: dict[str, Any] = {
            "id": attempt,
            "status": "unknown" if status in {"submitted", "elapsed"} else status,
        }
        if record_enc is not None:
            result["recordId"] = self._decrypt_json(user_id, "record_id", record_enc)
        return result

    def unsettled(self, user_id: str, branch: str) -> list[tuple[str, str, list[str]]]:
        rows = self._fetchall(
            "SELECT id, prepared_enc, prior_enc FROM attempts "
            "WHERE user_id = %s AND branch = %s AND status IN ('submitted', 'unknown')",
            (_uuid(user_id), branch),
        )
        result = []
        for attempt, prepared_enc, prior_enc in rows:
            prepared = self._decrypt_json(user_id, "prepared", prepared_enc)
            prior = self._decrypt_json(user_id, "prior", prior_enc) if prior_enc is not None else []
            result.append((str(attempt), json.dumps(prepared, ensure_ascii=False), list(prior)))
        return result

    def begin(
        self, user_id: str, branch: str, attempt: str,
        prepared: Mapping[str, Any], record_ids: Iterable[str],
    ) -> bool:
        prior = [str(record) for record in record_ids]
        try:
            with self.pool.connection() as connection:
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        INSERT INTO attempts (id, user_id, branch, status, prepared_enc, prior_enc)
                        VALUES (%s, %s, %s, 'unknown', %s, %s)
                        ON CONFLICT (id) DO NOTHING
                        """,
                        (
                            _uuid(attempt), _uuid(user_id), branch,
                            self._encrypt_json(user_id, "prepared", prepared),
                            self._encrypt_json(user_id, "prior", prior),
                        ),
                    )
                    return cursor.rowcount == 1
        except psycopg.Error:
            raise JournalError("cannot begin attempt") from None

    def finish_without_sending(self, user_id: str, branch: str, attempt: str, status: str) -> None:
        self._check_status(status)
        try:
            with self.pool.connection() as connection:
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        INSERT INTO attempts (id, user_id, branch, status, prepared_enc)
                        VALUES (%s, %s, %s, %s, NULL)
                        ON CONFLICT (id) DO NOTHING
                        """,
                        (_uuid(attempt), _uuid(user_id), branch, status),
                    )
        except psycopg.Error:
            raise JournalError("cannot record attempt") from None

    def set_status(
        self, user_id: str, branch: str, attempt: str, status: str, record_id: str | None = None,
    ) -> None:
        self._check_status(status)
        keep_prior = status in {"submitted", "unknown"}
        keep_prepared = keep_prior or status == "confirmed"
        record_enc = self._encrypt_json(user_id, "record_id", record_id) if record_id else None
        self._execute(
            """
            UPDATE attempts SET status = %s, record_id_enc = %s,
              prepared_enc = CASE WHEN %s THEN prepared_enc ELSE NULL END,
              prior_enc = CASE WHEN %s THEN prior_enc ELSE NULL END,
              updated_at = now()
            WHERE user_id = %s AND branch = %s AND id = %s
            """,
            (status, record_enc, keep_prepared, keep_prior, _uuid(user_id), branch, _uuid(attempt)),
        )

    def confirmed_details(self, user_id: str, branch: str) -> list[tuple[str, str, str]]:
        rows = self._fetchall(
            "SELECT id, record_id_enc, prepared_enc FROM attempts "
            "WHERE user_id = %s AND branch = %s AND status = 'confirmed' AND prepared_enc IS NOT NULL",
            (_uuid(user_id), branch),
        )
        result = []
        for attempt, record_enc, prepared_enc in rows:
            prepared = self._decrypt_json(user_id, "prepared", prepared_enc)
            record_id = self._decrypt_json(user_id, "record_id", record_enc) if record_enc else ""
            result.append((str(attempt), str(record_id), json.dumps(prepared, ensure_ascii=False)))
        return result

    def claimed_records(self, user_id: str, branch: str) -> set[str]:
        rows = self._fetchall(
            "SELECT record_id_enc FROM attempts "
            "WHERE user_id = %s AND branch = %s AND status = 'confirmed' AND record_id_enc IS NOT NULL",
            (_uuid(user_id), branch),
        )
        return {str(self._decrypt_json(user_id, "record_id", row[0])) for row in rows}

    def forget_details(self, user_id: str, branch: str, attempt: str) -> None:
        self._execute(
            "UPDATE attempts SET prepared_enc = NULL WHERE user_id = %s AND branch = %s AND id = %s",
            (_uuid(user_id), branch, _uuid(attempt)),
        )

    # -- cancellations -------------------------------------------------

    def reservation_key(self, user_id: str, branch: str, reservation: str) -> bytes:
        return self._reservation_hmac(user_id, branch, reservation)

    def find_cancellation(self, user_id: str, branch: str, attempt: str) -> tuple[bytes, str] | None:
        row = self._fetchone(
            "SELECT reservation_key_hmac, status FROM cancellations "
            "WHERE user_id = %s AND branch = %s AND id = %s",
            (_uuid(user_id), branch, _uuid(attempt)),
        )
        if row is None:
            return None
        key, status = row
        return bytes(key), _text(status)

    def begin_cancellation(
        self, user_id: str, branch: str, attempt: str, reservation_key: bytes, status: str = "unknown",
    ) -> bool:
        if status not in CANCELLATION_STATUSES:
            raise JournalError("invalid cancellation status")
        try:
            with self.pool.connection() as connection:
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        INSERT INTO cancellations (id, user_id, branch, reservation_key_hmac, status)
                        VALUES (%s, %s, %s, %s, %s)
                        ON CONFLICT DO NOTHING
                        """,
                        (_uuid(attempt), _uuid(user_id), branch, reservation_key, status),
                    )
                    return cursor.rowcount == 1
        except psycopg.Error:
            raise JournalError("cannot begin cancellation") from None

    def set_cancellation(self, user_id: str, branch: str, attempt: str, status: str) -> None:
        if status not in CANCELLATION_STATUSES:
            raise JournalError("invalid cancellation status")
        self._execute(
            "UPDATE cancellations SET status = %s WHERE user_id = %s AND branch = %s AND id = %s",
            (status, _uuid(user_id), branch, _uuid(attempt)),
        )

    def reconcile_cancellations(
        self, user_id: str, branch: str, present_ids: Iterable[str], cancelled_ids: Iterable[str],
    ) -> set[bytes]:
        present = {self._reservation_hmac(user_id, branch, value) for value in present_ids}
        cancelled = {self._reservation_hmac(user_id, branch, value) for value in cancelled_ids}
        rows = self._fetchall(
            "SELECT id, reservation_key_hmac, status FROM cancellations "
            "WHERE user_id = %s AND branch = %s AND status IN ('submitted', 'unknown')",
            (_uuid(user_id), branch),
        )
        pending: set[bytes] = set()
        for attempt, key, status in rows:
            key = bytes(key)
            status = _text(status)
            if key in cancelled or (status == "submitted" and key not in present):
                self.set_cancellation(user_id, branch, str(attempt), "cancelled")
            elif key not in present:
                self.set_cancellation(user_id, branch, str(attempt), "absent")
            else:
                pending.add(key)
        return pending

    # -- maintenance ---------------------------------------------------

    def cleanup(self, attempts_days: int, audit_days: int) -> None:
        self._execute(
            "DELETE FROM attempts WHERE created_at < now() - make_interval(days => %s)",
            (max(1, attempts_days),),
        )
        self._execute(
            "DELETE FROM cancellations WHERE created_at < now() - make_interval(days => %s)",
            (max(1, attempts_days),),
        )
        self._execute(
            "DELETE FROM audit WHERE created_at < now() - make_interval(days => %s)",
            (max(1, audit_days),),
        )

    def close(self) -> None:
        self.pool.close()

    # -- helpers -------------------------------------------------------

    def _execute(self, sql: str, params: tuple[Any, ...]) -> None:
        try:
            with self.pool.connection() as connection:
                with connection.cursor() as cursor:
                    cursor.execute(sql, params)
        except psycopg.Error:
            raise JournalError("database operation failed") from None

    def _fetchone(self, sql: str, params: tuple[Any, ...]):
        try:
            with self.pool.connection() as connection:
                with connection.cursor() as cursor:
                    cursor.execute(sql, params)
                    return cursor.fetchone()
        except psycopg.Error:
            raise JournalError("database query failed") from None

    def _fetchall(self, sql: str, params: tuple[Any, ...]):
        try:
            with self.pool.connection() as connection:
                with connection.cursor() as cursor:
                    cursor.execute(sql, params)
                    return cursor.fetchall()
        except psycopg.Error:
            raise JournalError("database query failed") from None

    @staticmethod
    def _check_status(status: str) -> None:
        if status not in ATTEMPT_STATUSES:
            raise JournalError("invalid attempt status")


class MemoryDatabase(_CipherMixin):
    """Development/test store. Nothing survives a restart."""

    def __init__(self, cipher: DataCipher, secret: bytes):
        self.cipher = cipher
        self.secret = secret
        self.users: dict[bytes, dict[str, Any]] = {}
        self.attempts: dict[str, dict[str, Any]] = {}
        self.cancellations: dict[str, dict[str, Any]] = {}
        self.audit_rows: list[dict[str, Any]] = []

    def ping(self) -> bool:
        return True

    def ensure_schema(self) -> None:
        return None

    def get_or_create_user(self, identity_hmac: bytes, consent_version: str) -> str:
        row = self.users.get(identity_hmac)
        if row is None:
            row = {"id": str(uuid.uuid4()), "consent_version": consent_version}
            self.users[identity_hmac] = row
        row["consent_version"] = consent_version
        return row["id"]

    def touch_user(self, user_id: str) -> None:
        return None

    def delete_user(self, user_id: str) -> None:
        self.users = {key: row for key, row in self.users.items() if row["id"] != user_id}
        self.attempts = {key: row for key, row in self.attempts.items() if row["user_id"] != user_id}
        self.cancellations = {
            key: row for key, row in self.cancellations.items() if row["user_id"] != user_id
        }

    def audit(
        self, user_id: str | None, action: str,
        ip_hash: bytes | None, ua_hash: bytes | None, meta: Mapping[str, Any] | None = None,
    ) -> None:
        if not re.fullmatch(r"[a-z_]{3,48}", action):
            raise JournalError("invalid audit action")
        self.audit_rows.append({
            "user_id": user_id, "action": action, "ip_hash": ip_hash, "ua_hash": ua_hash, "meta": meta,
        })

    def find(self, user_id: str, branch: str, attempt: str) -> dict[str, Any] | None:
        row = self.attempts.get(_uuid(attempt))
        if row is None or row["user_id"] != user_id or row["branch"] != branch:
            return None
        status = row["status"]
        result = {"id": attempt, "status": "unknown" if status in {"submitted", "elapsed"} else status}
        if row.get("record_id"):
            result["recordId"] = row["record_id"]
        return result

    def unsettled(self, user_id: str, branch: str) -> list[tuple[str, str, list[str]]]:
        return [
            (row["id"], json.dumps(row["prepared"], ensure_ascii=False), list(row["prior"]))
            for row in self.attempts.values()
            if row["user_id"] == user_id and row["branch"] == branch
            and row["status"] in {"submitted", "unknown"}
        ]

    def begin(
        self, user_id: str, branch: str, attempt: str,
        prepared: Mapping[str, Any], record_ids: Iterable[str],
    ) -> bool:
        attempt = _uuid(attempt)
        if attempt in self.attempts:
            return False
        self.attempts[attempt] = {
            "id": attempt, "user_id": user_id, "branch": branch, "status": "unknown",
            "prepared": dict(prepared), "prior": [str(record) for record in record_ids],
        }
        return True

    def finish_without_sending(self, user_id: str, branch: str, attempt: str, status: str) -> None:
        attempt = _uuid(attempt)
        self._check_status(status)
        if attempt not in self.attempts:
            self.attempts[attempt] = {
                "id": attempt, "user_id": user_id, "branch": branch, "status": status,
                "prepared": None, "prior": None,
            }

    def set_status(
        self, user_id: str, branch: str, attempt: str, status: str, record_id: str | None = None,
    ) -> None:
        self._check_status(status)
        row = self.attempts.get(_uuid(attempt))
        if row is None or row["user_id"] != user_id or row["branch"] != branch:
            return None
        row["status"] = status
        row["record_id"] = record_id
        if status not in {"submitted", "unknown"}:
            row["prior"] = None
            if status != "confirmed":
                row["prepared"] = None
        return None

    def confirmed_details(self, user_id: str, branch: str) -> list[tuple[str, str, str]]:
        return [
            (row["id"], str(row.get("record_id") or ""), json.dumps(row["prepared"], ensure_ascii=False))
            for row in self.attempts.values()
            if row["user_id"] == user_id and row["branch"] == branch
            and row["status"] == "confirmed" and row["prepared"] is not None
        ]

    def claimed_records(self, user_id: str, branch: str) -> set[str]:
        return {
            str(row["record_id"]) for row in self.attempts.values()
            if row["user_id"] == user_id and row["branch"] == branch
            and row["status"] == "confirmed" and row.get("record_id")
        }

    def forget_details(self, user_id: str, branch: str, attempt: str) -> None:
        row = self.attempts.get(_uuid(attempt))
        if row and row["user_id"] == user_id and row["branch"] == branch:
            row["prepared"] = None

    def reservation_key(self, user_id: str, branch: str, reservation: str) -> bytes:
        return self._reservation_hmac(user_id, branch, reservation)

    def find_cancellation(self, user_id: str, branch: str, attempt: str) -> tuple[bytes, str] | None:
        row = self.cancellations.get(_uuid(attempt))
        if row is None or row["user_id"] != user_id or row["branch"] != branch:
            return None
        return row["key"], row["status"]

    def begin_cancellation(
        self, user_id: str, branch: str, attempt: str, reservation_key: bytes, status: str = "unknown",
    ) -> bool:
        attempt = _uuid(attempt)
        if status not in CANCELLATION_STATUSES:
            raise JournalError("invalid cancellation status")
        if any(
            row["user_id"] == user_id and row["branch"] == branch and row["key"] == reservation_key
            and row["status"] in {"submitted", "unknown"}
            for row in self.cancellations.values()
        ):
            return False
        if attempt in self.cancellations:
            return False
        self.cancellations[attempt] = {
            "id": attempt, "user_id": user_id, "branch": branch, "key": reservation_key, "status": status,
        }
        return True

    def set_cancellation(self, user_id: str, branch: str, attempt: str, status: str) -> None:
        if status not in CANCELLATION_STATUSES:
            raise JournalError("invalid cancellation status")
        row = self.cancellations.get(_uuid(attempt))
        if row and row["user_id"] == user_id and row["branch"] == branch:
            row["status"] = status

    def reconcile_cancellations(
        self, user_id: str, branch: str, present_ids: Iterable[str], cancelled_ids: Iterable[str],
    ) -> set[bytes]:
        present = {self._reservation_hmac(user_id, branch, value) for value in present_ids}
        cancelled = {self._reservation_hmac(user_id, branch, value) for value in cancelled_ids}
        pending: set[bytes] = set()
        for attempt, row in list(self.cancellations.items()):
            if row["user_id"] != user_id or row["branch"] != branch:
                continue
            if row["status"] not in {"submitted", "unknown"}:
                continue
            key = row["key"]
            if key in cancelled or (row["status"] == "submitted" and key not in present):
                self.set_cancellation(user_id, branch, attempt, "cancelled")
            elif key not in present:
                self.set_cancellation(user_id, branch, attempt, "absent")
            else:
                pending.add(key)
        return pending

    def cleanup(self, attempts_days: int, audit_days: int) -> None:
        return None

    def close(self) -> None:
        return None

    @staticmethod
    def _check_status(status: str) -> None:
        if status not in ATTEMPT_STATUSES:
            raise JournalError("invalid attempt status")


def build_database(settings: Settings, cipher: DataCipher) -> Database:
    if settings.database_url:
        return PostgresDatabase(settings.database_url, cipher, settings.session_secret)
    if settings.production:
        raise ConfigError("DATABASE_URL is required in production")
    return MemoryDatabase(cipher, settings.session_secret)
