"""UPC CAS bootstrap and fixed-office requests for the two Chaoxing seat services."""

from dataclasses import dataclass
from html.parser import HTMLParser
from http.cookiejar import CookieJar, DefaultCookiePolicy
import json
import re
from urllib.parse import urlencode, urljoin, urlsplit
from urllib.request import HTTPCookieProcessor, ProxyHandler, Request, build_opener

from library_auth import (
    CAS_HOST, REDIRECTS, LibraryAuthError, _CasPage, _NoRedirect, _check_status,
    _json, _params, _redirect, _request,
)


OFFICE_HOST = "office.chaoxing.com"
OFFICE_ORIGIN = f"https://{OFFICE_HOST}"
TYRZFW_HOST = "tyrzfw.chaoxing.com"
PASSPORT_HOST = "passport2-api.chaoxing.com"
LANDING_HOST = "i.chaoxing.com"
AUTH_HOSTS = {CAS_HOST, OFFICE_HOST, TYRZFW_HOST, PASSPORT_HOST, LANDING_HOST}
SSO_START = "/auth3/upc/cas/init"
SSO_CALLBACK = "/auth3/upc/cas/index"
CAS_SERVICE = f"https://{TYRZFW_HOST}{SSO_CALLBACK}"
FID_ENC = "e32f8fbde51f9dba"
EAST_MAPP_ID = "4197453"
QIUSHI_SEAT_ID = "4258"
EAST_PATH = "/front/third/apps/seat/index"
EAST_ENTRY = f"{OFFICE_ORIGIN}{EAST_PATH}?{urlencode({'fidEnc': FID_ENC, 'mappId': EAST_MAPP_ID})}"
EAST_ENTRY_PATHS = {EAST_PATH, "/front/apps/seat/index"}
PASSPORT_KEYS = {"thirdinfo", "refer", "setmain", "time", "signature", "appid", "casfrom",
                 "casinfo", "casenc"}
FRONT_PATH = re.compile(r"/front/third/apps/(?:seat|seatengine)/(?:index|select)")
READ_PATH = re.compile(r"/data/apps/(?:seat|seatengine)/(?:room/list|getdrawseat|getusedtimes|getusedseatnums)")
POST_PATH = re.compile(r"/data/apps/(?:seat|seatengine)/(?:room/info|submit)")
JS_LOCATION = re.compile(r'''\bwindow\.location(?:\.href)?\s*=\s*(["'])((?:\\.|(?!\1)[^\r\n\\])*)\1''')
JS_ESCAPE = re.compile(r'''\\(?:u([0-9A-Fa-f]{4})|x([0-9A-Fa-f]{2})|([/"'\\]))''')
USER_INFO = re.compile(r"\buserLoginInfo\s*=\s*(?=\{)")


@dataclass(frozen=True, repr=False)
class ChaoxingSession:
    user_id: str
    username: str
    _office_cookie: str


class _OfficePage(HTMLParser):
    def __init__(self, body):
        super().__init__(convert_charrefs=True)
        self.scripts = []
        self.refers = []
        self._in_script = False
        self.feed(body.decode("utf-8", errors="replace"))
        self.close()

    def handle_starttag(self, tag, attributes):
        if tag == "script":
            self._in_script = True
        if tag == "input":
            attrs = dict(attributes)
            if attrs.get("id") == "refer" and attrs.get("type") == "hidden":
                self.refers.append(attrs.get("value"))

    def handle_endtag(self, tag):
        if tag == "script":
            self._in_script = False

    def handle_data(self, data):
        if self._in_script:
            self.scripts.append(data)


def _https(url):
    try:
        if any(not 33 <= ord(char) <= 126 or char == "\\" for char in url):
            raise ValueError
        parsed = urlsplit(url)
        if (parsed.scheme != "https" or parsed.hostname not in AUTH_HOSTS
                or parsed.username is not None or parsed.password is not None
                or parsed.port not in {None, 443} or "#" in url):
            raise ValueError
        return parsed
    except (TypeError, ValueError):
        raise LibraryAuthError("protocol_error") from None


def _path_params(url, host, paths):
    parsed = _https(url)
    if parsed.hostname != host or parsed.path not in paths:
        return None
    return _params(parsed.query)


