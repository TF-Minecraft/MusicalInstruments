"""Estimate the fundamental of .ogg files (decoded with ffmpeg) via harmonic product spectrum."""
import math
import subprocess
import sys

import numpy as np

NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]


def load(path, sr=44100):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-f", "f32le", "-ac", "1", "-ar", str(sr), "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32), sr


def f0(x, sr):
    start = int(np.argmax(np.abs(x)))
    seg = x[start + int(0.05 * sr): start + int(0.05 * sr) + 16384]
    seg = seg * np.hanning(len(seg))
    spec = np.abs(np.fft.rfft(seg, 1 << 17))
    hps = spec.copy()
    for h in (2, 3, 4):
        hps[: len(spec) // h] *= spec[::h][: len(spec) // h]
    lo = int(60 * len(spec) * 2 / sr)
    hi = int(2600 * len(spec) * 2 / sr)
    k = lo + int(np.argmax(hps[lo:hi]))
    return k * sr / (2 * (len(spec) - 1))


def name(freq):
    n = 12 * math.log2(freq / 440.0) + 69
    r = int(round(n))
    return f"{NAMES[r % 12]}{r // 12 - 1}{(n - r) * 100:+.0f}c"


if __name__ == "__main__":
    for p in sys.argv[1:]:
        x, sr = load(p)
        fr = f0(x, sr)
        rms = float(np.sqrt(np.mean(x ** 2)))
        print(f"{p.split('/')[-1]:32s} {fr:8.1f} Hz  {name(fr):8s} peak={np.max(np.abs(x)):.2f} rms={rms:.3f} len={len(x)/sr:.2f}s")
