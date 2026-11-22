"""Translate the authenticated Tangdaowan libseat service to Pocket's local API."""

from datetime import date, datetime
import re
import unicodedata
from zoneinfo import ZoneInfo

from library_auth import LibraryAuthError, request_library


CHOICE_FIELDS = ("building", "date", "room", "seat", "start", "end")
STRUCTURED_LOCATIONS = {
    "building": ("buildingId", ("buildingName", "building")),
    "room": ("roomId", ("roomName", "room")),
    "seat": ("seatId", ("seatName", "seatNo")),
}
SHANGHAI = ZoneInfo("Asia/Shanghai")
ACTIVE = {"RESERVE", "CHECK_IN", "AWAY"}
NOW = {"now", "-1"}
CHOICE_PATHS = (set(), *[set(CHOICE_FIELDS[:size]) for size in range(2, 6)])


class LibraryError(Exception):
    def __init__(self, status, code):
        super().__init__(code)
        self.status = status
        self.code = code


def protocol_error():
    return LibraryError(502, "library_protocol_error")


def invalid_selection():
    return LibraryError(400, "invalid_selection")


def scalar(value):
    if isinstance(value, str) or type(value) is int:
        return unicodedata.normalize("NFC", str(value)).strip()
    return ""


def field(mapping, *keys):
    if not isinstance(mapping, dict):
        raise protocol_error()
    for key in keys:
        value = scalar(mapping.get(key))
        if value:
            return value
    return ""


def path_id(value):
    return isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,80}", value)


def valid_date(value):
    try:
        return date.fromisoformat(value).isoformat() == value
    except (TypeError, ValueError):
        return False


def truth_value(value):
    if value is True or value == 1 or isinstance(value, str) and value.lower() in {"true", "1"}:
        return True
    if value is False or value == 0 or isinstance(value, str) and value.lower() in {"false", "0"}:
        return False
    return None


def integer(value):
    value = scalar(value)
    return int(value) if re.fullmatch(r"-?\d{1,9}", value, re.ASCII) else None


def time_label(value):
    match = re.fullmatch(r"(\d{1,2}):(\d{2})(?::\d{2})?", value, re.ASCII)
    if match:
        hour, minute = map(int, match.groups())
        if (0 <= hour <= 23 and 0 <= minute <= 59) or (hour, minute) == (24, 0):
            return f"{hour:02d}:{minute:02d}"
    return ""


def minute_of_day(label):
    hour, minute = map(int, label.split(":"))
    return hour * 60 + minute


def outcome(envelope):
    status = envelope.get("status")
    if isinstance(status, str):
        status = status.lower()
    code = scalar(envelope.get("code"))
    if code == "12":
        return "expired"
    if code == "20":
        return "unknown"
    rejected = status is False or envelope.get("success") is False or status in ("false", "fail", "error")
    submitted = status is True or envelope.get("success") is True or status in ("true", "success")
    if rejected and submitted:
        return "unknown"
    if rejected:
        return "rejected"
    if submitted:
        return "submitted"
    if code in {"0", "200"} and "data" in envelope:
        return "submitted"
    return "unknown"


def as_items(items):
    if not isinstance(items, list):
        raise protocol_error()
    return items


def option(item_id, label, **metadata):
    if not path_id(item_id) or not label:
        raise protocol_error()
    return {"id": item_id, "label": label, **metadata}


def option_map(items):
    result = {}
    for item in items:
        if item["id"] in result and result[item["id"]] != item:
            raise protocol_error()
        result[item["id"]] = item
    return result


def selected(items, item_id, *, selectable=False):
    item = option_map(items).get(item_id)
    if not item or selectable and not item["selectable"]:
        raise invalid_selection()
    return item["label"]


