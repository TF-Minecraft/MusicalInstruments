"""Build 21 keyboard notes per instrument from the server pack's recorded singles.

Usage: python make_notes.py <folder containing one sub-folder per instrument>
       (the pack's assets/minecraft/sounds/instruments/, e.g. lute/lute_1c_single.ogg)

Each instrument's top / middle / bottom keyboard row (C to B) becomes:
- one recorded octave (singles 1c..8c): middle = the recordings, top = the recorded high C plus
  D-B shifted up 12 semitones, bottom = the recordings shifted down 12 semitones;
- two recorded octaves (singles 1c..16c): middle = 1c..7b, top = 9c..15b (both recordings, like
  Shift + note on the hotbar), bottom = 1c..7b shifted down 12 semitones.
Instruments with recorded chords (1c..8c *_chord) get chord_<note><octave> sounds for all three
rows the same way, from the chord recordings. Shifting uses ffmpeg's rubberband filter (keeps the pluck/attack and length) and matches the
source note's loudness. Needs ffmpeg with rubberband + libvorbis, and numpy.

Output: resourcepack/assets/tfmc_instruments/sounds/<instrument>/<note><octave>.ogg, sounds.json
(events tfmc_instruments:<instrument>.<note><octave> and .chord_<note><octave>), and rewrites the instruments: block at the end
of src/main/resources/keyboard.yml to match.
"""
import json
import os
import shutil
import subprocess
import sys

import numpy as np

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(HERE, "assets", "tfmc_instruments")
NOTES = ["c", "d", "e", "f", "g", "a", "b"]
SR = 44100

# instrument id -> octave of its first recorded note (1c), from tools/pitch.py
INSTRUMENTS = {
    "accordion": 4,
    "bagpipe": 4,
    "celtic_harp": 5,
    "dulcimer": 4,
    "flute": 5,
    "kalimba": 5,
    "lute": 4,
    "trumpet": 4,
    "vielle": 4,
}


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


def write_shifted(src, factor, dest):
    base = decode(src)
    x = shift(src, factor)
    x = x * (rms(base) / max(rms(x), 1e-9))
    peak = float(np.max(np.abs(x)))
    if peak > 0.98:
        x = x * (0.98 / peak)
    encode(x, dest)


def recordings(folder, instrument, kind):
    found = {}
    for name in os.listdir(folder):
        if name.endswith(f"_{kind}.ogg"):
            number = int("".join(ch for ch in name[len(instrument) + 1:].split("_")[0] if ch.isdigit()))
            found[number] = os.path.join(folder, name)
    return found


def grid(instrument, octave, out, recorded, prefix):
    """Three rows of seven sounds from recordings 1..8 (or 1..16 with a recorded second octave)."""
    rows = {0: [], 1: [], 2: []}
    for i, note in enumerate(NOTES):
        src = recorded[i + 1]
        names = {2: f"{prefix}{note}{octave - 1}", 1: f"{prefix}{note}{octave}", 0: f"{prefix}{note}{octave + 1}"}
        shutil.copyfile(src, os.path.join(out, names[1] + ".ogg"))
        write_shifted(src, 0.5, os.path.join(out, names[2] + ".ogg"))
        if 16 in recorded:
            shutil.copyfile(recorded[i + 9], os.path.join(out, names[0] + ".ogg"))
        elif i == 0:
            shutil.copyfile(recorded[8], os.path.join(out, names[0] + ".ogg"))
        else:
            write_shifted(src, 2.0, os.path.join(out, names[0] + ".ogg"))
        for row in (0, 1, 2):
            rows[row].append(f"tfmc_instruments:{instrument}.{names[row]}")
    return rows


def build(src_root, instrument, octave):
    folder = os.path.join(src_root, instrument)
    singles = recordings(folder, instrument, "single")
    chords = recordings(folder, instrument, "chord")
    out = os.path.join(OUT, "sounds", instrument)
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(out)
    rows = grid(instrument, octave, out, singles, "")
    chord_rows = grid(instrument, octave, out, chords, "chord_") if chords else None
    return rows, chord_rows, 16 in singles


def main():
    src_root = sys.argv[1]
    sounds = {}
    yml = ["instruments:"]
    for instrument, octave in INSTRUMENTS.items():
        rows, chord_rows, two = build(src_root, instrument, octave)
        yml.append(f"  {instrument}:")
        for key, grid_rows in (("rows", rows), ("chords", chord_rows)):
            if grid_rows is None:
                continue
            yml.append(f"    {key}:")
            for row in (0, 1, 2):
                yml.append("      - [" + ", ".join(grid_rows[row]) + "]")
                for event in grid_rows[row]:
                    name = event.split(":", 1)[1]
                    inst, note = name.split(".")
                    sounds[name] = {"sounds": [{"name": f"tfmc_instruments:{inst}/{note}"}]}
        print(f"{instrument}: {'two recorded octaves' if two else 'one recorded octave'}"
              f"{', chords' if chord_rows else ''}")
    with open(os.path.join(OUT, "sounds.json"), "w", encoding="utf-8") as f:
        json.dump(dict(sorted(sounds.items())), f, indent=1)
    config = os.path.join(os.path.dirname(HERE), "src", "main", "resources", "keyboard.yml")
    with open(config, encoding="utf-8") as f:
        content = f.read()
    marker = "\ninstruments:"
    if marker not in content:
        raise SystemExit(f"{config} has no top-level 'instruments:' block to replace")
    head = content.split(marker, 1)[0]
    # Write next to it and swap in, so a failed run leaves keyboard.yml as it was.
    temp = config + ".tmp"
    try:
        with open(temp, "w", encoding="utf-8", newline="\n") as f:
            f.write(head + "\n" + "\n".join(yml) + "\n")
        os.replace(temp, config)
    finally:
        if os.path.exists(temp):
            os.remove(temp)
    print(len(sounds), "sound events")


if __name__ == "__main__":
    main()
