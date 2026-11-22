"""End-to-end API tests against the synthetic school and memory backends."""

from __future__ import annotations

import dataclasses
import logging
import uuid
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from conftest import build_client, login, login_payload, ready

CHINA = ZoneInfo("Asia/Shanghai")
TOMORROW = (datetime.now(CHINA).date() + timedelta(days=1)).isoformat()
BOOK = {
    "building": "b1", "date": TOMORROW, "room": "r1",
    "seat": "s1", "start": "09:00", "end": "10:00",
}
BOOK_HEADERS_EXTRA = {}


def _headers(revision: str, csrf: str | None = None) -> dict:
    headers = {"X-Library-Session": revision}
    if csrf is not None:
        headers["X-CSRF-Token"] = csrf
    return headers


def _book(client, revision: str, csrf: str, attempt: str | None = None):
    payload = {"id": attempt or str(uuid.uuid4()), **BOOK}
    return client.post(
        "/api/library/book", json=payload, headers=_headers(revision, csrf),
    )


def test_auth_key_shape(env):
    response = env.client.get("/api/auth/key")
    assert response.status_code == 200
    data = response.json()
    assert set(data) == {"kid", "spki", "alg", "enc"}
    assert data["alg"] == "RSA-OAEP-256"
    assert data["enc"] == "AES-256-GCM"
    assert isinstance(data["spki"], str) and len(data["spki"]) > 100
    assert response.headers["cache-control"] == "no-store"


def test_login_flow_and_session(env):
    revision, csrf = ready(env.client, env.keyring)
    session = env.client.get("/api/library/session")
    assert session.status_code == 200
    assert session.json()["state"] == "ready"
    assert session.json()["recordsAvailable"] is True
    state = env.client.get("/api/session").json()
    assert state["state"] == "ready"
    assert state["branches"] == {"tangdaowan": True, "east": False, "qiushi": False}
    assert state["revision"] == revision and state["csrf"] == csrf
    # A branch login rotates the browser session cookie and revision.
    east = login(env.client, env.keyring, branch="east")
    assert east.status_code == 200
    assert east.json()["revision"] != revision
    state = env.client.get("/api/session").json()
    assert state["branches"] == {"tangdaowan": True, "east": True, "qiushi": False}


def test_login_rejections(env):
    response = env.client.post(
        "/api/auth/login", json=login_payload(env.client, env.keyring, password="wrong"),
    )
    assert response.status_code == 401
    assert response.json() == {"error": "invalid_credentials"}

    response = env.client.post(
        "/api/auth/login", json=login_payload(env.client, env.keyring, password="challenge"),
    )
    assert response.status_code == 409
    assert response.json() == {"error": "interaction_required"}

    response = env.client.post(
        "/api/auth/login",
        json=login_payload(env.client, env.keyring, password="correct", consent=False),
    )
    assert response.status_code == 400
    assert response.json() == {"error": "consent_required"}

    payload = login_payload(env.client, env.keyring, password="correct")
    payload["ciphertext"] = payload["ciphertext"][:-4] + "AAAA"
    response = env.client.post("/api/auth/login", json=payload)
    assert response.status_code == 400


def test_login_envelope_cannot_be_replayed(env):
    payload = login_payload(env.client, env.keyring, password="correct")
    first = env.client.post("/api/auth/login", json=payload)
    assert first.status_code == 200
    replay = env.client.post("/api/auth/login", json=payload)
    assert replay.status_code == 400
    assert replay.json() == {"error": "invalid_request"}


def test_login_nonce_must_match_ciphertext(env):
    key = env.client.get("/api/auth/key").json()
    from conftest import encrypt_login

    # The nonce inside the ciphertext must equal the top-level nonce.
    payload = encrypt_login(env.keyring, "20230001", "correct", nonce="inside_nonce_1")
    payload.update({
        "nonce": "different_nonce_value", "branch": "tangdaowan", "consent": True,
    })
    response = env.client.post("/api/auth/login", json=payload)
    assert response.status_code == 400
    assert response.json() == {"error": "invalid_request"}


