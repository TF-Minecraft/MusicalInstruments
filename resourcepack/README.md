# Keyboard resource pack assets

The on-screen keyboard (`/instruments play`, or right-click with an instrument) draws its note
circles, rings and options button with the `tfmc_instruments:keyboard` font.

- `generate.py` (Python 3 + Pillow) regenerates `assets/tfmc_instruments/` (font JSON and PNGs).
  Codepoints and sizes must match `KeyboardFont.java`.
- Copy `assets/` into the server resource pack. On TF servers that is the ItemsAdder content pack
  `plugins/ItemsAdder/contents/tfmc_instruments/resourcepack/`, followed by `iazip`.
- Font glyph images must stay at most 256 px wide/high (client font atlas pages are 256 px).

## Celtic harp notes

The harp has its own sound for every keyboard cell (`keyboard.yml` -> `instruments.celtic_harp.rows`):
C4-B6 as `tfmc_instruments:celtic_harp.<note><octave>`. `tools/make_harp.py` builds them from the eight
recorded singles (C5-C6, from the server pack's `instruments/celtic_harp/`): the middle row is the
recordings, C6 is the recorded high C, and the other notes are shifted one octave with ffmpeg's
rubberband filter (keeps the pluck's attack and length). `tools/pitch.py` checks the result.
The .ogg files are not committed (they come from the server pack's recordings): generate them
with `python tools/make_harp.py <folder with the celtic_harp_*_single.ogg files>` before copying `assets/`.
