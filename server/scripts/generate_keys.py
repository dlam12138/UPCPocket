#!/usr/bin/env python3
"""Generate the service secrets: login RSA key, data key and session secret.

Run from the repository root with the service environment (the script needs
`cryptography`):

    server/.venv/bin/python server/scripts/generate_keys.py --out deploy/secrets --kid 2026-11

Layout (matches deploy/docker-compose.yml):

    deploy/secrets/login_keys/<kid>.pem   LOGIN_KEY_DIR
    deploy/secrets/data_key                DATA_KEY_FILE
    deploy/secrets/session_secret          SESSION_SECRET_FILE

The private key is written unencrypted with mode 0600 and is never printed. Do
not commit the output; `deploy/secrets/` is ignored by Git.
"""

from __future__ import annotations

import argparse
import base64
import os
import re
import secrets
import stat
import sys
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa


def _write_new(path: Path, data: bytes) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as handle:
        handle.write(data)
    os.chmod(path, 0o600)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default="deploy/secrets", help="output directory")
    parser.add_argument("--kid", default="2026-11", help="login key id, e.g. 2026-11")
    parser.add_argument("--key-size", type=int, default=3072, choices=(2048, 3072, 4096))
    parser.add_argument("--force", action="store_true", help="replace existing files")
    args = parser.parse_args(argv)

    if not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", args.kid):
        parser.error("--kid must match [A-Za-z0-9._-]{1,64}")
    directory = Path(args.out).expanduser()
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(directory, stat.S_IRWXU)

    keys_dir = directory / "login_keys"
    keys_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(keys_dir, stat.S_IRWXU)
    targets = {
        "key": keys_dir / f"{args.kid}.pem",
        "data": directory / "data_key",
        "session": directory / "session_secret",
    }
    existing = [str(path) for path in targets.values() if path.exists()]
    if existing and not args.force:
        print(f"refusing to overwrite existing files: {', '.join(existing)}", file=sys.stderr)
        print("rerun with --force only after a rotation plan", file=sys.stderr)
        return 2
    for path in targets.values():
        if path.exists():
            path.unlink()

    key = rsa.generate_private_key(public_exponent=65537, key_size=args.key_size)
    private_pem = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    spki = key.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    _write_new(targets["key"], private_pem)
    _write_new(targets["data"], secrets.token_bytes(32))
    _write_new(targets["session"], secrets.token_bytes(32))

    print(f"login key id: {args.kid}")
    print(f"login key:    {targets['key']}")
    print(f"keys dir:     {keys_dir}  (LOGIN_KEY_DIR)")
    print(f"SPKI base64:  {base64.b64encode(spki).decode('ascii')}")
    print("data key:     " + str(targets["data"]))
    print("session:      " + str(targets["session"]))
    print("Set LOGIN_KEY_DIR to the keys dir, keep files mode 0600 and owner-readable only.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