def _cas_url(url):
    query = _path_params(url, CAS_HOST, {"/cas/login"})
    return (query is not None and set(query) <= {"service", "from", "noAutoRedirect"}
            and query.get("service") == CAS_SERVICE and query.get("from", "wap") == "wap"
            and query.get("noAutoRedirect", "1") == "1")


def _east_reference(url, *, complete=False):
    query = _path_params(url, OFFICE_HOST, EAST_ENTRY_PATHS)
    if query is None or query.get("fidEnc") != FID_ENC:
        return False
    if complete:
        return set(query) == {"fidEnc", "mappId"} and query["mappId"] == EAST_MAPP_ID
    return (set(query) <= {"fidEnc", "mappId", "seatId"}
            and query.get("mappId", EAST_MAPP_ID) == EAST_MAPP_ID
            and query.get("seatId", "") in {"", QIUSHI_SEAT_ID})


def _read(opener, url):
    response = _request(opener, "GET", url, validate_url=_https)
    _check_status(response.status)
    return response


def _next(url, response):
    return _redirect(url, response, validate_url=_https)


def _js_target(base, response):
    if response.status != 200:
        raise LibraryAuthError("protocol_error")
    matches = [match[2] for script in _OfficePage(response.body).scripts
               for match in JS_LOCATION.finditer(script)]
    if len(matches) != 1:
        raise LibraryAuthError("protocol_error")

    def decode(match):
        number = match[1] or match[2]
        return chr(int(number, 16)) if number is not None else match[3]

    value = JS_ESCAPE.sub(decode, matches[0])
    if any(not 33 <= ord(char) <= 126 or char == "\\" for char in value):
        raise LibraryAuthError("protocol_error")
    try:
        return urljoin(base, value)
    except ValueError:
        raise LibraryAuthError("protocol_error") from None


def _cas_login(opener, cas_url, username, password):
    page = _request(opener, "GET", cas_url, validate_url=_https)
    _check_status(page.status, allow_password_form=True)
    if page.status in REDIRECTS:
        return _next(cas_url, page)
    action, execution, submit = _CasPage(page.body).password_form(cas_url, validate_action=_cas_url)
    result = _request(opener, "POST", action, extra_headers={
        "Content-Type": "application/x-www-form-urlencoded",
        "Origin": f"https://{CAS_HOST}",
        "Referer": cas_url,
    }, form={
        "username": username, "password": password, "type": "username_password",
        "execution": execution, "_eventId": "submit", "submit": submit,
    }, validate_url=_https)
    _check_status(result.status, allow_password_form=True)
    if result.status not in REDIRECTS:
        _CasPage(result.body).check_challenge()
        raise LibraryAuthError("interaction_required")
    target = _next(action, result)
    if not _cas_url(target):
        return target
    returned = _request(opener, "GET", target, validate_url=_https)
    _check_status(returned.status, allow_password_form=True)
    if returned.status not in REDIRECTS:
        _CasPage(returned.body).check_challenge()
        raise LibraryAuthError("interaction_required")
    return _next(target, returned)


def _office_form(opener, callback):
    query = _path_params(callback, TYRZFW_HOST, {SSO_CALLBACK})
    ticket = "" if query is None else query.get("ticket", "")
    if (query is None or set(query) != {"ticket"} or not ticket.startswith("ST-")
            or not 8 <= len(ticket) <= 4096 or any(not 32 < ord(char) < 127 for char in ticket)):
        raise LibraryAuthError("protocol_error")
    result = _read(opener, callback)
    if result.status in REDIRECTS:
        callback = _next(callback, result)
        if _path_params(callback, TYRZFW_HOST, {SSO_CALLBACK}) != {}:
            raise LibraryAuthError("protocol_error")
        result = _read(opener, callback)

    passport_url = _js_target(callback, result)
    query = _path_params(passport_url, PASSPORT_HOST, {"/api/v2/casreceive"})
    if (not query or not set(query) <= PASSPORT_KEYS or not query.get("thirdinfo", "").strip()
            or not _east_reference(query.get("refer", "")) or query.get("setmain") != "true"
            or query.get("casfrom", "login") != "login"):
        raise LibraryAuthError("protocol_error")
    response = _read(opener, passport_url)
    if response.status in REDIRECTS:
        landing = _next(passport_url, response)
        if _path_params(landing, LANDING_HOST, {"", "/", "/base", "/base/"}) is None:
            raise LibraryAuthError("protocol_error")
        response = _read(opener, landing)
    if response.status != 200:
        raise LibraryAuthError("protocol_error")
    refers = _OfficePage(response.body).refers
    if len(refers) != 1 or not refers[0] or not _east_reference(refers[0], complete=True):
        raise LibraryAuthError("protocol_error")
    return refers[0]


