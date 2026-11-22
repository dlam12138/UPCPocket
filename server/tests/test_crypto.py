"""Crypto unit tests: envelope login and AES-GCM data at rest."""

from __future__ import annotations

import os

import pytest
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from app.crypto import (
    CryptoError, DataCipher, KeyRing, aad, b64u_decode, b64u_encode, hmac_bytes,
)
from conftest import encrypt_login


def test_envelope_roundtrip(keyring: KeyRing):
    envelope = encrypt_login(keyring, "20230001", "correct horse", nonce="abc_NONCE-1")
    assert keyring.decrypt_login(envelope) == ("20230001", "correct horse", "abc_NONCE-1")
    payload = keyring.public_payload()
    assert payload["kid"] == "test-kid"
    assert payload["spki"]


def test_envelope_rejects_wrong_kid(keyring: KeyRing):
    envelope = encrypt_login(keyring, "20230001", "correct")
    envelope["kid"] = "other-kid"
    with pytest.raises(CryptoError):
        keyring.decrypt_login(envelope)


def test_envelope_rejects_tampering(keyring: KeyRing):
    envelope = encrypt_login(keyring, "20230001", "correct")
    envelope["ciphertext"] = envelope["ciphertext"][:-4] + "AAAA"
    with pytest.raises(CryptoError):
        keyring.decrypt_login(envelope)
    envelope = encrypt_login(keyring, "20230001", "correct")
    envelope["iv"] = b64u_encode(os.urandom(11))
    with pytest.raises(CryptoError):
        keyring.decrypt_login(envelope)


def test_envelope_requires_nonce(keyring: KeyRing):
    envelope = encrypt_login(keyring, "20230001", "correct", nonce="n1")
    assert keyring.decrypt_login(envelope)[2] == "n1"
    envelope = encrypt_login(keyring, "20230001", "correct", nonce="bad nonce")
    with pytest.raises(CryptoError):
        keyring.decrypt_login(envelope)


def test_envelope_rejects_extra_fields(keyring: KeyRing):
    envelope = encrypt_login(keyring, "20230001", "correct")
    envelope["branch"] = "tangdaowan"
    with pytest.raises(CryptoError):
        keyring.decrypt_login(envelope)


def test_envelope_rejects_bad_payload(keyring: KeyRing):
    public = keyring.public_payload()
    import base64
    import json

    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding

    key = os.urandom(32)
    iv = os.urandom(12)
    public_key = serialization.load_der_public_key(base64.b64decode(public["spki"]))
    wrapped = public_key.encrypt(
        key, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None),
    )
    for payload in (
        b'{"username":"a","password":"b","extra":1}',
        b'{"username":"a","password":"b"}',
        b'["a"]',
        b"not json",
    ):
        envelope = {
            "kid": public["kid"],
            "wrapped": b64u_encode(wrapped),
            "iv": b64u_encode(iv),
            "ciphertext": b64u_encode(AESGCM(key).encrypt(iv, payload, None)),
        }
        with pytest.raises(CryptoError):
            keyring.decrypt_login(envelope)


def test_base64url_is_strict():
    assert b64u_decode(b64u_encode(b"\x00\xffhi"), field="x", max_len=64) == b"\x00\xffhi"
    for value in ("a b", "abc=", "a+b/", "A"):
        with pytest.raises(CryptoError):
            b64u_decode(value, field="x", max_len=64)


def test_data_cipher_aad_binding(settings):
    cipher = DataCipher.from_settings(settings)
    token = cipher.encrypt(b"secret-token", aad("user-a", "redis", "library"))
    assert cipher.decrypt(token, aad("user-a", "redis", "library")) == b"secret-token"
    with pytest.raises(CryptoError):
        cipher.decrypt(token, aad("user-b", "redis", "library"))
    with pytest.raises(CryptoError):
        cipher.decrypt(token[:-1] + bytes([token[-1] ^ 1]), aad("user-a", "redis", "library"))


def test_data_cipher_versions(settings):
    key = os.urandom(32)
    cipher = DataCipher({1: key}, 1)
    token = cipher.encrypt(b"value", b"aad")
    assert token[0] == 1 and token[1] == 1
    assert cipher.decrypt(token, b"aad") == b"value"
    with pytest.raises(CryptoError):
        DataCipher({2: key}, 2).decrypt(token, b"aad")
    with pytest.raises(CryptoError):
        cipher.decrypt(b"", b"aad")


def test_identity_hashes_are_stable_and_distinct(settings):
    secret = settings.session_secret
    assert hmac_bytes(secret, "identity", "20230001") == hmac_bytes(secret, "identity", "20230001")
    assert hmac_bytes(secret, "identity", "20230001") != hmac_bytes(secret, "identity", "20230002")
    assert len(hmac_bytes(secret, "identity", "20230001")) == 32
