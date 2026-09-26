package net.tfminecraft.musicalinstruments.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import net.tfminecraft.musicalinstruments.events.InstrumentPlayEvent;
import net.tfminecraft.musicalinstruments.managers.InstrumentManager;

// ====================================
// Handles instrument-related events.
// Responsible for playing sounds when players change hotbar slots while holding an instrument.
// ====================================
public class InstrumentListener implements Listener {
    
    // Hotbar slot 9, which the player returns to after each note.
    private static final int RESET_SLOT = 8;

    private final InstrumentPlugin plugin;
    private final InstrumentManager manager;

    public InstrumentListener(InstrumentPlugin plugin, InstrumentManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerHotbarChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        String instrument = manager.getInstrument(player.getInventory().getItemInOffHand());
        
        // Pressing the reset slot again sends nothing, so it cannot play a note.
        if (instrument == null || event.getNewSlot() == RESET_SLOT) {
            return;
        }
        
        // Get hotbar slot (1-9)
        int newSlot = event.getNewSlot() + 1;
        
        // Get sound for this slot and sneak(shifting) state
        String soundKey = manager.getSoundKey(instrument, newSlot, player.isSneaking());
        
        if (soundKey == null) {
            return;
        }
        
        // Get volume and pitch
        double volume = manager.getVolume(instrument);
        double pitch = manager.getPitch(instrument);
        
        // Play sound at player's location
        player.getWorld().playSound(
            player.getLocation(), 
            soundKey, 
            SoundCategory.RECORDS, 
            (float) volume, 
            (float) pitch
        );
        
        plugin.recordInstrumentPlay(instrument);
        Bukkit.getPluginManager().callEvent(new InstrumentPlayEvent(player, instrument, soundKey));

        // Spawn particle effect
        player.getWorld().spawnParticle(
            Particle.NOTE, 
            player.getLocation().add(0.0, 2.0, 0.0), 
            1, 
            0.0, 
            0.0, 
            0.0, 
            1.0
        );
        
        // Switch back to 9th hotbar slot after playing (so we can use the same note multiple times).
        // The event must be cancelled too: otherwise the server applies the pressed slot after this
        // handler, while the client stays on slot 9, and Paper then ignores the next press of that key.
        player.getInventory().setHeldItemSlot(RESET_SLOT);
        event.setCancelled(true);
    }
}
