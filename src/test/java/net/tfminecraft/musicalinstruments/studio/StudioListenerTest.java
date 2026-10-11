package net.tfminecraft.musicalinstruments.studio;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudioListenerTest {
    private StudioService studio;
    private StudioListener listener;
    private StudioMenu menu;
    private DiscItems discs;
    private Player player;
    private PlayerInventory inventory;
    private Block block;
    private Jukebox box;
    private ItemStack disc;

    @BeforeEach
    void setup() {
        studio = mock(StudioService.class);
        discs = mock(DiscItems.class);
        menu = mock(StudioMenu.class);
        listener = new StudioListener(studio, menu);
        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        block = mock(Block.class);
        box = mock(Jukebox.class);
        disc = mock(ItemStack.class);
        when(studio.discs()).thenReturn(discs);
        when(studio.station()).thenReturn(StudioStation.none());
        when(block.getState()).thenReturn(box);
        when(player.getInventory()).thenReturn(inventory);
        when(player.hasPermission("instruments.play")).thenReturn(true);
        when(discs.custom(disc)).thenReturn(true);
    }

    private PlayerInteractEvent event(EquipmentSlot hand) {
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, disc, block, BlockFace.UP, hand);
    }

    @Test
    void customInsertionIsCancelledAndMovesExactlyOneDisc() throws Exception {
        ItemStack remaining = mock(ItemStack.class);
        when(disc.clone()).thenReturn(remaining);
        when(disc.getAmount()).thenReturn(2);
        when(remaining.getAmount()).thenReturn(1);
        PlayerInteractEvent event = event(EquipmentSlot.HAND);
        listener.interact(event);
        assertEquals(Event.Result.DENY, event.useInteractedBlock());
        verify(studio).play(block, disc);
        verify(remaining).setAmount(1);
        verify(inventory).setItemInMainHand(remaining);
    }

    @Test
    void offhandInsertionUpdatesTheCorrectInventorySlot() throws Exception {
        ItemStack remaining = mock(ItemStack.class);
        when(disc.clone()).thenReturn(remaining);
        when(disc.getAmount()).thenReturn(2);
        when(remaining.getAmount()).thenReturn(1);
        listener.interact(event(EquipmentSlot.OFF_HAND));
        verify(studio).play(block, disc);
        verify(inventory).setItemInOffHand(remaining);
        verify(inventory, never()).setItemInMainHand(any());
    }

    @Test
    void failedInsertionPreservesThePlayersDisc() throws Exception {
        doThrow(new IOException("Missing edition")).when(studio).play(block, disc);
        listener.interact(event(EquipmentSlot.HAND));
        verifyNoInteractions(inventory);
        verify(studio).failure(eq(player), any(IOException.class));
    }

    @Test
    void protectionDenialsAndPermissionChecksPreventPlayback() throws Exception {
        PlayerInteractEvent protectedEvent = event(EquipmentSlot.HAND);
        protectedEvent.setUseInteractedBlock(Event.Result.DENY);
        listener.interact(protectedEvent);
        when(player.hasPermission("instruments.play")).thenReturn(false);
        listener.interact(event(EquipmentSlot.HAND));
        verify(studio, never()).play(any(), any());
        verifyNoInteractions(inventory);
    }

    @Test
    void itemUseDenialPreventsCustomInsertion() throws Exception {
        PlayerInteractEvent event = event(EquipmentSlot.HAND);
        event.setUseItemInHand(Event.Result.DENY);
        listener.interact(event);
        verify(studio, never()).play(any(), any());
        verifyNoInteractions(inventory);
    }

    @Test
    void recordedDiscIsEjectedOnlyForTheMainHandEvent() {
        when(box.getRecord()).thenReturn(disc);
        listener.interact(event(EquipmentSlot.HAND));
        listener.interact(event(EquipmentSlot.OFF_HAND));
        verify(box, times(1)).eject();
        verify(studio, times(1)).stop(block);
    }

    @Test
    void vanillaDiscsRetainTheirNormalInteraction() throws Exception {
        when(discs.custom(disc)).thenReturn(false);
        PlayerInteractEvent event = event(EquipmentSlot.HAND);
        Event.Result original = event.useInteractedBlock();
        listener.interact(event);
        assertEquals(original, event.useInteractedBlock());
        verify(studio, never()).play(any(), any());
    }
    @Test
    void sneakingWithVanillaItemsDoesNotClaimTheMmoItemsInteraction() throws Exception {
        when(discs.custom(disc)).thenReturn(false);
        when(player.isSneaking()).thenReturn(true);
        when(player.hasPermission("instruments.record")).thenReturn(true);
        PlayerInteractEvent event = event(EquipmentSlot.HAND);
        Event.Result original = event.useInteractedBlock();
        listener.interact(event);
        assertEquals(original, event.useInteractedBlock());
        verify(studio, never()).play(any(), any());
    }

    private ItemStack held(Material material) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        when(inventory.getItemInMainHand()).thenReturn(item);
        return item;
    }

    @Test
    void rightClickingTheStationOpensTheStudioOnceAndKeepsTheKeyboardClosed() throws Exception {
        when(studio.station()).thenReturn(StudioStation.vanilla(Material.LOOM));
        when(block.getType()).thenReturn(Material.LOOM);
        held(Material.STICK);
        PlayerInteractEvent main = event(EquipmentSlot.HAND);
        // ItemsAdder cancels clicks on its own furniture; the studio still opens.
        main.setUseInteractedBlock(Event.Result.DENY);
        listener.station(main);
        PlayerInteractEvent off = event(EquipmentSlot.OFF_HAND);
        listener.station(off);
        verify(menu, times(1)).open(player, block);
        // The keyboard (HIGHEST) skips clicks whose item use is denied.
        assertEquals(Event.Result.DENY, main.useItemInHand());
        assertEquals(Event.Result.DENY, off.useItemInHand());
    }

    @Test
    void otherBlocksClicksAndSneakPlacementKeepVanillaBehaviour() throws Exception {
        when(studio.station()).thenReturn(StudioStation.vanilla(Material.LOOM));
        held(Material.AIR);
        PlayerInteractEvent other = event(EquipmentSlot.HAND);
        listener.station(other);
        assertNotEquals(Event.Result.DENY, other.useItemInHand());

        when(block.getType()).thenReturn(Material.LOOM);
        listener.station(new PlayerInteractEvent(player, Action.LEFT_CLICK_BLOCK, disc, block, BlockFace.UP, EquipmentSlot.HAND));
        when(player.isSneaking()).thenReturn(true);
        held(Material.OAK_PLANKS);
        PlayerInteractEvent placing = event(EquipmentSlot.HAND);
        listener.station(placing);
        assertNotEquals(Event.Result.DENY, placing.useItemInHand());
        verifyNoInteractions(menu);

        // Sneaking with an empty hand still opens it.
        held(Material.AIR);
        listener.station(event(EquipmentSlot.HAND));
        verify(menu).open(player, block);
    }

    @Test
    void rightClickingStationFurnitureOpensTheStudioAtItsBlock() throws Exception {
        Entity furniture = mock(Entity.class);
        Location location = mock(Location.class);
        when(furniture.getLocation()).thenReturn(location);
        when(location.getBlock()).thenReturn(block);
        StudioStation station = mock(StudioStation.class);
        when(studio.station()).thenReturn(station);
        when(station.matches(furniture)).thenReturn(true);
        held(Material.AIR);

        PlayerInteractEntityEvent other = new PlayerInteractEntityEvent(player, mock(Entity.class), EquipmentSlot.HAND);
        listener.stationFurniture(other);
        assertFalse(other.isCancelled());
        PlayerInteractEntityEvent off = new PlayerInteractEntityEvent(player, furniture, EquipmentSlot.OFF_HAND);
        listener.stationFurniture(off);
        assertTrue(off.isCancelled());
        verifyNoInteractions(menu);

        PlayerInteractEntityEvent main = new PlayerInteractEntityEvent(player, furniture, EquipmentSlot.HAND);
        listener.stationFurniture(main);
        assertTrue(main.isCancelled());
        verify(menu).open(player, block);

        when(player.isSneaking()).thenReturn(true);
        held(Material.OAK_PLANKS);
        PlayerInteractEntityEvent sneaking = new PlayerInteractEntityEvent(player, furniture, EquipmentSlot.HAND);
        listener.stationFurniture(sneaking);
        assertFalse(sneaking.isCancelled());
    }

    @Test
    void stationErrorsAreReportedToThePlayer() throws Exception {
        when(studio.station()).thenReturn(StudioStation.vanilla(Material.LOOM));
        when(block.getType()).thenReturn(Material.LOOM);
        held(Material.AIR);
        doThrow(new IllegalArgumentException("Use a recording station within 5 blocks to open the studio"))
                .doThrow(new IOException("Disk full")).when(menu).open(player, block);
        listener.station(event(EquipmentSlot.HAND));
        verify(player).sendMessage("Use a recording station within 5 blocks to open the studio");
        listener.station(event(EquipmentSlot.HAND));
        verify(studio).failure(eq(player), any(IOException.class));
    }

    @Test
    void blankDiscsOccupiedBoxesAndOtherClicksNeverInsertARecording() throws Exception {
        listener.interact(new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, disc, null, BlockFace.UP));
        listener.interact(new PlayerInteractEvent(player, Action.LEFT_CLICK_BLOCK, disc, block, BlockFace.UP));
        listener.interact(new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, disc, null, BlockFace.UP));
        when(block.getState()).thenReturn(mock(org.bukkit.block.BlockState.class));
        listener.interact(event(EquipmentSlot.HAND));
        when(block.getState()).thenReturn(box);
        when(box.hasRecord()).thenReturn(true);
        listener.interact(event(EquipmentSlot.HAND));
        when(box.hasRecord()).thenReturn(false);
        when(discs.blank(disc)).thenReturn(true);
        listener.interact(event(EquipmentSlot.HAND));
        verify(player).sendMessage(contains("This disc is blank"));
        verify(studio, never()).play(any(), any());
        when(discs.blank(disc)).thenReturn(false);
        doThrow(new IllegalArgumentException("Changed jukebox")).when(studio).play(block, disc);
        listener.interact(event(EquipmentSlot.HAND));
        verify(player).sendMessage("Changed jukebox");
    }

    @Test
    void insertingTheLastDiscClearsTheCorrectHand() throws Exception {
        ItemStack remaining = mock(ItemStack.class);
        when(disc.clone()).thenReturn(remaining);
        when(disc.getAmount()).thenReturn(1);
        try (var items = mockConstruction(ItemStack.class, (item, context) -> when(item.getType()).thenReturn(Material.AIR))) {
            listener.interact(event(EquipmentSlot.HAND));
            listener.interact(event(EquipmentSlot.OFF_HAND));
            verify(inventory).setItemInMainHand(items.constructed().get(0));
            verify(inventory).setItemInOffHand(items.constructed().get(1));
        }
    }

    @Test
    void worldLifecycleAndHoppersRespectCustomRecordingPlayback() {
        listener.breakBlock(new org.bukkit.event.block.BlockBreakEvent(block, player));
        verify(studio).stop(block);
        listener.quit(new org.bukkit.event.player.PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null));
        verify(studio).quit(player);
        var world = mock(org.bukkit.World.class);
        when(world.getUID()).thenReturn(java.util.UUID.randomUUID());
        var chunk = mock(org.bukkit.Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(2); when(chunk.getZ()).thenReturn(3);
        listener.unload(new org.bukkit.event.world.ChunkUnloadEvent(chunk));
        when(world.getLoadedChunks()).thenReturn(new org.bukkit.Chunk[]{chunk});
        listener.unloadWorld(new org.bukkit.event.world.WorldUnloadEvent(world));
        verify(studio, times(2)).unload(world.getUID(), 2, 3);
        var source = mock(org.bukkit.inventory.Inventory.class);
        var destination = mock(org.bukkit.inventory.Inventory.class);
        when(destination.getHolder()).thenReturn(box);
        var blocked = new org.bukkit.event.inventory.InventoryMoveItemEvent(source, disc, destination, true);
        listener.hopper(blocked); assertTrue(blocked.isCancelled());
        when(destination.getHolder()).thenReturn(null);
        var ordinary = new org.bukkit.event.inventory.InventoryMoveItemEvent(source, disc, destination, true);
        listener.hopper(ordinary); assertFalse(ordinary.isCancelled());
        when(discs.custom(disc)).thenReturn(false);
        listener.hopper(ordinary); assertFalse(ordinary.isCancelled());
    }

}
