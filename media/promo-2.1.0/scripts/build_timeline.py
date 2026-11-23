#!/usr/bin/env python3
"""Build the single master timeline from measured narration durations.

Scene padding (pre/post/gap, in frames) lives in vo-script.json. Every subtitle
and every voice-over Sequence is derived from the resulting segment frames, so
picture, narration and SRT can never drift apart.
"""
from __future__ import annotations

import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(ROOT, "public", "audio", "vo-script.json")
DURATIONS = os.path.join(ROOT, "public", "audio", "vo-durations.json")
OUT = os.path.join(ROOT, "src", "timeline.generated.json")


def main() -> int:
    cfg = json.load(open(SCRIPT, encoding="utf-8"))
    measured = json.load(open(DURATIONS, encoding="utf-8"))["durations"]
    fps = int(cfg["fps"])

    cursor = 0
    scenes = []
    for scene in cfg["scenes"]:
        segs = scene["segments"]
        pre = int(scene.get("pre", 10))
        post = int(scene.get("post", 20))
        gap = int(scene.get("gap", 8))
        scene_from = cursor
        local = pre
        out_segs = []
        for i, seg in enumerate(segs):
            if seg["id"] not in measured:
                raise SystemExit(f"missing duration for {seg['id']}; run make_vo.py")
            dur = max(1, round(measured[seg["id"]] * fps))
            abs_from = scene_from + local
            out_segs.append({
                "id": seg["id"],
                "from": abs_from,
                "localFrom": local,
                "duration": dur,
                "seconds": round(measured[seg["id"]], 3),
                "text": seg["text"],
                "lines": seg.get("lines", [seg["text"]]),
            })
            local += dur + (gap if i < len(segs) - 1 else 0)
        duration = local + post
        scenes.append({
            "id": scene["id"],
            "label": scene.get("label", scene["id"]),
            "from": scene_from,
            "duration": duration,
            "segments": out_segs,
        })
        cursor += duration

    total_frames = cursor
    data = {
        "fps": fps,
        "totalFrames": total_frames,
        "totalSeconds": round(total_frames / fps, 3),
        "scenes": scenes,
    }
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

    print(f"{'scene':5s} {'from':>6s} {'frames':>6s} {'sec':>7s}  narration")
    for s in scenes:
        print(f"{s['id']:5s} {s['from']:6d} {s['duration']:6d} "
              f"{s['duration']/fps:7.2f}  {s['label']}")
    print(f"TOTAL {total_frames} frames = {total_frames/fps:.2f}s @ {fps}fps")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
