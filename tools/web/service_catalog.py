"""Read the public e站通 directory and guides. No session or write operations."""

from collections import Counter
from html.parser import HTMLParser
from http.client import HTTPException, HTTPSConnection
import json
import re
from threading import Lock
from time import monotonic
from urllib.parse import urlencode, urlsplit

# Public protocol adapted from the repository's Kotlin ServicePortalProtocol,
# based on UPC-MCP (948f5f21), MIT, Copyright (c) 2026 3beuml.
# License: docs/third-party/UPC-MCP-MIT.txt.
HOST = "service.upc.edu.cn"
CATALOG_PATH = "/api/apps/list"
GUIDE_PATH = "/api/apps/detail"
MAX_BYTES = 2 * 1024 * 1024
CATALOG_TTL_SECONDS = 300
ID_PATTERN = re.compile(r"[1-9][0-9]{0,18}", re.ASCII)
URLS = re.compile(r"""(?:\b(?:https?|ftp|file|content)(?:://|%3a%2f%2f)|(?<![/:\w.-])//)[^\s<>"']+
                    |\b(?:[a-z0-9-]+\.)+[a-z]{2,}(?::\d+)?(?:[/?#][^\s<>"']*)?""", re.I | re.X)
PRIVATE_PATH = re.compile(r"""(?i)/(?:[a-z0-9._~%-]+/)*(?:attachments?|files?|download|upload)(?:/[a-z0-9._~%-]+)*(?:[?#][^\s<>"']*)?""")
PRIVATE_VALUE = re.compile(
    r"""(?i)(?<![\w-])(?:ticket|token|access[_-]?token|refresh[_-]?token|authorization|auth|session(?:id)?|jsessionid|file[_-]?id|attachment[_-]?id|inst(?:ance)?[_-]?id)["']?\s*(?:[:=]|%3d)\s*["']?[^\s"'&,;<>]+"""
)
CAS_TICKET = re.compile(r"(?<![A-Za-z0-9_])ST-[!-~]{5,}")


class ServiceCatalogError(Exception):
    def __init__(self, status, code):
        super().__init__(code)
        self.status = status
        self.code = code


def changed():
    return ServiceCatalogError(502, "service_catalog_changed")


def fetch_json(path):
    connection = HTTPSConnection(HOST, timeout=8)
    try:
        connection.request("GET", path, headers={
            "Accept": "application/json",
            "Accept-Language": "zh-CN,zh;q=0.9",
        })
        response = connection.getresponse()
        authorization_redirect = 300 <= response.status < 400 and urlsplit(
            response.headers.get("Location", "")
        ).path in {
            "/site/login/cas-login", "/cas/login",
        }
        if response.status in (401, 403) or authorization_redirect:
            raise ServiceCatalogError(403, "service_not_public")
        if response.status != 200:
            raise ServiceCatalogError(502, "service_catalog_unavailable")
        if "json" not in response.headers.get_content_subtype().lower():
            raise changed()
        body = response.read(MAX_BYTES + 1)
        if len(body) > MAX_BYTES:
            raise changed()
        root = json.loads(body)
    except (ValueError, RecursionError) as error:
        raise changed() from error
    except (OSError, HTTPException) as error:
        raise ServiceCatalogError(502, "service_catalog_unavailable") from error
    finally:
        connection.close()

    if not isinstance(root, dict):
        raise changed()
    code = root.get("e")
    if str(code) in {"10042", "100421", "30000"}:
        raise ServiceCatalogError(403, "service_not_public")
    if str(code) != "0" or not isinstance(root.get("d"), dict):
        raise changed()
    return root["d"]


