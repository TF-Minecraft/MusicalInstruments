package net.tfminecraft.musicalinstruments.studio;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import net.tfminecraft.musicalinstruments.managers.InstrumentManager;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StudioCompletionTest {
    @TempDir Path directory;

    @Test void completionUsesOwnerSnapshotsAndSavedTracksWithoutKeystrokeIo() throws Exception {
        var plugin = mock(InstrumentPlugin.class);
        var server = mock(Server.class);
        var scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(any(), any(Runnable.class), anyLong(), anyLong())).thenReturn(mock(BukkitTask.class));
        var tasks = new ArrayList<Runnable>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            tasks.add(call.getArgument(1)); return mock(BukkitTask.class);
        });
        var player = mock(Player.class);
        UUID account = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(account);
        when(player.getName()).thenReturn("Artist");
        when(player.hasPermission("instruments.record")).thenReturn(true);
        var identity = new TestIdentity(); identity.defaultHeirs = false;
        var owner = new StudioIdentity.Owner(account, UUID.randomUUID());
        var foreign = new StudioIdentity.Owner(UUID.randomUUID(), UUID.randomUUID());
        identity.sessions.put(account, new StudioIdentity.Token(owner, 1));
        var store = spy(new StudioStore(directory));
        var track = new Track(3, 20, 1, false, List.of(new Note(0, "lute", "sample", 1, 1)));
        var first = new Project(new Song(UUID.randomUUID(), account, "Author", "First", List.of(track)), null, 100, false);
        var other = new Project(new Song(UUID.randomUUID(), foreign.account(), "Other", "Foreign", List.of()), null, 100, false);
        store.saveOwned(owner, first); store.saveOwned(foreign, other);
        var studio = new StudioService(plugin, mock(InstrumentManager.class), store,
                new StudioSettings(4, 40, 20, 60, 8, 32, 2, 0, 20, 0, 0), StudioStation.none(), mock(DiscItems.class), identity);
        var commands = new StudioCommand(studio, mock(StudioMenu.class));
        try {
            clearInvocations(store);
            for (int i = 0; i < 4; i++) assertTrue(commands.onTabComplete(player, null, "music", new String[]{"open", ""}).isEmpty());
            verifyNoInteractions(store);
            assertEquals(1, tasks.size());
            tasks.removeFirst().run();
            clearInvocations(store);
            for (String alias : List.of("music", "recording")) {
                assertEquals(List.of(first.song().id().toString()), commands.onTabComplete(player, null, alias, new String[]{"open", ""}));
                assertEquals(List.of(first.song().id().toString()), commands.onTabComplete(player, null, alias,
                        new String[]{"OPEN", first.song().id().toString().substring(0, 8).toUpperCase()}));
                assertTrue(commands.onTabComplete(player, null, alias, new String[]{"open", "missing"}).isEmpty());
                for (String action : List.of("volume", "mute", "remove")) {
                    assertEquals(List.of("3"), commands.onTabComplete(player, null, alias, new String[]{action, ""}));
                    assertTrue(commands.onTabComplete(player, null, alias, new String[]{action, "1"}).isEmpty());
                }
                assertEquals(List.of("1", "2", "3", "4"), commands.onTabComplete(player, null, alias, new String[]{"record", ""}));
            }
            verifyNoInteractions(store);
            studio.create(player, "New", false);
            assertEquals(2, studio.completionProjectIds(player).size());
            assertTrue(studio.completionTracks(player).isEmpty());
            studio.deleteProject(player, first);
            assertFalse(studio.completionProjectIds(player).contains(first.song().id().toString()));
            identity.sessions.put(account, new StudioIdentity.Token(new StudioIdentity.Owner(account, UUID.randomUUID()), 2));
            assertTrue(studio.completionProjectIds(player).isEmpty());
            assertTrue(studio.completionTracks(player).isEmpty());
            tasks.removeFirst().run();
            assertTrue(studio.completionProjectIds(player).isEmpty());
            identity.sessions.put(account, null);
            assertTrue(studio.completionProjectIds(player).isEmpty());
            assertTrue(studio.completionTracks(player).isEmpty());
            var console = mock(CommandSender.class);
            when(console.hasPermission(anyString())).thenReturn(true);
            for (String alias : List.of("music", "recording")) {
                assertTrue(commands.onTabComplete(console, null, alias, new String[]{""}).isEmpty());
                assertTrue(commands.onTabComplete(console, null, alias, new String[]{"metronome", ""}).isEmpty());
                assertTrue(commands.onTabComplete(console, null, alias, new String[0]).isEmpty());
            }
        } finally { studio.close(); }
    }
    @Test void unavailableDataAndChangedSessionsNeverLeakCachedProjectsOrRunStaleLoads() throws Exception {
        var plugin = mock(InstrumentPlugin.class, RETURNS_DEEP_STUBS);
        var scheduler = plugin.getServer().getScheduler();
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var tasks = new ArrayList<Runnable>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            tasks.add(call.getArgument(1)); return mock(BukkitTask.class);
        });
        var identity = mock(StudioIdentity.class);
        var player = mock(Player.class);
        UUID account = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(account);
        when(player.getName()).thenReturn("Artist");
        var token = new StudioIdentity.Token(new StudioIdentity.Owner(account, UUID.randomUUID()), 1);
        when(identity.current(player)).thenReturn(java.util.Optional.of(token));
        var store = mock(StudioStore.class);
        var studio = new StudioService(plugin, mock(InstrumentManager.class), store,
                new StudioSettings(4, 40, 20, 60, 8, 32, 2, 0, 20, 0, 0), StudioStation.none(), mock(DiscItems.class), identity);
        try {
            clearInvocations(store);
            assertTrue(studio.completionProjectIds(player).isEmpty()); // Stale token cannot warm.
            assertTrue(tasks.isEmpty());
            when(identity.current(token)).thenReturn(true);
            assertTrue(studio.completionProjectIds(player).isEmpty());
            when(identity.current(token)).thenReturn(false);
            tasks.removeFirst().run();
            verifyNoInteractions(store);
            studio.transferred(token.owner().character());
            when(identity.current(token)).thenReturn(true);
            assertTrue(studio.completionProjectIds(player).isEmpty());
            when(store.summaries(token.owner().character())).thenThrow(new java.io.IOException("unavailable"));
            tasks.removeFirst().run();
            assertTrue(studio.completionProjectIds(player).isEmpty());
            verify(store, times(1)).summaries(token.owner().character());
            studio.transferred(token.owner().character());
            assertTrue(studio.completionProjectIds(player).isEmpty());
            var command = new StudioCommand(studio, mock(StudioMenu.class));
            assertTrue(command.onTabComplete(player, null, "music", new String[0]).isEmpty());
            studio.close();
            tasks.removeFirst().run();
            assertTrue(studio.completionProjectIds(player).isEmpty());
        } finally { studio.close(); }
    }

}
