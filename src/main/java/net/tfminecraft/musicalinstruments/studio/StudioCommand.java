package net.tfminecraft.musicalinstruments.studio;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class StudioCommand implements CommandExecutor, TabCompleter {
    private static final List<String> EDIT = List.of("new", "title", "studio", "projects", "open", "status", "record", "stop", "keep",
            "discard", "preview", "volume", "mute", "remove", "bpm", "metronome", "blank", "publish", "reset", "help");
    private final StudioService studio;
    private final StudioMenu menu;

    public StudioCommand(StudioService studio, StudioMenu menu) {
        this.studio = studio;
        this.menu = menu;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use the recording studio.");
            return true;
        }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!player.hasPermission(action.equals("copy") ? "instruments.copy" : "instruments.record")) {
            player.sendMessage("You don't have permission to do that.");
            return true;
        }
        try {
            if (action.equals("studio") && !player.hasPermission("instruments.studio")) {
                throw new IllegalArgumentException("Right-click a recording station to open the studio. Opening it by command is reserved for staff");
            }
            switch (action) {
                case "new" -> {
                    need(args, 2, "new <title>");
                    studio.create(player, title(args), false);
                    player.sendMessage("Project created. Hold an instrument and use /music record 1.");
                }
                case "title" -> {
                    need(args, 2, "title <title>");
                    Project project = studio.requireProject(player);
                    Song song = project.song();
                    studio.edit(player, new Project(new Song(song.id(), song.owner(), song.author(), title(args), song.tracks()),
                            project.pending(), project.bpm(), project.metronome()));
                    player.sendMessage("Project renamed.");
                }
                case "studio" -> menu.open(player, null);
                case "projects" -> {
                    var projects = studio.listProjects(player);
                    if (projects.isEmpty()) player.sendMessage("No projects yet. Open /music studio to create a song.");
                    for (ProjectSummary project : projects) player.sendMessage(project.title() + " | " + project.id());
                }
                case "open" -> {
                    need(args, 2, "open <project-id>");
                    UUID id;
                    try { id = UUID.fromString(args[1]); }
                    catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Use a project ID from /music projects"); }
                    studio.selectProject(player, id);
                    player.sendMessage("Opened '" + studio.requireProject(player).song().title() + "'.");
                }
                case "record" -> {
                    need(args, 2, "record <track>");
                    studio.record(player, integer(args[1]));
                }
                case "stop" -> studio.stop(player);
                case "keep" -> {
                    studio.edit(player, studio.requireProject(player).accept());
                    player.sendMessage("Take kept. The previous track was replaced.");
                }
                case "discard" -> {
                    studio.edit(player, studio.requireProject(player).discard());
                    player.sendMessage("Take discarded. The previous track was preserved.");
                }
                case "preview" -> {
                    if (args.length > 1 && !args[1].equalsIgnoreCase("take")) {
                        throw new IllegalArgumentException("Usage: /music preview [take]");
                    }
                    studio.preview(player, args.length > 1);
                }
                case "volume", "mute", "remove" -> {
                    need(args, action.equals("volume") ? 3 : 2,
                            action + " <track>" + (action.equals("volume") ? " <0-2>" : ""));
                    Project project = studio.requireProject(player);
                    int slot = integer(args[1]);
                    Track track = project.song().track(slot);
                    if (track == null) {
                        throw new IllegalArgumentException("That track has no saved take");
                    }
                    Song song = switch (action) {
                        case "volume" -> project.song().replace(track.mix(gain(args[2]), track.muted()));
                        case "mute" -> project.song().replace(track.mix(track.gain(), !track.muted()));
                        default -> project.song().remove(slot);
                    };
                    studio.edit(player, new Project(song, project.pending(), project.bpm(), project.metronome()));
                    player.sendMessage("Track " + slot + " updated.");
                }
                case "bpm" -> {
                    need(args, 2, "bpm <40-240>");
                    Project project = studio.requireProject(player);
                    studio.edit(player, new Project(project.song(), project.pending(), integer(args[1]), project.metronome()));
                    player.sendMessage("Metronome tempo updated. Recorded note timing is preserved.");
                }
                case "metronome" -> {
                    need(args, 2, "metronome <on|off>");
                    if (!args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off")) {
                        throw new IllegalArgumentException("Usage: /music metronome <on|off>");
                    }
                    Project project = studio.requireProject(player);
                    studio.edit(player, new Project(project.song(), project.pending(), project.bpm(), args[1].equalsIgnoreCase("on")));
                    player.sendMessage("Metronome " + args[1].toLowerCase(Locale.ROOT) + ".");
                }
                case "blank" -> studio.makeBlank(player);
                case "publish" -> studio.publish(player);
                case "copy" -> studio.copy(player);
                case "reset" -> {
                    need(args, 2, "reset confirm");
                    if (!args[1].equalsIgnoreCase("confirm")) {
                        throw new IllegalArgumentException("Use /music reset confirm to clear your editable project");
                    }
                    studio.create(player, "Untitled", true);
                    player.sendMessage("Editable project cleared. Published editions are preserved.");
                }
                case "status" -> status(player);
                default -> help(player);
            }
        } catch (IllegalArgumentException ex) {
            player.sendMessage(ex.getMessage());
        } catch (IOException ex) {
            studio.failure(player, ex);
        }
        return true;
    }

    private void status(Player player) throws IOException {
        Project project = studio.requireProject(player);
        player.sendMessage(project.song().title() + " | " + project.song().lengthTicks() / 20.0 + "s | " + project.bpm() + " BPM");
        for (Track track : project.song().tracks()) {
            player.sendMessage("Track " + track.slot() + ": " + track.notes().size() + " notes, volume "
                    + track.gain() + (track.muted() ? " (muted)" : ""));
        }
        if (project.pending() != null) {
            player.sendMessage("Pending take for track " + project.pending().slot() + ": /music preview take, keep, or discard.");
        }
    }

    private void help(Player player) {
        player.sendMessage("/music new <title> | projects | open <project-id> | status | title <title>");
        if (player.hasPermission("instruments.studio")) player.sendMessage("/music studio opens the studio anywhere (staff)");
        player.sendMessage("/music record <track> | stop | preview [take] | keep | discard");
        player.sendMessage("/music volume <track> <0-2> | mute <track> | remove <track>");
        player.sendMessage("/music bpm <40-240> | metronome <on|off>");
        player.sendMessage("/music blank | publish | copy | reset confirm");
        player.sendMessage("Right-click an empty jukebox with a published disc to play it.");
    }

    private void need(String[] args, int count, String usage) {
        if (args.length < count) {
            throw new IllegalArgumentException("Usage: /music " + usage);
        }
    }

    private String title(String[] args) {
        return String.join(" ", Arrays.copyOfRange(args, 1, args.length)).strip();
    }

    private int integer(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Expected a whole number");
        }
    }

    private float gain(String value) {
        try {
            float gain = Float.parseFloat(value);
            if (Float.isFinite(gain) && gain >= 0 && gain <= 2) {
                return gain;
            }
        } catch (NumberFormatException ignored) {
            // The error below also handles NaN and infinity.
        }
        throw new IllegalArgumentException("Volume must be between 0 and 2");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = List.of();
        if (args.length == 1) {
            var actions = new java.util.ArrayList<String>();
            if (sender.hasPermission("instruments.record")) {
                actions.addAll(EDIT);
                if (!sender.hasPermission("instruments.studio")) actions.remove("studio");
            }
            if (sender.hasPermission("instruments.copy")) {
                actions.add("copy");
            }
            options = actions;
        } else if (args.length == 2 && sender.hasPermission("instruments.record")) {
            options = switch (args[0].toLowerCase(Locale.ROOT)) {
                case "record", "volume", "mute", "remove" -> java.util.stream.IntStream.rangeClosed(1, studio.settings().tracks())
                        .mapToObj(Integer::toString).toList();
                case "preview" -> List.of("take");
                case "metronome" -> List.of("on", "off");
                case "reset" -> List.of("confirm");
                default -> List.of();
            };
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(prefix)).toList();
    }
}
