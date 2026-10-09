"""Build 21 celtic harp notes (C4-B6) from the 8 recorded singles (C5-C6).
Middle row C5-B5 = originals, C6 = original 8c, D6-B6 = +12 semitones, C4-B4 = -12 semitones.
Pitch shifting uses ffmpeg's rubberband (keeps the pluck's attack and length); levels are
matched to the source note's RMS. Needs ffmpeg built with rubberband + libvorbis, numpy.

Usage: python make_harp.py <dir with celtic_harp_<n><note>_single.ogg>
Output: resourcepack/assets/tfmc_instruments/sounds/celtic_harp/*.ogg + sounds.json
"""
import json
import os
import shutil
import subprocess

import numpy as np

import sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "src", "celtic_harp")
OUT = os.path.join(HERE, "assets", "tfmc_instruments")
SND = os.path.join(OUT, "sounds", "celtic_harp")
NOTES = ["c", "d", "e", "f", "g", "a", "b"]
SR = 44100


def decode(path):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


def encode(samples, path):
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(SR), "-ac", "1", "-i", "-",
                    "-c:a", "libvorbis", "-q:a", "5", path], input=samples.astype(np.float32).tobytes(), check=True)


def shift(path, factor):
    window = "long" if factor < 1 else "standard"
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-af",
                          f"rubberband=pitch={factor}:transients=crisp:detector=percussive:window={window}:pitchq=quality",
                          "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


def rms(x):
    return float(np.sqrt(np.mean(x ** 2)))


def main():
    if os.path.isdir(SND):
        shutil.rmtree(SND)
    os.makedirs(SND)
    sounds = {}
    for i, n in enumerate(NOTES):
        src = os.path.join(SRC, f"celtic_harp_{i + 1}{n}_single.ogg")
        base = decode(src)
        plan = {f"{n}5": base}
        high = decode(os.path.join(SRC, "celtic_harp_8c_single.ogg")) if n == "c" else shift(src, 2.0)
        low = shift(src, 0.5)
        for name, x in ((f"{n}6", high), (f"{n}4", low)):
            if name != "c6":
                x = x * (rms(base) / max(rms(x), 1e-9))
            peak = float(np.max(np.abs(x)))
            if peak > 0.98:
                x = x * (0.98 / peak)
            plan[name] = x
        for name, x in plan.items():
            if name.endswith("5"):
                shutil.copyfile(src, os.path.join(SND, name + ".ogg"))
            else:
                encode(x, os.path.join(SND, name + ".ogg"))
            sounds[f"celtic_harp.{name}"] = {"sounds": [{"name": f"tfmc_instruments:celtic_harp/{name}"}]}
    with open(os.path.join(OUT, "sounds.json"), "w", encoding="utf-8") as f:
        json.dump(dict(sorted(sounds.items())), f, indent=1)
    print(len(sounds), "sound events")


if __name__ == "__main__":
    main()
