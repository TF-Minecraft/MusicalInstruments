"""Generate the keyboard font (note circles, lit circles, ring frames, options button).

Output: resourcepack/assets/tfmc_instruments/{font/keyboard.json,textures/font/keyboard/*.png}.
Copy that assets folder into the server resource pack (e.g. an ItemsAdder content pack's resourcepack/ folder).
Codepoints and sizes must match KeyboardFont.java.
"""
import json
import os
import shutil

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "assets", "tfmc_instruments")
TEX = os.path.join(OUT, "textures", "font", "keyboard")
SCALE = 4  # texels per logical pixel; one logical pixel = 2 GUI pixels

CREAM = (244, 239, 224, 255)
CREAM_RIM = (214, 205, 182, 255)
TEAL = (92, 166, 156, 255)
TEAL_DARK = (66, 132, 124, 255)
TEAL_RIM = (60, 122, 115, 255)

# name, circle diameter (GUI px), ring diameters (GUI px), circle ascent (centres the
# circle in its row's click band: band = lines * 9 px; glyph top = line y + 7 - ascent)
# The last value is GUI pixels per art pixel (small draws at full resolution so the marks fit).
SIZES = [
    ("small", 32, [36, 40, 42], 1, 1),
    ("medium", 40, [46, 50, 52], 0, 2),
    ("large", 52, [58, 64, 70], 2, 2),
]
# Width (GUI px) of the gold "CHORD" tab drawn under each circle; it is the chord click zone.
TAB_WIDTHS = {"small": 26, "medium": 30, "large": 36}
TAB_HEIGHT = 8
GOLD = (238, 206, 132, 255)
GOLD_RIM = (196, 156, 82, 255)
GOLD_INK = (110, 72, 28, 255)
AMBER = (214, 148, 52, 255)
AMBER_RIM = (170, 110, 30, 255)
TAB_FONT = {
    "C": [".##", "#..", "#..", "#..", ".##"],
    "H": ["#.#", "#.#", "###", "#.#", "#.#"],
    "O": [".#.", "#.#", "#.#", "#.#", ".#."],
    "R": ["##.", "#.#", "##.", "#.#", "#.#"],
    "D": ["##.", "#.#", "#.#", "#.#", "##."],
}
RING_ALPHA = [255, 160, 80]

# Note symbols (11 x 5) in the spirit of a lyre's note marks; one per note C..B.
SYMBOLS = {
    "C": ["..#######..",
          ".#.......#.",
          "#.#######.#",
          ".#.......#.",
          "..#######.."],
    "D": ["...#####...",
          "..#.....#..",
          ".#.......#.",
          ".#.......#.",
          "###########"],
    "E": ["...#####...",
          "..#######..",
          "...........",
          "###########",
          "#.#.#.#.#.#"],
    "F": ["###########",
          ".#.......#.",
          ".#.......#.",
          ".#########.",
          ".#.......#."],
    "G": ["###########",
          "...........",
          "###########",
          "...........",
          "###########"],
    "A": ["###########",
          ".#.......#.",
          ".#.......#.",
          ".#.......#.",
          "####...####"],
    "B": ["###########",
          "#.........#",
          "###########",
          "#.........#",
          "###########"],
}
LETTERS = {
    "C": [".##", "#..", "#..", "#..", ".##"],
    "D": ["##.", "#.#", "#.#", "#.#", "##."],
    "E": ["###", "#..", "##.", "#..", "###"],
    "F": ["###", "#..", "##.", "#..", "#.."],
    "G": [".##", "#..", "#.#", "#.#", ".##"],
    "A": [".#.", "#.#", "###", "#.#", "#.#"],
    "B": ["##.", "#.#", "##.", "#.#", "##."],
}
NOTES = ["C", "D", "E", "F", "G", "A", "B"]


def cp(size_index, offset):
    return 0xE000 + size_index * 0x100 + offset


BUTTON_CP = 0xE300
SPACE_POS = 0xE400
SPACE_NEG = 0xE410


def inside(x, y, n, r):
    c = n / 2.0
    return (x + 0.5 - c) ** 2 + (y + 0.5 - c) ** 2 <= r * r


def mark_width(img):
    """Give the top-right texel a tiny alpha so the client measures the full width."""
    w, _ = img.size
    if img.getpixel((w - 1, 0))[3] == 0:
        img.putpixel((w - 1, 0), (0, 0, 0, 1))


def upscale(logical, factor=SCALE):
    n = logical.size
    return logical.resize((n[0] * factor, n[1] * factor), Image.NEAREST)


def draw_mask(img, mask, ox, oy, color):
    for j, row in enumerate(mask):
        for i, ch in enumerate(row):
            if ch == "#":
                img.putpixel((ox + i, oy + j), color)


