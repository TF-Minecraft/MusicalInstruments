package net.tfminecraft.musicalinstruments.studio;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Explicit identity fixture; production always uses the optional registered character service. */
final class TestIdentity implements StudioIdentity {
    static final TestIdentity INSTANCE = new TestIdentity();
    final Map<UUID, Token> sessions = new HashMap<>();
    final Map<UUID, Owner> heirs = new HashMap<>();
    boolean ready = true;
    boolean defaultHeirs = true;
    public Optional<Token> current(Player player) {
        UUID account = player.getUniqueId();
        return Optional.ofNullable(sessions.getOrDefault(account, new Token(new Owner(account, account), 1)));
    }
    public boolean current(Token token) {
        return token != null && token.equals(sessions.getOrDefault(token.owner().account(),
                new Token(new Owner(token.owner().account(), token.owner().account()), 1)));
    }
    public boolean heirsReady() { return ready; }
    public Optional<Owner> heir(UUID account) { return Optional.ofNullable(heirs.getOrDefault(account, defaultHeirs ? new Owner(account, account) : null)); }
}
