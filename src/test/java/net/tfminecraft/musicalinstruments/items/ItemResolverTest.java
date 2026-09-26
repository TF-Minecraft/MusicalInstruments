package net.tfminecraft.musicalinstruments.items;

import com.nexomc.nexo.api.NexoItems;
import dev.lone.itemsadder.api.CustomStack;
import net.Indyuce.mmoitems.MMOItems;
import net.tfminecraft.musicalinstruments.util.LegacyModelData;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// The modeled(...) format keeps legacy display names.
@SuppressWarnings("deprecation")
class ItemResolverTest {

    // Reflection targets shaped like the provider APIs.
    public record Types(Map<String, Object> byId) {
        public Object get(String id) { return byId.get(id); }
    }

    public record Items(Map<String, Object> byId) {
        public Object getMMOItem(Object type, String id) { return byId.get(type + ":" + id); }
    }

    public record Template(Object builder) {
        public Object newBuilder() { return builder; }
    }

    public record Builder(Object built) {
        public Object build() { return built; }
    }

    public record Stack(Object item) {
        public Object getItemStack() { return item; }
    }

    // Has a build method, but not the no-argument one the resolver calls.
    public record AmountBuilder() {
        public Object build(int amount) { return new ItemStack(Material.STICK, amount); }
    }

