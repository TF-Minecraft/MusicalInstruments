package net.tfminecraft.musicalinstruments.studio;

public record Project(Song song, Track pending, int bpm, boolean metronome) {
    public Project {
        if (song == null || bpm < 40 || bpm > 240) {
            throw new IllegalArgumentException("Tempo must be between 40 and 240 BPM");
        }
        if (pending != null) {
            // A take uses its track's current mix. Without a saved track, as after removing it, the
            // take is kept at full volume like one recorded on an empty track.
            Track saved = song.track(pending.slot());
            pending = saved != null ? pending.mix(saved.gain(), saved.muted()) : pending.mix(1, false);
            song.replace(pending); // Validate the prospective mix, including its total note bound.
        }
    }

    public Project accept() {
        if (pending == null) {
            throw new IllegalArgumentException("No take to keep");
        }
        return new Project(song.replace(pending), null, bpm, metronome);
    }

    public Project discard() {
        return new Project(song, null, bpm, metronome);
    }
}
