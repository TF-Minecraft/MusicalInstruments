package net.tfminecraft.musicalinstruments.studio;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native title input and confirmations without routing private input through roleplay chat. */
public final class StudioDialogs {
    @FunctionalInterface
    interface Reopen { void open(Player player, Block station) throws IOException; }
    @FunctionalInterface
    private interface Response { void apply(Player player, DialogResponseView view) throws IOException; }

    private final InstrumentPlugin plugin;
    private final StudioService studio;
    private final Reopen reopen;
    private final Reopen library;

    StudioDialogs(InstrumentPlugin plugin, StudioService studio, Reopen reopen) {
        this(plugin, studio, reopen, reopen);
    }

    StudioDialogs(InstrumentPlugin plugin, StudioService studio, Reopen reopen, Reopen library) {
        this.plugin = plugin;
        this.studio = studio;
        this.reopen = reopen;
        this.library = library;
    }

    public void create(Player player, Location station) throws IOException {
        studio.requireIdle(player);
        studio.requireProjectSpace(player);
        AtomicBoolean used = new AtomicBoolean();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Create a song", NamedTextColor.GOLD))
                        .body(List.of(DialogBody.plainMessage(Component.text("Your other projects will be kept."))))
                        .inputs(List.of(DialogInput.text("title", Component.text("Song title"))
                                .initial("Untitled").maxLength(64).width(300).build()))
                        .canCloseWithEscape(true).build())
                .type(DialogType.confirmation(
                        ActionButton.create(Component.text("Create", NamedTextColor.GREEN), null, 150,
                                action(player, station, used, (target, view) -> {
                                    String title = view.getText("title");
                                    if (title == null) throw new IllegalArgumentException("Enter a song title");
                                    studio.create(target, title.strip(), false);
                                })),
                        ActionButton.create(Component.text("Back", NamedTextColor.GRAY), null, 150,
                                action(player, station, used, (target, view) -> {}, library)))));
        player.closeInventory();
        player.showDialog(dialog);
    }

    public void rename(Player player, Location station) throws IOException {
        studio.requireIdle(player);
        Project project = studio.requireProject(player);
        AtomicBoolean used = new AtomicBoolean();
        UUID id = project.song().id();
        var accept = action(player, station, used, (target, view) -> {
            String title = view.getText("title");
            if (title == null) throw new IllegalArgumentException("Please enter a title");
            Project current = studio.requireProject(target);
            applySettings(target, id, title, current.bpm(), current.metronome());
        });
        var cancel = action(player, station, used, (target, view) -> {});
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Rename project", NamedTextColor.GOLD))
                        .inputs(List.of(DialogInput.text("title", Component.text("Song title"))
                                .initial(project.song().title()).maxLength(64).width(300).build()))
                        .canCloseWithEscape(true).build())
                .type(DialogType.confirmation(
                        ActionButton.create(Component.text("Save", NamedTextColor.GREEN), null, 150, accept),
                        ActionButton.create(Component.text("Back", NamedTextColor.GRAY), null, 150, cancel))));
        player.closeInventory();
        player.showDialog(dialog);
    }

    public void reset(Player player, Location station) throws IOException {
        studio.requireIdle(player);
        Project project = studio.requireProject(player);
        AtomicBoolean used = new AtomicBoolean();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Clear this project?", NamedTextColor.RED))
                        .body(List.of(DialogBody.plainMessage(Component.text(
                                "This clears the editable tracks and pending take of '" + project.song().title()
                                        + "'. Published discs keep their music.")))).canCloseWithEscape(true).build())
                .type(DialogType.confirmation(
                        ActionButton.create(Component.text("Clear project", NamedTextColor.RED), null, 150,
                                action(player, station, used, (target, view) -> {
                                    if (!studio.requireProject(target).equals(project)) {
                                        throw new IllegalArgumentException("The project changed. Open the confirmation again");
                                    }
                                    studio.create(target, "Untitled", true);
                                })),
                        ActionButton.create(Component.text("Keep my song", NamedTextColor.GREEN), null, 150,
                                action(player, station, used, (target, view) -> {})))));
        player.closeInventory();
        player.showDialog(dialog);
    }

    public void delete(Player player, Location station, UUID id) throws IOException {
        studio.requireIdle(player);
        Project project = studio.project(player, id);
        AtomicBoolean used = new AtomicBoolean();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Delete this song?", NamedTextColor.RED))
                        .body(List.of(DialogBody.plainMessage(Component.text("Delete '" + project.song().title()
                                + "' and its editable tracks and pending take? Other projects and published discs are kept."))))
                        .canCloseWithEscape(true).build())
                .type(DialogType.confirmation(
                        ActionButton.create(Component.text("Delete song", NamedTextColor.RED), null, 150,
                                action(player, station, used, (target, view) -> studio.deleteProject(target, project), library)),
                        ActionButton.create(Component.text("Keep song", NamedTextColor.GREEN), null, 150,
                                action(player, station, used, (target, view) -> {}, library)))));
        player.closeInventory();
        player.showDialog(dialog);
    }

    void applySettings(Player player, UUID projectId, String title, int bpm, boolean metronome) throws IOException {
        Project project = studio.requireProject(player);
        Song song = project.song();
        if (!song.id().equals(projectId)) {
            throw new IllegalArgumentException("The project changed. Open its settings again");
        }
        studio.edit(player, new Project(new Song(song.id(), song.owner(), song.author(), title.strip(), song.tracks()),
                project.pending(), bpm, metronome));
    }

    private DialogAction action(Player owner, Location station, AtomicBoolean used, Response response) {
        return action(owner, station, used, response, reopen);
    }

    private DialogAction action(Player owner, Location station, AtomicBoolean used, Response response, Reopen destination) {
        UUID ownerId = owner.getUniqueId();
        return DialogAction.customClick((view, audience) -> {
            if (!(audience instanceof Player target) || !target.getUniqueId().equals(ownerId)
                    || !used.compareAndSet(false, true) || !plugin.isEnabled()) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!target.isOnline()) return;
                try {
                    Block block = studio.station().require(target, station);
                    response.apply(target, view);
                    destination.open(target, block);
                } catch (IllegalArgumentException ex) {
                    target.sendMessage(ex.getMessage());
                } catch (IOException ex) {
                    studio.failure(target, ex);
                }
            });
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build());
    }
}
