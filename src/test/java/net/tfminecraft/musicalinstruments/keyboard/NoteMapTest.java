package net.tfminecraft.musicalinstruments.keyboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        return new KeyboardSettings(true, true, Size.MEDIUM, true, 2.0f, 0.5f, true, rows);
    }

    @BeforeEach
    void setUp() {
        manager = mock(InstrumentManager.class);
        settings = settings(Map.of());
        when(manager.getPitch("lute")).thenReturn(1.0);
        when(manager.getPitch("bagpipe")).thenReturn(1.0);
        when(manager.getPitch("drum")).thenReturn(1.5);
        when(manager.getSoundKey("lute", 4, false)).thenReturn("lute_4f_single");
        when(manager.getSoundKey("lute", 4, true)).thenReturn("lute_4f_chord");
        when(manager.getSoundKey("lute", 1, true)).thenReturn("lute_1c_chord");
        when(manager.getSoundKey("bagpipe", 4, false)).thenReturn("bagpipe_4f_single");
        when(manager.getSoundKey("bagpipe", 4, true)).thenReturn("bagpipe_12f_single");
        when(manager.getSoundKey("bagpipe", 1, true)).thenReturn("bagpipe_9c_single");
        when(manager.getSoundKey("drum", 4, false)).thenReturn("drum_4f");
    }

    @Test
    void knowsWhichInstrumentsHaveChords() {
        assertTrue(NoteMap.hasChords(manager, "lute"));
        assertFalse(NoteMap.hasChords(manager, "bagpipe"));
        assertFalse(NoteMap.hasChords(manager, "drum"));
    }

    @Test
    void pitchesTheHotbarNotesIntoThreeOctaves() {
        assertEquals(new Note("lute_4f_single", 2.0f), NoteMap.resolve(manager, settings, "lute", 0, 3, false));
        assertEquals(new Note("lute_4f_single", 1.0f), NoteMap.resolve(manager, settings, "lute", 1, 3, false));
        assertEquals(new Note("lute_4f_single", 0.5f), NoteMap.resolve(manager, settings, "lute", 2, 3, false));
    }

    @Test
    void usesRecordedSecondOctavesForTheTopRow() {
        assertEquals(new Note("bagpipe_12f_single", 1.0f), NoteMap.resolve(manager, settings, "bagpipe", 0, 3, false));
        // Chord mode does nothing without chord sounds.
        assertEquals(new Note("bagpipe_4f_single", 1.0f), NoteMap.resolve(manager, settings, "bagpipe", 1, 3, true));
    }

    @Test
    void chordModePlaysChordsOnTheMiddleRow() {
        assertEquals(new Note("lute_4f_chord", 1.0f), NoteMap.resolve(manager, settings, "lute", 1, 3, true));
        assertEquals(new Note("lute_4f_single", 0.5f), NoteMap.resolve(manager, settings, "lute", 2, 3, true));
    }

    @Test
    void clampsPitchesToTheClientRange() {
        assertEquals(new Note("drum_4f", 2.0f), NoteMap.resolve(manager, settings, "drum", 0, 3, false));
        assertEquals(new Note("drum_4f", 0.75f), NoteMap.resolve(manager, settings, "drum", 2, 3, false));
    }

    @Test
    void missingNotesPlayNothing() {
        assertNull(NoteMap.resolve(manager, settings, "lute", 1, 0, false));
    }

    @Test
    void configuredRowsGiveEveryCellItsOwnSound() {
        String[][] rows = new String[KeyboardFont.ROWS][KeyboardFont.COLUMNS];
        for (int r = 0; r < KeyboardFont.ROWS; r++) {
            for (int c = 0; c < KeyboardFont.COLUMNS; c++) {
                rows[r][c] = "harp." + r + c;
            }
        }
        KeyboardSettings withRows = settings(Map.of("lute", rows, "drum", rows));
        assertEquals(new Note("harp.03", 1.0f), NoteMap.resolve(manager, withRows, "lute", 0, 3, false));
        assertEquals(new Note("harp.13", 1.0f), NoteMap.resolve(manager, withRows, "lute", 1, 3, false));
        assertEquals(new Note("harp.23", 1.0f), NoteMap.resolve(manager, withRows, "lute", 2, 3, true));
        // Rows work without any hotbar sound, at the instrument's own pitch.
        assertEquals(new Note("harp.10", 1.0f), NoteMap.resolve(manager, withRows, "lute", 1, 0, false));
        assertEquals(new Note("harp.13", 1.5f), NoteMap.resolve(manager, withRows, "drum", 1, 3, true));
        // Chord mode still plays the hotbar chord on the middle row.
        assertEquals(new Note("lute_4f_chord", 1.0f), NoteMap.resolve(manager, withRows, "lute", 1, 3, true));
    }
}
