"""Private, loopback-only Web sessions for the UPC libraries."""

import fcntl
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import time
from collections import Counter
from datetime import datetime, timedelta
from http.cookies import CookieError, SimpleCookie
from pathlib import Path
from threading import Lock
from urllib.parse import parse_qs, urlencode
from uuid import UUID
from zoneinfo import ZoneInfo


LOGIN_COOKIE = "pocket_library_login"
SESSION_COOKIE = "pocket_library"
COOKIE_PATH = "/api/library/"
LIBRARY_ENTRY = "/?library=1#library"
LIBRARY_NAMES = {"": "唐岛湾图书馆", "east": "古镇口东区图书馆", "qiushi": "求是图书馆"}
BOOK_FIELDS = {"id", "building", "date", "room", "seat", "start", "end"}
CANCEL_FIELDS = {"id", "reservationId"}
CHINA = ZoneInfo("Asia/Shanghai")
ERROR_MESSAGES = {
    "invalid_credentials": "账号或密码未通过验证。",
    "interaction_required": "学校要求额外验证。请前往图书馆官方页面处理。",
    "network_unavailable": "无法连接学校，请检查网络后再试。",
    "tls_error": "学校连接的证书验证失败。",
    "upstream_error": "学校服务暂时不可用。",
    "access_denied": "学校未允许本次登录。",
    "identity_mismatch": "图书馆返回的身份与登录账号不一致。",
    "protocol_error": "暂时无法识别学校的登录结果。",
    "login_expired": "登录页面已过期，请重新输入。",
}


def cookie(name, value, age, path=COOKIE_PATH):
    return f"{name}={value}; Path={path}; Max-Age={age}; HttpOnly; SameSite=Strict"


def cookie_value(headers, name):
    jar = SimpleCookie()
    try:
        jar.load(headers.get("Cookie", ""))
    except CookieError:
        return ""
    return jar[name].value if name in jar else ""


def matches_secret(value, secret):
    return value.isascii() and hmac.compare_digest(value, secret)


def parse_fields(body, keys):
    fields = parse_qs(body, keep_blank_values=True, strict_parsing=True, max_num_fields=len(keys))
    if set(fields) != keys or any(len(values) != 1 for values in fields.values()):
        raise ValueError("fields")
    return {key: values[0] for key, values in fields.items()}


def login_page(nonce, message, *, name=LIBRARY_NAMES[""], path=COOKIE_PATH, entry=LIBRARY_ENTRY):
    return f"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="stylesheet" href="/styles.css">
<title>图书馆登录 · UPC Pocket</title>
</head>
<body>
<main class="page">
  <div class="page-heading">
    <a class="text-link" href="{entry}">图书馆选座</a>
    <h1>{name}登录</h1>
  </div>
  <section class="detail-card workspace-card">
    <p>使用本人学校账号。</p>
    <p role="status">{message}</p>
    <form class="todo-form" method="post" action="{path}login" autocomplete="off">
      <input type="hidden" name="nonce" value="{nonce}">
      <label class="field">学校账号 <input name="username" maxlength="128" autocapitalize="none" spellcheck="false" required></label>
      <label class="field">密码 <input name="password" type="password" maxlength="1024" required></label>
      <button class="button" type="submit">登录并返回选座</button>
    </form>
  </section>
