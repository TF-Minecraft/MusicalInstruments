package net.tfminecraft.musicalinstruments.studio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.tfminecraft.tlibs.character.estate.Estates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exact-byte readiness and lexical no-effect reconciliation boundaries. */
class StudioOwnershipPassTest {
    @TempDir Path root;

    private StudioIdentity.Owner owner() {
        return new StudioIdentity.Owner(UUID.randomUUID(), UUID.randomUUID());
    }

    private Path header(UUID death, StudioIdentity.Owner from) throws IOException {
        var values = new Properties();
        values.setProperty("format", "1"); values.setProperty("kind", "estate");
        values.setProperty("death", death.toString());
        values.setProperty("from-account", from.account().toString());
        values.setProperty("from-character", from.character().toString());
        Path file = root.resolve("ownership/estates/" + death + ".xml");
        StudioOwnership.write(file, values);
        return file;
    }

    @Test void completedNoEffectPassReadsLinearBytesAndDirectCallsAndLaterPassesReadFresh() throws Exception {
        var store = new StudioStore(root); var ownership = new StudioOwnership(store);
        var from = owner(); var to = owner();
        List<UUID> deaths = new ArrayList<>();
        for (int index = 0; index < 12; index++) { UUID death = UUID.randomUUID(); deaths.add(death); header(death, from); }
        var reads = new AtomicInteger();
        try (var disk = mockStatic(Files.class, call -> {
            if (call.getMethod().getName().equals("readAllBytes")) reads.incrementAndGet();
            return call.callRealMethod();
        })) {
            ownership.beginPass();
            try {
                for (UUID death : deaths) assertEquals(List.of(), ownership.retainedReceiptsDuringPass(death,
                        StudioOwnershipTest.decision(death, "one waiting", from, to), from));
                assertEquals(24, reads.get(), "one current header per decision plus one whole reservation snapshot");
                for (UUID death : deaths) ownership.retainedReceiptsDuringPass(death,
                        StudioOwnershipTest.decision(death, "one waiting", from, to), from);
                assertEquals(36, reads.get(), "same pass rereads each current header without another all-journal scan");
            } finally { ownership.endPass(); }
            reads.set(0);
            ownership.retainedReceipts(deaths.getFirst(), StudioOwnershipTest.decision(deaths.getFirst(), "one waiting", from, to), from);
            assertEquals(13, reads.get(), "direct settlement never borrows the ended pass snapshot");
            reads.set(0);
            ownership.retainedReceiptsDuringPass(deaths.getFirst(), StudioOwnershipTest.decision(deaths.getFirst(), "one waiting", from, to), from);
            assertEquals(13, reads.get(), "a pass operation outside its lexical pass falls back to full current validation");
            reads.set(0);
            ownership.beginPass();
            try {
                ownership.retainedReceiptsDuringPass(deaths.getFirst(), StudioOwnershipTest.decision(deaths.getFirst(), "one waiting", from, to), from);
                assertEquals(13, reads.get(), "a new pass captures fresh bytes");
                reads.set(0); ownership.requireWritable(from.character());
                assertEquals(12, reads.get(), "live actions scan even while a no-effect pass exists");
                reads.set(0);
                ownership.retainedReceipts(deaths.getFirst(), StudioOwnershipTest.decision(deaths.getFirst(), "one waiting", from, to), from);
                assertEquals(13, reads.get(), "direct operations also scan inside an active reconciliation pass");
            } finally { ownership.endPass(); }
        }
    }

