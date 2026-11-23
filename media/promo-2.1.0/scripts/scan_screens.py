#!/usr/bin/env python3
"""Discover emulator screenshots without touching public/screens.

Reads public/screens/, records every image with its pixel size, then writes
src/screens.generated.json for the Remotion bundle. Scene components resolve a
screen id to a real capture when one exists and otherwise fall back to a
clearly labelled, code-drawn mock screen.

Named slots (case-insensitive, extension may be png/jpg/jpeg/webp):
  home, home-demo, home-todo, home-pinned, services, services-life,
  services-admin, campus-settings, card, card-history, card-recharge,
  recharge-demo, power, power-guzhenkou, classroom, classroom-filter,
  classroom-afternoon, classroom-guzhenkou, library, library-area,
  library-seat, library-time, library-map, library-hidden, library-records,
  library-hub, library-hub-guzhenkou, schedule, course-detail, today, todo,
  todo-edit, todo-position, focus, focus-todo, focus-active, focus-stats,
  focus-records, focus-add-record, shiguang, shiguang-detail,
  shiguang-mine, estation, map, map-walk, map-bike, map-guzhenkou,
  map-origin, map-origin-wide, map-point, map-route, directory,
  directory-detail, directory-window, learn, learn-webvpn, official-web,
  official-pinned, gym, gym-swim, widgets, widget-week, widget-week-crop,
  widget-week-live, widget-today, widget-card, widget-power, widget-todo,
  widget-todo-live, widget-todo-done, settings, about, theme, theme-glass,
  diagnostics

Blank captures (directory-dial, widget-week-preview) are intentionally not
mapped. Any other filename is reported as unmapped and can be added to
ALIASES below.
"""
from __future__ import annotations

import json
import os
from datetime import datetime, timezone

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCREENS = os.path.join(ROOT, "public", "screens")
OUT = os.path.join(ROOT, "src", "screens.generated.json")

SLOTS = [
    "home", "home-demo", "home-todo", "home-pinned", "services",
    "services-life", "services-admin", "campus-settings", "card",
    "card-history", "card-recharge", "recharge-demo", "power",
    "power-guzhenkou", "classroom", "classroom-filter",
    "classroom-afternoon", "classroom-guzhenkou", "library", "library-area",
    "library-seat", "library-time", "library-map", "library-hidden",
    "library-records", "library-hub-guzhenkou",
    "schedule", "course-detail", "today", "todo",
    "todo-edit", "todo-position", "focus", "focus-todo", "focus-active",
    "focus-stats", "focus-records", "focus-add-record", "shiguang",
    "shiguang-detail", "shiguang-mine",
    "estation", "map", "map-walk", "map-bike", "map-guzhenkou",
    "map-origin", "map-origin-wide", "map-point", "map-route", "directory",
    "directory-detail", "directory-window", "learn", "learn-webvpn",
    "official-web", "official-pinned", "gym", "gym-swim", "widgets",
    "widget-week", "widget-week-crop", "widget-week-live", "widget-today",
    "widget-card", "widget-power", "widget-todo", "widget-todo-live",
    "widget-todo-done", "settings", "about", "theme",
    "theme-glass", "diagnostics",
]

