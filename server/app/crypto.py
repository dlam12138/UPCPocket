"""Envelope encryption for login requests and AES-GCM for data at rest.

The login envelope keeps the password out of TLS-terminating proxy logs, APM
sampling and request-body capture. It does not protect against a fully
compromised application host: the service must decrypt once to talk to CAS.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import json
import os
import re
import secrets
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from .config import ConfigError, Settings


_B64U = re.compile(r"[A-Za-z0-9_-]*")
MAX_WRAPPED = 1024
MAX_CIPHERTEXT = 8192
ENVELOPE_KEYS = {"kid", "wrapped", "iv", "ciphertext"}
FORMAT_VERSION = 1


class CryptoError(Exception):
    def __init__(self, code: str = "invalid_envelope"):
        self.code = code
        super().__init__(code)


def b64u_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def b64u_decode(text: str, *, field: str, max_len: int) -> bytes:
    if not isinstance(text, str) or not text or len(text) > max_len or not _B64U.fullmatch(text):
        raise CryptoError()
    if len(text) % 4 == 1:
        raise CryptoError()
    try:
        raw = base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))
    except (binascii.Error, ValueError):
        raise CryptoError() from None
    return raw


def b64_encode(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def hmac_bytes(secret: bytes, *parts: str) -> bytes:
    message = "\x1f".join(parts).encode("utf-8")
    return hmac.new(secret, message, hashlib.sha256).digest()


def _wipe(buffer: bytearray) -> None:
    buffer[:] = b"\x00" * len(buffer)


def _load_private_key(path: Path) -> rsa.RSAPrivateKey:
    try:
        data = path.read_bytes()
        key = serialization.load_pem_private_key(data, password=None)
    except (OSError, ValueError, TypeError):
        raise ConfigError(f"cannot load login key {path.name}") from None
    if not isinstance(key, rsa.RSAPrivateKey):
        raise ConfigError(f"login key {path.name} is not RSA")
    if key.key_size < 2048:
        raise ConfigError(f"login key {path.name} is too small")
    return key


class KeyRing:
    """Current RSA-OAEP login key plus older keys kept for rotation."""

    def __init__(self, keys: Mapping[str, rsa.RSAPrivateKey], current: str):
        if current not in keys:
            raise ConfigError("LOGIN_KEY_ID is not available")
        self._keys = dict(keys)
        self.current = current
        self._public: dict[str, str] | None = None

    @classmethod
    def from_settings(cls, settings: Settings) -> "KeyRing":
        keys: dict[str, rsa.RSAPrivateKey] = {}
        if settings.login_private_key_file is not None:
            keys[settings.login_key_id] = _load_private_key(settings.login_private_key_file)
        directory = settings.login_key_dir
        if directory.is_dir():
            for path in sorted(directory.glob("*.pem")):
                kid = path.stem
                if re.fullmatch(r"[A-Za-z0-9._\-]{1,64}", kid) and kid not in keys:
                    keys[kid] = _load_private_key(path)
        if not keys:
            if not settings.allow_ephemeral_keys:
                raise ConfigError("no login keys configured")
            keys[settings.login_key_id] = rsa.generate_private_key(
                public_exponent=65537, key_size=3072,
            )
        return cls(keys, settings.login_key_id)

    def public_payload(self) -> dict[str, str]:
        if self._public is not None:
            return dict(self._public)
        key = self._keys[self.current]
        spki = key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        self._public = {
            "kid": self.current,
            "spki": b64_encode(spki),
            "alg": "RSA-OAEP-256",
            "enc": "AES-256-GCM",
        }
        return dict(self._public)

    def decrypt_login(self, envelope: Mapping[str, object]) -> tuple[str, str, str]:
        if not isinstance(envelope, Mapping) or set(envelope) != ENVELOPE_KEYS:
            raise CryptoError()
        kid = envelope["kid"]
        if not isinstance(kid, str) or kid not in self._keys:
            raise CryptoError()
        wrapped = b64u_decode(envelope["wrapped"], field="wrapped", max_len=MAX_WRAPPED)
        iv = b64u_decode(envelope["iv"], field="iv", max_len=64)
        if len(iv) != 12:
            raise CryptoError()
        ciphertext = b64u_decode(envelope["ciphertext"], field="ciphertext", max_len=MAX_CIPHERTEXT)
        if len(ciphertext) < 17:
            raise CryptoError()
        try:
            key = self._keys[kid].decrypt(
                wrapped,
                padding.OAEP(mgf=padding.MGF1(algorithm=hashes.SHA256()),
                             algorithm=hashes.SHA256(), label=None),
            )
        except (ValueError, TypeError):
            raise CryptoError() from None
        if len(key) != 32:
            raise CryptoError()
        key_buffer = bytearray(key)
        try:
            plain = AESGCM(bytes(key_buffer)).decrypt(iv, ciphertext, None)
        except (InvalidTag, ValueError):
            raise CryptoError() from None
        finally:
            _wipe(key_buffer)
        buffer = bytearray(plain)
        try:
            payload = json.loads(buffer.decode("utf-8"))
        except (UnicodeDecodeError, ValueError, RecursionError):
            raise CryptoError() from None
        finally:
            _wipe(buffer)
        if not isinstance(payload, dict) or set(payload) != {"username", "password", "nonce"}:
            raise CryptoError()
        username, password, nonce = payload["username"], payload["password"], payload["nonce"]
        if not all(isinstance(value, str) for value in (username, password, nonce)):
            raise CryptoError()
        username = username.strip()
        if not 1 <= len(username) <= 256 or not 1 <= len(password) <= 1024:
            raise CryptoError()
        if not 1 <= len(nonce) <= 64 or not _B64U.fullmatch(nonce):
            raise CryptoError()
        return username, password, nonce


class DataCipher:
    """AES-256-GCM for Redis values and database columns, with key versioning."""

    def __init__(self, keys: Mapping[int, bytes], current: int):
        if current not in keys:
            raise ConfigError("current data key is missing")
        for version, key in keys.items():
            if not isinstance(version, int) or not 1 <= version <= 255 or len(key) != 32:
                raise ConfigError("invalid data key")
        self._keys = {version: AESGCM(key) for version, key in keys.items()}
        self.current = current

    @classmethod
    def from_settings(cls, settings: Settings) -> "DataCipher":
        keys = {settings.data_key_version: settings.data_key}
        for version, key in settings.old_data_keys:
            keys.setdefault(version, key)
        return cls(keys, settings.data_key_version)

    def encrypt(self, plaintext: bytes, aad: bytes) -> bytes:
        nonce = secrets.token_bytes(12)
        header = bytes([FORMAT_VERSION, self.current])
        return header + nonce + self._keys[self.current].encrypt(nonce, plaintext, aad)

    def decrypt(self, token: bytes, aad: bytes) -> bytes:
        if not isinstance(token, (bytes, bytearray)) or len(token) < 30:
            raise CryptoError("invalid_ciphertext")
        token = bytes(token)
        if token[0] != FORMAT_VERSION or token[1] not in self._keys:
            raise CryptoError("invalid_ciphertext")
        nonce, ciphertext = token[2:14], token[14:]
        try:
            return self._keys[token[1]].decrypt(nonce, ciphertext, aad)
        except (InvalidTag, ValueError):
            raise CryptoError("invalid_ciphertext") from None

    def encrypt_json(self, value: object, aad: bytes) -> bytes:
        payload = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        return self.encrypt(payload, aad)

    def decrypt_json(self, token: bytes, aad: bytes) -> object:
        try:
            return json.loads(self.decrypt(token, aad).decode("utf-8"))
        except (UnicodeDecodeError, ValueError, RecursionError):
            raise CryptoError("invalid_ciphertext") from None


def aad(*parts: str) -> bytes:
    return "\x1f".join(parts).encode("utf-8")


def new_secret(size: int = 32) -> str:
    return secrets.token_urlsafe(size)
