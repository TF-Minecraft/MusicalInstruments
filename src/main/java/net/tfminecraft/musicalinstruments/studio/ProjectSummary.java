package net.tfminecraft.musicalinstruments.studio;

import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/** What the project library shows, without loading a project's notes. */
public record ProjectSummary(UUID id, String title, int tracks, int lengthTicks, int pendingSlot) {
    public static final Comparator<ProjectSummary> ORDER = Comparator.comparing(ProjectSummary::title,
            String.CASE_INSENSITIVE_ORDER).thenComparing(ProjectSummary::id);

    public ProjectSummary {
        Objects.requireNonNull(id);
        Objects.requireNonNull(title);
    }

    public static ProjectSummary of(Project project) {
        Song song = project.song();
        return new ProjectSummary(song.id(), song.title(), song.tracks().size(), song.lengthTicks(),
                project.pending() == null ? 0 : project.pending().slot());
    }
}
