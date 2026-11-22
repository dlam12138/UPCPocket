"""Synthetic school backend shared by pytest and the local browser harness.

This module is for local verification only; it is excluded from the deployed
image by .dockerignore and is never imported by app code.
"""

from __future__ import annotations

import dataclasses
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from cryptography.hazmat.primitives.asymmetric import rsa

from app.crypto import KeyRing
from app.upstream.bridge import load as load_upstream

CHINA = ZoneInfo("Asia/Shanghai")


class FakeSchool:
    """Synthetic library backend keyed by verified user id."""

    def __init__(self):
        self.records: dict[str, list[dict]] = {}
        self.next_id = 1
        self.catalog_calls = 0
        self.classroom_calls = 0
        self.calendar_calls = 0
        self.session_expired = False
        self.captcha = False
        self.upstream_error = False
        self.tomorrow = (datetime.now(CHINA).date() + timedelta(days=1)).isoformat()

    def user_records(self, user_id: str) -> list[dict]:
        return self.records.setdefault(user_id, [])

    def add_record(self, user_id: str, choice: dict, labels: dict) -> str:
        record_id = str(1000 + self.next_id)
        self.next_id += 1
        self.user_records(user_id).append({
            "id": record_id,
            "date": choice["date"],
            "start": labels["start"],
            "end": labels["end"],
            "building": labels["building"],
            "room": labels["room"],
            "seat": labels["seat"],
            "cancellable": True,
            "location": f"{labels['building']} {labels['room']} {labels['seat']}",
            "_identity": {
                "building": choice["building"], "room": choice["room"], "seat": choice["seat"],
            },
            "_labels": dict(labels),
            "_status": "RESERVE",
        })
        return record_id

    def read(self, user_id: str):
        rows = self.user_records(user_id)
        present = {row["id"] for row in rows}
        cancelled = {row["id"] for row in rows if row["_status"] == "CANCEL"}
        visible = [row for row in rows if row["_status"] == "RESERVE"]
        return visible, present, cancelled


def make_upstream(real, school: FakeSchool):
    class FakeUpcLibrary:
        def __init__(self, session):
            self.session = session

        def _labels(self, choice):
            return {
                "building": "唐岛湾阅览楼", "room": "A区", "seat": "A-1",
                "date": choice["date"], "start": choice["start"], "end": choice["end"],
            }

        def _maybe_fail(self, *, prepare=False):
            if school.session_expired:
                raise real.LibraryError(401, "session_expired")
            if school.upstream_error:
                raise real.LibraryError(502, "library_unreachable")
            if prepare and school.captcha:
                raise real.LibraryError(409, "captcha_required")

        def catalog(self, params):
            school.catalog_calls += 1
            self._maybe_fail()
            dates = [school.tomorrow]
            if not params:
                return {"buildings": [{"id": "b1", "label": "唐岛湾阅览楼"}], "dates": dates}
            if params.get("building") != "b1" or params.get("date") not in dates:
                raise real.LibraryError(400, "invalid_selection")
            if "room" not in params:
                return {"rooms": [{"id": "r1", "label": "A区"}]}
            if "seat" not in params:
                return {
                    "seats": [{"id": "s1", "label": "A-1", "selectable": True}],
                    "layout": {"rows": 1, "cols": 1},
                }
            if "start" not in params:
                return {"starts": [{"id": "09:00", "label": "09:00"}]}
            return {"ends": [{"id": "10:00", "label": "10:00"}]}

        def prepare_booking(self, choice):
            self._maybe_fail(prepare=True)
            return {
                "choice": dict(choice),
                "labels": self._labels(choice),
                "started_at": datetime.now(CHINA).isoformat(),
                "unique_room": True,
            }

        def submit_booking(self, prepared):
            school.add_record(self.session.user_id, prepared["choice"], prepared["labels"])
            return "submitted"

        def reservations(self):
            return school.read(self.session.user_id)[0]

        def read_reservations(self):
            self._maybe_fail()
            return school.read(self.session.user_id)

        def cancel_reservation(self, reservation_id):
            for row in school.user_records(self.session.user_id):
                if row["id"] == reservation_id:
                    row["_status"] = "CANCEL"
                    row["cancellable"] = False
                    return "submitted"
            return "rejected"

    class FakeChaoxingLibrary(FakeUpcLibrary):
        label = "古镇口东区图书馆"

        def __init__(self, branch, session):
            self.branch = branch
            super().__init__(session)

        def _labels(self, choice):
            labels = super()._labels(choice)
            labels["building"] = self.label
            return labels

        def _maybe_fail(self, *, prepare=False):
            if school.session_expired:
                raise real.LibraryError(401, "session_expired")
            if school.upstream_error:
                raise real.LibraryError(502, "library_unreachable")
            if prepare and school.captcha:
                raise real.LibraryError(409, "captcha_required")

        def catalog(self, params):
            school.catalog_calls += 1
            self._maybe_fail()
            dates = [school.tomorrow]
            if not params:
                return {"buildings": [{"id": self.branch, "label": self.label}], "dates": dates}
            if "room" not in params:
                return {"rooms": [{"id": "r1", "label": "东区阅览室"}]}
            if "seat" not in params:
                return {"seats": [{"id": "s1", "label": "1", "selectable": True}]}
            if "start" not in params:
                return {"starts": [{"id": "540", "label": "09:00"}]}
            return {"ends": [{"id": "600", "label": "10:00"}]}

        def submit_booking(self, prepared):
            return "accepted"

    def authenticate(username, password):
        if password == "challenge":
            raise real.LibraryAuthError("interaction_required")
        if password != "correct":
            raise real.LibraryAuthError("invalid_credentials")
        return real.LibrarySession("seat-token", f"upc-{username}", username, "测试同学")

    def authenticate_chaoxing(username, password):
        if password != "correct":
            raise real.LibraryAuthError("invalid_credentials")
        return real.ChaoxingSession(username, username, "office-cookie")

    def query_classrooms(params):
        school.classroom_calls += 1
        return {
            "campus": params["campus"][0],
            "date": params["date"][0],
            "buildings": [],
            "classrooms": [],
            "queriedAt": datetime.now(CHINA).isoformat(),
        }

    def query_calendar():
        school.calendar_calls += 1
        return {"academicYear": "2026-2027", "terms": []}

    def query_service_catalog():
        return {"items": []}

    def query_service_guide(identifier):
        return {"id": identifier, "title": "指南"}

    return dataclasses.replace(
        real,
        authenticate=authenticate,
        authenticate_chaoxing=authenticate_chaoxing,
        UpcLibrary=FakeUpcLibrary,
        ChaoxingLibrary=FakeChaoxingLibrary,
        query_classrooms=query_classrooms,
        query_calendar=query_calendar,
        query_service_catalog=query_service_catalog,
        query_service_guide=query_service_guide,
    )


def build_keyring() -> KeyRing:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    return KeyRing({"test-kid": key}, "test-kid")
