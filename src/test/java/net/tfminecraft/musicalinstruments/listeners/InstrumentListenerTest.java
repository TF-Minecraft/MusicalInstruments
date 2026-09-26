package net.tfminecraft.musicalinstruments.listeners;

import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import net.tfminecraft.musicalinstruments.events.InstrumentPlayEvent;
import net.tfminecraft.musicalinstruments.managers.InstrumentManager;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.sound.AudioExperience;
import org.mockbukkit.mockbukkit.util.SpawnedParticle;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InstrumentListenerTest {
    private ServerMock server;
    private InstrumentPlugin plugin;
    private InstrumentManager manager;
    private PlayerMock player;
    private final List<InstrumentPlayEvent> played = new ArrayList<>();

    // Listens the way other plugins (such as ActivityTF) consume notes.
    public class PlayListener implements Listener {
        @EventHandler
        public void onInstrumentPlay(InstrumentPlayEvent event) {
            played.add(event);
        }
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = mock(InstrumentPlugin.class);
        manager = mock(InstrumentManager.class);
        server.getPluginManager().registerEvents(new InstrumentListener(plugin, manager), MockBukkit.createMockPlugin());
        server.getPluginManager().registerEvents(new PlayListener(), MockBukkit.createMockPlugin("ActivityTF"));
        player = server.addPlayer();
        // Start on slot 5, which no test presses.
        player.getInventory().setHeldItemSlot(4);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void holdLute() {
        when(manager.getInstrument(any())).thenReturn("lute");
    }

    // Presses a hotbar key the way Paper 1.21.10 handles it (ServerGamePacketListenerImpl.handleSetCarriedItem):
    // pressing the selected slot fires nothing, and an uncancelled event then selects the pressed slot.
    private PlayerItemHeldEvent pressSlot(int slot) {
        int selected = player.getInventory().getHeldItemSlot();
        if (slot - 1 == selected) {
            return null;
        }
        PlayerItemHeldEvent event = new PlayerItemHeldEvent(player, selected, slot - 1);
        server.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            player.getInventory().setHeldItemSlot(slot - 1);
        }
        return event;
    }

    @Test
    void ignoresSlotChangesWithoutAnInstrument() {
        PlayerItemHeldEvent event = pressSlot(1);

        verify(manager).getInstrument(any());
        verifyNoMoreInteractions(manager);
        assertFalse(event.isCancelled());
        assertEquals(0, player.getInventory().getHeldItemSlot());
        assertTrue(player.getHeardSounds().isEmpty());
        assertTrue(played.isEmpty());
        verifyNoInteractions(plugin);
    }

    @Test
    void playsTheNoteForTheSelectedSlot() {
        holdLute();
        when(manager.getSoundKey("lute", 2, false)).thenReturn("instruments.lute_2d_single");
        when(manager.getVolume("lute")).thenReturn(4.0);
        when(manager.getPitch("lute")).thenReturn(0.5);
        Location location = player.getLocation();

        PlayerItemHeldEvent event = pressSlot(2);

        AudioExperience sound = player.getHeardSounds().getFirst();
        assertEquals("instruments.lute_2d_single", sound.getSound());
        assertEquals(SoundCategory.RECORDS, sound.getCategory());
        assertEquals(location, sound.getLocation());
        assertEquals(4.0f, sound.getVolume());
        assertEquals(0.5f, sound.getPitch());

        SpawnedParticle particle = ((WorldMock) player.getWorld()).getSpawnedParticles().getFirst();
        assertEquals(Particle.NOTE, particle.particle());
        assertEquals(location.getY() + 2.0, particle.y());
        assertEquals(1, particle.count());

        verify(plugin).recordInstrumentPlay("lute");
        InstrumentPlayEvent note = played.getFirst();
        assertSame(player, note.getPlayer());
        assertEquals("lute", note.getInstrument());
        assertEquals("instruments.lute_2d_single", note.getSoundKey());

        // Cancelling keeps the server on slot 9 too, instead of applying the pressed slot afterwards.
        assertTrue(event.isCancelled());
        assertEquals(8, player.getInventory().getHeldItemSlot());
    }

    @Test
    void repeatsTheSameNote() {
        holdLute();
        when(manager.getSoundKey("lute", 1, false)).thenReturn("instruments.lute_1c_single");

        pressSlot(1);
        pressSlot(1);
        pressSlot(1);

        assertEquals(3, player.getHeardSounds().size());
        assertEquals(3, played.size());
        verify(plugin, times(3)).recordInstrumentPlay("lute");
    }

    @Test
    void sneakingSelectsTheAlternateNote() {
        holdLute();
        player.setSneaking(true);
        when(manager.getSoundKey("lute", 3, true)).thenReturn("instruments.lute_3e_chord");

        pressSlot(3);

        assertEquals("instruments.lute_3e_chord", player.getHeardSounds().getFirst().getSound());
    }

    @Test
    void unmappedSlotsChangeSlotNormally() {
        holdLute();

        PlayerItemHeldEvent event = pressSlot(9);

        verify(manager).getSoundKey("lute", 9, false);
        assertFalse(event.isCancelled());
        assertEquals(8, player.getInventory().getHeldItemSlot());
        assertTrue(player.getHeardSounds().isEmpty());
        verifyNoInteractions(plugin);
    }

    @Test
    void ignoresSlotChangesCancelledByOtherPlugins() {
        holdLute();
        when(manager.getSoundKey(any(), anyInt(), anyBoolean())).thenReturn("instruments.lute_1c_single");
        PlayerItemHeldEvent event = new PlayerItemHeldEvent(player, 0, 1);
        event.setCancelled(true);

        server.getPluginManager().callEvent(event);

        verifyNoInteractions(manager, plugin);
        assertTrue(player.getHeardSounds().isEmpty());
    }
}
