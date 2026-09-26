package net.tfminecraft.musicalinstruments;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.CustomChart;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bstats.json.JsonObjectBuilder;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.sound.AudioExperience;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InstrumentPluginTest {
    private ServerMock server;
    private InstrumentPlugin plugin;
    private List<CustomChart> charts;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        // bStats must not start its submission thread or reject the unrelocated test classes.
        try (MockedConstruction<Metrics> metrics = mockConstruction(Metrics.class)) {
            plugin = MockBukkit.load(InstrumentPlugin.class);
            ArgumentCaptor<CustomChart> captor = ArgumentCaptor.forClass(CustomChart.class);
            verify(metrics.constructed().getFirst(), times(3)).addCustomChart(captor.capture());
            charts = captor.getAllValues();
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // Returns the chart's submission, or null when bStats would skip it.
    private String submit(Class<? extends CustomChart> type) {
        CustomChart chart = charts.stream().filter(type::isInstance).findFirst().orElseThrow();
        JsonObjectBuilder.JsonObject json = chart.getRequestJsonObject((message, error) -> fail(error), true);
        return json == null ? null : json.toString();
    }

    // Runs /instruments list from the console and returns what it printed.
    private List<String> list() {
        ConsoleCommandSenderMock console = (ConsoleCommandSenderMock) server.getConsoleSender();
        assertTrue(server.dispatchCommand(console, "instruments list"));
        List<String> messages = new ArrayList<>();
        for (String message = console.nextMessage(); message != null; message = console.nextMessage()) {
            messages.add(message);
        }
        return messages;
    }

    @Test
    void savesTheBundledConfig() {
        assertTrue(plugin.getDataFolder().toPath().resolve("config.yml").toFile().isFile());
        assertEquals("m.instruments.lute", plugin.getConfig().getString("lute.item"));
    }

    @Test
    void loadsInstrumentsOnTheFirstTick() {
        // The bundled instruments need MMOItems, which is not installed, so point one at a vanilla item.
        plugin.getConfig().set("lute.item", "v.note_block");
        assertEquals(List.of("§cNo instruments are loaded."), list());

        server.getScheduler().performOneTick();

        assertEquals(List.of("§aLoaded instruments (§61§a):", "§elute"), list());
        assertEquals("{\"chartId\":\"instruments_loaded\",\"data\":{\"value\":\"1\"}}", submit(SimplePie.class));
    }

    @Test
    void playsBundledNotesAndCountsThem() {
        plugin.getConfig().set("lute.item", "v.note_block");
        server.getScheduler().performOneTick();
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInOffHand(new ItemStack(Material.NOTE_BLOCK));

        server.getPluginManager().callEvent(new PlayerItemHeldEvent(player, 8, 0));
        player.setSneaking(true);
        server.getPluginManager().callEvent(new PlayerItemHeldEvent(player, 8, 0));

        List<AudioExperience> sounds = player.getHeardSounds();
        assertEquals("instruments.lute_1c_single", sounds.get(0).getSound());
        assertEquals("instruments.lute_1c_chord", sounds.get(1).getSound());
        assertEquals(SoundCategory.RECORDS, sounds.get(0).getCategory());
        assertEquals(4.0f, sounds.get(0).getVolume());
        assertEquals("{\"chartId\":\"notes_played\",\"data\":{\"value\":2}}", submit(SingleLineChart.class));
        assertEquals("{\"chartId\":\"instrument_usage\",\"data\":{\"values\":{\"lute\":2}}}", submit(AdvancedPie.class));
    }

    @Test
    void reportsPlaysPerSubmissionInterval() {
        plugin.recordInstrumentPlay("lute");
        plugin.recordInstrumentPlay("lute");
        plugin.recordInstrumentPlay("flute");

        assertEquals("{\"chartId\":\"notes_played\",\"data\":{\"value\":3}}", submit(SingleLineChart.class));
        String usage = submit(AdvancedPie.class);
        assertTrue(usage.contains("\"lute\":2"), usage);
        assertTrue(usage.contains("\"flute\":1"), usage);

        // Both charts drain their counters, so an idle interval submits nothing.
        assertNull(submit(SingleLineChart.class));
        assertNull(submit(AdvancedPie.class));

        plugin.recordInstrumentPlay("flute");
        assertEquals("{\"chartId\":\"instrument_usage\",\"data\":{\"values\":{\"flute\":1}}}", submit(AdvancedPie.class));
    }
}
