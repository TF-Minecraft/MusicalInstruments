package net.tfminecraft.musicalinstruments.studio;

import org.bukkit.configuration.file.YamlConfiguration;

public record StudioSettings(int tracks, int durationTicks, int notesPerTrack, int notesPerSong,
                             int sessions, int radius, int countInTicks, int tailTicks, int checkpointTicks,
                             int publishCooldownTicks, int editionsPerDay) {
    public static StudioSettings read(YamlConfiguration config) {
        return new StudioSettings(bound(config, "max-tracks", 4, 1, Song.MAX_TRACKS),
                bound(config, "max-duration-seconds", 180, 1, Song.MAX_TICKS / 20) * 20,
                bound(config, "max-notes-per-track", 1500, 1, Song.MAX_NOTES),
                bound(config, "max-notes-per-song", 6000, 1, Song.MAX_NOTES),
                bound(config, "max-sessions", 32, 1, 128),
                bound(config, "jukebox-radius", 32, 1, 128),
                bound(config, "count-in-seconds", 3, 1, 10) * 20,
                bound(config, "sample-tail-seconds", 3, 0, 10) * 20,
                bound(config, "checkpoint-ticks", 200, 20, 1200),
                bound(config, "publish-cooldown-seconds", 30, 0, 3600) * 20,
                bound(config, "max-editions-per-day", 20, 0, 1000));
    }

    private static int bound(YamlConfiguration config, String key, int fallback, int min, int max) {
        return Math.clamp(config.getInt(key, fallback), min, max);
    }
}