</main>
</body>
</html>"""


class Attempts:
    def __init__(self, directory):
        self.lock = Lock()
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        directory.chmod(0o700)
        self.process_lock = os.fdopen(os.open(directory / "service.lock", os.O_RDWR | os.O_CREAT, 0o600), "r+b")
        try:
            fcntl.flock(self.process_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            self.process_lock.close()
            raise SystemExit("本机图书馆服务已在运行。") from None
        key_path = directory / "identity-key"
        try:
            with os.fdopen(os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as key_file:
                key_file.write(secrets.token_bytes(32))
        except FileExistsError:
            pass
        self.key = key_path.read_bytes()
        if len(self.key) != 32:
            raise ValueError("invalid library journal key")
        self.db = sqlite3.connect(directory / "attempts.sqlite3", check_same_thread=False)
        self.db.execute("""
            CREATE TABLE IF NOT EXISTS attempts (
                account TEXT NOT NULL, id TEXT NOT NULL, status TEXT NOT NULL,
                prepared TEXT, prior TEXT, record_id TEXT, created REAL NOT NULL,
                PRIMARY KEY (account, id)
            )
        """)
        self.db.execute("""
            CREATE TABLE IF NOT EXISTS cancellations (
                account TEXT NOT NULL, id TEXT NOT NULL, reservation TEXT NOT NULL,
                status TEXT NOT NULL, PRIMARY KEY (account, id)
            )
        """)
        self.db.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS one_pending_cancellation
            ON cancellations(account, reservation) WHERE status IN ('submitted', 'unknown')
        """)
        self.db.commit()
        (directory / "attempts.sqlite3").chmod(0o600)

    def account(self, user_id):
        return hmac.new(self.key, str(user_id).encode(), hashlib.sha256).hexdigest()

    def find(self, account, attempt):
        row = self.db.execute(
            "SELECT status, record_id FROM attempts WHERE account=? AND id=?", (account, attempt)
        ).fetchone()
        if not row:
            return None
        status, record_id = row
        result = {"id": attempt, "status": "unknown" if status in {"submitted", "elapsed"} else status}
        if record_id:
            result["recordId"] = record_id
        return result

    def unsettled(self, account):
        return self.db.execute(
            "SELECT id, prepared, prior FROM attempts WHERE account=? AND status IN ('submitted','unknown')",
            (account,),
        ).fetchall()

    def begin(self, account, attempt, prepared, records):
        with self.db:
            inserted = self.db.execute(
                "INSERT OR IGNORE INTO attempts VALUES (?, ?, 'unknown', ?, ?, NULL, ?)",
                (account, attempt, json.dumps(prepared), json.dumps([record["id"] for record in records]),
                 time.time()),
            )
            return inserted.rowcount == 1

    def finish_without_sending(self, account, attempt, status):
        with self.db:
            self.db.execute(
                "INSERT OR IGNORE INTO attempts VALUES (?, ?, ?, NULL, NULL, NULL, ?)",
                (account, attempt, status, time.time()),
            )

    def set_status(self, account, attempt, status, record_id=None):
        keep_prior = status in {"submitted", "unknown"}
        with self.db:
            self.db.execute(
                "UPDATE attempts SET status=?, record_id=?, "
                "prepared=CASE WHEN ? THEN prepared END, prior=CASE WHEN ? THEN prior END WHERE account=? AND id=?",
                (status, record_id, keep_prior or status == "confirmed", keep_prior, account, attempt),
            )

    def confirmed_details(self, account):
        return self.db.execute(
            "SELECT id, record_id, prepared FROM attempts WHERE account=? AND status='confirmed' AND prepared IS NOT NULL",
            (account,),
        ).fetchall()

    def claimed_records(self, account):
        return {row[0] for row in self.db.execute(
            "SELECT record_id FROM attempts WHERE account=? AND status='confirmed' AND record_id IS NOT NULL",
            (account,),
        )}

    def forget_details(self, account, attempt):
        with self.db:
            self.db.execute("UPDATE attempts SET prepared=NULL WHERE account=? AND id=?", (account, attempt))

    def reservation_key(self, account, reservation):
        payload = f"cancel:{account}:{reservation}".encode()
        return hmac.new(self.key, payload, hashlib.sha256).hexdigest()

    def find_cancellation(self, account, attempt):
        return self.db.execute(
            "SELECT reservation, status FROM cancellations WHERE account=? AND id=?", (account, attempt),
        ).fetchone()

    def begin_cancellation(self, account, attempt, reservation, status="unknown"):
        with self.db:
            inserted = self.db.execute(
                "INSERT OR IGNORE INTO cancellations VALUES (?, ?, ?, ?)", (account, attempt, reservation, status),
            )
            return inserted.rowcount == 1

    def set_cancellation(self, account, attempt, status):
        with self.db:
            self.db.execute("UPDATE cancellations SET status=? WHERE account=? AND id=?", (status, account, attempt))

    def reconcile_cancellations(self, account, present_ids, cancelled_ids):
        present = {self.reservation_key(account, reservation) for reservation in present_ids}
        cancelled = {self.reservation_key(account, reservation) for reservation in cancelled_ids}
        pending = set()
        for attempt, reservation, status in self.db.execute(
            "SELECT id, reservation, status FROM cancellations WHERE account=? AND status IN ('submitted','unknown')",
            (account,),
        ).fetchall():
            if reservation in cancelled or (status == "submitted" and reservation not in present):
                self.set_cancellation(account, attempt, "cancelled")
            elif reservation not in present:
                self.set_cancellation(account, attempt, "absent")
            else:
                pending.add(reservation)
        return pending


