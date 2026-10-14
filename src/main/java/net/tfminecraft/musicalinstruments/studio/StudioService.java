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
    static final long RETRY_TICKS = 1200;
    static final long SHUTDOWN_WAIT_SECONDS = 30;
    private final InstrumentPlugin plugin;
    private final InstrumentManager manager;
    private final StudioStore store;
    private final StudioIdentity identity;
    private final StudioOwnership ownership;
    private final Map<UUID, StudioIdentity.Owner> owners = new HashMap<>();
    private final StudioSettings settings;
    private final StudioStation station;
    private final DiscItems discs;
    private final Map<UUID, Project> projects = new HashMap<>();
    private final Map<UUID, Capture> recordings = new HashMap<>();
    private final Map<UUID, Preview> previews = new HashMap<>();
    private final Map<UUID, Set<String>> privateSounds = new HashMap<>();
    // Each owner's latest save. An in-flight snapshot is immutable; pending snapshots of the same
    // owner share a future and are replaced with the newest project instead of filling the executor.
    private final Map<UUID, CompletableFuture<Void>> pendingSaves = new HashMap<>();
    // Accessed by the main thread and the writer only while holding this map's monitor.
    private final Map<UUID, QueuedSave> queuedSaves = new HashMap<>();
    // Owners whose cached project is newer than its files: a write is still queued, or the last one failed.
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
    private boolean migrationAttempted;
    private volatile long shutdownDeadline;

    public StudioService(InstrumentPlugin plugin, InstrumentManager manager) throws IOException {
        this(plugin, manager, loadSettings(plugin));
    }

    private StudioService(InstrumentPlugin plugin, InstrumentManager manager, YamlConfiguration yaml) throws IOException {
        this(plugin, manager, new StudioStore(plugin.getDataFolder().toPath().resolve("studio"), plugin.getLogger()), StudioSettings.read(yaml),
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
        this(plugin, manager, store, settings, station, discs, StudioIdentity.load());
    }

    StudioService(InstrumentPlugin plugin, InstrumentManager manager, StudioStore store, StudioSettings settings,
                  StudioStation station, DiscItems discs, StudioIdentity identity) {
        this(plugin, manager, store, settings, station, discs, Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon(true).name("musical-instruments-checkpoints").factory()), identity);
    }

    StudioService(InstrumentPlugin plugin, InstrumentManager manager, StudioStore store, StudioSettings settings,
                  StudioStation station, DiscItems discs, ExecutorService checkpointWriter) {
        this(plugin, manager, store, settings, station, discs, checkpointWriter, StudioIdentity.load());
    }

    StudioService(InstrumentPlugin plugin, InstrumentManager manager, StudioStore store, StudioSettings settings,
                  StudioStation station, DiscItems discs, ExecutorService checkpointWriter, StudioIdentity identity) {
        this.plugin = plugin;
        this.manager = manager;
        this.settings = settings;
        this.station = station;
        this.store = store;
        this.identity = identity;
        this.ownership = new StudioOwnership(store);
        this.discs = discs;
        this.checkpointWriter = checkpointWriter;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public DiscItems discs() { return discs; }
    public StudioSettings settings() { return settings; }
    public StudioStation station() { return station; }

    /** The full ownership check for one change; callers pass the token on rather than checking again. */
    public StudioIdentity.Token token(Player player) {
        StudioIdentity.Token token = session(player);
        try {
            ownership.requireWritable(token.owner().character());
        }
        catch (IOException ex) { throw new IllegalArgumentException("Studio ownership needs recovery: " + ex.getMessage(), ex); }
        return token;
    }

    // Reads need only the current session: every change, menu and dialog first passes token().
    private StudioIdentity.Token session(Player player) {
        StudioIdentity.Token token = identity.current(player).orElseThrow(
                () -> new IllegalArgumentException("Choose an active character before using the recording studio"));
        if (!current(token, player)) throw new IllegalArgumentException("Your character session changed");
        owners.put(token.owner().character(), token.owner());
        if (!migrationAttempted) migrateOwnership();
        return token;
    }

    // Keeps the recording refusal ahead of the ownership check, as requireIdle always did.
    private StudioIdentity.Token idleToken(Player player) {
        requireNotRecording(player);
        return token(player);
    }

    public boolean current(StudioIdentity.Token token, Player player) {
        return token != null && token.owner().account().equals(player.getUniqueId()) && identity.current(token);
    }

    public void startOwnership(StudioMenu menu) {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            migrateOwnership();
        }, 1L, 72_000L);
        if (identity instanceof CharacterStudioBridge) CharacterStudioBridge.start(plugin, this, menu);
        StudioEstate.startIfAvailable(plugin, this, ownership);
    }

    private void migrateOwnership() {
        if (!identity.heirsReady()) return;
        migrationAttempted = true;
        try { ownership.migrate(identity); }
        catch (IOException ex) {
            var causes = new java.util.StringJoiner("; "); causes.add(ex.getMessage());
            for (Throwable other : ex.getSuppressed()) causes.add(other.getMessage());
            plugin.getLogger().warning("Studio migration held: " + causes);
        }
    }

    boolean quiescent(UUID character) {
        completeSaves();
        return !pendingSaves.containsKey(character) && !unsaved.contains(character)
                && recordings.values().stream().noneMatch(capture -> capture.token.owner().character().equals(character));
    }

    void transferred(UUID character) { projects.remove(character); owners.remove(character); }

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

    public Project project(Player player) throws IOException { return project(session(player)); }

    private Project project(StudioIdentity.Token token) throws IOException {
        UUID owner = token.owner().character();
        Project project = projects.get(owner);
        if (project == null) {
            project = store.project(owner).orElse(null);
            if (project != null) {
                requireOwner(token, project);
                projects.put(owner, project);
            }
        }
        return project;
    }

    public Project requireProject(Player player) throws IOException { return requireProject(session(player)); }

    private Project requireProject(StudioIdentity.Token token) throws IOException {
        Project project = project(token);
        if (project == null) {
            throw new IllegalArgumentException("Create a project with /music new <title> first");
        }
        return project;
    }

    public void create(Player player, String title, boolean reset) throws IOException {
        StudioIdentity.Token token = idleToken(player);
        requireSaved(player, token);
        Project previous = project(token);
        // Without an active project to replace, a reset creates another project.
        if (!reset || previous == null) requireProjectSpace(token);
        persist(player, token); // A project whose last write failed is written before another replaces it.
        UUID id = reset && previous != null ? previous.song().id() : UUID.randomUUID();
        save(player, token, new Project(new Song(id, player.getUniqueId(), player.getName(), title, List.of()),
                null, 100, true));
        stopPreview(player);
    }

    public void requireProjectSpace(Player player) throws IOException { requireProjectSpace(session(player)); }

    private void requireProjectSpace(StudioIdentity.Token token) throws IOException {
        if (listProjects(token).size() >= MAX_PROJECTS) {
            throw new IllegalArgumentException("Your studio is full (36 projects). Delete an old project first: right-click its song in the library");
        }
    }

    /** Loads only the requested project; a cached version may contain changes not yet written. */
    public Project project(Player player, UUID id) throws IOException { return project(session(player), id); }

    private Project project(StudioIdentity.Token token, UUID id) throws IOException {
        UUID owner = token.owner().character();
        Project current = projects.get(owner);
        if (current != null && current.song().id().equals(id)) return current;
        Project loaded = store.project(owner, id);
        requireOwner(token, loaded);
        return loaded;
    }

    /** Deletes the project only if it still matches what the player confirmed. */
    public void deleteProject(Player player, Project expected) throws IOException {
        StudioIdentity.Token token = idleToken(player);
        UUID owner = token.owner().character();
        requireSaved(player, token); // A queued write would bring the deleted project back.
        requireOwner(token, expected);
        UUID id = expected.song().id();
        // Summaries omit notes, mix and settings, so they cannot protect a stale confirmation.
        if (!project(token, id).equals(expected)) {
            throw new IllegalArgumentException("The project changed. Open the delete confirmation again");
        }
        store.delete(owner, id);
        Project current = projects.get(owner);
        if (current != null && current.song().id().equals(id)) {
            projects.remove(owner);
            unsaved.remove(owner);
            stopPreview(player);
        }
        player.sendMessage("Deleted '" + expected.song().title() + "'. Published discs keep their music.");
    }

    public List<ProjectSummary> listProjects(Player player) throws IOException { return listProjects(session(player)); }

    private List<ProjectSummary> listProjects(StudioIdentity.Token token) throws IOException {
        UUID owner = token.owner().character();
        Map<UUID, ProjectSummary> all = new LinkedHashMap<>();
        for (ProjectSummary summary : store.summaries(owner)) all.put(summary.id(), summary);
        // An uncached project matches its files; a cached one may be newer than them.
        Project active = projects.get(owner);
        if (active != null) all.put(active.song().id(), ProjectSummary.of(active));
        return all.values().stream().sorted(ProjectSummary.ORDER).toList();
    }

    public void selectProject(Player player, UUID id) throws IOException {
        StudioIdentity.Token token = token(player);
        UUID owner = token.owner().character();
        Project current = project(token);
        if (current != null && current.song().id().equals(id)) return;
        requireSaved(player, token); // The next project is read from its file, which a queued write could still change.
        Project next = store.project(owner, id);
        requireOwner(token, next);
        persist(player, token);
        store.select(owner, id); // Both projects already match their files.
        projects.put(owner, next);
        stopPreview(player);
    }

    /** Writes the project before it becomes the active one. Used when creating or replacing a project. */
    public void save(Player player, Project project) throws IOException { save(player, token(player), project); }

    private void save(Player player, StudioIdentity.Token token, Project project) throws IOException {
        requireOwner(token, project);
        requireSaved(player, token);
        store.saveOwned(token.owner(), project); // Persist successfully before replacing the in-memory version.
        projects.put(token.owner().character(), project);
        unsaved.remove(token.owner().character());
    }

    /**
     * Applies a change to the active project at once and writes it in the background, after any
     * earlier write. If that write fails, the change stays in memory and is written again.
     */
    public void edit(Player player, Project project) {
        StudioIdentity.Token token = token(player);
        UUID owner = token.owner().character();
        requireOwner(token, project);
        requireIdle(player, token);
        projects.put(owner, project);
        unsaved.add(owner);
        queueSave(token.owner(), project);
    }

    private void requireOwner(StudioIdentity.Token token, Project project) {
        try { store.requireAuthority(token.owner(), project.song().id(), project.song().owner()); }
        catch (IOException ex) { throw new IllegalArgumentException("Could not verify your project's owner", ex); }
    }

    // Retries a write that failed. A project with no failed write already matches its files.
    private void persist(Player player, StudioIdentity.Token token) throws IOException {
        UUID owner = token.owner().character();
        if (unsaved.contains(owner)) save(player, token, projects.get(owner));
    }

    public void requireIdle(Player player) {
        requireNotRecording(player);
        requireIdle(player, session(player));
    }

    private void requireNotRecording(Player player) {
        if (recordings.containsKey(player.getUniqueId())) {
            throw new IllegalArgumentException("Stop the recording with /music stop first");
        }
    }

    private void requireIdle(Player player, StudioIdentity.Token token) {
        requireNotRecording(player);
        UUID owner = token.owner().character();
        CompletableFuture<Void> pending = pendingSaves.get(owner);
        if (pending != null && pending.isDone()) completeSave(owner, pending);
    }

    /** Also requires the project's files to be current, for changes that read or remove them. */
    public void requireSaved(Player player) {
        requireNotRecording(player);
        requireSaved(player, session(player));
    }

    private void requireSaved(Player player, StudioIdentity.Token token) {
        requireIdle(player, token);
        if (pendingSaves.containsKey(token.owner().character())) {
            throw new IllegalArgumentException("Your project is still saving. Try again shortly");
        }
    }

    public void record(Player player, int slot) throws IOException {
        StudioIdentity.Token token = idleToken(player);
        requireIdle(player, token);
        Project project = requireProject(token);
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
        recordings.put(player.getUniqueId(), new Capture(project, slot, clock + settings.countInTicks(), token));
        player.sendMessage("Recording starts in " + settings.countInTicks() / 20 + " seconds. Play with the hotbar, "
                + "or right-click your instrument for the keyboard. /music stop ends the take.");
    }

    public void capture(Player player, String instrument, String sound, float volume, float pitch) {
        Capture capture = recordings.get(player.getUniqueId());
        if (capture == null || !current(capture.token, player) || clock < capture.start || clock - capture.start >= settings.durationTicks()) {
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
        StudioIdentity.Token token = idleToken(player);
        requireIdle(player, token);
        Project project = requireProject(token);
        Song song = project.song();
        if (pending) {
            Track take = project.pending();
            if (take == null) {
                throw new IllegalArgumentException("No take to preview");
            }
            // The take carries its track's mix, but reviewing it must be audible even on a muted or silent track.
            song = song.replace(take.mix(take.gain() > 0 ? take.gain() : 1, false));
        }
        if (song.tracks().isEmpty()) {
            throw new IllegalArgumentException("Record and keep a track first");
        }
        if (!song.audible()) {
            throw new IllegalArgumentException("Every saved track is muted or silent. Unmute or raise a track first");
        }
        previewSong(player, token, song);
        player.sendMessage(pending ? "Previewing the new take with the other tracks." : "Previewing the saved mix.");
    }

    public void previewTrack(Player player, int slot) throws IOException {
        StudioIdentity.Token token = idleToken(player);
        requireIdle(player, token);
        Song song = requireProject(token).song();
        Track track = song.track(slot);
        if (track == null) throw new IllegalArgumentException("Record and keep this track first");
        if (track.muted()) throw new IllegalArgumentException("Unmute this track to listen to it");
        Song single = new Song(song.id(), song.owner(), song.author(), song.title(), List.of(track));
        if (!single.audible()) throw new IllegalArgumentException("Raise this track's volume to listen to it");
        previewSong(player, token, single);
        player.sendMessage("Listening to saved track " + slot + ".");
    }

    private void previewSong(Player player, StudioIdentity.Token token, Song song) {
        stopPreview(player);
        capacity();
        previews.put(player.getUniqueId(), new Preview(song, clock + 1, token));
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
        StudioIdentity.Token token = idleToken(player);
        requireIdle(player, token);
        requireBlank(player);
        Song edition = publishEdition(player, token);
        player.getInventory().setItemInMainHand(discs.disc(edition));
        player.sendMessage("Published '" + edition.title() + "'. Existing discs will keep this edition.");
    }

    public ItemStack publishOnDisc(Player player, ItemStack disc) throws IOException {
        StudioIdentity.Token token = idleToken(player);
        requireIdle(player, token);
        if (!discs.musicDisc(disc) || disc.getAmount() != 1) {
            throw new IllegalArgumentException("Pick up one music disc and place it on Publish full song");
        }
        Song edition = publishEdition(player, token);
        ItemStack result = discs.disc(edition, disc);
        player.sendMessage("Published '" + edition.title() + "' on your disc. Other copies keep their previous music.");
        return result;
    }

    private Song publishEdition(Player player, StudioIdentity.Token token) throws IOException {
        Project project = requireProject(token);
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
        // No item is consumed when publication fails.
        Song edition = store.publishEdition(project.song(), player.getUniqueId(), settings.editionsPerDay());
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
        StudioIdentity.Token token = idleToken(player);
        requireIdle(player, token);
        DiscSlot slot = discSlot(player, true); // Check space before writing an edition or consuming a disc.
        Song edition = publishEdition(player, token);
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
        if (clock % RETRY_TICKS == 0) retryFailedSaves();
        for (var entry : new ArrayList<>(recordings.entrySet())) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null) {
                continue; // PlayerQuitEvent persists the take while the player is still accessible.
            }
            Capture capture = entry.getValue();
            if (!current(capture.token, player)) { finish(player, capture, "Recording stopped when the character changed"); continue; }
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
                    capture.checkpoint = write(capture.token.owner(), capture.snapshot((int) elapsed));
                }
            }
        }
        for (Iterator<Map.Entry<UUID, Preview>> it = previews.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            Preview preview = entry.getValue();
            int elapsed = (int) (clock - preview.start);
            if (player == null || !current(preview.token, player) || elapsed >= preview.song.lengthTicks() + settings.tailTicks()) {
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
        // The take is kept in memory even if the disk fails; it can be reviewed while it is written.
        UUID character = capture.token.owner().character();
        projects.put(character, result);
        unsaved.add(character);
        queueSave(capture.token.owner(), result);
        player.sendMessage(reason + ". Review the take: /music preview take, then /music keep or /music discard.");
    }

    private void queueSave(StudioIdentity.Owner ref, Project project) {
        pendingSaves.put(ref.character(), write(ref, project));
    }

    // One queued snapshot per owner, plus the single snapshot currently being written. Changes
    // arriving during a write go to the tail, letting other owners save before this owner runs again.
    private CompletableFuture<Void> write(StudioIdentity.Owner ref, Project project) {
        UUID owner = ref.character();
        synchronized (queuedSaves) {
            QueuedSave queued = queuedSaves.get(owner);
            if (queued != null) {
                queued.project = project;
                return queued.future;
            }
            QueuedSave next = new QueuedSave(project);
            queuedSaves.put(owner, next);
            CompletableFuture.runAsync(() -> {
                Project snapshot;
                synchronized (queuedSaves) {
                    queuedSaves.remove(owner);
                    snapshot = next.project;
                }
                saveSnapshot(ref, snapshot);
            }, checkpointWriter).whenComplete((ignored, error) -> {
                if (error == null) next.future.complete(null);
                else next.future.completeExceptionally(error);
            });
            return next.future;
        }
    }

    private void saveSnapshot(StudioIdentity.Owner ref, Project project) {
        try {
            try {
                store.saveOwned(ref, project);
            } catch (IOException ex) {
                // A failure can appear only after close has checked the pending saves. Retry it
                // once on this same worker, even after shutdown(), while the close budget remains.
                long deadline = shutdownDeadline;
                if (deadline == 0 || System.nanoTime() - deadline >= 0) throw ex;
                store.saveOwned(ref, project);
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save studio project for " + project.song().owner(), ex);
            throw new CompletionException(ex);
        }
    }

    private void completeSave(UUID owner, CompletableFuture<Void> pending) {
        pendingSaves.remove(owner);
        StudioIdentity.Owner ref = owners.get(owner);
        Player player = plugin.getServer().getPlayer(ref.account());
        if (player != null && identity.current(player).map(token -> token.owner().character().equals(owner)).orElse(false) == false) player = null;
        if (pending.isCompletedExceptionally()) {
            // Retried by the next change, every minute, on disconnect and on shutdown.
            if (player != null) player.sendMessage("Could not save your project. Your changes are kept and will be saved again; "
                    + "contact a server administrator if this continues.");
            return;
        }
        // The latest write holds the cached project, so it now matches its files.
        unsaved.remove(owner);
        if (player == null) projects.remove(owner);
    }

    private void completeSaves() {
        for (var entry : new ArrayList<>(pendingSaves.entrySet())) {
            // Only completed writes: this never waits on disk from the musical clock.
            if (entry.getValue().isDone()) completeSave(entry.getKey(), entry.getValue());
        }
    }

    // Queues another write for every cached project whose last write failed. A recording is skipped:
    // its checkpoints and final take include the cached project, which still lacks the take and would erase it.
    private void retryFailedSaves() {
        for (UUID owner : unsaved) {
            if (!pendingSaves.containsKey(owner) && recordings.values().stream().noneMatch(capture -> capture.token.owner().character().equals(owner))) queueSave(owners.get(owner), projects.get(owner));
        }
    }

    public void quit(Player player) {
        Capture capture = recordings.get(player.getUniqueId());
        if (capture != null) {
            finish(player, capture, "Recording stopped on disconnect");
        }
        stopPreview(player);
        StudioIdentity.Token token = identity.current(player).orElse(null);
        if (token == null) return;
        UUID owner = token.owner().character();
        if (pendingSaves.containsKey(owner)) return; // completeSave evicts the project once it is written.
        if (unsaved.contains(owner)) queueSave(owners.get(owner), projects.get(owner)); // Retry a write that failed.
        else projects.remove(owner); // Its files are current; the next visit reloads it.
    }

    public void end(Player player, StudioIdentity.Token ending) {
        Capture capture = recordings.get(player.getUniqueId());
        if (capture != null && capture.token.equals(ending)) finish(player, capture, "Recording stopped when the character session ended");
        stopPreview(player);
        UUID character = ending.owner().character();
        if (!pendingSaves.containsKey(character)) {
            if (unsaved.contains(character)) queueSave(ending.owner(), projects.get(character));
            else projects.remove(character);
        }
    }

    public void close() {
        if (closed) return;
        shutdownDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHUTDOWN_WAIT_SECONDS);
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
        retryFailedSaves();
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
            // Losing a take is worse than a slower shutdown, but a stuck disk must not hang it forever.
            if (!checkpointWriter.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
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

    public void failure(Player player, IOException ex) {
        plugin.getLogger().log(Level.SEVERE, "Studio storage operation failed for " + player.getUniqueId(), ex);
        player.sendMessage("Could not read/write the recording. Your disc was preserved; contact a server administrator.");
    }

    private static final class QueuedSave {
        private Project project;
        private final CompletableFuture<Void> future = new CompletableFuture<>();

        private QueuedSave(Project project) {
            this.project = project;
        }
    }

    private static final class Capture {
        private final Project project;
        private final StudioIdentity.Token token;
        private final int slot;
        private final long start;
        private final Timeline backing;
        private final List<Note> notes = new ArrayList<>();
        private CompletableFuture<Void> checkpoint = CompletableFuture.completedFuture(null);

        private Capture(Project project, int slot, long start, StudioIdentity.Token token) {
            this.token = token;
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

        private final StudioIdentity.Token token;

        private Preview(Song song, long start, StudioIdentity.Token token) {
            this.token = token;
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
