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
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterSessionStartEvent;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;

class StudioCompletionTest {
    @TempDir Path directory;

    @Test void startupAndSessionStartsLeaveProjectsColdUntilExplicitCompletion() throws Exception {
        var eventServer = MockBukkit.mock();
        try {
            var bridgePlugin = MockBukkit.createMockPlugin();
            var player = eventServer.addPlayer();
            player.addAttachment(bridgePlugin, "instruments.record", true);
            var plugin = mock(InstrumentPlugin.class, RETURNS_DEEP_STUBS);
            when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
            var scheduler = plugin.getServer().getScheduler();
            var tasks = new ArrayList<Runnable>();
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                tasks.add(call.getArgument(1)); return mock(BukkitTask.class);
            });
            var identity = new TestIdentity(); identity.defaultHeirs = false;
            var first = new StudioIdentity.Owner(player.getUniqueId(), UUID.randomUUID());
            var second = new StudioIdentity.Owner(player.getUniqueId(), UUID.randomUUID());
            identity.sessions.put(player.getUniqueId(), new StudioIdentity.Token(first, 1));
            var store = spy(new StudioStore(directory));
            var track = new Track(3, 20, 1, false, List.of(new Note(0, "lute", "sample", 1, 1)));
            var saved = new Project(new Song(UUID.randomUUID(), second.account(), "Artist", "Saved", List.of(track)), null, 100, false);
            store.saveOwned(first, new Project(new Song(UUID.randomUUID(), first.account(), "Artist", "Previous", List.of()), null, 100, false));
            store.saveOwned(second, saved);
            var studio = new StudioService(plugin, mock(InstrumentManager.class), store,
                    new StudioSettings(4, 40, 20, 60, 8, 32, 2, 0, 20, 0, 0), StudioStation.none(), mock(DiscItems.class), identity);
            try {
                clearInvocations(store);
                CharacterStudioBridge.start(bridgePlugin, studio, mock(StudioMenu.class));
                assertTrue(tasks.isEmpty(), "Startup must not schedule completion loading for online players");
                verifyNoInteractions(store);
                eventServer.getPluginManager().callEvent(new CharacterSessionStartEvent(player,
                        new CharacterSession(new CharacterRef(first.account(), first.character()), 1)));
                identity.sessions.put(player.getUniqueId(), new StudioIdentity.Token(second, 2));
                eventServer.getPluginManager().callEvent(new CharacterSessionStartEvent(player,
                        new CharacterSession(new CharacterRef(second.account(), second.character()), 2)));
                assertTrue(tasks.isEmpty(), "Login and character switches must not schedule completion loading");
                verifyNoInteractions(store);

                var commands = new StudioCommand(studio, mock(StudioMenu.class));
                for (String alias : List.of("music", "recording")) {
                    assertTrue(commands.onTabComplete(player, null, alias, new String[]{"open", ""}).isEmpty());
                    for (String action : List.of("volume", "mute", "remove")) {
                        assertTrue(commands.onTabComplete(player, null, alias, new String[]{action, ""}).isEmpty());
                    }
                }
                assertEquals(1, tasks.size(), "Cold completion requests must share one scheduled load");
                verifyNoInteractions(store);
                tasks.removeFirst().run();
                verify(store).summaries(second.character());
                verify(store).project(second.character());
                verify(store, never()).summaries(first.character());
                verify(store, never()).project(first.character());
                clearInvocations(store);
                for (String alias : List.of("music", "recording")) {
                    assertEquals(List.of(saved.song().id().toString()), commands.onTabComplete(player, null, alias, new String[]{"open", ""}));
                    for (String action : List.of("volume", "mute", "remove")) {
                        assertEquals(List.of("3"), commands.onTabComplete(player, null, alias, new String[]{action, ""}));
                    }
                }
                assertTrue(tasks.isEmpty(), "Warm completions must not schedule another load");
                verifyNoInteractions(store);
            } finally { studio.close(); }
        } finally { MockBukkit.unmock(); }
    }

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedCompletionLoadsRecoverInTheSameSessionWithoutKeystrokeRetries(boolean selectedProjectFails) throws Exception {
        var plugin = mock(InstrumentPlugin.class, RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var scheduler = plugin.getServer().getScheduler();
        var ticks = new ArrayList<Runnable>();
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L))).thenAnswer(call -> {
            ticks.add(call.getArgument(1)); return mock(BukkitTask.class);
        });
        var tasks = new ArrayList<Runnable>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            tasks.add(call.getArgument(1)); return mock(BukkitTask.class);
        });
        var player = mock(Player.class);
        UUID account = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(account);
        when(player.hasPermission("instruments.record")).thenReturn(true);
        var identity = new TestIdentity(); identity.defaultHeirs = false;
        var owner = new StudioIdentity.Owner(account, UUID.randomUUID());
        identity.sessions.put(account, new StudioIdentity.Token(owner, 1));
        var store = spy(new StudioStore(directory));
        var track = new Track(3, 20, 1, false, List.of(new Note(0, "lute", "sample", 1, 1)));
        var saved = new Project(new Song(UUID.randomUUID(), account, "Artist", "Saved", List.of(track)), null, 100, false);
        store.saveOwned(owner, saved);
        var failure = new java.io.IOException("Storage temporarily unavailable");
        if (selectedProjectFails) doThrow(failure).doThrow(failure).doCallRealMethod().when(store).project(owner.character());
        else doThrow(failure).doThrow(failure).doCallRealMethod().when(store).summaries(owner.character());
        var studio = new StudioService(plugin, mock(InstrumentManager.class), store,
                new StudioSettings(4, 40, 20, 60, 8, 32, 2, 0, 20, 0, 0), StudioStation.none(), mock(DiscItems.class), identity);
        var commands = new StudioCommand(studio, mock(StudioMenu.class));
        try {
            clearInvocations(store);
            assertTrue(commands.onTabComplete(player, null, "music", new String[]{"open", ""}).isEmpty());
            assertEquals(1, tasks.size());
            verifyNoInteractions(store);
            for (int attempt = 0; attempt < 2; attempt++) {
                tasks.removeFirst().run();
                verify(store).summaries(owner.character());
                if (selectedProjectFails) verify(store).project(owner.character());
                else verify(store, never()).project(owner.character());
                clearInvocations(store);
                for (long elapsed = 0; elapsed < StudioService.RETRY_TICKS; elapsed++) {
                    for (String alias : List.of("music", "recording")) {
                        assertTrue(commands.onTabComplete(player, null, alias, new String[]{"open", ""}).isEmpty());
                        assertTrue(commands.onTabComplete(player, null, alias, new String[]{"volume", ""}).isEmpty());
                    }
                    assertTrue(tasks.isEmpty(), "Failed storage must not be retried during the cooldown");
                    ticks.getFirst().run();
                }
                assertTrue(tasks.isEmpty(), "Elapsed time alone must not trigger a disk load");
                verifyNoInteractions(store);
                assertTrue(commands.onTabComplete(player, null, "music", new String[]{"open", ""}).isEmpty());
                assertTrue(commands.onTabComplete(player, null, "recording", new String[]{"remove", ""}).isEmpty());
                assertEquals(1, tasks.size(), "The next explicit requests must share one retry after the cooldown");
                verifyNoInteractions(store);
            }
            tasks.removeFirst().run();
            verify(store).summaries(owner.character());
            verify(store).project(owner.character());
            clearInvocations(store);
            for (String alias : List.of("music", "recording")) {
                assertEquals(List.of(saved.song().id().toString()), commands.onTabComplete(player, null, alias, new String[]{"open", ""}));
                for (String action : List.of("volume", "mute", "remove")) {
                    assertEquals(List.of("3"), commands.onTabComplete(player, null, alias, new String[]{action, ""}));
                }
            }
            assertTrue(tasks.isEmpty());
            verifyNoInteractions(store);
        } finally { studio.close(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"quit", "end", "transferred"})
    void completionRetryCooldownIsInvalidatedWithTheOwnerSnapshot(String invalidation) throws Exception {
        var plugin = mock(InstrumentPlugin.class, RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var scheduler = plugin.getServer().getScheduler();
        var tasks = new ArrayList<Runnable>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            tasks.add(call.getArgument(1)); return mock(BukkitTask.class);
        });
        var player = mock(Player.class);
        UUID account = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(account);
        var identity = new TestIdentity(); identity.defaultHeirs = false;
        var owner = new StudioIdentity.Owner(account, UUID.randomUUID());
        var token = new StudioIdentity.Token(owner, 1);
        identity.sessions.put(account, token);
        var store = spy(new StudioStore(directory));
        var saved = new Project(new Song(UUID.randomUUID(), account, "Artist", "Saved", List.of()), null, 100, false);
        store.saveOwned(owner, saved);
        doThrow(new IllegalArgumentException("Storage temporarily unavailable")).doCallRealMethod().when(store).summaries(owner.character());
        var studio = new StudioService(plugin, mock(InstrumentManager.class), store,
                new StudioSettings(4, 40, 20, 60, 8, 32, 2, 0, 20, 0, 0), StudioStation.none(), mock(DiscItems.class), identity);
        try {
            assertTrue(studio.completionProjectIds(player).isEmpty());
            tasks.removeFirst().run();
            assertTrue(studio.completionProjectIds(player).isEmpty());
            assertTrue(tasks.isEmpty());
            clearInvocations(store);
            switch (invalidation) {
                case "quit" -> studio.quit(player);
                case "end" -> studio.end(player, token);
                case "transferred" -> studio.transferred(owner.character());
                default -> fail("Unknown invalidation");
            }
            identity.sessions.put(account, new StudioIdentity.Token(owner, 2));
            assertTrue(studio.completionProjectIds(player).isEmpty());
            assertTrue(studio.completionTracks(player).isEmpty());
            assertEquals(1, tasks.size(), "An invalidated snapshot must not retain its previous session's cooldown");
            verifyNoInteractions(store);
            tasks.removeFirst().run();
            assertEquals(List.of(saved.song().id().toString()), studio.completionProjectIds(player));
            assertTrue(tasks.isEmpty());
        } finally { studio.close(); }
    }

}
