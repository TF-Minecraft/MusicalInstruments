package net.tfminecraft.musicalinstruments.studio;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent;
import net.tfminecraft.tlibs.character.Characters;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StudioBridgeTest {
    @Test void optionalCharacterBridgeBindsFullRefAndOutgoingEventWithoutCurrentness() throws Exception {
        var player = mock(Player.class); UUID account = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(account);
        var session = new CharacterSession(new CharacterRef(account, UUID.randomUUID()), 19);
        var bridge = new CharacterStudioBridge();
        try (var characters = mockStatic(Characters.class); var bukkit = mockStatic(Bukkit.class)) {
            assertTrue(bridge.current((Player) null).isEmpty());
            assertTrue(bridge.heir(null).isEmpty());
            assertFalse(bridge.current((StudioIdentity.Token) null));
            var token = CharacterStudioBridge.token(session);
            assertFalse(bridge.current(token));
            bukkit.when(() -> Bukkit.getPlayer(account)).thenReturn(player);
            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session));
            assertTrue(bridge.current(player).isEmpty()); // Provider rejected the captured session.
            characters.when(() -> Characters.isCurrent(session)).thenReturn(true);
            assertEquals(token, bridge.current(player).orElseThrow()); assertTrue(bridge.current(token));
            assertFalse(bridge.current(new StudioIdentity.Token(token.owner(), 20)));
            characters.when(() -> Characters.legacyHeir(account)).thenReturn(Optional.of(session.ref()));
            assertEquals(token.owner(), bridge.heir(account).orElseThrow());
            characters.when(Characters::legacyHeirsReady).thenReturn(true); assertTrue(bridge.heirsReady());
            Plugin plugin = mock(Plugin.class); var server = mock(org.bukkit.Server.class); var manager = mock(org.bukkit.plugin.PluginManager.class);
            when(plugin.getServer()).thenReturn(server); when(server.getPluginManager()).thenReturn(manager);
            StudioService studio = mock(StudioService.class); StudioMenu menu = mock(StudioMenu.class);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(java.util.List.of(player));
            CharacterStudioBridge.start(plugin, studio, menu);
            verifyNoInteractions(studio, menu);
            var listener = ArgumentCaptor.forClass(Listener.class); verify(manager).registerEvents(listener.capture(), eq(plugin));
            characters.when(() -> Characters.isCurrent(session)).thenReturn(false);
            listener.getValue().getClass().getMethod("end", CharacterSessionEndEvent.class).invoke(listener.getValue(),
                    new CharacterSessionEndEvent(player, session, CharacterSessionEndEvent.Reason.SWITCH));
            verify(studio).end(player, token); verify(menu).end(player);
        }
    }

    @Test void providersCannotRouteAnotherAccountsLiveOrStaleSessionOrReviewedHeir() {
        Player player = mock(Player.class); UUID account = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(account);
        var foreign = new CharacterSession(new CharacterRef(UUID.randomUUID(), UUID.randomUUID()), 31);
        var bridge = new CharacterStudioBridge();
        try (var characters = mockStatic(Characters.class)) {
            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(foreign));
            characters.when(() -> Characters.isCurrent(foreign)).thenReturn(true);
            assertTrue(bridge.current(player).isEmpty());
            characters.when(() -> Characters.isCurrent(foreign)).thenReturn(false);
            assertTrue(bridge.current(player).isEmpty());
            characters.when(() -> Characters.legacyHeir(account)).thenReturn(Optional.of(foreign.ref()));
            assertTrue(bridge.heir(account).isEmpty());
            characters.when(() -> Characters.legacyHeir(account)).thenReturn(Optional.empty());
            assertTrue(bridge.heir(account).isEmpty());
        }
    }

    @Test void olderOrAbsentTlibsLoadsTheFailClosedAdapterAndLeavesEstateIntegrationUnavailable() throws Exception {
        var classes = StudioIdentity.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new URLClassLoader(new java.net.URL[]{classes}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.tfminecraft.tlibs.character")) throw new ClassNotFoundException("Older TLibs fixture: " + name);
                if (name.startsWith("net.tfminecraft.musicalinstruments.studio")) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type);
                    return type;
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Class<?> identity = loader.loadClass(StudioIdentity.class.getName());
            Object unavailable = identity.getMethod("load").invoke(null);
            assertEquals(Optional.empty(), identity.getMethod("current", Player.class).invoke(unavailable, mock(Player.class)));
            Class<?> token = loader.loadClass(StudioIdentity.Token.class.getName());
            assertEquals(false, identity.getMethod("current", token).invoke(unavailable, new Object[]{null}));
            assertEquals(false, identity.getMethod("heirsReady").invoke(unavailable));
            assertEquals(Optional.empty(), identity.getMethod("heir", UUID.class).invoke(unavailable, UUID.randomUUID()));
            Class<?> estate = loader.loadClass(StudioEstate.class.getName());
            var method = estate.getDeclaredMethod("startIfAvailable", Plugin.class, loader.loadClass(StudioService.class.getName()), loader.loadClass(StudioOwnership.class.getName()));
            method.setAccessible(true);
            Plugin plugin = mock(Plugin.class); when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            assertDoesNotThrow(() -> method.invoke(null, plugin, null, null));
        }
    }

    @Test void theActualTlibs23ReleaseLoadsAndFailsClosedWithoutTheLegacyHeirApi() throws Exception {
        Path repository = Path.of(System.getProperty("maven.repo.local", Path.of(System.getProperty("user.home"), ".m2/repository").toString()));
        Path olderJar = repository.resolve("me/plugins/tlibs/2.3.0/tlibs-2.3.0.jar");
        assertTrue(java.nio.file.Files.isRegularFile(olderJar), "Install the pinned .github/compatibility/tlibs-2.3.xml fixture");
        var classes = StudioIdentity.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new URLClassLoader(new java.net.URL[]{classes, olderJar.toUri().toURL()}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.tfminecraft.tlibs") || name.startsWith("net.tfminecraft.musicalinstruments.studio")) {
                    Class<?> type = findLoadedClass(name); if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type); return type;
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Class<?> api = loader.loadClass("net.tfminecraft.tlibs.character.Characters");
            assertTrue(api.getProtectionDomain().getCodeSource().getLocation().toString().contains("2.3.0.jar"));
            assertThrows(NoSuchMethodException.class, () -> api.getMethod("legacyHeir", UUID.class));
            Class<?> identity = loader.loadClass(StudioIdentity.class.getName());
            Object fallback = identity.getMethod("load").invoke(null);
            assertEquals(Optional.empty(), identity.getMethod("current", Player.class).invoke(fallback, mock(Player.class)));
        }
    }

    @Test void rawYamlRefusesAmbiguousOrNonMappingDocumentsWithoutNormalizingThem() throws Exception {
        assertThrows(java.io.IOException.class, () -> RawYaml.read("a: 1\na: 2\n".getBytes()));
        assertThrows(java.io.IOException.class, () -> RawYaml.read("a plain string".getBytes()));
        assertThrows(java.io.IOException.class, () -> RawYaml.read("2: value".getBytes()));
        assertThrows(java.io.IOException.class, () -> RawYaml.read(new byte[]{(byte)0xff}));
    }
}
