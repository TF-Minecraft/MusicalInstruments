package net.tfminecraft.musicalinstruments.keyboard;

import net.tfminecraft.musicalinstruments.managers.InstrumentManager;

/**
 * Maps a keyboard cell to a sound. Columns are C D E F G A B (hotbar notes 1-7).
 * Row 0 is the high octave, row 1 the instrument's normal notes, row 2 the low octave.
 */
public final class NoteMap {
    private NoteMap() {
    }

    public record Note(String sound, float pitch) {
    }

    /** True when the instrument's sneak sounds are chords rather than a second octave. */
    public static boolean hasChords(InstrumentManager manager, String instrument) {
        String sneak = manager.getSoundKey(instrument, 1, true);
        return sneak != null && sneak.contains("chord");
    }

    public static Note resolve(InstrumentManager manager, KeyboardSettings settings, String instrument,
                               int row, int column, boolean chordMode) {
        int slot = column + 1;
        String sneak = manager.getSoundKey(instrument, slot, true);
        boolean chords = sneak != null && sneak.contains("chord");
        float pitch = KeyboardSettings.clampPitch(manager.getPitch(instrument));
        // keyboard.yml rows define every cell; only chord mode still uses the hotbar chord sounds.
        String[][] rows = settings.rowsFor(instrument);
        if (rows != null && !(row == 1 && chordMode && chords)) {
            return new Note(rows[row][column], pitch);
        }
        if (row == 1 && chordMode && chords) {
            return new Note(sneak, pitch);
        }
        String base = manager.getSoundKey(instrument, slot, false);
        if (base == null) {
            return null;
        }
        boolean octave = sneak != null && !chords;
        return switch (row) {
            case 0 -> octave
                    ? new Note(sneak, pitch)
                    : new Note(base, KeyboardSettings.clampPitch(pitch * settings.highPitch()));
            case 1 -> new Note(base, pitch);
            default -> new Note(base, KeyboardSettings.clampPitch(pitch * settings.lowPitch()));
        };
    }
}
