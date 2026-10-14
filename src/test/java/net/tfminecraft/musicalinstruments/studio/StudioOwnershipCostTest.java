package net.tfminecraft.musicalinstruments.studio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Each change reads the ownership journals once; reconciliation reads them once per pass. */
class StudioOwnershipCostTest {
    @TempDir Path root;

    private StudioOwnership spyOwnership(StudioService studio) throws ReflectiveOperationException {
        var field = StudioService.class.getDeclaredField("ownership"); field.setAccessible(true);
        StudioOwnership spy = spy((StudioOwnership) field.get(studio)); field.set(studio, spy);
        return spy;
    }

    private void drain(StudioOwnershipReviewTest.Rig rig) throws Exception {
        rig.writer().submit(() -> {}).get(3, TimeUnit.SECONDS); rig.tick().run();
    }

    @Test void everyPublicChangeChecksOwnershipOnceAndReadsNeedOnlyTheSession() throws Exception {
        var helper = new StudioOwnershipReviewTest(); helper.root = root;
        UUID account = UUID.randomUUID(), character = UUID.randomUUID();
        var rig = helper.rig(account, character, new TestIdentity());
        var studio = rig.studio(); var player = rig.player();
        try {
            var ownership = spyOwnership(studio);
            studio.create(player, "First", false);
            verify(ownership, times(1)).requireWritable(character); clearInvocations(ownership);
            Project first = studio.requireProject(player);
            studio.project(player); studio.listProjects(player); studio.project(player, first.song().id());
            studio.requireIdle(player); studio.requireSaved(player); studio.requireProjectSpace(player);
            verify(ownership, never()).requireWritable(any());
            studio.edit(player, new Project(first.song(), null, 120, false));
            verify(ownership, times(1)).requireWritable(character); clearInvocations(ownership); drain(rig);
            studio.create(player, "Second", false); Project second = studio.requireProject(player);
            studio.selectProject(player, first.song().id());
            studio.save(player, new Project(studio.requireProject(player).song(), null, 90, false));
            studio.deleteProject(player, second);
            studio.create(player, "Reset", true);
            verify(ownership, times(5)).requireWritable(character); clearInvocations(ownership);
            when(player.getInventory().getStorageContents()).thenReturn(new ItemStack[0]);
            for (var refused : List.<org.junit.jupiter.api.function.Executable>of(() -> studio.preview(player, false),
                    () -> studio.previewTrack(player, 1), () -> studio.publish(player), () -> studio.publishFromInventory(player),
                    () -> studio.publishOnDisc(player, mock(ItemStack.class)))) assertThrows(IllegalArgumentException.class, refused);
            verify(ownership, times(5)).requireWritable(character); clearInvocations(ownership);
            studio.record(player, 1); verify(ownership, times(1)).requireWritable(character);
            assertEquals("Stop the recording with /music stop first",
                    assertThrows(IllegalArgumentException.class, () -> studio.create(player, "Busy", false)).getMessage());
            verify(ownership, times(1)).requireWritable(character);
            studio.stop(player);
        } finally { studio.close(); }
    }

    @Test void aLockStillRefusesEveryChangeBeforeItWritesWhileReadsContinue() throws Exception {
        var helper = new StudioOwnershipReviewTest(); helper.root = root;
        var rig = helper.rig(UUID.randomUUID(), UUID.randomUUID(), new TestIdentity());
        var studio = rig.studio(); var player = rig.player();
        try {
            studio.create(player, "Kept", false); Project kept = studio.requireProject(player);
            Path broken = root.resolve("ownership/estates/" + UUID.randomUUID() + ".xml");
            Files.createDirectories(broken.getParent()); Files.writeString(broken, "not XML");
            var before = helper.snapshot();
            for (var refused : List.<org.junit.jupiter.api.function.Executable>of(() -> studio.create(player, "New", false),
                    () -> studio.edit(player, new Project(kept.song(), null, 140, false)), () -> studio.selectProject(player, kept.song().id()),
                    () -> studio.save(player, kept), () -> studio.deleteProject(player, kept), () -> studio.record(player, 1),
                    () -> studio.preview(player, false), () -> studio.publish(player), () -> studio.token(player))) {
                var error = assertThrows(IllegalArgumentException.class, refused);
                assertTrue(error.getMessage().startsWith("Studio ownership needs recovery"), error.getMessage());
            }
            assertEquals(before, helper.snapshot());
            assertEquals(kept, studio.requireProject(player));
            assertEquals(List.of(kept.song().id()), studio.listProjects(player).stream().map(ProjectSummary::id).toList());
            Files.delete(broken); studio.create(player, "Recovered", false);
        } finally { studio.close(); }
    }

