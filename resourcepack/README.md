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

## Focus outline

`assets/minecraft/shaders/core/gui.fsh` replaces the 1.21.10 menu fill shader so pure opaque
white fills are skipped. The client draws that white outline around dialog text you click (it
flashed around the keyboard after every note until the next frame arrived). Side effect: other
pure white menu fills, such as the focus border of a selected list entry, are hidden too while
the server pack is active. Re-check the shader when the client version changes.

## No background blur behind the keyboard

Only the keyboard is drawn without the menu blur. While it is open the server shows a title with
a one-GUI-pixel dark blue dot (`U+E3F0` in the keyboard font, RGB 0,0,24; red and green exactly 0) on the crosshair; titles are drawn around
the screen centre, so the dot covers the centre pixel. `assets/minecraft/post_effect/blur.json`
runs `assets/tfmc_instruments/shaders/post/keyboard_blur.fsh`, the vanilla box blur plus one
check: if the centre pixel has that colour the image passes through unblurred and the last pass
paints over the dot. Every other menu blurs as usual. The title is renewed every 2 seconds while
the keyboard is open and cleared as soon as the player moves, turns or uses an item.
