"""Read the university's published free-classroom search without user credentials."""

import datetime as dt
import http.client
import json
import re
import urllib.parse
import urllib.request


ENDPOINT = "https://app.upc.edu.cn/freeclass/wap/default/search-all"
CAMPUSES = {"tangdaowan": "唐岛湾校区", "guzhenkou": "古镇口校区"}
ALL_BUILDINGS = "全部"
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
PERIODS = frozenset(str(number) for number in range(1, 14))


class ClassroomError(Exception):
    def __init__(self, status, code):
        super().__init__(code)
        self.status = status
        self.code = code


class RejectRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        return None


def read_query(params):
    if set(params) != {"campus", "date", "building", "period"}:
        raise ClassroomError(400, "invalid_classroom_query")
    if any(len(params[key]) != 1 for key in ("campus", "date", "building")):
        raise ClassroomError(400, "invalid_classroom_query")

    campus, date, building = (params[key][0] for key in ("campus", "date", "building"))
    periods = params["period"]
    if (campus not in CAMPUSES or not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", date)
            or not building.strip() or len(building) > 100 or not building.isprintable()
            or not periods or len(periods) > len(PERIODS) or not PERIODS.issuperset(periods)):
        raise ClassroomError(400, "invalid_classroom_query")
    try:
        dt.date.fromisoformat(date)
    except ValueError:
        raise ClassroomError(400, "invalid_classroom_query") from None
    return {
        "campus": campus,
        "date": date,
        "building": building,
        "periods": sorted({int(value) for value in periods}),
    }


def required_text(item, key):
    if not isinstance(item, dict) or not isinstance(value := item.get(key), str) or not value.strip():
        raise ValueError
    return value


def room_groups(value):
    # PHP represents an empty associative list as [].
    if value == []:
        return {}
    if not isinstance(value, dict) or any(not isinstance(group, list) for group in value.values()):
        raise ValueError
    return value


def capacity_of(value):
    if isinstance(value, (str, int)) and re.fullmatch(r"[0-9]{1,10}", str(value)):
        capacity = int(value)
        if 0 < capacity <= 2_147_483_647:
            return capacity
    return None


def parse_result(payload, query):
    if not isinstance(payload, dict) or str(payload.get("e")) != "0":
        raise ValueError
    data = payload.get("d")
    if not isinstance(data, dict) or not isinstance(data.get("listLh"), list):
        raise ValueError
    directory, available = (room_groups(data.get(key)) for key in ("all", "js"))
    buildings = list(dict.fromkeys([ALL_BUILDINGS, *(required_text(item, "id") for item in data["listLh"])]))
    if not directory.keys() >= available.keys():
        raise ValueError

    rooms, known_codes = [], set()
    for building, group in directory.items():
        available_codes = {required_text(room, "code") for room in available.get(building, [])}
        group_codes = set()
        for room in group:
            code = required_text(room, "code")
            if (required_text(room, "xqm") != CAMPUSES[query["campus"]]
                    or required_text(room, "jxlm") != building
                    or query["building"] not in (ALL_BUILDINGS, building)
                    or code in known_codes):
                raise ValueError
            known_codes.add(code)
            group_codes.add(code)
            rooms.append({
                "code": code,
                "name": required_text(room, "name"),
                "building": building,
                "capacity": capacity_of(room.get("num")),
                "available": code in available_codes,
            })
        if not group_codes >= available_codes:
            raise ValueError
    return {
        "query": query,
        "buildings": buildings,
        "rooms": rooms,
        "fetchedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
    }


def query_classrooms(params):
    query = read_query(params)
    fields = [
        ("xq", CAMPUSES[query["campus"]]),
        ("date", query["date"]),
        ("lh", query["building"]),
        *(("jc[]", period) for period in query["periods"]),
    ]
    request = urllib.request.Request(
        ENDPOINT,
        data=urllib.parse.urlencode(fields).encode("ascii"),
        headers={
            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
            "Accept": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.build_opener(RejectRedirects).open(request, timeout=12) as response:
            if response.status != 200:
                raise ClassroomError(502, "classroom_upstream_unavailable")
            encoded = response.read(MAX_RESPONSE_BYTES + 1)
    except (OSError, http.client.HTTPException):
        raise ClassroomError(502, "classroom_upstream_unavailable") from None

    if len(encoded) > MAX_RESPONSE_BYTES:
        raise ClassroomError(502, "classroom_invalid_response")
    try:
        return parse_result(json.loads(encoded.decode("utf-8")), query)
    except (ValueError, RecursionError):
        raise ClassroomError(502, "classroom_invalid_response") from None