    private ServerMock server;
    private Logger logger;
    private ItemResolver resolver;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        logger = mock(Logger.class);
        resolver = new ItemResolver(logger);
    }

    @AfterEach
    void tearDown() {
        MMOItems.plugin = null;
        CustomStack.lookup = id -> null;
        NexoItems.lookup = id -> null;
        MockBukkit.unmock();
    }

    private void assertUnresolved(String path, String warning) {
        assertNull(resolver.resolve(path));
        verify(logger).warning(contains(warning));
    }

    @Test
    void ignoresMissingPaths() {
        assertNull(resolver.resolve(null));
        assertNull(resolver.resolve(""));
        assertNull(resolver.resolve("   "));
        verifyNoInteractions(logger);
    }

    @Test
    void rejectsUnknownPrefixes() {
        assertUnresolved("x.stone", "Unknown item path prefix in 'x.stone'");
    }

    @Test
    void resolvesVanillaItems() {
        ItemStack item = resolver.resolve("  V.iron_ingot ");
        assertEquals(Material.IRON_INGOT, item.getType());
        assertEquals(1, item.getAmount());
        assertEquals(Material.STICK, resolver.resolve("v.minecraft:stick").getType());
        assertEquals(Material.STICK, resolver.resolve("v.MINECRAFT:STICK").getType());
    }

    @Test
    void rejectsMalformedVanillaItems() {
        assertUnresolved("v", "Malformed vanilla item path 'v'");
        assertUnresolved("v.not_a_material", "Unknown material 'not_a_material'");
    }

    @Test
    void resolvesModeledItems() {
        try (MockedStatic<LegacyModelData> modelData = mockStatic(LegacyModelData.class)) {
            ItemStack item = resolver.resolve("MODELED(type=minecraft:PAPER; name = &6Flute ;model=1001;ignored)");
            assertEquals(Material.PAPER, item.getType());
            assertEquals("§6Flute", item.getItemMeta().getDisplayName());
            modelData.verify(() -> LegacyModelData.set(any(ItemMeta.class), eq(1001)));
        }
        verifyNoInteractions(logger);
    }

    @Test
    void modeledItemsDefaultToUnnamedDirt() {
        try (MockedStatic<LegacyModelData> modelData = mockStatic(LegacyModelData.class)) {
            ItemStack item = resolver.resolve("modeled()");
            assertEquals(Material.DIRT, item.getType());
            assertFalse(item.getItemMeta().hasDisplayName());
            modelData.verifyNoInteractions();
        }
    }

    @Test
    void modeledItemsKeepNameWhenModelIsInvalid() {
        ItemStack item = resolver.resolve("modeled(type=stick;name=Reed;model=abc)");
        assertEquals(Material.STICK, item.getType());
        assertEquals("Reed", item.getItemMeta().getDisplayName());
        verify(logger).warning(contains("Invalid model data"));
    }

    @Test
    void modeledItemsWithoutMetaSkipAttributes() {
        ItemStack item = resolver.resolve("modeled(type=air;name=Nothing)");
        assertEquals(Material.AIR, item.getType());
        assertNull(item.getItemMeta());
    }

    @Test
    void rejectsMalformedModeledItems() {
        assertUnresolved("modeled(type=paper", "Malformed modeled item path");
        assertUnresolved("modeled(type=not_a_material)", "Invalid material type in modeled item");
    }

    @Test
    void providerItemsRequireAnInstalledEnabledPlugin() {
        assertUnresolved("m.instruments.lute", "requires MMOItems");
        assertUnresolved("ia.tfmc:lute", "requires ItemsAdder");
        assertUnresolved("nx.lute", "requires Nexo");

        PluginMock mmoItems = MockBukkit.createMockPlugin("MMOItems");
        server.getPluginManager().disablePlugin(mmoItems);
        assertNull(resolver.resolve("m.instruments.lute"));
        verify(logger, times(2)).warning(contains("requires MMOItems"));
    }

    @Test
    void rejectsMalformedProviderPaths() {
        assertUnresolved("m.instruments", "Malformed MMOItems path");
        assertUnresolved("ia", "Malformed ItemsAdder path");
        assertUnresolved("nx", "Malformed Nexo path");
    }

    @Test
    void resolvesMmoItems() {
        MockBukkit.createMockPlugin("MMOItems");
        ItemStack lute = new ItemStack(Material.PAPER);
        MMOItems.plugin = new MMOItems(
                new Types(Map.of("INSTRUMENTS", "instrument-type")),
                new Items(Map.of(
                        "instrument-type:LUTE", new Template(new Builder(lute)),
                        "instrument-type:UNBUILT", new Template(null),
                        "instrument-type:TEXT", new Template(new Builder("not an item")),
                        "instrument-type:AMOUNT", new Template(new AmountBuilder()),
                        "instrument-type:PLAIN", "no builder method")));

        assertSame(lute, resolver.resolve("m.instruments.lute"));
        assertUnresolved("m.drums.snare", "Unknown MMOItems type 'drums'");
        assertUnresolved("m.instruments.harp", "Unknown MMOItems item");
        assertUnresolved("m.instruments.unbuilt", "MMOItems returned no item for 'm.instruments.unbuilt'");
        assertUnresolved("m.instruments.text", "MMOItems returned no item for 'm.instruments.text'");
        assertUnresolved("m.instruments.amount", "Failed to read MMOItems item 'm.instruments.amount'");
        assertUnresolved("m.instruments.plain", "Failed to read MMOItems item 'm.instruments.plain'");
    }

    @Test
    void reportsMmoItemsRuntimeFailures() {
        MockBukkit.createMockPlugin("MMOItems");
        assertUnresolved("m.instruments.lute", "Failed to read MMOItems item");
    }

    @Test
    void resolvesItemsAdderItemsAsSingleItems() {
        MockBukkit.createMockPlugin("ItemsAdder");
        ItemStack lute = new ItemStack(Material.PAPER, 5);
        CustomStack.lookup = id -> switch (id) {
            case "tfmc:lute" -> new Stack(lute);
            case "tfmc:text" -> new Stack("not an item");
            case "tfmc:broken" -> throw new IllegalStateException("registry reloading");
            default -> null;
        };

        ItemStack item = resolver.resolve("ia.tfmc:lute");
        assertSame(lute, item);
        assertEquals(1, item.getAmount());
        assertUnresolved("ia.tfmc:harp", "Unknown ItemsAdder item");
        assertUnresolved("ia.tfmc:text", "ItemsAdder returned no item");
        assertUnresolved("ia.tfmc:broken", "Failed to read ItemsAdder item");
    }

    @Test
    void resolvesNexoItemsAsSingleItems() {
        MockBukkit.createMockPlugin("Nexo");
        ItemStack lute = new ItemStack(Material.PAPER, 3);
        NexoItems.lookup = id -> switch (id) {
            case "lute" -> new Builder(lute);
            case "text" -> new Builder("not an item");
            case "amount" -> new AmountBuilder();
            default -> null;
        };

        ItemStack item = resolver.resolve("nx.lute");
        assertSame(lute, item);
        assertEquals(1, item.getAmount());
        assertUnresolved("nx.harp", "Unknown Nexo item");
        assertUnresolved("nx.text", "Nexo returned no item");
        assertUnresolved("nx.amount", "Failed to read Nexo item");
    }
}
