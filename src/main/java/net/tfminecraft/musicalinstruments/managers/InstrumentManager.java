package net.tfminecraft.musicalinstruments.managers;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import net.tfminecraft.musicalinstruments.items.ItemResolver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

// ====================================
// Manages instrument detection and validation.
// Identifies instruments by comparing against resolved item templates.
// ====================================
public class InstrumentManager {

    private final InstrumentPlugin plugin;
    private final ItemResolver itemResolver;
    private final Map<String, ItemStack> templates;
    private final Map<String, ItemStack> cosmeticFreeTemplates;

    public InstrumentManager(InstrumentPlugin plugin, ItemResolver itemResolver) {
        this.plugin = plugin;
        this.itemResolver = itemResolver;
        this.templates = new LinkedHashMap<>();
        this.cosmeticFreeTemplates = new LinkedHashMap<>();
    }

    // ====================================
    // Resolves every configured instrument item once and caches the result,
    // so hotbar events compare against cached templates instead of calling
    // the item resolver for each instrument on every slot change.
    // Called on enable and on /instruments reload.
    // ====================================
    public void loadTemplates() {
        templates.clear();
        cosmeticFreeTemplates.clear();

        for (String instrument : plugin.getConfig().getKeys(false)) {
            String configPath = plugin.getConfig().getString(instrument + ".item");
            if (configPath == null) {
                plugin.getLogger().warning("Instrument '" + instrument + "' has no 'item' defined in config.");
                continue;
            }

            try {
                ItemStack template = itemResolver.resolve(configPath);
                if (template == null) {
                    plugin.getLogger().warning("Could not resolve item '" + configPath + "' for instrument '" + instrument + "'.");
                    continue;
                }
                // Air in the off-hand never counts as an instrument, so it could not be played.
                if (template.getType().isAir()) {
                    plugin.getLogger().warning("Item '" + configPath + "' for instrument '" + instrument + "' is air.");
                    continue;
                }

                ItemStack cosmeticFree = withoutCosmetics(template);
                templates.put(instrument, template);
                cosmeticFreeTemplates.put(instrument, cosmeticFree);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load instrument '" + instrument + "': " + e.getMessage());
            }
        }

        plugin.getLogger().info("Loaded " + templates.size() + " instrument(s).");
    }

    // ====================================
    // Gets the instrument ID from an ItemStack.
    // ====================================
    public String getInstrument(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }

        for (Map.Entry<String, ItemStack> entry : templates.entrySet()) {
            if (item.isSimilar(entry.getValue())) {
                return entry.getKey();
            }
        }

        // Preserve exact matches before allowing namestone/lorestone edits.
        ItemStack cosmeticFree = withoutCosmetics(item);
        String match = null;
        for (Map.Entry<String, ItemStack> entry : cosmeticFreeTemplates.entrySet()) {
            if (cosmeticFree.isSimilar(entry.getValue())) {
                // Name/lore-only variants cannot be distinguished after a cosmetic edit.
                if (match != null) {
                    return null;
                }
                match = entry.getKey();
            }
        }
        return match;
    }

    // Only called with non-air items, which always have item meta.
    private static ItemStack withoutCosmetics(ItemStack item) {
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        meta.displayName(null);
        meta.lore(null);
        copy.setItemMeta(meta);
        return copy;
    }

    // Gets the sound key for an instrument slot and sneak state.
    public String getSoundKey(String instrument, int slot, boolean sneaking)
    {
        String commandKey = sneaking ? slot + "+sneak" : String.valueOf(slot);

        return plugin.getConfig().getString(instrument + ".hotbar-sounds." + commandKey);
    }

    public String getKeybindMessage(String instrument){ return plugin.getConfig().getString(instrument + ".keybind-message"); }
    public double getVolume(String instrument) { return plugin.getConfig().getDouble(instrument + ".hotbar-sounds.volume", 1.0);}
    public double getPitch(String instrument){ return plugin.getConfig().getDouble(instrument + ".hotbar-sounds.pitch", 1.0); }
    public Set<String> getAllInstruments() { return Collections.unmodifiableSet(templates.keySet()); }

    // Finds a loaded instrument by name, preferring an exact match over a case-insensitive one.
    public String findInstrument(String name) {
        if (templates.containsKey(name)) {
            return name;
        }
        for (String instrument : templates.keySet()) {
            if (instrument.equalsIgnoreCase(name)) {
                return instrument;
            }
        }
        return null;
    }

    // Gets a copy of an instrument's cached item template.
    public ItemStack getInstrumentItem(String instrument) {
        ItemStack template = templates.get(instrument);

        return template == null ? null : template.clone();
    }
}
