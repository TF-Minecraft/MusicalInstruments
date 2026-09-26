package net.tfminecraft.musicalinstruments.util;

import java.util.List;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

/** Preserves the integer model IDs used by existing configuration and resource packs. */
public final class LegacyModelData {
    private LegacyModelData() {}

    public static void set(ItemMeta meta, int value) {
        CustomModelDataComponent component = meta.getCustomModelDataComponent();
        // The former integer setter replaced the entire component, not just its first float.
        component.setFloats(List.of((float) value));
        component.setFlags(List.of());
        component.setStrings(List.of());
        component.setColors(List.of());
        meta.setCustomModelDataComponent(component);
    }
}