class LocalLibrary:
    def __init__(self, port, directory, *, branch="", journal=None):
        self.host = f"127.0.0.1:{port}"
        self.origin = f"http://{self.host}"
        self.branch = branch
        self.name = LIBRARY_NAMES[branch]
        self.path = COOKIE_PATH + (branch + "/" if branch else "")
        self.entry = LIBRARY_ENTRY + ("-" + branch if branch else "")
        self.login_cookie = LOGIN_COOKIE + ("_" + branch if branch else "")
        self.session_cookie = SESSION_COOKIE + ("_" + branch if branch else "")
        self.login_nonces = {}
        self.browser = self.revision = self.csrf = self.account = self.upstream = None
        self.journal = journal if journal is not None else Attempts(directory)
        self.lock = self.journal.lock

    def get(self, handler, path, query):
        if path == "login":
            with self.lock:
                now = time.monotonic()
                self.login_nonces = {key: expiry for key, expiry in self.login_nonces.items() if expiry > now}
                if len(self.login_nonces) >= 16:
                    self.login_nonces.pop(next(iter(self.login_nonces)))
                nonce = secrets.token_urlsafe(32)
                self.login_nonces[nonce] = now + 600
            error = parse_qs(query, max_num_fields=1).get("error", [""])[0]
            page = login_page(nonce, ERROR_MESSAGES.get(error, ""), name=self.name, path=self.path, entry=self.entry)
            handler.send_library_page(page, cookie(self.login_cookie, nonce, 600, self.path))
            return
        from library_upc import LibraryError

        with self.lock:
            signed_in = self.is_signed_in(handler)
            if path == "session" and not query:
                if signed_in:
                    handler.send_json(200, {"state": "ready", "revision": self.revision, "csrf": self.csrf,
                                            "recordsAvailable": not self.branch})
                else:
                    handler.send_json(200, {"state": "sign_in"})
                return
            if not self.check_session(handler):
                return
            if path == "catalog":
                params = parse_qs(query, strict_parsing=True, max_num_fields=5)
                if any(len(values) != 1 for values in params.values()):
                    raise ValueError("duplicate catalog field")
                try:
                    result = self.upstream.catalog({key: values[0] for key, values in params.items()})
                except LibraryError as error:
                    if error.status == 401:
                        self.expire()
                    raise
                handler.send_json(200, result)
                return
            if path == "reservations":
                if self.branch:
                    handler.send_json(200, {"available": False})
                    return
                params = parse_qs(query, keep_blank_values=True, strict_parsing=True, max_num_fields=2)
                if set(params) - {"attempt", "cancelAttempt"} or any(
                    len(values) != 1 or str(UUID(values[0])) != values[0] for values in params.values()
                ):
                    raise ValueError("attempt")
                attempt = params.get("attempt", [None])[0]
                cancel_attempt = params.get("cancelAttempt", [None])[0]
                try:
                    records, present, cancelled = self.upstream.read_reservations()
                except LibraryError as error:
                    if error.status == 401:
                        self.expire()
                    raise
                pending = self.reconcile(records)
                cancel_pending = self.journal.reconcile_cancellations(self.account, present, cancelled)
                result = {"pending": pending, "cancellationPending": bool(cancel_pending), "records": []}
                for record in records:
                    visible = {key: record[key] for key in ("id", "date", "start", "end", "building", "room", "seat")}
                    visible["cancellable"] = record["cancellable"]
                    visible["cancelPending"] = self.journal.reservation_key(self.account, record["id"]) in cancel_pending
                    result["records"].append(visible)
                if attempt and (outcome := self.journal.find(self.account, attempt)):
                    result["attempt"] = outcome
                if cancel_attempt and (result_row := self.journal.find_cancellation(self.account, cancel_attempt)):
                    status = result_row[1]
                    result["cancellation"] = {
                        "id": cancel_attempt, "status": "unknown" if status == "submitted" else status,
                    }
                handler.send_json(200, result)
                return
            handler.send_json(404, {"error": "unknown_route"})

    def is_signed_in(self, handler):
        return self.upstream is not None and matches_secret(cookie_value(handler.headers, self.session_cookie), self.browser)

    def check_session(self, handler, *, write=False):
        if not self.is_signed_in(handler):
            handler.send_json(401, {"error": "library_session_expired"})
        elif not matches_secret(handler.headers.get("X-Library-Session", ""), self.revision):
            handler.send_json(409, {"error": "library_session_changed"})
        elif write and not matches_secret(handler.headers.get("X-CSRF-Token", ""), self.csrf):
            handler.send_json(403, {"error": "invalid_request"})
        else:
            return True
        return False

    def login(self, handler, body):
        from library_auth import LibraryAuthError

        if self.branch:
            from library_chaoxing import ChaoxingLibrary
            from library_chaoxing_auth import authenticate_chaoxing as authenticate
        else:
            from library_auth import authenticate
            from library_upc import UpcLibrary

        fields = parse_fields(body, {"nonce", "username", "password"})
        nonce = fields["nonce"]
        with self.lock:
            nonce_valid = matches_secret(cookie_value(handler.headers, self.login_cookie), nonce) if nonce.isascii() else False
            if not nonce_valid or self.login_nonces.pop(nonce, 0) <= time.monotonic():
                reason = "login_expired"
            elif not (1 <= len(fields["username"].strip()) <= 128 and 1 <= len(fields["password"]) <= 1024):
                reason = "invalid_credentials"
            else:
                try:
                    verified = authenticate(fields["username"].strip(), fields["password"])
                except LibraryAuthError as error:
                    reason = error.code if error.code in ERROR_MESSAGES else "protocol_error"
                else:
                    self.upstream = ChaoxingLibrary(self.branch, verified) if self.branch else UpcLibrary(verified)
                    identity = f"{self.branch}:{verified.user_id}" if self.branch else verified.user_id
                    self.account = self.journal.account(identity)
                    self.browser, self.revision, self.csrf = (secrets.token_urlsafe(32) for _ in range(3))
                    print("图书馆本人核验通过", flush=True)
                    handler.redirect_library(self.entry, cookie(self.session_cookie, self.browser, 86400, self.path),
                                             cookie(self.login_cookie, "", 0, self.path))
                    return
        handler.redirect_library(self.path + "login?" + urlencode({"error": reason}),
                                 cookie(self.login_cookie, "", 0, self.path))

    def reconcile(self, records):
        from library_upc import matching_record

        claimed = self.journal.claimed_records(self.account)
        candidates = []
        for attempt, prepared_json, prior_json in self.journal.unsettled(self.account):
            prepared = json.loads(prepared_json)
            record_id = matching_record(prepared, records, claimed | set(json.loads(prior_json)))
            candidates.append((attempt, prepared, record_id))
        occurrences = Counter(record_id for _, _, record_id in candidates if record_id)

        pending = False
        for attempt, prepared, record_id in candidates:
            if record_id and occurrences[record_id] == 1:
                self.journal.set_status(self.account, attempt, "confirmed", record_id)
            elif self.booking_ended(prepared):
                self.journal.set_status(self.account, attempt, "elapsed")
            else:
                pending = True
        records_by_id = {record["id"]: record for record in records}
        for attempt, record_id, details in self.journal.confirmed_details(self.account):
            prepared = json.loads(details)
            record = records_by_id.get(record_id)
            if record:
                labels = prepared["labels"]
                record.update({key: labels[key] for key in ("building", "room", "seat")})
            elif self.booking_ended(prepared):
                self.journal.forget_details(self.account, attempt)
        return pending

    def booking_ended(self, prepared):
        try:
            date = prepared["choice"]["date"]
            label = prepared["labels"]["end"]
            end = datetime.fromisoformat(f"{date}T{'00:00' if label == '24:00' else label}").replace(tzinfo=CHINA)
            if label == "24:00":
                end += timedelta(days=1)
        except (KeyError, ValueError):
            return False
        return end.timestamp() + 3600 < time.time()

    def book(self, handler, body):
        from library_upc import LibraryError, matching_record

        selection = json.loads(body)
        if (not isinstance(selection, dict) or selection.keys() != BOOK_FIELDS
                or any(not isinstance(value, str) or not 1 <= len(value) <= 128 for value in selection.values())):
            raise ValueError("booking")
        attempt = selection.pop("id")
        if str(UUID(attempt)) != attempt:
            raise ValueError("booking id")
        with self.lock:
            if not self.check_session(handler, write=True):
                return
            if previous := self.journal.find(self.account, attempt):
                status = "unknown" if previous["status"] == "confirmed" else previous["status"]
                handler.send_json(200, {"status": status})
                return
            try:
                records = [] if self.branch else self.upstream.reservations()
                prepared = self.upstream.prepare_booking(selection)
                if matching_record(prepared, records, set(), existing=True):
                    self.journal.finish_without_sending(self.account, attempt, "rejected")
                    handler.send_json(200, {"status": "rejected"})
                    return
            except LibraryError as error:
                if error.status == 401:
                    self.expire()
                    handler.send_json(401, {"error": "library_session_expired"})
                else:
                    status = "challenge" if error.code == "captcha_required" else "rejected"
                    self.journal.finish_without_sending(self.account, attempt, status)
                    handler.send_json(200, {"status": status})
                return
            if not self.journal.begin(self.account, attempt, prepared, records):
                previous = self.journal.find(self.account, attempt)
                if previous:
                    status = previous["status"] if previous["status"] != "confirmed" else "unknown"
                else:
                    status = "rejected"
                    self.journal.finish_without_sending(self.account, attempt, status)
                handler.send_json(200, {"status": status})
                return
            status = self.upstream.submit_booking(prepared)
            if status not in {"submitted", "rejected", "unknown", "accepted", "challenge"}:
                status = "unknown"
            self.journal.set_status(self.account, attempt, status)
            handler.send_json(200, {"status": status})

    def cancel(self, handler, body):
        if self.branch:
            handler.send_json(404, {"error": "unknown_route"})
            return
        from library_upc import LibraryError, path_id

        fields = json.loads(body)
        if (not isinstance(fields, dict) or fields.keys() != CANCEL_FIELDS
                or not isinstance(fields["id"], str) or str(UUID(fields["id"])) != fields["id"]
                or not path_id(fields["reservationId"])):
            raise ValueError("cancellation")
        attempt, reservation = fields["id"], fields["reservationId"]
        with self.lock:
            if not self.check_session(handler, write=True):
                return
            account = self.account
            journal = self.journal
            key = journal.reservation_key(account, reservation)
            if previous := journal.find_cancellation(account, attempt):
                saved_key, status = previous
                if saved_key != key:
                    raise ValueError("cancellation target")
                handler.send_json(200, {"status": "unknown" if status == "submitted" else status})
                return

            try:
                records, present, cancelled = self.upstream.read_reservations()
            except LibraryError as error:
                journal.begin_cancellation(account, attempt, key, "rejected")
                if error.status == 401:
                    self.expire()
                    raise
                handler.send_json(200, {"status": "rejected"})
                return
            if key in journal.reconcile_cancellations(account, present, cancelled):
                handler.send_json(200, {"status": "unknown"})
                return
            if not any(record["id"] == reservation and record["cancellable"] for record in records):
                journal.begin_cancellation(account, attempt, key, "rejected")
                handler.send_json(200, {"status": "rejected"})
                return
            if not journal.begin_cancellation(account, attempt, key):
                handler.send_json(200, {"status": "unknown"})
                return

            status = self.upstream.cancel_reservation(reservation)
            journal.set_cancellation(account, attempt, status)
            if status != "rejected":
                status = "unknown"
                try:
                    _, present, cancelled = self.upstream.read_reservations()
                except LibraryError as error:
                    if error.status == 401:
                        self.expire()
                else:
                    journal.reconcile_cancellations(account, present, cancelled)
                    settled = journal.find_cancellation(account, attempt)[1]
                    if settled in {"cancelled", "absent"}:
                        status = settled
            handler.send_json(200, {"status": status})

    def expire(self):
        self.browser = self.revision = self.csrf = self.account = self.upstream = None

    def logout(self, handler):
        with self.lock:
            if self.check_session(handler, write=True):
                self.expire()
                handler.send_json(200, {"state": "sign_in"}, cookie(self.session_cookie, "", 0, self.path))
