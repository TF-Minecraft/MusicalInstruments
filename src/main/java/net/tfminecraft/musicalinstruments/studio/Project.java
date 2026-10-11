package net.tfminecraft.musicalinstruments.studio;

public record Project(Song song, Track pending, int bpm, boolean metronome) {
    public Project {
        if (song == null || bpm < 40 || bpm > 240) {
            throw new IllegalArgumentException("Tempo must be between 40 and 240 BPM");
        }
        if (pending != null) {
            Track saved = song.track(pending.slot());
            if (saved != null) pending = pending.mix(saved.gain(), saved.muted());
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