class UpcLibrary:
    def __init__(self, session):
        self.session = session

    def _read(self, path, *, settings=False):
        try:
            query = {} if settings else {"token": self.session.token}
            envelope = request_library(self.session, "GET", path, query=query)
        except LibraryAuthError as error:
            if error.code == "session_expired":
                raise LibraryError(401, "session_expired") from None
            raise LibraryError(502, "library_unreachable") from None
        result = outcome(envelope)
        if result == "expired":
            raise LibraryError(401, "session_expired")
        if result == "rejected":
            raise LibraryError(502, "library_rejected_query")
        if result != "submitted" or "data" not in envelope:
            raise protocol_error()
        return envelope["data"]

    def _filters(self):
        data = self._read("/rest/v2/free/filters")
        if not isinstance(data, dict):
            raise protocol_error()
        dates = as_items(data.get("dates"))
        if not all(map(valid_date, dates)):
            raise protocol_error()
        buildings = []
        for item in as_items(data.get("buildings")):
            if isinstance(item, list) and len(item) >= 2:
                buildings.append(option(scalar(item[0]), scalar(item[1])))
            else:
                buildings.append(option(field(item, "id"), field(item, "name")))
        return {"buildings": list(option_map(buildings).values()), "dates": list(dict.fromkeys(dates))}

    def _rooms(self, building, target_date):
        data = self._read(f"/rest/v2/room/stats2/{building}/{target_date}")
        rooms = [option(
            field(item, "roomId", "id"), field(item, "room", "roomName", "name"),
            floor=integer(item.get("floor")), totalSeats=integer(item.get("totalSeats")),
            freeSeats=integer(item.get("free")),
        ) for item in as_items(data)]
        return list(option_map(rooms).values())

    def _layout(self, room, target_date):
        data = self._read(f"/rest/v2/room/layoutByDate/{room}/{target_date}")
        if not isinstance(data, dict) or not isinstance(data.get("layout"), dict):
            raise protocol_error()
        rows = integer(data.get("rows"))
        cols = integer(data.get("cols"))
        if rows is None or cols is None or not (1 <= rows <= 200 and 1 <= cols <= 200):
            raise protocol_error()

        seats = []
        for key, item in data["layout"].items():
            if not isinstance(item, dict) or (field(item, "type") or "seat").lower() != "seat":
                continue
            seat_id = field(item, "id")
            if not seat_id:
                continue
            row = integer(item.get("row"))
            col = integer(item.get("col"))
            if row is None or col is None:
                number = integer(key)
                if number is not None:
                    keyed_row, keyed_col = divmod(number, 1000)
                else:
                    first, _, second = key.partition("_")
                    keyed_row, keyed_col = integer(first), integer(second)
                row = row if row is not None else keyed_row
                col = col if col is not None else keyed_col

            status = field(item, "status").upper() or "UNKNOWN"
            seat = option(
                seat_id, field(item, "name") or seat_id, row=row, col=col, status=status,
                selectable=truth_value(item.get("enabled")) is True and truth_value(item.get("local")) is not True,
            )
            seats.append(seat)
        if not seats:
            raise protocol_error()
        zero_rows = any(seat["row"] == 0 for seat in seats)
        zero_cols = any(seat["col"] == 0 for seat in seats)
        for seat in seats:
            row = seat["row"]
            col = seat["col"]
            row = row + zero_rows if row is not None else 0
            col = col + zero_cols if col is not None else 0
            seat["row"] = row if 1 <= row <= rows else None
            seat["col"] = col if 1 <= col <= cols else None
        return {"seats": list(option_map(seats).values()), "layout": {"rows": rows, "cols": cols}}

    def _times(self, seat, target_date, start=None):
        if start is None:
            key = "startTimes"
            path = f"/rest/v2/startTimesForSeat/{seat}/{target_date}"
        else:
            key = "endTimes"
            path = f"/rest/v2/endTimesForSeat/{seat}/{target_date}/{start}"
        data = self._read(path)
        if not isinstance(data, dict):
            raise protocol_error()
        items = []
        for value in as_items(data.get(key)):
            item_id = field(value, "id")
            label = ("现在" if start is None and item_id in NOW
                     else time_label(field(value, "value")))
            items.append(option(item_id, label))
        return list(option_map(items).values())

    def catalog(self, params):
        if set(params) not in CHOICE_PATHS or not all(map(path_id, params.values())):
            raise invalid_selection()
        if not params:
            return self._filters()
        if not valid_date(params["date"]):
            raise invalid_selection()

        target_date = params["date"]
        if "room" not in params:
            return {"rooms": self._rooms(params["building"], target_date)}
        if "seat" not in params:
            return self._layout(params["room"], target_date)
        if "start" not in params:
            return {"starts": self._times(params["seat"], target_date)}
        return {"ends": self._times(params["seat"], target_date, params["start"])}

    def prepare_booking(self, selection):
        choice = {key: selection.get(key) for key in CHOICE_FIELDS}
        if not all(map(path_id, choice.values())) or not valid_date(choice["date"]):
            raise invalid_selection()

        building, target_date, room, seat, start, end = (choice[key] for key in CHOICE_FIELDS)
        filters = self._filters()
        if target_date not in filters["dates"]:
            raise invalid_selection()
        labels = {"date": target_date, "building": selected(filters["buildings"], building)}
        rooms = self._rooms(building, target_date)
        labels["room"] = selected(rooms, room)
        room_label = normalized_identity(labels["room"])
        unique_room = len(filters["buildings"]) == 1 and sum(
            normalized_identity(item["label"]) == room_label for item in rooms
        ) == 1
        labels["seat"] = selected(self._layout(room, target_date)["seats"], seat, selectable=True)
        labels["start"] = selected(self._times(seat, target_date), start)
        labels["end"] = selected(self._times(seat, target_date, start), end)

        settings = self._read("/rest/v2/settings", settings=True)
        captcha = truth_value(settings.get("isCaptchaOpen")) if isinstance(settings, dict) else None
        if captcha is None:
            raise protocol_error()
        if captcha:
            raise LibraryError(409, "captcha_required")
        started_at = datetime.now(SHANGHAI)
        if start in NOW:
            if target_date != started_at.date().isoformat():
                raise invalid_selection()
            start_minute = started_at.hour * 60 + started_at.minute
        else:
            start_minute = minute_of_day(labels["start"])
        if start_minute >= minute_of_day(labels["end"]):
            raise invalid_selection()
        return {"choice": choice, "labels": labels, "started_at": started_at.isoformat(), "unique_room": unique_room}

    def submit_booking(self, prepared):
        choice = prepared["choice"]
        form = {
            "startTime": "-1" if choice["start"] in NOW else choice["start"],
            "endTime": choice["end"], "seat": choice["seat"], "date": choice["date"],
            "userId": self.session.user_id, "username": self.session.username, "authid": "",
        }
        try:
            envelope = request_library(self.session, "POST", "/rest/v2/freeBook",
                                       query={"token": self.session.token}, form=form)
            result = outcome(envelope)
        except LibraryAuthError:
            return "unknown"
        return result if result in {"submitted", "rejected"} else "unknown"

    def reservations(self):
        return self.read_reservations()[0]

    def cancel_reservation(self, reservation_id):
        if not path_id(reservation_id):
            raise invalid_selection()
        try:
            envelope = request_library(
                self.session, "GET", f"/rest/v2/cancel/{reservation_id}",
                query={"token": self.session.token}, cancellation=True,
            )
            result = outcome(envelope)
        except LibraryAuthError:
            return "unknown"
        return result if result in {"submitted", "rejected"} else "unknown"

    def read_reservations(self):
        data = self._read("/rest/v2/user/reservations")
        if data is None:
            return [], set(), set()
        records = {}
        present = set()
        cancelled = set()
        for item in as_items(data):
            record_id = field(item, "id")
            if not record_id or record_id in present:
                raise protocol_error()
            present.add(record_id)
            status = field(item, "stat", "status").upper()
            if status == "CANCEL":
                cancelled.add(record_id)
            if status and status not in ACTIVE:
                continue
            record_date = field(item, "date", "onDate")
            start = time_label(field(item, "begin"))
            end = time_label(field(item, "end"))
            location = field(item, "location", "loc")
            if not valid_date(record_date) or not start or not end or not location:
                raise protocol_error()
            labels = {key: field(item, *keys) for key, (_, keys) in STRUCTURED_LOCATIONS.items()}
            identity = {key: field(item, id_key) for key, (id_key, _) in STRUCTURED_LOCATIONS.items()}
            records[record_id] = {
                "id": record_id, "date": record_date, "start": start, "end": end,
                "building": labels["building"] or "唐岛湾", "room": labels["room"] or "图书馆",
                "seat": labels["seat"] or location,
                "cancellable": status == "RESERVE" and bool(path_id(record_id)),
                "location": location, "_identity": identity, "_labels": labels, "_status": status,
            }
        return list(records.values()), present, cancelled


