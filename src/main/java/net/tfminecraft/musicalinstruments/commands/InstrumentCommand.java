package net.tfminecraft.musicalinstruments.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import net.tfminecraft.musicalinstruments.managers.InstrumentManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// ====================================
// Handles the /instruments command.
// Subcommands: keybinds, list, give, reload.
// ====================================
public class InstrumentCommand implements CommandExecutor, TabCompleter {

    private static final String USAGE = "§cUsage: /instruments <keybinds|list|give|reload>";

    private final InstrumentPlugin plugin;
    private final InstrumentManager manager;

    public InstrumentCommand(InstrumentPlugin plugin, InstrumentManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Check if the correct subcommand was provided
        if (args.length == 0) {
            sender.sendMessage(USAGE);
            return true;
        }

        // Handle different subcommands
        switch (args[0].toLowerCase()) {
            case "keybinds":
                if (requirePermission(sender, "instruments.use") && requirePlayer(sender)) {
                    handleKeybinds((Player) sender);
                }
                break;
            case "list":
                if (requirePermission(sender, "instruments.use")) {
                    handleList(sender);
                }
                break;
            case "give":
                if (requirePermission(sender, "instruments.give") && requirePlayer(sender)) {
                    handleGive((Player) sender, args);
                }
                break;
            case "reload":
                if (requirePermission(sender, "instruments.reload")) {
                    handleReload(sender);
                }
                break;
            default:
                sender.sendMessage(USAGE);
                break;
        }

        return true;
    }

    private boolean requirePermission(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) {
            sender.sendMessage("§cYou don't have permission to do that!");
            return false;
        }

        return true;
    }

    private boolean requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can use this command!");
            return false;
        }

        return true;
    }

    // ====================================
    // Handles the /instruments keybinds command
    // ====================================
    private void handleKeybinds(Player player) {
        // Check what instrument is in the players off-hand
        String instrument = manager.getInstrument(player.getInventory().getItemInOffHand());

        // If no instrument is found, inform the player (if command is used without an instrument in hand)
        if (instrument == null) {
            player.sendMessage("§cYou must be holding an instrument in your off-hand!");
            return;
        }

        // Get the keybind message from config for this specific instrument
        String keybindMessage = manager.getKeybindMessage(instrument);
        if (keybindMessage != null)
        {
            // Split message into new lines and send each one
            String[] lines = keybindMessage.trim().split("\n");

            for (String line : lines)
            {
                player.sendMessage(line);
            }

        } else {
            // Fallback
            player.sendMessage("§aYour instrument keybinds were not defined in the config.");
        }
    }

    // ====================================
    // Handles the /instruments list command
    // ====================================
    private void handleList(CommandSender sender) {
        var instruments = manager.getAllInstruments();

        if (instruments.isEmpty()) {
            sender.sendMessage("§cNo instruments are loaded.");
            return;
        }

        sender.sendMessage("§aLoaded instruments (§6" + instruments.size() + "§a):");
        sender.sendMessage("§e" + String.join("§7, §e", instruments));
    }

    // ====================================
    // Handles the /instruments give <instrument> command
    // ====================================
    private void handleGive(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /instruments give <instrument>");
            return;
        }

        // Config keys keep their case, and tab completion suggests them as written.
        String instrument = manager.findInstrument(args[1]);

        if (instrument == null) {
            player.sendMessage("§cUnknown instrument: §e" + args[1]);
            return;
        }

        // Drop whatever does not fit, like vanilla /give.
        for (ItemStack leftover : player.getInventory().addItem(manager.getInstrumentItem(instrument)).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        player.sendMessage("§aYou received: §e" + instrument);
    }

    // ====================================
    // Handles the /instruments reload command
    // ====================================
    private void handleReload(CommandSender sender) {
        plugin.reloadConfig();
        manager.loadTemplates();
        sender.sendMessage("§aConfig reloaded. §e" + manager.getAllInstruments().size() + " §ainstrument(s) loaded.");
    }

    // ====================================
    // Provides tab completion for the command
    // When typing /instruments
    // ====================================
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {

        if (args.length == 1) {
            List<String> subcommands = new ArrayList<>();
            if (sender.hasPermission("instruments.use")) {
                subcommands.add("keybinds");
                subcommands.add("list");
            }
            if (sender.hasPermission("instruments.give")) {
                subcommands.add("give");
            }
            if (sender.hasPermission("instruments.reload")) {
                subcommands.add("reload");
            }

            return filterPrefix(subcommands, args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("give") && sender.hasPermission("instruments.give")) {
            return filterPrefix(new ArrayList<>(manager.getAllInstruments()), args[1]);
        }

        return Collections.emptyList();
    }

    private List<String> filterPrefix(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        options.removeIf(option -> !option.toLowerCase().startsWith(lower));

        return options;
    }
}
