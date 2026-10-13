package net.tfminecraft.musicalinstruments.studio;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Immutable routing tokens keep the optional character API outside the studio's models. */
public interface StudioIdentity {
    record Owner(UUID account, UUID character) {}
    record Token(Owner owner, long session) {}

    Optional<Token> current(Player player);
    boolean current(Token token);
    boolean heirsReady();
    Optional<Owner> heir(UUID account);

    static StudioIdentity load() {
        try {
            Class<?> api = Class.forName("net.tfminecraft.tlibs.character.Characters");
            api.getMethod("legacyHeir", UUID.class);
            api.getMethod("legacyHeirsReady");
            return (StudioIdentity) Class.forName("net.tfminecraft.musicalinstruments.studio.CharacterStudioBridge")
                    .getConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError ex) {
            return new StudioIdentity() {
                public Optional<Token> current(Player player) { return Optional.empty(); }
                public boolean current(Token token) { return false; }
                public boolean heirsReady() { return false; }
                public Optional<Owner> heir(UUID account) { return Optional.empty(); }
            };
        }
    }
}
