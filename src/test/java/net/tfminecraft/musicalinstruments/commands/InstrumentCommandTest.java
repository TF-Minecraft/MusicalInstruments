package net.tfminecraft.musicalinstruments.commands;

import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import net.tfminecraft.musicalinstruments.managers.InstrumentManager;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InstrumentCommandTest {
    private static final String USAGE = "§cUsage: /instruments <keybinds|list|give|reload>";
    private static final String NO_PERMISSION = "§cYou don't have permission to do that!";
    private static final String PLAYERS_ONLY = "§cOnly players can use this command!";

    private InstrumentPlugin plugin;
    private InstrumentManager manager;
    private InstrumentCommand handler;
    private Command command;
    private PlayerMock player;
    private PlayerMock operator;
    private ConsoleCommandSenderMock console;

    @BeforeEach
    void setUp() {
        ServerMock server = MockBukkit.mock();
        plugin = mock(InstrumentPlugin.class);
        manager = mock(InstrumentManager.class);
        handler = new InstrumentCommand(plugin, manager);
        command = mock(Command.class);
        player = server.addPlayer();
        operator = server.addPlayer();
        operator.setOp(true);
        console = (ConsoleCommandSenderMock) server.getConsoleSender();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void run(CommandSender sender, String... args) {
        assertTrue(handler.onCommand(sender, command, "instruments", args));
    }

    private List<String> complete(CommandSender sender, String... args) {
        return handler.onTabComplete(sender, command, "instruments", args);
    }

    private void loadInstruments(String... instruments) {
        Set<String> loaded = new LinkedHashSet<>(List.of(instruments));
        when(manager.getAllInstruments()).thenReturn(loaded);
    }

    @Test
    void showsUsageWithoutAKnownSubcommand() {
        run(operator);
        assertEquals(USAGE, operator.nextMessage());
        run(operator, "tune");
        assertEquals(USAGE, operator.nextMessage());
        assertNull(operator.nextMessage());
    }

    @Test
    void everySubcommandChecksPermission() {
        for (String subcommand : List.of("keybinds", "list", "give", "reload")) {
            run(player, subcommand);
            assertEquals(NO_PERMISSION, player.nextMessage());
        }
        assertNull(player.nextMessage());
        verifyNoInteractions(plugin, manager);
    }

    @Test
    void playerOnlySubcommandsRejectTheConsole() {
        run(console, "keybinds");
        assertEquals(PLAYERS_ONLY, console.nextMessage());
        run(console, "give", "lute");
        assertEquals(PLAYERS_ONLY, console.nextMessage());
        assertNull(console.nextMessage());
        verifyNoInteractions(manager);
    }

    @Test
    void keybindsRequireAnOffHandInstrument() {
        run(operator, "keybinds");
        assertEquals("§cYou must be holding an instrument in your off-hand!", operator.nextMessage());
        assertNull(operator.nextMessage());
    }

    @Test
    void keybindsSendEachConfiguredLine() {
        ItemStack lute = new ItemStack(Material.PAPER);
        operator.getInventory().setItemInOffHand(lute);
        when(manager.getInstrument(lute)).thenReturn("lute");
        when(manager.getKeybindMessage("lute")).thenReturn("§aUse keys 1-8\n§e1-[C] 2-[D]\n\n");

        run(operator, "KEYBINDS");

        assertEquals("§aUse keys 1-8", operator.nextMessage());
        assertEquals("§e1-[C] 2-[D]", operator.nextMessage());
        assertNull(operator.nextMessage());
    }

    @Test
    void keybindsFallBackWhenNoMessageIsConfigured() {
        when(manager.getInstrument(any())).thenReturn("lute");

        run(operator, "keybinds");

        assertEquals("§aYour instrument keybinds were not defined in the config.", operator.nextMessage());
        assertNull(operator.nextMessage());
    }

    @Test
    void listsLoadedInstruments() {
        loadInstruments();
        run(console, "list");
        assertEquals("§cNo instruments are loaded.", console.nextMessage());

        loadInstruments("lute", "flute");
        run(console, "list");
        assertEquals("§aLoaded instruments (§62§a):", console.nextMessage());
        assertEquals("§elute§7, §eflute", console.nextMessage());
        assertNull(console.nextMessage());
    }

    @Test
    void giveRequiresAnInstrumentName() {
        run(operator, "give");
        assertEquals("§cUsage: /instruments give <instrument>", operator.nextMessage());
        assertNull(operator.nextMessage());
    }

    @Test
    void giveRejectsUnknownInstruments() {
        run(operator, "give", "Harp");
        assertEquals("§cUnknown instrument: §eHarp", operator.nextMessage());
        assertNull(operator.nextMessage());
        verify(manager, never()).getInstrumentItem(any());
    }

    @Test
    void giveAddsTheInstrumentToTheInventory() {
        ItemStack lyre = new ItemStack(Material.PAPER);
        when(manager.findInstrument("lyre")).thenReturn("Lyre");
        when(manager.getInstrumentItem("Lyre")).thenReturn(lyre);

        run(operator, "give", "lyre");

        assertTrue(operator.getInventory().containsAtLeast(lyre, 1));
        assertTrue(operator.getWorld().getEntitiesByClass(Item.class).isEmpty());
        assertEquals("§aYou received: §eLyre", operator.nextMessage());
        assertNull(operator.nextMessage());
    }

    @Test
    void giveDropsTheInstrumentWhenTheInventoryIsFull() {
        ItemStack lute = new ItemStack(Material.PAPER);
        when(manager.findInstrument("lute")).thenReturn("lute");
        when(manager.getInstrumentItem("lute")).thenReturn(lute);
        // MockBukkit also fills armour and off-hand slots when adding items.
        for (int slot = 0; slot < operator.getInventory().getSize(); slot++) {
            operator.getInventory().setItem(slot, new ItemStack(Material.DIRT, 64));
        }

        run(operator, "give", "lute");

        Item dropped = operator.getWorld().getEntitiesByClass(Item.class).iterator().next();
        assertTrue(dropped.getItemStack().isSimilar(lute));
        assertEquals("§aYou received: §elute", operator.nextMessage());
    }

    @Test
    void reloadReloadsConfigAndTemplates() {
        loadInstruments("lute");

        run(console, "reload");

        var order = inOrder(plugin, manager);
        order.verify(plugin).reloadConfig();
        order.verify(manager).loadTemplates();
        assertEquals("§aConfig reloaded. §e1 §ainstrument(s) loaded.", console.nextMessage());
        assertNull(console.nextMessage());
    }

    @Test
    void completesPermittedSubcommands() {
        assertEquals(List.of(), complete(player, ""));
        assertEquals(List.of("keybinds", "list", "give", "reload"), complete(operator, ""));
        assertEquals(List.of("reload"), complete(operator, "R"));
        assertEquals(List.of(), complete(operator, "x"));
    }

    @Test
    void completesInstrumentNamesForGive() {
        // Suggestions keep the config's case; give accepts them through findInstrument.
        loadInstruments("lute", "flute", "Lyre");

        assertEquals(List.of("lute", "Lyre"), complete(operator, "GIVE", "l"));
        assertEquals(List.of(), complete(player, "give", ""));
        assertEquals(List.of(), complete(operator, "list", ""));
        assertEquals(List.of(), complete(operator, "give", "lute", ""));
    }
}