def test_catalog_and_session_guards(env):
    assert env.client.get("/api/library/catalog").status_code == 401
    revision, csrf = ready(env.client, env.keyring)
    params = {"building": "b1", "date": TOMORROW}
    assert env.client.get("/api/library/catalog", params=params).status_code == 409
    response = env.client.get("/api/library/catalog", params=params, headers=_headers(revision))
    assert response.status_code == 200
    assert response.json()["rooms"] == [{"id": "r1", "label": "A区"}]
    # Selected seat and time levels.
    response = env.client.get(
        "/api/library/catalog",
        params={**params, "room": "r1"}, headers=_headers(revision),
    )
    assert response.json()["layout"] == {"rows": 1, "cols": 1}
    response = env.client.get(
        "/api/library/catalog",
        params={**params, "room": "r1", "seat": "s1"}, headers=_headers(revision),
    )
    assert response.json()["starts"][0]["label"] == "09:00"
    response = env.client.get(
        "/api/library/catalog",
        params={**params, "room": "r1", "seat": "s1", "start": "09:00"},
        headers=_headers(revision),
    )
    assert response.json()["ends"][0]["label"] == "10:00"
    assert env.client.get("/api/library/catalog", params={"building": "b1"}).status_code == 409


def test_book_confirm_and_idempotency(env):
    revision, csrf = ready(env.client, env.keyring)
    attempt = str(uuid.uuid4())
    first = _book(env.client, revision, csrf, attempt)
    assert first.status_code == 200
    assert first.json() == {"status": "submitted"}
    records = env.school.read("upc-20230001")[0]
    assert len(records) == 1

    # The same UUID never sends a second request. A submitted attempt is
    # reported as unknown until the school record reconciles it.
    again = _book(env.client, revision, csrf, attempt)
    assert again.json() == {"status": "unknown"}
    assert len(env.school.read("upc-20230001")[0]) == 1

    # Reconcile against the school record.
    reservations = env.client.get(
        "/api/library/reservations", params={"attempt": attempt}, headers=_headers(revision),
    )
    assert reservations.status_code == 200
    data = reservations.json()
    assert data["attempt"]["status"] == "confirmed"
    assert data["attempt"]["recordId"] == records[0]["id"]
    assert len(data["records"]) == 1

    # A second confirmed attempt for the same slot is rejected before sending.
    duplicate = _book(env.client, revision, csrf)
    assert duplicate.json() == {"status": "rejected"}
    assert len(env.school.read("upc-20230001")[0]) == 1


def test_book_requires_csrf_and_same_origin(env):
    revision, csrf = ready(env.client, env.keyring)
    payload = {"id": str(uuid.uuid4()), **BOOK}
    missing = env.client.post(
        "/api/library/book", json=payload, headers={"X-Library-Session": revision},
    )
    assert missing.status_code == 403
    wrong_origin = env.client.post(
        "/api/library/book", json=payload,
        headers={**_headers(revision, csrf), "Origin": "https://evil.example"},
    )
    assert wrong_origin.status_code == 403
    assert env.school.read("upc-20230001")[0] == []
    bad_json = env.client.post(
        "/api/library/book", content=b"{not json",
        headers={**_headers(revision, csrf), "Content-Type": "application/json"},
    )
    assert bad_json.status_code == 400


def test_cancel_flow(env):
    revision, csrf = ready(env.client, env.keyring)
    _book(env.client, revision, csrf)
    reservations = env.client.get("/api/library/reservations", headers=_headers(revision)).json()
    reservation_id = reservations["records"][0]["id"]
    assert reservations["records"][0]["cancellable"] is True

    cancel_attempt = str(uuid.uuid4())
    response = env.client.post(
        "/api/library/cancel", json={"id": cancel_attempt, "reservationId": reservation_id},
        headers=_headers(revision, csrf),
    )
    assert response.status_code == 200
    assert response.json() == {"status": "cancelled"}
    # Retried cancellation UUID returns the stored outcome without resending.
    retry = env.client.post(
        "/api/library/cancel", json={"id": cancel_attempt, "reservationId": reservation_id},
        headers=_headers(revision, csrf),
    )
    assert retry.json() == {"status": "cancelled"}
    after = env.client.get("/api/library/reservations", headers=_headers(revision)).json()
    assert after["records"] == []


