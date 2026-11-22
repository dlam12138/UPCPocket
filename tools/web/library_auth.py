"""Normal UPC CAS authentication for the personal Tangdaowan libseat service."""

from dataclasses import dataclass
from email.message import Message
from html.parser import HTMLParser
import hashlib
import hmac
from http.client import HTTPException
from http.cookiejar import CookieJar, DefaultCookiePolicy
import json
import re
import ssl
import time
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qsl, urlencode, urljoin, urlsplit
from urllib.request import HTTPRedirectHandler, HTTPCookieProcessor, ProxyHandler, Request, build_opener
from uuid import uuid4


CAS_HOST = "cas.upc.edu.cn"
LIBRARY_HOST = "libspace.upc.edu.cn"
LIBRARY_ORIGIN = f"https://{LIBRARY_HOST}"
CALLBACK = f"{LIBRARY_ORIGIN}/remote/static/casAuth/getServiceByVerifyTicket/casLogin"
ENTRY = f"{LIBRARY_ORIGIN}/remote/static/sso/login?" + urlencode({
    "redirectUrl": f"{LIBRARY_ORIGIN}/libseat/#/login",
})
REDIRECTS = {301, 302, 303, 307, 308}
MAX_BYTES = 1024 * 1024
TIMEOUT = 12
USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
)
# Public libseat frontend configuration decoded by Android UpcLibrarySigning.configKey.
SIGNING_KEY = bytes.fromhex("6c656f733363723374")
COMPONENT = r"[A-Za-z0-9_-]{1,80}"
DATE = r"[0-9]{4}-[0-9]{2}-[0-9]{2}"
GET_PATH = re.compile(
    rf"/rest/v2/(?:free/filters|user/reservations|settings|"
    rf"room/(?:stats2|layoutByDate)/{COMPONENT}/{DATE}|"
    rf"startTimesForSeat/{COMPONENT}/{DATE}|endTimesForSeat/{COMPONENT}/{DATE}/{COMPONENT})"
)
CANCEL_PATH = re.compile(rf"/rest/v2/cancel/{COMPONENT}")


class LibraryAuthError(Exception):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


@dataclass(frozen=True, repr=False)
class LibrarySession:
    token: str
    user_id: str
    username: str
    name: str


@dataclass(frozen=True, repr=False)
class _Response:
    status: int
    headers: Message
    body: bytes


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _opener(with_cookies=False):
    handlers = [_NoRedirect(), ProxyHandler({})]
    if with_cookies:
        policy = DefaultCookiePolicy(allowed_domains=(
            CAS_HOST, "." + CAS_HOST, LIBRARY_HOST, "." + LIBRARY_HOST,
        ))
        handlers.append(HTTPCookieProcessor(CookieJar(policy)))
    return build_opener(*handlers)


def _url(url):
    try:
        if any(ord(char) < 33 or ord(char) == 127 or char == "\\" for char in url):
            raise ValueError
        parsed = urlsplit(url)
        if (parsed.scheme != "https" or parsed.hostname not in {CAS_HOST, LIBRARY_HOST}
                or parsed.username is not None or parsed.password is not None
                or parsed.port not in {None, 443} or not parsed.path.startswith("/")):
            raise ValueError
        return parsed
    except (TypeError, ValueError):
        raise LibraryAuthError("protocol_error") from None


def _params(query):
    try:
        pairs = parse_qsl(query, keep_blank_values=True, strict_parsing=True, max_num_fields=24,
                          errors="strict")
    except ValueError:
        raise LibraryAuthError("protocol_error") from None
    values = dict(pairs)
    if len(values) != len(pairs):
        raise LibraryAuthError("protocol_error")
    return values


def _cas_url(url):
    parsed = _url(url)
    if parsed.hostname != CAS_HOST or parsed.path != "/cas/login" or parsed.fragment:
        return False
    query = _params(parsed.query)
    return (set(query) <= {"service", "from", "noAutoRedirect"}
            and query.get("service") == CALLBACK and query.get("from", "wap") == "wap"
            and query.get("noAutoRedirect", "1") == "1")


def _ticket_callback(url):
    parsed = _url(url)
    if parsed.hostname != LIBRARY_HOST or parsed.path != urlsplit(CALLBACK).path or parsed.fragment:
        return False
    query = _params(parsed.query)
    ticket = query.get("ticket", "")
    return (set(query) == {"ticket"} and ticket.startswith("ST-")
            and 8 <= len(ticket) <= 4096 and all(32 < ord(char) < 127 for char in ticket))


