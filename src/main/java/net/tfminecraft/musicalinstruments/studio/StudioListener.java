package net.tfminecraft.musicalinstruments.studio;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.IOException;

public final class StudioListener implements Listener {
    private final Plugin plugin;
    private final StudioService studio;
    private final StudioMenu menu;

    public StudioListener(Plugin plugin, StudioService studio, StudioMenu menu) {
        this.plugin = plugin;
        this.studio = studio;
        this.menu = menu;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || event.useInteractedBlock() == Event.Result.DENY
                // Check the type first: getState() copies block entities such as chest inventories.
                || event.getClickedBlock().getType() != Material.JUKEBOX
                || !(event.getClickedBlock().getState() instanceof Jukebox box)) {
            return;
        }
        var player = event.getPlayer();
        ItemStack inserted = box.getRecord();
        ItemStack held = event.getItem();
        boolean mainHand = event.getHand() == EquipmentSlot.HAND;
        boolean itemAllowed = event.useItemInHand() != Event.Result.DENY;
        try {
            if (studio.discs().custom(inserted)) {
                event.setCancelled(true);
                if (mainHand) {
                    studio.stop(event.getClickedBlock());
                    box.eject();
                }
            } else if (!box.hasRecord() && studio.discs().custom(held)) {
                event.setCancelled(true);
                if (studio.discs().blank(held)) {
                    player.sendMessage("This disc is blank. Publish a song on it in a recording studio first.");
                } else if (!player.hasPermission("instruments.play")) {
                    player.sendMessage("You don't have permission to play recording discs.");
                } else if (itemAllowed) {
                    studio.play(event.getClickedBlock(), held);
                    // Always move exactly one physical disc, including in creative mode.
                    ItemStack remaining = held.clone();
                    remaining.setAmount(held.getAmount() - 1);
                    if (mainHand) {
                        player.getInventory().setItemInMainHand(remaining.getAmount() == 0 ? new ItemStack(Material.AIR) : remaining);
                    } else {
                        player.getInventory().setItemInOffHand(remaining.getAmount() == 0 ? new ItemStack(Material.AIR) : remaining);
                    }
                }
            }
        } catch (IllegalArgumentException ex) {
            player.sendMessage(ex.getMessage());
        } catch (IOException ex) {
            studio.failure(player, ex);
        }
    }

    // Before the instrument keyboard (HIGHEST), which skips clicks whose item use is denied.
    // Cancelled clicks still open the studio: ItemsAdder cancels clicks on its own furniture,
    // and the studio only shows the player's own projects.
    @EventHandler(priority = EventPriority.HIGH)
    public void station(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || keepsVanilla(event.getPlayer())
                || !studio.station().matches(event.getClickedBlock())) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) {
            openStation(event.getPlayer(), event.getClickedBlock());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void stationFurniture(PlayerInteractEntityEvent event) {
        if (keepsVanilla(event.getPlayer()) || !studio.station().matches(event.getRightClicked())) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) {
            openStation(event.getPlayer(), event.getRightClicked().getLocation().getBlock());
        }
    }

    // Sneaking with an item keeps vanilla behaviour, such as placing a block against the station.
    private static boolean keepsVanilla(Player player) {
        return player.isSneaking() && !player.getInventory().getItemInMainHand().getType().isAir();
    }

    private void openStation(Player player, Block station) {
        try {
            menu.open(player, station);
        } catch (IllegalArgumentException ex) {
            player.sendMessage(ex.getMessage());
        } catch (IOException ex) {
            studio.failure(player, ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        studio.stop(event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void hopper(InventoryMoveItemEvent event) {
        // Custom editions require our insertion lifecycle; vanilla discs retain hopper behavior.
        if (studio.discs().custom(event.getItem()) && event.getDestination().getHolder() instanceof Jukebox) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void extract(InventoryMoveItemEvent event) {
        // A rejected transfer restores the source disc after the event, so check on the next tick.
        // Hoppers move items constantly, so the cheap item and inventory type checks come first.
        if (studio.discs().custom(event.getItem()) && event.getSource().getType() == InventoryType.JUKEBOX
                && event.getSource().getLocation() != null) {
            Block block = event.getSource().getLocation().getBlock();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                // Do not reload a chunk that unloaded while the transfer completed.
                if (block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                        && block.getType() == Material.JUKEBOX
                        && block.getState() instanceof Jukebox box
                        && !studio.discs().custom(box.getRecord())) {
                    studio.stop(block);
                }
            });
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        studio.quit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void unload(ChunkUnloadEvent event) {
        studio.unload(event.getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void unloadWorld(WorldUnloadEvent event) {
        for (var chunk : event.getWorld().getLoadedChunks()) {
            studio.unload(event.getWorld().getUID(), chunk.getX(), chunk.getZ());
        }
    }
}