def test_chaoxing_branch(env):
    revision, csrf = ready(env.client, env.keyring, branch="east")
    session = env.client.get("/api/library/east/session").json()
    assert session["state"] == "ready"
    assert session["recordsAvailable"] is False
    catalog = env.client.get(
        "/api/library/east/catalog", params={"building": "east", "date": TOMORROW},
        headers=_headers(revision),
    )
    assert catalog.status_code == 200
    payload = {"id": str(uuid.uuid4()), **BOOK}
    response = env.client.post(
        "/api/library/east/book", json=payload, headers=_headers(revision, csrf),
    )
    assert response.status_code == 200
    assert response.json() == {"status": "accepted"}
    reservations = env.client.get(
        "/api/library/east/reservations", headers=_headers(revision),
    )
    assert reservations.json() == {"available": False}


def test_cross_user_isolation(env):
    revision, csrf = ready(env.client, env.keyring, username="20230001")
    attempt = str(uuid.uuid4())
    _book(env.client, revision, csrf, attempt)
    confirmed = env.client.get(
        "/api/library/reservations", params={"attempt": attempt}, headers=_headers(revision),
    ).json()
    reservation_id = confirmed["records"][0]["id"]

    # Same app and storage, different cookie jar: a second user.
    other = env.client.__class__(
        env.app, raise_server_exceptions=False, base_url="http://testserver",
    )
    other_revision, other_csrf = ready(other, env.keyring, username="20230002")
    records = other.get(
        "/api/library/reservations", headers=_headers(other_revision),
    ).json()
    assert records["records"] == []
    # Reusing another user's attempt UUID must not read or resend it.
    collision = other.post(
        "/api/library/book", json={"id": attempt, **BOOK},
        headers=_headers(other_revision, other_csrf),
    )
    assert collision.status_code == 200
    assert collision.json()["status"] in {"rejected", "unknown"}
    # Reusing another user's reservation ID for cancellation must be rejected.
    cancel = other.post(
        "/api/library/cancel",
        json={"id": str(uuid.uuid4()), "reservationId": reservation_id},
        headers=_headers(other_revision, other_csrf),
    )
    assert cancel.status_code == 200
    assert cancel.json()["status"] == "rejected"
    # The victim's record and the attacker's school account are untouched.
    assert len(env.school.read("upc-20230001")[0]) == 1
    assert env.school.read("upc-20230002") == ([], set(), set())
    # Reading the victim's attempt outcome as the other user yields no attempt.
    outcome = other.get(
        "/api/library/reservations", params={"attempt": attempt},
        headers=_headers(other_revision),
    ).json()
    assert "attempt" not in outcome


def test_logout_paths(env):
    revision, csrf = ready(env.client, env.keyring)
    response = env.client.post("/api/library/logout", headers=_headers(revision, csrf))
    assert response.json() == {"state": "sign_in"}
    assert env.client.get("/api/library/session").json() == {"state": "sign_in"}

    revision, csrf = ready(env.client, env.keyring)
    global_out = env.client.post("/api/logout", headers=_headers(revision, csrf))
    assert global_out.status_code == 200
    assert global_out.json() == {"state": "anonymous"}
    assert env.client.get("/api/session").json() == {"state": "anonymous"}
    assert env.client.get("/api/library/session").json() == {"state": "sign_in"}


def test_upstream_401_expires_only_that_branch(env):
    revision, _ = ready(env.client, env.keyring)
    assert env.client.get("/api/library/session").json()["state"] == "ready"
    env.school.session_expired = True
    response = env.client.get(
        "/api/library/catalog", headers=_headers(revision),
    )
    assert response.status_code == 401
    assert response.json() == {"error": "library_session_expired"}
    env.school.session_expired = False
    assert env.client.get("/api/library/session").json() == {"state": "sign_in"}
    # The browser session itself still exists; only the library token is gone.
    assert env.client.get("/api/session").json()["state"] == "ready"


