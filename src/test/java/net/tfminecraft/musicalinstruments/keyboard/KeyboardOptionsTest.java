package net.tfminecraft.musicalinstruments.keyboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.papermc.paper.dialog.DialogResponseView;
import java.util.Map;
import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import net.tfminecraft.musicalinstruments.keyboard.KeyboardFont.Size;
import net.tfminecraft.musicalinstruments.keyboard.KeyboardOptions.Choice;
import net.tfminecraft.musicalinstruments.keyboard.KeyboardOptions.Prefs;
import net.tfminecraft.musicalinstruments.managers.InstrumentManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class KeyboardOptionsTest {
    private PlayerMock player;
    private KeyboardOptions options;
    private InstrumentManager manager;

    @BeforeEach
    void setUp() {
        player = MockBukkit.mock().addPlayer();
        InstrumentPlugin plugin = mock(InstrumentPlugin.class);
        when(plugin.getName()).thenReturn("MusicalInstruments");
        when(plugin.namespace()).thenReturn("musicalinstruments");
        options = new KeyboardOptions(plugin, NoteMapTest.settings(Map.of()));
        manager = mock(InstrumentManager.class);
        when(manager.getSoundKey("lute", 1, true)).thenReturn("lute_1c_chord");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void startsFromTheServerDefaults() {
        assertEquals(new Prefs(Size.MEDIUM, true, false), options.prefs(player));
    }

    @Test
    void savesOnlyTheValuesTheDialogSent() {
        options.save(player, new Choice("large", false, true));
        assertEquals(new Prefs(Size.LARGE, false, true), options.prefs(player));

        options.save(player, new Choice(null, null, null));
        assertEquals(new Prefs(Size.LARGE, false, true), options.prefs(player));

        options.save(player, new Choice("tiny", true, false));
        assertEquals(new Prefs(Size.MEDIUM, true, false), options.prefs(player));
    }

    @Test
    void readsTheDialogResponse() {
        assertNull(Choice.read(null));
        DialogResponseView view = mock(DialogResponseView.class);
        when(view.getText("size")).thenReturn("SMALL");
        when(view.getBoolean("rings")).thenReturn(true);
        when(view.getBoolean("chords")).thenReturn(null);
        assertEquals(new Choice("SMALL", true, null), Choice.read(view));
    }

    @Test
    void buildsTheOptionsDialog() {
        try (DialogStubs stubs = new DialogStubs()) {
            assertNotNull(options.dialog(player, "lute", manager));
            options.save(player, new Choice("small", false, true));
            assertNotNull(options.dialog(player, "celtic_harp", manager));
            assertEquals(2, stubs.created.size());
        }
        assertTrue(NoteMap.hasChords(manager, "lute"));
        assertFalse(NoteMap.hasChords(manager, "celtic_harp"));
    }
}
