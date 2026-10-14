package net.tfminecraft.musicalinstruments.studio;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R3 probe: an edit admitted before an unrelated journal becomes unreadable, then a clean shutdown. */
class StudioShutdownHoldProbeTest {
    @TempDir java.nio.file.Path root;

    @Test void admittedEditSurvivesShutdownWhenAnUnrelatedJournalBreaksAfterAdmission() throws Exception {
        var support = new StudioOwnershipReviewTest(); support.root = root;
        var r = support.rig(UUID.randomUUID(), UUID.randomUUID(), new TestIdentity());
        r.studio().create(r.player(), "Before", false);
        Project project = r.studio().requireProject(r.player());
        CountDownLatch gate = new CountDownLatch(1);
        r.writer().submit(() -> { gate.await(5, TimeUnit.SECONDS); return null; });
        Song s = project.song();
        r.studio().edit(r.player(), new Project(new Song(s.id(), s.owner(), s.author(), "Admitted edit", s.tracks()),
                project.pending(), project.bpm(), project.metronome()));
        var bad = root.resolve("ownership/estates/unrelated.xml");
        Files.createDirectories(bad.getParent());
        Files.writeString(bad, "unreadable journal");
        gate.countDown();
        r.studio().close();
        String title = r.store().project(r.token().owner().character(), s.id()).song().title();
        System.out.println("R3-SHUTDOWN-PROBE persisted-title=" + title);
        assertEquals("Admitted edit", title);
    }
}
