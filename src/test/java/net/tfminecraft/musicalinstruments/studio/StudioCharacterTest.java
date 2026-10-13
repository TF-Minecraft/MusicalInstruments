package net.tfminecraft.musicalinstruments.studio;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bukkit.Server;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import net.tfminecraft.musicalinstruments.managers.InstrumentManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StudioCharacterTest {
    @TempDir Path root;
    @Test void sameAccountCharactersHaveSeparateLibrariesAndOutgoingTakeFinishesAfterSessionBecomesNoncurrent() throws Exception {
        var plugin = mock(InstrumentPlugin.class); var server = mock(Server.class); var scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server); when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(server.getScheduler()).thenReturn(scheduler);
        var tick = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L))).thenAnswer(call -> { tick.set(call.getArgument(1)); return mock(BukkitTask.class); });
        var player = mock(Player.class); var inventory = mock(PlayerInventory.class); var world = mock(World.class);
        UUID account = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(account); when(player.getName()).thenReturn("Account name");
        when(player.getInventory()).thenReturn(inventory); when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(server.getPlayer(account)).thenReturn(player);
        var instruments = mock(InstrumentManager.class); when(instruments.getInstrument(any())).thenReturn("lute");
        var identity = new TestIdentity();
        var first = new StudioIdentity.Token(new StudioIdentity.Owner(account, UUID.randomUUID()), 1);
        var second = new StudioIdentity.Token(new StudioIdentity.Owner(account, UUID.randomUUID()), 2);
        identity.sessions.put(account, first);
        var store = new StudioStore(root); var writer = Executors.newSingleThreadExecutor();
        var studio = new StudioService(plugin, instruments, store, new StudioSettings(4, 40, 20, 60, 8, 32, 2, 0, 2, 0, 0), StudioStation.none(), mock(DiscItems.class), writer, identity);
        try {
            studio.create(player, "First", false); UUID firstId = studio.requireProject(player).song().id();
            studio.record(player, 1); tick.get().run(); tick.get().run(); studio.capture(player, "lute", "original_session_sample", 1, 1);
            assertFalse(studio.quiescent(first.owner().character()));
            identity.sessions.put(account, second); // End event deliberately sees a noncurrent outgoing session.
            studio.end(player, first);
            writer.submit(() -> {}).get(3, TimeUnit.SECONDS); tick.get().run();
            assertTrue(studio.quiescent(first.owner().character()));
            assertFalse(studio.current(first, player)); assertTrue(studio.current(second, player));
            assertFalse(studio.current(null, player));
            var foreign = mock(Player.class); when(foreign.getUniqueId()).thenReturn(UUID.randomUUID()); assertFalse(studio.current(second, foreign));
            assertTrue(studio.listProjects(player).isEmpty());
            studio.create(player, "Second", false);
            UUID secondId = studio.requireProject(player).song().id(); assertNotEquals(firstId, secondId);
            assertEquals("First", store.project(first.owner().character(), firstId).song().title());
            assertEquals("original_session_sample", store.project(first.owner().character(), firstId).pending().notes().getFirst().sound());
            assertEquals("Second", store.project(second.owner().character(), secondId).song().title());
            // Estate delivery evicts the recipient cache, including an already selected project.
            var changed = store.project(second.owner().character(), secondId);
            store.saveOwned(second.owner(), new Project(new Song(changed.song().id(), account, "Account name", "Delivered update", List.of()), null, 100, false));
            studio.transferred(second.owner().character());
            assertEquals("Delivered update", studio.requireProject(player).song().title());
            identity.sessions.put(account, new StudioIdentity.Token(first.owner(), 3)); assertEquals(firstId, studio.requireProject(player).song().id());
            assertEquals(account, studio.requireProject(player).song().owner());
            identity.sessions.put(account, null); assertThrows(IllegalArgumentException.class, () -> studio.listProjects(player));
        } finally { studio.close(); }
    }

    @Test void inheritedOverflowIsRetainedAndCannotCreateAnotherProject() throws Exception {
        var store = new StudioStore(root); var from = new StudioIdentity.Owner(UUID.randomUUID(), UUID.randomUUID());
        var to = new StudioIdentity.Owner(UUID.randomUUID(), UUID.randomUUID());
        for (int i = 0; i < 36; i++) store.saveOwned(to, new Project(new Song(UUID.randomUUID(), to.account(), "Heir", "Existing " + i, List.of()), null, 100, false));
        for (int i = 0; i < 2; i++) store.saveOwned(from, new Project(new Song(UUID.randomUUID(), from.account(), "Author", "Inherited " + i, List.of()), null, 100, false));
        UUID death = UUID.randomUUID();
        new StudioOwnership(store).transfer(death, StudioOwnershipTest.decision(death, "one", from, to), from, to, () -> true);
        var plugin = mock(InstrumentPlugin.class); var server = mock(Server.class); var scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server); when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(any(), any(Runnable.class), anyLong(), anyLong())).thenReturn(mock(BukkitTask.class));
        var player = mock(Player.class); when(player.getUniqueId()).thenReturn(to.account());
        var identity = new TestIdentity(); identity.sessions.put(to.account(), new StudioIdentity.Token(to, 1));
        var studio = new StudioService(plugin, mock(InstrumentManager.class), store, new StudioSettings(4, 40, 20, 60, 8, 32, 2, 0, 2, 0, 0), StudioStation.none(), mock(DiscItems.class), identity);
        try { assertEquals(38, studio.listProjects(player).size()); assertThrows(IllegalArgumentException.class, () -> studio.create(player, "Extra", false)); }
        finally { studio.close(); }
    }
}
