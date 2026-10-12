package net.tfminecraft.musicalinstruments.studio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StudioStoreTest {
    @Test
    void maximumSizeProjectAndReplacementTakeStayCompactAndReadable() throws Exception {
        String instrument = "i".repeat(100);
        String sound = "s".repeat(200);
        var notes = java.util.stream.IntStream.range(0, Song.MAX_NOTES)
                .mapToObj(tick -> new Note(tick, instrument, sound, 4, 1)).toList();
        Track saved = new Track(1, Song.MAX_TICKS, 1, false, notes);
        Project project = new Project(new Song(UUID.randomUUID(), UUID.randomUUID(), "Player", "Song", List.of(saved)),
                saved, 100, false);
        StudioStore store = new StudioStore(directory);
        store.save(project);
        long bytes = Files.size(directory.resolve("projects").resolve(project.song().owner().toString()).resolve(project.song().id() + ".yml"));
        assertTrue(bytes < 600_000, "A YAML list per note needed over 8 MB for this project: " + bytes);
        assertEquals(project, store.project(project.song().owner()).orElseThrow());
    }

    @Test
    void compactNotesStoreEachNameOnceAndTimeSinceThePreviousNote() throws Exception {
        var notes = List.of(new Note(5, "lute", "tfmc:lute.c4", 1, 1), new Note(5, "lute", "tfmc:lute.e4", 1, 1.25f),
                new Note(12, "flute", "tfmc:lute.c4", .5f, 2));
        Song song = new Song(UUID.randomUUID(), UUID.randomUUID(), "Musician", "Song",
                List.of(new Track(1, 20, 1, false, notes), new Track(2, 1, 1, false, List.of())));
        StudioStore store = new StudioStore(directory);
        store.publish(song);
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                directory.resolve("songs").resolve(song.id() + ".yml").toFile());
        var track = yaml.getMapList("tracks").getFirst();
        assertEquals(List.of("lute", "tfmc:lute.c4", "tfmc:lute.e4", "flute"), track.get("names"));
        assertEquals("5 0 1 1.0 1.0\n0 0 2 1.0 1.25\n7 3 1 0.5 2.0", track.get("notes"));
        assertEquals("", yaml.getMapList("tracks").getLast().get("notes"));
        assertEquals(song, store.song(song.id()));
    }

    @TempDir Path directory;

    private Project project() {
        var old = new Track(1, 100, 0.5f, true, List.of(new Note(15, "lute", "instruments.lute", 4, 1)));
        var pending = new Track(1, 50, 1, false, List.of(new Note(0, "flute", "tfmc:flute", 2, 1.5f)));
        return new Project(new Song(UUID.randomUUID(), UUID.randomUUID(), "Musician", "Música", List.of(old)), pending, 120, false);
    }

    @Test
    void savesWriteOneProjectCopyAndASmallSelection() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project first = project();
        Song song = first.song();
        Project second = new Project(new Song(UUID.randomUUID(), song.owner(), song.author(), "Second", List.of()), null, 100, false);
        store.save(first);
        store.save(first.accept());
        store.save(second);
        Path folder = directory.resolve("projects").resolve(song.owner().toString());
        try (var files = Files.list(directory.resolve("projects"))) {
            assertEquals(List.of(folder), files.toList());
        }
        try (var files = Files.list(folder)) {
            assertEquals(java.util.Set.of(first.song().id() + ".yml", second.song().id() + ".yml", StudioStore.SELECTED, StudioStore.INDEX),
                    files.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
        assertEquals(second.song().id().toString(), Files.readString(folder.resolve(StudioStore.SELECTED)));
        assertEquals(second, new StudioStore(directory).project(song.owner()).orElseThrow());
        Files.delete(folder.resolve(second.song().id() + ".yml"));
        assertTrue(new StudioStore(directory).project(song.owner()).isEmpty());
        for (String invalid : List.of("not a project", "x".repeat(65))) {
            Files.writeString(folder.resolve(StudioStore.SELECTED), invalid);
            assertTrue(new StudioStore(directory).project(song.owner()).isEmpty());
        }
    }

    @Test
    void startupRemovesTemporaryFilesLeftByAnInterruptedWrite() throws Exception {
        Project project = project();
        new StudioStore(directory).save(project);
        Path folder = directory.resolve("projects").resolve(project.song().owner().toString());
        Path leftover = Files.writeString(folder.resolve("studio-123.tmp"), "partial");
        Path unrelated = Files.writeString(folder.resolve("notes.tmp"), "kept");
        Path similar = Files.writeString(directory.resolve("songs").resolve("studio-readme.txt"), "kept");
        StudioStore reopened = new StudioStore(directory);
        assertFalse(Files.exists(leftover));
        assertTrue(Files.exists(unrelated));
        assertTrue(Files.exists(similar));
        assertEquals(project, reopened.project(project.song().owner()).orElseThrow());
    }

    @Test
    void projectsAndPendingTakesSurviveANewStoreInstance() throws Exception {
        Project project = project();
        new StudioStore(directory).save(project);
        assertEquals(project, new StudioStore(directory).project(project.song().owner()).orElseThrow());
    }

    @Test
    void publishedEditionRemainsImmutableAfterProjectChanges() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project project = project();
        Song edition = store.publishEdition(project.song(), 0);
        assertNotEquals(project.song().id(), edition.id());
        store.save(project.accept());
        assertEquals(edition, new StudioStore(directory).song(edition.id()));
        assertThrows(IOException.class, () -> store.publish(edition));
        assertEquals(edition, store.song(edition.id()));
    }

    @Test
    void republishingUnchangedMusicReusesItsEditionWithoutAnotherFile() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project project = project();
        Song edition = store.publishEdition(project.song(), 0);
        Song renamed = new Song(project.song().id(), project.song().owner(), "Musician", "Renamed", project.song().tracks());
        assertEquals(edition, store.publishEdition(project.song(), 0));
        assertNotEquals(edition.id(), store.publishEdition(renamed, 0).id());
        try (var files = Files.list(directory.resolve("songs"))) {
            assertEquals(2, files.count());
        }
        Path file = directory.resolve("songs").resolve(edition.id() + ".yml");
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile());
        yaml.set("title", "Tampered");
        yaml.save(file.toFile());
        assertThrows(IOException.class, () -> store.publishEdition(project.song(), 0));
    }

    @Test
    void editionsKeepOnlyWhatPlaybackEmits() throws Exception {
        var audible = new Note(0, "lute", "tfmc:lute", 2, 1);
        var silent = new Note(1, "lute", "tfmc:lute", 0, 1);
        Song song = new Song(UUID.randomUUID(), UUID.randomUUID(), "Musician", "Song", List.of(
                new Track(1, 30, 1, false, List.of(audible, silent)), new Track(2, 40, 1, true, List.of(audible)),
                new Track(3, 50, 0, false, List.of(audible)), new Track(4, 60, .5f, false, List.of(silent))));
        StudioStore store = new StudioStore(directory);
        Song edition = store.publishEdition(song, 0);
        assertEquals(List.of(new Track(1, 30, 1, false, List.of(audible))), edition.tracks());
        assertEquals(edition, store.song(edition.id()));
        // Muting a track that is already silent publishes the same edition.
        Song muted = song.replace(song.track(4).mix(.5f, true));
        assertEquals(edition.id(), store.publishEdition(muted, 0).id());
    }

    @Test
    void eachPlayerCanPublishALimitedNumberOfNewEditionsADay() throws Exception {
        java.time.Clock clock = mock(java.time.Clock.class);
        long start = 1_000_000_000_000L;
        when(clock.millis()).thenReturn(start);
        StudioStore store = new StudioStore(directory, java.util.logging.Logger.getAnonymousLogger(), clock);
        Project project = project();
        Song song = project.song().replace(new Track(2, 10, 1, false, List.of(new Note(0, "lute", "tfmc:lute", 1, 1))));
        Song first = store.publishEdition(song, 2);
        when(clock.millis()).thenReturn(start + java.util.concurrent.TimeUnit.HOURS.toMillis(23));
        store.publishEdition(retitled(song, "Second"), 2);
        assertEquals(first, store.publishEdition(song, 2)); // Republishing does not count.
        var limit = assertThrows(IllegalArgumentException.class, () -> store.publishEdition(retitled(song, "Third"), 2));
        assertTrue(limit.getMessage().endsWith("Try again in 60 minutes"), limit.getMessage());
        when(clock.millis()).thenReturn(start + java.util.concurrent.TimeUnit.HOURS.toMillis(24));
        store.publishEdition(retitled(song, "Third"), 2); // The first publication left the window.
        limit = assertThrows(IllegalArgumentException.class, () -> store.publishEdition(retitled(song, "Fourth"), 2));
        assertTrue(limit.getMessage().endsWith("Try again in 23 hours"), limit.getMessage());
        Path log = directory.resolve("editions").resolve(song.owner() + ".log");
        assertEquals(2, Files.readAllLines(log).size()); // Older entries are dropped.
        Files.writeString(log, "damaged\n" + Files.readString(log));
        assertThrows(IllegalArgumentException.class, () -> store.publishEdition(retitled(song, "Fourth"), 2));
        store.publishEdition(retitled(song, "Fourth"), 0); // 0 disables the limit.
        try (var files = Files.list(directory.resolve("songs"))) {
            assertEquals(4, files.count());
        }
    }

    private Song retitled(Song song, String title) {
        return new Song(song.id(), song.owner(), song.author(), title, song.tracks());
    }

    @Test
    void anEditionFileWithTheSameSizeButOtherContentIsRejected() throws Exception {
        StudioStore store = new StudioStore(directory);
        Song song = project().song().replace(new Track(2, 10, 1, false, List.of(new Note(0, "lute", "tfmc:lute", 1, 1))));
        Song edition = store.publishEdition(song, 0);
        Path file = directory.resolve("songs").resolve(edition.id() + ".yml");
        Files.writeString(file, Files.readString(file).replace("tfmc:lute", "tfmc:lyre"));
        assertThrows(IOException.class, () -> store.publishEdition(song, 0));
    }

    @Test
    void atomicReplacementLeavesNoTemporaryFiles() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project project = project();
        store.save(project);
        store.save(project.accept());
        assertEquals(project.accept(), store.project(project.song().owner()).orElseThrow());
        try (var files = Files.walk(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void unreadableProjectsAreSkippedAndReportedOnceWhileTheRestOfTheLibraryWorks() throws Exception {
        List<String> warnings = new java.util.ArrayList<>();
        java.util.logging.Logger logger = java.util.logging.Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { warnings.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        StudioStore store = new StudioStore(directory, logger);
        Project damaged = project();
        UUID owner = damaged.song().owner();
        Project healthy = new Project(new Song(UUID.randomUUID(), owner, "Musician", "Healthy", List.of()), null, 100, false);
        store.save(damaged);
        store.save(healthy);
        Path folder = directory.resolve("projects").resolve(owner.toString());
        Path broken = folder.resolve(damaged.song().id() + ".yml");
        Files.writeString(broken, "format: 2\nid: [broken");
        Files.writeString(folder.resolve("notes.yml"), "not a project");
        for (int listing = 0; listing < 3; listing++) {
            StudioStore reader = listing == 2 ? new StudioStore(directory, logger) : store; // The index remembers the damage.
            assertEquals(List.of(ProjectSummary.of(healthy)), reader.summaries(owner));
        }
        assertEquals(4, warnings.size(), String.join("\n", warnings)); // Once per file and store.
        assertTrue(warnings.stream().anyMatch(message -> message.contains(broken.toString())));
        assertThrows(IllegalArgumentException.class, () -> store.delete(owner, damaged.song().id()));
        assertThrows(IOException.class, () -> store.project(owner, damaged.song().id()));
        // A damaged active project leaves the player without one, so they can create or open another.
        Files.writeString(folder.resolve(StudioStore.SELECTED), damaged.song().id().toString());
        assertTrue(store.project(owner).isEmpty());
        assertEquals(4, warnings.size()); // That file was already reported.
        store.delete(owner, healthy.song().id());
        assertTrue(store.summaries(owner).isEmpty());
        assertEquals("format: 2\nid: [broken", Files.readString(broken));
        // A selection that is not a project ID is replaced by the next save.
        Files.writeString(folder.resolve(StudioStore.SELECTED), "garbage");
        assertTrue(store.project(owner).isEmpty());
        assertTrue(store.project(owner).isEmpty());
        assertEquals(5, warnings.size());
        store.save(healthy);
        assertEquals(healthy, store.project(owner).orElseThrow());
    }

    @Test
    void invalidUtf8IsDamagedContentAndLeavesTheLibrarySelectionAndPublishingUsable() throws Exception {
        byte[] invalid = { (byte) 0xc3, 0x28 };
        StudioStore store = new StudioStore(directory);
        Project damaged = project();
        UUID owner = damaged.song().owner();
        Project healthy = new Project(new Song(UUID.randomUUID(), owner, "Musician", "Healthy", List.of()), null, 100, false);
        store.save(healthy);
        store.save(damaged);
        Path folder = directory.resolve("projects").resolve(owner.toString());
        Files.write(folder.resolve(damaged.song().id() + ".yml"), invalid);
        assertEquals(List.of(ProjectSummary.of(healthy)), store.summaries(owner));
        assertTrue(store.project(owner).isEmpty()); // The damaged project was the active one.
        assertThrows(IOException.class, () -> store.project(owner, damaged.song().id()));
        // A damaged selection is ignored and replaced by the next save.
        Files.write(folder.resolve(StudioStore.SELECTED), invalid);
        assertTrue(store.project(owner).isEmpty());
        store.save(healthy);
        assertEquals(healthy, store.project(owner).orElseThrow());
        // A damaged publication log loses only its unreadable lines.
        Path log = directory.resolve("editions").resolve(owner + ".log");
        Files.createDirectories(log.getParent());
        Files.write(log, invalid);
        store.publishEdition(healthy.song().replace(new Track(1, 10, 1, false, List.of(new Note(0, "lute", "tfmc:lute", 1, 1)))), 1);
        assertEquals(1, Files.readAllLines(log).size());
    }

    @Test
    void filenameIdentityMustMatchTheStoredOwnerOrEdition() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project project = project();
        store.save(project);
        UUID anotherOwner = UUID.randomUUID();
        Path foreign = directory.resolve("projects").resolve(anotherOwner.toString());
        Files.createDirectories(foreign);
        Files.copy(directory.resolve("projects").resolve(project.song().owner().toString()).resolve(project.song().id() + ".yml"),
                foreign.resolve(project.song().id() + ".yml"));
        Files.writeString(foreign.resolve(StudioStore.SELECTED), project.song().id().toString());
        assertTrue(store.project(anotherOwner).isEmpty());
        assertTrue(store.summaries(anotherOwner).isEmpty());
        Song edition = store.publishEdition(project.song(), 0);
        UUID anotherId = UUID.randomUUID();
        Files.copy(directory.resolve("songs").resolve(edition.id() + ".yml"),
                directory.resolve("songs").resolve(anotherId + ".yml"));
        assertThrows(IOException.class, () -> store.song(anotherId));
    }

    @Test
    void decodedNotesShareRepeatedInstrumentAndSampleNames() throws Exception {
        var notes = List.of(new Note(0, "lute", "tfmc:lute.c4", 1, 1), new Note(1, "lute", "tfmc:lute.c4", 1, 1));
        Project project = new Project(new Song(UUID.randomUUID(), UUID.randomUUID(), "Musician", "Song",
                List.of(new Track(1, 10, 1, false, notes))), null, 100, false);
        new StudioStore(directory).save(project);
        Song song = new StudioStore(directory).project(project.song().owner()).orElseThrow().song();
        Note first = song.track(1).notes().get(0);
        Note second = song.track(1).notes().get(1);
        assertSame(first.instrument(), second.instrument());
        assertSame(first.sound(), second.sound());
    }

    @Test
    void libraryListingUsesSavedSummariesWithoutReparsingProjects() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project project = project();
        store.save(project);
        Path file = directory.resolve("projects").resolve(project.song().owner().toString()).resolve(project.song().id() + ".yml");
        var modified = Files.getLastModifiedTime(file);
        Files.writeString(file, "x".repeat((int) Files.size(file))); // Same size and time: the index entry still applies.
        Files.setLastModifiedTime(file, modified);
        assertEquals(List.of(ProjectSummary.of(project)), new StudioStore(directory).summaries(project.song().owner()));
    }

    @Test
    void filesChangedOutsideSaveAndDamagedIndexEntriesAreReparsed() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project project = project();
        UUID owner = project.song().owner();
        store.save(project);
        Path folder = directory.resolve("projects").resolve(owner.toString());
        Path file = folder.resolve(project.song().id() + ".yml");
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile());
        yaml.set("title", "Edited outside the studio");
        yaml.save(file.toFile());
        assertEquals("Edited outside the studio", store.summaries(owner).getFirst().title());
        Path index = folder.resolve(StudioStore.INDEX);
        String key = project.song().id().toString();
        for (int damage = 0; damage < 2; damage++) {
            var cache = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(index.toFile());
            var entry = new java.util.ArrayList<Object>(cache.getList(key));
            if (damage == 0) entry.set(1, UUID.randomUUID().toString()); // Belongs to another file.
            else entry.subList(1, entry.size()).clear(); // Truncated.
            cache.set(key, entry);
            cache.save(index.toFile());
            assertEquals(project.song().id(), store.summaries(owner).getFirst().id());
        }
    }

    @Test
    void anUnwritableIndexLeavesTheLibraryUsable() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project project = project();
        Path index = directory.resolve("projects").resolve(project.song().owner().toString()).resolve(StudioStore.INDEX);
        Files.createDirectories(index.resolve("blocked")); // A non-empty directory cannot be replaced by the cache file.
        store.save(project);
        assertEquals(List.of(ProjectSummary.of(project)), store.summaries(project.song().owner()));
    }

    @Test
    void deletionRemovesTheSelectionAndRejectsUnknownProjects() throws Exception {
        Project project = project();
        UUID owner = project.song().owner();
        StudioStore store = new StudioStore(directory);
        store.save(project);
        assertThrows(IllegalArgumentException.class, () -> store.delete(owner, UUID.randomUUID()));
        store.delete(owner, project.song().id());
        assertFalse(Files.exists(directory.resolve("projects").resolve(owner.toString()).resolve(StudioStore.SELECTED)));
        assertTrue(store.project(owner).isEmpty());
        assertTrue(store.summaries(owner).isEmpty());
    }

    @Test
    void projectLibrariesAndDirectSelectionCannotCrossOwners() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project first = project();
        Project other = project();
        store.save(first);
        store.save(other);
        assertEquals(List.of(ProjectSummary.of(first)), store.summaries(first.song().owner()));
        assertThrows(IllegalArgumentException.class, () -> store.project(first.song().owner(), other.song().id()));
        Path copied = directory.resolve("projects").resolve(first.song().owner().toString()).resolve(other.song().id() + ".yml");
        Files.copy(directory.resolve("projects").resolve(other.song().owner().toString()).resolve(other.song().id() + ".yml"), copied);
        assertEquals(List.of(ProjectSummary.of(first)), store.summaries(first.song().owner()));
        assertThrows(IOException.class, () -> store.project(first.song().owner(), other.song().id()));
    }
}
