"""Load the unchanged synchronous protocol modules from tools/web."""

from __future__ import annotations

import importlib
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable


class UpstreamLoadError(RuntimeError):
    pass


@dataclass(frozen=True)
class Upstream:
    authenticate: Callable[[str, str], Any]
    authenticate_chaoxing: Callable[[str, str], Any]
    UpcLibrary: type
    ChaoxingLibrary: type
    LibrarySession: type
    ChaoxingSession: type
    LibraryError: type
    LibraryAuthError: type
    matching_record: Callable[..., Any]
    path_id: Callable[[Any], bool]
    query_classrooms: Callable[[dict[str, list[str]]], Any]
    ClassroomError: type
    query_calendar: Callable[[], Any]
    CalendarError: type
    query_service_catalog: Callable[[], Any]
    query_service_guide: Callable[[str], Any]
    ServiceCatalogError: type


_cached: Upstream | None = None
_loaded_path: Path | None = None


def _directory(path: Path) -> Path:
    resolved = path.expanduser().resolve()
    if not resolved.is_dir():
        raise UpstreamLoadError(f"upstream path is not a directory: {path}")
    return resolved


def _import(base: Path, name: str):
    try:
        module = importlib.import_module(name)
    except ImportError:
        raise UpstreamLoadError(f"cannot import upstream module {name}") from None
    origin = Path(getattr(module, "__file__", "") or "").resolve()
    if base not in origin.parents:
        raise UpstreamLoadError(f"unexpected upstream module origin for {name}")
    return module


def load(path: Path) -> Upstream:
    global _cached, _loaded_path
    base = _directory(path)
    if _cached is not None and _loaded_path == base:
        return _cached
    entry = str(base)
    if entry in sys.path:
        sys.path.remove(entry)
    sys.path.insert(0, entry)
    auth = _import(base, "library_auth")
    upc = _import(base, "library_upc")
    chaoxing_auth = _import(base, "library_chaoxing_auth")
    chaoxing = _import(base, "library_chaoxing")
    classrooms = _import(base, "classrooms")
    calendar = _import(base, "calendar_page")
    catalog = _import(base, "service_catalog")
    _cached = Upstream(
        authenticate=auth.authenticate,
        authenticate_chaoxing=chaoxing_auth.authenticate_chaoxing,
        UpcLibrary=upc.UpcLibrary,
        ChaoxingLibrary=chaoxing.ChaoxingLibrary,
        LibrarySession=auth.LibrarySession,
        ChaoxingSession=chaoxing_auth.ChaoxingSession,
        LibraryError=upc.LibraryError,
        LibraryAuthError=auth.LibraryAuthError,
        matching_record=upc.matching_record,
        path_id=upc.path_id,
        query_classrooms=classrooms.query_classrooms,
        ClassroomError=classrooms.ClassroomError,
        query_calendar=calendar.query_calendar,
        CalendarError=calendar.CalendarError,
        query_service_catalog=catalog.query_service_catalog,
        query_service_guide=catalog.query_service_guide,
        ServiceCatalogError=catalog.ServiceCatalogError,
    )
    _loaded_path = base
    return _cached
