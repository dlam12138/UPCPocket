"""Cross-language check: browser WebCrypto envelope decrypts on the server."""

from __future__ import annotations

import base64
import json
import os
import shutil
import subprocess
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from app.crypto import KeyRing

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "web" / "login-crypto.js"


@pytest.mark.skipif(not MODULE.is_file(), reason="web/login-crypto.js is not available")
@pytest.mark.skipif(shutil.which("node") is None, reason="node is not available")
def test_browser_envelope_decrypts_on_server(tmp_path):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    spki = key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    script = (
        "const module = await import('file://' + process.env.UPCP_MODULE);\n"
        "const out = await module.encryptCredentials(process.env.UPCP_SPKI, {\n"
        "  username: process.env.UPCP_USER, password: process.env.UPCP_PASSWORD,\n"
        "  nonce: process.env.UPCP_NONCE,\n"
        "});\n"
        "process.stdout.write(JSON.stringify(out));\n"
    )
    environment = {
        **os.environ,
        "UPCP_MODULE": str(MODULE),
        "UPCP_SPKI": base64.b64encode(spki).decode("ascii"),
        "UPCP_USER": "20230001",
        "UPCP_PASSWORD": "interop-secret-value",
        "UPCP_NONCE": "interop_nonce_1",
    }
    result = subprocess.run(
        ["node", "--input-type=module", "-e", script],
        capture_output=True, text=True, timeout=60, env=environment,
    )
    assert result.returncode == 0, result.stderr
    envelope = json.loads(result.stdout)
    assert set(envelope) == {"wrapped", "iv", "ciphertext"}
    keyring = KeyRing({"interop": key}, "interop")
    username, password, nonce = keyring.decrypt_login({**envelope, "kid": "interop"})
    assert (username, password, nonce) == ("20230001", "interop-secret-value", "interop_nonce_1")
