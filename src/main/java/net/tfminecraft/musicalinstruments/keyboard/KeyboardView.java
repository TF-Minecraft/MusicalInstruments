package net.tfminecraft.musicalinstruments.keyboard;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.set.RegistrySet;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.tfminecraft.musicalinstruments.keyboard.KeyboardFont.Size;

/**
 * Builds the keyboard dialog: an "Instrument Options..." button above a 7 x 3 grid of
 * clickable note circles, all drawn with resource-pack glyphs inside plain-message bodies.
 */
public final class KeyboardView {
    public static final String NAMESPACE = "mik";
    public static final Key OPTIONS = Key.key(NAMESPACE, "o");
    public static final String NOTE_PREFIX = "n";
    public static final String CHORD_PREFIX = "c";
    private static final String[] NOTE_NAMES = {"C", "D", "E", "F", "G", "A", "B"};
    /** Width of the three-dot chord mark glyph. */
    private static final int MARK_WIDTH = 12;
    private static final Key DEFAULT_FONT = Key.key("minecraft", "default");
    private static final String OPTIONS_LABEL = "Instrument Options...";
    /** Clickable padding either side of the label (the label is about 100 px wide). */
    private static final int LABEL_PAD = 25;

    private KeyboardView() {
    }

    /** Visual state of one note circle and the chord mark under it. */
    public record Cell(boolean lit, int ring, boolean chord) {
        public static final Cell IDLE = new Cell(false, -1, false);

        public Cell(boolean lit, int ring) {
            this(lit, ring, false);
        }
    }

    public static Key noteKey(int cell) {
        return Key.key(NAMESPACE, NOTE_PREFIX + cell);
    }

    /** The strip under a circle plays the chord on that note. */
    public static Key chordKey(int cell) {
        return Key.key(NAMESPACE, CHORD_PREFIX + cell);
    }

    /** Returns the cell index for a note click identifier, or -1. */
    public static int cellOf(Key key) {
        return indexOf(key, NOTE_PREFIX);
    }

    /** Returns the cell index for a chord click identifier, or -1. */
    public static int chordOf(Key key) {
        return indexOf(key, CHORD_PREFIX);
    }

    private static int indexOf(Key key, String prefix) {
        if (!NAMESPACE.equals(key.namespace()) || !key.value().startsWith(prefix)) {
            return -1;
        }
        try {
            int cell = Integer.parseInt(key.value().substring(prefix.length()));
            return cell >= 0 && cell < KeyboardFont.CELLS ? cell : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    public static Dialog dialog(Size size, Cell[] cells) {
        // An empty dialog_list has no buttons and no footer; the body carries everything.
        // Widths get slack because the client wraps a line whose running width exceeds it.
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(optionsButton(), KeyboardFont.BUTTON_WIDTH + 16),
                DialogBody.plainMessage(grid(size, cells), size.gridWidth() + 16));
        return Dialog.create(builder -> builder.empty()
                .base(base(body))
                .type(DialogType.dialogList(RegistrySet.valueSet(RegistryKey.DIALOG, List.<Dialog>of())).build()));
    }

    private static DialogBase base(List<DialogBody> body) {
        return DialogBase.builder(Component.empty())
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .body(body)
                .build();
    }

    private static TextComponent.Builder root() {
        return Component.text()
                .font(KeyboardFont.FONT)
                .color(NamedTextColor.WHITE)
                .shadowColor(ShadowColor.none());
    }

    /**
     * A vanilla-looking button: line 0 holds the button glyph (drawn 3 px below the line top),
     * line 1 the label, line 2 keeps the lower part clickable. Lines are centred one by one,
     * so the label is centred whatever its real width.
     */
    static Component optionsButton() {
        int buttonAdvance = KeyboardFont.advance(KeyboardFont.BUTTON_WIDTH);
        ClickEvent click = ClickEvent.custom(OPTIONS, "0b");
        TextComponent.Builder out = root();
        out.append(Component.text(String.valueOf(KeyboardFont.BUTTON)).clickEvent(click));
        out.append(Component.newline());
        out.append(Component.text(KeyboardFont.space(LABEL_PAD)).clickEvent(click));
        out.append(Component.text(OPTIONS_LABEL)
                .font(DEFAULT_FONT)
                .shadowColor(ShadowColor.shadowColor(0xFF3F3F3F))
                .clickEvent(click));
        out.append(Component.text(KeyboardFont.space(LABEL_PAD)).clickEvent(click));
        out.append(Component.newline());
        out.append(Component.text(KeyboardFont.space(buttonAdvance)).clickEvent(click));
        return out.build();
    }

    static Component grid(Size size, Cell[] cells) {
        int d = size.diameter();
        int px = size.pitchX();
        int lead = (px - d) / 2;
        String blankCell = KeyboardFont.space(px);
        int lines = size.lines();
        TextComponent.Builder out = root();
        for (int row = 0; row < KeyboardFont.ROWS; row++) {
            for (int line = 0; line < lines; line++) {
                if (row > 0 || line > 0) {
                    out.append(Component.newline());
                }
                // The bottom line of each row, under the circles, shows the chord marks and plays the chord.
                boolean chordLine = line == lines - 1;
                for (int column = 0; column < KeyboardFont.COLUMNS; column++) {
                    int index = row * KeyboardFont.COLUMNS + column;
                    if (chordLine) {
                        out.append(chordCell(centred(size, size.markChar(cells[index].chord()), MARK_WIDTH), index, column));
                    } else {
                        String text = line == 0 ? noteCell(size, column, cells[index], lead) : blankCell;
                        out.append(Component.text(text).clickEvent(ClickEvent.custom(noteKey(index), "0b")));
                    }
                }
            }
        }
        return out.build();
    }

    private static Component chordCell(String text, int index, int column) {
        return Component.text(text)
                .clickEvent(ClickEvent.custom(chordKey(index), "0b"))
                .hoverEvent(HoverEvent.showText(Component.text(NOTE_NAMES[column] + " chord")));
    }

    /** One glyph of the given width centred in a keyboard column. */
    private static String centred(Size size, char glyph, int width) {
        int lead = (size.pitchX() - width) / 2;
        return KeyboardFont.space(lead) + glyph + KeyboardFont.space(size.pitchX() - lead - KeyboardFont.advance(width));
    }

    private static String noteCell(Size size, int column, Cell cell, int lead) {
        int d = size.diameter();
        int px = size.pitchX();
        StringBuilder text = new StringBuilder();
        text.append(KeyboardFont.space(lead));
        text.append(size.note(column, cell.lit()));
        if (cell.ring() >= 0 && cell.ring() < size.ringFrames()) {
            int r = size.ring(cell.ring());
            int overhang = (r - d) / 2;
            text.append(KeyboardFont.space(-KeyboardFont.advance(d) - overhang));
            text.append(size.ringChar(cell.ring()));
            text.append(KeyboardFont.space(px - lead + overhang - KeyboardFont.advance(r)));
        } else {
            text.append(KeyboardFont.space(px - lead - KeyboardFont.advance(d)));
        }
        return text.toString();
    }
}