def _identity(body):
    candidates = [(script, match.end()) for script in _OfficePage(body).scripts
                  for match in USER_INFO.finditer(script)]
    if len(candidates) != 1:
        raise LibraryAuthError("protocol_error")
    try:
        script, start = candidates[0]
        obj, _ = json.JSONDecoder().raw_decode(script, start)
        value = obj["userInfo"]["sno"]
    except (ValueError, KeyError, TypeError, RecursionError):
        raise LibraryAuthError("protocol_error") from None
    if not (isinstance(value, str) or type(value) is int) or not str(value).strip():
        raise LibraryAuthError("protocol_error")
    return str(value)


def authenticate_chaoxing(username, password):
    """Submit one normal UPC CAS form and retain only verified Office cookies."""
    username = username.strip()
    if not username or not password or len(username) > 256 or len(password) > 1024:
        raise LibraryAuthError("invalid_credentials")
    domains = {*AUTH_HOSTS, ".chaoxing.com", "chaoxing.com"}
    cookies = CookieJar(DefaultCookiePolicy(
        allowed_domains=tuple(domains | {"." + domain for domain in AUTH_HOSTS}),
        strict_ns_domain=DefaultCookiePolicy.DomainStrictNonDomain,
    ))
    opener = build_opener(_NoRedirect(), ProxyHandler({}), HTTPCookieProcessor(cookies))

    init = _next(EAST_ENTRY, _read(opener, EAST_ENTRY))
    query = _path_params(init, TYRZFW_HOST, {SSO_START})
    if (not query or not set(query) <= {"refer", "mappId"}
            or not _east_reference(query.get("refer", ""))
            or query.get("mappId", EAST_MAPP_ID) != EAST_MAPP_ID):
        raise LibraryAuthError("protocol_error")
    cas_url = _js_target(init, _read(opener, init))
    if not _cas_url(cas_url):
        raise LibraryAuthError("protocol_error")
    callback = _cas_login(opener, cas_url, username, password)
    del password
    office = _office_form(opener, callback)
    for _ in range(9):
        response = _read(opener, office)
        if response.status == 200:
            break
        office = _next(office, response)
        if not _east_reference(office, complete=True):
            raise LibraryAuthError("protocol_error")
    else:
        raise LibraryAuthError("protocol_error")

    if _identity(response.body) != username:
        raise LibraryAuthError("identity_mismatch")
    request = Request(EAST_ENTRY)
    cookies.add_cookie_header(request)
    office_cookie = request.get_header("Cookie", "")
    if ("=" not in office_cookie or len(office_cookie) > 8192
            or any(not 32 <= ord(char) < 127 for char in office_cookie)):
        raise LibraryAuthError("protocol_error")
    return ChaoxingSession(username, username, office_cookie)


def request_chaoxing(session, method, path, *, query=None, form=None):
    """Send one fixed Office request. HTML endpoints return bytes and API endpoints return JSON."""
    is_html = method == "GET" and FRONT_PATH.fullmatch(path) is not None
    if method == "GET":
        allowed = form is None and (is_html or READ_PATH.fullmatch(path))
    else:
        allowed = method == "POST" and form is not None and POST_PATH.fullmatch(path)
    if not allowed:
        raise LibraryAuthError("protocol_error")
    url = OFFICE_ORIGIN + path
    if query:
        url += "?" + urlencode(query)
    response = _request(build_opener(_NoRedirect(), ProxyHandler({})), method, url, extra_headers={
        "Cookie": session._office_cookie,
        "Accept": "text/html,application/xhtml+xml" if is_html else "application/json",
    }, form=form, validate_url=_https)
    if response.status in {401, 403} or response.status in REDIRECTS:
        raise LibraryAuthError("session_expired")
    if response.status == 429:
        raise LibraryAuthError("rate_limited")
    if not 200 <= response.status <= 299:
        raise LibraryAuthError("upstream_error")
    return response.body if is_html else _json(response)
