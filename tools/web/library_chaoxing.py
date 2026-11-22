"""Seat and seatengine business adapters for the two UPC Chaoxing libraries."""

import hashlib
import json
import math
import re
from datetime import date, datetime, timedelta
from html.parser import HTMLParser

from library_auth import LibraryAuthError
from library_chaoxing_auth import request_chaoxing
from library_upc import (
    CHOICE_FIELDS, CHOICE_PATHS, SHANGHAI, LibraryError, as_items, field, integer,
    invalid_selection, minute_of_day, option_map, protocol_error, selected, time_label,
    valid_date,
)


BRANCHES = {
    "east": ("seat", "古镇口东区图书馆"),
    "qiushi": ("seatengine", "求是图书馆"),
}
FID = "e32f8fbde51f9dba"
SEAT_ID = "4258"
WEEKDAYS = ("mon", "tues", "wed", "thur", "fri", "sat", "sun")


def _clock(minute):
    return f"{minute // 60:02d}:{minute % 60:02d}"


def _hour_window(settings, day):
    if not isinstance(settings, dict):
        return None
    key = WEEKDAYS[day.weekday()]
    start = time_label(field(settings, key + "StartTime"))
    end = time_label(field(settings, key + "EndTime"))
    if start and end and start < end:
        return minute_of_day(start), minute_of_day(end)
    return None


def _label(room):
    return "-".join(part for key in ("firstLevelName", "secondLevelName", "thirdLevelName")
                    if (part := field(room, key))) or f"区域 {field(room, 'id')}"


def _duration(settings, key):
    raw = settings.get(key)
    if raw is None or raw == "":
        return None
    try:
        hours = float(raw)
    except (TypeError, ValueError, OverflowError):
        raise protocol_error() from None
    if isinstance(raw, bool) or not math.isfinite(hours) or hours > 24:
        raise protocol_error()
    return (int(hours * 60) or None) if hours > 0 else None


def _geometry(place):
    try:
        x, y, width, height, rotate = (float(place.get(key, default)) for key, default in
                                      (("xleft", None), ("ytop", None), ("width", None),
                                       ("height", None), ("rotate", 0)))
    except (TypeError, ValueError, OverflowError):
        return {}
    if not all(map(math.isfinite, (x, y, width, height, rotate))):
        return {}
    if x < 0 or y < 0 or width <= 0 or height <= 0 or max(x + width, y + height) > 6000:
        return {}
    return dict(x=x, y=y, width=width, height=height, rotate=rotate % 360)


class _SelectPage(HTMLParser):
    def __init__(self, html):
        super().__init__(convert_charrefs=True)
        self.tokens = []
        self.feed(html)
        self.close()

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if tag == "input" and values.get("id") == "submit_enc":
            self.tokens.append(values.get("value") or "")


