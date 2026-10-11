package net.tfminecraft.musicalinstruments.studio;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudioCommandWorkflowTest {
    private final StudioService studio = mock(StudioService.class);
    private final StudioMenu menu = mock(StudioMenu.class);
    private final Player player = mock(Player.class);
    private final StudioCommand commands = new StudioCommand(studio, menu);
    private Project project;

    @BeforeEach
    void setup() throws Exception {
        when(player.hasPermission("instruments.record")).thenReturn(true);
        when(studio.settings()).thenReturn(new StudioSettings(4, 40, 20, 60, 8, 32, 2, 0, 20));
        var first = new Track(1, 20, 1, false, List.of(new Note(0, "lute", "sample", 1, 1)));
        var second = new Track(2, 20, 1, true, first.notes());
        project = new Project(new Song(UUID.randomUUID(), UUID.randomUUID(), "Author", "Old", List.of(first, second)),
                new Track(3, 20, 1, false, first.notes()), 100, true);
        when(studio.requireProject(player)).thenReturn(project);
    }

    private void run(String... args) {
        assertTrue(commands.onCommand(player, mock(Command.class), "music", args));
    }

    private Project edited() throws IOException {
        var capture = ArgumentCaptor.forClass(Project.class);
        verify(studio).edit(eq(player), capture.capture());
        return capture.getValue();
    }

    @Test
    void volumeKeepsAMutedTrackMuted() throws Exception {
        run("volume", "2", "0.5");
        assertTrue(edited().song().track(2).muted());
    }

    @Test
    void muteCanRestoreAnAudibleTrack() throws Exception {
        run("mute", "2");
        assertFalse(edited().song().track(2).muted());
    }

    @Test
    void creatingRenamingAndOpeningProjectsPreservesTheSongData() throws Exception {
        run("NEW", "  Evening", "Ballad  ");
        verify(studio).create(player, "Evening Ballad", false);
        run("title", "Evening", "Ballad");
        assertEquals("Evening Ballad", edited().song().title());
        assertEquals(project.song().tracks(), edited().song().tracks());
        assertEquals(project.pending(), edited().pending());
        run("open", project.song().id().toString());
        verify(studio).selectProject(player, project.song().id());
        run("projects");
        verify(player).sendMessage("No projects yet. Open /music studio to create a song.");
        when(studio.listProjects(player)).thenReturn(List.of(project));
        run("projects");
        verify(player).sendMessage("Old | " + project.song().id());
    }

    @Test
    void changingVolumeKeepsOtherTracksAndThePendingTake() throws Exception {
        run("volume", "1", "0.5");
        Project saved = edited();
        assertEquals(0.5f, saved.song().track(1).gain());
        assertEquals(project.song().track(2), saved.song().track(2));
        assertEquals(project.pending(), saved.pending());
    }

    @Test
    void mutingAndRemovingTracksOnlyAffectsTheSelectedSlot() throws Exception {
        run("mute", "1");
        assertTrue(edited().song().track(1).muted());
        clearInvocations(studio);
        run("remove", "1");
        assertNull(edited().song().track(1));
        assertEquals(project.song().track(2), edited().song().track(2));
    }

    @Test
    void tempoAndMetronomeAreIndependentFromRecordedNoteTiming() throws Exception {
        run("bpm", "120");
        assertEquals(120, edited().bpm());
        assertEquals(project.song(), edited().song());
        clearInvocations(studio);
        run("metronome", "on");
        assertTrue(edited().metronome());
        clearInvocations(studio);
        run("metronome", "OFF");
        assertFalse(edited().metronome());
    }

    @Test
    void keepDiscardAndPreviewUseTheExpectedTake() throws Exception {
        run("keep");
        assertNull(edited().pending());
        assertEquals(project.pending(), edited().song().track(3));
        clearInvocations(studio);
        run("discard");
        assertEquals(project.song(), edited().song());
        assertNull(edited().pending());
        run("preview");
        run("preview", "TAKE");
        verify(studio).preview(player, false);
        verify(studio).preview(player, true);
        run("record", "2");
        run("stop");
        verify(studio).record(player, 2);
        verify(studio).stop(player);
    }

    @Test
    void discCommandsAndExplicitResetAreForwardedToTheService() throws Exception {
        when(player.hasPermission("instruments.copy")).thenReturn(true);
        run("blank");
        run("publish");
        run("copy");
        run("reset", "CONFIRM");
        verify(studio).makeBlank(player);
        verify(studio).publish(player);
        verify(studio).copy(player);
        verify(studio).create(player, "Untitled", true);
    }

    @Test
    void statusAndHelpDescribeTheSavedTracksAndPendingTake() throws Exception {
        run("status");
        verify(player).sendMessage("Old | 1.0s | 100 BPM");
        verify(player).sendMessage("Track 1: 1 notes, volume 1.0");
        verify(player).sendMessage("Track 2: 1 notes, volume 1.0 (muted)");
        verify(player).sendMessage("Pending take for track 3: /music preview take, keep, or discard.");
        when(studio.requireProject(player)).thenReturn(new Project(project.song(), null, 100, false));
        run("status");
        run();
        when(player.hasPermission("instruments.studio")).thenReturn(true);
        run("unknown");
        verify(player).sendMessage("/music studio opens the studio anywhere (staff)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"new", "title", "record", "open", "volume", "mute", "remove", "bpm", "metronome", "reset"})
    void missingArgumentsNeverModifyAProject(String command) throws Exception {
        run(command);
        verify(studio, never()).edit(any(), any());
        verify(studio, never()).create(any(), any(), anyBoolean());
        verify(player).sendMessage(startsWith("Usage: /music "));
    }

    @Test
    void invalidParametersAreRejectedWithoutWriting() throws Exception {
        run("open", "not-a-uuid");
        run("record", "not-an-integer");
        run("preview", "wrong");
        run("metronome", "maybe");
        run("reset", "no");
        run("volume", "4", "1");
        for (String gain : List.of("garbage", "NaN", "Infinity", "-0.1", "2.1")) run("volume", "1", gain);
        verify(studio, never()).edit(any(), any());
        verify(studio, never()).create(any(), any(), anyBoolean());
        verify(player).sendMessage("Use a project ID from /music projects");
        verify(player).sendMessage("Expected a whole number");
        verify(player, times(5)).sendMessage("Volume must be between 0 and 2");
    }

    @Test
    void consolePermissionsAndStorageFailuresDoNotModifyTheProject() throws Exception {
        CommandSender console = mock(CommandSender.class);
        assertTrue(commands.onCommand(console, mock(Command.class), "music", new String[0]));
        verify(console).sendMessage("Only players can use the recording studio.");
        when(player.hasPermission("instruments.record")).thenReturn(false);
        run("new", "Title");
        run("copy");
        verify(player, times(2)).sendMessage("You don't have permission to do that.");
        when(player.hasPermission("instruments.record")).thenReturn(true);
        IOException failure = new IOException("disk full");
        doThrow(failure).when(studio).record(player, 1);
        run("record", "1");
        verify(studio).failure(player, failure);
    }

    @Test
    void tabCompletionReflectsPermissionsAndMatchesTheLastArgument() {
        Command cmd = mock(Command.class);
        assertFalse(commands.onTabComplete(player, cmd, "music", new String[]{"s"}).contains("studio"));
        when(player.hasPermission("instruments.studio")).thenReturn(true);
        when(player.hasPermission("instruments.copy")).thenReturn(true);
        assertTrue(commands.onTabComplete(player, cmd, "music", new String[]{""}).containsAll(List.of("copy", "studio")));
        for (String action : List.of("record", "volume", "mute", "remove"))
            assertEquals(List.of("1", "2", "3", "4"), commands.onTabComplete(player, cmd, "music", new String[]{action, ""}));
        assertEquals(List.of("take"), commands.onTabComplete(player, cmd, "music", new String[]{"preview", "T"}));
        assertEquals(List.of("on", "off"), commands.onTabComplete(player, cmd, "music", new String[]{"metronome", ""}));
        assertEquals(List.of("confirm"), commands.onTabComplete(player, cmd, "music", new String[]{"reset", ""}));
        assertTrue(commands.onTabComplete(player, cmd, "music", new String[]{"title", ""}).isEmpty());
        assertTrue(commands.onTabComplete(player, cmd, "music", new String[]{"volume", "1", ""}).isEmpty());
        when(player.hasPermission("instruments.record")).thenReturn(false);
        assertEquals(List.of("copy"), commands.onTabComplete(player, cmd, "music", new String[]{""}));
        assertTrue(commands.onTabComplete(player, cmd, "music", new String[]{"record", ""}).isEmpty());
        when(player.hasPermission("instruments.copy")).thenReturn(false);
        assertTrue(commands.onTabComplete(player, cmd, "music", new String[]{""}).isEmpty());
    }
}
