package net.tfminecraft.musicalinstruments.studio;

import net.kyori.adventure.text.Component;
import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StudioMenuTest {
    @Test
    void keepingTakePreservesLatestTrackMix() throws Exception {
        Track saved = project.song().track(1);
        project = new Project(project.song(), saved, 100, false);
        when(studio.requireProject(player)).thenReturn(project);
        click(12, ClickType.LEFT);
        executeClick();
        var edited = ArgumentCaptor.forClass(Project.class);
        verify(studio).edit(eq(player), edited.capture());
        Project mixed = edited.getValue();
        assertTrue(mixed.song().track(1).muted());
        assertTrue(mixed.accept().song().track(1).muted(), "Keeping the take should not undo the mute button");
    }

    @Test
    void takePreviewAndKeepUseNewVolumeAndCanUnmuteAPreviouslyMutedTrack() throws Exception {
        Track saved = project.song().track(1).mix(.5f, true);
        project = new Project(project.song().replace(saved), saved, 100, false);
        when(studio.requireProject(player)).thenReturn(project);
        click(12, ClickType.LEFT); executeClick();
        var edits = ArgumentCaptor.forClass(Project.class);
        verify(studio).edit(eq(player), edits.capture());
        Project unmuted = edits.getValue();
        assertFalse(unmuted.pending().muted());
        when(studio.requireProject(player)).thenReturn(unmuted);
        click(11, ClickType.LEFT); executeClick();
        verify(studio, times(2)).edit(eq(player), edits.capture());
        Project adjusted = edits.getAllValues().getLast();
        assertEquals(.75f, adjusted.pending().gain());
        assertEquals(.75f, adjusted.accept().song().track(1).gain());
        assertFalse(adjusted.accept().song().track(1).muted());
    }

    private final InstrumentPlugin plugin = mock(InstrumentPlugin.class);
    private final StudioService studio = mock(StudioService.class);
    private final StudioDialogs dialogs = mock(StudioDialogs.class);
    private final Player player = mock(Player.class);
    private final Inventory inventory = mock(Inventory.class);
    private final InventoryView view = mock(InventoryView.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final World world = mock(World.class);
    private final Block station = mock(Block.class);
    private final BukkitTask refreshTask = mock(BukkitTask.class);
    private StudioMenu menu;
    private Project project;
    private Runnable queued;

    @BeforeEach
    void setup() throws Exception {
        when(studio.token(any())).thenAnswer(call -> TestIdentity.INSTANCE.current(call.getArgument(0, Player.class)).orElseThrow());
        when(studio.current(any(), any())).thenAnswer(call -> TestIdentity.INSTANCE.current(call.getArgument(0, StudioIdentity.Token.class))
                && call.getArgument(0, StudioIdentity.Token.class).owner().account().equals(call.getArgument(1, Player.class).getUniqueId()));
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        doReturn(List.of(player)).when(server).getOnlinePlayers();
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(10L), eq(10L))).thenReturn(refreshTask);
        when(server.createInventory(any(InventoryHolder.class), anyInt(), any(Component.class))).thenAnswer(call -> {
            when(inventory.getHolder()).thenReturn(call.getArgument(0));
            when(inventory.getSize()).thenReturn(call.getArgument(1));
            return inventory;
        });
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            queued = call.getArgument(1);
            return mock(BukkitTask.class);
        });
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission("instruments.record")).thenReturn(true);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0, 0, 0));
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(inventory);
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        when(world.getBlockAt(0, 0, 0)).thenReturn(station);
        when(world.getBlockAt(any(Location.class))).thenReturn(station);
        when(station.getWorld()).thenReturn(world);
        when(station.getLocation()).thenReturn(new Location(world, 0, 0, 0));
        when(station.getType()).thenReturn(Material.LOOM);
        when(studio.station()).thenReturn(StudioStation.vanilla(Material.LOOM));
        Track saved = new Track(1, 20, 1, false, List.of(new Note(0, "lute", "note", 4, 1)));
        Track pending = new Track(2, 20, 1, false, saved.notes());
        project = new Project(new Song(UUID.randomUUID(), player.getUniqueId(), "Player", "Song", List.of(saved)), pending, 100, false);
        when(studio.project(player)).thenReturn(project);
        when(studio.requireProject(player)).thenReturn(project);
        when(studio.listProjects(player)).thenReturn(List.of(ProjectSummary.of(project)));
        when(studio.settings()).thenReturn(new StudioSettings(4, 40, 20, 60, 8, 32, 2, 0, 2, 0, 0));
        when(studio.activity(player)).thenReturn(new StudioService.Activity(0, 0, 0, 0, false));
        StudioIcons icons = mock(StudioIcons.class);
        when(icons.item(any(), any(), anyList())).thenReturn(mock(ItemStack.class));
        menu = new StudioMenu(plugin, studio, icons, dialogs);
        menu.openProject(player, station);
    }

    private InventoryClickEvent click(int slot, ClickType type) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(slot);
        when(event.getClick()).thenReturn(type);
        menu.click(event);
        return event;
    }

    private void executeClick() {
        assertNotNull(queued);
        Runnable action = queued;
        queued = null;
        action.run();
    }

    @Test
    void trackClickRecordsAndClosesOnlyAfterTheInventoryEvent() throws Exception {
        InventoryClickEvent event = click(19, ClickType.LEFT);
        verify(event).setCancelled(true);
        verify(studio, never()).record(any(), anyInt());
        executeClick();
        verify(studio).record(player, 2);
        verify(player).closeInventory();
    }

    @Test
    void volumeMixingPreservesNotesAndThePendingTake() throws Exception {
        click(11, ClickType.RIGHT);
        executeClick();
        var edited = ArgumentCaptor.forClass(Project.class);
        verify(studio).edit(eq(player), edited.capture());
        assertEquals(0.75f, edited.getValue().song().track(1).gain());
        assertEquals(project.song().track(1).notes(), edited.getValue().song().track(1).notes());
        assertEquals(project.pending(), edited.getValue().pending());
        assertEquals(project.song().id(), edited.getValue().song().id());
    }

    @Test
    void muteOnlyChangesTheSavedTrackMix() throws Exception {
        click(12, ClickType.LEFT);
        executeClick();
        var edited = ArgumentCaptor.forClass(Project.class);
        verify(studio).edit(eq(player), edited.capture());
        assertTrue(edited.getValue().song().track(1).muted());
        assertEquals(project.pending(), edited.getValue().pending());
    }

    @Test
    void publishingRequiresADiscOnTheCursor() throws Exception {
        click(50, ClickType.LEFT);
        assertNull(queued);
        verify(studio, never()).publishFromInventory(player);
        verify(studio, never()).publish(player);
    }

    @Test
    void settingsOpensItsInventoryAndTitleUsesOnlyARenameForm() throws Exception {
        click(4, ClickType.LEFT);
        executeClick();
        assertEquals(27, inventory.getSize());
        click(11, ClickType.LEFT);
        executeClick();
        verify(dialogs).rename(player, new Location(world, 0, 0, 0));
    }

    @Test
    void bottomShiftClicksNumberKeysDoubleClicksAndDragsCannotMoveMenuItems() {
        for (var type : List.of(ClickType.NUMBER_KEY, ClickType.DOUBLE_CLICK, ClickType.DROP)) {
            verify(click(51, type)).setCancelled(true);
        }
        verify(click(54, ClickType.SHIFT_LEFT)).setCancelled(true);
        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getView()).thenReturn(view);
        when(drag.getRawSlots()).thenReturn(java.util.Set.of(9));
        menu.drag(drag);
        verify(drag).setCancelled(true);
        verify(scheduler, never()).runTask(eq(plugin), any(Runnable.class));
    }

    @Test
    void movingAwayBeforeTheDeferredClickCannotRecordOrLoadTheStationChunk() throws Exception {
        click(10, ClickType.LEFT);
        when(player.getLocation()).thenReturn(new Location(world, 100, 0, 0));
        executeClick();
        verify(studio, never()).record(any(), anyInt());
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verify(world, never()).getBlockAt(any(Location.class));
    }

    @Test
    void permissionRevocationPreventsADeferredRecording() throws Exception {
        click(10, ClickType.LEFT);
        when(player.hasPermission("instruments.record")).thenReturn(false);
        executeClick();
        verify(studio, never()).record(any(), anyInt());
    }

    @Test
    void closingThePluginCancelsRefreshAndClosesItsInventory() {
        menu.close();
        verify(refreshTask).cancel();
        verify(player).closeInventory();
    }

    @Test
    void studioOpensAProjectLibraryAndSelectingASongOpensItsEditor() throws Exception {
        menu.open(player, station);
        verify(studio, never()).create(any(), anyString(), anyBoolean());
        click(9, ClickType.LEFT);
        executeClick();
        verify(studio).selectProject(player, project.song().id());
        click(19, ClickType.LEFT);
        executeClick();
        verify(studio).record(player, 2);
    }

    @Test
    void newProjectButtonUsesATitleForm() throws Exception {
        menu.open(player, station);
        click(49, ClickType.LEFT);
        executeClick();
        verify(dialogs).create(player, new Location(world, 0, 0, 0));
    }

    @Test
    void takingActionOnAnotherRowsPendingTakeCannotAcceptIt() throws Exception {
        click(15, ClickType.LEFT); // Keep on track 1, while the pending take belongs to track 2.
        executeClick();
        verify(studio, never()).edit(any(), any());
        verify(player).sendMessage("No pending take on this track");
    }

    @Test
    void eachRowsReviewAndTheGlobalPreviewTargetDifferentAudio() throws Exception {
        click(13, ClickType.LEFT);
        executeClick();
        verify(studio).previewTrack(player, 1);
        click(23, ClickType.LEFT);
        executeClick();
        verify(studio).preview(player, true);
        click(47, ClickType.LEFT);
        executeClick();
        verify(studio).preview(player, false);
        click(48, ClickType.LEFT);
        executeClick();
        verify(studio).stop(player);
    }

    @Test
    void settingsHasTempoMetronomeAndConfirmedResetWithoutDiscActions() throws Exception {
        click(51, ClickType.LEFT);
        executeClick();
        click(13, ClickType.RIGHT);
        executeClick();
        var changed = ArgumentCaptor.forClass(Project.class);
        verify(studio).edit(eq(player), changed.capture());
        assertEquals(95, changed.getValue().bpm());
        click(15, ClickType.LEFT);
        executeClick();
        verify(studio).edit(player, new Project(project.song(), project.pending(), 100, true));
        click(18, ClickType.LEFT);
        executeClick();
        verify(dialogs).reset(player, new Location(world, 0, 0, 0));
        verify(studio, never()).record(any(), anyInt());
        verify(studio, never()).prepareBlankFromInventory(any());
        verify(studio, never()).copyFromInventory(any());
    }

    @Test
    void switchingTheActiveProjectRejectsAnAlreadyQueuedTrackClick() throws Exception {
        click(10, ClickType.LEFT);
        Song other = new Song(UUID.randomUUID(), player.getUniqueId(), "Player", "Other", List.of());
        when(studio.requireProject(player)).thenReturn(new Project(other, null, 100, true));
        executeClick();
        verify(studio, never()).record(any(), anyInt());
    }

    @Test
    void tracksFiveToEightHaveTheirOwnPageAndPreserveTrackNumbers() throws Exception {
        when(studio.settings()).thenReturn(new StudioSettings(8, 40, 20, 60, 8, 32, 2, 0, 2, 0, 0));
        menu.openProject(player, station);
        click(53, ClickType.LEFT);
        executeClick();
        click(10, ClickType.LEFT);
        executeClick();
        verify(studio).record(player, 5);
    }

    @Test
    void libraryFillsAllThirtySixSlotsAndRightClickRequestsDeletion() throws Exception {
        List<Project> projects = java.util.stream.IntStream.range(0, 36).mapToObj(index ->
                new Project(new Song(UUID.randomUUID(), player.getUniqueId(), "Player", "Song " + index, List.of()), null, 100, true)).toList();
        List<ProjectSummary> library = projects.stream().map(ProjectSummary::of).toList();
        when(studio.listProjects(player)).thenReturn(library);
        menu.open(player, station);
        click(9, ClickType.RIGHT);
        executeClick();
        verify(dialogs).delete(player, new Location(world, 0, 0, 0), library.getFirst().id());
        when(studio.requireProject(player)).thenReturn(projects.getLast());
        click(44, ClickType.LEFT);
        executeClick();
        verify(studio).selectProject(player, library.getLast().id());
    }

    @Test
    void keepingTheMatchingRowsTakePreservesTheOtherSavedTracks() throws Exception {
        click(24, ClickType.LEFT);
        executeClick();
        var result = ArgumentCaptor.forClass(Project.class);
        verify(studio).edit(eq(player), result.capture());
        assertNull(result.getValue().pending());
        assertEquals(project.song().track(1), result.getValue().song().track(1));
        assertEquals(project.pending(), result.getValue().song().track(2));
    }
    private ItemStack prepareCursorDisc() {
        DiscItems discs = mock(DiscItems.class);
        when(studio.discs()).thenReturn(discs);
        ItemStack disc = mock(ItemStack.class);
        when(disc.getType()).thenReturn(org.bukkit.Material.MUSIC_DISC_CAT);
        when(disc.getAmount()).thenReturn(1);
        when(disc.clone()).thenReturn(disc);
        when(discs.musicDisc(disc)).thenReturn(true);
        when(player.getItemOnCursor()).thenReturn(disc);
        return disc;
    }

    private void deposit(ItemStack disc) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(50);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        when(event.getCursor()).thenReturn(disc);
        menu.click(event);
        verify(event).setCancelled(true);
    }

    @Test
    void publishingChangesExactlyTheCursorDiscAfterTheEvent() throws Exception {
        ItemStack disc = prepareCursorDisc();
        ItemStack result = mock(ItemStack.class);
        when(studio.publishOnDisc(player, disc)).thenReturn(result);
        deposit(disc);
        verify(studio, never()).publishOnDisc(any(), any());
        executeClick();
        verify(studio).publishOnDisc(player, disc);
        verify(player).setItemOnCursor(result);
        verify(studio, never()).publishFromInventory(any());
    }

    @Test
    void recordedDiscNeedsASecondClickAndChangingProjectInvalidatesConfirmation() throws Exception {
        ItemStack disc = prepareCursorDisc();
        when(studio.discs().recorded(disc)).thenReturn(true);
        deposit(disc);
        executeClick();
        verify(studio, never()).publishOnDisc(any(), any());
        when(studio.requireProject(player)).thenReturn(new Project(project.song(), project.pending(), 105, false));
        deposit(disc);
        executeClick();
        verify(studio, never()).publishOnDisc(any(), any());
        deposit(disc);
        executeClick();
        verify(studio).publishOnDisc(player, disc);
    }

    @Test
    void cursorChangesAndFailedWritesDoNotConsumeDiscs() throws Exception {
        ItemStack disc = prepareCursorDisc();
        deposit(disc);
        when(player.getItemOnCursor()).thenReturn(mock(ItemStack.class));
        executeClick();
        verify(studio, never()).publishOnDisc(any(), any());
        when(player.getItemOnCursor()).thenReturn(disc);
        doThrow(new java.io.IOException("disk full")).when(studio).publishOnDisc(player, disc);
        deposit(disc);
        executeClick();
        verify(player, never()).setItemOnCursor(any());
        verify(studio).failure(eq(player), any(java.io.IOException.class));
    }

    @Test
    void bottomOrdinaryClicksCanPickUpDiscsAndDragAcrossMenuCannotPublish() throws Exception {
        verify(click(54, ClickType.LEFT), never()).setCancelled(true);
        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getView()).thenReturn(view);
        when(drag.getRawSlots()).thenReturn(java.util.Set.of(50, 51));
        menu.drag(drag);
        verify(drag).setCancelled(true);
        verify(studio, never()).publishOnDisc(any(), any());
    }

    @Test
    void staffCanOpenWithoutStationButRevocationBlocksDeferredControls() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> menu.open(player, null));
        when(player.hasPermission("instruments.studio")).thenReturn(true);
        menu.openProject(player, null);
        click(19, ClickType.LEFT);
        when(player.hasPermission("instruments.studio")).thenReturn(false);
        executeClick();
        verify(studio, never()).record(any(), anyInt());
    }

    @Test
    void renderingShowsRecordingListeningMutedTracksAndAnEmptyLibrary() throws Exception {
        when(studio.listProjects(player)).thenReturn(List.of());
        menu.open(player, station);
        Track muted = project.song().track(1).mix(.5f, true);
        project = new Project(project.song().replace(muted), null, 100, true);
        when(studio.requireProject(player)).thenReturn(project);
        when(studio.settings()).thenReturn(new StudioSettings(2, 40, 20, 60, 8, 32, 2, 0, 2, 0, 0));
        when(studio.activity(player)).thenReturn(new StudioService.Activity(1, 2, 1, 0, false));
        menu.openProject(player, station);
        when(studio.activity(player)).thenReturn(new StudioService.Activity(0, 0, 0, 0, true));
        menu.openProject(player, station);
        assertSame(inventory, inventory.getHolder().getInventory());
        verify(inventory, atLeastOnce()).setItem(eq(10), any());
    }

    @Test
    void clicksCannotActForAnotherOwnerOrFromAnotherInventory() throws Exception {
        UUID owner = player.getUniqueId();
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        click(10, ClickType.LEFT); assertNull(queued);
        when(player.getUniqueId()).thenReturn(owner);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(mock(org.bukkit.entity.HumanEntity.class));
        menu.click(event); assertNull(queued);
        click(-1, ClickType.LEFT); assertNull(queued);
        click(10, ClickType.SHIFT_RIGHT); executeClick();
        verify(studio).record(player, 1);
        click(10, ClickType.LEFT);
        when(player.isOnline()).thenReturn(false); executeClick();
        when(player.isOnline()).thenReturn(true);
        click(10, ClickType.LEFT);
        when(view.getTopInventory()).thenReturn(mock(Inventory.class)); executeClick();
        verify(studio, times(1)).record(player, 1);
        click(10, ClickType.LEFT); assertNull(queued);
    }

    @Test
    void navigatingBackClosingAndUnassignedControlsHaveNoTrackEffects() throws Exception {
        click(8, ClickType.LEFT); executeClick(); verify(player).closeInventory();
        click(0, ClickType.LEFT); executeClick();
        click(8, ClickType.LEFT); executeClick();
        verify(player, times(1)).closeInventory();
        menu.openProject(player, station);
        click(51, ClickType.LEFT); executeClick();
        click(9, ClickType.LEFT); executeClick();
        click(0, ClickType.LEFT); executeClick();
        for (int raw : new int[]{9, 17, 46, 45, 53, 49}) { click(raw, ClickType.LEFT); executeClick(); }
        when(studio.settings()).thenReturn(new StudioSettings(2, 40, 20, 60, 8, 32, 2, 0, 2, 0, 0));
        click(28, ClickType.LEFT); executeClick();
        verify(studio, never()).record(any(), anyInt());
    }

    @Test
    void volumeResetMissingSavedTracksDiscardAndTempoStepControlsAreValidated() throws Exception {
        click(11, ClickType.LEFT); executeClick();
        verify(studio).edit(player, new Project(project.song().replace(project.song().track(1).mix(1.25f, false)), project.pending(), 100, false));
        click(11, ClickType.SHIFT_LEFT); executeClick();
        verify(studio).edit(player, project);
        click(20, ClickType.LEFT); executeClick();
        verify(player).sendMessage("Record and keep this track first");
        click(25, ClickType.LEFT); executeClick();
        verify(studio).edit(player, project.discard());
        when(studio.requireProject(player)).thenReturn(project.discard());
        click(24, ClickType.LEFT); executeClick();
        verify(player).sendMessage("No pending take on this track");
        click(51, ClickType.LEFT); executeClick();
        click(13, ClickType.SHIFT_LEFT); executeClick();
        verify(studio).edit(player, new Project(project.song(), null, 101, false));
        click(13, ClickType.SHIFT_RIGHT); executeClick();
        verify(studio).edit(player, new Project(project.song(), null, 99, false));
    }

    @Test
    void trackPagingReturnsToThePreviousFourTracks() throws Exception {
        when(studio.settings()).thenReturn(new StudioSettings(8, 40, 20, 60, 8, 32, 2, 0, 2, 0, 0));
        menu.openProject(player, station);
        click(53, ClickType.LEFT); executeClick();
        click(45, ClickType.LEFT); executeClick();
        click(10, ClickType.LEFT); executeClick();
        verify(studio).record(player, 1);
    }

    @Test
    void publicationValidatesCursorAmountTypeProjectAndDeferredSession() throws Exception {
        ItemStack disc = prepareCursorDisc();
        when(disc.getAmount()).thenReturn(0); deposit(disc); assertNull(queued);
        when(disc.getAmount()).thenReturn(1); when(disc.getType()).thenReturn(Material.AIR);
        deposit(disc); assertNull(queued);
        when(disc.getType()).thenReturn(Material.MUSIC_DISC_CAT);
        when(disc.getAmount()).thenReturn(2); deposit(disc); executeClick();
        when(disc.getAmount()).thenReturn(1); when(studio.discs().musicDisc(disc)).thenReturn(false);
        deposit(disc); executeClick();
        when(studio.discs().musicDisc(disc)).thenReturn(true);
        deposit(disc); when(player.isOnline()).thenReturn(false); executeClick();
        when(player.isOnline()).thenReturn(true);
        deposit(disc); when(view.getTopInventory()).thenReturn(mock(Inventory.class)); executeClick();
        when(view.getTopInventory()).thenReturn(inventory);
        deposit(disc);
        Project other = new Project(new Song(UUID.randomUUID(), player.getUniqueId(), "Player", "Other", List.of()), null, 100, false);
        when(studio.requireProject(player)).thenReturn(other);
        executeClick();
        verify(studio, never()).publishOnDisc(any(), any());
        click(50, ClickType.SHIFT_LEFT); assertNull(queued);
    }

    @Test
    void overwriteConfirmationExpiresAndIsBoundToTheDisc() throws Exception {
        ItemStack disc = prepareCursorDisc();
        when(studio.discs().recorded(disc)).thenReturn(true);
        deposit(disc); executeClick();
        Object holder = inventory.getHolder();
        var deadline = holder.getClass().getDeclaredField("overwriteDeadline");
        deadline.setAccessible(true); deadline.setLong(holder, 0);
        deposit(disc); executeClick();
        verify(studio, never()).publishOnDisc(any(), any());
        var overwrite = holder.getClass().getDeclaredField("overwriteDisc");
        overwrite.setAccessible(true); overwrite.set(holder, mock(ItemStack.class));
        deposit(disc); executeClick();
        verify(studio, never()).publishOnDisc(any(), any());
        deposit(disc); executeClick();
        verify(studio).publishOnDisc(player, disc);
    }

    @Test
    void refreshRendersChangesAndClosesInvalidSessions() throws Exception {
        var callbacks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskTimer(eq(plugin), callbacks.capture(), eq(10L), eq(10L));
        Runnable refresh = callbacks.getValue();
        refresh.run();
        when(studio.activity(player)).thenReturn(new StudioService.Activity(0, 1, 0, 0, true));
        refresh.run();
        when(studio.requireProject(player)).thenReturn(new Project(project.song(), null, 105, true)); refresh.run();
        Project other = new Project(new Song(UUID.randomUUID(), player.getUniqueId(), "Player", "Other", List.of()), null, 100, false);
        when(studio.requireProject(player)).thenReturn(other);
        refresh.run(); // project changed: return to library
        refresh.run(); // library does not refresh track activity
        when(player.getUniqueId()).thenReturn(UUID.randomUUID()); refresh.run();
        verify(player).closeInventory();
        when(view.getTopInventory()).thenReturn(mock(Inventory.class)); refresh.run();
        menu.close();
    }

    @Test
    void refreshAndDeferredActionsReportStorageFailureAndRecheckStation() throws Exception {
        var callbacks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskTimer(eq(plugin), callbacks.capture(), eq(10L), eq(10L));
        when(studio.requireProject(player)).thenThrow(new java.io.IOException("Read failed"));
        callbacks.getValue().run();
        click(10, ClickType.LEFT); executeClick();
        verify(studio, times(2)).failure(eq(player), any(java.io.IOException.class));
        when(player.hasPermission("instruments.record")).thenReturn(false);
        callbacks.getValue().run();
        verify(player, times(2)).closeInventory();
        when(view.getTopInventory()).thenReturn(null); callbacks.getValue().run();
        menu.close();
    }

    @Test
    void draggingOneDiscToPublishWorksAndOtherDragsRemainProtected() throws Exception {
        ItemStack disc = prepareCursorDisc();
        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getView()).thenReturn(view);
        when(drag.getRawSlots()).thenReturn(java.util.Set.of(50));
        when(drag.getWhoClicked()).thenReturn(player);
        when(drag.getOldCursor()).thenReturn(disc);
        menu.drag(drag); executeClick(); verify(studio).publishOnDisc(player, disc);
        when(drag.getWhoClicked()).thenReturn(mock(org.bukkit.entity.HumanEntity.class)); menu.drag(drag);
        when(drag.getWhoClicked()).thenReturn(player); when(player.getUniqueId()).thenReturn(UUID.randomUUID()); menu.drag(drag);
        when(drag.getRawSlots()).thenReturn(java.util.Set.of(54)); menu.drag(drag);
        when(view.getTopInventory()).thenReturn(mock(Inventory.class)); menu.drag(drag);
        assertNull(queued);
    }

    @Test
    void settingsCanDisableTheMetronomeAndMixControlsPreserveAMutedTrack() throws Exception {
        project = new Project(project.song().replace(project.song().track(1).mix(.5f, true)), project.pending(), 100, true);
        when(studio.requireProject(player)).thenReturn(project);
        menu.openProject(player, station);
        click(11, ClickType.LEFT); executeClick();
        click(12, ClickType.LEFT); executeClick();
        verify(studio).edit(player, new Project(project.song().replace(project.song().track(1).mix(.5f, false)), project.pending(), 100, true));
        click(51, ClickType.LEFT); executeClick();
        click(15, ClickType.LEFT); executeClick();
        verify(studio).edit(player, new Project(project.song(), project.pending(), 100, false));
        var drag = mock(InventoryDragEvent.class);
        when(drag.getView()).thenReturn(view); when(drag.getRawSlots()).thenReturn(java.util.Set.of(11));
        menu.drag(drag); verify(drag).setCancelled(true);
    }

    @Test
    void rightClickPublishingAndBottomInventoryClicksAreSupported() throws Exception {
        verify(click(54, ClickType.RIGHT), never()).setCancelled(true);
        click(50, ClickType.RIGHT); assertNull(queued);
        click(1, ClickType.LEFT); executeClick();
        Project other = new Project(new Song(UUID.randomUUID(), player.getUniqueId(), "Player", "Other", List.of()), null, 100, false);
        when(studio.requireProject(player)).thenReturn(project, other);
        assertThrows(IllegalArgumentException.class, () -> menu.openProject(player, station));
    }

    @Test void sessionEndClosesOnlyStudioMenusAndDeferredClicksRecheckTheCapturedSession() throws Exception {
        click(11, ClickType.LEFT);
        when(studio.current(any(), eq(player))).thenReturn(false);
        executeClick(); verify(studio, never()).edit(eq(player), any());
        menu.end(player); verify(player).closeInventory();
        when(view.getTopInventory()).thenReturn(mock(Inventory.class));
        menu.end(player); verify(player, times(1)).closeInventory();
    }

    @Test void deferredDiscPublicationDoesNotConsumeTheCursorAfterItsSessionChanges() throws Exception {
        var event = mock(InventoryClickEvent.class); var disc = prepareCursorDisc();
        when(event.getView()).thenReturn(view); when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(50); when(event.getClick()).thenReturn(ClickType.LEFT); when(event.getCursor()).thenReturn(disc);
        menu.click(event);
        when(studio.current(any(), eq(player))).thenReturn(false);
        executeClick(); verify(studio, never()).publishOnDisc(any(), any());
    }

}
