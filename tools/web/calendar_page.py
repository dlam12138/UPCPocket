"""Read the public teaching affairs calendar without a school session."""

from datetime import date
from html.parser import HTMLParser
from http.client import HTTPException, HTTPSConnection
import re
from urllib.parse import urljoin, urlsplit


HOST = "jwc.upc.edu.cn"
COLUMN = f"https://{HOST}/603/list.htm"
MAX_HTML_BYTES = 512 * 1024
ACADEMIC_YEAR = re.compile(r"(20\d{2})\s*[-–—至]\s*(20\d{2})\s*学年校历")
ARTICLE_PATH = re.compile(r"/20\d{2}/\d{4}/c603a\d+/page\.htm")
IMAGE_PATH = re.compile(r"/_upload/article/images/(?:[\w-]+/)*[\w-]+\.(?:jpe?g|png)", re.I | re.ASCII)


class CalendarError(Exception):
    def __init__(self, status, code):
        super().__init__(code)
        self.status = status
        self.code = code


def public_url(value, path_pattern):
    try:
        url = urlsplit(urljoin(COLUMN, value))
    except ValueError:
        return None
    if (url.scheme == "https" and url.netloc == HOST and not url.query and not url.fragment
            and path_pattern.fullmatch(url.path)):
        return url.geturl()
    return None


def fetch_html(url):
    connection = HTTPSConnection(HOST, timeout=8)
    try:
        connection.request("GET", urlsplit(url).path, headers={"Accept": "text/html"})
        response = connection.getresponse()
        if response.status != 200 or response.headers.get_content_type() != "text/html":
            raise CalendarError(502, "calendar_unavailable")
        data = response.read(MAX_HTML_BYTES + 1)
        if len(data) > MAX_HTML_BYTES:
            raise CalendarError(502, "calendar_changed")
        return data.decode(response.headers.get_content_charset() or "utf-8")
    except (OSError, HTTPException) as error:
        raise CalendarError(502, "calendar_unavailable") from error
    except (ValueError, LookupError) as error:
        raise CalendarError(502, "calendar_changed") from error
    finally:
        connection.close()


def academic_year(text):
    match = ACADEMIC_YEAR.search(text)
    if match:
        first, second = map(int, match.groups())
        if second == first + 1:
            return first, second
    return None


class CalendarColumn(HTMLParser):
    def __init__(self):
        super().__init__()
        self.articles = {}

    def handle_starttag(self, tag, attrs):
        if tag != "a":
            return
        entry = dict(attrs)
        year = academic_year(entry.get("title") or "")
        url = public_url(entry.get("href") or "", ARTICLE_PATH)
        if year and url:
            self.articles.setdefault(year, url)


class CalendarArticle(HTMLParser):
    def __init__(self):
        super().__init__()
        self.title = ""
        self.published = ""
        self.images = []
        self._in_title = False
        self._in_published = False
        self._content_depth = 0

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        classes = (attributes.get("class") or "").split()
        if tag == "title":
            self._in_title = True
        if tag == "span" and "arti_update" in classes:
            self._in_published = True
        if tag == "div" and (self._content_depth or "wp_articlecontent" in classes):
            self._content_depth += 1
        if tag == "img" and self._content_depth:
            image = public_url(attributes.get("src") or "", IMAGE_PATH)
            if image and image not in self.images:
                self.images.append(image)

    def handle_endtag(self, tag):
        if tag == "title":
            self._in_title = False
        if tag == "span":
            self._in_published = False
        if tag == "div" and self._content_depth:
            self._content_depth -= 1

    def handle_data(self, data):
        if self._in_title:
            self.title += data
        if self._in_published:
            self.published += data


def query_calendar():
    column = CalendarColumn()
    column.feed(fetch_html(COLUMN))
    if not column.articles:
        raise CalendarError(502, "calendar_changed")

    year = max(column.articles)
    article_url = column.articles[year]
    article = CalendarArticle()
    article.feed(fetch_html(article_url))
    if academic_year(article.title) != year or len(article.images) != 2:
        raise CalendarError(502, "calendar_changed")

    published_date = re.search(r"\b20\d{2}-\d{2}-\d{2}\b", article.published)
    try:
        published = date.fromisoformat(published_date[0]).isoformat() if published_date else None
    except ValueError:
        published = None
    return {
        "academicYear": f"{year[0]}–{year[1]}",
        "articleUrl": article_url,
        "publishedOn": published,
        "semesters": [
            {"label": f"第{label}学期", "imageUrl": image}
            for label, image in zip(("一", "二"), article.images)
        ],
    }
