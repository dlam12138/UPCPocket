#!/usr/bin/env python3
"""Synthesize an original ambient bed and soft UI sound design.

Everything here is generated from sine/noise primitives; no third-party samples.
The bed is deliberately quiet (mixed at ~ -18 dB under the voice) and follows
the same master timeline as picture and narration.
"""
from __future__ import annotations

import json
import os
import subprocess
import wave

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "public", "audio")
SFX = os.path.join(OUT, "sfx")
SR = 48000
TIMELINE = os.path.join(ROOT, "src", "timeline.generated.json")


def ffprobe_duration(path: str) -> float:
    p = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=nw=1:nk=1", path],
        capture_output=True, text=True, check=True,
    )
    return float(p.stdout.strip())


def write_wav(path: str, x: np.ndarray, channels: int = 2) -> None:
    x = np.asarray(x, dtype=np.float64)
    peak = np.max(np.abs(x)) or 1.0
    x = x / peak * 0.82
    pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def midi(n: float) -> float:
    return 440.0 * 2 ** ((n - 69) / 12)


def note_wave(freq: float, dur: float, amp: float, *, attack=0.02, decay=None,
              harmonics=(1.0, 0.35, 0.12), detune=0.0) -> np.ndarray:
    n = int(dur * SR)
    t = np.arange(n) / SR
    env = np.minimum(1.0, t / max(attack, 1e-4))
    if decay is not None:
        env *= np.exp(-t / decay)
    else:
        # slow pad envelope
        rel = np.minimum(1.0, (dur - t) / min(0.9, dur * 0.35))
        env *= np.maximum(0.0, rel)
    sig = np.zeros(n)
    for k, h in enumerate(harmonics, 1):
        f = freq * k * (1.0 + detune * (k - 1))
        sig += h * np.sin(2 * np.pi * f * t + 0.17 * k)
    return amp * env * sig


def stereo(x: np.ndarray, pan: float = 0.0) -> np.ndarray:
    """Constant-power pan, pan in [-1, 1]."""
    a = (pan + 1) * np.pi / 4
    return np.stack([x * np.cos(a), x * np.sin(a)], axis=1)


def add_at(dst: np.ndarray, src_stereo: np.ndarray, start: float) -> None:
    i = int(start * SR)
    j = min(len(dst), i + len(src_stereo))
    if i < len(dst):
        dst[i:j] += src_stereo[: j - i]


def build_bed(total: float) -> np.ndarray:
    n = int((total + 2.0) * SR)
    bed = np.zeros((n, 2))

    # Warm, unresolved progression: Cmaj9 - G/B - Am7 - Fmaj7 (MIDI roots).
    chords = [
        [48, 52, 55, 59, 62],
        [47, 50, 54, 59, 62],
        [45, 48, 52, 55, 59],
        [41, 48, 52, 55, 60],
        [48, 52, 55, 59, 62],
        [43, 50, 55, 59, 62],
        [45, 48, 52, 55, 59],
        [41, 45, 48, 52, 55],
    ]
    bar = 4.2
    t = 0.0
    idx = 0
    while t < total:
        chord = chords[idx % len(chords)]
        # pad, two detuned voices, wide stereo
        for k, m in enumerate(chord):
            pan = -0.55 + 1.1 * (k / max(1, len(chord) - 1))
            voice = note_wave(midi(m), min(bar + 0.9, total - t + 1.2), 0.045,
                              attack=0.55, harmonics=(1.0, 0.18), detune=0.0025)
            voice2 = note_wave(midi(m) * 1.003, min(bar + 0.9, total - t + 1.2), 0.022,
                               attack=0.8, harmonics=(1.0,), detune=0.0)
            add_at(bed, stereo(voice + voice2, pan), t)
        # low root
        root = note_wave(midi(chord[0] - 12), bar * 1.05, 0.055, attack=0.35,
                         harmonics=(1.0, 0.08))
        add_at(bed, stereo(root, 0.0), t)

        # sparse plucked arpeggio, eighth notes, gentle delay
        pattern = [0, 2, 1, 3, 2, 4, 3, 1]
        for step, p in enumerate(pattern):
            if (idx + step) % 3 == 2:
                continue
            m = chord[p % len(chord)] + 12
            pluck = note_wave(midi(m), 1.6, 0.026, attack=0.004, decay=0.42,
                              harmonics=(1.0, 0.22, 0.06))
            pan = 0.5 if step % 2 else -0.5
            start = t + step * 0.525
            add_at(bed, stereo(pluck, pan), start)
            add_at(bed, stereo(pluck * 0.28, -pan), start + 0.42)

        t += bar
        idx += 1

    # air: very quiet filtered noise, slowly breathing
    rng = np.random.default_rng(21)
    raw = rng.normal(0, 1, n)
    smooth = np.convolve(raw, np.ones(240) / 240, mode="same")
    tt = np.arange(n) / SR
    air = smooth * (0.008 + 0.004 * (0.5 + 0.5 * np.sin(2 * np.pi * tt / 17)))
    bed[:, 0] += air
    bed[:, 1] += air * 0.92

    # global fades
    fade_in = int(2.2 * SR)
    fade_out = int(3.5 * SR)
    bed[:fade_in] *= np.linspace(0, 1, fade_in)[:, None]
    bed[-fade_out:] *= np.linspace(1, 0, fade_out)[:, None]
    return bed


