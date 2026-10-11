package net.tfminecraft.musicalinstruments.studio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StudioStoreTest {
    @Test
    void maximumSizeProjectAndReplacementTakeRemainReadable() throws Exception {
        String instrument = "i".repeat(100);
        String sound = "s".repeat(200);
        var notes = java.util.stream.IntStream.range(0, Song.MAX_NOTES)
                .mapToObj(tick -> new Note(tick, instrument, sound, 4, 1)).toList();
        Track saved = new Track(1, Song.MAX_TICKS, 1, false, notes);
        Project project = new Project(new Song(UUID.randomUUID(), UUID.randomUUID(), "Player", "Song", List.of(saved)),
                saved, 100, false);
        StudioStore store = new StudioStore(directory);
        store.save(project);
        long bytes = Files.size(directory.resolve("projects").resolve(project.song().owner() + ".yml"));
        assertTrue(bytes > 8_000_000);
        assertTrue(bytes <= StudioStore.MAX_FILE_BYTES);
        assertEquals(project, store.project(project.song().owner()).orElseThrow());
    }

    @TempDir Path directory;

    private Project project() {
        var old = new Track(1, 100, 0.5f, true, List.of(new Note(15, "lute", "instruments.lute", 4, 1)));
        var pending = new Track(1, 50, 1, false, List.of(new Note(0, "flute", "tfmc:flute", 2, 1.5f)));
        return new Project(new Song(UUID.randomUUID(), UUID.randomUUID(), "Musician", "Música", List.of(old)), pending, 120, false);
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
        Song edition = project.song().edition();
        store.publish(edition);
        store.save(project.accept());
        assertEquals(edition, new StudioStore(directory).song(edition.id()));
        assertThrows(IOException.class, () -> store.publish(edition));
        assertEquals(edition, store.song(edition.id()));
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
    void corruptionAndUnknownFormatsAreReportedWithoutOverwritingData() throws Exception {
        StudioStore store = new StudioStore(directory);
        UUID owner = UUID.randomUUID();
        Path file = directory.resolve("projects").resolve(owner + ".yml");
        for (String invalid : List.of("format: 2\n", "format: 1\ntracks: nonsense\n", "[broken yaml")) {
            Files.writeString(file, invalid);
            assertThrows(IOException.class, () -> store.project(owner));
            assertEquals(invalid, Files.readString(file));
        }
    }

    @Test
    void filenameIdentityMustMatchTheStoredOwnerOrEdition() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project project = project();
        store.save(project);
        UUID anotherOwner = UUID.randomUUID();
        Files.copy(directory.resolve("projects").resolve(project.song().owner() + ".yml"),
                directory.resolve("projects").resolve(anotherOwner + ".yml"));
        assertThrows(IOException.class, () -> store.project(anotherOwner));
        Song edition = project.song().edition();
        store.publish(edition);
        UUID anotherId = UUID.randomUUID();
        Files.copy(directory.resolve("songs").resolve(edition.id() + ".yml"),
                directory.resolve("songs").resolve(anotherId + ".yml"));
        assertThrows(IOException.class, () -> store.song(anotherId));
    }

    @Test
    void switchingProjectsMigratesTheLegacySongWithoutLosingItsPendingTake() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project legacy = project();
        store.save(legacy);
        Path archived = directory.resolve("projects").resolve(legacy.song().owner().toString()).resolve(legacy.song().id() + ".yml");
        Files.delete(archived); // Simulate the original single-project layout, present when the server starts.
        Project second = new Project(new Song(UUID.randomUUID(), legacy.song().owner(), "Musician", "Second", List.of()), null, 100, true);
        new StudioStore(directory).save(second);
        StudioStore reopened = new StudioStore(directory);
        assertEquals(2, reopened.projects(legacy.song().owner()).size());
        assertEquals(legacy, reopened.project(legacy.song().owner(), legacy.song().id()));
        assertEquals(second, reopened.project(legacy.song().owner()).orElseThrow());
        reopened.save(legacy);
        assertEquals(legacy, new StudioStore(directory).project(legacy.song().owner()).orElseThrow());
        assertEquals(second, reopened.project(legacy.song().owner(), second.song().id()));
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
    void laterSavesDoNotReparseTheSelectedFile() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project project = project();
        store.save(project);
        Path selected = directory.resolve("projects").resolve(project.song().owner() + ".yml");
        Files.writeString(selected, "[broken yaml");
        assertThrows(IOException.class, () -> new StudioStore(directory).save(project)); // A new store checks once.
        store.save(project.accept());
        assertEquals(project.accept(), store.project(project.song().owner()).orElseThrow());
    }

    @Test
    void projectLibrariesAndDirectSelectionCannotCrossOwners() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project first = project();
        Project other = project();
        store.save(first);
        store.save(other);
        assertEquals(List.of(first), store.projects(first.song().owner()));
        assertThrows(IllegalArgumentException.class, () -> store.project(first.song().owner(), other.song().id()));
        Path copied = directory.resolve("projects").resolve(first.song().owner().toString()).resolve(other.song().id() + ".yml");
        Files.copy(directory.resolve("projects").resolve(other.song().owner().toString()).resolve(other.song().id() + ".yml"), copied);
        assertThrows(IOException.class, () -> store.projects(first.song().owner()));
    }
}
