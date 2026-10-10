package net.tfminecraft.musicalinstruments.keyboard;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import net.tfminecraft.musicalinstruments.keyboard.KeyboardFont.Size;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Per-player keyboard preferences and the "Instrument Options..." dialog. */
public final class KeyboardOptions {
    public static final Key DONE = Key.key(KeyboardView.NAMESPACE, "done");
    private final KeyboardSettings settings;
    private final NamespacedKey sizeKey;
    private final NamespacedKey ringsKey;

    public KeyboardOptions(InstrumentPlugin plugin, KeyboardSettings settings) {
        this.settings = settings;
        this.sizeKey = new NamespacedKey(plugin, "keyboard_size");
        this.ringsKey = new NamespacedKey(plugin, "keyboard_rings");
    }

    public record Prefs(Size size, boolean rings) {
    }

    /** Values submitted from the options dialog; null fields were not shown. */
    public record Choice(String size, Boolean rings) {
        static Choice read(DialogResponseView view) {
            if (view == null) {
                return null;
            }
            return new Choice(view.getText("size"), view.getBoolean("rings"));
        }
    }

    public Prefs prefs(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        Size size = Size.byName(data.get(this.sizeKey, PersistentDataType.STRING), this.settings.defaultSize());
        Byte rings = data.get(this.ringsKey, PersistentDataType.BYTE);
        return new Prefs(size, rings == null ? this.settings.defaultRings() : rings != 0);
    }

    public void save(Player player, Choice choice) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (choice.size() != null) {
            data.set(this.sizeKey, PersistentDataType.STRING, Size.byName(choice.size(), this.settings.defaultSize()).name());
        }
        if (choice.rings() != null) {
            data.set(this.ringsKey, PersistentDataType.BYTE, (byte) (choice.rings() ? 1 : 0));
        }
    }

    public Dialog dialog(Player player, String instrument) {
        Prefs prefs = this.prefs(player);
        List<SingleOptionDialogInput.OptionEntry> sizes = new ArrayList<>();
        for (Size size : Size.values()) {
            sizes.add(SingleOptionDialogInput.OptionEntry.create(size.name(), Component.text(size.label()), size == prefs.size()));
        }
        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.singleOption("size", Component.text("Keyboard size"), sizes).width(200).build());
        inputs.add(DialogInput.bool("rings", Component.text("Ring effect (off: flash only)")).initial(prefs.rings()).build());
        String name = instrument.isEmpty() ? "instrument" : instrument.replace('_', ' ');
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(Component.text("Playing: ", NamedTextColor.GRAY)
                        .append(Component.text(Character.toUpperCase(name.charAt(0)) + name.substring(1), NamedTextColor.GOLD)), 250),
                DialogBody.plainMessage(Component.text(
                        "Top row: high notes. Middle row: normal notes. Bottom row: low notes.", NamedTextColor.GRAY), 250),
                DialogBody.plainMessage(Component.text(
                        "Chords: click the small gold dots under a note to play its chord.", NamedTextColor.GRAY), 250));
        ActionButton done = ActionButton.builder(Component.text("Done"))
                .tooltip(Component.text("Back to the keyboard"))
                .width(150)
                .action(DialogAction.customClick(DONE, null))
                .build();
        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Instrument Options"))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .body(body)
                        .inputs(inputs)
                        .build())
                // A notice has one button; Escape runs it too.
                .type(DialogType.notice(done)));
    }
}
