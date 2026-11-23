#!/usr/bin/env python3
"""Write the Chinese SRT from the same segment timings the picture uses."""
from __future__ import annotations

import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TIMELINE = os.path.join(ROOT, "src", "timeline.generated.json")
OUT = os.path.join(ROOT, "output", "UPC-Pocket-2.1.0-Subtitles.srt")


def ts(sec: float) -> str:
    ms = int(round(sec * 1000))
    h, ms = divmod(ms, 3_600_000)
    m, ms = divmod(ms, 60_000)
    s, ms = divmod(ms, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def main() -> int:
    data = json.load(open(TIMELINE, encoding="utf-8"))
    fps = data["fps"]
    cues = []
    for scene in data["scenes"]:
        for seg in scene["segments"]:
            start = seg["from"] / fps
            end = (seg["from"] + seg["duration"]) / fps
            cues.append((start, end, seg.get("lines") or [seg["text"]]))

    lines: list[str] = []
    for i, (a, b, text_lines) in enumerate(cues, 1):
        lines.append(str(i))
        lines.append(f"{ts(a)} --> {ts(b)}")
        lines.extend(text_lines)
        lines.append("")

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))
    print(f"wrote {OUT} ({len(cues)} cues, {data['totalSeconds']}s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
