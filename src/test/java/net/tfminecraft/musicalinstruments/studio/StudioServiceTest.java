package net.tfminecraft.musicalinstruments.studio;

import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import net.tfminecraft.musicalinstruments.managers.InstrumentManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StudioServiceTest {
    @TempDir Path directory;
    private InstrumentPlugin plugin;
    private Server server;
    private PluginManager pluginManager;
    private Player player;
    private PlayerInventory inventory;
    private World world;
    private DiscItems discs;
    private StudioStore store;
    private StudioService studio;
    private BukkitTask task;
    private Runnable tick;
    private InstrumentManager manager;
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setup() throws Exception {
        plugin = mock(InstrumentPlugin.class);
        server = mock(Server.class);
        pluginManager = mock(PluginManager.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        task = mock(BukkitTask.class);
        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        world = mock(World.class);
        discs = mock(DiscItems.class);
        store = spy(new StudioStore(directory));
        manager = mock(InstrumentManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(manager.getInstrument(any())).thenReturn("lute");
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(server.getPlayer(owner)).thenReturn(player);
        when(player.getUniqueId()).thenReturn(owner);
        when(player.getName()).thenReturn("Musician");
        when(player.getInventory()).thenReturn(inventory);
        when(player.getLocation()).thenReturn(new Location(world, 0, 0, 0));
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(server.getWorld(world.getUID())).thenReturn(world);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getPlayers()).thenReturn(List.of(player));
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L))).thenReturn(task);
        studio = new StudioService(plugin, manager, store, new StudioSettings(4, 40, 20, 60, 8, 32, 2, 0, 2),
                StudioStation.none(), discs);
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskTimer(eq(plugin), callback.capture(), eq(1L), eq(1L));
        tick = callback.getValue();
    }

    @AfterEach
    void close() {
        studio.close();
    }

    private Song song(Track... tracks) {
        return new Song(UUID.randomUUID(), owner, "Musician", "Song", List.of(tracks));
    }

    private Track track(int slot, String sound) {
        return new Track(slot, 10, 1, false, List.of(new Note(0, "lute", sound, 4, 1)));
    }

    @Test
    void countInIgnoresNotesAndOverdubRetainsThePreviousTrackUntilKept() throws Exception {
        Song original = song(track(1, "backing"), track(2, "old_take"));
        studio.save(player, new Project(original, null, 100, false));
        studio.record(player, 2);
        studio.capture(player, "flute", "early", 4, 1);
        tick.run();
        studio.capture(player, "flute", "early", 4, 1);
        tick.run();
        verify(player).playSound(any(Location.class), eq("backing"), eq(SoundCategory.RECORDS), eq(4f), eq(1f));
        verify(player, never()).playSound(any(Location.class), eq("old_take"), any(SoundCategory.class), anyFloat(), anyFloat());
        studio.capture(player, "flute", "new_take", 2, 1.5f);
        studio.stop(player);
        Project pending = studio.requireProject(player);
        assertEquals(original, pending.song());
        assertEquals(1, pending.pending().notes().size());
        assertEquals(0, pending.pending().notes().getFirst().tick());
        studio.edit(player, pending.accept());
        assertEquals("new_take", studio.requireProject(player).song().track(2).notes().getFirst().sound());
    }

    @Test
    void previewsPlaySimultaneousTracksWithoutEmittingLivePerformanceEvents() throws Exception {
        studio.save(player, new Project(song(track(1, "lute"), track(2, "flute")), null, 100, false));
        studio.preview(player, false);
        tick.run();
        verify(player).playSound(any(Location.class), eq("lute"), eq(SoundCategory.RECORDS), eq(4f), eq(1f));
        verify(player).playSound(any(Location.class), eq("flute"), eq(SoundCategory.RECORDS), eq(4f), eq(1f));
        verifyNoInteractions(pluginManager);
    }

    @Test
    void newProjectsPreserveOlderTracksPendingTakesAndActiveSelectionAfterRestart() throws Exception {
        Project first = new Project(song(track(1, "first")), track(2, "pending"), 120, false);
        studio.save(player, first);
        studio.create(player, "Second", false);
        Project second = studio.requireProject(player);
        assertEquals(2, studio.listProjects(player).size());
        studio.selectProject(player, first.song().id());
        assertEquals(first, studio.requireProject(player));
        assertEquals(first, new StudioStore(directory).project(owner).orElseThrow());
        studio.create(player, "Reset", true);
        assertEquals(first.song().id(), studio.requireProject(player).song().id());
        assertTrue(studio.requireProject(player).song().tracks().isEmpty());
        assertEquals(second, store.project(owner, second.song().id()));
    }

    @Test
    void changingProjectsIsBlockedDuringCaptureAndIsSafeAfterFinishing() throws Exception {
        studio.create(player, "First", false);
        UUID first = studio.requireProject(player).song().id();
        studio.create(player, "Second", false);
        studio.record(player, 1);
        assertThrows(IllegalArgumentException.class, () -> studio.selectProject(player, first));
        assertThrows(IllegalArgumentException.class, () -> studio.create(player, "Third", false));
        studio.stop(player);
        studio.selectProject(player, first);
        assertEquals(first, studio.requireProject(player).song().id());
    }

    @Test
    void listeningToOneTrackDoesNotEmitTheRestOfTheMix() throws Exception {
        studio.save(player, new Project(song(track(1, "solo"), track(2, "other")), null, 100, false));
        studio.previewTrack(player, 1);
        tick.run();
        verify(player).playSound(any(Location.class), eq("solo"), eq(SoundCategory.RECORDS), anyFloat(), anyFloat());
        verify(player, never()).playSound(any(Location.class), eq("other"), any(SoundCategory.class), anyFloat(), anyFloat());
    }

    @Test
    void checkpointsAndShutdownRecoverAnUnacceptedTake() throws Exception {
        studio.create(player, "Song", false);
        studio.record(player, 1);
        tick.run();
        tick.run();
        studio.capture(player, "lute", "first", 4, 1);
        tick.run();
        tick.run();
        verify(store, timeout(3000).times(2)).save(any());
        // Mockito observes the call at entry; await the atomically installed file contents.
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (store.project(owner).orElseThrow().pending() == null && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals("first", new StudioStore(directory).project(owner).orElseThrow().pending().notes().getFirst().sound());
        studio.capture(player, "lute", "second", 4, 1);
        studio.close();
        Project saved = new StudioStore(directory).project(owner).orElseThrow();
        assertEquals(2, saved.pending().notes().size());
        assertTrue(saved.song().tracks().isEmpty());
        verify(task).cancel();
    }

    @Test
    void emptyTakeDoesNotReplaceASavedTrack() throws Exception {
        Song original = song(track(1, "saved"));
        studio.save(player, new Project(original, null, 100, false));
        studio.record(player, 1);
        studio.stop(player);
        assertEquals(original, studio.requireProject(player).song());
        assertNull(studio.requireProject(player).pending());
    }

    @Test
    void anOlderBackgroundCheckpointCannotOverwriteTheFinalTake() throws Exception {
        studio.create(player, "Song", false);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("musical-instruments-checkpoints")) {
                started.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) {
                    throw new IOException("Test checkpoint timed out");
                }
            }
            return invocation.callRealMethod();
        }).when(store).save(any());
        studio.record(player, 1);
        tick.run();
        tick.run();
        studio.capture(player, "lute", "first", 4, 1);
        tick.run();
        tick.run();
        assertTrue(started.await(3, TimeUnit.SECONDS));
        studio.capture(player, "lute", "second", 4, 1);
        CompletableFuture<Void> stopped = CompletableFuture.runAsync(() -> studio.stop(player));
        release.countDown();
        stopped.get(3, TimeUnit.SECONDS);
        Project saved = new StudioStore(directory).project(owner).orElseThrow();
        assertEquals(List.of("first", "second"), saved.pending().notes().stream().map(Note::sound).toList());
    }

    @Test
    void noteLimitFinishesTheTakeWithoutOverwritingTheOldTrack() throws Exception {
        Song original = song(track(1, "saved"));
        studio.save(player, new Project(original, null, 100, false));
        studio.record(player, 1);
        tick.run();
        tick.run();
        for (int i = 0; i < 21; i++) {
            studio.capture(player, "lute", "new", 4, 1);
        }
        Project project = studio.requireProject(player);
        assertEquals(original, project.song());
        assertEquals(20, project.pending().notes().size());
        studio.requireIdle(player);
    }

    @Test
    void maximumDurationFinishesAndPersistsTheTake() throws Exception {
        studio.create(player, "Song", false);
        studio.record(player, 1);
        tick.run();
        tick.run();
        studio.capture(player, "lute", "sample", 4, 1);
        for (int i = 0; i < 40; i++) {
            tick.run();
        }
        Project project = studio.requireProject(player);
        assertEquals(40, project.pending().lengthTicks());
        studio.requireIdle(player);
        assertEquals(project, store.project(owner).orElseThrow());
    }

    @Test
    void failedPublicationPreservesThePhysicalBlankDisc() throws Exception {
        studio.save(player, new Project(song(track(1, "sample")), null, 100, false));
        ItemStack blank = mock(ItemStack.class);
        when(inventory.getItemInMainHand()).thenReturn(blank);
        when(blank.getAmount()).thenReturn(1);
        when(discs.blank(blank)).thenReturn(true);
        when(discs.disc(any())).thenReturn(mock(ItemStack.class));
        doThrow(new IOException("Disk full")).when(store).publish(any());
        assertThrows(IOException.class, () -> studio.publish(player));
        verify(inventory, never()).setItemInMainHand(any());
    }

    private ItemStack inventoryItem(Material type, int amount, boolean blank) {
        ItemStack item = mock(ItemStack.class);
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenReturn(amount);
        when(discs.blank(item)).thenReturn(blank);
        return item;
    }

    @Test
    void menuPublicationConsumesOneInventoryDiscWithoutChangingTheHeldInstrument() throws Exception {
        studio.save(player, new Project(song(track(1, "sample")), null, 100, false));
        ItemStack blank = inventoryItem(Material.MUSIC_DISC_13, 3, true);
        ItemStack remainder = mock(ItemStack.class);
        ItemStack published = mock(ItemStack.class);
        when(blank.clone()).thenReturn(remainder);
        when(discs.disc(any())).thenReturn(published);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{blank, null});
        studio.publishFromInventory(player);
        verify(remainder).setAmount(2);
        verify(inventory).setItem(0, remainder);
        verify(inventory).setItem(1, published);
        verify(inventory, never()).setItemInMainHand(any());
        assertEquals(3, studio.blankDiscs(player));
    }

    @Test
    void fullInventoryPreventsPublicationBeforeWritingOrConsumingADisc() throws Exception {
        studio.save(player, new Project(song(track(1, "sample")), null, 100, false));
        ItemStack blank = inventoryItem(Material.MUSIC_DISC_13, 2, true);
        ItemStack stone = inventoryItem(Material.STONE, 64, false);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{blank, stone});
        assertThrows(IllegalArgumentException.class, () -> studio.publishFromInventory(player));
        verify(store, never()).publish(any());
        verify(inventory, never()).setItem(anyInt(), any());
    }

    @Test
    void fullInventoryCanUseALaterSingleDiscRatherThanSplitTheFirstStack() throws Exception {
        studio.save(player, new Project(song(track(1, "sample")), null, 100, false));
        ItemStack stack = inventoryItem(Material.MUSIC_DISC_13, 2, true);
        ItemStack single = inventoryItem(Material.MUSIC_DISC_13, 1, true);
        ItemStack published = mock(ItemStack.class);
        when(discs.disc(any())).thenReturn(published);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{stack, single});
        studio.publishFromInventory(player);
        verify(inventory).setItem(1, published);
        verify(inventory, never()).setItem(eq(0), any());
    }

    @Test
    void failedMenuPublicationPreservesAllInventoryItems() throws Exception {
        studio.save(player, new Project(song(track(1, "sample")), null, 100, false));
        ItemStack blank = inventoryItem(Material.MUSIC_DISC_13, 1, true);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{blank});
        doThrow(new IOException("Disk full")).when(store).publish(any());
        assertThrows(IOException.class, () -> studio.publishFromInventory(player));
        verify(inventory, never()).setItem(anyInt(), any());
    }

    @Test
    void menuCopyRespectsItsPermissionBeforeInspectingOrConsumingDiscs() {
        when(player.hasPermission("instruments.copy")).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> studio.copyFromInventory(player));
        verifyNoInteractions(inventory);
    }

    @Test
    void publicationAndCopiesReferenceAnImmutableEdition() throws Exception {
        Song original = song(track(1, "original"));
        studio.save(player, new Project(original, null, 100, false));
        ItemStack blank = mock(ItemStack.class);
        ItemStack published = mock(ItemStack.class);
        when(inventory.getItemInMainHand()).thenReturn(blank);
        when(blank.getAmount()).thenReturn(1);
        when(discs.blank(blank)).thenReturn(true);
        when(discs.disc(any())).thenReturn(published);
        studio.publish(player);
        var captured = ArgumentCaptor.forClass(Song.class);
        verify(store).publish(captured.capture());
        Song edition = captured.getValue();
        studio.edit(player, new Project(original.replace(track(1, "changed")), null, 100, false));
        when(inventory.getItemInOffHand()).thenReturn(published);
        when(discs.songId(published)).thenReturn(edition.id());
        studio.copy(player);
        assertEquals("original", store.song(edition.id()).track(1).notes().getFirst().sound());
        verify(inventory, times(2)).setItemInMainHand(published);
    }

    private Block jukebox(Song song, ItemStack disc) throws IOException {
        store.publish(song);
        Block block = mock(Block.class);
        Jukebox box = mock(Jukebox.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getState()).thenReturn(box);
        when(block.getType()).thenReturn(Material.JUKEBOX);
        when(discs.custom(disc)).thenReturn(true);
        when(discs.playbackDisc(disc)).thenReturn(disc);
        when(block.getLocation()).thenReturn(new Location(world, 0, 0, 0));
        when(world.getBlockAt(0, 0, 0)).thenReturn(block);
        when(box.update(false, false)).thenReturn(true);
        when(box.getRecord()).thenReturn(disc);
        when(disc.asOne()).thenReturn(disc);
        when(discs.songId(disc)).thenReturn(song.id());
        return block;
    }

    @Test
    void jukeboxAppliesItsSnapshotAndPlaysEveryTrackOnlyToNearbyListeners() throws Exception {
        Song song = song(track(1, "lute"), track(2, "flute"));
        ItemStack disc = mock(ItemStack.class);
        Block block = jukebox(song, disc);
        Player farAway = mock(Player.class);
        when(farAway.getLocation()).thenReturn(new Location(world, 100, 0, 0));
        when(world.getPlayers()).thenReturn(List.of(player, farAway));
        studio.play(block, disc);
        var order = inOrder((Jukebox) block.getState());
        order.verify((Jukebox) block.getState()).stopPlaying();
        order.verify((Jukebox) block.getState()).setRecord(disc);
        order.verify((Jukebox) block.getState()).update(false, false);
        order.verify((Jukebox) block.getState()).stopPlaying();
        tick.run();
        verify(player).playSound(eq(new Location(world, 0.5, 0.5, 0.5)), eq("lute"), eq(SoundCategory.RECORDS), eq(4f), eq(1f));
        verify(player).playSound(any(Location.class), eq("flute"), eq(SoundCategory.RECORDS), eq(4f), eq(1f));
        verify(farAway, never()).playSound(any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
        verifyNoInteractions(pluginManager);
    }

    @Test
    void ejectionAndChunkUnloadingCancelFutureNotes() throws Exception {
        Song song = song(new Track(1, 10, 1, false, List.of(new Note(3, "lute", "late", 4, 1))));
        ItemStack disc = mock(ItemStack.class);
        Block block = jukebox(song, disc);
        studio.play(block, disc);
        studio.stop(block);
        for (int i = 0; i < 5; i++) tick.run();
        studio.play(block, disc);
        studio.unload(world.getUID(), 0, 0);
        for (int i = 0; i < 5; i++) tick.run();
        verify(player, never()).playSound(any(Location.class), eq("late"), any(SoundCategory.class), anyFloat(), anyFloat());
    }

    @Test
    void missingEditionDoesNotAlterTheJukebox() {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        ItemStack disc = mock(ItemStack.class);
        when(discs.songId(disc)).thenReturn(UUID.randomUUID());
        assertThrows(IOException.class, () -> studio.play(block, disc));
        verify(block, never()).getState();
    }
    @Test
    void fullLibraryRejectsCreationUntilAProjectIsDeletedAndResetRemainsAllowed() throws Exception {
        for (int index = 0; index < StudioService.MAX_PROJECTS; index++) studio.create(player, "Song " + index, false);
        Project active = studio.requireProject(player);
        assertThrows(IllegalArgumentException.class, () -> studio.create(player, "Extra", false));
        assertEquals(active, studio.requireProject(player));
        studio.create(player, "Reset", true);
        assertEquals(36, studio.listProjects(player).size());
        Project inactive = studio.listProjects(player).stream().filter(p -> !p.song().id().equals(active.song().id())).findFirst().orElseThrow();
        studio.deleteProject(player, inactive);
        studio.create(player, "Replacement", false);
        assertEquals(36, studio.listProjects(player).size());
    }

    @Test
    void deletingActiveProjectDoesNotResurrectItAndKeepsOtherProjectsAndEditions() throws Exception {
        studio.create(player, "Other", false);
        Project other = studio.requireProject(player);
        Project active = new Project(song(track(1, "note")), null, 100, false);
        studio.save(player, active);
        Song edition = active.song().edition();
        store.publish(edition);
        studio.deleteProject(player, active);
        assertNull(studio.project(player));
        studio.quit(player);
        assertTrue(store.project(owner).isEmpty());
        assertEquals(List.of(other), store.projects(owner));
        assertEquals(edition, store.song(edition.id()));
    }

    @Test
    void deletionRejectsStaleConfirmationForeignOwnerAndActiveRecording() throws Exception {
        Project before = new Project(song(track(1, "note")), null, 100, false);
        studio.save(player, before);
        Project changed = new Project(before.song(), null, 105, false);
        studio.edit(player, changed);
        assertThrows(IllegalArgumentException.class, () -> studio.deleteProject(player, before));
        Project foreign = new Project(new Song(UUID.randomUUID(), UUID.randomUUID(), "Other", "Other", List.of()), null, 100, false);
        assertThrows(IllegalArgumentException.class, () -> studio.deleteProject(player, foreign));
        studio.record(player, 2);
        assertThrows(IllegalArgumentException.class, () -> studio.deleteProject(player, changed));
        assertEquals(changed, store.project(owner).orElseThrow());
    }

    @Test
    void publishingOnProvidedDiscKeepsEditionsSeparateAndRejectsStacks() throws Exception {
        studio.save(player, new Project(song(track(1, "note")), null, 100, false));
        ItemStack original = mock(ItemStack.class);
        ItemStack encoded = mock(ItemStack.class);
        when(discs.musicDisc(original)).thenReturn(true);
        when(original.getAmount()).thenReturn(2);
        assertThrows(IllegalArgumentException.class, () -> studio.publishOnDisc(player, original));
        verify(store, never()).publish(any());
        when(original.getAmount()).thenReturn(1);
        when(discs.disc(any(Song.class), eq(original))).thenReturn(encoded);
        assertSame(encoded, studio.publishOnDisc(player, original));
        var published = ArgumentCaptor.forClass(Song.class);
        verify(store).publish(published.capture());
        assertNotEquals(studio.requireProject(player).song().id(), published.getValue().id());
        assertEquals(published.getValue(), store.song(published.getValue().id()));
        verifyNoInteractions(inventory);
    }

    @Test
    void providedDiscSurvivesPublicationFailureAndPendingTakesCannotBePublished() throws Exception {
        Project project = new Project(song(track(1, "note")), track(2, "pending"), 100, false);
        studio.save(player, project);
        ItemStack original = mock(ItemStack.class);
        when(discs.musicDisc(original)).thenReturn(true);
        when(original.getAmount()).thenReturn(1);
        assertThrows(IllegalArgumentException.class, () -> studio.publishOnDisc(player, original));
        studio.edit(player, project.discard());
        doThrow(new IOException("disk full")).when(store).publish(any());
        assertThrows(IOException.class, () -> studio.publishOnDisc(player, original));
        verify(discs, never()).disc(any(Song.class), any(ItemStack.class));
        verify(original, never()).setAmount(anyInt());
        verifyNoInteractions(inventory);
    }

    @Test
    void legacyDiscIsNormalizedBeforeInsertionAndStopTargetsOnlyItsJukeboxChannel() throws Exception {
        Song song = song(track(1, "note"));
        ItemStack oldDisc = mock(ItemStack.class);
        ItemStack silentDisc = mock(ItemStack.class);
        Block block = jukebox(song, oldDisc);
        when(discs.playbackDisc(oldDisc)).thenReturn(silentDisc);
        when(discs.songId(silentDisc)).thenReturn(song.id());
        Jukebox box = (Jukebox) block.getState();
        when(box.getRecord()).thenReturn(silentDisc);
        var order = inOrder(discs, box);
        studio.play(block, oldDisc);
        order.verify(discs).playbackDisc(oldDisc);
        order.verify(box).setRecord(silentDisc);
        order.verify(box).update(false, false);
        order.verify(box).stopPlaying();
        clearInvocations(world, box);
        studio.stop(block);
        verify(box).stopPlaying();
        verify(world).playEffect(block.getLocation(), org.bukkit.Effect.SOUND_STOP_JUKEBOX_SONG, 0, 64);
        verify(player, never()).stopAllSounds();
        tick.run();
        verify(player, never()).playSound(any(Location.class), eq("note"), any(SoundCategory.class), anyFloat(), anyFloat());
        verify(oldDisc, never()).setAmount(anyInt());
    }

    @Test
    void finishedCustomRecordStillClearsVanillaSoundWhileOrdinaryDiscsAreUntouched() throws Exception {
        Song song = song(track(1, "note"));
        ItemStack disc = mock(ItemStack.class);
        Block block = jukebox(song, disc);
        studio.play(block, disc);
        for (int index = 0; index < 12; index++) tick.run();
        Jukebox box = (Jukebox) block.getState();
        clearInvocations(world, box);
        studio.stop(block);
        verify(world).playEffect(block.getLocation(), org.bukkit.Effect.SOUND_STOP_JUKEBOX_SONG, 0, 64);
        clearInvocations(world, box);
        when(discs.custom(disc)).thenReturn(false);
        studio.stop(block);
        verify(world, never()).playEffect(any(Location.class), any(org.bukkit.Effect.class), anyInt(), anyInt());
        verify(box, never()).stopPlaying();
    }


    @Test
    void takesNeedAnInstrumentInEitherHand() throws Exception {
        ItemStack offHand = mock(ItemStack.class);
        ItemStack mainHand = mock(ItemStack.class);
        when(inventory.getItemInOffHand()).thenReturn(offHand);
        when(inventory.getItemInMainHand()).thenReturn(mainHand);
        when(manager.getInstrument(any())).thenReturn(null);
        studio.save(player, new Project(song(), null, 100, false));
        var error = assertThrows(IllegalArgumentException.class, () -> studio.record(player, 1));
        assertEquals("Hold an instrument in your hand or off-hand", error.getMessage());

        // A held instrument plays through the on-screen keyboard.
        when(manager.getInstrument(mainHand)).thenReturn("lute");
        studio.record(player, 1);
        assertTrue(studio.activity(player).recording());
    }

    @Test
    void projectSelectionReloadsAfterQuitAndResetWorksWithoutAnExistingProject() throws Exception {
        assertNull(studio.project(player));
        assertThrows(IllegalArgumentException.class, () -> studio.requireProject(player));
        assertSame(discs, studio.discs());
        assertEquals(4, studio.settings().tracks());
        studio.create(player, "Reset", true);
        Project project = studio.requireProject(player);
        studio.selectProject(player, project.song().id());
        studio.quit(player);
        assertEquals(project, studio.requireProject(player));
        Project foreign = new Project(new Song(UUID.randomUUID(), UUID.randomUUID(), "Other", "Foreign", List.of()), null, 100, false);
        assertThrows(IllegalArgumentException.class, () -> studio.save(player, foreign));
        studio.quit(player);
        studio.quit(player);
    }

    @Test
    void previewAndRecordingValidatePendingTakesSlotsAndAudibility() throws Exception {
        studio.create(player, "Empty", false);
        assertThrows(IllegalArgumentException.class, () -> studio.preview(player, false));
        assertThrows(IllegalArgumentException.class, () -> studio.preview(player, true));
        assertThrows(IllegalArgumentException.class, () -> studio.previewTrack(player, 1));
        assertThrows(IllegalArgumentException.class, () -> studio.record(player, 0));
        assertThrows(IllegalArgumentException.class, () -> studio.record(player, 5));
        Track muted = new Track(1, 10, .5f, true, track(1, "muted").notes());
        Project project = new Project(song(muted), track(2, "pending"), 100, false);
        studio.save(player, project);
        assertThrows(IllegalArgumentException.class, () -> studio.record(player, 1));
        assertThrows(IllegalArgumentException.class, () -> studio.previewTrack(player, 1));
        studio.preview(player, true);
        assertTrue(studio.activity(player).previewing());
        tick.run();
        verify(player).playSound(any(Location.class), eq("pending"), any(SoundCategory.class), anyFloat(), anyFloat());
        studio.stop(player);
        assertFalse(studio.activity(player).previewing());
        studio.edit(player, project.discard());
        ItemStack disc = mock(ItemStack.class);
        when(discs.musicDisc(disc)).thenReturn(true);
        when(disc.getAmount()).thenReturn(1);
        assertThrows(IllegalArgumentException.class, () -> studio.publishOnDisc(player, disc));
        studio.record(player, 1);
        tick.run(); tick.run();
        studio.capture(player, "lute", "replacement", 1, 1);
        studio.stop(player);
        assertTrue(studio.requireProject(player).pending().muted());
        assertEquals(.5f, studio.requireProject(player).pending().gain());
    }

    @Test
    void invalidSamplesStopCaptureAndDisconnectedPlayersDoNotReceivePlayback() throws Exception {
        studio.capture(player, "lute", "ignored", 1, 1);
        studio.create(player, "Song", false);
        studio.record(player, 1);
        tick.run(); tick.run();
        studio.capture(player, "lute", "valid", 1, 1);
        studio.capture(player, "lute", "invalid sound", 1, 1);
        assertFalse(studio.activity(player).recording());
        assertEquals(1, studio.requireProject(player).pending().notes().size());
        studio.edit(player, studio.requireProject(player).accept());
        studio.preview(player, false);
        when(server.getPlayer(owner)).thenReturn(null);
        tick.run();
        assertFalse(studio.activity(player).previewing());
        studio.record(player, 1);
        tick.run();
        studio.close();
        verify(player, never()).playSound(any(Location.class), eq("valid"), any(SoundCategory.class), anyFloat(), anyFloat());
    }

    @Test
    void failedCheckpointAndFinalSaveRetainTheTakeInMemoryForRetry() throws Exception {
        studio.create(player, "Song", false);
        CountDownLatch failed = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("musical-instruments-checkpoints")) {
                failed.countDown();
                throw new IOException("Checkpoint disk full");
            }
            return invocation.callRealMethod();
        }).when(store).save(any());
        studio.record(player, 1);
        tick.run(); tick.run();
        studio.capture(player, "lute", "saved", 1, 1);
        tick.run(); tick.run();
        assertTrue(failed.await(3, TimeUnit.SECONDS));
        // Await completion through the capture's future, then let the clock observe failure.
        var recordingsField = StudioService.class.getDeclaredField("recordings");
        recordingsField.setAccessible(true);
        Object capture = ((java.util.Map<?, ?>) recordingsField.get(studio)).get(owner);
        var checkpointField = capture.getClass().getDeclaredField("checkpoint");
        checkpointField.setAccessible(true);
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> ((CompletableFuture<?>) checkpointField.get(capture)).join());
        doThrow(new IOException("Final disk full")).when(store).save(any());
        tick.run(); tick.run();
        assertFalse(studio.activity(player).recording());
        assertEquals("saved", studio.requireProject(player).pending().notes().getFirst().sound());
        studio.quit(player);
        assertNotNull(studio.requireProject(player).pending());
        studio.close();
        verify(player, atLeastOnce()).sendMessage(contains("Could not read/write"));
        doCallRealMethod().when(store).save(any());
    }

    @Test
    void discsRequireAnOrdinarySingleItemAndCopyRequiresARecordedSource() throws Exception {
        ItemStack disc = mock(ItemStack.class);
        var meta = mock(org.bukkit.inventory.meta.ItemMeta.class);
        var data = mock(org.bukkit.persistence.PersistentDataContainer.class);
        when(inventory.getItemInMainHand()).thenReturn(disc);
        when(disc.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        assertFalse(studio.ordinaryDisc(disc));
        assertThrows(IllegalArgumentException.class, () -> studio.makeBlank(player));
        when(discs.musicDisc(disc)).thenReturn(true);
        when(discs.custom(disc)).thenReturn(true);
        assertFalse(studio.ordinaryDisc(disc));
        when(discs.custom(disc)).thenReturn(false);
        assertFalse(studio.ordinaryDisc(disc));
        when(data.isEmpty()).thenReturn(true);
        when(meta.hasCustomModelData()).thenReturn(true);
        assertFalse(studio.ordinaryDisc(disc));
        when(meta.hasCustomModelData()).thenReturn(false);
        assertTrue(studio.ordinaryDisc(disc));
        when(disc.getAmount()).thenReturn(2);
        assertThrows(IllegalArgumentException.class, () -> studio.makeBlank(player));
        when(disc.getAmount()).thenReturn(1);
        ItemStack blank = mock(ItemStack.class);
        when(discs.blankDisc()).thenReturn(blank);
        studio.makeBlank(player);
        verify(inventory).setItemInMainHand(blank);
        assertThrows(IllegalArgumentException.class, () -> studio.copy(player));
        when(discs.blank(disc)).thenReturn(true);
        when(disc.getAmount()).thenReturn(2);
        assertThrows(IllegalArgumentException.class, () -> studio.copy(player));
        when(disc.getAmount()).thenReturn(1);
        assertThrows(IllegalArgumentException.class, () -> studio.copy(player));
        when(player.hasPermission("instruments.copy")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> studio.copyFromInventory(player));
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{null, disc});
        studio.prepareBlankFromInventory(player);
        verify(inventory).setItem(1, blank);
        when(discs.musicDisc(disc)).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> studio.prepareBlankFromInventory(player));
    }

    @Test
    void jukeboxInsertionRejectsInvalidDiscsOccupiedBoxesAndChangedBlocks() throws Exception {
        ItemStack disc = mock(ItemStack.class);
        Song song = song(track(1, "note"));
        Block block = jukebox(song, disc);
        when(discs.songId(disc)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> studio.play(block, disc));
        when(discs.songId(disc)).thenReturn(song.id());
        Jukebox box = (Jukebox) block.getState();
        when(box.update(false, false)).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> studio.play(block, disc));
        when(box.update(false, false)).thenReturn(true);
        studio.play(block, disc);
        assertThrows(IllegalArgumentException.class, () -> studio.play(block, disc));
        when(server.getWorld(world.getUID())).thenReturn(null);
        tick.run();
        verify(player, never()).playSound(any(Location.class), eq("note"), any(SoundCategory.class), anyFloat(), anyFloat());
        studio.stop(block);
        when(block.getType()).thenReturn(Material.STONE);
        when(block.getState()).thenReturn(mock(org.bukkit.block.BlockState.class));
        studio.stop(block);
        when(block.getType()).thenReturn(Material.JUKEBOX);
        studio.stop(block);
    }

    @Test
    void newSessionsCanSelectStoredProjectsAndDeleteAnUnselectedProject() throws Exception {
        Project first = new Project(song(), null, 100, false);
        Project second = new Project(song(), null, 100, false);
        store.save(first); store.save(second);
        assertEquals(first, studio.project(player, first.song().id()));
        studio.deleteProject(player, first);
        assertEquals(second, studio.requireProject(player));
        studio.quit(player);
        doReturn(java.util.Optional.empty()).when(store).project(owner);
        studio.selectProject(player, second.song().id());
        assertEquals(second, studio.requireProject(player));
        Project third = new Project(song(), null, 100, false);
        studio.save(player, third);
        assertEquals(2, studio.listProjects(player).size()); // same title uses deterministic id ordering
    }

    @Test
    void copyingFromInventoryReadsImmutableEditionsAndEvictsOldCachedSongs() throws Exception {
        ItemStack blank = inventoryItem(Material.MUSIC_DISC_13, 1, true);
        ItemStack source = mock(ItemStack.class);
        ItemStack copied = mock(ItemStack.class);
        when(player.hasPermission("instruments.copy")).thenReturn(true);
        when(inventory.getItemInOffHand()).thenReturn(source);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{blank});
        when(discs.disc(any())).thenReturn(copied);
        Song first = null;
        for (int index = 0; index < 65; index++) {
            Song edition = song(track(1, "note"));
            if (index == 0) first = edition;
            store.publish(edition);
            when(discs.songId(source)).thenReturn(edition.id());
            studio.copyFromInventory(player);
        }
        assertNotNull(first);
        when(discs.songId(source)).thenReturn(first.id());
        studio.copyFromInventory(player);
        verify(store, times(2)).song(first.id());
        verify(inventory, times(66)).setItem(0, copied);
        ItemStack empty = inventoryItem(Material.AIR, 0, true);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{empty});
        assertThrows(IllegalArgumentException.class, () -> studio.copyFromInventory(player));
        ItemStack stack = inventoryItem(Material.MUSIC_DISC_13, 2, true);
        when(stack.clone()).thenReturn(mock(ItemStack.class));
        ItemStack air = inventoryItem(Material.AIR, 1, false);
        when(air.getType().isAir()).thenReturn(true);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{stack, air});
        studio.copyFromInventory(player);
        verify(inventory).setItem(1, copied);
    }

    private void configure(StudioSettings settings) {
        studio.close();
        studio = new StudioService(plugin, manager, store, settings, StudioStation.none(), discs);
        var callbacks = ArgumentCaptor.forClass(Runnable.class);
        verify(plugin.getServer().getScheduler(), times(2)).runTaskTimer(eq(plugin), callbacks.capture(), eq(1L), eq(1L));
        tick = callbacks.getAllValues().getLast();
    }

    private void clock(long value) throws Exception {
        var field = StudioService.class.getDeclaredField("clock"); field.setAccessible(true); field.setLong(studio, value);
    }

    @Test
    void simultaneousNoteFloodsAndSongLimitsBoundCapture() throws Exception {
        configure(new StudioSettings(4, 40, 50, 60, 8, 32, 2, 0, 2));
        studio.create(player, "Song", false); studio.record(player, 1);
        tick.run(); tick.run();
        for (int index = 0; index < 34; index++) studio.capture(player, "lute", "note", 1, 1);
        assertEquals(32, studio.activity(player).notes());
        clock(42); studio.capture(player, "lute", "late", 1, 1);
        assertEquals(32, studio.activity(player).notes());
        studio.stop(player); studio.edit(player, studio.requireProject(player).accept());
        studio.record(player, 2); tick.run(); tick.run();
        for (int index = 0; index < 29; index++) studio.capture(player, "lute", "note", 1, 1);
        assertFalse(studio.activity(player).recording());
        assertEquals(28, studio.requireProject(player).pending().notes().size());
    }

    @Test
    void sessionCapacityDoesNotInterruptAnExistingPreviewAndPlaybackEndsCleanly() throws Exception {
        configure(new StudioSettings(4, 40, 20, 60, 1, 32, 2, 0, 2));
        studio.save(player, new Project(song(track(1, "note")), null, 100, false));
        studio.preview(player, false);
        Player second = mock(Player.class);
        UUID id = UUID.randomUUID(); when(second.getUniqueId()).thenReturn(id);
        when(second.getName()).thenReturn("Second"); when(second.getInventory()).thenReturn(inventory);
        studio.create(second, "Other", false);
        assertThrows(IllegalArgumentException.class, () -> studio.record(second, 1));
        clock(-2); tick.run(); // a not-yet-due preview emits no samples
        verify(player, never()).playSound(any(Location.class), eq("note"), any(SoundCategory.class), anyFloat(), anyFloat());
        for (int index = 0; index < 12; index++) tick.run();
        assertFalse(studio.activity(player).previewing());
        studio.record(player, 1);
        studio.quit(player);
        assertFalse(studio.activity(player).recording());
    }

    @Test
    void jukeboxLifecycleHandlesUnloadedChunksReplacedBlocksAndFuturePlayback() throws Exception {
        ItemStack disc = mock(ItemStack.class);
        Block block = jukebox(song(track(1, "note")), disc);
        Jukebox box = (Jukebox) block.getState();
        studio.play(block, disc);
        studio.unload(UUID.randomUUID(), 0, 0); studio.unload(world.getUID(), 1, 0); studio.unload(world.getUID(), 0, 1);
        clock(-2); tick.run();
        verify(player, never()).playSound(any(Location.class), eq("note"), any(SoundCategory.class), anyFloat(), anyFloat());
        clock(0); when(block.getState()).thenReturn(mock(org.bukkit.block.BlockState.class)); tick.run();
        when(block.getState()).thenReturn(box); studio.play(block, disc);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false); tick.run();
    }

    @Test
    void archivedProjectCanBeDeletedWhenTheSelectedFileIsMissing() throws Exception {
        Project project = new Project(song(), null, 100, false);
        store.save(project);
        doReturn(java.util.Optional.empty()).when(store).project(owner);
        assertEquals(project, studio.project(player, project.song().id()));
        studio.deleteProject(player, project);
        assertNull(studio.project(player));
    }

    @Test
    void anEmptyCaptureDoesNotQueueCheckpointsAndInvalidDiscsCannotPublish() throws Exception {
        studio.create(player, "Empty", false); studio.record(player, 1);
        for (int index = 0; index < 6; index++) tick.run();
        verify(store, times(1)).save(any());
        studio.stop(player);
        assertThrows(IllegalArgumentException.class, () -> studio.publishOnDisc(player, mock(ItemStack.class)));
    }

    @Test
    void changedJukeboxStatesAreClearedAndAReplacedRecordStopsThePreviousSong() throws Exception {
        ItemStack disc = mock(ItemStack.class);
        Block block = jukebox(song(track(1, "note")), disc);
        Jukebox box = (Jukebox) block.getState();
        var replaced = mock(org.bukkit.block.BlockState.class);
        when(block.getState()).thenReturn(box, replaced, box);
        studio.play(block, disc);
        when(box.getRecord()).thenReturn(mock(ItemStack.class));
        tick.run();
        verify(player, never()).playSound(any(Location.class), eq("note"), any(SoundCategory.class), anyFloat(), anyFloat());
    }
}
