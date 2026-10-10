package net.tfminecraft.musicalinstruments.keyboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.papermc.paper.dialog.DialogResponseView;
import java.util.Map;
import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import net.tfminecraft.musicalinstruments.keyboard.KeyboardFont.Size;
import net.tfminecraft.musicalinstruments.keyboard.KeyboardOptions.Choice;
import net.tfminecraft.musicalinstruments.keyboard.KeyboardOptions.Prefs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class KeyboardOptionsTest {
    private PlayerMock player;
    private KeyboardOptions options;

    @BeforeEach
    void setUp() {
        player = MockBukkit.mock().addPlayer();
        InstrumentPlugin plugin = mock(InstrumentPlugin.class);
        when(plugin.getName()).thenReturn("MusicalInstruments");
        when(plugin.namespace()).thenReturn("musicalinstruments");
        options = new KeyboardOptions(plugin, NoteMapTest.settings(Map.of()));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void startsFromTheServerDefaults() {
        assertEquals(new Prefs(Size.MEDIUM, true), options.prefs(player));
    }

    @Test
    void savesOnlyTheValuesTheDialogSent() {
        options.save(player, new Choice("large", false));
        assertEquals(new Prefs(Size.LARGE, false), options.prefs(player));

        options.save(player, new Choice(null, null));
        assertEquals(new Prefs(Size.LARGE, false), options.prefs(player));

        options.save(player, new Choice("tiny", true));
        assertEquals(new Prefs(Size.MEDIUM, true), options.prefs(player));
    }

    @Test
    void readsTheDialogResponse() {
        assertNull(Choice.read(null));
        DialogResponseView view = mock(DialogResponseView.class);
        when(view.getText("size")).thenReturn("SMALL");
        when(view.getBoolean("rings")).thenReturn(true);
        assertEquals(new Choice("SMALL", true), Choice.read(view));
    }

    @Test
    void buildsTheOptionsDialog() {
        try (DialogStubs stubs = new DialogStubs()) {
            assertNotNull(options.dialog(player, "lute"));
            options.save(player, new Choice("small", false));
            assertNotNull(options.dialog(player, "celtic_harp"));
            assertNotNull(options.dialog(player, ""));
            assertEquals(3, stubs.created.size());
        }
    }
}
