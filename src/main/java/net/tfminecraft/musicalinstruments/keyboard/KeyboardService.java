package net.tfminecraft.musicalinstruments.keyboard;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.time.Duration;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.title.Title;
import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import net.tfminecraft.musicalinstruments.events.InstrumentPlayEvent;
import net.tfminecraft.musicalinstruments.keyboard.KeyboardView.Cell;
import net.tfminecraft.musicalinstruments.managers.InstrumentManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;

/**
 * Runs the on-screen keyboard: opens it, plays notes for clicks and animates the circle
 * that was played by re-sending the dialog for a few frames.
 */
public final class KeyboardService implements Listener {
    /** Animation frames (lit, lit + ring 0, ring 1, ring 2), each shown for {@link #FRAME_MS}. */
    static final int FRAMES = 4;
    static final long FRAME_MS = 50L;
    /** Keyboard actions (notes and menu buttons) allowed per player per rolling second. */
    static final int ACTIONS_PER_SECOND = 20;
    /**
     * Ticks after opening in which signs of play are taken to come from the opening click, and
     * the player's view is still settling (mouse movement sent before the screen appeared).
     */
    static final int OPEN_GRACE_TICKS = 10;
    /** Degrees the view may differ from the one the keyboard was played with before it counts as looking around. */
    static final float LOOK_TOLERANCE = 1.0f;
    /** How often (ticks) the blur marker title is renewed while the keyboard is open. */
    static final int MARKER_REFRESH_TICKS = 40;
    /**
     * Shown as a title while the keyboard is open: a dot at the screen centre that the resource
     * pack's blur shader looks for, so only this menu is drawn without background blur.
     */
    static final Title BLUR_MARKER = Title.title(
            Component.text(String.valueOf(KeyboardFont.BLUR_MARKER))
                    .font(KeyboardFont.FONT)
                    .color(NamedTextColor.WHITE)
                    .shadowColor(ShadowColor.none()),
            Component.empty(),
            Title.Times.times(Duration.ZERO, Duration.ofSeconds(3), Duration.ZERO));
    private final InstrumentPlugin plugin;
    private final InstrumentManager manager;
    private final KeyboardSettings settings;
    private final KeyboardOptions options;
    private final Map<UUID, Session> sessions = new HashMap<>();
    /** Per player and independent of sessions, so reopening the keyboard does not reset it. */
    private final Map<UUID, Limiter> limits = new HashMap<>();
    /** Monotonic milliseconds. */
    private final LongSupplier clock;
    private BukkitTask ticker;

    public KeyboardService(InstrumentPlugin plugin, InstrumentManager manager, KeyboardSettings settings) {
        this(plugin, manager, settings, () -> System.nanoTime() / 1_000_000L);
    }

    KeyboardService(InstrumentPlugin plugin, InstrumentManager manager, KeyboardSettings settings, LongSupplier clock) {
        this.plugin = plugin;
        this.manager = manager;
        this.settings = settings;
        this.options = new KeyboardOptions(plugin, settings);
        this.clock = clock;
    }

