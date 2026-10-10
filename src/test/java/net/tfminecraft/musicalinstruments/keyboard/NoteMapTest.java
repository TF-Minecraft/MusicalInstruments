package net.tfminecraft.musicalinstruments.keyboard;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import net.tfminecraft.musicalinstruments.keyboard.KeyboardFont.Size;
import net.tfminecraft.musicalinstruments.keyboard.NoteMap.Note;
import net.tfminecraft.musicalinstruments.managers.InstrumentManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NoteMapTest {
    private InstrumentManager manager;
    private KeyboardSettings settings;

    static KeyboardSettings settings(Map<String, String[][]> rows) {
        return settings(rows, Map.of());
    }

    static KeyboardSettings settings(Map<String, String[][]> rows, Map<String, String[][]> chords) {
        return new KeyboardSettings(true, true, Size.MEDIUM, true, 2.0f, 0.5f, true, rows, chords);
    }

    private static String[][] harpRows() {
        String[][] rows = new String[3][KeyboardFont.COLUMNS];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < KeyboardFont.COLUMNS; c++) {
                rows[r][c] = "harp." + r + c;
            }
        }
        return rows;
    }

    @BeforeEach
    void setUp() {
        manager = mock(InstrumentManager.class);
        settings = settings(Map.of());
        when(manager.getPitch("lute")).thenReturn(1.0);
        when(manager.getPitch("bagpipe")).thenReturn(1.0);
        when(manager.getPitch("drum")).thenReturn(1.5);
        when(manager.getPitch("harp")).thenReturn(1.0);
        when(manager.getSoundKey("lute", 4, false)).thenReturn("lute_4f_single");
        when(manager.getSoundKey("lute", 4, true)).thenReturn("lute_4f_chord");
        for (int slot = 1; slot <= 7; slot++) {
            when(manager.getSoundKey("bagpipe", slot, false)).thenReturn("bagpipe_" + slot);
            when(manager.getSoundKey("bagpipe", slot, true)).thenReturn("bagpipe_" + (slot + 8));
        }
        when(manager.getSoundKey("drum", 4, false)).thenReturn("drum_4f");
        when(manager.getSoundKey("harp", 1, true)).thenReturn("harp_1c_chord");
    }

    @Test
    void pitchesTheHotbarNotesIntoThreeOctaves() {
        assertEquals(new Note("lute_4f_single", 2.0f), NoteMap.resolve(manager, settings, "lute", 0, 3));
        assertEquals(new Note("lute_4f_single", 1.0f), NoteMap.resolve(manager, settings, "lute", 1, 3));
        assertEquals(new Note("lute_4f_single", 0.5f), NoteMap.resolve(manager, settings, "lute", 2, 3));
    }

    @Test
    void usesRecordedSecondOctavesForTheTopRow() {
        assertEquals(new Note("bagpipe_12", 1.0f), NoteMap.resolve(manager, settings, "bagpipe", 0, 3));
        assertEquals(new Note("bagpipe_4", 1.0f), NoteMap.resolve(manager, settings, "bagpipe", 1, 3));
    }

    @Test
    void clampsPitchesToTheClientRange() {
        assertEquals(new Note("drum_4f", 2.0f), NoteMap.resolve(manager, settings, "drum", 0, 3));
        assertEquals(new Note("drum_4f", 0.75f), NoteMap.resolve(manager, settings, "drum", 2, 3));
    }

    @Test
    void missingNotesPlayNothing() {
        assertNull(NoteMap.resolve(manager, settings, "lute", 1, 0));
    }

    @Test
    void configuredRowsGiveEveryCellItsOwnSound() {
        KeyboardSettings withRows = settings(Map.of("harp", harpRows(), "drum", harpRows()));
        assertEquals(new Note("harp.03", 1.0f), NoteMap.resolve(manager, withRows, "harp", 0, 3));
        assertEquals(new Note("harp.13", 1.0f), NoteMap.resolve(manager, withRows, "harp", 1, 3));
        assertEquals(new Note("harp.23", 1.0f), NoteMap.resolve(manager, withRows, "harp", 2, 3));
        assertEquals(new Note("harp.13", 1.5f), NoteMap.resolve(manager, withRows, "drum", 1, 3));
    }

    @Test
    void recordedChordsPlayInTheRowsOctave() {
        assertEquals(List.of(new Note("lute_4f_chord", 2.0f)), NoteMap.chord(manager, settings, "lute", 0, 3));
        assertEquals(List.of(new Note("lute_4f_chord", 1.0f)), NoteMap.chord(manager, settings, "lute", 1, 3));
        assertEquals(List.of(new Note("lute_4f_chord", 0.5f)), NoteMap.chord(manager, settings, "lute", 2, 3));
        // Also for instruments with their own sound per cell.
        KeyboardSettings withRows = settings(Map.of("harp", harpRows()));
        assertEquals(List.of(new Note("harp_1c_chord", 1.0f)), NoteMap.chord(manager, withRows, "harp", 1, 0));
    }

    @Test
    void configuredChordsGiveEveryCellItsOwnChord() {
        KeyboardSettings withChords = settings(Map.of("harp", harpRows()), Map.of("harp", harpRows(), "drum", harpRows()));
        assertEquals(List.of(new Note("harp.04", 1.0f)), NoteMap.chord(manager, withChords, "harp", 0, 4));
        assertEquals(List.of(new Note("harp.20", 1.0f)), NoteMap.chord(manager, withChords, "harp", 2, 0));
        assertEquals(List.of(new Note("harp.13", 1.5f)), NoteMap.chord(manager, withChords, "drum", 1, 3));
    }

    @Test
    void otherInstrumentsPlayRootThirdAndFifthTogether() {
        // C chord on the bottom row: C, E, G at the low pitch.
        assertEquals(List.of(new Note("bagpipe_1", 0.5f), new Note("bagpipe_3", 0.5f), new Note("bagpipe_5", 0.5f)),
                NoteMap.chord(manager, settings, "bagpipe", 2, 0));
        // A chord wraps within the row: A, C, E.
        assertEquals(List.of(new Note("bagpipe_6", 1.0f), new Note("bagpipe_1", 1.0f), new Note("bagpipe_3", 1.0f)),
                NoteMap.chord(manager, settings, "bagpipe", 1, 5));
        KeyboardSettings withRows = settings(Map.of("harp", harpRows()));
        assertEquals(List.of(new Note("harp.04", 1.0f), new Note("harp.06", 1.0f), new Note("harp.01", 1.0f)),
                NoteMap.chord(manager, withRows, "harp", 0, 4));
        // Missing notes are left out.
        assertEquals(List.of(new Note("lute_4f_single", 1.0f)), NoteMap.chord(manager, settings, "lute", 1, 1));
        assertTrue(NoteMap.chord(manager, settings, "drum", 1, 0).isEmpty());
    }

    @Test
    void chordCellsAreTheTriadInTheSameRow() {
        assertArrayEquals(new int[]{0, 2, 4}, NoteMap.chordCells(0, 0));
        assertArrayEquals(new int[]{19, 14, 16}, NoteMap.chordCells(2, 5));
    }
}