# Loose aliases for captures produced under other names. Prefer the file's own
# name when it already matches a slot above.
ALIASES = {
    "service": "services",
    "service-search": "services",
    "life-services": "services-life",
    "home-search": "home",
    "campus": "campus-settings",
    "campus-preference": "campus-settings",
    "ecard": "card",
    "card-balance": "card",
    "history": "card-history",
    "card-transactions": "card-history",
    "transactions": "card-history",
    "recharge": "card-recharge",
    "top-up": "card-recharge",
    "electricity": "power",
    "electricity-query": "power",
    "energy": "power",
    "classrooms": "classroom",
    "empty-classroom": "classroom",
    "classroom-search": "classroom",
    "classroom-result": "classroom",
    "classroom-filter-panel": "classroom-filter",
    "classroom-pm": "classroom-afternoon",
    "classroom-gz": "classroom-guzhenkou",
    "library-venues": "library",
    "library-venue": "library",
    "library-hub": "library",
    "library-hub-tangdaowan": "library",
    "library-gz": "library-hub-guzhenkou",
    "library-guzhenkou": "library-hub-guzhenkou",
    "library-areas": "library-area",
    "seat": "library-seat",
    "seat-map": "library-seat",
    "library-layout": "library-seat",
    "library-record": "library-records",
    "booking-records": "library-records",
    "timetable": "schedule",
    "timetable-app": "schedule",
    "timetable-week": "schedule",
    "week": "schedule",
    "course": "course-detail",
    "timetable-detail": "course-detail",
    "tasks": "todo",
    "todo-list": "todo",
    "tasks-day": "todo",
    "focus-summary": "focus-stats",
    "stats": "focus-stats",
    "diagnostic": "diagnostics",
    "admin-services": "services-admin",
    "gym-handoff": "gym",
    "swimming": "gym-swim",
    "swim": "gym-swim",
    "sports": "gym",
    "widgets-week": "widget-week",
    "widgets-live": "widget-week-live",
    "desktop-widget": "widget-week-live",
    "widget": "widgets",
    "widgets-home": "widgets",
    "widget-today-card": "widget-today",
    "widget-balance": "widget-card",
    "stone-light": "shiguang",
    "service-portal": "estation",
    "ezton": "estation",
    "campus-map": "map",
    "campus-map-origin": "map-origin",
    "campus-map-origin-wide": "map-origin-wide",
    "campus-map-point": "map-point",
    "route": "map-route",
    "map-route-preview": "map-route",
    "contacts": "directory",
    "learning": "learn",
    "learning-platforms": "learn",
    "learning-webvpn": "learn-webvpn",
    "web": "official-web",
    "official-webpages": "official-web",
    "official": "official-web",
    "settings-appearance": "settings",
    "appearance": "theme",
    "appearance-light": "theme",
    "appearance-glass": "theme-glass",
    "theme-settings": "theme",
    "pool": "gym-swim",
    "cards": "card",
    "campus-card": "card",
}

EXTS = (".png", ".jpg", ".jpeg", ".webp")

# Deliberately blank / unusable captures that must not silently reach the film.
IGNORED = {"directory-dial", "widget-week-preview"}


def norm(name: str) -> str:
    return name.lower().replace("_", "-").replace(" ", "-")


def main() -> int:
    os.makedirs(SCREENS, exist_ok=True)
    found: dict[str, dict] = {}
    unmapped: list[str] = []
    for entry in sorted(os.listdir(SCREENS)):
        path = os.path.join(SCREENS, entry)
        if not os.path.isfile(path):
            continue
        stem, ext = os.path.splitext(entry)
        if ext.lower() not in EXTS:
            continue
        key = norm(stem)
        if key in IGNORED:
            continue
        slot = key if key in SLOTS else ALIASES.get(key)
        if slot is None:
            unmapped.append(entry)
            continue
        if slot in found:
            # Prefer the newest capture if two names map to the same slot.
            if os.path.getmtime(path) <= found[slot]["mtime"]:
                continue
        with Image.open(path) as im:
            width, height = im.size
        found[slot] = {
            "file": f"screens/{entry}",
            "width": width,
            "height": height,
            "mtime": os.path.getmtime(path),
        }

    screens = {}
    for slot in SLOTS:
        item = found.get(slot)
        if item:
            screens[slot] = {k: item[k] for k in ("file", "width", "height")}

    data = {
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "sourceDir": "public/screens",
        "screens": screens,
        "missing": [s for s in SLOTS if s not in screens],
        "unmapped": unmapped,
    }
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

    print(f"{len(screens)}/{len(SLOTS)} screenshot slots available")
    for slot in SLOTS:
        if slot in screens:
            s = screens[slot]
            print(f"  OK   {slot:16s} {s['file']}  {s['width']}x{s['height']}")
    if data["missing"]:
        print("  missing:", ", ".join(data["missing"]))
    if unmapped:
        print("  unmapped files (add an alias or rename):", ", ".join(unmapped))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
