package net.tfminecraft.musicalinstruments;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.plugin.java.JavaPlugin;
import net.tfminecraft.musicalinstruments.commands.InstrumentCommand;
import net.tfminecraft.musicalinstruments.items.ItemResolver;
import net.tfminecraft.musicalinstruments.listeners.InstrumentListener;
import net.tfminecraft.musicalinstruments.managers.InstrumentManager;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// ====================================
// Main plugin class for MusicalInstruments.
// ====================================
public class InstrumentPlugin extends JavaPlugin {

    private static final int BSTATS_PLUGIN_ID = 33322;

    private InstrumentManager manager;

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

        InstrumentCommand commandHandler = new InstrumentCommand(this, manager);
        getCommand("instruments").setExecutor(commandHandler);
        getCommand("instruments").setTabCompleter(commandHandler);

        // Register event listeners
        getServer().getPluginManager().registerEvents(new InstrumentListener(this, manager), this);

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

    public void recordInstrumentPlay(String instrument) {
        playCounts.computeIfAbsent(instrument, k -> new AtomicInteger()).incrementAndGet();
        totalPlays.incrementAndGet();
    }
}
