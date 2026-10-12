package net.tfminecraft.musicalinstruments.studio;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The block or furniture that opens the recording studio, configured in studio.yml as
 * {@code v.<material>} or {@code ia.<namespace:id>}. ItemsAdder is reached through reflection,
 * like the instrument items, so it stays optional.
 */
public final class StudioStation {
    /** Squared reach within which a player can keep using the station's controls. */
    private static final double REACH_SQUARED = 36;
    private static final Map<String, Optional<Method>> METHODS = new ConcurrentHashMap<>();
    private final Material material;
    private final String itemsAdderId;

    private StudioStation(Material material, String itemsAdderId) {
        this.material = material;
        this.itemsAdderId = itemsAdderId;
    }

    /** A station nobody can use; the studio then opens only by staff command. */
    static StudioStation none() {
        return new StudioStation(null, null);
    }

    static StudioStation vanilla(Material material) {
        return new StudioStation(material, null);
    }

    static StudioStation itemsAdder(String id) {
        return new StudioStation(null, id);
    }

    public static StudioStation parse(String path, Logger logger) {
        if (path == null || path.isBlank()) {
            return none();
        }
        String[] parts = path.strip().split("\\.", 2);
        String type = parts[0].toLowerCase(Locale.ROOT);
        if (parts.length < 2 || parts[1].isBlank() || !type.equals("v") && !type.equals("ia")) {
            logger.warning("Invalid studio station '" + path + "'. Expected v.<material> or ia.<namespace:id>.");
            return none();
        }
        if (type.equals("ia")) {
            if (!parts[1].matches("[a-z0-9._-]+:[a-z0-9/._-]+")) {
                logger.warning("Invalid studio station '" + path + "'. Expected ia.<namespace:id>.");
                return none();
            }
            // ItemsAdder may enable after this plugin; warnIfUnavailable checks it once all have.
            return itemsAdder(parts[1]);
        }
        Material material = Material.matchMaterial(parts[1].toLowerCase(Locale.ROOT));
        if (material == null || !material.isBlock() || material.isAir()) {
            logger.warning("Studio station '" + path + "' is not a block.");
            return none();
        }
        if (material == Material.JUKEBOX) {
            // Right-clicking a jukebox inserts and ejects recorded discs.
            logger.warning("Studio station '" + path + "' cannot be a jukebox, which plays recorded discs.");
            return none();
        }
        return vanilla(material);
    }

    public boolean enabled() {
        return material != null || itemsAdderId != null;
    }

    /** Call after every plugin has enabled; until ItemsAdder is running, its stations match nothing. */
    public void warnIfUnavailable(Logger logger) {
        if (itemsAdderId != null && !pluginEnabled("ItemsAdder")) {
            logger.warning("Studio station 'ia." + itemsAdderId + "' requires ItemsAdder, which is not enabled.");
        }
    }

    /** True for the station block, or a block holding the station's custom block or furniture. */
    public boolean matches(Block block) {
        if (block == null || !enabled()) {
            return false;
        }
        if (material != null) {
            return block.getType() == material;
        }
        // This runs on every right-click on a block; without ItemsAdder each lookup would fail slowly.
        if (!pluginEnabled("ItemsAdder")) {
            return false;
        }
        if (itemsAdderId.equals(namespacedId("dev.lone.itemsadder.api.CustomBlock", "byAlreadyPlaced", Block.class, block))) {
            return true;
        }
        if (itemsAdderId.equals(namespacedId("dev.lone.itemsadder.api.CustomFurniture", "byAlreadySpawned", Block.class, block))) {
            return true;
        }
        // Older ItemsAdder versions may only expose the entity lookup.
        for (Entity entity : block.getWorld().getNearbyEntities(BoundingBox.of(block))) {
            if (matches(entity)) {
                return true;
            }
        }
        return false;
    }

    public boolean matches(Entity entity) {
        return itemsAdderId != null && entity != null && pluginEnabled("ItemsAdder")
                && itemsAdderId.equals(namespacedId("dev.lone.itemsadder.api.CustomFurniture", "byAlreadySpawned", Entity.class, entity));
    }

    /** Throws unless the player may use the studio here; staff may use it without a station. */
    public void require(Player player, Block station) {
        if (!player.hasPermission("instruments.record")) throw new IllegalArgumentException("You don't have permission to use the recording studio");
        if (station == null && player.hasPermission("instruments.studio")) return;
        if (station == null || !station.getWorld().equals(player.getWorld())
                || station.getLocation().distanceSquared(player.getLocation()) > REACH_SQUARED || !matches(station)) {
            throw new IllegalArgumentException("Use a recording station within 5 blocks to open the studio");
        }
    }

    public Block require(Player player, Location location) {
        if (location == null) { require(player, (Block) null); return null; }
        if (!player.getWorld().equals(location.getWorld()) || location.distanceSquared(player.getLocation()) > REACH_SQUARED
                || !player.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            throw new IllegalArgumentException("Stay near your recording station to use its controls");
        }
        Block station = location.getBlock();
        require(player, station);
        return station;
    }

    // CustomBlock.byAlreadyPlaced(block) / CustomFurniture.byAlreadySpawned(entity), then getNamespacedID().
    private static String namespacedId(String type, String lookup, Class<?> parameter, Object target) {
        try {
            Object custom = method(type + "#" + lookup + "#" + parameter.getName(),
                    () -> Class.forName(type).getMethod(lookup, parameter)).invoke(null, target);
            return custom == null ? null : (String) method(custom.getClass().getName() + "#getNamespacedID",
                    () -> custom.getClass().getMethod("getNamespacedID")).invoke(custom);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    @FunctionalInterface
    private interface Lookup { Method find() throws ReflectiveOperationException; }

    // matches(Block) runs on every right-click on a block and every studio menu refresh, so each
    // reflective lookup is resolved once. Lookups run only while ItemsAdder is enabled, so a method
    // missing from that ItemsAdder version stays missing and is remembered too.
    private static Method method(String key, Lookup lookup) throws ReflectiveOperationException {
        Optional<Method> method = METHODS.get(key);
        if (method == null) {
            try {
                method = Optional.of(lookup.find());
            } catch (NoSuchMethodException ex) {
                method = Optional.empty();
            }
            METHODS.put(key, method);
        }
        return method.orElseThrow(() -> new NoSuchMethodException(key));
    }

    private static boolean pluginEnabled(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled();
    }
}
