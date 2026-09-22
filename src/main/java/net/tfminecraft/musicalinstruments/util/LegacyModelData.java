package net.tfminecraft.musicalinstruments.util;

import java.util.List;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

/** Preserves the integer model IDs used by existing configuration and resource packs. */
public final class LegacyModelData {
    private LegacyModelData() {}

    public static boolean has(ItemMeta meta) {
        return !meta.getCustomModelDataComponent().getFloats().isEmpty();
    }

    public static int get(ItemMeta meta) {
        List<Float> floats = meta.getCustomModelDataComponent().getFloats();
        if (floats.isEmpty()) {
            throw new IllegalStateException("We don't have CustomModelData! Check hasCustomModelData first!");
        }
        return floats.get(0).intValue();
    }

    public static void set(ItemMeta meta, Integer value) {
        if (value == null) {
            meta.setCustomModelDataComponent(null);
            return;
        }
        CustomModelDataComponent component = meta.getCustomModelDataComponent();
        // The former integer setter replaced the entire component, not just its first float.
        component.setFloats(List.of(value.floatValue()));
        component.setFlags(List.of());
        component.setStrings(List.of());
        component.setColors(List.of());
        meta.setCustomModelDataComponent(component);
    }
}
