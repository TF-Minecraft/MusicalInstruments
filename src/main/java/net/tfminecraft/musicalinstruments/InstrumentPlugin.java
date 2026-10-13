package net.tfminecraft.musicalinstruments;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.plugin.java.JavaPlugin;
import net.tfminecraft.musicalinstruments.commands.InstrumentCommand;
import net.tfminecraft.musicalinstruments.items.ItemResolver;
import net.tfminecraft.musicalinstruments.keyboard.KeyboardService;
import net.tfminecraft.musicalinstruments.keyboard.KeyboardSettings;
import net.tfminecraft.musicalinstruments.listeners.InstrumentListener;
import net.tfminecraft.musicalinstruments.managers.InstrumentManager;
import net.tfminecraft.musicalinstruments.studio.StudioCommand;
import net.tfminecraft.musicalinstruments.studio.StudioListener;
import net.tfminecraft.musicalinstruments.studio.StudioMenu;
import net.tfminecraft.musicalinstruments.studio.StudioService;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

// ====================================
// Main plugin class for MusicalInstruments.
// ====================================
public class InstrumentPlugin extends JavaPlugin {

    private static final int BSTATS_PLUGIN_ID = 33322;

    private InstrumentManager manager;
    private KeyboardService keyboard;
    private StudioService studio;
    private StudioMenu studioMenu;

    // Play counts since the last bStats submission.
    // Written by the listener and drained when bStats collects chart data every 30 minutes.
    // bStats collects on the main thread, but its Folia path collects on its own thread, so keep these atomic.
    private final Map<String, AtomicInteger> playCounts = new ConcurrentHashMap<>();
    private final AtomicInteger totalPlays = new AtomicInteger();

    @Override
    public void onEnable() {
        getLogger().info("MusicalInstruments is enabled!");

        saveDefaultConfig();

        manager = new InstrumentManager(this, new ItemResolver(getLogger()));

        // Resolve instrument templates on the first tick, after every plugin
        // (MMOItems, ItemsAdder, Nexo) has finished enabling and registered its items.
        getServer().getScheduler().runTask(this, manager::loadTemplates);

        // The studio starts first: both the hotbar listener and the keyboard hand it their notes.
        try {
            studio = new StudioService(this, manager);
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Could not initialize recording studio", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // ItemsAdder can enable after this plugin despite the softdepend, so check the station on the first tick.
        getServer().getScheduler().runTask(this, () -> studio.station().warnIfUnavailable(getLogger()));
        studioMenu = new StudioMenu(this, studio);
        studio.startOwnership(studioMenu);
        StudioCommand musicCommand = new StudioCommand(studio, studioMenu);
        getCommand("music").setExecutor(musicCommand);
        getCommand("music").setTabCompleter(musicCommand);
        getServer().getPluginManager().registerEvents(studioMenu, this);
        getServer().getPluginManager().registerEvents(new StudioListener(this, studio, studioMenu), this);

        InstrumentCommand commandHandler = new InstrumentCommand(this, manager);
        getCommand("instruments").setExecutor(commandHandler);
        getCommand("instruments").setTabCompleter(commandHandler);

        // Register event listeners
        getServer().getPluginManager().registerEvents(new InstrumentListener(this, manager), this);

        // On-screen 7 x 3 keyboard (needs the tfmc_instruments:keyboard font in the resource pack).
        KeyboardSettings keyboardSettings = KeyboardSettings.load(this);
        keyboard = keyboardSettings.enabled() ? new KeyboardService(this, manager, keyboardSettings) : null;
        if (keyboard != null) {
            getServer().getPluginManager().registerEvents(keyboard, this);
            keyboard.start();
        }

        setupMetrics();
    }

    // Anonymous usage stats via bStats.
    // Servers can opt out globally in plugins/bStats/config.yml.
    private void setupMetrics() {
        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);

        // How many instrument templates actually resolved on this server.
        metrics.addCustomChart(new SimplePie("instruments_loaded",
                () -> String.valueOf(manager.getAllInstruments().size())));

        // Total notes played in the last submission interval.
        // Kept as its own counter so it does not depend on chart submission order.
        metrics.addCustomChart(new SingleLineChart("notes_played",
                () -> totalPlays.getAndSet(0)));

        // Per-instrument breakdown, drained so each submission covers one interval.
        metrics.addCustomChart(new AdvancedPie("instrument_usage", () -> {
            Map<String, Integer> snapshot = new HashMap<>();
            for (Map.Entry<String, AtomicInteger> entry : playCounts.entrySet()) {
                int count = entry.getValue().getAndSet(0);
                if (count > 0) {
                    snapshot.put(entry.getKey(), count);
                }
            }
            return snapshot;
        }));
    }

    @Override
    public void onDisable() {
        if (keyboard != null) {
            keyboard.close();
        }
        if (studioMenu != null) {
            studioMenu.close();
        }
        if (studio != null) {
            studio.close();
        }
    }

    public KeyboardService getKeyboard() {
        return keyboard;
    }

    public void recordInstrumentPlay(String instrument) {
        playCounts.computeIfAbsent(instrument, k -> new AtomicInteger()).incrementAndGet();
        totalPlays.incrementAndGet();
    }

    // Hands a played note to the recording studio, which keeps it only during a take.
    public void captureNote(Player player, String instrument, String sound, float volume, float pitch) {
        studio.capture(player, instrument, sound, volume, pitch);
    }
}
