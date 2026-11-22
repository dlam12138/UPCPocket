"""Journal state-machine semantics on the in-memory implementation."""

from __future__ import annotations

import json
import uuid

import pytest

from app.crypto import DataCipher
from app.library.journal import JournalError, MemoryDatabase

BRANCH = "tangdaowan"
PREPARED = {
    "choice": {"building": "b1", "date": "2026-11-23", "room": "r1", "seat": "s1",
               "start": "09:00", "end": "10:00"},
    "labels": {"building": "唐岛湾阅览楼", "room": "A区", "seat": "A-1",
               "date": "2026-11-23", "start": "09:00", "end": "10:00"},
    "started_at": "2026-11-22T10:00:00+08:00",
    "unique_room": True,
}


@pytest.fixture
def db(settings):
    return MemoryDatabase(DataCipher.from_settings(settings), settings.session_secret)


@pytest.fixture
def user(db):
    return db.get_or_create_user(b"\x11" * 32, "2026-11-web-v1")


def test_user_identity_is_idempotent(db):
    first = db.get_or_create_user(b"\x22" * 32, "v1")
    second = db.get_or_create_user(b"\x22" * 32, "v2")
    assert first == second


def test_attempt_lifecycle(db, user):
    attempt = str(uuid.uuid4())
    assert db.begin(user, BRANCH, attempt, PREPARED, ["old-1"]) is True
    assert db.begin(user, BRANCH, attempt, PREPARED, []) is False
    assert db.find(user, BRANCH, attempt) == {"id": attempt, "status": "unknown"}
    unsettled = db.unsettled(user, BRANCH)
    assert len(unsettled) == 1
    assert json.loads(unsettled[0][1]) == PREPARED
    assert unsettled[0][2] == ["old-1"]

    db.set_status(user, BRANCH, attempt, "confirmed", "record-1")
    assert db.find(user, BRANCH, attempt) == {
        "id": attempt, "status": "confirmed", "recordId": "record-1",
    }
    assert db.unsettled(user, BRANCH) == []
    details = db.confirmed_details(user, BRANCH)
    assert len(details) == 1 and details[0][0] == attempt and details[0][1] == "record-1"
    assert json.loads(details[0][2]) == PREPARED
    assert db.claimed_records(user, BRANCH) == {"record-1"}

    db.forget_details(user, BRANCH, attempt)
    assert db.confirmed_details(user, BRANCH) == []


def test_rejected_clears_details(db, user):
    attempt = str(uuid.uuid4())
    db.begin(user, BRANCH, attempt, PREPARED, [])
    db.set_status(user, BRANCH, attempt, "rejected")
    row = db.attempts[attempt]
    assert row["prepared"] is None and row["prior"] is None
    assert db.find(user, BRANCH, attempt)["status"] == "rejected"


def test_submitted_maps_to_unknown_and_keeps_details(db, user):
    attempt = str(uuid.uuid4())
    db.begin(user, BRANCH, attempt, PREPARED, [])
    db.set_status(user, BRANCH, attempt, "submitted")
    assert db.find(user, BRANCH, attempt)["status"] == "unknown"
    assert db.unsettled(user, BRANCH)[0][0] == attempt


def test_attempt_isolation_between_users(db):
    first = db.get_or_create_user(b"\x33" * 32, "v1")
    second = db.get_or_create_user(b"\x44" * 32, "v1")
    attempt = str(uuid.uuid4())
    db.begin(first, BRANCH, attempt, PREPARED, [])
    assert db.find(second, BRANCH, attempt) is None
    assert db.unsettled(second, BRANCH) == []
    # Another branch of the same user must not see the attempt either.
    assert db.find(first, "east", attempt) is None


def test_invalid_status_is_rejected(db, user):
    attempt = str(uuid.uuid4())
    db.begin(user, BRANCH, attempt, PREPARED, [])
    with pytest.raises(JournalError):
        db.set_status(user, BRANCH, attempt, "done")
    with pytest.raises(JournalError):
        db.finish_without_sending(user, BRANCH, str(uuid.uuid4()), "what")


def test_cancellation_reconciliation(db, user):
    reservation = "record-9"
    key = db.reservation_key(user, BRANCH, reservation)
    assert key == db.reservation_key(user, BRANCH, reservation)
    assert key != db.reservation_key(user, "east", reservation)

    attempt = str(uuid.uuid4())
    assert db.begin_cancellation(user, BRANCH, attempt, key) is True
    assert db.begin_cancellation(user, BRANCH, str(uuid.uuid4()), key) is False
    assert db.find_cancellation(user, BRANCH, attempt) == (key, "unknown")
    assert db.reconcile_cancellations(user, BRANCH, [reservation], []) == {key}

    db.set_cancellation(user, BRANCH, attempt, "submitted")
    assert db.reconcile_cancellations(user, BRANCH, [reservation], []) == {key}
    assert db.reconcile_cancellations(user, BRANCH, [], [reservation]) == set()
    assert db.find_cancellation(user, BRANCH, attempt)[1] == "cancelled"

    absent = str(uuid.uuid4())
    db.begin_cancellation(user, BRANCH, absent, db.reservation_key(user, BRANCH, "record-10"))
    db.reconcile_cancellations(user, BRANCH, [], [])
    assert db.find_cancellation(user, BRANCH, absent)[1] == "absent"
