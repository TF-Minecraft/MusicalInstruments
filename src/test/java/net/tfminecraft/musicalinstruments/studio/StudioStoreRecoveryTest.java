package net.tfminecraft.musicalinstruments.studio;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StudioStoreRecoveryTest {
    @TempDir Path directory;

    @Test
    void oversizedSerializationCannotReplaceTheLastSavedFile() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project project = project(); store.save(project);
        Path selected = directory.resolve("projects/" + project.song().owner() + "/" + project.song().id() + ".yml");
        byte[] original = Files.readAllBytes(selected);
        YamlConfiguration oversized = mock(YamlConfiguration.class);
        when(oversized.saveToString()).thenReturn("x".repeat(StudioStore.MAX_FILE_BYTES + 1));
        var write = StudioStore.class.getDeclaredMethod("write", Path.class, YamlConfiguration.class, boolean.class);
        write.setAccessible(true);
        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> write.invoke(store, selected, oversized, true));
        assertInstanceOf(IOException.class, failure.getCause());
        assertArrayEquals(original, Files.readAllBytes(selected));
        assertEquals(project, store.project(project.song().owner()).orElseThrow());
    }

    private Project project() {
        return new Project(new Song(UUID.randomUUID(), UUID.randomUUID(), "Author", "Song",
                List.of(new Track(1, 10, 1, false, List.of(new Note(0, "lute", "note", 1, 1))))), null, 100, false);
    }

    @Test
    void removedProjectFilesAreNoLongerAvailable() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project original = project();
        store.save(original);
        UUID owner = original.song().owner();
        assertThrows(IllegalArgumentException.class, () -> store.project(owner, UUID.randomUUID()));
        Files.delete(directory.resolve("projects/" + owner + "/" + original.song().id() + ".yml"));
        assertThrows(IllegalArgumentException.class, () -> store.project(owner, original.song().id()));
        assertTrue(store.project(owner).isEmpty());
        assertTrue(store.summaries(owner).isEmpty());
    }

    @Test
    void malformedArchiveNamesAndMismatchedIdsAreRejected() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project project = project(); store.save(project);
        Path folder = directory.resolve("projects/" + project.song().owner());
        Files.writeString(folder.resolve("ignore.tmp"), "ignored");
        assertEquals(List.of(ProjectSummary.of(project)), store.summaries(project.song().owner()));
        Files.writeString(folder.resolve("invalid.yml"), "invalid");
        assertEquals(List.of(ProjectSummary.of(project)), store.summaries(project.song().owner()));
        Files.delete(folder.resolve("invalid.yml"));
        UUID wrong = UUID.randomUUID();
        Files.copy(folder.resolve(project.song().id() + ".yml"), folder.resolve(wrong + ".yml"));
        assertThrows(IOException.class, () -> store.project(project.song().owner(), wrong));
    }

    @Test
    void deletionOfAnUnselectedProjectKeepsSelectionAndSortsEqualTitlesById() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project first = project();
        Song song = first.song();
        Project second = new Project(new Song(UUID.randomUUID(), song.owner(), song.author(), song.title(), song.tracks()), null, 100, false);
        store.save(first); store.save(second);
        assertEquals(2, store.summaries(song.owner()).size());
        store.delete(song.owner(), first.song().id());
        assertEquals(second, store.project(song.owner()).orElseThrow());
        Files.delete(directory.resolve("projects/" + song.owner() + "/" + StudioStore.SELECTED));
        store.delete(song.owner(), second.song().id());
        assertTrue(store.summaries(song.owner()).isEmpty());
    }

    @Test
    void malformedNumericFieldsAndOversizedSongStructuresCannotLoad() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project project = project(); store.publish(project.song());
        Path file = directory.resolve("songs/" + project.song().id() + ".yml");
        String valid = Files.readString(file);
        for (Object slot : Arrays.asList("one", .5, Double.NaN, Double.POSITIVE_INFINITY)) {
            YamlConfiguration yaml = new YamlConfiguration(); yaml.loadFromString(valid);
            Map<String, Object> track = new LinkedHashMap<>((Map<String, Object>) yaml.getMapList("tracks").getFirst());
            track.put("slot", slot); yaml.set("tracks", List.of(track)); yaml.save(file.toFile());
            assertThrows(IOException.class, () -> store.song(project.song().id()));
        }
        for (Object format : List.of(1, 3)) { // Format 1 was written by 3.3.0 and is not read.
            YamlConfiguration yaml = new YamlConfiguration(); yaml.loadFromString(valid);
            yaml.set("format", format);
            yaml.save(file.toFile()); assertThrows(IOException.class, () -> store.song(project.song().id()));
        }
        YamlConfiguration nine = new YamlConfiguration(); nine.loadFromString(valid);
        nine.set("tracks", Collections.nCopies(9, nine.getMapList("tracks").getFirst()));
        nine.save(file.toFile()); assertThrows(IOException.class, () -> store.song(project.song().id()));
        String tooMany = String.join("\n", Collections.nCopies(Song.MAX_NOTES + 1, "0 0 1 1.0 1.0"));
        List<Object[]> compact = List.of(
                new Object[] {List.of("lute", "note"), "0 0 1 1.0"}, // Missing field.
                new Object[] {List.of("lute", "note"), "0 0 2 1.0 1.0"}, // Unknown name.
                new Object[] {List.of("lute", "note"), "x 0 1 1.0 1.0"},
                new Object[] {List.of("lute", "note"), "0 0 1 1.0 1.0\n-1 0 1 1.0 1.0"}, // Earlier than the previous note.
                new Object[] {List.of("lute", "note"), "0 0 1 1.0 1.0\n\n0 0 1 1.0 1.0"},
                new Object[] {List.of("lute", "note"), "2147483647 0 1 1.0 1.0\n1 0 1 1.0 1.0"},
                new Object[] {List.of("lute", "note"), "0 0 1 NaN 1.0"},
                new Object[] {List.of("lute", "note"), List.of("0 0 1 1.0 1.0")},
                new Object[] {List.of("lute", 7), "0 0 1 1.0 1.0"},
                new Object[] {Collections.nCopies(2 * Song.MAX_NOTES + 1, "lute"), "0 0 1 1.0 1.0"},
                new Object[] {List.of("lute", "note"), tooMany});
        for (Object[] invalid : compact) {
            YamlConfiguration yaml = new YamlConfiguration(); yaml.loadFromString(valid);
            Map<String, Object> track = new LinkedHashMap<>((Map<String, Object>) yaml.getMapList("tracks").getFirst());
            track.put("names", invalid[0]);
            track.put("notes", invalid[1]);
            yaml.set("tracks", List.of(track));
            yaml.save(file.toFile()); assertThrows(IOException.class, () -> store.song(project.song().id()));
        }
        Files.writeString(file, " ".repeat(StudioStore.MAX_FILE_BYTES + 1));
        assertThrows(IOException.class, () -> store.song(project.song().id()));
    }

    @Test
    void filesystemsWithoutAtomicMovesStillPreserveProjectsAndPublishedEditions() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project project = project();
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.move(any(Path.class), any(Path.class), eq(StandardCopyOption.ATOMIC_MOVE), eq(StandardCopyOption.REPLACE_EXISTING)))
                    .thenThrow(new AtomicMoveNotSupportedException("source", "target", "Not supported"));
            files.when(() -> Files.move(any(Path.class), any(Path.class), eq(StandardCopyOption.ATOMIC_MOVE)))
                    .thenThrow(new AtomicMoveNotSupportedException("source", "target", "Not supported"));
            store.save(project); store.publish(project.song());
            assertEquals(project, store.project(project.song().owner()).orElseThrow());
            assertEquals(project.song(), store.song(project.song().id()));
        }
    }
}
