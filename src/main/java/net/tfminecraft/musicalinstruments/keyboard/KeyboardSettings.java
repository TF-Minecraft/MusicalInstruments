package net.tfminecraft.musicalinstruments.keyboard;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
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
        ChordStyle defaultChordStyle,
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
                ChordStyle.byName(yaml.getString("chord-buttons"), ChordStyle.ROW),
                clampPitch(yaml.getDouble("high-row-pitch", 2.0)),
                clampPitch(yaml.getDouble("low-row-pitch", 0.5)),
                yaml.getBoolean("note-particles", true),
                loadRows(yaml.getConfigurationSection("instruments"), plugin.getLogger()));
    }

    /** instruments.<id>.rows: three lists (top, middle, bottom) of seven sound keys each. */
    static Map<String, String[][]> loadRows(ConfigurationSection section, Logger logger) {
        Map<String, String[][]> rows = new HashMap<>();
        if (section == null) {
            return rows;
        }
        for (String instrument : section.getKeys(false)) {
            List<?> lists = section.getList(instrument + ".rows");
            if (lists == null || lists.size() != KeyboardFont.ROWS) {
                logger.warning("keyboard.yml: instruments." + instrument + ".rows needs 3 rows; ignored.");
                continue;
            }
            String[][] cells = new String[KeyboardFont.ROWS][KeyboardFont.COLUMNS];
            boolean valid = true;
            for (int row = 0; row < KeyboardFont.ROWS && valid; row++) {
                if (!(lists.get(row) instanceof List<?> sounds) || sounds.size() != KeyboardFont.COLUMNS) {
                    valid = false;
                    continue;
                }
                for (int column = 0; column < KeyboardFont.COLUMNS && valid; column++) {
                    if (sounds.get(column) instanceof String sound && !sound.isBlank()) {
                        cells[row][column] = sound;
                    } else {
                        valid = false;
                    }
                }
            }
            if (valid) {
                rows.put(instrument, cells);
            } else {
                logger.warning("keyboard.yml: instruments." + instrument + ".rows needs 7 sound names per row; ignored.");
            }
        }
        return rows;
    }

    public static float clampPitch(double pitch) {
        return (float) Math.max(0.5, Math.min(2.0, pitch));
    }
}
