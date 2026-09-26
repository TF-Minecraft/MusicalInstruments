package net.tfminecraft.musicalinstruments.managers;

import net.kyori.adventure.text.Component;
import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import net.tfminecraft.musicalinstruments.items.ItemResolver;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// MockBukkit 4.95 implements the legacy model setter, not the component setter.
@SuppressWarnings("deprecation")
class InstrumentManagerTest {
    private static final NamespacedKey ID = new NamespacedKey("instruments", "id");
    private final YamlConfiguration config = new YamlConfiguration();
    private InstrumentManager manager;
    private ItemResolver resolver;
    private Logger logger;
    private ItemStack lute;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        InstrumentPlugin plugin = mock(InstrumentPlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        resolver = mock(ItemResolver.class);
        manager = new InstrumentManager(plugin, resolver);
        lute = new ItemStack(Material.PAPER);
        lute.editMeta(meta -> {
            meta.displayName(Component.text("Lute"));
            meta.lore(List.of(Component.text("An instrument")));
            meta.setCustomModelData(1001);
            meta.getPersistentDataContainer().set(ID, PersistentDataType.STRING, "lute");
        });
        config.set("lute.item", "m.instruments.lute");
        when(resolver.resolve("m.instruments.lute")).thenReturn(lute);
        manager.loadTemplates();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void recognizesUnchangedInstrumentAndIgnoresStackSize() {
        ItemStack item = lute.clone();
        item.setAmount(3);
        assertEquals("lute", manager.getInstrument(item));
    }

    @Test
    void recognizesRenamedInstrumentWithoutMutatingItems() {
        ItemStack item = lute.clone();
        item.editMeta(meta -> meta.displayName(Component.text("My lute")));
        ItemStack before = item.clone();
        ItemStack templateBefore = lute.clone();
        assertFalse(item.isSimilar(lute));
        assertEquals("lute", manager.getInstrument(item));
        assertEquals(before, item);
        assertEquals(templateBefore, lute);
        assertEquals(templateBefore, manager.getInstrumentItem("lute"));
    }

    @Test
    void recognizesLorestoneAndCombinedCosmeticEdits() {
        ItemStack item = lute.clone();
        item.editMeta(meta -> meta.lore(List.of(Component.text("Passed down for generations"))));
        assertEquals("lute", manager.getInstrument(item));
        item.editMeta(meta -> meta.displayName(Component.text("Family heirloom")));
        assertEquals("lute", manager.getInstrument(item));
    }

    @Test
    void rejectsDifferentMaterialModelAndProviderIdentity() {
        assertNull(manager.getInstrument(new ItemStack(Material.PAPER)));
        ItemStack wrongMaterial = lute.clone();
        wrongMaterial.setType(Material.STICK);
        assertNull(manager.getInstrument(wrongMaterial));
        ItemStack wrongModel = lute.clone();
        wrongModel.editMeta(meta -> meta.setCustomModelData(1002));
        assertNull(manager.getInstrument(wrongModel));
        ItemStack wrongId = lute.clone();
        wrongId.editMeta(meta -> meta.getPersistentDataContainer().set(ID, PersistentDataType.STRING, "flute"));
        assertNull(manager.getInstrument(wrongId));
    }

    @Test
    void rejectsEmptyHands() {
        assertNull(manager.getInstrument(null));
        assertNull(manager.getInstrument(new ItemStack(Material.AIR)));
    }

    @Test
    void exactMatchesWinAndAmbiguousRenamesAreRejected() {
        ItemStack other = lute.clone();
        other.editMeta(meta -> meta.displayName(Component.text("Other lute")));
        config.set("other.item", "modeled-other");
        when(resolver.resolve("modeled-other")).thenReturn(other);
        manager.loadTemplates();
        assertEquals("lute", manager.getInstrument(lute));
        assertEquals("other", manager.getInstrument(other));
        ItemStack renamed = other.clone();
        renamed.editMeta(meta -> meta.displayName(Component.text("Renamed")));
        assertNull(manager.getInstrument(renamed));
    }

    @Test
    void reloadRemovesOldTemplates() {
        config.set("lute", null);
        config.set("flute.item", "v.STICK");
        when(resolver.resolve("v.STICK")).thenReturn(new ItemStack(Material.STICK));
        manager.loadTemplates();
        assertNull(manager.getInstrument(lute));
        assertEquals("flute", manager.getInstrument(new ItemStack(Material.STICK)));
        assertNull(manager.getInstrumentItem("lute"));
    }

    @Test
    void skipsInstrumentsThatCannotBeLoaded() {
        config.set("drum.keybind-message", "no item");
        config.set("harp.item", "m.instruments.harp");
        config.set("horn.item", "nx.horn");
        when(resolver.resolve("nx.horn")).thenThrow(new IllegalStateException("registry reloading"));
        config.set("rest.item", "v.air");
        when(resolver.resolve("v.air")).thenReturn(new ItemStack(Material.AIR));
        manager.loadTemplates();
        assertEquals(List.of("lute"), List.copyOf(manager.getAllInstruments()));
        verify(logger).warning("Instrument 'drum' has no 'item' defined in config.");
        verify(logger).warning("Could not resolve item 'm.instruments.harp' for instrument 'harp'.");
        verify(logger).warning("Failed to load instrument 'horn': registry reloading");
        verify(logger).warning("Item 'v.air' for instrument 'rest' is air.");
        assertThrows(UnsupportedOperationException.class, () -> manager.getAllInstruments().clear());
    }

    @Test
    void findsInstrumentsIgnoringCase() {
        config.set("Lyre.item", "v.STICK");
        config.set("LUTE.item", "v.STICK");
        when(resolver.resolve("v.STICK")).thenReturn(new ItemStack(Material.STICK));
        manager.loadTemplates();
        assertEquals("Lyre", manager.findInstrument("lyre"));
        assertEquals("Lyre", manager.findInstrument("LYRE"));
        // An exact match beats an earlier case-insensitive one.
        assertEquals("LUTE", manager.findInstrument("LUTE"));
        assertEquals("lute", manager.findInstrument("Lute"));
        assertNull(manager.findInstrument("harp"));
    }

    @Test
    void readsNoteSettingsFromConfig() {
        config.set("lute.keybind-message", "1-[C]");
        config.set("lute.hotbar-sounds.1", "instruments.lute_1c_single");
        config.set("lute.hotbar-sounds.1+sneak", "instruments.lute_1c_chord");
        config.set("lute.hotbar-sounds.volume", 4.0);
        config.set("lute.hotbar-sounds.pitch", 0.5);
        assertEquals("1-[C]", manager.getKeybindMessage("lute"));
        assertEquals("instruments.lute_1c_single", manager.getSoundKey("lute", 1, false));
        assertEquals("instruments.lute_1c_chord", manager.getSoundKey("lute", 1, true));
        assertNull(manager.getSoundKey("lute", 2, false));
        assertEquals(4.0, manager.getVolume("lute"));
        assertEquals(0.5, manager.getPitch("lute"));
        assertEquals(1.0, manager.getVolume("flute"));
        assertEquals(1.0, manager.getPitch("flute"));
        assertNull(manager.getKeybindMessage("flute"));
    }
}
