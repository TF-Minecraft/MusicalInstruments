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

    private Project project() {
        return new Project(new Song(UUID.randomUUID(), UUID.randomUUID(), "Author", "Song",
                List.of(new Track(1, 10, 1, false, List.of(new Note(0, "lute", "note", 1, 1))))), null, 100, false);
    }

    @Test
    void newerArchiveRecoversAnInterruptedSelectionWriteAndLegacySelectionRemainsUsable() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project original = project();
        store.save(original);
        Path selected = directory.resolve("projects/" + original.song().owner() + ".yml");
        Path archive = directory.resolve("projects/" + original.song().owner() + "/" + original.song().id() + ".yml");
        Song song = original.song();
        Project renamed = new Project(new Song(song.id(), song.owner(), song.author(), "Recovered", song.tracks()), null, 100, false);
        store.save(renamed);
        Files.copy(archive, selected, StandardCopyOption.REPLACE_EXISTING);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(selected.toFile());
        yaml.set("title", "Original"); yaml.save(selected.toFile());
        Files.setLastModifiedTime(selected, FileTime.fromMillis(1));
        assertEquals(renamed, store.project(original.song().owner()).orElseThrow());
        Files.delete(archive);
        assertEquals("Original", store.project(original.song().owner(), original.song().id()).song().title());
        assertThrows(IllegalArgumentException.class, () -> store.project(original.song().owner(), UUID.randomUUID()));
        store.delete(original.song().owner(), original.song().id());
        assertTrue(store.projects(original.song().owner()).isEmpty());
    }

    @Test
    void malformedArchiveNamesAndMismatchedIdsAreRejected() throws Exception {
        StudioStore store = new StudioStore(directory);
        Project project = project(); store.save(project);
        Path folder = directory.resolve("projects/" + project.song().owner());
        Files.writeString(folder.resolve("ignore.tmp"), "ignored");
        assertEquals(List.of(project), store.projects(project.song().owner()));
        Files.writeString(folder.resolve("invalid.yml"), "invalid");
        assertThrows(IOException.class, () -> store.projects(project.song().owner()));
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
        assertEquals(2, store.projects(song.owner()).size());
        store.delete(song.owner(), first.song().id());
        assertEquals(second, store.project(song.owner()).orElseThrow());
        Files.delete(directory.resolve("projects/" + song.owner() + ".yml"));
        store.delete(song.owner(), second.song().id());
        assertTrue(store.projects(song.owner()).isEmpty());
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
        for (int mode = 0; mode < 3; mode++) {
            YamlConfiguration yaml = new YamlConfiguration(); yaml.loadFromString(valid);
            Map<String, Object> track = new LinkedHashMap<>((Map<String, Object>) yaml.getMapList("tracks").getFirst());
            if (mode == 0) yaml.set("tracks", Collections.nCopies(9, track));
            else {
                track.put("notes", mode == 1 ? Collections.nCopies(Song.MAX_NOTES + 1, List.of(0, "lute", "note", 1, 1)) : List.of(List.of(0, "lute")));
                yaml.set("tracks", List.of(track));
            }
            yaml.save(file.toFile()); assertThrows(IOException.class, () -> store.song(project.song().id()));
        }
        Files.writeString(file, " ".repeat(8_000_001));
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