class PlainGuideText(HTMLParser):
    BLOCKS = {"p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "table"}
    HIDDEN = {"script", "style", "noscript", "iframe", "svg", "object", "template"}

    def __init__(self):
        super().__init__()
        self.segments = []
        self.skip = []

    def handle_starttag(self, tag, attrs):
        if tag in self.HIDDEN:
            self.skip.append(tag)
        elif not self.skip and tag in self.BLOCKS:
            self.segments.append("\n• " if tag == "li" else "\n")

    def handle_endtag(self, tag):
        if self.skip:
            if tag == self.skip[-1]:
                self.skip.pop()
        elif tag in self.BLOCKS:
            self.segments.append("\n")

    def handle_data(self, data):
        if not self.skip:
            self.segments.append(data)


def plain_text(raw, limit):
    if not isinstance(raw, str) or not raw:
        return "", False
    text = PlainGuideText()
    try:
        text.feed(raw)
        text.close()
    except (AssertionError, ValueError) as error:
        raise changed() from error
    result = re.sub(r"[\t \r]+", " ", "".join(text.segments))
    result = re.sub(r" *\n *", "\n", result)
    result = re.sub(r"\n{3,}", "\n\n", result).strip()
    result = URLS.sub("[链接已隐藏]", result)
    result = PRIVATE_PATH.sub("[附件链接已隐藏]", result)
    result = PRIVATE_VALUE.sub("[信息已隐藏]", result)
    result = CAS_TICKET.sub("[票据已隐藏]", result)
    clipped = len(result) > limit
    return result[:limit].rstrip(), clipped


def item_id(value):
    value = str(value)
    return value if ID_PATTERN.fullmatch(value) and int(value) <= 2**63 - 1 else None


def read_catalog():
    rows = fetch_json(CATALOG_PATH).get("list")
    if not isinstance(rows, list):
        raise changed()
    items = []
    frequencies = Counter()
    for row in rows:
        if not isinstance(row, dict):
            raise changed()
        identifier = item_id(row.get("id"))
        name, _ = plain_text(row.get("name"), 200)
        if not identifier or not name:
            raise changed()
        frequencies[identifier] += 1
        if str(row.get("is_folder")).lower() in {"1", "true"}:
            continue
        department, _ = plain_text(row.get("departName"), 200)
        items.append({"id": identifier, "name": name, "department": department})
    for row in items:
        row["ambiguous"] = frequencies[row["id"]] > 1
    return items


catalog_lock = Lock()
catalog_cache = None


def query_service_catalog():
    global catalog_cache
    with catalog_lock:
        now = monotonic()
        if not catalog_cache or now >= catalog_cache[0]:
            items = read_catalog()
            catalog_cache = (monotonic() + CATALOG_TTL_SECONDS, items)
        return {"items": catalog_cache[1]}


def query_service_guide(raw_id):
    identifier = item_id(raw_id)
    if not identifier:
        raise ServiceCatalogError(400, "invalid_query")
    matches = [item for item in query_service_catalog()["items"] if item["id"] == identifier]
    if not matches:
        raise ServiceCatalogError(404, "service_not_public")
    guide = fetch_json(GUIDE_PATH + "?" + urlencode({"id": identifier, "userview": "1"}))
    name, clipped = plain_text(guide.get("name"), 200)
    if item_id(guide.get("id")) != identifier or not name:
        raise changed()

    sections = []
    for label, key, limit in (
        ("事项说明", "explain", 8000),
        ("办理地点", "handle_place", 200),
        ("办理时间", "handling_times", 200),
        ("是否收费", "is_charge", 80),
        ("费用说明", "charge", 120),
    ):
        text, truncated = plain_text(guide.get(key), limit)
        if text:
            sections.append({"label": label, "text": text})
            clipped = clipped or truncated

    materials = guide.get("detailed_information")
    if materials is not None and not isinstance(materials, list):
        raise changed()
    for material in (materials or [])[:40]:
        if not isinstance(material, dict):
            raise changed()
        if material.get("field") in ("person", "person_tel"):
            continue
        label, _ = plain_text(material.get("label"), 120)
        text, truncated = plain_text(material.get("value"), 1000)
        if text:
            sections.append({"label": label or "办理材料", "text": text})
            clipped = clipped or truncated
    matches_by_name = [item for item in matches if item["name"].casefold() == name.casefold()]
    return {
        "name": name,
        "department": matches_by_name[0]["department"] if len(matches_by_name) == 1 else "",
        "sections": sections,
        "truncated": clipped or bool(materials and len(materials) > 40),
    }
