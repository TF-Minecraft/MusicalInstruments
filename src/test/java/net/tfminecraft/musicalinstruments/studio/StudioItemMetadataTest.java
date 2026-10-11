package net.tfminecraft.musicalinstruments.studio;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StudioItemMetadataTest {
    private Plugin plugin() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.namespace()).thenReturn("musicalinstruments");
        return plugin;
    }

    @Test
    void recordedDiscIdsAreValidatedAndPlainItemsNeverBecomeCustomDiscs() {
        Plugin plugin = plugin();
        DiscItems discs = new DiscItems(plugin);
        assertFalse(discs.musicDisc(null));
        assertFalse(discs.blank(null));
        assertFalse(discs.recorded(null));
        assertFalse(discs.custom(null));
        assertNull(discs.songId(null));
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.PAPER);
        assertFalse(discs.musicDisc(item));
        when(item.getType()).thenReturn(Material.MUSIC_DISC_13);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        NamespacedKey blank = new NamespacedKey(plugin, "blank_disc");
        NamespacedKey song = new NamespacedKey(plugin, "song_id");
        assertFalse(discs.blank(item));
        assertFalse(discs.recorded(item));
        assertFalse(discs.custom(item));
        when(data.get(blank, PersistentDataType.INTEGER)).thenReturn(1);
        assertTrue(discs.blank(item));
        assertTrue(discs.custom(item));
        when(data.get(blank, PersistentDataType.INTEGER)).thenReturn(0);
        when(data.has(song)).thenReturn(true);
        assertTrue(discs.recorded(item));
        assertTrue(discs.custom(item));
        assertNull(discs.songId(item));
        when(data.get(song, PersistentDataType.STRING)).thenReturn("bad-id");
        assertNull(discs.songId(item));
        UUID id = UUID.randomUUID();
        when(data.get(song, PersistentDataType.STRING)).thenReturn(id.toString());
        assertEquals(id, discs.songId(item));
    }

    @Test
    void blankAndPublishedDiscsHaveSeparateMetadataAndSuppressVanillaPlayback() {
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        try (var icons = mockStatic(StudioIcons.class);
             var construction = mockConstruction(ItemStack.class, (item, context) -> {
                 when(item.getItemMeta()).thenReturn(meta);
                 when(item.asOne()).thenReturn(item);
             })) {
            Plugin plugin = plugin();
            DiscItems discs = new DiscItems(plugin);
            ItemStack blank = discs.blankDisc();
            verify(data).set(new NamespacedKey(plugin, "blank_disc"), PersistentDataType.INTEGER, 1);
            verify(blank).unsetData(DataComponentTypes.JUKEBOX_PLAYABLE);
            icons.verify(() -> StudioIcons.hideDiscDescription(blank));
            Song song = new Song(UUID.randomUUID(), UUID.randomUUID(), "Author", "Song", List.of());
            ItemStack published = discs.disc(song);
            verify(data).remove(new NamespacedKey(plugin, "blank_disc"));
            verify(data).set(new NamespacedKey(plugin, "song_id"), PersistentDataType.STRING, song.id().toString());
            verify(published).unsetData(DataComponentTypes.JUKEBOX_PLAYABLE);
            icons.verify(() -> StudioIcons.hideDiscDescription(published));
            assertNotSame(blank, published);
        }
    }

    @Test
    void menuIconsKeepTheirOwnTextAndPreserveUnrelatedTooltipHiding() {
        ItemMeta meta = mock(ItemMeta.class);
        TooltipDisplay.Builder builder = mock(TooltipDisplay.Builder.class, RETURNS_SELF);
        try (var tooltip = mockStatic(TooltipDisplay.class);
             var construction = mockConstruction(ItemStack.class, (item, context) -> when(item.getItemMeta()).thenReturn(meta))) {
            tooltip.when(TooltipDisplay::tooltipDisplay).thenReturn(builder);
            StudioIcons icons = new StudioIcons();
            Component title = Component.text("Record");
            Component description = Component.text("Perform with your instrument");
            ItemStack button = icons.item(Material.BOOK, title, List.of(description));
            verify(meta).displayName(title.decoration(TextDecoration.ITALIC, false));
            verify(meta).lore(List.of(description.decoration(TextDecoration.ITALIC, false)));
            verify(button).setItemMeta(meta);
            verify(button, never()).getData(DataComponentTypes.TOOLTIP_DISPLAY);
            ItemStack disc = icons.item(Material.MUSIC_DISC_13, title, List.of());
            verify(builder).addHiddenComponents(DataComponentTypes.JUKEBOX_PLAYABLE);
            TooltipDisplay previous = mock(TooltipDisplay.class);
            when(previous.hideTooltip()).thenReturn(true);
            when(previous.hiddenComponents()).thenReturn(Set.of(DataComponentTypes.CUSTOM_NAME));
            when(disc.getData(DataComponentTypes.TOOLTIP_DISPLAY)).thenReturn(previous);
            StudioIcons.hideDiscDescription(disc);
            verify(builder).hideTooltip(true);
            verify(builder).hiddenComponents(Set.of(DataComponentTypes.CUSTOM_NAME));
        }
    }
}
