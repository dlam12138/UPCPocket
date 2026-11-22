#!/usr/bin/env python3
"""Apply the database schema manually (DB_AUTO_MIGRATE alternative)."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import Settings  # noqa: E402
from app.crypto import DataCipher  # noqa: E402
from app.library.journal import build_database  # noqa: E402


def main() -> int:
    settings = Settings.from_env()
    if not settings.database_url:
        print("DATABASE_URL is not configured", file=sys.stderr)
        return 2
    database = build_database(settings, DataCipher.from_settings(settings))
    try:
        database.ensure_schema()
        print("database schema is up to date")
    finally:
        database.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
