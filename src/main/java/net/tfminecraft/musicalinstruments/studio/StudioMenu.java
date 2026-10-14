package net.tfminecraft.musicalinstruments.studio;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** A bounded project library, horizontal track editor and inventory settings. */
public final class StudioMenu implements Listener {
    private enum Screen { LIBRARY, EDITOR, SETTINGS }
    private final InstrumentPlugin plugin;
    private final StudioService studio;
    private final StudioIcons icons;
    private final StudioDialogs dialogs;
    private final BukkitTask refresher;

    public StudioMenu(InstrumentPlugin plugin, StudioService studio) {
        this(plugin, studio, new StudioIcons(), null);
    }

    StudioMenu(InstrumentPlugin plugin, StudioService studio, StudioIcons icons, StudioDialogs dialogs) {
        this.plugin = plugin;
        this.studio = studio;
        this.icons = icons;
        this.dialogs = dialogs == null ? new StudioDialogs(plugin, studio, this::openProject, this::open) : dialogs;
        refresher = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refresh, 10L, 10L);
    }

    public void open(Player player, Block station) throws IOException { open(player, station, Screen.LIBRARY, 0); }
    public void openProject(Player player, Block station) throws IOException { open(player, station, Screen.EDITOR, 0); }

    private void open(Player player, Block station, Screen screen, int page) throws IOException {
        studio.station().require(player, station);
        StudioIdentity.Token token = studio.token(player);
        UUID projectId = screen == Screen.LIBRARY ? null : studio.requireProject(player).song().id();
        Holder holder = new Holder(token, station == null ? null : station.getLocation(), screen, page, projectId);
        holder.inventory = plugin.getServer().createInventory(holder, screen == Screen.SETTINGS ? 27 : 54,
                Component.text(screen == Screen.LIBRARY ? "\u266b Your song projects" : screen == Screen.SETTINGS
                        ? "\u266b Song settings" : "\u266b Recording Studio", NamedTextColor.DARK_GRAY));
        render(player, holder);
        player.openInventory(holder.inventory);
    }

    private void render(Player player, Holder holder) throws IOException {
        Inventory inventory = holder.inventory;
        int size = holder.screen == Screen.SETTINGS ? 27 : 54;
        ItemStack background = button(holder.screen != Screen.SETTINGS ? Material.LIGHT_GRAY_STAINED_GLASS_PANE
                : Material.BROWN_STAINED_GLASS_PANE, " ", NamedTextColor.DARK_GRAY);
        ItemStack frame = button(holder.screen != Screen.SETTINGS ? Material.BROWN_STAINED_GLASS_PANE
                : Material.LIGHT_GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        for (int index = 0; index < size; index++) inventory.setItem(index, index < 9 || index >= size - 9 ? frame : background);
        holder.choices.clear();
        if (holder.screen == Screen.LIBRARY) { renderLibrary(player, holder); return; }
        inventory.setItem(8, button(Material.BARRIER, "Close", NamedTextColor.GRAY));
        Project project = studio.requireProject(player);
        if (!project.song().id().equals(holder.projectId)) throw new IllegalArgumentException("The active project changed. Open it again from your projects");
        StudioService.Activity activity = studio.activity(player);
        String status = activity.recording() ? "Recording track " + activity.track() + " | " + time(activity.seconds() * 20)
                : activity.previewing() ? "Listening" : "Ready";
        inventory.setItem(0, button(Material.ARROW, holder.screen == Screen.SETTINGS ? "Back to recording" : "All projects", NamedTextColor.GOLD));
        inventory.setItem(4, button(Material.MUSIC_DISC_5, project.song().title(), NamedTextColor.GOLD,
                "By " + project.song().author(), status, project.song().tracks().size() + " saved tracks | " + time(project.song().lengthTicks()),
                "Click for song settings."));
        if (holder.screen == Screen.EDITOR) renderTracks(player, holder, project, activity);
        else renderSettings(holder, project);
        holder.project = project;
        holder.activity = activity;
    }

    private void renderLibrary(Player player, Holder holder) throws IOException {
        List<ProjectSummary> projects = studio.listProjects(player);
        inventoryItem(holder, 4, Material.BOOKSHELF, "Your songs", NamedTextColor.GOLD,
                projects.size() + "/36 projects", "Left-click to open a song.", "Right-click to delete a song after confirmation.");
        for (int index = 0; index < Math.min(projects.size(), StudioService.MAX_PROJECTS); index++) {
            ProjectSummary project = projects.get(index);
            int slot = 9 + index;
            holder.choices.put(slot, project.id());
            inventoryItem(holder, slot, Material.MUSIC_DISC_13, project.title(), NamedTextColor.YELLOW,
                    project.tracks() + " tracks | " + time(project.lengthTicks()),
                    project.pendingSlot() == 0 ? "No pending take" : "Pending take on track " + project.pendingSlot(),
                    "Left-click: open this project.", "Right-click: delete with confirmation.");
        }
        if (projects.isEmpty()) inventoryItem(holder, 22, Material.WRITABLE_BOOK, "Your studio is empty", NamedTextColor.GOLD,
                "Create your first song with the green button below.");
        boolean full = projects.size() >= StudioService.MAX_PROJECTS;
        inventoryItem(holder, 49, full ? Material.REDSTONE_BLOCK : Material.EMERALD_BLOCK,
                full ? "Studio full - delete a song first" : "Create a new song", full ? NamedTextColor.RED : NamedTextColor.GREEN,
                full ? "Right-click an old song to delete it after confirmation." : "Choose its title, then open its recording studio.",
                "Limit: 36 projects. Existing songs are kept until you delete them.");
    }

    private int trackCount(Project project) {
        int saved = project.song().tracks().stream().mapToInt(Track::slot).max().orElse(0);
        return Math.max(studio.settings().tracks(), Math.max(saved, project.pending() == null ? 0 : project.pending().slot()));
    }

    private void renderTracks(Player player, Holder holder, Project project, StudioService.Activity activity) {
        holder.pages = (trackCount(project) + 3) / 4;
        holder.page = Math.clamp(holder.page, 0, holder.pages - 1);
        for (int row = 0; row < 4; row++) {
            int slot = holder.page * 4 + row + 1;
            if (slot > trackCount(project)) break;
            int base = 9 + row * 9;
            Track saved = project.song().track(slot);
            boolean pending = project.pending() != null && project.pending().slot() == slot;
            boolean recording = activity.track() == slot;
            Material material = recording ? Material.RED_CONCRETE : pending ? Material.YELLOW_CONCRETE
                    : saved == null ? Material.GRAY_CONCRETE : Material.LIME_CONCRETE;
            inventoryItem(holder, base + 1, material, "Record track " + slot, NamedTextColor.GOLD,
                    recording ? "RECORDING" : pending ? "NEW TAKE READY" : saved == null ? "EMPTY TRACK" : "SAVED TRACK",
                    saved == null ? "No saved notes yet." : time(saved.lengthTicks()) + " | " + saved.notes().size() + " notes",
                    saved == null ? "" : "Instruments: " + String.join(", ", saved.notes().stream().map(Note::instrument).distinct().toList()),
                    "Click to record; other saved tracks are your backing.");
            float gain = saved == null ? 1 : saved.gain();
            inventoryItem(holder, base + 2, Material.COMPARATOR, "Track " + slot + " volume: " + Math.round(gain * 100) + "%", NamedTextColor.GOLD,
                    "Left: +25% | Right: -25% | Shift: 100%", saved == null ? "Keep a take before mixing." : "Only this track is changed.");
            boolean muted = saved != null && saved.muted();
            inventoryItem(holder, base + 3, muted ? Material.RED_DYE : Material.LIME_DYE,
                    "Track " + slot + (muted ? ": muted" : ": audible"), muted ? NamedTextColor.RED : NamedTextColor.GREEN,
                    "Click to toggle this track's sound.");
            inventoryItem(holder, base + 4, Material.JUKEBOX, "Listen to track " + slot, NamedTextColor.GOLD,
                    "Hear only this saved track.", "Use Listen to full song below to hear the whole song.");
            inventoryItem(holder, base + 5, Material.NOTE_BLOCK, "Listen to take " + slot, pending ? NamedTextColor.YELLOW : NamedTextColor.GRAY,
                    pending ? "Hear this new take with the saved backing tracks." : "No pending take on this track.");
            inventoryItem(holder, base + 6, Material.EMERALD, "Keep take " + slot, pending ? NamedTextColor.GREEN : NamedTextColor.GRAY,
                    pending ? "Replace this track's saved version." : "No pending take on this track.");
            inventoryItem(holder, base + 7, Material.FLINT, "Discard take " + slot, pending ? NamedTextColor.RED : NamedTextColor.GRAY,
                    pending ? "Keep the previous saved version." : "No pending take on this track.");
        }
        if (holder.page > 0) inventoryItem(holder, 45, Material.ARROW, "Previous tracks", NamedTextColor.GOLD);
        inventoryItem(holder, 49, Material.WRITABLE_BOOK, "Recording guide", NamedTextColor.GOLD,
                "Each row controls one track.", "Hold your instrument and click Record.", "Off-hand: hotbar notes. Hand: right-click for the keyboard.",
                "Reopen your project to stop; then listen, keep or discard.", "The bottom bar controls the whole project.");
        inventoryItem(holder, 47, Material.JUKEBOX, "Listen to full song", NamedTextColor.GOLD, "All saved, audible tracks in this project.");
        inventoryItem(holder, 48, Material.REDSTONE_BLOCK, "Stop recording / preview", NamedTextColor.RED,
                "Finish the active take or stop private listening.");
        inventoryItem(holder, 50, Material.MUSIC_DISC_13, "Publish full song", NamedTextColor.GOLD,
                "Pick up one music disc and place it on this button.", "Overwrites that disc with the saved mix.",
                "Recorded discs: click twice to confirm overwriting.", "Review pending takes first.");
        inventoryItem(holder, 51, Material.NETHER_STAR, "Song settings", NamedTextColor.GOLD,
                "Edit the title, tempo and recording metronome.");
        if (holder.page + 1 < holder.pages) inventoryItem(holder, 53, Material.ARROW, "More tracks", NamedTextColor.GOLD);
    }

    private void renderSettings(Holder holder, Project project) {
        inventoryItem(holder, 11, Material.NAME_TAG, "Title: " + project.song().title(), NamedTextColor.GOLD, "Click to rename this project.");
        inventoryItem(holder, 13, Material.CLOCK, "Tempo: " + project.bpm() + " BPM", NamedTextColor.GOLD,
                "Left: +5 BPM | Right: -5 BPM", "Shift: change by 1 BPM", "Controls recording clicks, not recorded note timing.");
        inventoryItem(holder, 15, Material.BELL, "Metronome: " + (project.metronome() ? "ON" : "OFF"), NamedTextColor.YELLOW,
                "Click to hear or silence the metronome while recording.", "Only you hear it; clicks are never recorded.", "The count-in always plays.");
        inventoryItem(holder, 18, Material.LAVA_BUCKET, "Clear this project", NamedTextColor.RED,
                "Requires confirmation; other projects and published discs are kept.");
    }

    private void inventoryItem(Holder holder, int slot, Material material, String title, NamedTextColor color, String... lore) {
        holder.inventory.setItem(slot, button(material, title, color, lore));
    }

    private String time(int ticks) { return String.format(Locale.ROOT, "%02d:%02d", ticks / 1200, ticks / 20 % 60); }
    private ItemStack button(Material material, String title, NamedTextColor color, String... lore) {
        return icons.item(material, Component.text(title, color).decorate(TextDecoration.BOLD),
                Arrays.stream(lore).map(line -> (Component) Component.text(line, NamedTextColor.GRAY)).toList());
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        // Normal inventory clicks let the player pick up and put down the physical disc.
        if (event.getRawSlot() >= holder.inventory.getSize()
                && (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !studio.current(holder.token, player)
                || event.getRawSlot() < 0 || event.getRawSlot() >= holder.inventory.getSize()
                || !(event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT
                || event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT)) return;
        int raw = event.getRawSlot();
        ClickType click = event.getClick();
        if (holder.screen == Screen.EDITOR && raw == 50) {
            if (click == ClickType.LEFT || click == ClickType.RIGHT) deposit(player, holder, event.getCursor());
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !studio.current(holder.token, player) || player.getOpenInventory().getTopInventory() != holder.inventory) return;
            try {
                Block station = studio.station().require(player, holder.station);
                if (raw == 8 && holder.screen != Screen.LIBRARY) { player.closeInventory(); return; }
                if (holder.screen == Screen.LIBRARY) {
                    if (holder.choices.containsKey(raw)) {
                        if (click.isRightClick()) { dialogs.delete(player, holder.station, holder.choices.get(raw)); return; }
                        studio.selectProject(player, holder.choices.get(raw));
                        openProject(player, station);
                    } else if (raw == 49) dialogs.create(player, holder.station);
                    return;
                }
                Project project = studio.requireProject(player);
                if (!project.song().id().equals(holder.projectId)) throw new IllegalArgumentException("The active project changed. Open it again from your projects");
                if (raw == 0) { open(player, station, holder.screen == Screen.SETTINGS ? Screen.EDITOR : Screen.LIBRARY, 0); return; }
                if (raw == 4) { open(player, station, Screen.SETTINGS, 0); return; }
                if (holder.screen == Screen.EDITOR) {
                    if (raw >= 9 && raw < 45) {
                        int track = holder.page * 4 + (raw - 9) / 9 + 1;
                        if (track > trackCount(project)) return;
                        int control = (raw - 9) % 9;
                        Track saved = project.song().track(track);
                        switch (control) {
                            case 1 -> { studio.record(player, track); player.closeInventory(); return; }
                            case 2, 3 -> {
                                if (saved == null) throw new IllegalArgumentException("Record and keep this track first");
                                float gain = control == 3 ? saved.gain() : click.isShiftClick() ? 1
                                        : Math.clamp(saved.gain() + (click.isRightClick() ? -0.25f : 0.25f), 0, 2);
                                studio.edit(player, new Project(project.song().replace(saved.mix(gain, control == 3 ? !saved.muted() : saved.muted())),
                                        project.pending(), project.bpm(), project.metronome()));
                            }
                            case 4 -> studio.previewTrack(player, track);
                            case 5, 6, 7 -> {
                                if (project.pending() == null || project.pending().slot() != track) throw new IllegalArgumentException("No pending take on this track");
                                if (control == 5) studio.preview(player, true);
                                else studio.edit(player, control == 6 ? project.accept() : project.discard());
                            }
                            default -> { return; }
                        }
                    } else switch (raw) {
                        case 45 -> { if (holder.page > 0) open(player, station, Screen.EDITOR, holder.page - 1); return; }
                        case 49 -> { player.sendMessage("Each row controls one track. The bottom bar controls the full project. Hold an instrument and record: off-hand plays with the hotbar, right-click a held instrument for the keyboard. Stop, then review your take."); return; }
                        case 47 -> studio.preview(player, false);
                        case 48 -> studio.stop(player);
                        case 51 -> { open(player, station, Screen.SETTINGS, 0); return; }
                        case 53 -> { if (holder.page + 1 < holder.pages) open(player, station, Screen.EDITOR, holder.page + 1); return; }
                        default -> { return; }
                    }
                } else switch (raw) {
                    case 11 -> { dialogs.rename(player, holder.station); return; }
                    case 13 -> studio.edit(player, new Project(project.song(), project.pending(),
                            Math.clamp(project.bpm() + (click.isRightClick() ? -1 : 1) * (click.isShiftClick() ? 1 : 5), 40, 240), project.metronome()));
                    case 15 -> studio.edit(player, new Project(project.song(), project.pending(), project.bpm(), !project.metronome()));
                    case 18 -> { dialogs.reset(player, holder.station); return; }
                    default -> { return; }
                }
                render(player, holder);
            } catch (IllegalArgumentException ex) { player.sendMessage(ex.getMessage()); }
            catch (IOException ex) { studio.failure(player, ex); }
        });
    }

    private void deposit(Player player, Holder holder, ItemStack cursor) {
        if (cursor == null || cursor.getAmount() < 1 || cursor.getType() == Material.AIR) {
            player.sendMessage("Pick up one music disc from your inventory, then place it on Publish full song.");
            return;
        }
        ItemStack original = cursor.clone();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !studio.current(holder.token, player) || player.getOpenInventory().getTopInventory() != holder.inventory
                    || !original.equals(player.getItemOnCursor())) return;
            try {
                studio.station().require(player, holder.station);
                Project project = studio.requireProject(player);
                if (!project.song().id().equals(holder.projectId)) throw new IllegalArgumentException("The active project changed. Open it again from your projects");
                if (!studio.discs().musicDisc(original) || original.getAmount() != 1) {
                    throw new IllegalArgumentException("Place exactly one music disc on Publish full song");
                }
                if (studio.discs().recorded(original) && (!original.equals(holder.overwriteDisc)
                        || !project.equals(holder.overwriteProject) || System.nanoTime() > holder.overwriteDeadline)) {
                    holder.overwriteDisc = original;
                    holder.overwriteProject = project;
                    holder.overwriteDeadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
                    player.sendMessage("This replaces the recording on this disc. Click Publish again within 10 seconds to confirm.");
                    return;
                }
                player.setItemOnCursor(studio.publishOnDisc(player, original));
                holder.overwriteDisc = null;
                holder.overwriteProject = null;
            } catch (IllegalArgumentException ex) { player.sendMessage(ex.getMessage()); }
            catch (IOException ex) { studio.failure(player, ex); }
        });
    }

    private void refresh() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!(open(player) instanceof Holder holder)) continue;
            try {
                if (!studio.current(holder.token, player)) { player.closeInventory(); continue; }
                studio.station().require(player, holder.station);
                if (holder.screen == Screen.LIBRARY) continue;
                Project project = studio.requireProject(player);
                if (!project.song().id().equals(holder.projectId)) { open(player, studio.station().require(player, holder.station)); continue; }
                if (project != holder.project || !studio.activity(player).equals(holder.activity)) render(player, holder);
            } catch (IllegalArgumentException ex) { player.closeInventory(); }
            catch (IOException ex) { player.closeInventory(); studio.failure(player, ex); }
        }
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        if (event.getRawSlots().stream().noneMatch(slot -> slot < holder.inventory.getSize())) return;
        event.setCancelled(true);
        if (holder.screen == Screen.EDITOR && event.getRawSlots().equals(java.util.Set.of(50))
                && event.getWhoClicked() instanceof Player player && studio.current(holder.token, player)) {
            deposit(player, holder, event.getOldCursor());
        }
    }

    public void end(Player player) {
        if (open(player) instanceof Holder) player.closeInventory();
    }

    public void close() {
        refresher.cancel();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (open(player) instanceof Holder) player.closeInventory();
        }
    }

    // The holder of the player's open container; MockBukkit reports no top inventory when none is open.
    private static InventoryHolder open(Player player) {
        return player.getOpenInventory().getTopInventory() instanceof Inventory top ? top.getHolder() : null;
    }

    private static final class Holder implements InventoryHolder {
        private final StudioIdentity.Token token;
        private final Location station;
        private final Screen screen;
        private final UUID projectId;
        private final Map<Integer, UUID> choices = new HashMap<>();
        private int page;
        private int pages;
        private Inventory inventory;
        private Project project;
        private StudioService.Activity activity;
        private ItemStack overwriteDisc;
        private Project overwriteProject;
        private long overwriteDeadline;
        private Holder(StudioIdentity.Token token, Location station, Screen screen, int page, UUID projectId) {
            this.token = token; this.station = station; this.screen = screen; this.page = page; this.projectId = projectId;
        }
        @Override public Inventory getInventory() { return inventory; }
    }
}
