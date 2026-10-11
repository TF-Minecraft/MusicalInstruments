package net.tfminecraft.musicalinstruments.studio;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.*;

class StudioValidationTest {
    private final UUID id = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final Note note = new Note(0, "lute", "sample", 1, 1);

    @Test
    void rejectsMalformedSongIdentityAndTitlesLoadedFromDisk() {
        List<Executable> invalid = List.of(
                () -> new Song(null, owner, "Author", "Title", List.of()),
                () -> new Song(id, null, "Author", "Title", List.of()),
                () -> new Song(id, owner, null, "Title", List.of()),
                () -> new Song(id, owner, " ", "Title", List.of()),
                () -> new Song(id, owner, "a".repeat(65), "Title", List.of()),
                () -> new Song(id, owner, "Author", null, List.of()),
                () -> new Song(id, owner, "Author", " ", List.of()),
                () -> new Song(id, owner, "Author", "a".repeat(65), List.of()),
                () -> new Song(id, owner, "Author", "Bad\nTitle", List.of()),
                () -> new Song(id, owner, "Author", "\u00a7aColored", List.of()),
                () -> new Song(id, owner, "Author", "Title", Collections.nCopies(9, new Track(1, 1, 1, false, List.of()))));
        invalid.forEach(test -> assertThrows(IllegalArgumentException.class, test));
        assertEquals(0, new Song(id, owner, "Author", "Title", List.of()).lengthTicks());
        Track full = new Track(1, 1, 1, false, Collections.nCopies(Song.MAX_NOTES, note));
        assertThrows(IllegalArgumentException.class, () -> new Song(id, owner, "Author", "Title",
                List.of(full, new Track(2, 1, 1, false, List.of(note)))));
    }

    @Test
    void recordingLimitsRejectUnboundedOrNonfiniteSamples() {
        List<Executable> invalid = List.of(
                () -> new Note(Song.MAX_TICKS, "lute", "sample", 1, 1),
                () -> new Note(0, null, "sample", 1, 1),
                () -> new Note(0, "a".repeat(101), "sample", 1, 1),
                () -> new Note(0, "lute", null, 1, 1),
                () -> new Note(0, "lute", "a".repeat(201), 1, 1),
                () -> new Note(0, "lute", "bad sound!", 1, 1),
                () -> new Note(0, "lute", "sample", -1, 1),
                () -> new Note(0, "lute", "sample", 17, 1),
                () -> new Note(0, "lute", "sample", 1, Float.NaN),
                () -> new Note(0, "lute", "sample", 1, 0.49f),
                () -> new Note(0, "lute", "sample", 1, 2.01f),
                () -> new Track(0, 1, 1, false, List.of()),
                () -> new Track(9, 1, 1, false, List.of()),
                () -> new Track(1, 0, 1, false, List.of()),
                () -> new Track(1, Song.MAX_TICKS + 1, 1, false, List.of()),
                () -> new Track(1, 1, -1, false, List.of()),
                () -> new Track(1, 1, 2.1f, false, List.of()),
                () -> new Track(1, 1, 1, false, Collections.nCopies(Song.MAX_NOTES + 1, note)));
        invalid.forEach(test -> assertThrows(IllegalArgumentException.class, test));
    }

    @Test
    void projectsRejectInvalidTempoAndCannotKeepANonexistentTake() {
        Song song = new Song(id, owner, "Author", "Title", List.of());
        assertThrows(IllegalArgumentException.class, () -> new Project(null, null, 100, false));
        assertThrows(IllegalArgumentException.class, () -> new Project(song, null, 39, false));
        assertThrows(IllegalArgumentException.class, () -> new Project(song, null, 241, false));
        assertThrows(IllegalArgumentException.class, () -> new Project(song, null, 100, false).accept());
        Song silent = song.replace(new Track(1, 1, 1, false, List.of(new Note(0, "lute", "sample", 0, 1))));
        assertTrue(new Timeline(silent, -1).due(0).isEmpty());
    }
}
