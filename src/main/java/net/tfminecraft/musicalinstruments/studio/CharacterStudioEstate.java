package net.tfminecraft.musicalinstruments.studio;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import net.tfminecraft.tlibs.character.estate.Estates;
import net.tfminecraft.tlibs.character.estate.Holdings;
import net.tfminecraft.tlibs.character.estate.Outcome;

/** Published recordings stay immutable; only the editable project's authority moves. */
final class CharacterStudioEstate implements EstateParticipant {
    static final String ID = "studio";
    private final StudioService studio;
    private final StudioOwnership ownership;
    private final Plugin plugin;
    private final Map<UUID, Map.Entry<Long, Outcome>> reported = new HashMap<>();

    CharacterStudioEstate(Plugin plugin, StudioService studio, StudioOwnership ownership) {
        this.plugin = plugin; this.studio = studio; this.ownership = ownership;
    }

    static void start(Plugin plugin, StudioService studio, StudioOwnership ownership) {
        CharacterStudioEstate participant = new CharacterStudioEstate(plugin, studio, ownership);
        plugin.getServer().getServicesManager().register(EstateParticipant.class, participant, plugin, ServicePriority.Normal);
        plugin.getServer().getScheduler().runTaskTimer(plugin, participant::reconcile, 1L, 72_000L);
    }

    public String id() { return ID; }
    public Outcome settle(EstateDecision decision) { return settle(decision, false); }

    private Outcome settle(EstateDecision decision, boolean inPass) {
        if (!Estates.guardHolds(decision)) return Outcome.retry("The current estate decision does not hold");
        UUID source = decision.testator().characterId();
        if (!studio.quiescent(source)) return Outcome.retry("The original character's studio saves are still pending");
        try {
            ownership.requireEstateReady(owner(decision.testator()));
            if (decision.property().character().isEmpty()) {
                var previous = inPass
                        ? ownership.retainedReceiptsDuringPass(decision.deathId(), decision.toString(), owner(decision.testator()))
                        : ownership.retainedReceipts(decision.deathId(), decision.toString(), owner(decision.testator()));
                return Outcome.done(Map.of("projects", previous.size()), previous);
            }
            CharacterRef recipient = decision.property().character().orElseThrow();
            if (!studio.quiescent(recipient.characterId())) return Outcome.retry("The recipient's studio saves are still pending");
            var moved = inPass
                    ? ownership.transferDuringPass(decision.deathId(), decision.toString(), owner(decision.testator()), owner(recipient),
                            () -> Estates.guardHolds(decision))
                    : ownership.transfer(decision.deathId(), decision.toString(), owner(decision.testator()), owner(recipient),
                            () -> Estates.guardHolds(decision));
            studio.transferred(source);
            studio.transferred(recipient.characterId());
            return Outcome.done(Map.of("projects", moved.size()), moved);
        } catch (StudioOwnership.Conflict ex) {
            return Outcome.blocked(ex.getMessage());
        } catch (IOException ex) {
            return Outcome.retry("Could not save studio ownership: " + ex.getMessage());
        }
    }

    public Holdings describe(UUID character) {
        try {
            var ids = ownership.holdings(character);
            return ids.isEmpty() ? Holdings.NONE : new Holdings(Map.of("projects", ids.size()), ids);
        } catch (IOException ex) {
            return new Holdings(Map.of("heldLibraries", 1), java.util.List.of("Studio library needs recovery"));
        }
    }

    void reconcile() {
        ownership.beginPass();
        try {
            for (EstateDecision decision : Estates.decided()) {
                try {
                    if (!Estates.guardHolds(decision)) continue;
                    Outcome outcome = settle(decision, true);
                    Map.Entry<Long, Outcome> result = Map.entry(decision.revision(), outcome);
                    if (!result.equals(reported.get(decision.deathId()))
                            && Estates.report(decision.deathId(), ID, decision.revision(), outcome)) reported.put(decision.deathId(), result);
                } catch (RuntimeException ex) { plugin.getLogger().warning("Studio estate failed: " + decision.deathId() + " " + ex); }
            }
        } finally { ownership.endPass(); }
    }

    private static StudioIdentity.Owner owner(CharacterRef ref) { return new StudioIdentity.Owner(ref.ownerId(), ref.characterId()); }
}