def _request(opener, method, url, *, extra_headers=None, form=None, validate_url=_url):
    validate_url(url)
    headers = {
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "User-Agent": USER_AGENT,
    }
    body = None
    if form is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded;charset=UTF-8"
        body = urlencode(form).encode("ascii")
    if extra_headers is not None:
        headers.update(extra_headers)
    request = Request(url, data=body, headers=headers, method=method)
    try:
        try:
            response = opener.open(request, timeout=TIMEOUT)
        except HTTPError as failure:
            response = failure
        with response:
            status = response.getcode()
            payload = b"" if status in REDIRECTS else response.read(MAX_BYTES + 1)
            if len(payload) > MAX_BYTES:
                raise LibraryAuthError("upstream_error")
            return _Response(status, response.headers, payload)
    except (URLError, OSError) as failure:
        cause = failure.reason if isinstance(failure, URLError) else failure
        code = "tls_error" if isinstance(cause, ssl.SSLError) else "network_unavailable"
        raise LibraryAuthError(code) from None
    except (HTTPException, ValueError):
        raise LibraryAuthError("protocol_error") from None


def _redirect(url, response, *, validate_url=_url):
    targets = response.headers.get_all("Location", [])
    if response.status not in REDIRECTS or len(targets) != 1 or not targets[0]:
        raise LibraryAuthError("protocol_error")
    if any(ord(char) < 33 or ord(char) == 127 or char == "\\" for char in targets[0]):
        raise LibraryAuthError("protocol_error")
    try:
        target = urljoin(url, targets[0])
    except ValueError:
        raise LibraryAuthError("protocol_error") from None
    validate_url(target)
    return target


_JS_INERT = re.compile(r'//[^\r\n]*|/\*[\s\S]*?\*/|"(?:\\[\s\S]|[^"\\])*"|'
                       r"'(?:\\[\s\S]|[^'\\])*'|`(?:\\[\s\S]|[^`\\])*`")
_CAPTCHA_ASSIGNMENT = re.compile(
    r"\bconfig\s*\.\s*captcha\s*(\|\|=|&&=|\?\?=|<<=|>>>=|>>=|\*\*=|"
    r"[+*/%&^|\-]=|(?<![=!<>])=(?!=|>))", re.IGNORECASE,
)
_INACTIVE_CAPTCHA = re.compile(r"\s*(?:null|false|undefined)\s*(?:;|\Z)", re.IGNORECASE)
_VOID_TAGS = set("area base br col embed hr img input link meta param source track wbr".split())
_INERT_TAGS = {"script", "style", "textarea", "template", "title"}
_CAS_FORM_FIELDS = {"type", "_eventid", "execution"}
_CREDENTIAL_ERRORS = {
    "用户名或密码错误", "账号或密码错误", "用户名或密码不正确", "账号或密码不正确",
    "密码错误", "密码不正确", "Invalidusernameorpassword", "Invalidcredentials",
}


def _hidden(attrs):
    styles = {}
    for declaration in attrs.get("style", "").lower().split(";"):
        name, separator, value = declaration.partition(":")
        if separator:
            styles.setdefault(name.strip(), []).append(value.split("!important", 1)[0].strip())
    return ("hidden" in attrs or styles.get("display") == ["none"]
            or styles.get("visibility") in (["hidden"], ["collapse"]))


