package net.tfminecraft.musicalinstruments.studio;

import dev.lone.itemsadder.api.CustomBlock;
import dev.lone.itemsadder.api.CustomFurniture;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StudioStationTest {
    private final List<String> warnings = new ArrayList<>();
    private final Logger logger = Logger.getAnonymousLogger();
    private final World world = mock(World.class);
    private final Block block = mock(Block.class);
    private final Entity furniture = mock(Entity.class);
    private final Player player = mock(Player.class);

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { warnings.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        when(block.getWorld()).thenReturn(world);
        when(block.getLocation()).thenReturn(new Location(world, 2, 64, 2));
        when(block.getType()).thenReturn(Material.BARRIER);
        when(world.getNearbyEntities(any(BoundingBox.class))).thenReturn(List.of());
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(player.hasPermission("instruments.record")).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        CustomBlock.lookup = target -> null;
        CustomFurniture.lookup = target -> null;
        CustomFurniture.blockLookup = target -> null;
        MockBukkit.unmock();
    }

    private StudioStation parse(String path) {
        return StudioStation.parse(path, logger);
    }

    private void assertDisabled(String path, String warning) {
        warnings.clear();
        assertFalse(parse(path).enabled(), path);
        assertTrue(warnings.getFirst().contains(warning), warnings.toString());
    }

    @Test
    void parsesVanillaBlocksAndRejectsUnusableOnes() {
        assertFalse(parse(null).enabled());
        assertFalse(parse(" ").enabled());
        assertTrue(warnings.isEmpty());
        assertTrue(parse(" V.LOOM ").enabled());
        assertDisabled("loom", "Expected v.<material> or ia.<namespace:id>");
        assertDisabled("v.", "Expected v.<material>");
        assertDisabled("nx.music_station", "Expected v.<material>");
        assertDisabled("v.not_a_block_at_all", "is not a block");
        assertDisabled("v.stick", "is not a block");
        assertDisabled("v.air", "is not a block");
        assertDisabled("v.jukebox", "cannot be a jukebox");
        for (String id : List.of("music_station", ":music_station", "tfmc:", "tfmc:music:station", "TFMC:music_station", "tfmc:music station")) {
            assertDisabled("ia." + id, "Expected ia.<namespace:id>");
        }
    }

    @Test
    void itemsAdderStationsAreKeptUntilItemsAdderHasHadTheChanceToEnable() {
        // ItemsAdder can enable after this plugin, so parsing does not depend on it.
        StudioStation station = parse("ia.tfmc:music_station");
        assertTrue(station.enabled());
        assertTrue(warnings.isEmpty());
        station.warnIfUnavailable(logger);
        assertTrue(warnings.getFirst().contains("requires ItemsAdder"), warnings.toString());

        warnings.clear();
        var itemsAdder = MockBukkit.createMockPlugin("ItemsAdder");
        station.warnIfUnavailable(logger);
        StudioStation.vanilla(Material.LOOM).warnIfUnavailable(logger);
        assertTrue(warnings.isEmpty(), warnings.toString());
        MockBukkit.getMock().getPluginManager().disablePlugin(itemsAdder);
        station.warnIfUnavailable(logger);
        assertTrue(warnings.getFirst().contains("requires ItemsAdder"), warnings.toString());
    }

    @Test
    void vanillaStationsMatchTheirBlockOnly() {
        StudioStation station = StudioStation.vanilla(Material.BARRIER);
        assertTrue(station.matches(block));
        assertFalse(station.matches((Block) null));
        assertFalse(station.matches(furniture));
        when(block.getType()).thenReturn(Material.STONE);
        assertFalse(station.matches(block));
        assertFalse(StudioStation.none().matches(block));
        assertFalse(StudioStation.none().matches(furniture));
    }

    @Test
    void itemsAdderStationsMatchCustomBlocksAndFurniture() {
        StudioStation station = StudioStation.itemsAdder("tfmc:music_station");
        assertFalse(station.matches(block));
        assertFalse(station.matches((Entity) null));

        CustomBlock.lookup = target -> new CustomBlock(target == block ? "tfmc:music_station" : "tfmc:other");
        assertTrue(station.matches(block));
        CustomBlock.lookup = target -> null;

        // Furniture: its entity, or the block it stands in (such as its solid hitbox).
        Entity other = mock(Entity.class);
        CustomFurniture.lookup = target -> target == furniture ? new CustomFurniture("tfmc:music_station")
                : new CustomFurniture("tfmc:other");
        assertTrue(station.matches(furniture));
        assertFalse(station.matches(other));
        when(world.getNearbyEntities(BoundingBox.of(block))).thenReturn(List.of(other, furniture));
        assertTrue(station.matches(block));
        when(world.getNearbyEntities(BoundingBox.of(block))).thenReturn(List.of(other));
        assertFalse(station.matches(block));

        // A broken ItemsAdder lookup never opens the studio.
        CustomFurniture.lookup = target -> { throw new IllegalStateException("ItemsAdder is reloading"); };
        assertFalse(station.matches(furniture));
    }

    @Test
    void playersMustStandNearAStation() {
        StudioStation station = StudioStation.vanilla(Material.BARRIER);
        station.require(player, block);
        assertEquals(block, station.require(player, mockLocation(block)));

        when(block.getType()).thenReturn(Material.STONE);
        assertThrows(IllegalArgumentException.class, () -> station.require(player, block));
        when(block.getType()).thenReturn(Material.BARRIER);
        when(block.getLocation()).thenReturn(new Location(world, 7, 64, 0));
        assertThrows(IllegalArgumentException.class, () -> station.require(player, block));
        when(block.getWorld()).thenReturn(mock(World.class));
        assertThrows(IllegalArgumentException.class, () -> station.require(player, block));
        assertThrows(IllegalArgumentException.class, () -> station.require(player, (Block) null));
        Location far = new Location(world, 9, 64, 0);
        assertThrows(IllegalArgumentException.class, () -> station.require(player, far));
        assertThrows(IllegalArgumentException.class, () -> station.require(player, new Location(mock(World.class), 0, 64, 0)));
        assertThrows(IllegalArgumentException.class, () -> station.require(player, new Location(world, 1, 64, 1)));
        assertThrows(IllegalArgumentException.class, () -> station.require(player, new Location(world, 0, 64, -1))); // unloaded chunk

        // Staff can use the studio without a station; nobody can without instruments.record.
        when(player.hasPermission("instruments.studio")).thenReturn(true);
        assertNull(station.require(player, (Location) null));
        when(player.hasPermission("instruments.record")).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> station.require(player, (Block) null));
    }

    @Test
    void furnitureHitboxesResolveWithoutAnIntersectingEntityAndFallBackWhenUnavailable() {
        StudioStation station = StudioStation.itemsAdder("tfmc:music_station");
        CustomFurniture.blockLookup = target -> new CustomFurniture("tfmc:music_station");
        assertTrue(station.matches(block));
        verify(world, never()).getNearbyEntities(any(BoundingBox.class));
        CustomFurniture.blockLookup = target -> new CustomFurniture("tfmc:other");
        assertFalse(station.matches(block));
        CustomFurniture.blockLookup = target -> { throw new UnsupportedOperationException("Older ItemsAdder"); };
        CustomFurniture.lookup = target -> new CustomFurniture("tfmc:music_station");
        when(world.getNearbyEntities(any(BoundingBox.class))).thenReturn(List.of(furniture));
        assertTrue(station.matches(block));
    }

    private Location mockLocation(Block station) {
        Location location = spy(new Location(world, 2, 64, 2));
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        doReturn(station).when(location).getBlock();
        return location;
    }
}