    public void start() {
        this.ticker = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 1L, 1L);
    }

    public void close() {
        if (this.ticker != null) {
            this.ticker.cancel();
        }
        for (Session session : this.sessions.values()) {
            Player player = Bukkit.getPlayer(session.player);
            if (player != null && session.open) {
                player.closeDialog();
                hideMarker(player, session);
            }
        }
        this.sessions.clear();
    }

    /** The instrument a player can open the keyboard with, preferring the main hand. */
    public String heldInstrument(Player player) {
        String main = this.manager.getInstrument(player.getInventory().getItemInMainHand());
        return main != null ? main : this.manager.getInstrument(player.getInventory().getItemInOffHand());
    }

    /**
     * Opens the keyboard. {@code free} sessions (staff opening any instrument by name) skip
     * the check that the instrument is still held.
     */
    public void open(Player player, String instrument, boolean free) {
        Session session = new Session(player.getUniqueId(), instrument, free);
        session.openedTick = Bukkit.getCurrentTick();
        session.look(player.getLocation());
        this.sessions.put(player.getUniqueId(), session);
        this.send(player, session, this.clock.getAsLong());
    }

    private void send(Player player, Session session, long now) {
        KeyboardOptions.Prefs prefs = this.options.prefs(player);
        Cell[] cells = this.cells(session, prefs.rings(), now);
        session.signature = signature(prefs.size(), cells);
        session.showingFrame = !session.signature.equals(signature(prefs.size(), idle()));
        session.open = true;
        session.inOptions = false;
        session.dirty = false;
        session.lastSendTick = Bukkit.getCurrentTick();
        player.showDialog(KeyboardView.dialog(prefs.size(), cells));
        if (!session.markerShown) {
            this.showMarker(player, session);
        }
    }

    private Cell[] cells(Session session, boolean rings, long now) {
        Cell[] cells = new Cell[KeyboardFont.CELLS];
        for (int i = 0; i < cells.length; i++) {
            Cell cell = cell(now - session.started[i], rings);
            boolean tab = now - session.chordStarted[i] >= 0 && now - session.chordStarted[i] < 2 * FRAME_MS;
            cells[i] = tab ? new Cell(cell.lit(), cell.ring(), true) : cell;
        }
        return cells;
    }

    private static Cell[] idle() {
        Cell[] cells = new Cell[KeyboardFont.CELLS];
        Arrays.fill(cells, Cell.IDLE);
        return cells;
    }

    static String signature(KeyboardFont.Size size, Cell[] cells) {
        StringBuilder out = new StringBuilder(size.name());
        for (Cell cell : cells) {
            out.append(cell.lit() ? 'L' : '-').append(cell.ring());
            if (cell.chord()) {
                out.append('T');
            }
        }
        return out.toString();
    }

    /** The frame for a circle played {@code elapsed} ms ago. With rings off it only flashes. */
    static Cell cell(long elapsed, boolean rings) {
        long frame = elapsed / FRAME_MS;
        if (elapsed < 0 || frame >= FRAMES) {
            return Cell.IDLE;
        }
        if (!rings) {
            return frame < 2 ? new Cell(true, -1) : Cell.IDLE;
        }
        return switch ((int) frame) {
            case 0 -> new Cell(true, -1);
            case 1 -> new Cell(true, 0);
            case 2 -> new Cell(false, 1);
            default -> new Cell(false, 2);
        };
    }

    void tick() {
        long now = this.clock.getAsLong();
        Iterator<Session> it = this.sessions.values().iterator();
        while (it.hasNext()) {
            Session session = it.next();
            Player player = Bukkit.getPlayer(session.player);
            if (player == null) {
                it.remove();
                continue;
            }
            // The options screen is blurred like any other menu, so the marker pauses there.
            if (session.open && !session.inOptions
                    && Bukkit.getCurrentTick() - session.markerTick >= MARKER_REFRESH_TICKS) {
                this.showMarker(player, session);
            }
            // Keep going until an idle frame has gone out, even if the server stalled past the animation.
            if (!session.open || session.inOptions || player.isDead()
                    || (!session.dirty && !session.showingFrame && !session.animating(now))) {
                continue;
            }
            if (!canShow(player.getOpenInventory().getType())) {
                session.open = false;
                hideMarker(player, session);
                continue;
            }
            if (session.lastSendTick == Bukkit.getCurrentTick()) {
                continue;
            }
            KeyboardOptions.Prefs prefs = this.options.prefs(player);
            String signature = signature(prefs.size(), this.cells(session, prefs.rings(), now));
            if (session.dirty || !signature.equals(session.signature)) {
                this.send(player, session, now);
            }
        }
    }

    /** Plays a note, or with {@code chord} the chord on that note. */
    private void play(Player player, Session session, int index, boolean chord) {
        int row = index / KeyboardFont.COLUMNS;
        int column = index % KeyboardFont.COLUMNS;
        List<NoteMap.Note> notes;
        if (chord) {
            notes = NoteMap.chord(this.manager, this.settings, session.instrument, row, column);
        } else {
            NoteMap.Note note = NoteMap.resolve(this.manager, this.settings, session.instrument, row, column);
            notes = note == null ? List.of() : List.of(note);
        }
        if (notes.isEmpty()) {
            return;
        }
        float volume = (float) this.manager.getVolume(session.instrument);
        Location location = player.getLocation();
        for (NoteMap.Note note : notes) {
            player.getWorld().playSound(location, note.sound(), SoundCategory.RECORDS, volume, note.pitch());
        }
        // One play per click, also for a chord.
        this.plugin.recordInstrumentPlay(session.instrument);
        Bukkit.getPluginManager().callEvent(new InstrumentPlayEvent(player, session.instrument, notes.getFirst().sound()));
        if (this.settings.particles()) {
            // Count 0 turns the x offset into the note colour (0..1 across 24 note-block colours).
            double colour = (2 - index / KeyboardFont.COLUMNS) * 7 + index % KeyboardFont.COLUMNS;
            player.getWorld().spawnParticle(Particle.NOTE, location.add(0.0, 2.2, 0.0), 0, colour / 24.0, 0.0, 0.0, 1.0);
        }
    }

    // ----------------------------------------------------------------- clicks

    @EventHandler
    public void onCustomClick(PlayerCustomClickEvent event) {
        Key id = event.getIdentifier();
        if (!KeyboardView.NAMESPACE.equals(id.namespace())
                || !(event.getCommonConnection() instanceof PlayerGameConnection connection)) {
            return;
        }
        Player player = connection.getPlayer();
        KeyboardOptions.Choice choice = id.equals(KeyboardOptions.DONE)
                ? KeyboardOptions.Choice.read(event.getDialogResponseView())
                : null;
        Runnable task = () -> this.handleClick(player, id, choice);
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(this.plugin, task);
        }
    }

    void handleClick(Player player, Key id, KeyboardOptions.Choice choice) {
        if (!player.isOnline()) {
            return;
        }
        Session session = this.sessions.get(player.getUniqueId());
        if (session == null) {
            // e.g. after a plugin reload: the screen has nothing behind it any more.
            player.closeDialog();
            return;
        }
        long now = this.clock.getAsLong();
        if (!this.limits.computeIfAbsent(player.getUniqueId(), uuid -> new Limiter()).allow(now)) {
            return;
        }
        int note = KeyboardView.cellOf(id);
        int chord = KeyboardView.chordOf(id);
        int index = Math.max(note, chord);
        if (index >= 0) {
            if (session.inOptions || player.isDead() || !player.hasPermission("instruments.use")) {
                return; // a stale click from a screen that is no longer the keyboard
            }
            if (!session.free && !session.instrument.equals(this.heldInstrument(player))) {
                this.sessions.remove(player.getUniqueId());
                player.closeDialog();
                hideMarker(player, session);
                return;
            }
            session.open = true;
            session.look(player.getLocation());
            if (chord >= 0) {
                // Light the mark and every note of the chord so it reads as a chord.
                session.chordStarted[index] = now;
                for (int cell : NoteMap.chordCells(index / KeyboardFont.COLUMNS, index % KeyboardFont.COLUMNS)) {
                    session.started[cell] = now;
                }
            } else {
                session.started[index] = now;
            }
            this.play(player, session, index, chord >= 0);
            // Re-send at once (lit circle, clears the client's focus outline), at most once a tick.
            if (session.lastSendTick == Bukkit.getCurrentTick()) {
                session.dirty = true;
            } else {
                this.send(player, session, now);
            }
            return;
        }
        if (id.equals(KeyboardView.OPTIONS)) {
            session.inOptions = true;
            hideMarker(player, session);
            player.showDialog(this.options.dialog(player, session.instrument));
            return;
        }
        if (id.equals(KeyboardOptions.DONE)) {
            if (choice != null) {
                this.options.save(player, choice);
            }
            Arrays.fill(session.started, Long.MIN_VALUE / 2);
            Arrays.fill(session.chordStarted, Long.MIN_VALUE / 2);
            this.send(player, session, now);
        }
    }

    private void showMarker(Player player, Session session) {
        player.showTitle(BLUR_MARKER);
        session.markerTick = Bukkit.getCurrentTick();
        session.markerShown = true;
    }

    private static void hideMarker(Player player, Session session) {
        if (session.markerShown) {
            player.clearTitle();
            session.markerShown = false;
        }
    }

    // ------------------------------------------------------- opening/closing

    // Interactable blocks (jukeboxes for the studio, doors, chests) are left alone entirely.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        this.markClosed(player);
        if (!this.settings.openOnRightClick() || !player.hasPermission("instruments.use")) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        if (action == Action.RIGHT_CLICK_BLOCK && (event.getClickedBlock() == null
                || event.getClickedBlock().getType().isInteractable()
                || event.useInteractedBlock() == Event.Result.DENY)) {
            return;
        }
        String instrument = this.manager.getInstrument(event.getItem());
        if (instrument == null) {
            return;
        }
        if (event.getHand() == EquipmentSlot.OFF_HAND
                && player.getInventory().getItemInMainHand().getType() != Material.AIR) {
            return;
        }
        Session current = this.sessions.get(player.getUniqueId());
        if (current != null && current.openedTick == Bukkit.getCurrentTick()) {
            return; // both hands fired this tick
        }
        event.setUseItemInHand(Event.Result.DENY);
        this.open(player, instrument, false);
    }

    /** False while a container is open: a re-send would replace that screen. */
    static boolean canShow(InventoryType type) {
        return type == InventoryType.CRAFTING || type == InventoryType.CREATIVE;
    }

    /**
     * Escape closes the dialog without telling the server, so stop animating on any sign of play.
     * Signs right after opening belong to the click that opened it (a right-click on a block also
     * fires the off hand's block click and can swing the arm), so they are ignored.
     */
    private void markClosed(Player player) {
        Session session = this.sessions.get(player.getUniqueId());
        if (session != null && !settling(session)) {
            session.open = false;
            hideMarker(player, session);
        }
    }

    private static boolean settling(Session session) {
        return Bukkit.getCurrentTick() - session.openedTick < OPEN_GRACE_TICKS;
    }

    /**
     * Turning means the screen is gone (the view cannot turn while it is open). The view is
     * compared with the one the keyboard was last played with, not with the event's start:
     * Paper only fires the event past a threshold, so its start can be several packets old.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Session session = this.sessions.get(event.getPlayer().getUniqueId());
        if (session == null || !session.open) {
            return;
        }
        Location to = event.getTo();
        if (settling(session)) {
            session.look(to);
        } else if (yawDifference(session.yaw, to.getYaw()) > LOOK_TOLERANCE
                || Math.abs(session.pitch - to.getPitch()) > LOOK_TOLERANCE) {
            this.markClosed(event.getPlayer());
        }
    }

    /** Smallest angle between two yaws, in degrees (clients send unwrapped yaws such as 370). */
    static float yawDifference(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        this.markClosed(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent event) {
        this.markClosed(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent event) {
        this.markClosed(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        this.markClosed(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventory(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            this.markClosed(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.sessions.remove(event.getPlayer().getUniqueId());
        this.limits.remove(event.getPlayer().getUniqueId());
    }

    /** True while the player has a keyboard session that is believed to be on screen. */
    boolean isOpen(Player player) {
        Session session = this.sessions.get(player.getUniqueId());
        return session != null && session.open;
    }

    /** Rolling one-second window of keyboard actions. */
    static final class Limiter {
        private final long[] recent = new long[ACTIONS_PER_SECOND];
        private int next;

        Limiter() {
            Arrays.fill(this.recent, Long.MIN_VALUE / 2);
        }

        boolean allow(long now) {
            if (now - this.recent[this.next] < 1000L) {
                return false;
            }
            this.recent[this.next] = now;
            this.next = (this.next + 1) % this.recent.length;
            return true;
        }
    }

    private static final class Session {
        final UUID player;
        final String instrument;
        final boolean free;
        final long[] started = new long[KeyboardFont.CELLS];
        final long[] chordStarted = new long[KeyboardFont.CELLS];
        boolean open;
        boolean inOptions;
        boolean dirty;
        /** The last frame sent was not the idle keyboard. */
        boolean showingFrame;
        int lastSendTick = -1;
        int openedTick = -1;
        int markerTick = Integer.MIN_VALUE / 2;
        boolean markerShown;
        String signature = "";
        /** The view the keyboard was opened or last played with. */
        float yaw;
        float pitch;

        Session(UUID player, String instrument, boolean free) {
            this.player = player;
            this.instrument = instrument;
            this.free = free;
            Arrays.fill(this.started, Long.MIN_VALUE / 2);
            Arrays.fill(this.chordStarted, Long.MIN_VALUE / 2);
        }

        void look(Location location) {
            this.yaw = location.getYaw();
            this.pitch = location.getPitch();
        }

        boolean animating(long now) {
            for (long start : this.started) {
                if (now - start < FRAMES * FRAME_MS) {
                    return true;
                }
            }
            return false;
        }
    }
}
