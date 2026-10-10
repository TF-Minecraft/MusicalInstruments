package net.tfminecraft.musicalinstruments.keyboard;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import net.tfminecraft.musicalinstruments.keyboard.KeyboardFont.Size;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeyboardSettingsTest {
    @TempDir
    Path folder;
    private InstrumentPlugin plugin;
    private Logger logger;

    private static final List<String> SEVEN = List.of("a", "b", "c", "d", "e", "f", "g");

    @BeforeEach
    void setUp() {
        plugin = mock(InstrumentPlugin.class);
        logger = mock(Logger.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(logger);
    }

    @Test
    void writesAndReadsTheBundledDefaults() {
        // saveResource copies keyboard.yml from the jar the first time.
        doAnswer(invocation -> {
            try (InputStream in = KeyboardSettings.class.getResourceAsStream("/keyboard.yml")) {
                Files.copy(in, folder.resolve("keyboard.yml"));
            }
            return null;
        }).when(plugin).saveResource("keyboard.yml", false);

        KeyboardSettings settings = KeyboardSettings.load(plugin);

        assertTrue(settings.enabled());
        assertTrue(settings.openOnRightClick());
        assertEquals(Size.MEDIUM, settings.defaultSize());
        assertTrue(settings.defaultRings());
        assertEquals(2.0f, settings.highPitch());
        assertEquals(0.5f, settings.lowPitch());
        assertTrue(settings.particles());
        String[][] harp = settings.rowsFor("celtic_harp");
        assertEquals("tfmc_instruments:celtic_harp.c6", harp[0][0]);
        assertEquals("tfmc_instruments:celtic_harp.f5", harp[1][3]);
        assertEquals("tfmc_instruments:celtic_harp.b4", harp[2][6]);
        assertNull(settings.rowsFor("bongo"));
        String[][] lute = settings.chordsFor("lute");
        assertEquals("tfmc_instruments:lute.chord_c5", lute[0][0]);
        assertEquals("tfmc_instruments:lute.chord_f4", lute[1][3]);
        assertEquals("tfmc_instruments:lute.chord_b3", lute[2][6]);
        assertNull(settings.chordsFor("flute"));
    }

    @Test
    void readsAnExistingFileAndClampsPitches() throws IOException {
        Files.writeString(folder.resolve("keyboard.yml"), """
                enabled: false
                open-on-right-click: false
                default-size: large
                ring-effect: false
                high-row-pitch: 5
                low-row-pitch: 0.1
                note-particles: false
                """);

        KeyboardSettings settings = KeyboardSettings.load(plugin);

        verify(plugin, never()).saveResource("keyboard.yml", false);
        assertFalse(settings.enabled());
        assertFalse(settings.openOnRightClick());
        assertEquals(Size.LARGE, settings.defaultSize());
        assertFalse(settings.defaultRings());
        assertEquals(2.0f, settings.highPitch());
        assertEquals(0.5f, settings.lowPitch());
        assertFalse(settings.particles());
        assertTrue(settings.rows().isEmpty());
        assertTrue(settings.chords().isEmpty());
    }

    @Test
    void ignoresMissingFilesWithDefaults() {
        KeyboardSettings settings = KeyboardSettings.load(plugin);
        assertTrue(settings.enabled());
        assertEquals(Size.MEDIUM, settings.defaultSize());
        assertTrue(new File(folder.toFile(), "keyboard.yml").exists() == false);
    }

    @Test
    void acceptsOnlyCompleteRowsAndWarnsAboutTheRest() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("good.rows", List.of(SEVEN, SEVEN, SEVEN));
        yaml.set("norows.volume", 1);
        yaml.set("tworows.rows", List.of(SEVEN, SEVEN));
        yaml.set("scalar.rows", 5);
        yaml.set("notalist.rows", List.of(SEVEN, "oops", SEVEN));
        yaml.set("short.rows", List.of(SEVEN, SEVEN, List.of("a", "b")));
        yaml.set("number.rows", List.of(SEVEN, SEVEN, List.of("a", "b", "c", "d", "e", "f", 7)));
        yaml.set("blank.rows", List.of(SEVEN, List.of("a", "b", "c", " ", "e", "f", "g"), SEVEN));

        yaml.set("good.chords", List.of(SEVEN, SEVEN));

        var rows = KeyboardSettings.loadGrids(yaml, "rows", logger);

        assertEquals(1, rows.size());
        assertArrayEquals(SEVEN.toArray(), rows.get("good")[2]);
        // Instruments without the key are skipped quietly.
        verify(logger, never()).warning(contains("instruments.norows"));
        verify(logger).warning(contains("instruments.tworows.rows needs 3 rows"));
        verify(logger).warning(contains("instruments.scalar.rows needs 3 rows"));
        verify(logger).warning(contains("instruments.notalist.rows needs 7 sound names"));
        verify(logger).warning(contains("instruments.short.rows needs 7 sound names"));
        verify(logger).warning(contains("instruments.number.rows needs 7 sound names"));
        verify(logger).warning(contains("instruments.blank.rows needs 7 sound names"));
        assertTrue(KeyboardSettings.loadGrids(yaml, "chords", logger).isEmpty());
        verify(logger).warning(contains("instruments.good.chords needs 3 rows"));
        assertTrue(KeyboardSettings.loadGrids(null, "rows", logger).isEmpty());
    }

    @Test
    void clampsPitch() {
        assertEquals(0.5f, KeyboardSettings.clampPitch(0.2));
        assertEquals(1.25f, KeyboardSettings.clampPitch(1.25));
        assertEquals(2.0f, KeyboardSettings.clampPitch(9));
    }
}
