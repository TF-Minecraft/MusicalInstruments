package net.tfminecraft.musicalinstruments.keyboard;

/** How the keyboard shows where to click for a chord. Players pick one under "Instrument Options...". */
public enum ChordStyle {
    /** One row of named chord buttons (C, Dm, Em, F, G, Am, B°) under the keyboard, middle octave. */
    ROW("Chord row"),
    /** Three small gold dots under every circle; click them for that note's chord, all octaves. */
    MARKS("Small marks under notes"),
    /** A gold CHORD tab under every circle, all octaves. */
    TABS("CHORD tabs under notes");

    private final String label;

    ChordStyle(String label) {
        this.label = label;
    }

    public String label() {
        return this.label;
    }

    public static ChordStyle byName(String name, ChordStyle fallback) {
        if (name != null) {
            for (ChordStyle style : values()) {
                if (style.name().equalsIgnoreCase(name)) {
                    return style;
                }
            }
        }
        return fallback;
    }
}
