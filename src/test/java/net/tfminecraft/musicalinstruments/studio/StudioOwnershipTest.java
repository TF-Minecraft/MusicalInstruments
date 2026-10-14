package net.tfminecraft.musicalinstruments.studio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StudioOwnershipTest {
    @TempDir Path root;
    final UUID account = UUID.randomUUID();
    final StudioIdentity.Owner from = new StudioIdentity.Owner(account, UUID.randomUUID());
    final StudioIdentity.Owner to = new StudioIdentity.Owner(UUID.randomUUID(), UUID.randomUUID());

    Project project() {
        Track track = new Track(1, 20, 1, false, List.of(new Note(0, "lute", "sound", 1, 1)));
        return new Project(new Song(UUID.randomUUID(), account, "Original author", "Original title", List.of(track)), track, 100, true);
    }
    StudioStore store() throws IOException { return new StudioStore(root); }
    Path legacy(Project project) throws Exception {
        StudioStore store = store();
        store.save(project);
        Path legacy = root.resolve("projects").resolve(account.toString());
        Files.createDirectories(legacy.getParent());
        Files.move(store.directory(account), legacy);
        return legacy.resolve(project.song().id() + ".yml");
    }
    TestIdentity reviewed() {
        TestIdentity identity = new TestIdentity();
        identity.defaultHeirs = false; identity.heirs.put(account, from);
        return identity;
    }

    static String decision(UUID death, String label, StudioIdentity.Owner source, StudioIdentity.Owner recipient) {
        long revision = label.startsWith("one") || label.startsWith("revision1") ? 1 : label.startsWith("two") || label.startsWith("revision2") ? 2 : label.startsWith("three") ? 3 : 4;
        var from = new net.tfminecraft.tlibs.character.CharacterRef(source.account(), source.character());
        var to = new net.tfminecraft.tlibs.character.CharacterRef(recipient.account(), recipient.character());
        var property = label.contains("frozen") ? net.tfminecraft.tlibs.character.estate.Recipient.FROZEN : label.contains("waiting") ? net.tfminecraft.tlibs.character.estate.Recipient.WAITING : net.tfminecraft.tlibs.character.estate.Recipient.character(to);
        return new net.tfminecraft.tlibs.character.estate.EstateDecision(death, from, java.util.Optional.empty(), java.time.Instant.EPOCH, java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), net.tfminecraft.tlibs.character.estate.Recipient.FROZEN, property, net.tfminecraft.tlibs.character.estate.Recipient.FROZEN, net.tfminecraft.tlibs.character.estate.Recipient.SENDER, revision).toString();
    }
    List<String> transfer(StudioOwnership ownership, UUID death, String label, StudioIdentity.Owner source, StudioIdentity.Owner recipient, java.util.function.BooleanSupplier guard) throws IOException {
        return ownership.transfer(death, decision(death, label, source, recipient), source, recipient, guard);
    }
    List<String> retained(StudioOwnership ownership, UUID death, String label, StudioIdentity.Owner source) throws IOException {
        return ownership.retainedReceipts(death, decision(death, label, source, to), source);
    }

    @Test void migrationKeepsEditionBytesAttributionSelectionAndOneReviewedHeir() throws Exception {
        StudioStore store = store(); Project project = project(); Path source = legacy(project);
        Song edition = store.publishEdition(project.song(), 20);
        byte[] published = Files.readAllBytes(root.resolve("songs").resolve(edition.id() + ".yml"));
        byte[] quota = Files.readAllBytes(root.resolve("editions").resolve(account + ".log"));
        byte[] old = Files.readAllBytes(source);
        StudioOwnership migration = new StudioOwnership(store);
        TestIdentity identity = reviewed(); identity.ready = false;
        migration.migrate(identity); assertFalse(Files.exists(store.projectFile(from.character(), project.song().id())));
        identity.ready = true; identity.heirs.clear(); migration.migrate(identity);
        assertFalse(Files.exists(store.projectFile(from.character(), project.song().id())));
        identity.heirs.put(account, from); migration.migrate(identity);
        assertEquals(project, store.project(from.character()).orElseThrow());
        assertArrayEquals(old, Files.readAllBytes(source));
        assertArrayEquals(published, Files.readAllBytes(root.resolve("songs").resolve(edition.id() + ".yml")));
        assertArrayEquals(quota, Files.readAllBytes(root.resolve("editions").resolve(account + ".log")));
        identity.heirs.put(account, to); migration.migrate(identity);
        assertFalse(Files.exists(store.projectFile(to.character(), project.song().id())));
        assertEquals(List.of(project.song().id().toString()), migration.holdings(from.character()));
        migration.requireWritable(from.character());
    }

    @Test void unknownFieldsSurviveMigrationMutationSaveShutdownAndRestart() throws Exception {
        StudioStore store = store(); Project project = project(); Path source = legacy(project);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(source.toFile());
        yaml.set("foreign.deep", Map.of("value", List.of("keep", 42)));
        var tracks = (List<Map<String,Object>>) yaml.getList("tracks");
        tracks.getFirst().put("foreign-track", Map.of("nested", "keep")); yaml.set("tracks", tracks);
        yaml.set("pending.foreign-pending.nested", "keep"); yaml.save(source.toFile());
        var raw = RawYaml.read(Files.readAllBytes(source)); raw.put("future-null", null);
        var nested = new java.util.LinkedHashMap<String,Object>(); nested.put("nullable", null); raw.put("future-object", nested);
        Files.write(source, RawYaml.bytes(raw));
        new StudioOwnership(store).migrate(reviewed());
        Project loaded = store.project(from.character()).orElseThrow();
        store.saveOwned(from, new Project(loaded.song(), loaded.pending(), 123, false));
        StudioStore restarted = store();
        assertEquals(123, restarted.project(from.character()).orElseThrow().bpm());
        var preserved = RawYaml.read(Files.readAllBytes(restarted.projectFile(from.character(), project.song().id())));
        assertTrue(preserved.containsKey("future-null")); assertNull(preserved.get("future-null"));
        assertTrue(((Map<?, ?>) preserved.get("future-object")).containsKey("nullable"));
        Path current = restarted.projectFile(from.character(), project.song().id());
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(current.toFile());
        assertEquals(List.of("keep", 42), saved.getList("foreign.deep.value"));
        assertEquals(Map.of("nested", "keep"), ((Map<?,?>) saved.getList("tracks").getFirst()).get("foreign-track"));
        assertEquals("keep", saved.getString("pending.foreign-pending.nested"));
        store.saveOwned(from, new Project(loaded.song().remove(1), null, 123, false));
        saved = YamlConfiguration.loadConfiguration(current.toFile());
        assertEquals("keep", saved.getString("retained-pending.foreign-pending.nested"));
        assertNotNull(saved.get("retained-tracks.1"));
        store.saveOwned(from, new Project(loaded.song(), null, 124, true));
        store.saveOwned(from, new Project(loaded.song().remove(1), null, 124, true));
        assertNotNull(YamlConfiguration.loadConfiguration(current.toFile()).get("retained-tracks.1"));
    }

    @Test void malformedLegacyFilesAreRetainedAndReportedWithoutActiveFallback() throws Exception {
        Project project = project(); Path source = legacy(project); Path folder = source.getParent();
        Files.writeString(source, "broken: [");
        Files.writeString(folder.resolve("not-a-uuid.yml"), "still: preserved");
        Files.writeString(folder.resolve("selected"), "bad pointer");
        Path ignored = root.resolve("projects/unreviewed-folder"); Files.createDirectories(ignored);
        Files.writeString(ignored.resolve("opaque.yml"), "keep");
        StudioOwnership ownership = new StudioOwnership(store()); ownership.migrate(reviewed());
        var journal = StudioOwnership.read(root.resolve("ownership/migration").resolve(account + ".xml"));
        assertTrue(journal.containsKey("held." + project.song().id()));
        assertTrue(journal.containsKey("held.not-a-uuid"));
        assertEquals("Invalid project selection", journal.getProperty("held.selected"));
        assertEquals("broken: [", Files.readString(source));
        assertTrue(ownership.holdings(from.character()).isEmpty());
    }

    @Test void migrationValidatesIdentityAndDoesNotOverwriteRecipient() throws Exception {
        Project project = project(); Path source = legacy(project);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(source.toFile());
        yaml.set("owner", to.account().toString()); yaml.save(source.toFile());
        StudioOwnership ownership = new StudioOwnership(store()); ownership.migrate(reviewed());
        assertTrue(StudioOwnership.read(root.resolve("ownership/migration").resolve(account + ".xml")).containsKey("held." + project.song().id()));
    }

    @Test void transfersReplayOnceConserveProjectsAndPreserveRecipientSelection() throws Exception {
        StudioStore store = store(); Project project = project(); Project rawRecipient = project();
        Project recipient = new Project(new Song(rawRecipient.song().id(), to.account(), "Recipient author", "Existing", rawRecipient.song().tracks()), null, 100, false);
        store.saveOwned(from, project); store.saveOwned(to, recipient);
        StudioOwnership ownership = new StudioOwnership(store); UUID death = UUID.randomUUID();
        byte[] authorBytes = Files.readAllBytes(store.projectFile(from.character(), project.song().id()));
        List<String> moved = transfer(ownership,death, "revision1 exact decision", from, to, () -> true);
        assertEquals(List.of(project.song().id().toString()), moved);
        assertFalse(Files.exists(store.projectFile(from.character(), project.song().id())));
        assertEquals(recipient.song().id(), store.project(to.character()).orElseThrow().song().id());
        assertEquals(project, store.project(to.character(), project.song().id()));
        assertEquals(moved, transfer(new StudioOwnership(store()),death, "revision1 exact decision", from, to, () -> true));
        assertEquals(moved, retained(ownership,death, "revision2 frozen", from));
        assertArrayEquals(authorBytes, Files.readAllBytes(root.resolve("ownership/backups").resolve(StudioOwnership.hash(authorBytes) + ".yml")));
        var journal = StudioOwnership.read(root.resolve("ownership/estates").resolve(death + ".xml"));
        assertEquals(to.account().toString(), journal.getProperty(project.song().id() + ".to-account"));
        assertEquals(from.character().toString(), journal.getProperty(project.song().id() + ".from-character"));
    }

    @Test void completedEstateReconciliationPreservesJournalBytesAndChainOrder() throws Exception {
        StudioStore store = store(); Project project = project(); store.saveOwned(from, project);
        StudioOwnership ownership = new StudioOwnership(store); UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        StudioIdentity.Owner last = new StudioIdentity.Owner(UUID.randomUUID(), UUID.randomUUID());
        List<String> moved = transfer(ownership, first, "one", from, to, () -> true);
        Path firstJournal = root.resolve("ownership/estates/" + first + ".xml");
        var firstTime = java.nio.file.attribute.FileTime.fromMillis(1_000_000);
        Files.setLastModifiedTime(firstJournal, firstTime);
        byte[] firstBytes = Files.readAllBytes(firstJournal);
        assertEquals(moved, transfer(ownership, second, "one", to, last, () -> true));
        Path secondJournal = root.resolve("ownership/estates/" + second + ".xml");
        var secondTime = java.nio.file.attribute.FileTime.fromMillis(2_000_000);
        Files.setLastModifiedTime(secondJournal, secondTime);
        byte[] secondBytes = Files.readAllBytes(secondJournal);
        byte[] projectBytes = Files.readAllBytes(store.projectFile(last.character(), project.song().id()));

        assertEquals(moved, transfer(new StudioOwnership(store()), first, "one", from, to, () -> true));
        assertArrayEquals(firstBytes, Files.readAllBytes(firstJournal));
        assertEquals(firstTime, Files.getLastModifiedTime(firstJournal));
        assertEquals(moved, retained(ownership, first, "two frozen", from));
        assertArrayEquals(firstBytes, Files.readAllBytes(firstJournal));
        assertEquals(firstTime, Files.getLastModifiedTime(firstJournal));
        assertArrayEquals(secondBytes, Files.readAllBytes(secondJournal));
        assertEquals(secondTime, Files.getLastModifiedTime(secondJournal));
        assertTrue(Files.getLastModifiedTime(secondJournal).compareTo(Files.getLastModifiedTime(firstJournal)) > 0);
        assertArrayEquals(projectBytes, Files.readAllBytes(store.projectFile(last.character(), project.song().id())));
        assertFalse(Files.exists(store.projectFile(from.character(), project.song().id())));
        assertFalse(Files.exists(store.projectFile(to.character(), project.song().id())));
    }

    @Test void retainedPlanWritesOnlyWhenSupersessionRemovesAnEntry() throws Exception {
        StudioStore store = store(); Project project = project(); store.saveOwned(from, project);
        StudioOwnership ownership = new StudioOwnership(store); UUID death = UUID.randomUUID();
        AtomicInteger checks = new AtomicInteger();
        assertThrows(IOException.class, () -> transfer(ownership, death, "one", from, to, () -> checks.incrementAndGet() == 1));
        Path file = root.resolve("ownership/estates/" + death + ".xml");
        var unchanged = java.nio.file.attribute.FileTime.fromMillis(1_000_000);
        Files.setLastModifiedTime(file, unchanged); byte[] before = Files.readAllBytes(file);
        assertTrue(retained(ownership, death, "one", from).isEmpty());
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(unchanged, Files.getLastModifiedTime(file));
        assertTrue(retained(ownership, death, "two frozen", from).isEmpty());
        assertFalse(java.util.Arrays.equals(before, Files.readAllBytes(file)));
        assertFalse(StudioOwnership.read(file).containsKey(project.song().id() + ".state"));
        byte[] after = Files.readAllBytes(file); Files.setLastModifiedTime(file, unchanged);
        assertTrue(retained(ownership, death, "two frozen", from).isEmpty());
        assertArrayEquals(after, Files.readAllBytes(file));
        assertEquals(unchanged, Files.getLastModifiedTime(file));
        assertTrue(Files.exists(store.projectFile(from.character(), project.song().id())));
        assertFalse(Files.exists(store.projectFile(to.character(), project.song().id())));
    }

    @Test void decisionGuardsPrecedeEveryPropertyPublicationAndSourceRemoval() throws Exception {
        StudioStore store = store(); Project project = project(); store.saveOwned(from, project);
        StudioOwnership ownership = new StudioOwnership(store); UUID death = UUID.randomUUID();
        AtomicInteger checks = new AtomicInteger();
        assertThrows(IOException.class, () -> transfer(ownership,death, "revision1", from, to, () -> checks.incrementAndGet() < 3));
        assertTrue(Files.exists(store.projectFile(from.character(), project.song().id())));
        assertTrue(Files.exists(store.projectFile(to.character(), project.song().id())));
        assertThrows(StudioOwnership.Conflict.class, () -> ownership.requireWritable(from.character()));
        assertThrows(StudioOwnership.Conflict.class, () -> ownership.requireWritable(to.character()));
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,death, "revision2", from, to, () -> true));
        assertThrows(StudioOwnership.Conflict.class, () -> retained(ownership,death, "revision2 frozen", from));
        assertEquals(List.of(project.song().id().toString()), transfer(ownership,death, "revision1", from, to, () -> true));
        ownership.requireWritable(from.character()); ownership.requireWritable(to.character());
    }

    @Test void anUntouchedPlanCanBeReplacedButPublishedOutputCannotFollowAnObsoleteRecipient() throws Exception {
        StudioStore store = store(); Project project = project(); store.saveOwned(from, project);
        StudioOwnership ownership = new StudioOwnership(store); UUID death = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IOException.class, () -> transfer(ownership,death, "one", from, to, () -> calls.incrementAndGet() == 1));
        StudioIdentity.Owner replacement = new StudioIdentity.Owner(UUID.randomUUID(), UUID.randomUUID());
        assertEquals(List.of(project.song().id().toString()), transfer(ownership,death, "two", from, replacement, () -> true));
        assertFalse(Files.exists(store.projectFile(to.character(), project.song().id())));
        assertTrue(Files.exists(store.projectFile(replacement.character(), project.song().id())));
    }

    @Test void interruptedDestinationWriteAndSourceDeletionRecoverOnlyForTheSameDecision() throws Exception {
        StudioStore store = store(); Project project = project(); store.saveOwned(from, project);
        StudioOwnership ownership = new StudioOwnership(store); UUID death = UUID.randomUUID();
        Path destination = store.projectFile(to.character(), project.song().id());
        try (var disk = mockStatic(DurableFiles.class, CALLS_REAL_METHODS)) {
            disk.when(() -> DurableFiles.write(eq(destination), any(byte[].class), eq(false))).thenAnswer(call -> {
                call.callRealMethod(); throw new IOException("crash after destination publication");
            });
            assertThrows(IOException.class, () -> transfer(ownership,death, "one", from, to, () -> true));
        }
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,death, "two", from, to, () -> true));
        Path source = store.projectFile(from.character(), project.song().id());
        try (var disk = mockStatic(DurableFiles.class, CALLS_REAL_METHODS)) {
            byte[] sourceBytes = Files.readAllBytes(source);
            disk.when(() -> DurableFiles.delete(source)).thenAnswer(call -> { call.callRealMethod(); throw new IOException("crash after source removal"); });
            Files.write(source, sourceBytes);
            assertThrows(IOException.class, () -> transfer(ownership,death, "one", from, to, () -> true));
        }
        assertEquals(List.of(project.song().id().toString()), transfer(ownership,death, "one", from, to, () -> true));
    }

    @Test void conflictsHoldChangedSourcesRecipientIdsAndReappearingOldRecords() throws Exception {
        StudioStore store = store(); Project project = project(); store.saveOwned(from, project);
        StudioOwnership ownership = new StudioOwnership(store); UUID death = UUID.randomUUID();
        Path destination = store.projectFile(to.character(), project.song().id()); Files.createDirectories(destination.getParent());
        Files.writeString(destination, "conflicting project");
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,death, "one", from, to, () -> true));
        Files.delete(destination);
        Path source = store.projectFile(from.character(), project.song().id()); byte[] original = Files.readAllBytes(source);
        Files.writeString(source, "changed");
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,death, "one", from, to, () -> true));
        Files.delete(source);
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,death, "one", from, to, () -> true));
        Files.write(source, original); transfer(ownership,death, "one", from, to, () -> true);
        Files.write(source, original);
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,death, "two", from, to, () -> true));
    }

    @Test void journalIdentityMalformedOwnerUnreadableProjectAndEscapedPathsBlock() throws Exception {
        StudioStore store = store(); StudioOwnership ownership = new StudioOwnership(store); UUID death = UUID.randomUUID();
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,death, "one", from, from, () -> true));
        assertThrows(IOException.class, () -> transfer(ownership,death, "one", from, to, () -> false));
        Project project = project(); store.saveOwned(from, project);
        Path source = store.projectFile(from.character(), project.song().id());
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(source.toFile()); yaml.set("owner-account", "unknown"); yaml.save(source.toFile());
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,death, "one", from, to, () -> true));
        store.save(project); Files.writeString(store.directory(from.character()).resolve("bad.yml"), "bad");
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,death, "one", from, to, () -> true));
        Files.delete(store.directory(from.character()).resolve("bad.yml"));
        yaml.set("owner-account", from.account().toString()); yaml.save(source.toFile());
        AtomicInteger checks = new AtomicInteger();
        assertThrows(IOException.class, () -> transfer(ownership,death, "one", from, to, () -> checks.incrementAndGet() == 1));
        Path file = root.resolve("ownership/estates").resolve(death + ".xml");
        var journal = StudioOwnership.read(file); journal.setProperty("from-character", "broken"); StudioOwnership.write(file, journal);
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,death, "one", from, to, () -> true));
        journal.setProperty("from-character", from.character().toString()); journal.setProperty(project.song().id() + ".target", "../outside.yml"); StudioOwnership.write(file, journal);
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,death, "two", from, to, () -> true));
        journal.setProperty("death", UUID.randomUUID().toString()); StudioOwnership.write(file, journal);
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,death, "one", from, to, () -> true));
        journal.setProperty("format", "99"); StudioOwnership.write(file, journal);
        assertThrows(StudioOwnership.Conflict.class, () -> StudioOwnership.read(file));
    }

    @Test void quotaBelongsToThePublishingAccountEvenWhenItsCharacterInheritsOtherAuthorsWork() throws Exception {
        StudioStore store = store(); Project project = project();
        store.publishEdition(project.song(), to.account(), 1);
        Project another = new Project(new Song(UUID.randomUUID(), account, "Author", "Another", project.song().tracks()), null, 100, false);
        assertThrows(IllegalArgumentException.class, () -> store.publishEdition(another.song(), to.account(), 1));
        assertFalse(Files.exists(root.resolve("editions").resolve(account + ".log")));
        assertTrue(Files.exists(root.resolve("editions").resolve(to.account() + ".log")));
    }
    @Test void existingSelectionsOpaquePathsAndMalformedIdentitiesRemainConserved() throws Exception {
        StudioStore store = store(); Project project = project(); Path source = legacy(project);
        var original = RawYaml.read(Files.readAllBytes(source));
        var wrongFormat = new java.util.LinkedHashMap<>(original); wrongFormat.put("format", 9); Files.write(source, RawYaml.bytes(wrongFormat));
        UUID wrongId = UUID.randomUUID(); Files.write(source.getParent().resolve(wrongId + ".yml"), RawYaml.bytes(original));
        UUID goodId = UUID.randomUUID(); var good = new java.util.LinkedHashMap<>(original); good.put("id", goodId.toString());
        Files.write(source.getParent().resolve(goodId + ".yml"), RawYaml.bytes(good));
        Project existing = project(); store.saveOwned(from, existing);
        Files.writeString(root.resolve("projects/legacy-selected.yml"), "opaque legacy file");
        var ownership = new StudioOwnership(store); ownership.migrate(reviewed()); ownership.migrate(reviewed());
        assertEquals(existing.song().id(), store.project(from.character()).orElseThrow().song().id());
        assertTrue(Files.exists(store.projectFile(from.character(), goodId)));
        assertThrows(StudioOwnership.Conflict.class, () -> ownership.requireEstateReady(from));
        ownership.requireEstateReady(new StudioIdentity.Owner(account, UUID.randomUUID()));
    }

    @Test void missingSelectionAndUnfinishedMappingDelayEstateRatherThanPretendThereAreNoProjects() throws Exception {
        Project project = project(); Path source = legacy(project); Files.delete(source.getParent().resolve("selected"));
        StudioOwnership ownership = new StudioOwnership(store());
        assertThrows(IOException.class, () -> ownership.requireEstateReady(from));
        ownership.migrate(reviewed()); ownership.requireEstateReady(from);
        assertFalse(Files.exists(store().directory(from.character()).resolve("selected")));
    }

    @Test void aFrozenRevisionCancelsOnlyAnUntouchedPlanAndPreservesPreviousReceipts() throws Exception {
        StudioStore store = store(); Project project = project(); store.saveOwned(from, project);
        StudioOwnership ownership = new StudioOwnership(store); UUID death = UUID.randomUUID(); AtomicInteger checks = new AtomicInteger();
        assertThrows(IOException.class, () -> transfer(ownership,death, "one", from, to, () -> checks.incrementAndGet() == 1));
        assertEquals(List.of(), retained(ownership,death, "one", from));
        ownership.requireWritable(UUID.randomUUID());
        assertEquals(List.of(), retained(ownership,death, "two frozen", from)); ownership.requireWritable(from.character());
        checks.set(0);
        assertThrows(IOException.class, () -> transfer(ownership,death, "three", from, to, () -> checks.incrementAndGet() < 3));
        assertThrows(StudioOwnership.Conflict.class, () -> retained(ownership,death, "four frozen", from));
        assertEquals(List.of(project.song().id().toString()), transfer(ownership,death, "three", from, to, () -> true));
        assertThrows(StudioOwnership.Conflict.class, () -> retained(ownership,death, "three", new StudioIdentity.Owner(account, UUID.randomUUID())));
    }

    @Test void corruptManifestsAndMismatchedRecipientPayloadsAreBlockedBeforePublication() throws Exception {
        StudioStore store = store(); Project project = project(); store.saveOwned(from, project);
        StudioOwnership ownership = new StudioOwnership(store); UUID death = UUID.randomUUID(); AtomicInteger checks = new AtomicInteger();
        assertThrows(IOException.class, () -> transfer(ownership,death, "one", from, to, () -> checks.incrementAndGet() == 1));
        Path file = root.resolve("ownership/estates").resolve(death + ".xml"); var valid = StudioOwnership.read(file); String id = project.song().id().toString();
        for (String field : List.of("kind", id + ".state", id + ".before", id + ".before-hash", id + ".after-hash", id + ".to-account", id + ".to-character")) {
            var changed = new java.util.Properties(); changed.putAll(valid); changed.setProperty(field, "invalid"); StudioOwnership.write(file, changed);
            assertThrows(StudioOwnership.Conflict.class, () -> StudioOwnership.read(file), field);
        }
        for (String field : List.of("kind", id + ".source", id + ".target", id + ".after")) {
            var changed = new java.util.Properties(); changed.putAll(valid); changed.remove(field); StudioOwnership.write(file, changed);
            assertThrows(StudioOwnership.Conflict.class, () -> StudioOwnership.read(file), field);
        }
        for (String field : List.of(id + ".to-account", id + ".to-character")) {
            var changed = new java.util.Properties(); changed.putAll(valid); changed.setProperty(field, UUID.randomUUID().toString()); StudioOwnership.write(file, changed);
            assertThrows(StudioOwnership.Conflict.class, () -> StudioOwnership.read(file), field);
        }
        for (String target : List.of(valid.getProperty(id + ".source"), "character-projects/" + UUID.randomUUID() + "/" + id + ".yml")) {
            var changed = new java.util.Properties(); changed.putAll(valid); changed.setProperty(id + ".target", target); StudioOwnership.write(file, changed);
            assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,death, "one", from, to, () -> true));
        }
        var changed = new java.util.Properties(); changed.putAll(valid);
        var after = RawYaml.read(java.util.Base64.getDecoder().decode(changed.getProperty(id + ".after")));
        UUID otherAccount = UUID.randomUUID(); changed.setProperty(id + ".to-account", otherAccount.toString()); after.put("owner-account", otherAccount.toString());
        byte[] bytes = RawYaml.bytes(after); changed.setProperty(id + ".after", java.util.Base64.getEncoder().encodeToString(bytes)); changed.setProperty(id + ".after-hash", StudioOwnership.hash(bytes));
        StudioOwnership.write(file, changed);
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,death, "one", from, to, () -> true));
        Files.writeString(file, "broken XML"); assertThrows(StudioOwnership.Conflict.class, () -> StudioOwnership.read(file));
    }

    @Test void damagedImmutableBackupsAndInvalidSourceAuthoritiesNeverProduceAnotherCopy() throws Exception {
        StudioStore store = store(); Project project = project(); store.saveOwned(from, project);
        Path source = store.projectFile(from.character(), project.song().id()); byte[] original = Files.readAllBytes(source);
        Path backup = root.resolve("ownership/backups").resolve(StudioOwnership.hash(original) + ".yml");
        Files.createDirectories(backup.getParent()); Files.writeString(backup, "tampered backup");
        StudioOwnership ownership = new StudioOwnership(store);
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,UUID.randomUUID(), "one", from, to, () -> true));
        Files.delete(backup);
        for (String field : List.of("id", "owner-character")) {
            var raw = RawYaml.read(original); raw.put(field, UUID.randomUUID().toString()); Files.write(source, RawYaml.bytes(raw));
            assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,UUID.randomUUID(), "one", from, to, () -> true));
        }
        Files.write(source, original);
        UUID death = UUID.randomUUID(); AtomicInteger calls = new AtomicInteger();
        assertThrows(IOException.class, () -> transfer(ownership,death, "one", from, to, () -> calls.incrementAndGet() < 3));
        Files.delete(source); Files.delete(store.projectFile(to.character(), project.song().id()));
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,death, "one", from, to, () -> true));
    }

    @Test void pendingDestinationCannotBeCancelledByAChangedFrozenDecisionEvenBeforeTheReceiptWrites() throws Exception {
        StudioStore store = store(); Project project = project(); store.saveOwned(from, project);
        StudioOwnership ownership = new StudioOwnership(store); UUID death = UUID.randomUUID(); Path target = store.projectFile(to.character(), project.song().id());
        try (var disk = mockStatic(DurableFiles.class, CALLS_REAL_METHODS)) {
            disk.when(() -> DurableFiles.write(eq(target), any(byte[].class), eq(false))).thenAnswer(call -> { call.callRealMethod(); throw new IOException("after publication"); });
            assertThrows(IOException.class, () -> transfer(ownership,death, "one", from, to, () -> true));
        }
        assertThrows(StudioOwnership.Conflict.class, () -> retained(ownership,death, "two frozen", from));
    }

    @Test void strictOwnershipPublicationsRefuseAFileSystemWithoutAtomicMoves() throws Exception {
        Path target = root.resolve("original"); Files.writeString(target, "original");
        try (var disk = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            disk.when(() -> Files.move(any(Path.class), any(Path.class), eq(java.nio.file.StandardCopyOption.ATOMIC_MOVE), eq(java.nio.file.StandardCopyOption.REPLACE_EXISTING)))
                    .thenThrow(new java.nio.file.AtomicMoveNotSupportedException("a", "b", "unsupported"));
            assertThrows(IOException.class, () -> DurableFiles.write(target, "new".getBytes(), true));
        }
        assertEquals("original", Files.readString(target));
    }

    @Test void hashUnavailableFailsBeforeAnyOwnershipCanBePublished() throws Exception {
        try (var digest = mockStatic(java.security.MessageDigest.class)) {
            digest.when(() -> java.security.MessageDigest.getInstance("SHA-256")).thenThrow(new java.security.NoSuchAlgorithmException("unavailable"));
            assertThrows(AssertionError.class, () -> StudioOwnership.hash("unchanged".getBytes()));
        }
    }

    @Test void pendingLegacyPublicationsBlockOnlyTheirPlannedCharacterUntilRetryFinishes() throws Exception {
        StudioStore store = store(); Project project = project(); legacy(project);
        Files.writeString(root.resolve("projects/opaque.state"), "keep this opaque state file");
        StudioOwnership ownership = new StudioOwnership(store); Path target = store.projectFile(from.character(), project.song().id());
        try (var disk = mockStatic(DurableFiles.class, CALLS_REAL_METHODS)) {
            disk.when(() -> DurableFiles.write(eq(target), any(byte[].class), eq(false))).thenThrow(new IOException("temporary disk failure"));
            assertThrows(IOException.class, () -> ownership.migrate(reviewed()));
        }
        assertThrows(IOException.class, () -> ownership.requireEstateReady(from));
        assertThrows(StudioOwnership.Conflict.class, () -> ownership.requireWritable(from.character()));
        ownership.requireWritable(UUID.randomUUID());
        ownership.migrate(reviewed()); ownership.requireWritable(from.character()); ownership.requireEstateReady(from);
        assertEquals("keep this opaque state file", Files.readString(root.resolve("projects/opaque.state")));
    }

    @Test void validButDifferentJournalTestatorsAndUnrecognizedFilesAreHeld() throws Exception {
        StudioStore store = store(); Project project = project(); store.saveOwned(from, project);
        StudioOwnership ownership = new StudioOwnership(store); UUID death = UUID.randomUUID(); AtomicInteger checks = new AtomicInteger();
        assertThrows(IOException.class, () -> transfer(ownership,death, "one", from, to, () -> checks.incrementAndGet() == 1));
        Path file = root.resolve("ownership/estates").resolve(death + ".xml");
        var different = new StudioIdentity.Owner(account, UUID.randomUUID());
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,death, "one", different, to, () -> true));
        assertThrows(StudioOwnership.Conflict.class, () -> retained(ownership,death, "one", different));
        var journal = StudioOwnership.read(file);
        journal.setProperty("death", UUID.randomUUID().toString()); StudioOwnership.write(file, journal);
        assertThrows(StudioOwnership.Conflict.class, () -> retained(ownership,death, "one", from));
        journal.setProperty("death", death.toString());
        journal.setProperty("from-character", UUID.randomUUID().toString()); StudioOwnership.write(file, journal);
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,death, "one", from, to, () -> true));
        assertThrows(StudioOwnership.Conflict.class, () -> retained(ownership,death, "one", from));
        journal.setProperty("death", UUID.randomUUID().toString()); StudioOwnership.write(file, journal);
        assertThrows(StudioOwnership.Conflict.class, () -> retained(ownership,death, "one", from));
        Path bad = store.directory(from.character()).resolve("0-not-a-uuid.yml"); Files.writeString(bad, "unreadable identity");
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,UUID.randomUUID(), "new", from, to, () -> true));
    }

    @Test void wrongAccountOrCharacterAuthorityCannotEditAProjectEvenWhenItsDirectoryIsKnown() throws Exception {
        StudioStore store = store(); Project project = project(); store.saveOwned(from, project);
        Path file = store.projectFile(from.character(), project.song().id()); var valid = RawYaml.read(Files.readAllBytes(file));
        for (String field : List.of("owner-account", "owner-character")) {
            var changed = new java.util.LinkedHashMap<>(valid); changed.put(field, UUID.randomUUID().toString()); Files.write(file, RawYaml.bytes(changed));
            assertThrows(IllegalArgumentException.class, () -> store.requireAuthority(from, project.song().id(), account));
        }
    }

    @Test void immutablePublicationCannotOverwriteADestinationCreatedAfterTheExistenceCheck() throws Exception {
        StudioStore store = store(); Project project = project(); store.saveOwned(from, project);
        Path target = store.projectFile(to.character(), project.song().id());
        byte[] rival = "racing recipient content".getBytes(StandardCharsets.UTF_8);
        UUID death = UUID.randomUUID(); var ownership = new StudioOwnership(store);
        try (var disk = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            disk.when(() -> Files.createLink(eq(target), any(Path.class))).thenAnswer(call -> {
                Files.write(target, rival); return call.callRealMethod();
            });
            assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> transfer(ownership,death, "one", from, to, () -> true));
        }
        assertArrayEquals(rival, Files.readAllBytes(target));
        assertTrue(Files.exists(store.projectFile(from.character(), project.song().id())));
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,death, "one", from, to, () -> true));
        try (var disk = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            disk.when(() -> Files.createLink(any(Path.class), any(Path.class))).thenThrow(new UnsupportedOperationException("unsupported links"));
            assertThrows(IOException.class, () -> DurableFiles.write(root.resolve("unsupported"), rival, false));
        }
        assertFalse(Files.exists(root.resolve("unsupported")));
    }

    @Test void acknowledgedPublicationForcesEveryDirectoryLinkIncludingPreviouslyCreatedParents() throws Exception {
        Path target = root.resolve("new/character-projects/owner/project.yml");
        var forced = new java.util.ArrayList<Path>();
        try (var disk = mockStatic(DurableFiles.class, CALLS_REAL_METHODS)) {
            disk.when(() -> DurableFiles.force(any(Path.class))).thenAnswer(call -> { forced.add(call.getArgument(0)); return call.callRealMethod(); });
            DurableFiles.write(target, "first".getBytes(), false);
            var expected = new java.util.ArrayList<Path>();
            for (Path folder = target.toAbsolutePath().getParent(); folder != null; folder = folder.getParent()) expected.add(folder);
            assertEquals(expected, forced); forced.clear();
            DurableFiles.write(target, "second".getBytes(), true);
            assertEquals(expected, forced);
        }
        assertEquals("second", Files.readString(target));
    }

    @Test void replayRejectsInRootWrongSourcesAndFullReferenceOrPayloadAuthorityChanges() throws Exception {
        StudioStore store = store(); Project project = project(); store.saveOwned(from, project);
        var ownership = new StudioOwnership(store); UUID death = UUID.randomUUID(); var calls = new AtomicInteger();
        assertThrows(IOException.class, () -> transfer(ownership,death, "one", from, to, () -> calls.incrementAndGet() == 1));
        Path file = root.resolve("ownership/estates").resolve(death + ".xml");
        var original = StudioOwnership.read(file); String id = project.song().id().toString();
        Path wrongSource = store.projectFile(UUID.randomUUID(), project.song().id()); Files.createDirectories(wrongSource.getParent());
        byte[] sourceBytes = Files.readAllBytes(store.projectFile(from.character(), project.song().id())); Files.write(wrongSource, sourceBytes);
        var changed = new java.util.Properties(); changed.putAll(original); changed.setProperty(id + ".source", root.relativize(wrongSource).toString());
        StudioOwnership.write(file, changed);
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership,death, "one", from, to, () -> true));
        assertArrayEquals(sourceBytes, Files.readAllBytes(wrongSource));
        for (String field : List.of(id + ".from-account", id + ".from-character")) {
            changed = new java.util.Properties(); changed.putAll(original); changed.setProperty(field, UUID.randomUUID().toString()); StudioOwnership.write(file, changed);
            assertThrows(StudioOwnership.Conflict.class, () -> StudioOwnership.read(file), field);
        }
        for (String part : List.of("before", "after")) for (String field : List.of("id", "owner-account", "owner-character")) {
            changed = new java.util.Properties(); changed.putAll(original);
            var payload = RawYaml.read(java.util.Base64.getDecoder().decode(changed.getProperty(id + "." + part)));
            payload.put(field, UUID.randomUUID().toString()); byte[] bytes = RawYaml.bytes(payload);
            changed.setProperty(id + "." + part, java.util.Base64.getEncoder().encodeToString(bytes)); changed.setProperty(id + "." + part + "-hash", StudioOwnership.hash(bytes));
            StudioOwnership.write(file, changed); assertThrows(StudioOwnership.Conflict.class, () -> StudioOwnership.read(file), part + ":" + field);
        }
        // Reviewed migration uses the account source envelope, never a same-byte file elsewhere.
        Files.delete(file); legacy(project); ownership.migrate(reviewed());
        Path migration = root.resolve("ownership/migration").resolve(account + ".xml"); var valid = StudioOwnership.read(migration);
        for (String field : List.of("from-account", "to-account", "to-character")) {
            changed = new java.util.Properties(); changed.putAll(valid); changed.setProperty(field, UUID.randomUUID().toString()); StudioOwnership.write(migration, changed);
            assertThrows(StudioOwnership.Conflict.class, () -> StudioOwnership.read(migration), field);
        }
    }

    @Test void restoredSourceBlocksWaitingAndFrozenReceiptsAcrossRestartWithoutChangingAnyFile() throws Exception {
        var store = store(); var project = project(); store.saveOwned(from, project); var ownership = new StudioOwnership(store); UUID death = UUID.randomUUID();
        Path source = store.projectFile(from.character(), project.song().id()); byte[] original = Files.readAllBytes(source);
        transfer(ownership, death, "one", from, to, () -> true); Path target = store.projectFile(to.character(), project.song().id()); byte[] moved = Files.readAllBytes(target); Path file = root.resolve("ownership/estates/" + death + ".xml"); byte[] receipt = Files.readAllBytes(file);
        Files.write(source, original);
        for (String label : List.of("two waiting", "three frozen")) {
            assertThrows(StudioOwnership.Conflict.class, () -> retained(new StudioOwnership(store()), death, label, from));
            assertArrayEquals(original, Files.readAllBytes(source)); assertArrayEquals(moved, Files.readAllBytes(target)); assertArrayEquals(receipt, Files.readAllBytes(file));
        }
    }
    @Test void everyHistoricalReceiptBindsDeathFullReferencesRecipientAndSupportedRevision() throws Exception {
        var store = store(); var project = project(); store.saveOwned(from, project); var ownership = new StudioOwnership(store); UUID death = UUID.randomUUID();
        transfer(ownership, death, "one", from, to, () -> true); Path file = root.resolve("ownership/estates/" + death + ".xml"); var valid = StudioOwnership.read(file); String id = project.song().id().toString(); Path target = store.projectFile(to.character(), project.song().id()); byte[] original = Files.readAllBytes(target);
        for (String field : List.of("to-account", "to-character")) {
            var changed = new java.util.Properties(); changed.putAll(valid); String value = UUID.randomUUID().toString(); changed.setProperty(id + "." + field, value);
            var after = RawYaml.read(java.util.Base64.getDecoder().decode(changed.getProperty(id + ".after"))); after.put(field.replace("to-", "owner-"), value); byte[] bytes = RawYaml.bytes(after); changed.setProperty(id + ".after", java.util.Base64.getEncoder().encodeToString(bytes)); changed.setProperty(id + ".after-hash", StudioOwnership.hash(bytes));
            StudioOwnership.write(file, changed); byte[] bad = Files.readAllBytes(file);
            assertThrows(StudioOwnership.Conflict.class, () -> retained(ownership, death, "two frozen", from)); assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership, death, "two", from, to, () -> true)); assertArrayEquals(bad, Files.readAllBytes(file)); assertArrayEquals(original, Files.readAllBytes(target));
        }
        for (String suffix : List.of("-1", "01", "9223372036854775808")) {
            var changed = new java.util.Properties(); changed.putAll(valid); changed.setProperty(id + ".decision", changed.getProperty(id + ".decision").replace(", revision=1]", ", revision=" + suffix + "]")); StudioOwnership.write(file, changed); assertThrows(StudioOwnership.Conflict.class, () -> StudioOwnership.read(file));
        }
        for (String field : List.of("death", "revision", "decision")) {
            var changed = new java.util.Properties(); changed.putAll(valid); changed.setProperty(field.equals("death") ? field : id + "." + field, field.equals("decision") ? "wrong" : field.equals("revision") ? "2" : UUID.randomUUID().toString()); StudioOwnership.write(file, changed); assertThrows(StudioOwnership.Conflict.class, () -> StudioOwnership.read(file));
        }
        var changed = new java.util.Properties(); changed.putAll(valid); changed.setProperty(id + ".decision", changed.getProperty(id + ".decision").replace(from.account().toString(), UUID.randomUUID().toString())); StudioOwnership.write(file, changed); assertThrows(StudioOwnership.Conflict.class, () -> StudioOwnership.read(file));
        StudioOwnership.write(file, valid); assertEquals(List.of(id), retained(new StudioOwnership(store()), death, "two waiting", from));
        UUID misplaced = UUID.randomUUID(); Files.copy(file, root.resolve("ownership/estates/" + misplaced + ".xml"));
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership, misplaced, "two", from, to, () -> true));
        assertThrows(StudioOwnership.Conflict.class, () -> retained(ownership, misplaced, "two frozen", from));
    }
    @Test void hashConsistentOwnershipPlanCannotChangeTitlesTracksOrUnknownNestedNullMetadata() throws Exception {
        var store = store(); var project = project(); store.saveOwned(from, project); Path source = store.projectFile(from.character(), project.song().id());
        var raw = RawYaml.read(Files.readAllBytes(source)); var future = new java.util.LinkedHashMap<String,Object>(); future.put("kept", null); future.put("nested", java.util.Arrays.asList(null, "value")); future.put("binary", new byte[]{0, 1, 2}); raw.put("future", future); Files.write(source, RawYaml.bytes(raw)); byte[] original = Files.readAllBytes(source);
        var ownership = new StudioOwnership(store); UUID death = UUID.randomUUID(); AtomicInteger checks = new AtomicInteger(); assertThrows(IOException.class, () -> transfer(ownership, death, "one", from, to, () -> checks.incrementAndGet() == 1)); Path file = root.resolve("ownership/estates/" + death + ".xml"); var valid = StudioOwnership.read(file); String id = project.song().id().toString();
        for (String field : List.of("title", "tracks", "future")) {
            var changed = new java.util.Properties(); changed.putAll(valid); var after = RawYaml.read(java.util.Base64.getDecoder().decode(changed.getProperty(id + ".after"))); after.put(field, "altered content"); byte[] bytes = RawYaml.bytes(after); changed.setProperty(id + ".after", java.util.Base64.getEncoder().encodeToString(bytes)); changed.setProperty(id + ".after-hash", StudioOwnership.hash(bytes)); StudioOwnership.write(file, changed); byte[] evidence = Files.readAllBytes(file);
            assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership, death, "one", from, to, () -> true)); assertThrows(StudioOwnership.Conflict.class, () -> ownership.requireWritable(from.character())); assertArrayEquals(original, Files.readAllBytes(source)); assertArrayEquals(evidence, Files.readAllBytes(file)); assertFalse(Files.exists(store.projectFile(to.character(), project.song().id())));
        }
        StudioOwnership.write(file, valid); transfer(ownership, death, "one", from, to, () -> true); var moved = RawYaml.read(Files.readAllBytes(store.projectFile(to.character(), project.song().id()))); var movedFuture = (Map<?,?>) moved.get("future"); assertTrue(movedFuture.containsKey("kept")); assertNull(movedFuture.get("kept")); assertEquals(future.get("nested"), movedFuture.get("nested")); assertArrayEquals((byte[]) future.get("binary"), (byte[]) movedFuture.get("binary")); assertEquals(raw.get("title"), moved.get("title")); assertEquals(raw.get("tracks"), moved.get("tracks"));
    }
    @Test void duplicateXmlUnsupportedPayloadSchemasAndHistoricalTargetPathsBlockBeforeAnyMutation() throws Exception {
        var store = store(); var project = project(); store.saveOwned(from, project); var ownership = new StudioOwnership(store); UUID death = UUID.randomUUID(); transfer(ownership, death, "one", from, to, () -> true);
        Path file = root.resolve("ownership/estates/" + death + ".xml"); var valid = StudioOwnership.read(file); String id = project.song().id().toString(); Path target = store.projectFile(to.character(), project.song().id()); byte[] original = Files.readAllBytes(target);
        String xml = Files.readString(file); Files.writeString(file, xml.replace("</properties>", "<entry key=\"format\">1</entry></properties>")); byte[] duplicate = Files.readAllBytes(file);
        assertThrows(StudioOwnership.Conflict.class, () -> retained(ownership, death, "two frozen", from)); assertArrayEquals(duplicate, Files.readAllBytes(file));
        for (String part : List.of("before", "after")) for (Object format : List.of(1, "2", 2.0, true)) {
            var changed = new java.util.Properties(); changed.putAll(valid); var raw = RawYaml.read(java.util.Base64.getDecoder().decode(changed.getProperty(id + "." + part))); raw.put("format", format); byte[] bytes = RawYaml.bytes(raw); changed.setProperty(id + "." + part, java.util.Base64.getEncoder().encodeToString(bytes)); changed.setProperty(id + "." + part + "-hash", StudioOwnership.hash(bytes)); StudioOwnership.write(file, changed); byte[] evidence = Files.readAllBytes(file);
            assertThrows(StudioOwnership.Conflict.class, () -> retained(ownership, death, "two waiting", from)); assertArrayEquals(evidence, Files.readAllBytes(file)); assertArrayEquals(original, Files.readAllBytes(target));
        }
        var changed = new java.util.Properties(); changed.putAll(valid); changed.setProperty(id + ".target", "character-projects/" + UUID.randomUUID() + "/" + id + ".yml"); StudioOwnership.write(file, changed); byte[] evidence = Files.readAllBytes(file);
        assertThrows(StudioOwnership.Conflict.class, () -> retained(ownership, death, "two frozen", from)); assertThrows(StudioOwnership.Conflict.class, () -> ownership.requireWritable(to.character())); assertArrayEquals(evidence, Files.readAllBytes(file)); assertArrayEquals(original, Files.readAllBytes(target));
    }
    @Test void anotherAccountCannotAddressTheSameCharacterFolderAsSourceAndRecipient() throws Exception {
        var store = store(); var project = project(); store.saveOwned(from, project); var ownership = new StudioOwnership(store); UUID death = UUID.randomUUID(); var wrong = new StudioIdentity.Owner(UUID.randomUUID(), from.character()); Path source = store.projectFile(from.character(), project.song().id()); byte[] original = Files.readAllBytes(source);
        assertThrows(StudioOwnership.Conflict.class, () -> transfer(ownership, death, "one", from, wrong, () -> true)); assertArrayEquals(original, Files.readAllBytes(source));
        Path file = root.resolve("ownership/estates/" + death + ".xml"); byte[] evidence = Files.readAllBytes(file); assertThrows(StudioOwnership.Conflict.class, () -> retained(ownership, death, "two frozen", from)); assertArrayEquals(evidence, Files.readAllBytes(file)); assertArrayEquals(original, Files.readAllBytes(source));
    }
}