class _CasPage(HTMLParser):
    def __init__(self, body):
        super().__init__(convert_charrefs=True)
        self.forms = []
        self.stack = []
        self.error = []
        self.challenge = False
        self.feed(body.decode("utf-8", errors="replace"))
        self.close()

    def handle_starttag(self, tag, attributes):
        attrs = {key: value or "" for key, value in attributes}
        parent_hidden, parent_inert, error, form = self.stack[-1][1:] if self.stack else (
            False, False, False, None,
        )
        hidden = parent_hidden or _hidden(attrs)
        inert = parent_inert or tag in _INERT_TAGS
        identifier = attrs.get("id", "").lower()
        field = attrs.get("name", "").lower()
        field_type = attrs.get("type", "").lower()
        error = error or (tag == "div" and identifier == "errordiv")
        if not hidden and not inert:
            if tag == "form" and attrs.get("aria-hidden", "").lower() != "true":
                form = (attrs, {})
                self.forms.append(form)
            self.challenge |= bool(
                (tag == "div" and identifier == "captchaparent")
                or (tag == "input" and field_type != "hidden"
                    and ({field, identifier} & {"captcha", "username_smstoken", "otp", "totp"}))
                or (tag == "img" and identifier in {"captcha", "captchaimg", "captchaimage"})
            )
        # CAS places its flow controls inside a visually hidden container.
        if (tag == "input" and form is not None and field and not inert and "disabled" not in attrs
                and (not hidden or field in _CAS_FORM_FIELDS)):
            form[1].setdefault(field, []).append((field_type, attrs.get("value", "")))
        if tag not in _VOID_TAGS:
            self.stack.append((tag, hidden, inert, error, form))

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in _VOID_TAGS:
            self.handle_endtag(tag)

    def handle_endtag(self, tag):
        for index in range(len(self.stack) - 1, -1, -1):
            if self.stack[index][0] == tag:
                del self.stack[index:]
                return

    def handle_data(self, data):
        if self.stack and self.stack[-1][0] == "script":
            syntax = _JS_INERT.sub(lambda match: (
                " " * len(match[0]) if match[0].startswith(("//", "/*"))
                else "@" * len(match[0])
            ), data)
            self.challenge |= any(
                match[1] != "=" or not _INACTIVE_CAPTCHA.match(syntax, match.end())
                for match in _CAPTCHA_ASSIGNMENT.finditer(syntax)
            )
        elif self.stack and self.stack[-1][3] and not self.stack[-1][2]:
            self.error.append(data)

    def check_challenge(self):
        if self.challenge or any(
            ("username_smstoken" in {value for _, value in fields.get("type", [])})
            for _, fields in self.forms
        ):
            raise LibraryAuthError("interaction_required")
        if "".join("".join(self.error).split()) in _CREDENTIAL_ERRORS:
            raise LibraryAuthError("invalid_credentials")

    def password_form(self, cas_url, *, validate_action=_cas_url):
        self.check_challenge()
        forms = [(attrs, fields) for attrs, fields in self.forms
                 if len(fields.get("password", [])) == 1
                 and fields["password"][0][0] == "password" and "username" in fields]
        if len(forms) != 1:
            raise LibraryAuthError("protocol_error")
        attrs, fields = forms[0]
        if attrs.get("method", "").lower() != "post":
            raise LibraryAuthError("protocol_error")
        values = {}
        for name, expected in (("type", "username_password"), ("_eventid", "submit"), ("execution", None)):
            entries = fields.get(name, [])
            if len(entries) != 1 or (expected is not None and entries[0][1] != expected):
                raise LibraryAuthError("protocol_error")
            values[name] = entries[0][1]
        execution = values["execution"]
        if not 1 <= len(execution) <= 65536 or any(
            ord(char) <= 32 or ord(char) == 127 for char in execution
        ):
            raise LibraryAuthError("protocol_error")
        action_text = attrs.get("action", "")
        if any(ord(char) < 33 or ord(char) == 127 or char == "\\" for char in action_text):
            raise LibraryAuthError("protocol_error")
        try:
            action = urljoin(cas_url, action_text)
        except ValueError:
            raise LibraryAuthError("protocol_error") from None
        if not validate_action(action):
            raise LibraryAuthError("protocol_error")
        submit = fields.get("submit", [("", "登录")])[0][1]
        return action, execution, submit


