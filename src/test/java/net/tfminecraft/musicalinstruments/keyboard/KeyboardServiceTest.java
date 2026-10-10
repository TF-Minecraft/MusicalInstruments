package net.tfminecraft.musicalinstruments.keyboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.papermc.paper.connection.PlayerCommonConnection;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import net.tfminecraft.musicalinstruments.events.InstrumentPlayEvent;
import net.tfminecraft.musicalinstruments.keyboard.KeyboardFont.Size;
import net.tfminecraft.musicalinstruments.keyboard.KeyboardView.Cell;
import net.tfminecraft.musicalinstruments.managers.InstrumentManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class KeyboardServiceTest {
    private ServerMock server;
    private InstrumentPlugin plugin;
    private InstrumentManager manager;
    private DialogStubs dialogs;
    private final AtomicLong time = new AtomicLong(1_000_000L);
    private KeyboardService service;
    private PlayerMock player;
    private final List<InstrumentPlayEvent> played = new ArrayList<>();

    public class PlayListener implements Listener {
        @EventHandler
        public void onPlay(InstrumentPlayEvent event) {
            played.add(event);
        }
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = mock(InstrumentPlugin.class);
        when(plugin.getName()).thenReturn("MusicalInstruments");
        when(plugin.namespace()).thenReturn("musicalinstruments");
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        manager = mock(InstrumentManager.class);
        when(manager.getInstrument(any())).thenAnswer(invocation -> {
            ItemStack item = invocation.getArgument(0);
            return item != null && item.getType() == Material.STICK ? "lute" : null;
        });
        for (int slot = 1; slot <= 7; slot++) {
            when(manager.getSoundKey("lute", slot, false)).thenReturn("lute_" + slot);
        }
        when(manager.getPitch("lute")).thenReturn(1.0);
        when(manager.getVolume("lute")).thenReturn(4.0);
        dialogs = new DialogStubs();
        service = new KeyboardService(plugin, manager, NoteMapTest.settings(Map.of()), time::get);
        server.getPluginManager().registerEvents(new PlayListener(), MockBukkit.createMockPlugin());
        player = server.addPlayer();
        player.addAttachment(MockBukkit.createMockPlugin("Perms"), "instruments.use", true);
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
    }

    @AfterEach
    void tearDown() {
        dialogs.close();
        MockBukkit.unmock();
    }

    private int sent() {
        return dialogs.created.size();
    }

    private void nextTick(long ms) {
        server.getScheduler().performOneTick();
        time.addAndGet(ms);
    }

    private void click(int cell) {
        service.handleClick(player, KeyboardView.noteKey(cell), null);
    }

    // ------------------------------------------------------------ frames

    @Test
    void framesFlashThenRingThenRest() {
        assertEquals(new Cell(true, -1), KeyboardService.cell(0, true));
        assertEquals(new Cell(true, 0), KeyboardService.cell(50, true));
        assertEquals(new Cell(false, 1), KeyboardService.cell(120, true));
        assertEquals(new Cell(false, 2), KeyboardService.cell(199, true));
        assertEquals(Cell.IDLE, KeyboardService.cell(200, true));
        assertEquals(Cell.IDLE, KeyboardService.cell(-1, true));
        assertEquals(new Cell(true, -1), KeyboardService.cell(99, false));
        assertEquals(Cell.IDLE, KeyboardService.cell(100, false));
        assertEquals("SMALL--1L0", KeyboardService.signature(Size.SMALL, new Cell[]{Cell.IDLE, new Cell(true, 0)}));
        assertEquals("SMALL--1T", KeyboardService.signature(Size.SMALL, new Cell[]{new Cell(false, -1, true)}));
    }

    @Test
    void onlyThePlayersOwnInventoryLetsTheKeyboardRedraw() {
        assertTrue(KeyboardService.canShow(InventoryType.CRAFTING));
        assertTrue(KeyboardService.canShow(InventoryType.CREATIVE));
        assertFalse(KeyboardService.canShow(InventoryType.CHEST));
    }

    // ------------------------------------------------------------ notes

    @Test
    void aClickPlaysTheNoteAndAnimatesItsCircle() {
        service.open(player, "lute", false);
        assertEquals(1, sent());
        assertTrue(service.isOpen(player));

        nextTick(0);
        click(10); // middle row, F
        assertEquals("lute_4", player.getHeardSounds().getFirst().getSound());
        assertEquals(1.0f, player.getHeardSounds().getFirst().getPitch());
        assertEquals(1, played.size());
        assertEquals("lute", played.getFirst().getInstrument());
        assertEquals(1, ((WorldMock) player.getWorld()).getSpawnedParticles().size());
        verify(plugin).recordInstrumentPlay("lute");
        assertEquals(2, sent()); // lit at once

        service.tick(); // same tick: nothing more
        assertEquals(2, sent());
        nextTick(10);
        service.tick(); // still the lit frame
        assertEquals(2, sent());
        nextTick(40);
        service.tick(); // ring 0
        assertEquals(3, sent());
        nextTick(50);
        service.tick(); // ring 1
        nextTick(50);
        service.tick(); // ring 2
        assertEquals(5, sent());
        nextTick(5000); // a stall: the idle frame still goes out
        service.tick();
        assertEquals(6, sent());
        nextTick(50);
        service.tick();
        assertEquals(6, sent());
    }

    @Test
    void theStripUnderACirclePlaysItsChord() {
        when(manager.getSoundKey("lute", 4, true)).thenReturn("lute_4f_chord");
        service.open(player, "lute", false);
        nextTick(0);
        service.handleClick(player, KeyboardView.chordKey(10), null); // F chord, recorded
        assertEquals("lute_4f_chord", player.getHeardSounds().getFirst().getSound());
        assertEquals(1, played.size());
        assertEquals(2, sent());

        // The tab under the note stays lit for two frames; a clock step back keeps it unlit.
        nextTick(60);
        service.tick();
        assertEquals(3, sent());
        nextTick(60);
        service.tick();
        assertEquals(4, sent());
        time.addAndGet(-500);
        nextTick(0);
        service.tick();
        time.addAndGet(500);

        nextTick(300);
        service.handleClick(player, KeyboardView.chordKey(14), null); // C chord, built from C E G
        assertEquals(4, player.getHeardSounds().size());
        assertEquals("lute_1", player.getHeardSounds().get(1).getSound());
        assertEquals("lute_3", player.getHeardSounds().get(2).getSound());
        assertEquals("lute_5", player.getHeardSounds().get(3).getSound());
        assertEquals(0.5f, player.getHeardSounds().get(1).getPitch());
        assertEquals(2, played.size()); // one play per click
        verify(plugin, times(2)).recordInstrumentPlay("lute");

        // The chord's three circles flash, then the keyboard rests again.
        int before = sent();
        nextTick(250);
        service.tick();
        assertEquals(before + 1, sent());
        when(manager.getSoundKey("lute", 2, false)).thenReturn(null);
        when(manager.getSoundKey("lute", 4, false)).thenReturn(null);
        when(manager.getSoundKey("lute", 6, false)).thenReturn(null);
        nextTick(0);
        service.handleClick(player, KeyboardView.chordKey(1), null); // D F A: nothing to play
        assertEquals(4, player.getHeardSounds().size());
    }

    @Test
    void severalClicksInOneTickSendOnce() {
        service.open(player, "lute", false);
        click(0); // same tick as opening
        click(1);
        assertEquals(1, sent());
        assertEquals(2, player.getHeardSounds().size());
        nextTick(0);
        service.tick();
        assertEquals(2, sent());
    }

    @Test
    void ringsOffOnlyFlashes() {
        service.open(player, "lute", false);
        service.handleClick(player, KeyboardOptions.DONE, new KeyboardOptions.Choice(null, false));
        nextTick(0);
        click(3);
        int afterClick = sent();
        nextTick(50);
        service.tick(); // still lit
        assertEquals(afterClick, sent());
        nextTick(50);
        service.tick(); // back to idle
        assertEquals(afterClick + 1, sent());
        nextTick(50);
        service.tick(); // still inside the animation window, but nothing changes
        assertEquals(afterClick + 1, sent());
    }

    @Test
    void anIdleKeyboardIsNotRedrawn() {
        service.open(player, "lute", false);
        nextTick(50);
        service.tick();
        assertEquals(1, sent());
    }

    @Test
    void notesWithoutASoundStillAnimate() {
        service.open(player, "lute", false);
        nextTick(0);
        service.handleClick(player, KeyboardView.noteKey(20), null);
        assertEquals(1, player.getHeardSounds().size()); // low B exists
        when(manager.getSoundKey("lute", 7, false)).thenReturn(null);
        nextTick(0);
        service.handleClick(player, KeyboardView.noteKey(13), null);
        assertEquals(1, player.getHeardSounds().size());
        assertEquals(3, sent());
    }

    @Test
    void particlesCanBeTurnedOff() {
        KeyboardSettings quiet = new KeyboardSettings(true, true, Size.SMALL, true, 2f, 0.5f, false, Map.of(), Map.of());
        service = new KeyboardService(plugin, manager, quiet, time::get);
        service.open(player, "lute", false);
        click(0);
        assertEquals(1, player.getHeardSounds().size());
        assertTrue(((WorldMock) player.getWorld()).getSpawnedParticles().isEmpty());
    }

    @Test
    void actionsAreRateLimitedPerPlayer() {
        service.open(player, "lute", false);
        for (int i = 0; i < 25; i++) {
            click(i % 7);
        }
        assertEquals(KeyboardService.ACTIONS_PER_SECOND, player.getHeardSounds().size());
        // Reopening does not reset the limit, and menu buttons count too.
        service.open(player, "lute", false);
        service.handleClick(player, KeyboardView.OPTIONS, null);
        time.addAndGet(1000);
        click(0);
        assertEquals(KeyboardService.ACTIONS_PER_SECOND + 1, player.getHeardSounds().size());
    }

    @Test
    void theInstrumentMustStillBeHeldUnlessStaffOpenedIt() {
        service.open(player, "lute", true);
        player.getInventory().setItemInMainHand(null);
        click(0);
        assertEquals(1, player.getHeardSounds().size());

        service.open(player, "lute", false);
        click(1);
        assertEquals(1, player.getHeardSounds().size());
        assertFalse(service.isOpen(player));
        player.getInventory().setItemInOffHand(new ItemStack(Material.STICK));
        assertEquals("lute", service.heldInstrument(player));
    }

    @Test
    void staleClicksAreIgnored() {
        service.open(player, "lute", false);
        service.handleClick(player, KeyboardView.OPTIONS, null);
        click(0); // from the keyboard behind the options screen
        assertTrue(player.getHeardSounds().isEmpty());

        service.open(player, "lute", false);
        player.addAttachment(MockBukkit.createMockPlugin("Deny"), "instruments.use", false);
        click(0);
        assertTrue(player.getHeardSounds().isEmpty());

        PlayerMock dead = server.addPlayer();
        dead.addAttachment(MockBukkit.createMockPlugin("Perms2"), "instruments.use", true);
        service.open(dead, "lute", true);
        dead.setHealth(0);
        service.handleClick(dead, KeyboardView.noteKey(0), null);
        assertTrue(dead.getHeardSounds().isEmpty());
    }

    @Test
    void clicksWithoutASessionCloseTheScreen() {
        click(0);
        assertTrue(player.getHeardSounds().isEmpty());
        service.open(player, "lute", false);
        player.disconnect();
        click(0);
        assertTrue(player.getHeardSounds().isEmpty());
    }

    // ---------------------------------------------------------- options

    @Test
    void optionsOpenApplyAndClose() {
        service.open(player, "lute", false);
        service.handleClick(player, KeyboardView.OPTIONS, null);
        assertEquals(2, sent());
        nextTick(0);
        service.tick(); // options on screen: no keyboard redraw
        assertEquals(2, sent());

        service.handleClick(player, KeyboardOptions.DONE, new KeyboardOptions.Choice("LARGE", true));
        assertEquals(3, sent());
        service.handleClick(player, KeyboardOptions.DONE, null);
        assertEquals(4, sent());

        service.handleClick(player, Key.key(KeyboardView.NAMESPACE, "unknown"), null);
        assertEquals(4, sent());
        assertTrue(service.isOpen(player));
    }

    @Test
    void aTitleMarkerKeepsTheBackgroundSharpWhileTheKeyboardIsOpen() {
        PlayerMock spied = spy(player);
        service.open(spied, "lute", false);
        verify(spied).showTitle(KeyboardService.BLUR_MARKER);

        nextTick(0);
        service.handleClick(spied, KeyboardView.noteKey(0), null); // redraws do not resend the marker
        verify(spied, times(1)).showTitle(KeyboardService.BLUR_MARKER);

        // While open the marker is renewed before it runs out.
        server.getScheduler().performTicks(KeyboardService.MARKER_REFRESH_TICKS);
        service.tick();
        service.tick();

        // Any sign the keyboard is gone clears it, once.
        service.onHeld(new PlayerItemHeldEvent(spied, 0, 1));
        service.onSwing(new PlayerAnimationEvent(spied, org.bukkit.event.player.PlayerAnimationType.ARM_SWING));
        verify(spied, times(1)).clearTitle();
        server.getScheduler().performTicks(KeyboardService.MARKER_REFRESH_TICKS);
        service.tick(); // closed: no renewal
    }

    @Test
    void theOptionsScreenIsBlurredLikeOtherMenus() {
        PlayerMock spied = spy(player);
        service.open(spied, "lute", false);
        service.handleClick(spied, KeyboardView.OPTIONS, null);
        verify(spied).clearTitle();
        server.getScheduler().performTicks(KeyboardService.MARKER_REFRESH_TICKS);
        service.tick(); // no renewal behind the options screen
        verify(spied, times(1)).showTitle(KeyboardService.BLUR_MARKER);
        service.handleClick(spied, KeyboardOptions.DONE, new KeyboardOptions.Choice("SMALL", true));
        verify(spied, times(2)).showTitle(KeyboardService.BLUR_MARKER);
    }

    @Test
    void customClicksAreRoutedToTheKeyboard() throws InterruptedException {
        service.open(player, "lute", false);
        PlayerGameConnection connection = mock(PlayerGameConnection.class);
        when(connection.getPlayer()).thenReturn(player);

        service.onCustomClick(clickEvent(Key.key("other", "n0"), connection, null));
        service.onCustomClick(clickEvent(KeyboardView.noteKey(0), mock(PlayerCommonConnection.class), null));
        assertTrue(player.getHeardSounds().isEmpty());

        service.onCustomClick(clickEvent(KeyboardView.noteKey(0), connection, null));
        assertEquals(1, player.getHeardSounds().size());

        DialogResponseView view = mock(DialogResponseView.class);
        when(view.getText("size")).thenReturn("SMALL");
        service.onCustomClick(clickEvent(KeyboardOptions.DONE, connection, view));
        assertTrue(service.isOpen(player));
        verify(view).getText("size");

        // Off the main thread the click waits for the next tick.
        service.open(player, "lute", false);
        Thread other = new Thread(() -> service.onCustomClick(clickEvent(KeyboardView.noteKey(1), connection, null)));
        other.start();
        other.join();
        assertEquals(1, player.getHeardSounds().size());
        server.getScheduler().performOneTick();
        assertEquals(2, player.getHeardSounds().size());
    }

    private static PlayerCustomClickEvent clickEvent(Key id, PlayerCommonConnection connection, DialogResponseView view) {
        PlayerCustomClickEvent event = mock(PlayerCustomClickEvent.class);
        when(event.getIdentifier()).thenReturn(id);
        when(event.getCommonConnection()).thenReturn(connection);
        when(event.getDialogResponseView()).thenReturn(view);
        return event;
    }

    // --------------------------------------------------- redraw guards

    @Test
    void redrawsStopWhenThePlayerCannotSeeTheKeyboard() {
        service.open(player, "lute", false);
        nextTick(0);
        click(0);
        int base = sent();

        player.openInventory(server.createInventory(null, 9));
        nextTick(60);
        service.tick();
        assertEquals(base, sent());
        assertFalse(service.isOpen(player));
        nextTick(0);
        service.tick(); // marked closed: stays quiet
        assertEquals(base, sent());

        player.closeInventory();
        service.open(player, "lute", false);
        nextTick(0);
        click(1);
        base = sent();
        player.setHealth(0);
        nextTick(60);
        service.tick();
        assertEquals(base, sent());

        PlayerMock gone = server.addPlayer();
        service.open(gone, "lute", true);
        gone.disconnect();
        nextTick(0);
        service.tick(); // drops the session of a player who left
        service.handleClick(gone, KeyboardView.noteKey(0), null);
        assertTrue(gone.getHeardSounds().isEmpty());
    }

    @Test
    void signsOfPlayingMarkTheScreenClosed() {
        Location here = player.getLocation();
        Location turned = here.clone();
        turned.setYaw(here.getYaw() + 30);
        Location tilted = here.clone();
        tilted.setPitch(here.getPitch() + 30);
        Location stepped = here.clone().add(1, 0, 0);

        List<Runnable> signs = List.of(
                () -> service.onMove(new PlayerMoveEvent(player, here, turned)),
                () -> service.onMove(new PlayerMoveEvent(player, here, tilted)),
                () -> service.onTeleport(new PlayerTeleportEvent(player, here, stepped)),
                () -> service.onSwing(new PlayerAnimationEvent(player, org.bukkit.event.player.PlayerAnimationType.ARM_SWING)),
                () -> service.onHeld(new PlayerItemHeldEvent(player, 0, 1)),
                () -> service.onDrop(dropEvent()),
                () -> service.onInventory(inventoryEvent(player)));
        for (Runnable sign : signs) {
            service.open(player, "lute", false);
            server.getScheduler().performTicks(KeyboardService.OPEN_GRACE_TICKS);
            sign.run();
            assertFalse(service.isOpen(player));
        }

        service.open(player, "lute", false);
        server.getScheduler().performTicks(KeyboardService.OPEN_GRACE_TICKS);
        service.onMove(new PlayerMoveEvent(player, here, stepped)); // walking alone can be knockback
        service.onInventory(inventoryEvent(mock(HumanEntity.class)));
        assertTrue(service.isOpen(player));

        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        service.onQuit(quit);
        assertFalse(service.isOpen(player));
        service.onHeld(new PlayerItemHeldEvent(player, 1, 2)); // no session: nothing to close
    }

    @Test
    void theViewIsComparedWithTheOneTheKeyboardWasPlayedWith() {
        Location here = player.getLocation();
        Location nudged = here.clone();
        nudged.setYaw(here.getYaw() + 5);
        Location nudgedAndStepped = nudged.clone().add(0.1, 0, 0);
        Location wrapped = nudged.clone();
        wrapped.setYaw(nudged.getYaw() + 360);

        // Mouse movement sent before the screen appeared settles the view.
        service.open(player, "lute", false);
        service.onMove(new PlayerMoveEvent(player, here, nudged));
        server.getScheduler().performTicks(KeyboardService.OPEN_GRACE_TICKS);
        // Paper's event starts where the last fired one ended: still the old view, yet nothing turned.
        service.onMove(new PlayerMoveEvent(player, here, nudgedAndStepped));
        service.onMove(new PlayerMoveEvent(player, here, wrapped));
        assertTrue(service.isOpen(player));
        Location turned = nudged.clone();
        turned.setYaw(nudged.getYaw() + KeyboardService.LOOK_TOLERANCE * 2);
        service.onMove(new PlayerMoveEvent(player, nudged, turned));
        assertFalse(service.isOpen(player));
        service.onMove(new PlayerMoveEvent(player, turned, here)); // already closed

        // Playing a note takes the current view.
        player.teleport(turned);
        click(0);
        service.onMove(new PlayerMoveEvent(player, here, turned));
        assertTrue(service.isOpen(player));
        PlayerMoveEvent idle = new PlayerMoveEvent(server.addPlayer(), here, turned);
        service.onMove(idle); // no keyboard
    }

    @Test
    void yawDifferenceWraps() {
        assertEquals(10f, KeyboardService.yawDifference(355f, 5f));
        assertEquals(10f, KeyboardService.yawDifference(-365f, 5f));
        assertEquals(0f, KeyboardService.yawDifference(10f, 370f));
        assertEquals(180f, KeyboardService.yawDifference(0f, 180f));
    }

    private PlayerDropItemEvent dropEvent() {
        PlayerDropItemEvent event = mock(PlayerDropItemEvent.class);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    private static InventoryOpenEvent inventoryEvent(HumanEntity who) {
        InventoryOpenEvent event = mock(InventoryOpenEvent.class);
        when(event.getPlayer()).thenReturn(who);
        return event;
    }

    // ---------------------------------------------------------- opening

    private PlayerInteractEvent interact(Action action, Block block, EquipmentSlot hand) {
        ItemStack item = hand == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        PlayerInteractEvent event = new PlayerInteractEvent(player, action, item, block, BlockFace.UP, hand);
        service.onInteract(event);
        return event;
    }

    @Test
    void rightClickingWithAnInstrumentOpensTheKeyboard() {
        PlayerInteractEvent event = interact(Action.RIGHT_CLICK_AIR, null, EquipmentSlot.HAND);
        assertEquals(1, sent());
        assertEquals(Event.Result.DENY, event.useItemInHand());

        interact(Action.RIGHT_CLICK_AIR, null, EquipmentSlot.HAND); // the other hand in the same tick
        assertEquals(1, sent());

        nextTick(0);
        Block stone = player.getWorld().getBlockAt(0, 64, 0);
        stone.setType(Material.STONE);
        interact(Action.RIGHT_CLICK_BLOCK, stone, EquipmentSlot.HAND);
        assertEquals(2, sent());
        // The empty off hand's click on the same block follows; it must not drop the no-blur marker.
        PlayerMock spied = spy(player);
        service.onInteract(new PlayerInteractEvent(spied, Action.RIGHT_CLICK_BLOCK, null, stone, BlockFace.UP,
                EquipmentSlot.OFF_HAND));
        service.onSwing(new PlayerAnimationEvent(spied, org.bukkit.event.player.PlayerAnimationType.ARM_SWING));
        assertTrue(service.isOpen(spied));
        verify(spied, never()).clearTitle();
    }

    @Test
    void rightClicksThatMeanSomethingElseAreLeftAlone() {
        Block chest = player.getWorld().getBlockAt(1, 64, 0);
        chest.setType(Material.CHEST);
        Block stone = player.getWorld().getBlockAt(2, 64, 0);
        stone.setType(Material.STONE);

        interact(Action.LEFT_CLICK_AIR, null, EquipmentSlot.HAND);
        interact(Action.RIGHT_CLICK_BLOCK, chest, EquipmentSlot.HAND);
        interact(Action.RIGHT_CLICK_BLOCK, null, EquipmentSlot.HAND);

        PlayerInteractEvent denied = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR,
                player.getInventory().getItemInMainHand(), null, BlockFace.UP, EquipmentSlot.HAND);
        denied.setUseItemInHand(Event.Result.DENY);
        service.onInteract(denied);

        PlayerInteractEvent blockDenied = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                player.getInventory().getItemInMainHand(), stone, BlockFace.UP, EquipmentSlot.HAND);
        blockDenied.setUseInteractedBlock(Event.Result.DENY);
        service.onInteract(blockDenied);

        // Off hand only counts with an empty main hand.
        player.getInventory().setItemInOffHand(new ItemStack(Material.STICK));
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIRT));
        interact(Action.RIGHT_CLICK_AIR, null, EquipmentSlot.HAND); // dirt is no instrument
        interact(Action.RIGHT_CLICK_AIR, null, EquipmentSlot.OFF_HAND);
        assertEquals(0, sent());

        player.getInventory().setItemInMainHand(null);
        interact(Action.RIGHT_CLICK_AIR, null, EquipmentSlot.OFF_HAND);
        assertEquals(1, sent());
    }

    @Test
    void rightClickOpeningCanBeTurnedOffOrDenied() {
        KeyboardSettings off = new KeyboardSettings(true, false, Size.SMALL, true, 2f, 0.5f, true, Map.of(), Map.of());
        service = new KeyboardService(plugin, manager, off, time::get);
        interact(Action.RIGHT_CLICK_AIR, null, EquipmentSlot.HAND);
        assertEquals(0, sent());

        service = new KeyboardService(plugin, manager, NoteMapTest.settings(Map.of()), time::get);
        player.addAttachment(MockBukkit.createMockPlugin("Deny"), "instruments.use", false);
        interact(Action.RIGHT_CLICK_AIR, null, EquipmentSlot.HAND);
        assertEquals(0, sent());
    }

    // -------------------------------------------------------- lifecycle

    @Test
    void closingTheServiceClosesOpenKeyboards() {
        service.close(); // never started
        service.start();
        PlayerMock idle = server.addPlayer();
        PlayerMock gone = server.addPlayer();
        service.open(player, "lute", true);
        service.open(idle, "lute", true);
        service.open(gone, "lute", true);
        server.getScheduler().performTicks(KeyboardService.OPEN_GRACE_TICKS);
        service.onHeld(new PlayerItemHeldEvent(idle, 0, 1));
        gone.disconnect();

        service.close();

        assertFalse(service.isOpen(player));
        assertFalse(service.isOpen(idle));
    }

    @Test
    void theTickerRunsEveryTick() {
        service.start();
        service.open(player, "lute", false);
        nextTick(0);
        click(0);
        int base = sent();
        nextTick(60);
        server.getScheduler().performOneTick();
        assertTrue(sent() > base);
        verify(manager, atLeastOnce()).getVolume("lute");
    }
}
