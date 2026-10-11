package net.tfminecraft.musicalinstruments.studio;

import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import net.tfminecraft.musicalinstruments.managers.InstrumentManager;
import org.bukkit.Location;
import org.bukkit.Effect;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** All capture/playback uses one main-thread clock, including overdub monitoring. */
public final class StudioService {
    public static final int MAX_PROJECTS = 36;
    private final InstrumentPlugin plugin;
    private final InstrumentManager manager;
    private final StudioStore store;
    private final StudioSettings settings;
    private final StudioStation station;
    private final DiscItems discs;
    private final Map<UUID, Project> projects = new HashMap<>();
    private final Map<UUID, Capture> recordings = new HashMap<>();
    private final Map<UUID, Preview> previews = new HashMap<>();
    private final Map<UUID, Set<String>> privateSounds = new HashMap<>();
    private final Map<UUID, PendingSave> pendingSaves = new HashMap<>();
    // Owners whose cached project is newer than its files: a final take still saving or whose write failed.
    // Other cached projects match the disk, so disconnects and shutdown need not write them again.
    private final Set<UUID> unsaved = new HashSet<>();
    // Clock tick of each player's last publication, kept only while the cooldown applies.
    private final Map<UUID, Long> lastPublished = new HashMap<>();
    private final Map<BlockKey, Playback> jukeboxes = new HashMap<>();
    private final Map<UUID, Song> editions = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Song> eldest) {
            return size() > 64;
        }
    };
    private final BukkitTask task;
    private final ExecutorService checkpointWriter;
    private long clock;
    private boolean closed;

    public StudioService(InstrumentPlugin plugin, InstrumentManager manager) throws IOException {
        this(plugin, manager, loadSettings(plugin));
    }

    private StudioService(InstrumentPlugin plugin, InstrumentManager manager, YamlConfiguration yaml) throws IOException {
        this(plugin, manager, new StudioStore(plugin.getDataFolder().toPath().resolve("studio")), StudioSettings.read(yaml),
                StudioStation.parse(yaml.getString("station", ""), plugin.getLogger()), new DiscItems(plugin));
    }

    private static YamlConfiguration loadSettings(InstrumentPlugin plugin) throws IOException {
        if (!new File(plugin.getDataFolder(), "studio.yml").exists()) {
            plugin.saveResource("studio.yml", false);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new File(plugin.getDataFolder(), "studio.yml"));
        } catch (InvalidConfigurationException ex) {
            throw new IOException("Invalid studio.yml", ex);
        }
        return yaml;
    }

    StudioService(InstrumentPlugin plugin, InstrumentManager manager, StudioStore store, StudioSettings settings,
                  StudioStation station, DiscItems discs) {
        this(plugin, manager, store, settings, station, discs, Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon(true).name("musical-instruments-checkpoints").factory()));
    }

    StudioService(InstrumentPlugin plugin, InstrumentManager manager, StudioStore store, StudioSettings settings,
                  StudioStation station, DiscItems discs, ExecutorService checkpointWriter) {
        this.plugin = plugin;
        this.manager = manager;
        this.settings = settings;
        this.station = station;
        this.store = store;
        this.discs = discs;
        this.checkpointWriter = checkpointWriter;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public DiscItems discs() { return discs; }
    public StudioSettings settings() { return settings; }
    public StudioStation station() { return station; }

    public record Activity(int track, int seconds, int notes, int countdown, boolean previewing) {
        public boolean recording() { return track > 0; }
    }

    public Activity activity(Player player) {
        Capture capture = recordings.get(player.getUniqueId());
        if (capture == null) {
            return new Activity(0, 0, 0, 0, previews.containsKey(player.getUniqueId()));
        }
        int countdown = (int) Math.max(0, (capture.start - clock + 19) / 20);
        return new Activity(capture.slot, (int) Math.max(0, clock - capture.start) / 20,
                capture.notes.size(), countdown, false);
    }

    public Project project(Player player) throws IOException {
        Project project = projects.get(player.getUniqueId());
        if (project == null) {
            project = store.project(player.getUniqueId()).orElse(null);
            if (project != null) {
                projects.put(player.getUniqueId(), project);
            }
        }
        return project;
    }

    public Project requireProject(Player player) throws IOException {
        Project project = project(player);
        if (project == null) {
            throw new IllegalArgumentException("Create a project with /music new <title> first");
        }
        return project;
    }

    public void create(Player player, String title, boolean reset) throws IOException {
        requireIdle(player);
        if (!reset) requireProjectSpace(player);
        Project previous = project(player);
        if (previous != null) save(player, previous); // Also preserve a take retained after a failed write.
        UUID id = reset && previous != null ? previous.song().id() : UUID.randomUUID();
        save(player, new Project(new Song(id, player.getUniqueId(), player.getName(), title, List.of()),
                null, 100, true));
        stopPreview(player);
    }

    public void requireProjectSpace(Player player) throws IOException {
        if (listProjects(player).size() >= MAX_PROJECTS) {
            throw new IllegalArgumentException("Your studio is full (36 projects). Delete an old project first: right-click its song in the library");
        }
    }

    public ProjectSummary summary(Player player, UUID id) throws IOException {
        return listProjects(player).stream().filter(summary -> summary.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That project is no longer available"));
    }

    /** Deletes the project only if it still matches what the player confirmed. */
    public void deleteProject(Player player, ProjectSummary expected) throws IOException {
        requireIdle(player);
        if (!listProjects(player).contains(expected)) {
            throw new IllegalArgumentException("The project changed. Open the delete confirmation again");
        }
        UUID id = expected.id();
        store.delete(player.getUniqueId(), id);
        Project current = projects.get(player.getUniqueId());
        if (current != null && current.song().id().equals(id)) {
            projects.remove(player.getUniqueId());
            unsaved.remove(player.getUniqueId());
            stopPreview(player);
        }
        player.sendMessage("Deleted '" + expected.title() + "'. Published discs keep their music.");
    }

    public List<ProjectSummary> listProjects(Player player) throws IOException {
        Map<UUID, ProjectSummary> all = new LinkedHashMap<>();
        for (ProjectSummary summary : store.summaries(player.getUniqueId())) all.put(summary.id(), summary);
        // An uncached project matches its files; a cached one may hold a take that is still saving.
        Project active = projects.get(player.getUniqueId());
        if (active != null) all.put(active.song().id(), ProjectSummary.of(active));
        return all.values().stream().sorted(ProjectSummary.ORDER).toList();
    }

    public void selectProject(Player player, UUID id) throws IOException {
        Project current = project(player);
        if (current != null && current.song().id().equals(id)) return;
        requireIdle(player);
        Project next = store.project(player.getUniqueId(), id);
        if (current != null) save(player, current);
        save(player, next);
        stopPreview(player);
    }

    public void save(Player player, Project project) throws IOException {
        if (!project.song().owner().equals(player.getUniqueId())) {
            throw new IllegalArgumentException("This is not your project");
        }
        requireIdle(player);
        store.save(project); // Persist successfully before replacing the in-memory version.
        projects.put(player.getUniqueId(), project);
        unsaved.remove(player.getUniqueId());
    }

    public void edit(Player player, Project project) throws IOException {
        requireIdle(player);
        save(player, project);
    }

    public void requireIdle(Player player) {
        if (recordings.containsKey(player.getUniqueId())) {
            throw new IllegalArgumentException("Stop the recording with /music stop first");
        }
        PendingSave pending = pendingSaves.get(player.getUniqueId());
        if (pending != null) {
            if (!pending.future().isDone()) {
                throw new IllegalArgumentException("Your take is still saving. Try again shortly");
            }
            completeSave(player.getUniqueId(), pending);
        }
    }

    public void record(Player player, int slot) throws IOException {
        requireIdle(player);
        Project project = requireProject(player);
        if (project.pending() != null) {
            throw new IllegalArgumentException("Use /music keep or /music discard for the previous take first");
        }
        if (slot < 1 || slot > settings.tracks()) {
            throw new IllegalArgumentException("Track must be between 1 and " + settings.tracks());
        }
        // Off-hand instruments play with the hotbar; a held instrument opens the on-screen keyboard.
        if (manager.getInstrument(player.getInventory().getItemInOffHand()) == null
                && manager.getInstrument(player.getInventory().getItemInMainHand()) == null) {
            throw new IllegalArgumentException("Hold an instrument in your hand or off-hand");
        }
        stopPreview(player);
        capacity();
        recordings.put(player.getUniqueId(), new Capture(project, slot, clock + settings.countInTicks()));
        player.sendMessage("Recording starts in " + settings.countInTicks() / 20 + " seconds. Play with the hotbar, "
                + "or right-click your instrument for the keyboard. /music stop ends the take.");
    }

    public void capture(Player player, String instrument, String sound, float volume, float pitch) {
        Capture capture = recordings.get(player.getUniqueId());
        if (capture == null || clock < capture.start || clock - capture.start >= settings.durationTicks()) {
            return;
        }
        int at = (int) (clock - capture.start);
        // Bound simultaneous triggers even when a client floods slot changes.
        int inTick = 0;
        for (int i = capture.notes.size() - 1; i >= 0 && capture.notes.get(i).tick() == at; i--) {
            inTick++;
        }
        if (inTick >= 32) {
            return;
        }
        int existing = capture.project.song().tracks().stream().filter(t -> t.slot() != capture.slot)
                .mapToInt(t -> t.notes().size()).sum();
        if (capture.notes.size() >= settings.notesPerTrack()
                || existing + capture.notes.size() >= settings.notesPerSong()) {
            finish(player, capture, "Note limit reached");
            return;
        }
        try {
            capture.notes.add(new Note(at, instrument, sound, volume, pitch));
        } catch (IllegalArgumentException ex) {
            finish(player, capture, "The instrument sample is outside supported recording bounds");
        }
    }

    public void stop(Player player) {
        stopPreview(player);
        Capture capture = recordings.get(player.getUniqueId());
        if (capture != null) {
            finish(player, capture, "Recording stopped");
        } else {
            player.sendMessage("Preview stopped.");
        }
    }

    public void preview(Player player, boolean pending) throws IOException {
        requireIdle(player);
        Project project = requireProject(player);
        Song song = project.song();
        if (pending) {
            if (project.pending() == null) {
                throw new IllegalArgumentException("No take to preview");
            }
            song = song.replace(project.pending());
        }
        if (song.tracks().isEmpty()) {
            throw new IllegalArgumentException("Record and keep a track first");
        }
        previewSong(player, song);
        player.sendMessage(pending ? "Previewing the new take with the other tracks." : "Previewing the saved mix.");
    }

    public void previewTrack(Player player, int slot) throws IOException {
        requireIdle(player);
        Song song = requireProject(player).song();
        Track track = song.track(slot);
        if (track == null) throw new IllegalArgumentException("Record and keep this track first");
        if (track.muted()) throw new IllegalArgumentException("Unmute this track to listen to it");
        previewSong(player, new Song(song.id(), song.owner(), song.author(), song.title(), List.of(track)));
        player.sendMessage("Listening to saved track " + slot + ".");
    }

    private void previewSong(Player player, Song song) {
        stopPreview(player);
        capacity();
        previews.put(player.getUniqueId(), new Preview(song, clock + 1));
    }

    private void stopPreview(Player player) {
        previews.remove(player.getUniqueId());
        stopPrivateSounds(player);
    }

    public void makeBlank(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!ordinaryDisc(held) || held.getAmount() != 1) {
            throw new IllegalArgumentException("Hold one ordinary music disc in your main hand");
        }
        player.getInventory().setItemInMainHand(discs.blankDisc());
        player.sendMessage("Converted your music disc into a blank recording disc.");
    }

    private void requireBlank(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!discs.blank(held) || held.getAmount() != 1) {
            throw new IllegalArgumentException("Hold one blank disc in your main hand. /music blank converts an ordinary disc");
        }
    }

    public void publish(Player player) throws IOException {
        requireIdle(player);
        requireBlank(player);
        Song edition = publishEdition(player);
        player.getInventory().setItemInMainHand(discs.disc(edition));
        player.sendMessage("Published '" + edition.title() + "'. Existing discs will keep this edition.");
    }

    public ItemStack publishOnDisc(Player player, ItemStack disc) throws IOException {
        requireIdle(player);
        if (!discs.musicDisc(disc) || disc.getAmount() != 1) {
            throw new IllegalArgumentException("Pick up one music disc and place it on Publish full song");
        }
        Song edition = publishEdition(player);
        ItemStack result = discs.disc(edition, disc);
        player.sendMessage("Published '" + edition.title() + "' on your disc. Other copies keep their previous music.");
        return result;
    }

    private Song publishEdition(Player player) throws IOException {
        Project project = requireProject(player);
        if (project.pending() != null) {
            throw new IllegalArgumentException("Keep or discard your pending take first");
        }
        if (!project.song().audible()) {
            throw new IllegalArgumentException("The mix needs at least one audible note");
        }
        // Each publication of changed music adds a file that discs may reference forever.
        lastPublished.values().removeIf(at -> clock - at >= settings.publishCooldownTicks());
        Long last = lastPublished.get(player.getUniqueId());
        if (last != null) {
            long seconds = (last + settings.publishCooldownTicks() - clock + 19) / 20;
            throw new IllegalArgumentException("Wait " + seconds + " seconds before publishing again");
        }
        Song edition = store.publishEdition(project.song()); // No item is consumed when publication fails.
        lastPublished.put(player.getUniqueId(), clock);
        editions.put(edition.id(), edition);
        return edition;
    }

    public void copy(Player player) throws IOException {
        requireBlank(player);
        UUID id = discs.songId(player.getInventory().getItemInOffHand());
        if (id == null) {
            throw new IllegalArgumentException("Hold the recorded disc to copy in your off-hand");
        }
        player.getInventory().setItemInMainHand(discs.disc(edition(id)));
        player.sendMessage("Copied the recorded disc using your blank disc.");
    }

    public boolean ordinaryDisc(ItemStack item) {
        return discs.musicDisc(item) && !discs.custom(item)
                && item.getItemMeta().getPersistentDataContainer().isEmpty()
                && !item.getItemMeta().hasCustomModelData();
    }

    public int blankDiscs(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (discs.blank(item)) count += item.getAmount();
        }
        return count;
    }

    public void prepareBlankFromInventory(Player player) {
        DiscSlot slot = discSlot(player, false);
        slot.replace(discs.blankDisc());
        player.sendMessage("A music disc from your inventory is now a blank recording disc.");
    }

    public void publishFromInventory(Player player) throws IOException {
        requireIdle(player);
        DiscSlot slot = discSlot(player, true); // Check space before writing an edition or consuming a disc.
        Song edition = publishEdition(player);
        slot.replace(discs.disc(edition));
        player.sendMessage("Published '" + edition.title() + "' on a blank disc from your inventory.");
    }

    public void copyFromInventory(Player player) throws IOException {
        if (!player.hasPermission("instruments.copy")) {
            throw new IllegalArgumentException("You don't have permission to copy recording discs");
        }
        UUID id = discs.songId(player.getInventory().getItemInOffHand());
        if (id == null) {
            throw new IllegalArgumentException("Hold the recorded disc to copy in your off-hand");
        }
        DiscSlot slot = discSlot(player, true);
        slot.replace(discs.disc(edition(id)));
        player.sendMessage("Copied the recorded disc using a blank disc from your inventory.");
    }

    private DiscSlot discSlot(Player player, boolean blank) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] items = inventory.getStorageContents();
        boolean needsSpace = false;
        for (int index = 0; index < items.length; index++) {
            ItemStack item = items[index];
            if (item == null || item.getAmount() < 1 || !(blank ? discs.blank(item) : ordinaryDisc(item))) continue;
            if (item.getAmount() == 1) return new DiscSlot(inventory, index, index, item);
            for (int output = 0; output < items.length; output++) {
                if (items[output] == null || items[output].getType().isAir()) {
                    return new DiscSlot(inventory, index, output, item);
                }
            }
            needsSpace = true;
        }
        if (needsSpace) throw new IllegalArgumentException("Leave one free inventory slot to separate a disc from this stack");
        throw new IllegalArgumentException(blank ? "Put a blank recording disc in your inventory first"
                : "Put an ordinary music disc in your inventory first");
    }

    private record DiscSlot(PlayerInventory inventory, int input, int output, ItemStack original) {
        private void replace(ItemStack result) {
            if (input != output) {
                ItemStack remainder = original.clone();
                remainder.setAmount(original.getAmount() - 1);
                inventory.setItem(input, remainder);
            }
            inventory.setItem(output, result);
        }
    }

    private Song edition(UUID id) throws IOException {
        Song song = editions.get(id);
        if (song == null) {
            song = store.song(id);
            editions.put(id, song);
        }
        return song;
    }

    public void play(Block block, ItemStack disc) throws IOException {
        UUID id = discs.songId(disc);
        if (id == null) {
            throw new IllegalArgumentException("Invalid recording disc");
        }
        BlockKey key = BlockKey.of(block);
        if (jukeboxes.containsKey(key)) {
            throw new IllegalArgumentException("This jukebox is already playing");
        }
        capacity();
        Song song = edition(id);
        // Resolve the edition before inserting the item or altering the world.
        ItemStack recording = discs.playbackDisc(disc);
        Jukebox box = (Jukebox) block.getState();
        stopVanilla(block);
        box.setRecord(recording);
        if (!box.update(false, false)) {
            throw new IllegalArgumentException("The jukebox changed before the disc could be inserted");
        }
        stopVanilla(block);
        jukeboxes.put(key, new Playback(song, clock + 1));
    }

    public void stop(Block block) {
        boolean active = jukeboxes.remove(BlockKey.of(block)) != null;
        if (active || block.getType() == Material.JUKEBOX
                && block.getState() instanceof Jukebox box && discs.custom(box.getRecord())) {
            stopVanilla(block);
        }
    }

    private void stopVanilla(Block block) {
        if (block.getState() instanceof Jukebox box) box.stopPlaying();
        // Clear the client's jukebox channel even if the server already thinks it is stopped.
        // This targets only this block, leaving other jukeboxes and instrument samples alone.
        block.getWorld().playEffect(block.getLocation(), Effect.SOUND_STOP_JUKEBOX_SONG, 0, Math.max(64, settings.radius()));
    }

    public void unload(UUID world, int chunkX, int chunkZ) {
        jukeboxes.keySet().removeIf(key -> key.world.equals(world) && key.x >> 4 == chunkX && key.z >> 4 == chunkZ);
    }

    private void capacity() {
        if (recordings.size() + previews.size() + jukeboxes.size() >= settings.sessions()) {
            throw new IllegalArgumentException("All music sessions are busy. Try again later");
        }
    }

    private void tick() {
        clock++;
        completeSaves();
        for (var entry : new ArrayList<>(recordings.entrySet())) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null) {
                continue; // PlayerQuitEvent persists the take while the player is still accessible.
            }
            Capture capture = entry.getValue();
            long elapsed = clock - capture.start;
            if (elapsed < 0) {
                if (-elapsed % 20 == 0) {
                    player.sendActionBar(net.kyori.adventure.text.Component.text("Recording in " + (-elapsed / 20) + "..."));
                    click(player);
                }
                continue;
            }
            if (elapsed == 0) {
                player.sendMessage("Recording track " + capture.slot + " now.");
            }
            if (elapsed % 10 == 0) {
                int seconds = (int) elapsed / 20;
                String time = String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
                player.sendActionBar(Component.text("\u25cf Track " + capture.slot + "  |  " + time
                        + "  |  " + capture.notes.size() + " notes", NamedTextColor.RED));
            }
            if (elapsed >= settings.durationTicks()) {
                finish(player, capture, "Maximum duration reached");
                continue;
            }
            emit(player, capture.backing.due((int) elapsed));
            if (capture.project.metronome()
                    && (elapsed == 0 || elapsed * capture.project.bpm() / 1200 != (elapsed - 1) * capture.project.bpm() / 1200)) {
                click(player);
            }
            if (elapsed > 0 && elapsed % settings.checkpointTicks() == 0 && !capture.notes.isEmpty()) {
                // Immutable snapshots keep YAML and disk work off the musical clock.
                // Only one checkpoint per capture can be queued; this bounds the worker backlog.
                if (capture.checkpoint.isCompletedExceptionally()) {
                    finish(player, capture, "Checkpoint failed; recording stopped to protect your take");
                } else if (capture.checkpoint.isDone()) {
                    Project snapshot = capture.snapshot((int) elapsed);
                    capture.checkpoint = CompletableFuture.runAsync(() -> {
                        try {
                            store.save(snapshot);
                        } catch (IOException ex) {
                            throw new CompletionException(ex);
                        }
                    }, checkpointWriter);
                }
            }
        }
        for (Iterator<Map.Entry<UUID, Preview>> it = previews.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            Preview preview = entry.getValue();
            int elapsed = (int) (clock - preview.start);
            if (player == null || elapsed >= preview.song.lengthTicks() + settings.tailTicks()) {
                it.remove();
                if (player != null) stopPrivateSounds(player);
                else privateSounds.remove(entry.getKey());
            } else if (elapsed >= 0) {
                emit(player, preview.timeline.due(elapsed));
            }
        }
        for (Iterator<Map.Entry<BlockKey, Playback>> it = jukeboxes.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            BlockKey key = entry.getKey();
            Playback playback = entry.getValue();
            var world = plugin.getServer().getWorld(key.world);
            if (world == null || !world.isChunkLoaded(key.x >> 4, key.z >> 4)) {
                it.remove();
                continue;
            }
            Block block = world.getBlockAt(key.x, key.y, key.z);
            int elapsed = (int) (clock - playback.start);
            // The block type is cheap to read every tick. Reading the record copies the block state and
            // item, so it is checked once a second; ejection and breaking stop playback immediately.
            if (block.getType() != Material.JUKEBOX || elapsed % 20 == 0 && !holds(block, playback.song)
                    || elapsed >= playback.song.lengthTicks() + settings.tailTicks()) {
                it.remove();
                continue;
            }
            if (elapsed >= 0) {
                List<Timeline.Trigger> due = playback.timeline.due(elapsed);
                if (!due.isEmpty()) {
                    Location origin = block.getLocation().add(0.5, 0.5, 0.5);
                    for (Player listener : world.getPlayers()) {
                        if (listener.getLocation().distanceSquared(origin) <= settings.radius() * settings.radius()) {
                            for (Timeline.Trigger trigger : due) {
                                sample(listener, origin, trigger, SoundCategory.RECORDS);
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean holds(Block block, Song song) {
        return block.getState() instanceof Jukebox box && song.id().equals(discs.songId(box.getRecord()));
    }

    private void emit(Player player, List<Timeline.Trigger> due) {
        for (Timeline.Trigger trigger : due) {
            privateSounds.computeIfAbsent(player.getUniqueId(), key -> new HashSet<>()).add(trigger.note().sound());
            sample(player, player.getLocation(), trigger, SoundCategory.PLAYERS);
        }
    }

    private void sample(Player player, Location origin, Timeline.Trigger trigger, SoundCategory category) {
        Note note = trigger.note();
        player.playSound(origin, note.sound(), category,
                Math.min(16, note.volume() * trigger.gain()), note.pitch());
    }

    private void click(Player player) {
        privateSounds.computeIfAbsent(player.getUniqueId(), key -> new HashSet<>()).add("block.note_block.hat");
        player.playSound(player.getLocation(), "block.note_block.hat", SoundCategory.PLAYERS, 0.35f, 1);
    }

    private void finish(Player player, Capture capture, String reason) {
        recordings.remove(player.getUniqueId());
        stopPreview(player);
        player.sendActionBar(Component.empty());
        if (capture.notes.isEmpty()) {
            player.sendMessage(reason + ". No notes captured; the previous track was preserved.");
            return;
        }
        Project result = capture.snapshot((int) Math.max(1, Math.min(settings.durationTicks(), clock - capture.start + 1)));
        // Retain the take in memory even if the disk fails, so /music keep can retry.
        projects.put(player.getUniqueId(), result);
        unsaved.add(player.getUniqueId());
        queueSave(result, true);
        player.sendMessage(reason + ". Saving take; you can review it when the save finishes.");
    }

    private void queueSave(Project project, boolean take) {
        // The shared FIFO worker puts the final snapshot after every older checkpoint.
        // Main-thread edits are guarded until this write completes, so they cannot be overwritten.
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try { store.save(project); }
            catch (IOException ex) { throw new CompletionException(ex); }
        }, checkpointWriter);
        pendingSaves.put(project.song().owner(), new PendingSave(future, take));
    }

    private void completeSave(UUID owner, PendingSave pending) {
        pendingSaves.remove(owner);
        Player player = plugin.getServer().getPlayer(owner);
        try {
            // Called only after isDone(): this never waits on disk from the musical clock.
            pending.future().join();
            // Edits wait for this save, so the cached project now matches its files.
            unsaved.remove(owner);
            if (player == null) projects.remove(owner);
            else if (pending.take()) player.sendMessage("Take saved: /music preview take, /music keep, or /music discard.");
        } catch (CompletionException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save studio project for " + owner, ex.getCause());
            if (player != null) player.sendMessage("Could not read/write the recording. Your take is retained in memory; try /music keep again.");
        }
    }

    private void completeSaves() {
        for (var entry : new ArrayList<>(pendingSaves.entrySet())) {
            if (entry.getValue().future().isDone()) completeSave(entry.getKey(), entry.getValue());
        }
    }

    public void quit(Player player) {
        Capture capture = recordings.get(player.getUniqueId());
        if (capture != null) {
            finish(player, capture, "Recording stopped on disconnect");
        }
        stopPreview(player);
        UUID owner = player.getUniqueId();
        if (pendingSaves.containsKey(owner)) return; // completeSave evicts the project once it is written.
        if (unsaved.contains(owner)) queueSave(projects.get(owner), false); // Retry a take whose write failed.
        else projects.remove(owner); // Its files are current; the next visit reloads it.
    }

    public void close() {
        if (closed) return;
        closed = true;
        task.cancel();
        // Observe completed failures before deciding which cached takes need another save.
        completeSaves();
        for (var entry : new ArrayList<>(recordings.entrySet())) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) {
                finish(player, entry.getValue(), "Recording stopped on shutdown");
            }
        }
        for (var entry : projects.entrySet()) {
            if (unsaved.contains(entry.getKey()) && !pendingSaves.containsKey(entry.getKey())) queueSave(entry.getValue(), false);
        }
        for (UUID owner : new ArrayList<>(privateSounds.keySet())) {
            Player player = plugin.getServer().getPlayer(owner);
            if (player != null) stopPreview(player);
        }
        jukeboxes.clear();
        previews.clear();
        recordings.clear();
        privateSounds.clear();
        checkpointWriter.shutdown();
        try {
            if (!checkpointWriter.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().severe("Studio saves are still pending after the shutdown timeout");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.SEVERE, "Interrupted while saving studio projects on shutdown", ex);
        }
        completeSaves();
    }

    private void stopPrivateSounds(Player player) {
        Set<String> sounds = privateSounds.remove(player.getUniqueId());
        if (sounds != null) for (String sound : sounds) player.stopSound(sound, SoundCategory.PLAYERS);
    }

    private record PendingSave(CompletableFuture<Void> future, boolean take) {}

    public void failure(Player player, IOException ex) {
        plugin.getLogger().log(Level.SEVERE, "Studio storage operation failed for " + player.getUniqueId(), ex);
        player.sendMessage("Could not read/write the recording. Your disc was preserved; contact a server administrator.");
    }

    private static final class Capture {
        private final Project project;
        private final int slot;
        private final long start;
        private final Timeline backing;
        private final List<Note> notes = new ArrayList<>();
        private CompletableFuture<Void> checkpoint = CompletableFuture.completedFuture(null);

        private Capture(Project project, int slot, long start) {
            this.project = project;
            this.slot = slot;
            this.start = start;
            backing = new Timeline(project.song(), slot);
        }

        private Project snapshot(int length) {
            Track old = project.song().track(slot);
            int boundedLength = Math.min(Song.MAX_TICKS, Math.max(length, notes.getLast().tick() + 1));
            Track track = new Track(slot, boundedLength, old == null ? 1 : old.gain(), old != null && old.muted(), notes);
            return new Project(project.song(), track, project.bpm(), project.metronome());
        }
    }

    private static final class Preview {
        private final Song song;
        private final long start;
        private final Timeline timeline;

        private Preview(Song song, long start) {
            this.song = song;
            this.start = start;
            timeline = new Timeline(song, -1);
        }
    }

    private static final class Playback {
        private final Song song;
        private final long start;
        private final Timeline timeline;

        private Playback(Song song, long start) {
            this.song = song;
            this.start = start;
            timeline = new Timeline(song, -1);
        }
    }

    private record BlockKey(UUID world, int x, int y, int z) {
        private static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }
}
