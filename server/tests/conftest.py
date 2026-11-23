"""Test fixtures: real protocol modules, synthetic school, real crypto."""

from __future__ import annotations

import base64
import dataclasses
import json
import os
import sys
from datetime import datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from zoneinfo import ZoneInfo

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "server"))

from app.cache import MemoryCache, PublicCache  # noqa: E402
from app.config import Settings  # noqa: E402
from app.crypto import DataCipher, KeyRing, b64u_encode  # noqa: E402
from app.library.journal import MemoryDatabase  # noqa: E402
from app.main import create_app  # noqa: E402
from app.security import MemoryRateLimiter, RateLimiter  # noqa: E402
from app.sessions import MemoryBackend  # noqa: E402
from app.upstream.bridge import load as load_upstream  # noqa: E402
from fake_upstream import FakeSchool, build_keyring, make_upstream  # noqa: E402

CHINA = ZoneInfo("Asia/Shanghai")


@pytest.fixture(scope="session")
def keyring() -> KeyRing:
    return build_keyring()


@pytest.fixture
def settings() -> Settings:
    return Settings.from_env({
        "ENV": "development",
        "PUBLIC_ORIGIN": "http://testserver",
        "SERVE_STATIC": "false",
        "ALLOW_EPHEMERAL_KEYS": "true",
        "RATE_LIMIT_ENABLED": "false",
        "SCHOOL_ACCESS_MODE": "station",
        "LOGIN_RATE_LIMIT": "100",
        "SESSION_IDLE_SECONDS": "300",
        "SESSION_ABSOLUTE_SECONDS": "3600",
        "LIBRARY_SESSION_IDLE_SECONDS": "300",
        "LIBRARY_SESSION_ABSOLUTE_SECONDS": "3600",
        "UPSTREAM_TIMEOUT_SECONDS": "5",
        "UPSTREAM_CONCURRENCY": "4",
    })


def encrypt_login(
    keyring: KeyRing, username: str, password: str, nonce: str = "test_nonce_value",
) -> dict:
    public = keyring.public_payload()
    public_key = serialization.load_der_public_key(base64.b64decode(public["spki"]))
    aes_key = os.urandom(32)
    iv = os.urandom(12)
    wrapped = public_key.encrypt(
        aes_key,
        padding.OAEP(mgf=padding.MGF1(algorithm=hashes.SHA256()),
                     algorithm=hashes.SHA256(), label=None),
    )
    ciphertext = AESGCM(aes_key).encrypt(
        iv,
        json.dumps({"username": username, "password": password, "nonce": nonce}).encode("utf-8"),
        None,
    )
    return {
        "kid": public["kid"],
        "wrapped": b64u_encode(wrapped),
        "iv": b64u_encode(iv),
        "ciphertext": b64u_encode(ciphertext),
    }


def build_client(settings, keyring, *, school=None, rate_limit_enabled=None):
    school = school or FakeSchool()
    upstream = make_upstream(load_upstream(settings.upstream_path), school)
    cipher = DataCipher.from_settings(settings)
    store = MemoryBackend(settings)
    database = MemoryDatabase(cipher, settings.session_secret)
    cache = PublicCache(MemoryCache(), settings)
    enabled = settings.rate_limit_enabled if rate_limit_enabled is None else rate_limit_enabled
    limiter = RateLimiter(MemoryRateLimiter(), enabled)
    app = create_app(
        settings, upstream=upstream, store=store, database=database,
        cache=cache, limiter=limiter, keyring=keyring,
    )
    client = TestClient(app, raise_server_exceptions=False, base_url="http://testserver")
    return client, SimpleNamespace(
        app=app, client=client, school=school, settings=settings,
        keyring=keyring, store=store, database=database, cache=cache,
    )


@pytest.fixture
def env(settings, keyring):
    client, environment = build_client(settings, keyring)
    with client:
        yield environment


def login_payload(
    client: TestClient, keyring: KeyRing, *, username: str = "20230001",
    password: str = "correct", branch: str = "tangdaowan", consent: bool = True,
) -> dict:
    import secrets

    key = client.get("/api/auth/key").json()
    nonce = secrets.token_urlsafe(32)
    payload = encrypt_login(keyring, username, password, nonce=nonce)
    payload.update({"nonce": nonce, "branch": branch, "consent": consent})
    return payload


def login(client: TestClient, keyring: KeyRing, *, username: str = "20230001",
          password: str = "correct", branch: str = "tangdaowan", consent: bool = True):
    return client.post(
        "/api/auth/login",
        json=login_payload(
            client, keyring, username=username, password=password,
            branch=branch, consent=consent,
        ),
    )


def ready(client: TestClient, keyring: KeyRing, *, username: str = "20230001",
          branch: str = "tangdaowan") -> tuple[str, str]:
    response = login(client, keyring, username=username, branch=branch)
    assert response.status_code == 200, response.text
    state = response.json()
    return state["revision"], state["csrf"]


def session_path(branch: str, endpoint: str) -> str:
    return f"/api/library/{branch}/{endpoint}" if branch != "tangdaowan" else f"/api/library/{endpoint}"


def catalog_params(branch: str = "tangdaowan") -> dict[str, str]:
    tomorrow = (datetime.now(CHINA).date() + timedelta(days=1)).isoformat()
    return {"building": "b1", "date": tomorrow}
