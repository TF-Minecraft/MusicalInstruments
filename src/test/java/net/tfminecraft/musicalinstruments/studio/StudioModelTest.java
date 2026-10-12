package net.tfminecraft.musicalinstruments.studio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StudioModelTest {
    private Note note(int tick, String sound) {
        return new Note(tick, "lute", sound, 4, 1);
    }

    private Song song(Track... tracks) {
        return new Song(UUID.randomUUID(), UUID.randomUUID(), "Musician", "Song", List.of(tracks));
    }

    @Test
    void overlappingNotesFromEveryTrackAreDispatchedOnce() {
        Song song = song(new Track(1, 10, 1, false, List.of(note(0, "a"), note(5, "b"))),
                new Track(2, 10, 0.5f, false, List.of(note(0, "c"), note(5, "d"))));
        Timeline timeline = new Timeline(song, -1);
        assertEquals(List.of("a", "c"), timeline.due(0).stream().map(t -> t.note().sound()).toList());
        assertTrue(timeline.due(0).isEmpty());
        assertTrue(timeline.due(4).isEmpty());
        var second = timeline.due(5);
        assertEquals(List.of("b", "d"), second.stream().map(t -> t.note().sound()).toList());
        assertEquals(0.5f, second.get(1).gain());
        assertTrue(timeline.due(9).isEmpty());
    }

    @Test
    void backingExcludesTheTrackBeingReplacedAndMutedTracks() {
        Song song = song(new Track(1, 10, 1, false, List.of(note(0, "old_take"))),
                new Track(2, 10, 1, true, List.of(note(0, "muted"))),
                new Track(3, 10, 0, false, List.of(note(0, "zero_gain"))),
                new Track(4, 10, 1, false, List.of(note(0, "backing"))));
        assertEquals(List.of("backing"), new Timeline(song, 1).due(0).stream().map(t -> t.note().sound()).toList());
    }

    @Test
    void audibilityIgnoresMutedSilentAndZeroVolumeTracks() {
        Track muted = new Track(1, 10, 1, false, List.of(note(0, "a"))).mix(1, true);
        Track silent = new Track(2, 10, 0, false, List.of(note(0, "b")));
        Track quiet = new Track(3, 10, 1, false, List.of(new Note(0, "lute", "c", 0, 1)));
        assertFalse(song(muted, silent, quiet).audible());
        assertFalse(song().audible());
        assertTrue(song(muted, new Track(4, 10, 1, false, List.of(new Note(0, "lute", "d", 0, 1), note(1, "e")))).audible());
    }

    @Test
    void acceptingOrDiscardingATakeLeavesTheOriginalSongUnchanged() {
        Song original = song(new Track(1, 100, 1, false, List.of(note(0, "original"))));
        Track replacement = new Track(1, 50, 0.5f, false, List.of(note(20, "replacement")));
        Project project = new Project(original, replacement, 100, true);
        assertEquals("original", project.discard().song().track(1).notes().getFirst().sound());
        assertEquals("replacement", project.accept().song().track(1).notes().getFirst().sound());
        assertEquals(50, project.accept().song().lengthTicks());
        assertEquals("original", original.track(1).notes().getFirst().sound());
        assertNull(project.accept().pending());
    }

    @Test
    void aTakeWhoseSavedTrackWasRemovedIsKeptAtFullVolume() {
        Song original = song(new Track(1, 100, 0.5f, true, List.of(note(0, "original"))));
        Project project = new Project(original, new Track(1, 50, 1, false, List.of(note(20, "replacement"))), 100, true);
        assertEquals(0.5f, project.pending().gain());
        assertTrue(project.pending().muted());
        Track kept = new Project(original.remove(1), project.pending(), 100, true).accept().song().track(1);
        assertEquals(1, kept.gain());
        assertFalse(kept.muted());
    }

    @Test
    void dataSnapshotsDoNotShareMutableNoteLists() {
        var notes = new ArrayList<>(List.of(note(0, "sample")));
        Track track = new Track(1, 10, 1, false, notes);
        notes.clear();
        assertEquals(1, track.notes().size());
        assertThrows(UnsupportedOperationException.class, () -> track.notes().clear());
    }

    @Test
    void malformedTimingsAndNonfiniteVolumesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> note(-1, "sample"));
        assertThrows(IllegalArgumentException.class, () -> new Note(0, "lute", "sample", Float.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> new Track(1, 10, Float.POSITIVE_INFINITY, false, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Track(1, 10, 1, false, List.of(note(10, "late"))));
        assertThrows(IllegalArgumentException.class, () -> new Track(1, 10, 1, false, List.of(note(5, "a"), note(4, "b"))));
        Track track = new Track(1, 10, 1, false, List.of(note(0, "a")));
        assertThrows(IllegalArgumentException.class, () -> song(track, track));
    }

    @Test
    void totalNoteLimitAppliesBeforeAcceptingAPendingTake() {
        Track full = new Track(1, 1, 1, false, java.util.Collections.nCopies(Song.MAX_NOTES, note(0, "a")));
        Song song = song(full);
        Track extra = new Track(2, 1, 1, false, List.of(note(0, "b")));
        assertThrows(IllegalArgumentException.class, () -> new Project(song, extra, 100, true));
    }
}
