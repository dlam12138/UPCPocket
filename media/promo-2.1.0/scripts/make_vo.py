#!/usr/bin/env python3
"""Synthesize the Chinese voice-over with edge-tts and build the master timeline.

Each segment in public/audio/vo-script.json is one narration sentence. The same
segment list is the single source for:
  - public/audio/vo/<id>.mp3, the narration audio
  - src/timeline.generated.json, absolute frame positions for picture and audio
  - output/UPC-Pocket-2.1.0-Subtitles.srt, cue timing from the same measured WAVs

Usage:
  python scripts/make_vo.py            # reuse existing MP3s when present
  python scripts/make_vo.py --force    # re-synthesize everything
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import subprocess
import sys
import time

import edge_tts

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(ROOT, "public", "audio", "vo-script.json")
OUTDIR = os.path.join(ROOT, "public", "audio", "vo")
TMP = os.path.join(ROOT, ".tmp-audio")


def ffprobe_duration(path: str) -> float:
    p = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=nw=1:nk=1", path],
        capture_output=True, text=True, check=True,
    )
    return float(p.stdout.strip())


def normalize(src: str, dst: str) -> None:
    """Speech normalization: -16 LUFS, 48 kHz mono, 96 kbps MP3."""
    p = subprocess.run(
        ["ffmpeg", "-y", "-i", src,
         "-af", "loudnorm=I=-16:TP=-1.5:LRA=11,highpass=f=90",
         "-ar", "48000", "-ac", "1", "-c:a", "libmp3lame", "-b:a", "96k", dst],
        capture_output=True, text=True,
    )
    if p.returncode != 0:
        print(p.stderr[-1500:])
        raise SystemExit("ffmpeg normalization failed")


async def synthesize(text: str, voice: str, rate: str, pitch: str, dst: str) -> None:
    last: Exception | None = None
    for attempt in range(3):
        try:
            communicate = edge_tts.Communicate(text, voice, rate=rate, pitch=pitch)
            with open(dst, "wb") as f:
                async for chunk in communicate.stream():
                    if chunk["type"] == "audio":
                        f.write(chunk["data"])
            if os.path.getsize(dst) > 1024:
                return
            raise RuntimeError("empty audio")
        except Exception as exc:  # network hiccups are retryable
            last = exc
            time.sleep(1.5 * (attempt + 1))
    raise SystemExit(f"edge-tts failed for {text!r}: {last}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--force", action="store_true", help="re-synthesize existing segments")
    args = parser.parse_args()

    cfg = json.load(open(SCRIPT, encoding="utf-8"))
    voice, rate = cfg["voice"], cfg["rate"]
    pitch = cfg.get("pitch", "+0Hz")
    os.makedirs(OUTDIR, exist_ok=True)
    os.makedirs(TMP, exist_ok=True)

    durations: dict[str, float] = {}
    total = 0.0
    for scene in cfg["scenes"]:
        for seg in scene["segments"]:
            dst = os.path.join(OUTDIR, seg["id"] + ".mp3")
            if args.force or not os.path.exists(dst) or os.path.getsize(dst) < 1024:
                raw = os.path.join(TMP, seg["id"] + ".mp3")
                asyncio.run(synthesize(seg["text"], voice, rate, pitch, raw))
                normalize(raw, dst)
            d = ffprobe_duration(dst)
            durations[seg["id"]] = round(d, 3)
            total += d
            print(f"{seg['id']:5s} {d:6.2f}s  {seg['text']}")

    out = {
        "voice": voice,
        "rate": rate,
        "pitch": pitch,
        "durations": durations,
        "speechSeconds": round(total, 3),
    }
    with open(os.path.join(ROOT, "public", "audio", "vo-durations.json"), "w",
              encoding="utf-8", newline="\n") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print(f"SPEECH TOTAL {total:.2f}s")

    subprocess.run([sys.executable, os.path.join(ROOT, "scripts", "build_timeline.py")],
                   check=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
