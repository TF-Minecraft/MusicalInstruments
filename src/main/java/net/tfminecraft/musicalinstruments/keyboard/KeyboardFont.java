package net.tfminecraft.musicalinstruments.keyboard;

import net.kyori.adventure.key.Key;

/**
 * Codepoints and geometry of the {@code tfmc_instruments:keyboard} resource-pack font.
 * Must match {@code pack/generate.py}.
 */
public final class KeyboardFont {
    public static final Key FONT = Key.key("tfmc_instruments", "keyboard");
    public static final int COLUMNS = 7;
    public static final int ROWS = 3;
    public static final int CELLS = COLUMNS * ROWS;
    public static final char BUTTON = '\uE300';
    public static final int BUTTON_WIDTH = 150;
    private static final int SPACE_POS = 0xE400;
    private static final int SPACE_NEG = 0xE410;

    private KeyboardFont() {
    }

    /** Keyboard sizes offered in the options menu. */
    public enum Size {
        SMALL("Small", 0, 32, 44, 5, 26, 36, new int[]{36, 40, 42}),
        MEDIUM("Medium", 1, 40, 54, 6, 30, 46, new int[]{46, 50, 52}),
        LARGE("Large (GUI scale 3 or lower)", 2, 52, 72, 7, 36, 60, new int[]{58, 64, 70});

        private final String label;
        private final int index;
        private final int diameter;
        private final int pitchX;
        private final int lines;
        private final int tabWidth;
        private final int rowButtonWidth;
        private final int[] rings;

        Size(String label, int index, int diameter, int pitchX, int lines, int tabWidth, int rowButtonWidth,
             int[] rings) {
            this.label = label;
            this.index = index;
            this.diameter = diameter;
            this.pitchX = pitchX;
            this.lines = lines;
            this.tabWidth = tabWidth;
            this.rowButtonWidth = rowButtonWidth;
            this.rings = rings;
        }

        public String label() {
            return this.label;
        }

        public int diameter() {
            return this.diameter;
        }

        public int pitchX() {
            return this.pitchX;
        }

        /** Text lines per keyboard row (CHORD tabs add one more, see {@link #rowLines}). */
        public int lines() {
            return this.lines;
        }

        /** Text lines per keyboard row for a chord style; the last one is the chord strip. */
        public int rowLines(ChordStyle style) {
            return style == ChordStyle.TABS ? this.lines + 1 : this.lines;
        }

        /** Width of a button in the chord row. */
        public int rowButtonWidth() {
            return this.rowButtonWidth;
        }

        /** Three small dots marking the chord strip under a circle. */
        public char markChar(boolean lit) {
            return (char) (0xE000 + this.index * 0x100 + 0x32 + (lit ? 1 : 0));
        }

        /** Named chord button (C, Dm, Em, F, G, Am, B°) for the chord row. */
        public char rowButtonChar(int column, boolean lit) {
            return (char) (0xE000 + this.index * 0x100 + (lit ? 0x50 : 0x40) + column);
        }

        /** Width of the gold CHORD tab under each circle. */
        public int tabWidth() {
            return this.tabWidth;
        }

        public char tabChar(boolean lit) {
            return (char) (0xE000 + this.index * 0x100 + 0x30 + (lit ? 1 : 0));
        }

        public int ring(int frame) {
            return this.rings[frame];
        }

        public int ringFrames() {
            return this.rings.length;
        }

        public int gridWidth() {
            return this.pitchX * COLUMNS;
        }

        public char note(int column, boolean lit) {
            return (char) (0xE000 + this.index * 0x100 + (lit ? 0x10 : 0) + column);
        }

        public char ringChar(int frame) {
            return (char) (0xE000 + this.index * 0x100 + 0x20 + frame);
        }

        public static Size byName(String name, Size fallback) {
            if (name != null) {
                for (Size size : values()) {
                    if (size.name().equalsIgnoreCase(name)) {
                        return size;
                    }
                }
            }
            return fallback;
        }
    }

    /** Glyph advance of a bitmap glyph whose texture spans the full cell: width plus one. */
    public static int advance(int width) {
        return width + 1;
    }

    /** Invisible characters that move the pen by {@code amount} GUI pixels (negative moves left). */
    public static String space(int amount) {
        StringBuilder out = new StringBuilder();
        int base = amount < 0 ? SPACE_NEG : SPACE_POS;
        int remaining = Math.abs(amount);
        for (int bit = 9; bit >= 0 && remaining > 0; bit--) {
            int step = 1 << bit;
            while (remaining >= step) {
                out.append((char) (base + bit));
                remaining -= step;
            }
        }
        return out.toString();
    }
}
