package net.tfminecraft.musicalinstruments.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after a player plays a configured instrument note. */
public class InstrumentPlayEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String instrument;
    private final String soundKey;

    public InstrumentPlayEvent(Player player, String instrument, String soundKey) {
        this.player = player;
        this.instrument = instrument;
        this.soundKey = soundKey;
    }

    public Player getPlayer() {
        return player;
    }

    public String getInstrument() {
        return instrument;
    }

    public String getSoundKey() {
        return soundKey;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