    @Test void newMalformedJournalIsCheckedBeforeFirstBackupAndDirectSettlement() throws Exception {
        var store = new StudioStore(root); var ownership = new StudioOwnership(store);
        var from = owner(); var to = owner(); UUID existing = UUID.randomUUID(); header(existing, from);
        ownership.beginPass();
        try {
            ownership.retainedReceiptsDuringPass(existing, StudioOwnershipTest.decision(existing, "one waiting", from, to), from);
            Path broken = root.resolve("ownership/estates/new-unattributed.xml"); Files.writeString(broken, "not XML");
            var project = new Project(new Song(UUID.randomUUID(), from.account(), "Author", "Kept", List.of()), null, 100, false);
            store.saveOwned(from, project);
            var helper = new StudioOwnershipReviewTest(); helper.root = root; var before = helper.snapshot();
            UUID next = UUID.randomUUID();
            assertThrows(StudioOwnership.Conflict.class, () -> ownership.transferDuringPass(next,
                    StudioOwnershipTest.decision(next, "one", from, to), from, to, () -> true));
            assertEquals(before, helper.snapshot(), "refusal precedes backups, journal publication and owner changes");
        } finally { ownership.endPass(); }
        var studio = mock(StudioService.class); when(studio.quiescent(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        var support = new StudioEstateTest(); var decision = support.decision(net.tfminecraft.tlibs.character.estate.Recipient.FROZEN, 1);
        var estate = new CharacterStudioEstate(support.plugin(), studio, ownership);
        try (var estates = mockStatic(Estates.class)) {
            estates.when(() -> Estates.guardHolds(decision)).thenReturn(true);
            assertEquals(net.tfminecraft.tlibs.character.estate.Outcome.Kind.BLOCKED, estate.settle(decision).kind());
        }
    }

    @Test void passFailuresStayFreshAndKeepConflictVersusIoClassification() throws Exception {
        var ownership = new StudioOwnership(new StudioStore(root)); var from = owner(); var to = owner();
        Path bad = root.resolve("ownership/estates/bad.xml"); Files.createDirectories(bad.getParent()); Files.writeString(bad, "bad XML");
        for (boolean directory : List.of(false, true)) {
            if (directory) { Files.delete(bad); Files.createDirectory(bad); }
            ownership.beginPass();
            try {
                List<IOException> failures = new ArrayList<>();
                for (int index = 0; index < 2; index++) {
                    UUID death = UUID.randomUUID(); failures.add(assertThrows(IOException.class,
                            () -> ownership.retainedReceiptsDuringPass(death, StudioOwnershipTest.decision(death, "one waiting", from, to), from)));
                }
                assertNotSame(failures.getFirst(), failures.getLast());
                assertEquals(!directory, failures.getFirst() instanceof StudioOwnership.Conflict);
            } finally { ownership.endPass(); }
        }
        Files.delete(bad);
        ownership.beginPass();
        try { UUID death = UUID.randomUUID(); assertEquals(List.of(), ownership.retainedReceiptsDuringPass(death,
                StudioOwnershipTest.decision(death, "one waiting", from, to), from)); }
        finally { ownership.endPass(); }
    }

    private record Legacy(StudioOwnership ownership, StudioIdentity.Owner owner, Path journal, Path folder) {}

    private Legacy legacy(boolean invalidSelection) throws Exception {
        var store = new StudioStore(root); var owner = owner();
        var project = new Project(new Song(UUID.randomUUID(), owner.account(), "Author", "Legacy", List.of()), null, 100, false);
        store.save(project);
        Path folder = root.resolve("projects/" + owner.account()); Files.createDirectories(folder.getParent());
        Files.move(store.directory(owner.account()), folder);
        if (invalidSelection) Files.writeString(folder.resolve("selected"), "invalid pointer");
        var identity = new TestIdentity(); identity.defaultHeirs = false; identity.heirs.put(owner.account(), owner);
        var ownership = new StudioOwnership(store); ownership.migrate(identity);
        return new Legacy(ownership, owner, root.resolve("ownership/migration/" + owner.account() + ".xml"), folder);
    }

    @Test void readinessHashesEveryCallAndOnlyMemoizesSuccessfulExactBodyValidation() throws Exception {
        var fixture = legacy(false); byte[] original = Files.readAllBytes(fixture.journal());
        var reads = new AtomicInteger(); var parses = new AtomicInteger();
        try (var disk = mockStatic(Files.class, call -> {
            if (call.getMethod().getName().equals("readAllBytes") && fixture.journal().equals(call.getArgument(0))) reads.incrementAndGet();
            return call.callRealMethod();
        }); var yaml = mockStatic(RawYaml.class, call -> {
            if (call.getMethod().getName().equals("read")) parses.incrementAndGet();
            return call.callRealMethod();
        })) {
            fixture.ownership().requireEstateReady(fixture.owner()); assertTrue(parses.get() > 0);
            parses.set(0); fixture.ownership().requireEstateReady(fixture.owner());
            assertEquals(2, reads.get()); assertEquals(0, parses.get(), "unchanged successful body avoids payload decoding");
            byte[] changed = new String(original, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("Studio ownership format 1", "Studio ownership format 2").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(original.length, changed.length);
            var metadata = Files.readAttributes(fixture.journal(), java.nio.file.attribute.BasicFileAttributes.class);
            Files.write(fixture.journal(), changed); Files.setLastModifiedTime(fixture.journal(), metadata.lastModifiedTime());
            fixture.ownership().requireEstateReady(fixture.owner()); assertTrue(parses.get() > 0, "same path/size/time does not grant validation");
            var malformed = StudioOwnership.read(fixture.journal());
            String id = StudioOwnership.ids(malformed).getFirst(); malformed.setProperty(id + ".after-hash", "broken");
            StudioOwnership.write(fixture.journal(), malformed);
            for (int attempt = 0; attempt < 2; attempt++) assertThrows(StudioOwnership.Conflict.class,
                    () -> fixture.ownership().requireEstateReady(fixture.owner()));
            Files.write(fixture.journal(), original); fixture.ownership().requireEstateReady(fixture.owner());
            Path other = fixture.journal().resolveSibling(UUID.randomUUID() + ".xml"); Files.write(other, original);
            var foreign = new StudioIdentity.Owner(UUID.fromString(other.getFileName().toString().replace(".xml", "")), UUID.randomUUID());
            Path foreignFolder = root.resolve("projects/" + foreign.account()); Files.createDirectories(foreignFolder);
            Files.copy(fixture.folder().resolve(id + ".yml"), foreignFolder.resolve(id + ".yml"));
            assertThrows(StudioOwnership.Conflict.class, () -> fixture.ownership().requireEstateReady(foreign));
        }
    }

    @Test void cachedInvalidSelectionExemptionStillReadsTheLiveLegacyPointer() throws Exception {
        var fixture = legacy(true); var ownership = fixture.ownership(); var owner = fixture.owner();
        ownership.requireEstateReady(owner); ownership.requireEstateReady(owner);
        Path selected = fixture.folder().resolve("selected"); byte[] invalid = Files.readAllBytes(selected);
        Files.writeString(selected, UUID.randomUUID().toString());
        assertThrows(StudioOwnership.Conflict.class, () -> ownership.requireEstateReady(owner));
        Files.write(selected, invalid); ownership.requireEstateReady(owner);
        Files.delete(selected); assertThrows(StudioOwnership.Conflict.class, () -> ownership.requireEstateReady(owner));
        Files.createDirectory(selected); assertThrows(StudioOwnership.Conflict.class, () -> ownership.requireEstateReady(owner));
        ownership.requireEstateReady(new StudioIdentity.Owner(owner.account(), UUID.randomUUID()));
    }

    @Test void reconcileAlwaysEndsItsPassEvenWhenTheDecisionProviderThrows() throws Exception {
        var ownership = spy(new StudioOwnership(new StudioStore(root)));
        var support = new StudioEstateTest();
        var estate = new CharacterStudioEstate(support.plugin(), mock(StudioService.class), ownership);
        try (var estates = mockStatic(Estates.class)) {
            estates.when(Estates::decided).thenThrow(new IllegalStateException("provider unavailable"));
            assertThrows(IllegalStateException.class, estate::reconcile);
            verify(ownership).beginPass(); verify(ownership).endPass();
        }
    }
    @Test void reconciliationEffectsUseThePassButReenteredPublicSettlementReadsCurrentJournals() throws Exception {
        var support = new StudioEstateTest();
        var store = new StudioStore(root); var ownership = spy(new StudioOwnership(store));
        var studio = mock(StudioService.class); when(studio.quiescent(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        var decision = support.decision(net.tfminecraft.tlibs.character.estate.Recipient.character(support.to), 1);
        var project = new Project(new Song(UUID.randomUUID(), support.from.ownerId(), "Author", "Moved", List.of()), null, 100, false);
        store.saveOwned(new StudioIdentity.Owner(support.from.ownerId(), support.from.characterId()), project);
        var estate = new CharacterStudioEstate(support.plugin(), studio, ownership);
        var frozen = new net.tfminecraft.tlibs.character.estate.EstateDecision(decision.deathId(), decision.testator(),
                decision.diedAt(), decision.decidedAt(), decision.heir(), decision.deathGuild(), decision.deathRealm(),
                decision.money(), net.tfminecraft.tlibs.character.estate.Recipient.FROZEN, decision.guildLocks(), decision.letters(), 2);
        try (var estates = mockStatic(Estates.class)) {
            estates.when(Estates::decided).thenReturn(List.of(decision));
            estates.when(() -> Estates.guardHolds(decision)).thenReturn(true);
            estates.when(() -> Estates.guardHolds(frozen)).thenReturn(true);
            estates.when(() -> Estates.report(org.mockito.ArgumentMatchers.eq(decision.deathId()),
                    org.mockito.ArgumentMatchers.eq("studio"), org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(call -> {
                        Files.writeString(root.resolve("ownership/estates/unattributed.xml"), "bad XML");
                        assertEquals(net.tfminecraft.tlibs.character.estate.Outcome.Kind.BLOCKED, estate.settle(frozen).kind());
                        return true;
                    });
            estate.reconcile();
            verify(ownership).transferDuringPass(org.mockito.ArgumentMatchers.eq(decision.deathId()),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            verify(ownership).endPass();
            assertFalse(Files.exists(store.projectFile(support.from.characterId(), project.song().id())));
            assertTrue(Files.exists(store.projectFile(support.to.characterId(), project.song().id())));
        }
    }

}
