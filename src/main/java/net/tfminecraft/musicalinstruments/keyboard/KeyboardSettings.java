package net.tfminecraft.musicalinstruments.keyboard;

import java.io.File;
import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import net.tfminecraft.musicalinstruments.keyboard.KeyboardFont.Size;
import org.bukkit.configuration.file.YamlConfiguration;

/** Settings from {@code keyboard.yml}. */
public record KeyboardSettings(
        boolean enabled,
        boolean openOnRightClick,
        Size defaultSize,
        boolean defaultRings,
        float highPitch,
        float lowPitch,
        boolean particles) {

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
                yaml.getBoolean("note-particles", true));
    }

    public static float clampPitch(double pitch) {
        return (float) Math.max(0.5, Math.min(2.0, pitch));
    }
}
