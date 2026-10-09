package net.tfminecraft.musicalinstruments.keyboard;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import net.tfminecraft.musicalinstruments.keyboard.KeyboardFont.Size;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Settings from {@code keyboard.yml}. */
public record KeyboardSettings(
        boolean enabled,
        boolean openOnRightClick,
        Size defaultSize,
        boolean defaultRings,
        float highPitch,
        float lowPitch,
        boolean particles,
        Map<String, String[][]> rows) {

    /** Sounds for each keyboard cell, [row][column], when keyboard.yml lists them for an instrument. */
    public String[][] rowsFor(String instrument) {
        return this.rows.get(instrument);
    }

    public static KeyboardSettings load(InstrumentPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "keyboard.yml");
        if (!file.exists()) {
            plugin.saveResource("keyboard.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        return new KeyboardSettings(
                yaml.getBoolean("enabled", true),
                yaml.getBoolean("open-on-right-click", true),
                Size.byName(yaml.getString("default-size"), Size.MEDIUM),
                yaml.getBoolean("ring-effect", true),
                clampPitch(yaml.getDouble("high-row-pitch", 2.0)),
                clampPitch(yaml.getDouble("low-row-pitch", 0.5)),
                yaml.getBoolean("note-particles", true),
                loadRows(yaml.getConfigurationSection("instruments")));
    }

    /** instruments.<id>.rows: three lists (top, middle, bottom) of seven sound keys each. */
    static Map<String, String[][]> loadRows(ConfigurationSection section) {
        Map<String, String[][]> rows = new HashMap<>();
        if (section == null) {
            return rows;
        }
        for (String instrument : section.getKeys(false)) {
            List<?> lists = section.getList(instrument + ".rows");
            if (lists == null || lists.size() != KeyboardFont.ROWS) {
                continue;
            }
            String[][] cells = new String[KeyboardFont.ROWS][KeyboardFont.COLUMNS];
            boolean valid = true;
            for (int row = 0; row < KeyboardFont.ROWS && valid; row++) {
                if (!(lists.get(row) instanceof List<?> sounds) || sounds.size() != KeyboardFont.COLUMNS) {
                    valid = false;
                    continue;
                }
                for (int column = 0; column < KeyboardFont.COLUMNS; column++) {
                    cells[row][column] = String.valueOf(sounds.get(column));
                }
            }
            if (valid) {
                rows.put(instrument, cells);
            }
        }
        return rows;
    }

    public static float clampPitch(double pitch) {
        return (float) Math.max(0.5, Math.min(2.0, pitch));
    }
}