def normalized_identity(value):
    return unicodedata.normalize("NFKC", value).strip().casefold()


def location_matches(prepared, record):
    choice = prepared["choice"]
    labels = prepared["labels"]
    unmatched = set()
    identity = record.get("_identity", {})
    record_labels = record.get("_labels", {})
    for field in STRUCTURED_LOCATIONS:
        if identity.get(field):
            if choice[field] != identity[field]:
                return False
        elif record_labels.get(field):
            if normalized_identity(labels[field]) != normalized_identity(record_labels[field]):
                return False
        else:
            unmatched.add(field)

    if not unmatched or unmatched == {"building"} and identity.get("room") and identity.get("seat"):
        return True
    # The school sometimes returns a composed location only. Match complete values in order
    # at actual separators, not substrings such as seat 12 inside seat 123.
    separator = r"[\s\-–—·,，/]+"
    parts = [re.escape(normalized_identity(labels[key])) for key in STRUCTURED_LOCATIONS]
    location = normalized_identity(record.get("location", ""))
    if re.fullmatch(separator.join(parts), location):
        return True
    return bool(prepared.get("unique_room") and re.fullmatch(separator.join(parts[1:]), location))


def matching_record(prepared, records, prior_ids, *, existing=False):
    """Return a uniquely matching new active record or leave the attempt unresolved."""
    labels = prepared["labels"]
    choice = prepared["choice"]
    immediate = choice["start"] in NOW
    earliest = datetime.fromisoformat(prepared["started_at"])
    candidates = []
    for record in records:
        if record["id"] in prior_ids or record.get("_status") not in ACTIVE:
            continue
        if record["date"] != labels["date"] or record["end"] != labels["end"]:
            continue
        if immediate:
            if record["date"] != earliest.date().isoformat():
                continue
            # School records round to minutes. An immediate reservation must start when sent.
            actual = minute_of_day(record["start"]) * 60
            elapsed = earliest.hour * 3600 + earliest.minute * 60 + earliest.second
            if not (actual <= elapsed if existing else -60 < actual - elapsed <= 90):
                continue
        elif record["start"] != labels["start"]:
            continue
        if location_matches(prepared, record):
            if existing:
                return record["id"]
            candidates.append(record["id"])
    return candidates[0] if len(candidates) == 1 else None
