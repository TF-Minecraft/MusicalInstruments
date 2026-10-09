# Keyboard resource pack assets

The on-screen keyboard (`/instruments play`, or right-click with an instrument) draws its note
circles, rings and options button with the `tfmc_instruments:keyboard` font.

- `generate.py` (Python 3 + Pillow) regenerates `assets/tfmc_instruments/` (font JSON and PNGs).
  Codepoints and sizes must match `KeyboardFont.java`.
- Copy `assets/` into the server resource pack. On TF servers that is the ItemsAdder content pack
  `plugins/ItemsAdder/contents/tfmc_instruments/resourcepack/`, followed by `iazip`.
- Font glyph images must stay at most 256 px wide/high (client font atlas pages are 256 px).