def signed_headers(token, method):
    nonce = str(uuid4())
    now_ms = str(time.time_ns() // 1_000_000)
    message = f"seat::{nonce}::{now_ms}::{method}".encode("ascii")
    return {
        "Accept": "application/json",
        "Authorization": token,
        "loginType": "PC",
        "X-request-id": nonce,
        "X-request-date": now_ms,
        "X-hmac-request-key": hmac.new(SIGNING_KEY, message, hashlib.sha256).hexdigest(),
    }


def _json(response):
    try:
        data = json.loads(response.body)
    except (ValueError, RecursionError):
        raise LibraryAuthError("protocol_error") from None
    if not isinstance(data, dict):
        raise LibraryAuthError("protocol_error")
    return data


def _check_status(status, *, allow_password_form=False):
    if status in {401, 403} and allow_password_form:
        return
    if status in {401, 402, 403, 429}:
        raise LibraryAuthError("access_denied")
    if status != 200 and status not in REDIRECTS:
        raise LibraryAuthError("upstream_error")


def _data(response):
    _check_status(response.status)
    if response.status != 200:
        raise LibraryAuthError("upstream_error")
    result = _json(response)
    code = str(result.get("code"))
    if code == "12":
        raise LibraryAuthError("access_denied")
    if (code not in {"None", "0", "200"} or result.get("success") is False
            or str(result.get("status")).lower() in {"false", "fail", "error"}):
        raise LibraryAuthError("upstream_error")
    if not isinstance(result.get("data"), dict):
        raise LibraryAuthError("protocol_error")
    return result["data"]


def _token(value):
    if not isinstance(value, str) or not 1 <= len(value) <= 4096 or any(
        not 32 < ord(char) < 127 for char in value
    ):
        raise LibraryAuthError("protocol_error")
    return value


def _follow_to_app_token(opener, target):
    for _ in range(8):
        parsed = _url(target)
        if parsed.hostname != LIBRARY_HOST:
            raise LibraryAuthError("protocol_error")
        if parsed.path == urlsplit(CALLBACK).path:
            if not _ticket_callback(target):
                raise LibraryAuthError("protocol_error")
        elif parsed.path == "/libseat" or parsed.path.startswith("/libseat/"):
            token = _params(parsed.query).get("token")
            fragment_query = parsed.fragment.partition("?")[2]
            if not fragment_query and parsed.fragment.startswith("token="):
                fragment_query = parsed.fragment
            fragment_token = _params(fragment_query).get("token")
            if token is not None or fragment_token is not None:
                if token is not None and fragment_token is not None:
                    raise LibraryAuthError("protocol_error")
                return _token(token if token is not None else fragment_token)
        else:
            raise LibraryAuthError("protocol_error")
        response = _request(opener, "GET", target)
        _check_status(response.status)
        target = _redirect(target, response)
    raise LibraryAuthError("protocol_error")


def authenticate(username: str, password: str) -> LibrarySession:
    """Use the official password form once and bind the resulting session to the input account."""
    username = username.strip()
    if not username or not password or len(username) > 256 or len(password) > 1024:
        raise LibraryAuthError("invalid_credentials")
    opener = _opener(with_cookies=True)
    entry = _request(opener, "GET", ENTRY)
    _check_status(entry.status)
    cas_url = _redirect(ENTRY, entry)
    if not _cas_url(cas_url):
        raise LibraryAuthError("protocol_error")
    page = _request(opener, "GET", cas_url)
    _check_status(page.status, allow_password_form=True)
    if page.status in REDIRECTS:
        raise LibraryAuthError("interaction_required")
    action, execution, submit = _CasPage(page.body).password_form(cas_url)
    result = _request(opener, "POST", action, extra_headers={
        "Content-Type": "application/x-www-form-urlencoded",
        "Origin": f"https://{CAS_HOST}",
        "Referer": cas_url,
    }, form={
        "username": username, "password": password, "type": "username_password",
        "execution": execution, "_eventId": "submit", "submit": submit,
    })
    del password
    _check_status(result.status, allow_password_form=True)
    if result.status not in REDIRECTS:
        _CasPage(result.body).check_challenge()
        raise LibraryAuthError("interaction_required")
    target = _redirect(action, result)
    if _cas_url(target):
        returned = _request(opener, "GET", target)
        _check_status(returned.status, allow_password_form=True)
        if returned.status not in REDIRECTS:
            _CasPage(returned.body).check_challenge()
            raise LibraryAuthError("interaction_required")
        target = _redirect(target, returned)
    if not _ticket_callback(target):
        raise LibraryAuthError("protocol_error")
    app_token = _follow_to_app_token(opener, target)
    exchange = _request(opener, "POST", f"{LIBRARY_ORIGIN}/rest/ssoAuth?" + urlencode({
        "token": app_token,
    }), extra_headers=signed_headers(app_token, "POST"), form={"token": app_token})
    seat_token = _token(_data(exchange).get("token"))
    user = _data(_request(opener, "GET", f"{LIBRARY_ORIGIN}/rest/v2/user?" + urlencode({
        "token": seat_token,
    }), extra_headers=signed_headers(seat_token, "GET")))
    user_id = user.get("id")
    school_username = user.get("username")
    if isinstance(user_id, bool) or not isinstance(user_id, (str, int)) or not str(user_id).strip():
        raise LibraryAuthError("protocol_error")
    if not isinstance(school_username, str) or school_username.strip() != username:
        raise LibraryAuthError("identity_mismatch")
    name = user.get("name")
    return LibrarySession(seat_token, str(user_id), school_username,
                          name if isinstance(name, str) and name.strip() else school_username)


def request_library(session, method, path, *, query=None, form=None, cancellation=False):
    """Send one same-host request for the supported personal library operations."""
    if cancellation:
        allowed = method == "GET" and CANCEL_PATH.fullmatch(path) and form is None
    else:
        allowed = (method == "GET" and GET_PATH.fullmatch(path) and form is None) or (
            method == "POST" and path == "/rest/v2/freeBook" and form is not None
        )
    if not allowed:
        raise LibraryAuthError("protocol_error")
    url = LIBRARY_ORIGIN + path
    if query:
        url += "?" + urlencode(query)
    response = _request(_opener(), method, url, extra_headers=signed_headers(session.token, method),
                        form=form)
    if response.status in {401, 403}:
        raise LibraryAuthError("session_expired")
    if not 200 <= response.status <= 299:
        raise LibraryAuthError("upstream_error")
    return _json(response)
