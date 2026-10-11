package net.tfminecraft.musicalinstruments.studio;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.RegistryBuilderFactory;
import io.papermc.paper.registry.data.InlinedRegistryBuilderProvider;
import io.papermc.paper.registry.data.dialog.DialogInstancesProvider;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.event.ClickCallback;
import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StudioDialogWorkflowTest {
    private final InstrumentPlugin plugin = mock(InstrumentPlugin.class);
    private final StudioService studio = mock(StudioService.class);
    private final Player player = mock(Player.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final StudioDialogs.Reopen editor = mock(StudioDialogs.Reopen.class);
    private final StudioDialogs.Reopen library = mock(StudioDialogs.Reopen.class);
    private final List<Runnable> queued = new ArrayList<>();
    private StudioDialogs dialogs;
    private Project project;

    @BeforeEach
    void setup() throws Exception {
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(plugin.isEnabled()).thenReturn(true);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            queued.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        project = new Project(new Song(UUID.randomUUID(), player.getUniqueId(), "Author", "Old", List.of()), null, 100, true);
        when(studio.requireProject(player)).thenReturn(project);
        when(studio.project(player, project.song().id())).thenReturn(project);
        StudioStation station = mock(StudioStation.class);
        when(studio.station()).thenReturn(station);
        dialogs = new StudioDialogs(plugin, studio, editor, library);
    }

    private void respond(Harness harness, int button, String title) {
        DialogResponseView view = mock(DialogResponseView.class);
        when(view.getText("title")).thenReturn(title);
        harness.callbacks.get(button).accept(view, player);
        assertFalse(queued.isEmpty());
        queued.removeFirst().run();
    }

    @Test
    void newSongsAndCancellationsReopenTheCorrectInventory() throws Exception {
        try (Harness h = new Harness()) {
            dialogs.create(player, null);
            verify(player).closeInventory();
            verify(player).showDialog(h.dialog);
            respond(h, 0, "  New song  ");
            verify(studio).create(player, "New song", false);
            verify(editor).open(player, null);
        }
        try (Harness h = new Harness()) {
            dialogs.create(player, null);
            respond(h, 1, null);
            verify(library).open(player, null);
        }
    }

    @Test
    void renamingUsesTheLatestTracksAndCancellingDoesNotEdit() throws Exception {
        try (Harness h = new Harness()) {
            dialogs.rename(player, null);
            respond(h, 0, "Changed");
            verify(studio).edit(player, new Project(new Song(project.song().id(), project.song().owner(), "Author", "Changed", List.of()), null, 100, true));
        }
        clearInvocations(studio);
        try (Harness h = new Harness()) {
            dialogs.rename(player, null);
            respond(h, 1, null);
            verify(studio, never()).edit(any(), any());
        }
    }

    @Test
    void missingTitlesReturnAnErrorRatherThanCreatingOrRenamingSongs() throws Exception {
        try (Harness h = new Harness()) {
            dialogs.create(player, null);
            respond(h, 0, null);
            verify(player).sendMessage("Enter a song title");
        }
        try (Harness h = new Harness()) {
            dialogs.rename(player, null);
            respond(h, 0, null);
            verify(player).sendMessage("Please enter a title");
        }
        verify(studio, never()).create(any(), any(), anyBoolean());
        verify(studio, never()).edit(any(), any());
    }

    @Test
    void resettingRequiresTheSameProjectAndDeletingRequiresConfirmation() throws Exception {
        try (Harness h = new Harness()) {
            dialogs.reset(player, null);
            respond(h, 0, null);
            verify(studio).create(player, "Untitled", true);
        }
        try (Harness h = new Harness()) {
            dialogs.reset(player, null);
            respond(h, 1, null);
        }
        try (Harness h = new Harness()) {
            dialogs.reset(player, null);
            when(studio.requireProject(player)).thenReturn(new Project(project.song(), null, 120, true));
            respond(h, 0, null);
            verify(player).sendMessage("The project changed. Open the confirmation again");
            verify(studio, times(1)).create(player, "Untitled", true);
        }
        try (Harness h = new Harness()) {
            dialogs.delete(player, null, project.song().id());
            respond(h, 0, null);
            verify(studio).deleteProject(player, project);
        }
        try (Harness h = new Harness()) {
            dialogs.delete(player, null, project.song().id());
            respond(h, 1, null);
            verify(studio, times(1)).deleteProject(player, project);
        }
    }

    @Test
    void responsesArePrivateSingleUseAndNeverExecuteAfterDisconnectOrDisable() throws Exception {
        try (Harness h = new Harness()) {
            dialogs.create(player, null);
            DialogActionCallback action = h.callbacks.getFirst();
            DialogResponseView view = mock(DialogResponseView.class);
            action.accept(view, mock(Audience.class));
            Player other = mock(Player.class);
            when(other.getUniqueId()).thenReturn(UUID.randomUUID());
            action.accept(view, other);
            assertTrue(queued.isEmpty());
            when(view.getText("title")).thenReturn("Single use");
            action.accept(view, player);
            action.accept(view, player);
            assertEquals(1, queued.size());
            queued.removeFirst().run();
            verify(studio).create(player, "Single use", false);
        }
        try (Harness h = new Harness()) {
            dialogs.create(player, null);
            when(plugin.isEnabled()).thenReturn(false);
            h.callbacks.getFirst().accept(mock(DialogResponseView.class), player);
            assertTrue(queued.isEmpty());
        }
        when(plugin.isEnabled()).thenReturn(true);
        try (Harness h = new Harness()) {
            dialogs.create(player, null);
            when(player.isOnline()).thenReturn(false);
            respond(h, 0, "Offline");
            verify(studio, never()).create(player, "Offline", false);
        }
    }

    @Test
    void failedPersistenceReportsTheFailureWithoutReopeningAnUncommittedSong() throws Exception {
        IOException failure = new IOException("disk full");
        doThrow(failure).when(studio).create(player, "Failed", false);
        try (Harness h = new Harness()) {
            dialogs.create(player, null);
            respond(h, 0, "Failed");
            verify(studio).failure(player, failure);
            verifyNoInteractions(editor);
        }
    }

    private static final class Harness implements AutoCloseable {
        final List<DialogActionCallback> callbacks = new ArrayList<>();
        final Dialog dialog = mock(Dialog.class);
        final MockedStatic<DialogInstancesProvider> instances;
        final MockedStatic<InlinedRegistryBuilderProvider> inlined;
        final MockedStatic<DialogAction> actions;

        @SuppressWarnings({"unchecked", "rawtypes"})
        Harness() {
            DialogInstancesProvider provider = mock(DialogInstancesProvider.class, RETURNS_DEEP_STUBS);
            InlinedRegistryBuilderProvider builders = mock(InlinedRegistryBuilderProvider.class);
            when(builders.createDialog(any())).thenAnswer(call -> {
                RegistryBuilderFactory factory = mock(RegistryBuilderFactory.class);
                when(factory.empty()).thenReturn(mock(DialogRegistryEntry.Builder.class, RETURNS_SELF));
                ((Consumer) call.getArgument(0)).accept(factory);
                return dialog;
            });
            instances = mockStatic(DialogInstancesProvider.class);
            instances.when(DialogInstancesProvider::instance).thenReturn(provider);
            inlined = mockStatic(InlinedRegistryBuilderProvider.class);
            inlined.when(InlinedRegistryBuilderProvider::instance).thenReturn(builders);
            actions = mockStatic(DialogAction.class);
            actions.when(() -> DialogAction.customClick(any(DialogActionCallback.class), any(ClickCallback.Options.class))).thenAnswer(call -> {
                callbacks.add(call.getArgument(0));
                return mock(DialogAction.CustomClickAction.class);
            });
        }

        @Override
        public void close() {
            actions.close();
            inlined.close();
            instances.close();
        }
    }
}
