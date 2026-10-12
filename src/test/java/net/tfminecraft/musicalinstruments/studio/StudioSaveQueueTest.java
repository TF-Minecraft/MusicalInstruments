package net.tfminecraft.musicalinstruments.studio;

import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import net.tfminecraft.musicalinstruments.managers.InstrumentManager;
import org.bukkit.Server;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Regressions for slow storage, coalesced saves, and shutdown failures. */
class StudioSaveQueueTest {
    @TempDir Path directory;

    @Test
    void inFlightSnapshotRemainsImmutableAndNewEditsYieldToAnotherOwner() throws Exception {
        var worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        var fixture = new Fixture(directory, worker);
        Player first = fixture.player();
        Player second = fixture.player();
        Project original = fixture.project(first);
        Project other = fixture.project(second);
        fixture.studio.save(first, original);
        fixture.studio.save(second, other);
        var writes = java.util.Collections.synchronizedList(new ArrayList<Project>());
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Project snapshot = invocation.getArgument(0);
            writes.add(snapshot);
            if (writes.size() == 1) {
                started.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("In-flight save timed out");
            }
            return invocation.callRealMethod();
        }).when(fixture.store).save(any());
        Project firstEdit = new Project(original.song(), null, 80, false);
        Project finalEdit = new Project(original.song(), null, 180, true);
        try {
            fixture.studio.edit(first, firstEdit);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            fixture.studio.edit(second, other);
            for (int i = 0; i < 1000; i++) fixture.studio.edit(first, finalEdit);
            assertEquals(2, worker.getQueue().size()); // One per queued owner, besides the running write.
            release.countDown();
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(List.of(firstEdit, other, finalEdit), writes);
            assertEquals(finalEdit, new StudioStore(directory).project(first.getUniqueId()).orElseThrow());
            assertEquals(other, new StudioStore(directory).project(second.getUniqueId()).orElseThrow());
        } finally {
            release.countDown();
            fixture.studio.close();
        }
    }

    @Test
    void persistentStorageFailureDuringShutdownGetsOnlyOneFinalRetry() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        var fixture = new Fixture(directory, worker);
        Player player = fixture.player();
        fixture.studio.save(player, fixture.project(player));
        clearInvocations(fixture.store);
        doThrow(new IOException("Storage stays unavailable")).when(fixture.store).save(any());
        try {
            fixture.take(player, "retained_take");
            worker.submit(() -> {}).get(3, TimeUnit.SECONDS); // Failure completes before close.
            fixture.studio.close();
            verify(fixture.store, times(3)).save(any()); // Initial attempt, closing attempt, one closing retry.
            assertNotNull(fixture.studio.requireProject(player).pending());
            assertNull(new StudioStore(directory).project(player.getUniqueId()).orElseThrow().pending());
        } finally {
            fixture.studio.close();
        }
    }

    @Test
    void failureAfterTheShutdownDeadlineDoesNotStartAnotherWrite() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        var fixture = new Fixture(directory, worker);
        Player player = fixture.player();
        fixture.studio.save(player, fixture.project(player));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Late save timed out");
            throw new IOException("The write failed after the shutdown deadline");
        }).when(fixture.store).save(any());
        try {
            fixture.take(player, "take_to_retry_when_storage_recovers");
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var deadline = StudioService.class.getDeclaredField("shutdownDeadline");
            deadline.setAccessible(true);
            deadline.setLong(fixture.studio, System.nanoTime() - 1);
            release.countDown();
            worker.submit(() -> {}).get(3, TimeUnit.SECONDS);
            verify(fixture.store, times(2)).save(any()); // Original project and one failed take write.
            assertNotNull(fixture.studio.requireProject(player).pending());
        } finally {
            release.countDown();
            doCallRealMethod().when(fixture.store).save(any());
            fixture.studio.close();
        }
    }

    @Test
    void anotherMusiciansFinalTakeMustNotWaitBehindEveryObsoleteEdit() throws Exception {
        var worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        var fixture = new Fixture(directory, worker);
        Player first = fixture.player();
        Player second = fixture.player();
        Project project = fixture.project(first);
        fixture.studio.save(first, project);
        fixture.studio.save(second, fixture.project(second));
        var writes = java.util.Collections.synchronizedList(new ArrayList<UUID>());
        doAnswer(invocation -> {
            writes.add(((Project) invocation.getArgument(0)).song().owner());
            return invocation.callRealMethod();
        }).when(fixture.store).save(any());
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        worker.execute(() -> {
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        try {
            for (int i = 0; i < 100; i++) fixture.studio.edit(first,
                    new Project(project.song(), null, 40 + i, false));
            fixture.take(second, "second_musicians_new_take");
            assertNotNull(fixture.studio.requireProject(second).pending());
            release.countDown();
            worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
            int olderWrites = writes.indexOf(second.getUniqueId());
            System.out.println("READINESS_FAIRNESS obsolete_edits_before_other_take=" + olderWrites);
            assertTrue(olderWrites <= 2,
                    "Other musicians' final takes should not wait behind " + olderWrites + " obsolete edits");
        } finally {
            release.countDown();
            fixture.studio.close();
        }
    }

    @Test
    void transientFailureCompletingDuringShutdownMustNotLoseTheOnlyNewTake() throws Exception {
        ExecutorService writer = spy(Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon(true).name("readiness-shutdown-writer").factory()));
        var fixture = new Fixture(directory, writer);
        Player player = fixture.player();
        fixture.studio.save(player, fixture.project(player));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failedOnce = new AtomicBoolean();
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("readiness-shutdown-writer")
                    && failedOnce.compareAndSet(false, true)) {
                started.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Review latch timed out");
                throw new IOException("One transient final-save failure; subsequent saves would succeed");
            }
            return invocation.callRealMethod();
        }).when(fixture.store).save(any());
        // The failure is discovered only after close has checked which writes need retrying.
        doAnswer(invocation -> {
            release.countDown();
            return invocation.callRealMethod();
        }).when(writer).awaitTermination(eq(StudioService.SHUTDOWN_WAIT_SECONDS), eq(TimeUnit.SECONDS));
        try {
            fixture.take(player, "new_take_that_must_survive_restart");
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertNotNull(fixture.studio.requireProject(player).pending());
            fixture.studio.close();
            Project persisted = new StudioStore(directory).project(player.getUniqueId()).orElseThrow();
            System.out.println("READINESS_SHUTDOWN take_in_memory="
                    + (fixture.studio.requireProject(player).pending() != null)
                    + " take_on_disk=" + (persisted.pending() != null));
            assertNotNull(persisted.pending(),
                    "A one-off failure discovered during shutdown must be retried before discarding the only take");
        } finally {
            release.countDown();
            fixture.studio.close();
            writer.shutdown();
            assertTrue(writer.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void retryingAFailedEditDoesNotEraseTheCheckpointOfARecordingInProgress() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        var fixture = new Fixture(directory, worker);
        Player player = fixture.player();
        Project original = fixture.project(player);
        fixture.studio.save(player, original);
        Project edited = new Project(original.song(), null, 120, false);
        doThrow(new IOException("One transient failure before the recording")).when(fixture.store).save(edited);
        try {
            fixture.studio.edit(player, edited);
            worker.submit(() -> {}).get(3, TimeUnit.SECONDS);
            fixture.tick.run(); // Clock 1 observes the failure; the edit stays cached and unsaved.
            doCallRealMethod().when(fixture.store).save(any());
            fixture.studio.record(player, 2); // The take starts at clock 3.
            fixture.tick.run(); fixture.tick.run();
            fixture.studio.capture(player, "lute", "new_take", 1, 1);
            for (int clock = 4; clock < StudioService.RETRY_TICKS; clock++) fixture.tick.run();
            worker.submit(() -> {}).get(3, TimeUnit.SECONDS);
            Project checkpoint = new StudioStore(directory).project(player.getUniqueId()).orElseThrow();
            assertEquals("new_take", checkpoint.pending().notes().getFirst().sound());
            assertEquals(120, checkpoint.bpm()); // Checkpoints include the failed edit.
            fixture.tick.run(); // The periodic retry skips the recording musician.
            worker.submit(() -> {}).get(3, TimeUnit.SECONDS);
            assertTrue(fixture.studio.activity(player).recording());
            assertEquals(checkpoint, new StudioStore(directory).project(player.getUniqueId()).orElseThrow());
            fixture.studio.stop(player);
            worker.submit(() -> {}).get(3, TimeUnit.SECONDS);
            fixture.tick.run();
            assertEquals(fixture.studio.requireProject(player), new StudioStore(directory).project(player.getUniqueId()).orElseThrow());
        } finally {
            fixture.studio.close();
        }
    }

    private static final class Fixture {
        private final Server server = mock(Server.class);
        private final StudioStore store;
        private final StudioService studio;
        private final World world = mock(World.class);
        private Runnable tick;

        private Fixture(Path directory, ExecutorService worker) throws Exception {
            var plugin = mock(InstrumentPlugin.class);
            var scheduler = mock(BukkitScheduler.class);
            var manager = mock(InstrumentManager.class);
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getLogger()).thenReturn(Logger.getLogger("readiness-review"));
            when(server.getScheduler()).thenReturn(scheduler);
            when(manager.getInstrument(any())).thenReturn("lute");
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L)))
                    .thenAnswer(invocation -> { tick = invocation.getArgument(1); return mock(BukkitTask.class); });
            store = spy(new StudioStore(directory));
            studio = new StudioService(plugin, manager, store,
                    new StudioSettings(4, 3600, 1500, 6000, 32, 32, 2, 60, 200, 600, 20),
                    StudioStation.none(), mock(DiscItems.class), worker);
        }

        private Player player() {
            var player = mock(Player.class);
            UUID owner = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(owner);
            when(player.getName()).thenReturn("Musician");
            when(player.getInventory()).thenReturn(mock(PlayerInventory.class));
            when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
            when(server.getPlayer(owner)).thenReturn(player);
            return player;
        }

        private Project project(Player player) {
            return new Project(new Song(UUID.randomUUID(), player.getUniqueId(), "Musician", "Song",
                    List.of(new Track(1, 20, 1, false, List.of(new Note(0, "lute", "original_take", 1, 1))))),
                    null, 100, false);
        }

        private void take(Player player, String sound) throws Exception {
            studio.record(player, 1);
            tick.run(); tick.run();
            studio.capture(player, "lute", sound, 1, 1);
            studio.stop(player);
        }
    }

    @Test
    void repeatedEditsMustNotCreateAnUnboundedSaveBacklogBehindASlowDisk() throws Exception {
        var plugin = mock(InstrumentPlugin.class);
        var server = mock(Server.class);
        var scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("readiness-review"));
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
                .thenReturn(mock(BukkitTask.class));
        var player = mock(Player.class);
        UUID owner = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(owner);
        when(player.hasPermission("instruments.record")).thenReturn(true);
        when(server.getPlayer(owner)).thenReturn(player);
        var store = mock(StudioStore.class);
        var worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        var studio = new StudioService(plugin, mock(InstrumentManager.class), store,
                new StudioSettings(4, 3600, 1500, 6000, 32, 32, 60, 60, 200, 600, 20),
                StudioStation.none(), mock(DiscItems.class), worker);
        var song = new Song(UUID.randomUUID(), owner, "Musician", "Song",
                List.of(new Track(1, 20, 1, false, List.of(new Note(0, "lute", "note", 1, 1)))));
        studio.save(player, new Project(song, null, 100, false));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        worker.execute(() -> {
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        try {
            // Go through the public command handler using the normal recording permission.
            var commands = new StudioCommand(studio, mock(StudioMenu.class));
            var command = mock(Command.class);
            for (int i = 0; i < 1000; i++) commands.onCommand(player, command, "music",
                    new String[] { "bpm", String.valueOf(40 + i % 201) });
            int queued = worker.getQueue().size();
            System.out.println("READINESS_QUEUE edits=1000 queued=" + queued + " owners=1");
            assertTrue(queued <= 2,
                    "A slow write should retain a bounded number of snapshots per owner, but queued " + queued);
        } finally {
            release.countDown();
            studio.close();
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
