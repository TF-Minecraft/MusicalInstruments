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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    @Test
    void stoppingPreviewStopsAlreadySentSamples() throws Exception {
        studio.save(player, new Project(song(track(1, "long_sample")), null, 100, false));
        studio.preview(player, false);
        tick.run();
        verify(player).playSound(any(Location.class), eq("long_sample"), eq(SoundCategory.PLAYERS), anyFloat(), anyFloat());
        studio.stop(player); awaitSaves();
        verify(player).stopSound("long_sample", SoundCategory.PLAYERS);
        verify(player, never()).stopSound(anyString(), eq(SoundCategory.RECORDS));
    }

    @Test
    void slowCheckpointDoesNotBlockStopCaller() throws Exception {
        studio.create(player, "Song", false);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("musical-instruments-checkpoints")) {
                started.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Checkpoint timed out");
            }
            return invocation.callRealMethod();
        }).when(store).saveOwned(any(), any());
        studio.record(player, 1);
        tick.run();
        tick.run();
        studio.capture(player, "lute", "first", 4, 1);
        tick.run();
        tick.run();
        assertTrue(started.await(3, TimeUnit.SECONDS));
        CompletableFuture<Void> stopped = CompletableFuture.runAsync(() -> studio.stop(player));
        try {
            assertDoesNotThrow(() -> stopped.get(200, TimeUnit.MILLISECONDS),
                    "The same call runs on the server thread when Stop is clicked");
        } finally {
            release.countDown();
            stopped.get(3, TimeUnit.SECONDS);
            awaitSaves();
        }
    }

    @Test
    void editsDuringAPendingTakeSaveApplyAtOnceAndAreWrittenAfterIt() throws Exception {
        studio.create(player, "Song", false);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("musical-instruments-checkpoints")) {
                started.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Save timed out");
            }
            return invocation.callRealMethod();
        }).when(store).saveOwned(any(), any());
        studio.record(player, 1);
        tick.run(); tick.run();
        studio.capture(player, "lute", "final", 1, 1);
        studio.stop(player);
        assertTrue(started.await(3, TimeUnit.SECONDS));
        assertFalse(studio.quiescent(owner)); // An in-flight final take must drain before inheritance.
        Project kept = studio.requireProject(player).accept();
        try {
            studio.edit(player, kept); // Does not wait for the take to be written.
            assertEquals(kept, studio.requireProject(player));
            // Changes that read or remove project files wait until they are current.
            assertThrows(IllegalArgumentException.class, () -> studio.create(player, "Other", false));
            assertThrows(IllegalArgumentException.class, () -> studio.deleteProject(player, kept));
            tick.run(); // An incomplete save never waits on the server clock.
            studio.quit(player);
            when(server.getPlayer(owner)).thenReturn(null);
        } finally {
            release.countDown();
            awaitSaves();
        }
        assertEquals(kept, new StudioStore(directory).project(owner).orElseThrow());
        when(server.getPlayer(owner)).thenReturn(player);
        assertEquals(kept, studio.requireProject(player)); // Evicted after the write, then read back.
        verify(player, never()).sendMessage(startsWith("Could not save"));
    }

    @Test
    void completedSaveAllowsImmediateEditsBeforeTheNextClockTick() throws Exception {
        studio.create(player, "Song", false);
        studio.record(player, 1);
        tick.run(); tick.run();
        studio.capture(player, "lute", "note", 1, 1);
        studio.stop(player);
        awaitSaveFutures();
        studio.create(player, "Next", false);
        assertEquals("Next", new StudioStore(directory).project(owner).orElseThrow().song().title());
    }

    @Test
    void previewRestartAndRecordingStopClearPrivateAudioWithoutStoppingJukeboxes() throws Exception {
        Song song = song(track(1, "shared_sample"));
        ItemStack disc = mock(ItemStack.class);
        Block box = jukebox(song, disc);
        studio.save(player, new Project(song, null, 100, false));
        studio.play(box, disc);
        studio.preview(player, false);
        tick.run();
        verify(player).playSound(any(Location.class), eq("shared_sample"), eq(SoundCategory.RECORDS), anyFloat(), anyFloat());
        verify(player).playSound(any(Location.class), eq("shared_sample"), eq(SoundCategory.PLAYERS), anyFloat(), anyFloat());
        studio.preview(player, false);
        verify(player).stopSound("shared_sample", SoundCategory.PLAYERS);
        tick.run();
        studio.record(player, 2);
        verify(player, times(2)).stopSound("shared_sample", SoundCategory.PLAYERS);
        tick.run(); tick.run();
        studio.stop(player);
        verify(player, times(3)).stopSound("shared_sample", SoundCategory.PLAYERS);
        verify(player, never()).stopSound(anyString(), eq(SoundCategory.RECORDS));
    }

    @Test
    void aFailedSaveAfterDisconnectRetainsTheTakeForRetryOnRejoin() throws Exception {
        studio.create(player, "Song", false);
        studio.record(player, 1);
        tick.run(); tick.run();
        studio.capture(player, "lute", "retained", 1, 1);
        doThrow(new IOException("Disk full")).when(store).saveOwned(any(), any());
        studio.quit(player);
        when(server.getPlayer(owner)).thenReturn(null);
        awaitSaves();
        when(server.getPlayer(owner)).thenReturn(player);
        Project retained = studio.requireProject(player);
        assertEquals("retained", retained.pending().notes().getFirst().sound());
        doCallRealMethod().when(store).saveOwned(any(), any());
        studio.edit(player, retained.accept());
        awaitSaves();
        assertEquals("retained", new StudioStore(directory).project(owner).orElseThrow()
                .song().track(1).notes().getFirst().sound());
    }

    @Test
    void shutdownDiscardsDisconnectedPreviewSoundTracking() throws Exception {
        studio.save(player, new Project(song(track(1, "sample")), null, 100, false));
        studio.preview(player, false); tick.run();
        when(server.getPlayer(owner)).thenReturn(null);
        studio.close();
        assertEquals("sample", new StudioStore(directory).project(owner).orElseThrow()
                .song().track(1).notes().getFirst().sound());
        verify(player, never()).stopSound(anyString(), any(SoundCategory.class));
    }

    @Test
    void shutdownRetriesAFailedFinalSaveWhenStorageHasRecovered() throws Exception {
        studio.create(player, "Song", false);
        studio.record(player, 1); tick.run(); tick.run();
        studio.capture(player, "lute", "recovered", 1, 1);
        doThrow(new IOException("Temporary disk failure")).when(store).saveOwned(any(), any());
        studio.stop(player);
        awaitSaveFutures(); // The failure has not yet been observed by the musical clock.
        doCallRealMethod().when(store).saveOwned(any(), any());
        studio.close();
        assertEquals("recovered", new StudioStore(directory).project(owner).orElseThrow()
                .pending().notes().getFirst().sound());
        verify(player, never()).sendMessage(startsWith("Take saved"));
    }

    @Test
    void disconnectAndShutdownDoNotRewriteProjectsAlreadyOnDisk() throws Exception {
        studio.save(player, new Project(song(track(1, "cached")), null, 100, false));
        clearInvocations(store);
        studio.quit(player);
        studio.close();
        verify(store, never()).saveOwned(any(), any());
        verify(player, never()).sendMessage(startsWith("Take saved"));
    }

    @Test
    void disconnectEvictsACurrentProjectSoTheNextVisitReadsItsFiles() throws Exception {
        studio.save(player, new Project(song(track(1, "before")), null, 100, false));
        studio.quit(player);
        new StudioStore(directory).saveOwned(new StudioIdentity.Owner(owner, owner), new Project(song(track(1, "after")), null, 100, false));
        assertEquals("after", studio.requireProject(player).song().track(1).notes().getFirst().sound());
    }

    @Test
    void disconnectRetriesATakeWhoseSaveFailedWhileOnline() throws Exception {
        studio.create(player, "Song", false);
        studio.record(player, 1); tick.run(); tick.run();
        studio.capture(player, "lute", "retry", 1, 1);
        doThrow(new IOException("Temporary disk failure")).when(store).saveOwned(any(), any());
        studio.stop(player);
        awaitSaves();
        doCallRealMethod().when(store).saveOwned(any(), any());
        studio.quit(player);
        when(server.getPlayer(owner)).thenReturn(null);
        awaitSaves();
        assertEquals("retry", new StudioStore(directory).project(owner).orElseThrow()
                .pending().notes().getFirst().sound());
    }

    @Test
    void failedEditsAreKeptAndRetriedEveryMinuteUntilTheyAreWritten() throws Exception {
        Project project = new Project(song(track(1, "note")), null, 100, false);
        studio.save(player, project);
        Project renamed = new Project(new Song(project.song().id(), owner, "Musician", "Renamed", project.song().tracks()), null, 100, false);
        doThrow(new IOException("Disk full")).when(store).saveOwned(any(), any());
        studio.edit(player, renamed);
        awaitSaves();
        verify(player).sendMessage(startsWith("Could not save your project"));
        assertEquals(renamed, studio.requireProject(player));
        assertEquals(project, new StudioStore(directory).project(owner).orElseThrow());
        doCallRealMethod().when(store).saveOwned(any(), any());
        clock(StudioService.RETRY_TICKS - 2);
        tick.run();
        assertFalse(awaitSaveFutures()); // Not retried before the minute.
        tick.run();
        awaitSaves();
        assertEquals(renamed, new StudioStore(directory).project(owner).orElseThrow());
        clearInvocations(store);
        clock(2 * StudioService.RETRY_TICKS - 1);
        tick.run();
        verify(store, never()).saveOwned(any(), any()); // Nothing left to retry.
    }

    @Test
    void switchingProjectsOnlyRewritesTheSelectionUnlessAWriteFailed() throws Exception {
        Project first = new Project(song(track(1, "first")), null, 100, false);
        Project second = new Project(song(track(1, "second")), null, 100, false);
        studio.save(player, first);
        studio.save(player, second);
        clearInvocations(store);
        studio.selectProject(player, first.song().id());
        verify(store, never()).saveOwned(any(), any());
        assertEquals(first, new StudioStore(directory).project(owner).orElseThrow());
        Project renamed = new Project(new Song(first.song().id(), owner, "Musician", "Renamed", first.song().tracks()), null, 100, false);
        doThrow(new IOException("Disk full")).when(store).saveOwned(any(), any());
        studio.edit(player, renamed);
        awaitSaves();
        doCallRealMethod().when(store).saveOwned(any(), any());
        studio.selectProject(player, second.song().id());
        assertEquals(renamed, new StudioStore(directory).project(owner, first.song().id()));
        assertEquals(second, new StudioStore(directory).project(owner).orElseThrow());
        doReturn(second).when(store).project(owner, first.song().id()); // The file disappears after it was read.
        java.nio.file.Files.delete(directory.resolve("character-projects").resolve(owner.toString()).resolve(first.song().id() + ".yml"));
        assertThrows(IllegalArgumentException.class, () -> studio.selectProject(player, first.song().id()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shutdownWaitIsBoundedAndInterruptionPreservesQueuedSaves(boolean interrupted) throws Exception {
        studio.close();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon(true).name("musical-instruments-checkpoints").factory());
        var worker = spy(executor);
        if (interrupted) doThrow(new InterruptedException("Shutdown interrupted")).when(worker).awaitTermination(StudioService.SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        else doReturn(false).when(worker).awaitTermination(StudioService.SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        studio = new StudioService(plugin, manager, store, new StudioSettings(4, 40, 20, 60, 8, 32, 2, 0, 2, 0, 0),
                StudioStation.none(), discs, worker, TestIdentity.INSTANCE);
        var callbacks = ArgumentCaptor.forClass(Runnable.class);
        verify(server.getScheduler(), times(2)).runTaskTimer(eq(plugin), callbacks.capture(), eq(1L), eq(1L));
        tick = callbacks.getAllValues().getLast();
        studio.create(player, "Song", false);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("musical-instruments-checkpoints")) {
                started.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Save timed out");
            }
            return invocation.callRealMethod();
        }).when(store).saveOwned(any(), any());
        studio.record(player, 1); tick.run(); tick.run();
        studio.capture(player, "lute", "final", 1, 1);
        studio.stop(player);
        assertTrue(started.await(3, TimeUnit.SECONDS));
        try {
            studio.close();
            assertEquals(interrupted, Thread.currentThread().isInterrupted());
            verify(worker).shutdown();
            verify(worker).awaitTermination(StudioService.SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(studio.requireProject(player).pending());
        } finally {
            Thread.interrupted();
            release.countDown();
            awaitSaves();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
        assertEquals("final", new StudioStore(directory).project(owner).orElseThrow()
                .pending().notes().getFirst().sound());
    }

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
        studio = new StudioService(plugin, manager, store, new StudioSettings(4, 40, 20, 60, 8, 32, 2, 0, 2, 0, 0),
                StudioStation.none(), discs, TestIdentity.INSTANCE);
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskTimer(eq(plugin), callback.capture(), eq(1L), eq(1L));
        tick = callback.getValue();
    }

    @AfterEach
    void close() {
        studio.close();
    }

    private void awaitSaves() throws Exception {
        if (awaitSaveFutures()) tick.run();
    }

    private boolean awaitSaveFutures() throws Exception {
        var field = StudioService.class.getDeclaredField("pendingSaves");
        field.setAccessible(true);
        var pending = (java.util.Map<?, ?>) field.get(studio);
        if (pending.isEmpty()) return false;
        for (Object save : List.copyOf(pending.values())) {
            try {
                ((CompletableFuture<?>) save).get(3, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expectedFailure) {
                // The musical clock reports failures and retains the take for retry.
            }
        }
        return true;
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
        verify(player).playSound(any(Location.class), eq("backing"), eq(SoundCategory.PLAYERS), eq(4f), eq(1f));
        verify(player, never()).playSound(any(Location.class), eq("old_take"), any(SoundCategory.class), anyFloat(), anyFloat());
        studio.capture(player, "flute", "new_take", 2, 1.5f);
        studio.stop(player); awaitSaves();
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
        verify(player).playSound(any(Location.class), eq("lute"), eq(SoundCategory.PLAYERS), eq(4f), eq(1f));
        verify(player).playSound(any(Location.class), eq("flute"), eq(SoundCategory.PLAYERS), eq(4f), eq(1f));
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
        studio.stop(player); awaitSaves();
        studio.selectProject(player, first);
        assertEquals(first, studio.requireProject(player).song().id());
    }

    @Test
    void listeningToOneTrackDoesNotEmitTheRestOfTheMix() throws Exception {
        studio.save(player, new Project(song(track(1, "solo"), track(2, "other")), null, 100, false));
        studio.previewTrack(player, 1);
        tick.run();
        verify(player).playSound(any(Location.class), eq("solo"), eq(SoundCategory.PLAYERS), anyFloat(), anyFloat());
        verify(player, never()).playSound(any(Location.class), eq("other"), any(SoundCategory.class), anyFloat(), anyFloat());
    }

    @Test
    void checkpointsAndShutdownRecoverAnUnacceptedTake() throws Exception {
        studio.create(player, "Song", false);
        // Wait for the checkpoint to finish rather than polling its file: on Windows, reading a
        // file can make the writer's replacement of it fail.
        CountDownLatch checkpointed = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            checkpointed.countDown();
            return result;
        }).when(store).saveOwned(any(), any());
        studio.record(player, 1);
        tick.run();
        tick.run();
        studio.capture(player, "lute", "first", 4, 1);
        tick.run();
        tick.run();
        assertTrue(checkpointed.await(3, TimeUnit.SECONDS));
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
        studio.stop(player); awaitSaves();
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
        }).when(store).saveOwned(any(), any());
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
        awaitSaves();
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
        awaitSaves(); studio.requireIdle(player);
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
        awaitSaves(); studio.requireIdle(player);
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
        IOException failure = assertThrows(IOException.class, () -> studio.publish(player));
        studio.failure(player, failure);
        verify(player).sendMessage(contains("Your disc was preserved"));
        verify(inventory, never()).setItemInMainHand(any());
    }

    @Test
    void publicationCooldownLimitsHowOftenEditionsCanBeWritten() throws Exception {
        configure(new StudioSettings(4, 40, 20, 60, 8, 32, 2, 0, 2, 40, 0));
        studio.save(player, new Project(song(track(1, "note")), null, 100, false));
        ItemStack disc = mock(ItemStack.class);
        when(discs.musicDisc(disc)).thenReturn(true);
        when(disc.getAmount()).thenReturn(1);
        when(discs.disc(any(Song.class), eq(disc))).thenReturn(disc);
        studio.publishOnDisc(player, disc);
        var wait = assertThrows(IllegalArgumentException.class, () -> studio.publishOnDisc(player, disc));
        assertEquals("Wait 2 seconds before publishing again", wait.getMessage());
        for (int elapsed = 0; elapsed < 40; elapsed++) tick.run();
        studio.publishOnDisc(player, disc);
        verify(store, times(1)).publish(any()); // Unchanged music reuses the first edition.
    }

    @Test
    void theDailyEditionLimitKeepsTheDiscWhenReached() throws Exception {
        configure(new StudioSettings(4, 40, 20, 60, 8, 32, 2, 0, 2, 0, 1));
        Project project = new Project(song(track(1, "note")), null, 100, false);
        studio.save(player, project);
        ItemStack disc = mock(ItemStack.class);
        when(discs.musicDisc(disc)).thenReturn(true);
        when(disc.getAmount()).thenReturn(1);
        when(discs.disc(any(Song.class), eq(disc))).thenReturn(disc);
        studio.publishOnDisc(player, disc);
        studio.save(player, new Project(project.song().replace(track(2, "other")), null, 100, false));
        var limit = assertThrows(IllegalArgumentException.class, () -> studio.publishOnDisc(player, disc));
        assertTrue(limit.getMessage().startsWith("You can publish 1 new song editions a day"));
        verify(discs, times(1)).disc(any(Song.class), eq(disc));
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
    void jukeboxRecordIsCheckedOnceASecondAndARemovedJukeboxStopsAtOnce() throws Exception {
        Song song = song(new Track(1, 60, 1, false, List.of(new Note(0, "lute", "first", 4, 1),
                new Note(5, "lute", "second", 4, 1), new Note(25, "lute", "third", 4, 1))));
        ItemStack disc = mock(ItemStack.class);
        Block block = jukebox(song, disc);
        studio.play(block, disc);
        clearInvocations(block);
        for (int elapsed = 0; elapsed <= 5; elapsed++) tick.run();
        verify(block, times(1)).getState();
        verify(player).playSound(any(Location.class), eq("second"), eq(SoundCategory.RECORDS), anyFloat(), anyFloat());
        when(block.getType()).thenReturn(Material.AIR);
        for (int elapsed = 6; elapsed <= 25; elapsed++) tick.run();
        verify(player, never()).playSound(any(Location.class), eq("third"), any(SoundCategory.class), anyFloat(), anyFloat());
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
        ProjectSummary inactive = studio.listProjects(player).stream().filter(p -> !p.id().equals(active.song().id())).findFirst().orElseThrow();
        studio.deleteProject(player, studio.project(player, inactive.id()));
        studio.create(player, "Replacement", false);
        assertEquals(36, studio.listProjects(player).size());
        // Without an active project, a reset would add a 37th project.
        studio.quit(player);
        java.nio.file.Files.writeString(directory.resolve("character-projects").resolve(owner.toString()).resolve(StudioStore.SELECTED), "garbage");
        assertNull(studio.project(player));
        assertThrows(IllegalArgumentException.class, () -> studio.create(player, "Untitled", true));
        assertEquals(36, studio.listProjects(player).size());
    }

    @Test
    void deletingActiveProjectDoesNotResurrectItAndKeepsOtherProjectsAndEditions() throws Exception {
        studio.create(player, "Other", false);
        Project other = studio.requireProject(player);
        Project active = new Project(song(track(1, "note")), null, 100, false);
        studio.save(player, active);
        Song edition = store.publishEdition(active.song(), 0);
        studio.deleteProject(player, active);
        assertNull(studio.project(player));
        studio.quit(player); awaitSaves();
        assertTrue(store.project(owner).isEmpty());
        assertEquals(List.of(ProjectSummary.of(other)), store.summaries(owner));
        assertEquals(edition, store.song(edition.id()));
    }

    @Test
    void deletionRejectsStaleConfirmationForeignOwnerAndActiveRecording() throws Exception {
        Project before = new Project(song(track(1, "note")), null, 100, false);
        studio.save(player, before);
        Song song = before.song();
        Project changed = new Project(new Song(song.id(), owner, song.author(), "Renamed", song.tracks()), null, 100, false);
        studio.edit(player, changed); awaitSaves();
        assertThrows(IllegalArgumentException.class, () -> studio.deleteProject(player, before));
        Project foreign = new Project(new Song(UUID.randomUUID(), UUID.randomUUID(), "Other", "Other", List.of()), null, 100, false);
        assertThrows(IllegalArgumentException.class, () -> studio.deleteProject(player, foreign));
        studio.record(player, 2);
        assertThrows(IllegalArgumentException.class, () -> studio.deleteProject(player, changed));
        assertEquals(changed, store.project(owner).orElseThrow());
    }

    @Test
    void deletionMustRejectAReplacementTakeOfTheSameDuration() throws Exception {
        Project original = new Project(song(new Track(1, 20, 1, false,
                List.of(new Note(0, "lute", "original_take", 1, 1)))), null, 100, false);
        studio.save(player, original);
        Project confirmation = studio.project(player, original.song().id());
        studio.record(player, 1);
        tick.run(); tick.run();
        studio.capture(player, "lute", "replacement_take", 1, 1);
        for (int index = 0; index < 19; index++) tick.run();
        studio.stop(player);
        Project kept = studio.requireProject(player).accept();
        studio.edit(player, kept);
        Project changed = new Project(kept.song().replace(kept.song().track(1).mix(.5f, true)),
                null, 120, true);
        studio.edit(player, changed); awaitSaves();
        assertEquals(ProjectSummary.of(confirmation), ProjectSummary.of(changed));
        assertThrows(IllegalArgumentException.class, () -> studio.deleteProject(player, confirmation));
        assertEquals(changed, store.project(owner, original.song().id()));
        assertEquals(changed, studio.requireProject(player));
    }

    @Test
    void deletionRejectsEditsToAnInactiveProjectAndLoadsOnlyThatProject() throws Exception {
        Project original = new Project(song(track(1, "original")), null, 100, false);
        store.saveOwned(new StudioIdentity.Owner(owner, owner), original);
        Project active = new Project(song(track(1, "other_song")), null, 100, false);
        studio.save(player, active);
        Project confirmation = studio.project(player, original.song().id());
        Project changed = new Project(original.song().replace(track(1, "replacement")), null, 100, false);
        store.saveOwned(new StudioIdentity.Owner(owner, owner), changed);
        store.select(owner, active.song().id());
        assertEquals(ProjectSummary.of(original), ProjectSummary.of(changed));
        assertThrows(IllegalArgumentException.class, () -> studio.deleteProject(player, confirmation));
        verify(store, times(2)).project(owner, original.song().id());
        verify(store, never()).project(owner, active.song().id());
        verify(store, never()).summaries(owner);
        assertEquals(changed, store.project(owner, original.song().id()));
        assertEquals(active, studio.requireProject(player));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sound", "instrument", "timing", "volume", "pitch", "gain", "mute", "bpm", "metronome", "pending"})
    void deletionRejectsContentChangesThatLeaveTheLibrarySummaryUnchanged(String change) throws Exception {
        Track track = track(1, "original");
        Track pending = track(1, "pending");
        Project original = new Project(song(track), pending, 100, false);
        studio.save(player, original);
        Project confirmation = studio.project(player, original.song().id());
        Note note = switch (change) {
            case "sound" -> new Note(0, "lute", "replacement", 4, 1);
            case "instrument" -> new Note(0, "flute", "original", 4, 1);
            case "timing" -> new Note(1, "lute", "original", 4, 1);
            case "volume" -> new Note(0, "lute", "original", 2, 1);
            case "pitch" -> new Note(0, "lute", "original", 4, .5f);
            default -> track.notes().getFirst();
        };
        Track replacement = new Track(1, track.lengthTicks(), change.equals("gain") ? .5f : 1,
                change.equals("mute"), List.of(note));
        Project changed = new Project(original.song().replace(replacement),
                change.equals("pending") ? track(1, "new_pending") : pending,
                change.equals("bpm") ? 120 : 100, change.equals("metronome"));
        studio.edit(player, changed); awaitSaves();
        assertEquals(ProjectSummary.of(confirmation), ProjectSummary.of(changed));
        assertThrows(IllegalArgumentException.class, () -> studio.deleteProject(player, confirmation));
        assertEquals(changed, store.project(owner, original.song().id()));
        assertEquals(changed, studio.requireProject(player));
    }

    @Test
    void deletionChecksTheNewerCachedContentAfterItsWriteFails() throws Exception {
        Project original = new Project(song(track(1, "original")), null, 100, false);
        studio.save(player, original);
        Project confirmation = studio.project(player, original.song().id());
        Project changed = new Project(original.song().replace(track(1, "replacement")), null, 100, false);
        doThrow(new IOException("Write failed")).when(store).saveOwned(any(), eq(changed));
        studio.edit(player, changed); awaitSaves();
        assertEquals(original, store.project(owner, original.song().id()));
        assertFalse(studio.quiescent(owner)); // A completed failed write still holds the unsaved property.
        assertEquals(changed, studio.project(player, original.song().id()));
        assertThrows(IllegalArgumentException.class, () -> studio.deleteProject(player, confirmation));
        assertEquals(original, store.project(owner, original.song().id()));
        assertEquals(changed, studio.requireProject(player));
        doCallRealMethod().when(store).saveOwned(any(), eq(changed));
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
        awaitSaves();
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
        studio.quit(player); awaitSaves();
        assertEquals(project, studio.requireProject(player));
        Project foreign = new Project(new Song(UUID.randomUUID(), UUID.randomUUID(), "Other", "Foreign", List.of()), null, 100, false);
        assertThrows(IllegalArgumentException.class, () -> studio.save(player, foreign));
        studio.quit(player); awaitSaves();
        studio.quit(player); awaitSaves();
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
        studio.stop(player); awaitSaves();
        assertFalse(studio.activity(player).previewing());
        studio.edit(player, project.discard());
        ItemStack disc = mock(ItemStack.class);
        when(discs.musicDisc(disc)).thenReturn(true);
        when(disc.getAmount()).thenReturn(1);
        assertThrows(IllegalArgumentException.class, () -> studio.publishOnDisc(player, disc));
        studio.record(player, 1);
        tick.run(); tick.run();
        studio.capture(player, "lute", "replacement", 1, 1);
        studio.stop(player); awaitSaves();
        assertTrue(studio.requireProject(player).pending().muted());
        assertEquals(.5f, studio.requireProject(player).pending().gain());
    }

    @Test
    void takesOnMutedOrSilentTracksStayAudibleWhileReviewing() throws Exception {
        Track muted = new Track(1, 10, .5f, true, track(1, "muted").notes());
        Track silent = new Track(2, 10, 0, false, track(2, "silent").notes());
        studio.save(player, new Project(song(muted, silent), null, 100, false));
        assertThrows(IllegalArgumentException.class, () -> studio.preview(player, false));
        assertThrows(IllegalArgumentException.class, () -> studio.previewTrack(player, 2));
        assertFalse(studio.activity(player).previewing());
        for (int slot : new int[] {1, 2}) {
            Project project = studio.requireProject(player);
            studio.save(player, new Project(project.song(), track(slot, "take" + slot), 100, false));
            studio.preview(player, true);
            tick.run();
            verify(player).playSound(any(Location.class), eq("take" + slot), eq(SoundCategory.PLAYERS), eq(slot == 1 ? 2f : 4f), anyFloat());
            verify(player, never()).playSound(any(Location.class), eq("muted"), any(SoundCategory.class), anyFloat(), anyFloat());
            verify(player, never()).playSound(any(Location.class), eq("silent"), any(SoundCategory.class), anyFloat(), anyFloat());
            studio.stop(player);
            studio.save(player, studio.requireProject(player).discard());
        }
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
        awaitSaves();
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
        }).when(store).saveOwned(any(), any());
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
        doThrow(new IOException("Final disk full")).when(store).saveOwned(any(), any());
        tick.run(); tick.run();
        assertFalse(studio.activity(player).recording());
        assertEquals("saved", studio.requireProject(player).pending().notes().getFirst().sound());
        studio.quit(player); awaitSaves();
        assertNotNull(studio.requireProject(player).pending());
        studio.close();
        verify(player, atLeastOnce()).sendMessage(startsWith("Could not save your project"));
        doCallRealMethod().when(store).saveOwned(any(), any());
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
        store.saveOwned(new StudioIdentity.Owner(owner, owner), first); store.saveOwned(new StudioIdentity.Owner(owner, owner), second);
        assertEquals(first, studio.project(player, first.song().id()));
        studio.deleteProject(player, first);
        assertEquals(second, studio.requireProject(player));
        studio.quit(player);
        when(server.getPlayer(owner)).thenReturn(null);
        awaitSaves();
        when(server.getPlayer(owner)).thenReturn(player);
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
        studio = new StudioService(plugin, manager, store, settings, StudioStation.none(), discs, TestIdentity.INSTANCE);
        var callbacks = ArgumentCaptor.forClass(Runnable.class);
        verify(plugin.getServer().getScheduler(), times(2)).runTaskTimer(eq(plugin), callbacks.capture(), eq(1L), eq(1L));
        tick = callbacks.getAllValues().getLast();
    }

    private void clock(long value) throws Exception {
        var field = StudioService.class.getDeclaredField("clock"); field.setAccessible(true); field.setLong(studio, value);
    }

    @Test
    void simultaneousNoteFloodsAndSongLimitsBoundCapture() throws Exception {
        configure(new StudioSettings(4, 40, 50, 60, 8, 32, 2, 0, 2, 0, 0));
        studio.create(player, "Song", false); studio.record(player, 1);
        tick.run(); tick.run();
        for (int index = 0; index < 34; index++) studio.capture(player, "lute", "note", 1, 1);
        assertEquals(32, studio.activity(player).notes());
        clock(42); studio.capture(player, "lute", "late", 1, 1);
        assertEquals(32, studio.activity(player).notes());
        studio.stop(player); awaitSaves(); studio.edit(player, studio.requireProject(player).accept());
        studio.record(player, 2); tick.run(); tick.run();
        for (int index = 0; index < 29; index++) studio.capture(player, "lute", "note", 1, 1);
        assertFalse(studio.activity(player).recording());
        assertEquals(28, studio.requireProject(player).pending().notes().size());
    }

    @Test
    void sessionCapacityDoesNotInterruptAnExistingPreviewAndPlaybackEndsCleanly() throws Exception {
        configure(new StudioSettings(4, 40, 20, 60, 1, 32, 2, 0, 2, 0, 0));
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
        studio.quit(player); awaitSaves();
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
        store.saveOwned(new StudioIdentity.Owner(owner, owner), project);
        doReturn(java.util.Optional.empty()).when(store).project(owner);
        assertEquals(project, studio.project(player, project.song().id()));
        assertThrows(IllegalArgumentException.class, () -> studio.project(player, UUID.randomUUID()));
        studio.deleteProject(player, project);
        assertNull(studio.project(player));
    }

    @Test
    void anEmptyCaptureDoesNotQueueCheckpointsAndInvalidDiscsCannotPublish() throws Exception {
        studio.create(player, "Empty", false); studio.record(player, 1);
        for (int index = 0; index < 6; index++) tick.run();
        verify(store, times(1)).saveOwned(any(), any());
        studio.stop(player); awaitSaves();
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
    @Test void outgoingCleanupRetriesFailedEditsEvenWithoutACurrentSession() throws Exception {
        studio.save(player, new Project(song(track(1, "original")), null, 100, false));
        Project changed = new Project(studio.requireProject(player).song(), null, 125, true);
        doThrow(new IOException("temporary disk failure")).when(store).saveOwned(any(), any());
        studio.edit(player, changed); awaitSaves();
        assertFalse(studio.quiescent(owner));
        doCallRealMethod().when(store).saveOwned(any(), any());
        TestIdentity.INSTANCE.sessions.put(owner, null);
        try {
            studio.quit(player); // Join/quit without a current identity never selects an account fallback.
            studio.end(player, new StudioIdentity.Token(new StudioIdentity.Owner(owner, owner), 1));
            awaitSaves();
            assertEquals(125, store.project(owner).orElseThrow().bpm());
            studio.end(player, new StudioIdentity.Token(new StudioIdentity.Owner(owner, owner), 1));
            assertThrows(IllegalArgumentException.class, () -> studio.listProjects(player));
        } finally { TestIdentity.INSTANCE.sessions.remove(owner); }
    }

    @Test void staleCaptureAndPreviewCannotFollowAnotherCharacterOnTheSameAccount() throws Exception {
        studio.save(player, new Project(song(track(1, "original")), null, 100, false));
        studio.record(player, 1); tick.run(); tick.run(); studio.capture(player, "lute", "outgoing", 1, 1);
        assertFalse(studio.quiescent(owner)); assertTrue(studio.quiescent(UUID.randomUUID()));
        TestIdentity.INSTANCE.sessions.put(owner, new StudioIdentity.Token(new StudioIdentity.Owner(owner, UUID.randomUUID()), 2));
        try {
            studio.capture(player, "lute", "incoming_must_be_ignored", 1, 1);
            tick.run(); awaitSaves();
            assertEquals(List.of("outgoing"), store.project(owner).orElseThrow().pending().notes().stream().map(Note::sound).toList());
        } finally { TestIdentity.INSTANCE.sessions.remove(owner); }
        studio.edit(player, studio.requireProject(player).discard()); awaitSaves();
        studio.preview(player, false); tick.run();
        TestIdentity.INSTANCE.sessions.put(owner, new StudioIdentity.Token(new StudioIdentity.Owner(owner, UUID.randomUUID()), 3));
        try { tick.run(); assertFalse(studio.activity(player).previewing()); }
        finally { TestIdentity.INSTANCE.sessions.remove(owner); }
    }

    @Test void aFailedEditIsNotRewrittenOverAnActiveCaptureAtTheRetryTick() throws Exception {
        studio.save(player, new Project(song(track(1, "original")), null, 100, false));
        doThrow(new IOException("disk failure")).when(store).saveOwned(any(), any());
        studio.edit(player, new Project(studio.requireProject(player).song(), null, 125, false)); awaitSaves();
        clock(StudioService.RETRY_TICKS - 1); studio.record(player, 1); clearInvocations(store);
        tick.run(); verify(store, never()).saveOwned(any(), any());
        studio.end(player, new StudioIdentity.Token(new StudioIdentity.Owner(owner, UUID.randomUUID()), 99));
        doCallRealMethod().when(store).saveOwned(any(), any()); studio.stop(player); awaitSaves();
    }

    @Test void unknownDiskAuthorityAndCorruptOwnershipJournalsFailClosed() throws Exception {
        doThrow(new IOException("owner file inaccessible")).when(store).requireAuthority(any(), any(), any());
        assertThrows(IllegalArgumentException.class, () -> studio.create(player, "Unavailable", false));
        doCallRealMethod().when(store).requireAuthority(any(), any(), any());
        var folder = directory.resolve("ownership/estates"); java.nio.file.Files.createDirectories(folder);
        var corrupt = folder.resolve("broken.xml"); java.nio.file.Files.writeString(corrupt, "broken XML");
        assertThrows(IllegalArgumentException.class, () -> studio.token(player));
        java.nio.file.Files.delete(corrupt);
        studio.create(player, "Recovered", false);
    }

    @Test void runtimeMigrationReportsDurableWriteFailuresWithoutChangingTheLegacySource() throws Exception {
        Project project = new Project(song(track(1, "original")), null, 100, false); studio.save(player, project);
        var source = directory.resolve("projects").resolve(owner.toString()).resolve(project.song().id() + ".yml");
        java.nio.file.Files.createDirectories(source.getParent());
        java.nio.file.Files.copy(store.projectFile(owner, project.song().id()), source);
        java.nio.file.Files.createDirectories(directory.resolve("ownership"));
        var blocked = directory.resolve("ownership/migration"); java.nio.file.Files.writeString(blocked, "block directory creation");
        studio.startOwnership(mock(StudioMenu.class));
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(server.getScheduler()).runTaskTimer(eq(plugin), callback.capture(), eq(1L), eq(72_000L));
        assertDoesNotThrow(() -> callback.getValue().run());
        assertTrue(java.nio.file.Files.exists(source));
        java.nio.file.Files.delete(blocked);
    }

    @Test void staleProviderTokensAreRejectedBeforePropertyOperations() throws Exception {
        var identity = mock(StudioIdentity.class);
        var token = new StudioIdentity.Token(new StudioIdentity.Owner(owner, owner), 1);
        when(identity.current(player)).thenReturn(java.util.Optional.of(token));
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        var closedStudio = new StudioService(plugin, manager, store, new StudioSettings(4, 40, 20, 60, 8, 32, 2, 0, 2, 0, 0), StudioStation.none(), discs, worker, identity);
        try {
            assertThrows(IllegalArgumentException.class, () -> closedStudio.token(player));
            when(identity.current(token)).thenReturn(true);
            assertEquals(token, closedStudio.token(player));
        }
        finally { closedStudio.close(); }
        var optionalStudio = new StudioService(plugin, manager, store, new StudioSettings(4, 40, 20, 60, 8, 32, 2, 0, 2, 0, 0), StudioStation.none(), discs, java.util.concurrent.Executors.newSingleThreadExecutor());
        optionalStudio.close();
    }

}
