"""Serve UPC Pocket and private library access from Mac loopback."""

import argparse
import json
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from calendar_page import CalendarError, query_calendar
from classrooms import ClassroomError, query_classrooms
from service_catalog import ServiceCatalogError, query_service_catalog, query_service_guide


WEB_ROOT = Path(__file__).resolve().parents[2] / "web"


class PocketHandler(SimpleHTTPRequestHandler):
    # Keep-alive avoids a fresh loopback connection for each of the app's
    # static requests, which is noticeably slower in the iOS simulator.
    protocol_version = "HTTP/1.1"

    def __init__(self, *args, **kwargs):
        self.request_body_read = False
        super().__init__(*args, directory=str(WEB_ROOT), **kwargs)

    def discard_request_body(self):
        # With keep-alive, an error response must not leave the POST body in
        # the socket for the next request on the same connection.
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length <= 0:
            return
        if length > 65536:
            self.close_connection = True
            return
        self.connection.settimeout(2)
        try:
            self.rfile.read(length)
        except (TimeoutError, OSError):
            self.close_connection = True

    def do_GET(self):
        try:
            target = urlsplit(self.path)
        except ValueError:
            self.send_json(400, {"error": "invalid_query"})
            return

        if target.path.startswith("/api/library/"):
            self.handle_library(target)
            return
        if not target.path.startswith("/api/"):
            super().do_GET()
            return

        try:
            if target.path == "/api/config" and not target.query:
                # 本地回环预览保留站内登录，便于继续开发 station 模式。
                self.send_json(200, {"schoolAccess": "station"})
            elif target.path == "/api/classrooms":
                params = parse_qs(target.query, keep_blank_values=True, max_num_fields=18)
                self.send_json(200, query_classrooms(params))
            elif target.path == "/api/calendar" and not target.query:
                self.send_json(200, query_calendar())
            elif target.path == "/api/service-catalog" and not target.query:
                self.send_json(200, query_service_catalog())
            elif target.path == "/api/service-catalog/guide":
                params = parse_qs(target.query, keep_blank_values=True, max_num_fields=1)
                if set(params) != {"id"} or len(params["id"]) != 1:
                    raise ValueError("invalid guide")
                self.send_json(200, query_service_guide(params["id"][0]))
            else:
                self.send_json(404, {"error": "unknown_route"})
        except (ClassroomError, CalendarError, ServiceCatalogError) as error:
            self.send_json(error.status, {"error": error.code})
        except ValueError:
            self.send_json(400, {"error": "invalid_query"})

    def do_POST(self):
        try:
            target = urlsplit(self.path)
        except ValueError:
            self.send_json(400, {"error": "invalid_request"})
            return
        if not target.path.startswith("/api/library/"):
            self.send_json(404, {"error": "unknown_route"})
            return
        self.handle_library(target)

    def handle_library(self, target):
        library = self.server.library
        path = target.path.removeprefix("/api/library/")
        branch, separator, endpoint = path.partition("/")
        if separator and branch in {"east", "qiushi"}:
            library = self.server.additional_libraries.get(branch)
            path = endpoint
        if library is None:
            if self.command == "GET" and path == "session" and not target.query:
                self.send_json(200, {"state": "unavailable"})
            else:
                self.send_json(404, {"error": "unknown_route"})
            return
        if self.headers.get_all("Host") != [library.host] or target.scheme or target.netloc:
            self.send_json(403, {"error": "invalid_request"})
            return
        from library_upc import LibraryError

        try:
            if self.command == "GET":
                library.get(self, path, target.query)
                return
            if self.headers.get_all("Origin") != [library.origin] or target.query:
                self.send_json(403, {"error": "invalid_request"})
                return
            if path == "logout":
                library.logout(self)
                return
            media = {"login": "application/x-www-form-urlencoded", "book": "application/json",
                     "cancel": "application/json"}
            if path not in media:
                self.send_json(404, {"error": "unknown_route"})
                return
            if self.headers.get_content_type() != media[path]:
                self.send_json(415, {"error": "invalid_request"})
                return
            length_values = self.headers.get_all("Content-Length", [])
            size = int(length_values[0]) if len(length_values) == 1 else -1
            if not 0 < size <= 8192 or self.headers.get("Transfer-Encoding"):
                raise ValueError("body size")
            self.connection.settimeout(5)
            raw = self.rfile.read(size)
            self.request_body_read = True
            if len(raw) != size:
                self.close_connection = True
                raise ValueError("incomplete body")
            body = raw.decode("utf-8")
            if path == "login":
                library.login(self, body)
            elif path == "cancel":
                library.cancel(self, body)
            else:
                library.book(self, body)
        except LibraryError as error:
            self.send_json(error.status, {"error": error.code})
        except (ValueError, TimeoutError):
            self.send_json(400, {"error": "invalid_request"})

    def send_library_page(self, html, set_cookie):
        self.send_bytes(200, html.encode(), "text/html; charset=utf-8", (set_cookie,), {
            "Content-Security-Policy": "default-src 'none'; style-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
            "Referrer-Policy": "same-origin",
        })

    def redirect_library(self, target, *cookies):
        self.send_bytes(303, b"", "text/plain; charset=utf-8", cookies, {"Location": target})

    def send_json(self, status, payload, *cookies):
        if self.command == "POST" and not self.request_body_read:
            self.discard_request_body()
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_bytes(status, body, "application/json; charset=utf-8", cookies)

    def send_bytes(self, status, body, content_type, cookies=(), extra_headers=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        for value in cookies:
            self.send_header("Set-Cookie", value)
        for name, value in (extra_headers or {}).items():
            self.send_header(name, value)
        try:
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            # A new filter can cancel an outstanding request in the browser.
            pass

    def log_message(self, *args):
        # Keep URLs, request fields and upstream details out of local logs.
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--library", action=argparse.BooleanOptionalAction, default=True,
                        help="enable private library access on loopback (default: enabled)")
    args = parser.parse_args()
    with ThreadingHTTPServer(("127.0.0.1", args.port), PocketHandler) as server:
        server.library = None
        server.additional_libraries = {}
        if args.library:
            from library_local import LocalLibrary

            directory = WEB_ROOT.parent / ".local" / "web-library"
            server.library = LocalLibrary(server.server_port, directory)
            server.additional_libraries = {
                branch: LocalLibrary(server.server_port, directory, branch=branch, journal=server.library.journal)
                for branch in ("east", "qiushi")
            }
        print(f"UPC Pocket · http://127.0.0.1:{server.server_port}/", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