    private UUID project(StudioStore store, StudioIdentity.Owner owner, String title) throws IOException {
        var project = new Project(new Song(UUID.randomUUID(), owner.account(), "Author", title, List.of()), null, 100, false);
        store.saveOwned(owner, project); return project.song().id();
    }

    @Test void aPassReadsOtherJournalsOnceToFindNoOpsAndRereadsThemAllBeforeAnyEffect() throws Exception {
        var helper = new StudioOwnershipReviewTest(); helper.root = root;
        var store = new StudioStore(root); var ownership = new StudioOwnership(store);
        var a = helper.owner(); var b = helper.owner(); var c = helper.owner(); var x = helper.owner(); var y = helper.owner();
        project(store, x, "Earlier"); UUID earlier = UUID.randomUUID();
        ownership.transfer(earlier, StudioOwnershipTest.decision(earlier, "one", x, y), x, y, () -> true);
        Path earlierFile = root.resolve("ownership/estates/" + earlier + ".xml"); byte[] completed = Files.readAllBytes(earlierFile);
        UUID id = project(store, a, "Interrupted"); project(store, b, "Recipient's own");
        var h = helper.owner(); var i = helper.owner(); project(store, h, "Settled"); UUID settled = UUID.randomUUID();
        ownership.transfer(settled, StudioOwnershipTest.decision(settled, "one", h, i), h, i, () -> true);
        ownership.beginPass();
        try {
            UUID first = UUID.randomUUID(); int[] checks = {0};
            // Publication succeeds, then the decision changes before the source is removed.
            assertThrows(IOException.class, () -> ownership.transferDuringPass(first, StudioOwnershipTest.decision(first, "one", a, b), a, b, () -> ++checks[0] <= 2));
            assertTrue(Files.exists(store.projectFile(b.character(), id)));
            var before = helper.snapshot(); UUID second = UUID.randomUUID();
            var error = assertThrows(StudioOwnership.Conflict.class,
                    () -> ownership.transferDuringPass(second, StudioOwnershipTest.decision(second, "one", b, c), b, c, () -> true));
            assertTrue(error.getMessage().contains("pending in another ownership journal"), error.getMessage());
            assertEquals(before, helper.snapshot());
            // An outside edit during the pass: a settlement with nothing to do uses the snapshot,
            // but the first backup, journal write or publication reads every journal again.
            Files.writeString(earlierFile, "not XML");
            assertEquals(1, ownership.transferDuringPass(settled, StudioOwnershipTest.decision(settled, "one", h, i), h, i, () -> true).size());
            UUID third = UUID.randomUUID(); var d = helper.owner(); var e = helper.owner(); project(store, d, "Unrelated");
            var unrelated = helper.snapshot();
            assertThrows(StudioOwnership.Conflict.class, () -> ownership.transferDuringPass(third, StudioOwnershipTest.decision(third, "one", d, e), d, e, () -> true));
            assertEquals(unrelated, helper.snapshot());
            Files.write(earlierFile, completed);
            assertEquals(1, ownership.transferDuringPass(third, StudioOwnershipTest.decision(third, "one", d, e), d, e, () -> true).size());
            Files.writeString(earlierFile, "not XML");
        } finally { ownership.endPass(); }
        var f = helper.owner(); var g = helper.owner(); project(store, f, "Held"); var before = helper.snapshot();
        UUID fourth = UUID.randomUUID();
        assertThrows(StudioOwnership.Conflict.class, () -> ownership.transfer(fourth, StudioOwnershipTest.decision(fourth, "one", f, g), f, g, () -> true));
        ownership.beginPass();
        try {
            for (int attempt = 0; attempt < 2; attempt++)
                assertThrows(StudioOwnership.Conflict.class, () -> ownership.transfer(fourth, StudioOwnershipTest.decision(fourth, "one", f, g), f, g, () -> true));
        } finally { ownership.endPass(); }
        assertEquals(before, helper.snapshot());
        Files.write(earlierFile, completed); Path folder = root.resolve("ownership/estates/unreadable.xml"); Files.createDirectories(folder);
        ownership.beginPass();
        try {
            var failure = assertThrows(IOException.class, () -> ownership.transfer(fourth, StudioOwnershipTest.decision(fourth, "one", f, g), f, g, () -> true));
            assertFalse(failure instanceof StudioOwnership.Conflict);
        } finally { ownership.endPass(); }
        Files.delete(folder);
        assertEquals(1, ownership.transfer(fourth, StudioOwnershipTest.decision(fourth, "one", f, g), f, g, () -> true).size());
    }
}
