#!/usr/bin/env python3
"""Delete one user's server-side data by school login name.

The service stores only HMAC(identity), so deletion is recomputed from the
login name supplied interactively. Booking journal rows cascade; audit rows
keep their action but lose the user reference. This is the operator path for a
data-deletion request; it does not contact the school.
"""

from __future__ import annotations

import argparse
import getpass
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import asyncio  # noqa: E402

import psycopg  # noqa: E402

from app.config import Settings  # noqa: E402
from app.crypto import hmac_bytes  # noqa: E402
from app.sessions import RedisBackend  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--username", help="school login name; omit to be prompted")
    parser.add_argument("--confirm", action="store_true", help="required to delete")
    args = parser.parse_args()
    if not args.confirm:
        print("refusing to delete without --confirm", file=sys.stderr)
        return 2
    settings = Settings.from_env()
    if not settings.database_url:
        print("DATABASE_URL is not configured", file=sys.stderr)
        return 2
    username = (args.username or getpass.getpass("school login name: ")).strip()
    if not username:
        print("empty login name", file=sys.stderr)
        return 2
    identity = hmac_bytes(settings.session_secret, "identity", username)
    with psycopg.connect(settings.database_url, autocommit=True) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT id FROM users WHERE identity_hmac = %s", (identity,))
            rows = cursor.fetchall()
    user_ids = [str(row[0]) for row in rows]
    # Invalidate live sessions and encrypted library tokens first, so a deleted
    # account cannot keep using a Redis session until its TTL expires.
    if settings.redis_url and user_ids:
        backend = RedisBackend(settings.redis_url, settings)

        async def purge_sessions() -> None:
            try:
                for user_id in user_ids:
                    await backend.drop_user_data(user_id)
            finally:
                await backend.close()

        asyncio.run(purge_sessions())
    with psycopg.connect(settings.database_url, autocommit=True) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "DELETE FROM users WHERE identity_hmac = %s RETURNING id", (identity,),
            )
            deleted = len(cursor.fetchall())
    print(f"deleted {deleted} user record(s); attempts/cancellations cascaded; sessions revoked")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
