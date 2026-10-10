package net.tfminecraft.musicalinstruments.keyboard;

import java.util.ArrayList;
import java.util.List;
import net.tfminecraft.musicalinstruments.managers.InstrumentManager;

/**
 * Maps a keyboard cell to sounds. Columns are C D E F G A B (hotbar notes 1-7).
 * Row 0 is the high octave, row 1 the instrument's normal notes, row 2 the low octave.
 */
public final class NoteMap {
    /** Scale steps above the root that make a chord: root, third and fifth. */
    private static final int[] TRIAD = {0, 2, 4};

    private NoteMap() {
    }

    public record Note(String sound, float pitch) {
    }

    /** A single note, or null when the instrument has no sound for it. */
    public static Note resolve(InstrumentManager manager, KeyboardSettings settings, String instrument,
                               int row, int column) {
        float pitch = KeyboardSettings.clampPitch(manager.getPitch(instrument));
        // keyboard.yml rows define every cell.
        String[][] rows = settings.rowsFor(instrument);
        if (rows != null) {
            return new Note(rows[row][column], pitch);
        }
        int slot = column + 1;
        String base = manager.getSoundKey(instrument, slot, false);
        if (base == null) {
            return null;
        }
        String sneak = manager.getSoundKey(instrument, slot, true);
        boolean octave = sneak != null && !sneak.contains("chord");
        return switch (row) {
            case 0 -> octave
                    ? new Note(sneak, pitch)
                    : new Note(base, KeyboardSettings.clampPitch(pitch * settings.highPitch()));
            case 1 -> new Note(base, pitch);
            default -> new Note(base, KeyboardSettings.clampPitch(pitch * settings.lowPitch()));
        };
    }

    /**
     * The chord on a note, in that note's octave. keyboard.yml chords give every cell its own
     * chord sound. Otherwise instruments with recorded chords (their Shift + note hotbar sounds)
     * play the recording, pitched for the top and bottom rows like single notes; the others play
     * root, third and fifth together from their own notes.
     */
    public static List<Note> chord(InstrumentManager manager, KeyboardSettings settings, String instrument,
                                   int row, int column) {
        String[][] chords = settings.chordsFor(instrument);
        if (chords != null) {
            return List.of(new Note(chords[row][column], KeyboardSettings.clampPitch(manager.getPitch(instrument))));
        }
        String sneak = manager.getSoundKey(instrument, column + 1, true);
        if (sneak != null && sneak.contains("chord")) {
            float pitch = KeyboardSettings.clampPitch(manager.getPitch(instrument));
            float factor = row == 0 ? settings.highPitch() : row == 1 ? 1.0f : settings.lowPitch();
            return List.of(new Note(sneak, KeyboardSettings.clampPitch(pitch * factor)));
        }
        List<Note> notes = new ArrayList<>();
        for (int cell : chordCells(row, column)) {
            Note note = resolve(manager, settings, instrument, row, cell % KeyboardFont.COLUMNS);
            if (note != null) {
                notes.add(note);
            }
        }
        return notes;
    }

    /** The keyboard cells of a chord's notes (root, third, fifth in the same row). */
    public static int[] chordCells(int row, int column) {
        int[] cells = new int[TRIAD.length];
        for (int i = 0; i < TRIAD.length; i++) {
            cells[i] = row * KeyboardFont.COLUMNS + (column + TRIAD[i]) % KeyboardFont.COLUMNS;
        }
        return cells;
    }
}
