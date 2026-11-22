#!/usr/bin/env python3
"""Local-only browser harness: the real app with a synthetic school backend.

This is NOT a deployment entry point. It exists so WebKit/Playwright can verify
the login dialog and booking UI without real school credentials. It refuses to
run when ENV=production and is excluded from the container image.
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "server"))
sys.path.insert(0, str(ROOT / "server" / "tests"))

import uvicorn  # noqa: E402

from app.config import Settings  # noqa: E402
from app.main import create_app  # noqa: E402
from app.upstream.bridge import load as load_upstream  # noqa: E402
from fake_upstream import FakeSchool, build_keyring, make_upstream  # noqa: E402


LOOPBACK = {"127.0.0.1", "::1", "localhost"}


def main(argv: list[str]) -> int:
    if os.environ.get("ENV", "").strip().lower() == "production":
        print("refusing to run the synthetic harness with ENV=production", file=sys.stderr)
        return 2
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args(argv)
    if args.host not in LOOPBACK:
        print("refusing to bind the synthetic harness outside loopback", file=sys.stderr)
        return 2
    settings = Settings.from_env({
        "ENV": "development",
        "PUBLIC_ORIGIN": f"http://{args.host}:{args.port}",
        "SERVE_STATIC": "true",
        "ALLOW_EPHEMERAL_KEYS": "true",
        "RATE_LIMIT_ENABLED": "false",
        "BOOKING_ENABLED": "true",
        "LOG_LEVEL": "INFO",
    })
    school = FakeSchool()
    upstream = make_upstream(load_upstream(settings.upstream_path), school)
    app = create_app(settings, upstream=upstream, keyring=build_keyring())
    uvicorn.run(app, host=args.host, port=args.port, log_level="warning", access_log=False)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