class ChaoxingLibrary:
    def __init__(self, branch, session):
        self.kind, self.label = BRANCHES[branch]
        self.branch = branch
        self.session = session

    def _read(self, endpoint, *, params=None, form=None):
        try:
            envelope = request_chaoxing(
                self.session, "POST" if form is not None else "GET",
                f"/data/apps/{self.kind}/{endpoint}", query=params, form=form,
            )
        except LibraryAuthError as error:
            if error.code in {"access_denied", "session_expired"}:
                raise LibraryError(401, "session_expired") from None
            raise LibraryError(502, "library_unreachable") from None
        if not isinstance(envelope, dict) or envelope.get("success") is not True or "data" not in envelope:
            raise protocol_error()
        return envelope["data"]

    def _dates(self):
        today = datetime.now(SHANGHAI).date()
        return [today.isoformat(), (today + timedelta(days=1)).isoformat()]

    def _rooms(self, target_date):
        params = dict(time="", cpage="1", pageSize="100", firstLevelName="", secondLevelName="",
                      thirdLevelName="", day=target_date, deptIdEnc=FID)
        if self.kind == "seatengine":
            params["seatId"] = SEAT_ID
        data = self._read("room/list", params=params)
        if not isinstance(data, dict) or (integer(data.get("totalPage")) or 1) > 1:
            raise protocol_error()
        day = date.fromisoformat(target_date)
        rooms = []
        for room in as_items(data.get("seatRoomList")):
            identifier = field(room, "id")
            if not identifier.isascii() or not identifier.isdecimal() or len(identifier) > 10:
                raise protocol_error()
            if not (_hour_window(room.get("seatSpecialTime"), day)
                    or _hour_window(room.get("seatEngineSpecialTime"), day)):
                continue
            rooms.append({"id": identifier, "label": _label(room), "totalSeats": integer(room.get("capacity"))})
        return list(option_map(rooms).values())

    def _room(self, room_id, target_date):
        data = self._read("room/info", form={"id": room_id, "fidEnc": FID})
        if not isinstance(data, dict):
            raise protocol_error()
        room, config = data.get("seatRoom"), data.get("seatConfig")
        if not isinstance(room, dict) or not isinstance(config, dict) or field(room, "id") != room_id:
            raise protocol_error()
        day = date.fromisoformat(target_date)
        hours = (_hour_window(room.get("seatSpecialTime"), day)
                 or _hour_window(room.get("seatEngineSpecialTime"), day)
                 or _hour_window(config.get("commonTimeConfig"), day))
        unit = integer(config.get("timeUnit"))
        if not hours or not unit or not 1 <= unit <= 1440:
            raise protocol_error()
        denied = set()
        for attribute in as_items(data.get("seatAttributes")):
            seat, allowed = field(attribute, "seatNum"), field(attribute, "isReserve")
            if not seat or allowed not in {"0", "1"}:
                raise protocol_error()
            if allowed == "0":
                denied.add(seat)
        opened = integer(room.get("isOpen")) != 1
        return hours, unit, opened, denied, _duration(config, "minReserveDuration"), _duration(config, "reserveDuration")

    def _seats(self, room, detail):
        _, _, opened, denied, _, _ = detail
        places = as_items(self._read("getdrawseat", params={"id": room}))
        seats = []
        for place in places:
            number = field(place, "seatNum")
            if not number or len(number) > 80:
                raise protocol_error()
            seats.append({"id": number, "label": number, "selectable": opened and number not in denied,
                          **_geometry(place)})
        if len(option_map(seats)) != len(seats):
            raise protocol_error()
        return seats

    def _occupancy_params(self, **params):
        if self.kind == "seatengine":
            params["seatId"] = SEAT_ID
        else:
            params["fidEnc"] = FID
        return params

    def _times(self, room, seat, target_date, detail):
        hours, unit, opened, _, minimum, maximum = detail
        if not opened:
            return {}
        busy = []
        params = self._occupancy_params(roomId=room, seatNum=seat, day=target_date)
        for span in as_items(self._read("getusedtimes", params=params)):
            if not isinstance(span, list) or len(span) != 2:
                raise protocol_error()
            minutes = []
            for value in span:
                try:
                    stamp = int(value)
                    when = datetime.fromtimestamp(stamp / 1000 if stamp > 1_000_000_000_000 else stamp, SHANGHAI)
                except (TypeError, ValueError, OverflowError, OSError):
                    raise protocol_error() from None
                minutes.append(when.hour * 60 + when.minute)
            begin, end = minutes
            busy.append((begin, end if end > begin else end + 1440))
        now = datetime.now(SHANGHAI)
        first, last = hours
        choices = {}
        for start in range(first, last, unit):
            if target_date == now.date().isoformat() and start <= now.hour * 60 + now.minute:
                continue
            ends = [end for end in range(start + unit, last + 1, unit)
                    if (minimum is None or end - start >= minimum)
                    and (maximum is None or end - start <= maximum)
                    and not any(start < busy_end and busy_start < end for busy_start, busy_end in busy)]
            if ends:
                choices[str(start)] = [{"id": str(end), "label": _clock(end)} for end in ends]
        return choices

    def catalog(self, params):
        if set(params) not in CHOICE_PATHS:
            raise invalid_selection()
        if not params:
            return {"buildings": [{"id": self.branch, "label": self.label}], "dates": self._dates()}
        day = params["date"]
        if params["building"] != self.branch or day not in self._dates():
            raise invalid_selection()
        rooms = self._rooms(day)
        if "room" not in params:
            return {"rooms": rooms}
        room = params["room"]
        selected(rooms, room)
        detail = self._room(room, day)
        seats = self._seats(room, detail)
        if "seat" not in params:
            result = {"seats": seats}
            if seats and all("width" in seat for seat in seats):
                result["layout"] = {"width": max(seat["x"] + seat["width"] for seat in seats),
                                    "height": max(seat["y"] + seat["height"] for seat in seats)}
            return result
        seat = params["seat"]
        selected(seats, seat, selectable=True)
        times = self._times(room, seat, day, detail)
        if "start" not in params:
            return {"starts": [{"id": start, "label": _clock(int(start))} for start in times]}
        return {"ends": times.get(params["start"], [])}

    def prepare_booking(self, choice):
        if set(choice) != set(CHOICE_FIELDS) or not valid_date(choice["date"]):
            raise invalid_selection()
        branch, day, room, seat, start, end = (choice[key] for key in CHOICE_FIELDS)
        if branch != self.branch or day not in self._dates():
            raise invalid_selection()
        room_label = selected(self._rooms(day), room)
        detail = self._room(room, day)
        seats = self._seats(room, detail)
        selected(seats, seat, selectable=True)
        ends = self._times(room, seat, day, detail).get(start, [])
        end_label = selected(ends, end)
        start_label = _clock(int(start))
        params = self._occupancy_params(roomId=room, startTime=start_label, endTime=end_label, day=day)
        used = self._read("getusedseatnums", params=params)
        if not isinstance(used, dict):
            raise protocol_error()
        busy = [field(item, "seatNum") for item in as_items(used.get("seatReserves"))]
        if set(busy).difference(item["id"] for item in seats):
            raise protocol_error()
        if seat in busy:
            raise invalid_selection()
        return {"choice": choice, "labels": dict(building=self.label, room=room_label, seat=seat, date=day,
                                                start=start_label, end=end_label),
                "started_at": datetime.now(SHANGHAI).isoformat()}

    def submit_booking(self, prepared):
        choice, labels = prepared["choice"], prepared["labels"]
        seat, day = choice["seat"], choice["date"]
        params = dict(id=choice["room"], deptIdEnc=FID, fidEnc=FID, day=day)
        if self.kind == "seatengine":
            params["seatId"] = SEAT_ID
        try:
            html = request_chaoxing(self.session, "GET", f"/front/third/apps/{self.kind}/select", query=params)
            html = html.decode("utf-8")
            if re.search(r"""\briskCheckOpen\s*["']?\s*[:=]\s*true""", html, re.IGNORECASE):
                return "challenge"
            tokens = _SelectPage(html).tokens
            if len(tokens) != 1 or not 1 <= len(tokens[0].strip()) <= 512:
                return "rejected"
        except (LibraryAuthError, UnicodeError, ValueError):
            return "rejected"
        form = {"deptIdEnc": FID} if self.kind == "seat" else {}
        form.update(roomId=choice["room"], day=day, startTime=labels["start"], endTime=labels["end"],
                    seatNum=seat, captcha="", wyToken="")
        if self.kind == "seatengine":
            field_match = re.search(r"""\bwfwEngineEnc\s*=\s*(['"])(.*?)\1""", html)
            form["wfwEngineEnc"] = field_match[2] if field_match else ""
            if len(form["wfwEngineEnc"]) > 256:
                return "rejected"
        canonical = "".join(f"[{key}={value}]" for key, value in form.items())
        form["enc"] = hashlib.md5((canonical + f"[{tokens[0]}]").encode(), usedforsecurity=False).hexdigest()
        try:
            result = request_chaoxing(self.session, "POST", f"/data/apps/{self.kind}/submit", form=form)
        except (LibraryAuthError, json.JSONDecodeError):
            return "unknown"
        if not isinstance(result, dict) or type(result.get("success")) is not bool:
            return "unknown"
        message = result.get("msg")
        if "msg" in result and not isinstance(message, str):
            return "unknown"
        data = result.get("data")
        if data is not None and not isinstance(data, dict):
            return "unknown"
        if isinstance(data, dict) and "seatReserve" in data:
            details = data["seatReserve"]
            if not isinstance(details, dict) or any(
                key in details and details[key] != value for key, value in {"seatNum": seat, "day": day}.items()
            ):
                return "unknown"
        if result["success"]:
            return "accepted"
        if not message or not message.strip():
            return "unknown"
        return "challenge" if re.search(r"验证码|安全验证|captcha", message, re.IGNORECASE) else "rejected"
