package net.tfminecraft.musicalinstruments.util;

import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

// MockBukkit 4.95 does not implement model data components, so they are mocked here.
class LegacyModelDataTest {

    @Test
    void settingAModelReplacesTheWholeComponent() {
        ItemMeta meta = mock(ItemMeta.class);
        CustomModelDataComponent component = mock(CustomModelDataComponent.class);
        when(meta.getCustomModelDataComponent()).thenReturn(component);

        LegacyModelData.set(meta, 1002);

        verify(component).setFloats(List.of(1002.0f));
        verify(component).setFlags(List.of());
        verify(component).setStrings(List.of());
        verify(component).setColors(List.of());
        verify(meta).setCustomModelDataComponent(component);
    }
}
