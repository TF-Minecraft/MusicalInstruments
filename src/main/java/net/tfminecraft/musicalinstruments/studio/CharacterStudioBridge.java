package net.tfminecraft.musicalinstruments.studio;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent;
import net.tfminecraft.tlibs.character.Characters;

/** Loaded only after the optional TLibs character API has been probed. */
public final class CharacterStudioBridge implements StudioIdentity {
    public CharacterStudioBridge() {}

    static Token token(CharacterSession session) {
        return new Token(new Owner(session.ref().ownerId(), session.ref().characterId()), session.sessionId());
    }

    public Optional<Token> current(Player player) {
        if (player == null) return Optional.empty();
        return Characters.currentSession(player)
                .filter(session -> session.ref().ownerId().equals(player.getUniqueId()))
                .filter(Characters::isCurrent).map(CharacterStudioBridge::token);
    }

    public boolean current(Token token) {
        if (token == null) return false;
        Player player = Bukkit.getPlayer(token.owner().account());
        return player != null && current(player).filter(token::equals).isPresent();
    }

    public boolean heirsReady() { return Characters.legacyHeirsReady(); }
    public Optional<Owner> heir(UUID account) {
        if (account == null) return Optional.empty();
        return Characters.legacyHeir(account).filter(ref -> ref.ownerId().equals(account))
                .map(ref -> new Owner(ref.ownerId(), ref.characterId()));
    }

    public static void start(Plugin plugin, StudioService studio, StudioMenu menu) {
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.LOWEST)
            public void end(CharacterSessionEndEvent event) {
                // The event's session is already noncurrent; finish with its captured owner.
                studio.end(event.getPlayer(), token(event.getSession()));
                menu.end(event.getPlayer());
            }
        }, plugin);
    }
}