def make_sfx() -> None:
    os.makedirs(SFX, exist_ok=True)
    sr = SR

    def save(name: str, x: np.ndarray, gain: float = 1.0) -> None:
        x = np.asarray(x, dtype=np.float64) * gain
        peak = np.max(np.abs(x)) or 1.0
        x = x / peak * 0.7
        n = int(0.006 * sr)
        if len(x) > 2 * n:
            x[:n] *= np.linspace(0, 1, n)
            x[-n:] *= np.linspace(1, 0, n)
        pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16)
        wav = os.path.join(SFX, name + ".wav")
        with wave.open(wav, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(sr)
            w.writeframes(pcm.tobytes())
        mp3 = os.path.join(SFX, name + ".mp3")
        subprocess.run(["ffmpeg", "-y", "-i", wav, "-c:a", "libmp3lame",
                        "-b:a", "112k", mp3], capture_output=True, check=True)
        os.remove(wav)

    # soft band-passed whoosh
    dur = 0.7
    nn = int(dur * sr)
    tt = np.arange(nn) / sr
    rng = np.random.default_rng(3)
    noise = rng.normal(0, 1, nn)
    smooth = np.convolve(noise, np.ones(18) / 18, mode="same")
    env = np.sin(np.pi * np.clip(tt / dur, 0, 1)) ** 1.8
    save("whoosh-soft", smooth * env)

    # two-note glass chime
    nn = int(1.6 * sr)
    tt = np.arange(nn) / sr
    x = np.zeros(nn)
    for f, d in ((1318.5, 0.0), (1975.5, 0.12), (2637.0, 0.24)):
        s = int(d * sr)
        t2 = np.arange(nn - s) / sr
        x[s:] += np.sin(2 * np.pi * f * t2) * np.exp(-t2 * 4.2) * 0.5
    save("chime", x)

    # tiny UI pop
    nn = int(0.14 * sr)
    tt = np.arange(nn) / sr
    f = 820 * np.exp(-tt * 16) + 260
    save("pop", np.sin(2 * np.pi * np.cumsum(f) / sr) * np.exp(-tt * 30))

    # low, soft impact
    nn = int(1.1 * sr)
    tt = np.arange(nn) / sr
    f = 84 * np.exp(-tt * 4) + 42
    body = np.sin(2 * np.pi * np.cumsum(f) / sr) * np.exp(-tt * 3.6)
    click = rng.normal(0, 1, nn) * np.exp(-tt * 80) * 0.25
    save("impact-low", body + click)

    # tick
    nn = int(0.09 * sr)
    tt = np.arange(nn) / sr
    save("tick-ui", np.sin(2 * np.pi * 2300 * tt) * np.exp(-tt * 90) +
         rng.normal(0, 1, nn) * np.exp(-tt * 220) * 0.4)


def main() -> int:
    total = 127.5
    if os.path.exists(TIMELINE):
        total = json.load(open(TIMELINE, encoding="utf-8"))["totalSeconds"]

    bed = build_bed(total)
    wav = os.path.join(OUT, "bgm.wav")
    write_wav(wav, bed)
    mp3 = os.path.join(OUT, "bgm.mp3")
    subprocess.run(["ffmpeg", "-y", "-i", wav, "-c:a", "libmp3lame",
                    "-b:a", "160k", mp3], capture_output=True, check=True)
    os.remove(wav)
    make_sfx()
    print(f"bgm {ffprobe_duration(mp3):.2f}s -> {mp3}")
    print("sfx ->", SFX)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
