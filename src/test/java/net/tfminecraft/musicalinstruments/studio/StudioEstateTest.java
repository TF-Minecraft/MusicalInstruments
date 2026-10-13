package net.tfminecraft.musicalinstruments.studio;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.Estates;
import net.tfminecraft.tlibs.character.estate.Recipient;
import net.tfminecraft.tlibs.character.estate.Outcome;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StudioEstateTest {
    @TempDir Path root;
    final CharacterRef from = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
    final CharacterRef to = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
    EstateDecision decision(Recipient property, long revision) {
        return new EstateDecision(UUID.randomUUID(), from, Optional.of(Instant.EPOCH), Instant.EPOCH,
                Optional.of(to), Optional.empty(), Optional.empty(), Recipient.FROZEN, property, Recipient.FROZEN, Recipient.SENDER, revision);
    }
    Plugin plugin() { Plugin plugin = mock(Plugin.class); when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger()); return plugin; }

    @Test void exactEstateGuardWaitAndWriterDrainPrecedeMovement() throws Exception {
        Plugin plugin = plugin(); StudioService studio = mock(StudioService.class);
        StudioOwnership ownership = new StudioOwnership(new StudioStore(root));
        CharacterStudioEstate estate = new CharacterStudioEstate(plugin, studio, ownership);
        var decision = decision(Recipient.character(to), 1);
        try (var estates = mockStatic(Estates.class)) {
            assertEquals(Outcome.Kind.RETRY, estate.settle(decision).kind());
            estates.when(() -> Estates.guardHolds(decision)).thenReturn(true);
            assertEquals(Outcome.Kind.RETRY, estate.settle(decision).kind());
            when(studio.quiescent(from.characterId())).thenReturn(true);
            assertEquals(Outcome.Kind.RETRY, estate.settle(decision).kind());
            when(studio.quiescent(to.characterId())).thenReturn(true);
            assertEquals(Outcome.Kind.DONE, estate.settle(decision).kind());
            verify(studio).transferred(from.characterId()); verify(studio).transferred(to.characterId());
            var frozen = decision(Recipient.FROZEN, 2); estates.when(() -> Estates.guardHolds(frozen)).thenReturn(true);
            assertEquals(Outcome.Kind.DONE, estate.settle(frozen).kind());
            var waiting = decision(Recipient.WAITING, 3); estates.when(() -> Estates.guardHolds(waiting)).thenReturn(true);
            assertEquals(Outcome.Kind.DONE, estate.settle(waiting).kind());
            estates.when(() -> Estates.guardHolds(decision)).thenReturn(false); // revival, later death or newer exact decision
            assertEquals(Outcome.Kind.RETRY, estate.settle(decision).kind());
        }
        assertEquals("studio", estate.id()); assertEquals(Map.of(), estate.describe(from.characterId()).counts());
    }

    @Test void cumulativeReceiptsAndRawHoldingsSurviveARepeatedSettlement() throws Exception {
        Plugin plugin = plugin(); StudioService studio = mock(StudioService.class); when(studio.quiescent(any())).thenReturn(true);
        StudioStore store = new StudioStore(root);
        Project project = new Project(new Song(UUID.randomUUID(), from.ownerId(), "Author", "Song", List.of()), null, 100, false);
        store.saveOwned(new StudioIdentity.Owner(from.ownerId(), from.characterId()), project);
        CharacterStudioEstate estate = new CharacterStudioEstate(plugin, studio, new StudioOwnership(store));
        assertEquals(Map.of("projects", 1), estate.describe(from.characterId()).counts());
        var decision = decision(Recipient.character(to), 1);
        try (var estates = mockStatic(Estates.class)) {
            estates.when(() -> Estates.guardHolds(decision)).thenReturn(true);
            var first = (Outcome.Done) estate.settle(decision);
            assertEquals(List.of(project.song().id().toString()), first.movedIds());
            assertEquals(first, estate.settle(decision));
            var frozen = new EstateDecision(decision.deathId(), from, decision.diedAt(), decision.decidedAt(), decision.heir(), decision.deathGuild(), decision.deathRealm(), Recipient.FROZEN, Recipient.FROZEN, Recipient.FROZEN, Recipient.SENDER, 2);
            estates.when(() -> Estates.guardHolds(frozen)).thenReturn(true);
            assertEquals(first.movedIds(), ((Outcome.Done) estate.settle(frozen)).movedIds());
        }
    }

    @Test void ioErrorsRetryConflictsBlockAndBadDescriptionsRemainVisible() throws Exception {
        Plugin plugin = plugin(); StudioService studio = mock(StudioService.class); when(studio.quiescent(any())).thenReturn(true);
        StudioOwnership ownership = mock(StudioOwnership.class);
        CharacterStudioEstate estate = new CharacterStudioEstate(plugin, studio, ownership);
        var decision = decision(Recipient.character(to), 1);
        try (var estates = mockStatic(Estates.class)) {
            estates.when(() -> Estates.guardHolds(decision)).thenReturn(true);
            when(ownership.transfer(any(), anyString(), any(), any(), any())).thenThrow(new IOException("disk unavailable"));
            assertEquals(Outcome.Kind.RETRY, estate.settle(decision).kind());
            doThrow(new StudioOwnership.Conflict("changed recipient")).when(ownership).transfer(any(), anyString(), any(), any(), any());
            assertEquals(Outcome.Kind.BLOCKED, estate.settle(decision).kind());
        }
        when(ownership.holdings(any())).thenThrow(new IOException("unloaded unreadable library"));
        assertEquals(Map.of("heldLibraries", 1), estate.describe(from.characterId()).counts());
    }

    @Test void reconciliationReportsOnlyChangedRevisionOutcomesAndIsolatesFailures() throws Exception {
        Plugin plugin = plugin(); StudioService studio = mock(StudioService.class); when(studio.quiescent(any())).thenReturn(true);
        var estate = new CharacterStudioEstate(plugin, studio, new StudioOwnership(new StudioStore(root)));
        var first = decision(Recipient.FROZEN, 1); var invalid = decision(Recipient.FROZEN, 1);
        try (var estates = mockStatic(Estates.class)) {
            estates.when(Estates::decided).thenReturn(List.of(first, invalid));
            estates.when(() -> Estates.guardHolds(first)).thenReturn(true);
            estate.reconcile(); // No service accepts reports yet; replay later.
            estates.when(() -> Estates.report(eq(first.deathId()), eq("studio"), eq(1L), any())).thenReturn(true);
            estate.reconcile(); estate.reconcile();
            estates.verify(() -> Estates.report(eq(first.deathId()), eq("studio"), eq(1L), any()), times(2));
            estates.when(() -> Estates.guardHolds(invalid)).thenThrow(new IllegalStateException("one bad estate"));
            assertDoesNotThrow(estate::reconcile);
        }
    }
}
