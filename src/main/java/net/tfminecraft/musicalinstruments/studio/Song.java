package net.tfminecraft.musicalinstruments.studio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Immutable project/published edition. Hard bounds also apply to data loaded from disk. */
public record Song(UUID id, UUID owner, String author, String title, List<Track> tracks) {
    public static final int MAX_TRACKS = 8;
    public static final int MAX_TICKS = 12_000;
    public static final int MAX_NOTES = 12_000;

    public Song {
        tracks = List.copyOf(tracks);
        if (id == null || owner == null || author == null || author.isBlank() || author.length() > 64
                || title == null || title.isBlank() || title.length() > 64
                || title.chars().anyMatch(Character::isISOControl) || title.indexOf('\u00a7') >= 0
                || tracks.size() > MAX_TRACKS) {
            throw new IllegalArgumentException("Title must contain 1-64 ordinary characters");
        }
        var slots = new HashSet<Integer>();
        int count = 0;
        for (Track track : tracks) {
            if (!slots.add(track.slot())) {
                throw new IllegalArgumentException("Duplicate track slot");
            }
            count += track.notes().size();
        }
        if (count > MAX_NOTES) {
            throw new IllegalArgumentException("Song has too many notes");
        }
    }

    public int lengthTicks() {
        return tracks.stream().mapToInt(Track::lengthTicks).max().orElse(0);
    }

    public Track track(int slot) {
        return tracks.stream().filter(track -> track.slot() == slot).findFirst().orElse(null);
    }

    /** Whether playback emits any sound; matches the notes {@link Timeline} schedules. */
    public boolean audible() {
        return tracks.stream().anyMatch(track -> !track.muted() && track.gain() > 0
                && track.notes().stream().anyMatch(note -> note.volume() > 0));
    }

    /** The same song with only what playback emits: muted, silent and empty tracks and silent notes are left out. */
    public Song audibleMix() {
        List<Track> audible = new ArrayList<>();
        for (Track track : tracks) {
            if (track.muted() || track.gain() <= 0) continue;
            List<Note> notes = track.notes().stream().filter(note -> note.volume() > 0).toList();
            if (!notes.isEmpty()) audible.add(new Track(track.slot(), track.lengthTicks(), track.gain(), false, notes));
        }
        return new Song(id, owner, author, title, audible);
    }

    public Song replace(Track track) {
        List<Track> result = new ArrayList<>(tracks);
        result.removeIf(existing -> existing.slot() == track.slot());
        result.add(track);
        result.sort(Comparator.comparingInt(Track::slot));
        return new Song(id, owner, author, title, result);
    }

    public Song remove(int slot) {
        return new Song(id, owner, author, title, tracks.stream().filter(t -> t.slot() != slot).toList());
    }
}