def test_captcha_returns_challenge_without_writing(env):
    revision, csrf = ready(env.client, env.keyring)
    env.school.captcha = True
    response = _book(env.client, revision, csrf)
    assert response.status_code == 200
    assert response.json() == {"status": "challenge"}
    assert env.school.read("upc-20230001")[0] == []


def test_upstream_failure_maps_to_503(env):
    revision, _ = ready(env.client, env.keyring)
    env.school.upstream_error = True
    response = env.client.get("/api/library/catalog", headers=_headers(revision))
    assert response.status_code == 503
    assert response.json() == {"error": "upstream_unavailable"}


def test_concurrent_same_uuid_sends_once(env):
    import asyncio

    revision, csrf = ready(env.client, env.keyring)
    session = asyncio.run(env.store.get_browser(
        env.client.cookies.get(env.settings.cookie_name),
    ))
    assert session is not None
    attempt = str(uuid.uuid4())
    payload = {"id": attempt, **BOOK}

    async def run():
        audit = __import__("app.library.service", fromlist=["AuditContext"]).AuditContext(None, None)
        return await asyncio.gather(
            env.app.state.resources.library_service.book(session, "tangdaowan", payload, audit),
            env.app.state.resources.library_service.book(session, "tangdaowan", payload, audit),
        )

    results = asyncio.run(run())
    assert all(result["status"] in {"submitted", "unknown", "rejected"} for result in results)
    assert len(env.school.read("upc-20230001")[0]) == 1


def test_public_data_is_cached(env):
    params = {
        "campus": "tangdaowan", "date": TOMORROW,
        "building": "全部", "period": ["1", "2"],
    }
    first = env.client.get("/api/classrooms", params=params)
    assert first.status_code == 200
    assert first.headers["x-cache"] == "miss"
    second = env.client.get("/api/classrooms", params=params)
    assert second.status_code == 200
    assert second.headers["x-cache"] == "hit"
    assert env.school.classroom_calls == 1

    calendar = env.client.get("/api/calendar")
    assert calendar.status_code == 200
    assert env.client.get("/api/calendar").headers["x-cache"] == "hit"
    assert calendar.json()["academicYear"] == "2026-2027"

    catalog = env.client.get("/api/service-catalog")
    assert catalog.status_code == 200
    guide = env.client.get("/api/service-catalog/guide", params={"id": "123"})
    assert guide.json()["title"] == "指南"
    assert env.client.get("/api/service-catalog/guide", params={"id": "../etc"}).status_code == 400
    assert env.client.get("/api/classrooms", params={"campus": "tangdaowan"}).status_code == 400


def test_legacy_login_redirect(env):
    response = env.client.get("/api/library/login", follow_redirects=False)
    assert response.status_code == 303
    assert response.headers["location"] == "/?library=1#library"
    response = env.client.get("/api/library/east/login", follow_redirects=False)
    assert response.headers["location"] == "/?library=1#library-east"


def test_login_rate_limit(settings, keyring):
    limited = dataclasses.replace(
        settings, rate_limit_enabled=True, login_rate_limit=2, login_rate_window=600,
    )
    client, _ = build_client(limited, keyring)
    with client:
        for _ in range(2):
            payload = login_payload(client, keyring, username="20230009", password="wrong")
            assert client.post("/api/auth/login", json=payload).status_code == 401
        payload = login_payload(client, keyring, username="20230009", password="wrong")
        response = client.post("/api/auth/login", json=payload)
        assert response.status_code == 429
        assert response.json() == {"error": "rate_limited"}


def test_access_log_has_no_credentials(env, caplog):
    caplog.set_level(logging.INFO, logger="upcp.access")
    secret = "S3cret-Value-For-Log-Check"
    response = login(env.client, env.keyring, password=secret)
    assert response.status_code == 401
    assert secret not in caplog.text
    assert "20230001" not in caplog.text
    assert "/api/auth/login" in caplog.text


def test_health_and_unknown_routes(env):
    health = env.client.get("/healthz")
    assert health.status_code == 200
    assert health.json()["status"] == "ok"
    unknown = env.client.get("/api/nope")
    assert unknown.status_code == 404
    assert unknown.json() == {"error": "unknown_route"}
    assert env.client.get("/api/auth/key").headers["x-request-id"]