def circle(n, note, lit, factor=SCALE):
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    r = n / 2.0
    for y in range(n):
        for x in range(n):
            if inside(x, y, n, r):
                rim = not inside(x, y, n, r - 1)
                if lit:
                    img.putpixel((x, y), TEAL_RIM if rim else TEAL)
                else:
                    img.putpixel((x, y), CREAM_RIM if rim else CREAM)
    ink = CREAM if lit else TEAL_DARK
    sym = SYMBOLS[note]
    let = LETTERS[note]
    sw, sh = len(sym[0]), len(sym)
    gap = 1 if n >= 20 else 0
    total = sh + gap + len(let)
    top = (n - total) // 2
    draw_mask(img, sym, (n - sw) // 2, top, ink)
    draw_mask(img, let, (n - len(let[0])) // 2, top + sh + gap, ink)
    out = upscale(img, factor)
    mark_width(out)
    return out


def ring(n, alpha, thickness=1, factor=SCALE):
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    r = n / 2.0
    color = (TEAL[0], TEAL[1], TEAL[2], alpha)
    for y in range(n):
        for x in range(n):
            if inside(x, y, n, r) and not inside(x, y, n, r - thickness):
                img.putpixel((x, y), color)
    out = upscale(img, factor)
    mark_width(out)
    return out


def tab(width, lit):
    """A rounded gold label reading CHORD, at 2 texels per GUI pixel."""
    img = Image.new("RGBA", (width, TAB_HEIGHT), (0, 0, 0, 0))
    fill, rim = (AMBER, AMBER_RIM) if lit else (GOLD, GOLD_RIM)
    ink = CREAM if lit else GOLD_INK
    for y in range(TAB_HEIGHT):
        for x in range(width):
            corner = (x in (0, width - 1)) and (y in (0, TAB_HEIGHT - 1))
            if corner:
                continue
            edge = x in (0, width - 1) or y in (0, TAB_HEIGHT - 1)
            img.putpixel((x, y), rim if edge else fill)
    word = "CHORD"
    text_width = len(word) * 4 - 1
    x = (width - text_width) // 2
    for letter in word:
        draw_mask(img, TAB_FONT[letter], x, (TAB_HEIGHT - 5) // 2, ink)
        x += 4
    out = upscale(img, 2)
    mark_width(out)
    return out


def button():
    # 150 x 20 GUI px at 1 texel per GUI px like the vanilla sprite (font pages are 256 px).
    w, h = 150, 20
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    for y in range(h):
        for x in range(w):
            edge = x < 1 or x >= w - 1 or y < 1 or y >= h - 1
            corner = (x < 1 or x >= w - 1) and (y < 1 or y >= h - 1)
            if corner:
                continue
            if edge:
                c = (0, 0, 0, 255)
            elif y < 2:
                c = (170, 170, 170, 255)
            elif y >= h - 3:
                c = (86, 86, 86, 255)
            elif x < 2:
                c = (150, 150, 150, 255)
            elif x >= w - 2:
                c = (96, 96, 96, 255)
            else:
                c = (111, 111, 111, 255)
            img.putpixel((x, y), c)
    mark_width(img)
    return img


def main():
    # Only replace this script's own output; sounds/ and sounds.json belong to tools/make_harp.py.
    for generated in (os.path.join(OUT, "font"), os.path.join(OUT, "textures", "font", "keyboard")):
        if os.path.isdir(generated):
            shutil.rmtree(generated)
    os.makedirs(TEX)
    os.makedirs(os.path.join(OUT, "font"))
    providers = []

    def bitmap(name, img, height, ascent, codepoint):
        img.save(os.path.join(TEX, name + ".png"))
        providers.append({
            "type": "bitmap",
            "file": "tfmc_instruments:font/keyboard/" + name + ".png",
            "height": height,
            "ascent": ascent,
            "chars": [chr(codepoint)],
        })

    for si, (sname, d, rings, ascent, px) in enumerate(SIZES):
        n = d // px
        f = 2 * px  # texels per art pixel; 2 texels per GUI pixel everywhere
        for ni, note in enumerate(NOTES):
            bitmap(f"{sname}_{note.lower()}", circle(n, note, False, f), d, ascent, cp(si, ni))
            bitmap(f"{sname}_{note.lower()}_lit", circle(n, note, True, f), d, ascent, cp(si, 0x10 + ni))
        for k, rd in enumerate(rings):
            thick = (2 if k == 0 else 1) * (2 // px)
            bitmap(f"{sname}_ring{k}", ring(rd // px, RING_ALPHA[k], thick, f), rd, ascent + (rd - d) // 2, cp(si, 0x20 + k))
    # Button: 20 tall, top 3 px below line 0 so the label on line 1 (y+9..y+17) is centred on it.
    bitmap("options_button", button(), 20, 4, BUTTON_CP)
    for si, (sname, _, _, _, _) in enumerate(SIZES):
        # Ascent 7: the tab's top sits on its text line's top.
        bitmap(f"{sname}_chord_tab", tab(TAB_WIDTHS[sname], False), TAB_HEIGHT, 7, cp(si, 0x30))
        bitmap(f"{sname}_chord_tab_lit", tab(TAB_WIDTHS[sname], True), TAB_HEIGHT, 7, cp(si, 0x31))

    advances = {}
    for i in range(10):
        advances[chr(SPACE_POS + i)] = 1 << i
        advances[chr(SPACE_NEG + i)] = -(1 << i)
    providers.append({"type": "space", "advances": advances})

    with open(os.path.join(OUT, "font", "keyboard.json"), "w", encoding="utf-8") as f:
        json.dump({"providers": providers}, f, indent=1, ensure_ascii=True)

    # Preview sheet for humans.
    prev = Image.new("RGBA", (8 * 120, 3 * 260), (40, 44, 52, 255))
    for si, (sname, d, rings, _, _) in enumerate(SIZES):
        for ni, note in enumerate(NOTES):
            a = Image.open(os.path.join(TEX, f"{sname}_{note.lower()}.png"))
            b = Image.open(os.path.join(TEX, f"{sname}_{note.lower()}_lit.png"))
            prev.alpha_composite(a, (ni * 120 + 4, si * 260 + 4))
            prev.alpha_composite(b, (ni * 120 + 4, si * 260 + 130))
        for k in range(3):
            rimg = Image.open(os.path.join(TEX, f"{sname}_ring{k}.png"))
            prev.alpha_composite(rimg.resize((rimg.width // 2, rimg.height // 2)), (7 * 120 + 4, si * 260 + 4 + k * 80))
    prev.save(os.path.join(HERE, "preview.png"))
    print(f"{len(providers)} providers written")


if __name__ == "__main__":
    main()
