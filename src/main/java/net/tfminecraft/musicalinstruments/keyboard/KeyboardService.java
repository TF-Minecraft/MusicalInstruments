package net.tfminecraft.musicalinstruments.keyboard;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.key.Key;
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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * Runs the on-screen keyboard: opens it, plays notes for clicks and animates the circle
 * that was played by re-sending the dialog for a few ticks.
 */
public final class KeyboardService implements Listener {
    /** Animation frames (lit, lit + ring 0, ring 1, ring 2), each shown for {@link #FRAME_MS}. */
    private static final int FRAMES = 4;
    private static final long FRAME_MS = 50L;
    private final InstrumentPlugin plugin;
    private final InstrumentManager manager;
    private final KeyboardSettings settings;
    private final KeyboardOptions options;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private BukkitTask ticker;

    public KeyboardService(InstrumentPlugin plugin, InstrumentManager manager, KeyboardSettings settings) {
        this.plugin = plugin;
        this.manager = manager;
        this.settings = settings;
        this.options = new KeyboardOptions(plugin, settings);
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
            }
        }
        this.sessions.clear();
    }

    public KeyboardSettings settings() {
        return this.settings;
    }

    /** The instrument a player can open the keyboard with, preferring the main hand. */
    public String heldInstrument(Player player) {
        String main = this.manager.getInstrument(player.getInventory().getItemInMainHand());
        return main != null ? main : this.manager.getInstrument(player.getInventory().getItemInOffHand());
    }

    /**
     * Opens the keyboard. {@code free} sessions (admins opening any instrument by name) skip
     * the check that the instrument is still held.
     */
    public void open(Player player, String instrument, boolean free) {
        Session session = new Session(player.getUniqueId(), instrument, free);
        session.openedTick = Bukkit.getCurrentTick();
        this.sessions.put(player.getUniqueId(), session);
        this.send(player, session);
    }

    private void send(Player player, Session session) {
        KeyboardOptions.Prefs prefs = this.options.prefs(player);
        Cell[] cells = new Cell[KeyboardFont.CELLS];
        StringBuilder signature = new StringBuilder(prefs.size().name());
        for (int i = 0; i < cells.length; i++) {
            cells[i] = this.cell(session, i, prefs.rings());
            signature.append(cells[i].lit() ? 'L' : '-').append(cells[i].ring());
        }
        session.signature = signature.toString();
        session.open = true;
        session.inOptions = false;
        session.dirty = false;
        session.lastSendTick = Bukkit.getCurrentTick();
        player.showDialog(KeyboardView.dialog(prefs.size(), cells));
    }

    private Cell cell(Session session, int index, boolean rings) {
        long age = (System.currentTimeMillis() - session.started[index]) / FRAME_MS;
        if (age < 0 || age >= FRAMES) {
            return Cell.IDLE;
        }
        if (!rings) {
            return age < 2 ? new Cell(true, -1) : Cell.IDLE;
        }
        return switch ((int) age) {
            case 0 -> new Cell(true, -1);
            case 1 -> new Cell(true, 0);
            case 2 -> new Cell(false, 1);
            default -> new Cell(false, 2);
        };
    }

    private void tick() {
        long now = System.currentTimeMillis();
        Iterator<Session> it = this.sessions.values().iterator();
        while (it.hasNext()) {
            Session session = it.next();
            Player player = Bukkit.getPlayer(session.player);
            if (player == null) {
                it.remove();
                continue;
            }
            if (!session.open || session.inOptions || player.isDead()
                    || (!session.dirty && !session.animating(now))) {
                continue;
            }
            if (!this.canShow(player)) {
                session.open = false;
                continue;
            }
            if (session.lastSendTick == Bukkit.getCurrentTick()) {
                continue;
            }
            KeyboardOptions.Prefs prefs = this.options.prefs(player);
            StringBuilder signature = new StringBuilder(prefs.size().name());
            for (int i = 0; i < KeyboardFont.CELLS; i++) {
                Cell cell = this.cell(session, i, prefs.rings());
                signature.append(cell.lit() ? 'L' : '-').append(cell.ring());
            }
            if (session.dirty || !signature.toString().equals(session.signature)) {
                this.send(player, session);
            }
        }
    }

    private void play(Player player, Session session, int index) {
        KeyboardOptions.Prefs prefs = this.options.prefs(player);
        NoteMap.Note note = NoteMap.resolve(this.manager, this.settings, session.instrument,
                index / KeyboardFont.COLUMNS, index % KeyboardFont.COLUMNS, prefs.chords());
        if (note == null) {
            return;
        }
        float volume = (float) this.manager.getVolume(session.instrument);
        Location location = player.getLocation();
        player.getWorld().playSound(location, note.sound(), SoundCategory.RECORDS, volume, note.pitch());
        this.plugin.recordInstrumentPlay(session.instrument);
        Bukkit.getPluginManager().callEvent(new InstrumentPlayEvent(player, session.instrument, note.sound()));
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
        KeyboardOptions.Choice choice = id.equals(KeyboardOptions.DONE) || id.equals(KeyboardOptions.CLOSE)
                ? KeyboardOptions.Choice.read(event.getDialogResponseView())
                : null;
        Runnable task = () -> this.handleClick(player, id, choice);
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(this.plugin, task);
        }
    }

    private void handleClick(Player player, Key id, KeyboardOptions.Choice choice) {
        if (!player.isOnline()) {
            return;
        }
        Session session = this.sessions.get(player.getUniqueId());
        if (session == null) {
            // e.g. after a plugin reload: the screen has nothing behind it any more.
            player.closeDialog();
            return;
        }
        int index = KeyboardView.cellOf(id);
        if (index >= 0) {
            if (!session.free && !session.instrument.equals(this.heldInstrument(player))) {
                this.sessions.remove(player.getUniqueId());
                player.closeDialog();
                return;
            }
            long now = System.currentTimeMillis();
            if (!session.allowNote(now)) {
                return;
            }
            session.open = true;
            session.started[index] = now;
            this.play(player, session, index);
            // Re-send at once (lit circle, clears the client's focus outline), at most once a tick.
            if (session.lastSendTick == Bukkit.getCurrentTick()) {
                session.dirty = true;
            } else {
                this.send(player, session);
            }
            return;
        }
        if (id.equals(KeyboardView.OPTIONS)) {
            session.inOptions = true;
            player.showDialog(this.options.dialog(player, session.instrument, this.manager));
            return;
        }
        if (id.equals(KeyboardOptions.DONE)) {
            if (choice != null) {
                this.options.save(player, choice);
            }
            Arrays.fill(session.started, Long.MIN_VALUE / 2);
            this.send(player, session);
            return;
        }
        if (id.equals(KeyboardOptions.CLOSE)) {
            if (choice != null) {
                this.options.save(player, choice);
            }
            this.sessions.remove(player.getUniqueId());
            player.closeDialog();
        }
    }

    // ------------------------------------------------------- opening/closing

    // Runs after the studio's jukebox handler (HIGHEST) and leaves interactable blocks alone.
    @EventHandler(priority = EventPriority.MONITOR)
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
        if (event.useItemInHand() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        if (action == Action.RIGHT_CLICK_BLOCK && (event.getClickedBlock() == null
                || event.getClickedBlock().getType().isInteractable()
                || event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY)) {
            return;
        }
        ItemStack item = event.getItem();
        String instrument = this.manager.getInstrument(item);
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
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        this.open(player, instrument, false);
    }

    /** False while a container is open: a re-send would replace that screen. */
    private boolean canShow(Player player) {
        org.bukkit.event.inventory.InventoryType type = player.getOpenInventory().getType();
        return type == org.bukkit.event.inventory.InventoryType.CRAFTING
                || type == org.bukkit.event.inventory.InventoryType.CREATIVE;
    }

    /** Escape closes the dialog without telling the server, so stop animating on any sign of play. */
    private void markClosed(Player player) {
        Session session = this.sessions.get(player.getUniqueId());
        if (session != null) {
            session.open = false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (Math.abs(from.getYaw() - to.getYaw()) > 0.01f || Math.abs(from.getPitch() - to.getPitch()) > 0.01f) {
            this.markClosed(event.getPlayer());
        }
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
    }

    private static final class Session {
        /** At most this many notes per rolling second. */
        private static final int NOTES_PER_SECOND = 20;
        final UUID player;
        final String instrument;
        final boolean free;
        final long[] started = new long[KeyboardFont.CELLS];
        private final long[] recent = new long[NOTES_PER_SECOND];
        private int recentIndex;
        boolean open;
        boolean inOptions;
        boolean dirty;
        int lastSendTick = -1;
        int openedTick = -1;
        String signature = "";

        Session(UUID player, String instrument, boolean free) {
            this.player = player;
            this.instrument = instrument;
            this.free = free;
            Arrays.fill(this.started, Long.MIN_VALUE / 2);
            Arrays.fill(this.recent, Long.MIN_VALUE / 2);
        }

        boolean allowNote(long now) {
            if (now - this.recent[this.recentIndex] < 1000L) {
                return false;
            }
            this.recent[this.recentIndex] = now;
            this.recentIndex = (this.recentIndex + 1) % this.recent.length;
            return true;
        }

        boolean animating(long now) {
            for (long start : this.started) {
                if (now - start <= FRAMES * FRAME_MS + FRAME_MS) {
                    return true;
                }
            }
            return false;
        }
    }
}
