package net.tfminecraft.musicalinstruments.keyboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.ShadowColor;
import net.tfminecraft.musicalinstruments.keyboard.KeyboardFont.Size;
import net.tfminecraft.musicalinstruments.keyboard.KeyboardView.Cell;
import org.junit.jupiter.api.Test;

class KeyboardViewTest {

    private static Cell[] idle() {
        Cell[] cells = new Cell[KeyboardFont.CELLS];
        Arrays.fill(cells, Cell.IDLE);
        return cells;
    }

    /** Splits a built component into text lines of (text, click) segments. */
    private static List<List<TextComponent>> lines(Component root) {
        List<List<TextComponent>> lines = new ArrayList<>();
        lines.add(new ArrayList<>());
        for (Component child : root.children()) {
            TextComponent text = (TextComponent) child;
            if (text.content().equals("\n")) {
                lines.add(new ArrayList<>());
            } else {
                lines.getLast().add(text);
            }
        }
        return lines;
    }

    // Pen advance of a run that mixes space characters and bitmap glyphs of the given width.
    private static int advance(String text, Size size) {
        int total = 0;
        for (char c : text.toCharArray()) {
            if (c >= 0xE400) {
                total += KeyboardFontTest.advanceOf(String.valueOf(c));
            } else if ((c & 0xFF) >= 0x30) {
                total += KeyboardFont.advance(12); // three-dot chord mark
            } else if ((c & 0xFF) >= 0x20) {
                total += KeyboardFont.advance(size.ring((c & 0xFF) - 0x20));
            } else {
                total += KeyboardFont.advance(size.diameter());
            }
        }
        return total;
    }

    @Test
    void clickIdentifiersRoundTrip() {
        for (int cell = 0; cell < KeyboardFont.CELLS; cell++) {
            assertEquals(cell, KeyboardView.cellOf(KeyboardView.noteKey(cell)));
        }
        assertEquals(-1, KeyboardView.cellOf(Key.key("other", "n3")));
        assertEquals(-1, KeyboardView.cellOf(KeyboardView.OPTIONS));
        assertEquals(-1, KeyboardView.cellOf(Key.key(KeyboardView.NAMESPACE, "nx")));
        assertEquals(-1, KeyboardView.cellOf(Key.key(KeyboardView.NAMESPACE, "n21")));
        assertEquals(-1, KeyboardView.cellOf(Key.key(KeyboardView.NAMESPACE, "n-1")));
        assertEquals(7, KeyboardView.chordOf(KeyboardView.chordKey(7)));
        assertEquals(-1, KeyboardView.chordOf(KeyboardView.noteKey(7)));
        assertEquals(-1, KeyboardView.cellOf(KeyboardView.chordKey(7)));
    }

    @Test
    void gridHasOneClickableCellPerNoteOnEveryLine() {
        Cell[] cells = idle();
        cells[3] = new Cell(true, -1);
        cells[10] = new Cell(true, 0);
        cells[20] = new Cell(false, 2);
        cells[13] = new Cell(false, 7); // unknown ring frames draw no ring
        for (Size size : Size.values()) {
            Component grid = KeyboardView.grid(size, cells);
            assertEquals(ShadowColor.none(), grid.style().shadowColor());
            assertEquals(KeyboardFont.FONT, grid.style().font());
            List<List<TextComponent>> lines = lines(grid);
            assertEquals(KeyboardFont.ROWS * size.lines(), lines.size());
            for (int line = 0; line < lines.size(); line++) {
                List<TextComponent> segments = lines.get(line);
                assertEquals(KeyboardFont.COLUMNS, segments.size());
                int row = line / size.lines();
                // The bottom line of each row, under the circles, holds the chord marks.
                boolean chordLine = line % size.lines() == size.lines() - 1;
                for (int column = 0; column < KeyboardFont.COLUMNS; column++) {
                    TextComponent segment = segments.get(column);
                    // Every cell segment fills exactly one column, so lines stay aligned.
                    assertEquals(size.pitchX(), advance(segment.content(), size), size + " line " + line + " column " + column);
                    ClickEvent click = segment.clickEvent();
                    assertNotNull(click);
                    assertEquals(ClickEvent.Action.CUSTOM, click.action());
                    ClickEvent.Payload.Custom payload = (ClickEvent.Payload.Custom) click.payload();
                    int index = row * KeyboardFont.COLUMNS + column;
                    assertEquals(chordLine ? KeyboardView.chordKey(index) : KeyboardView.noteKey(index), payload.key());
                    assertEquals(chordLine, segment.hoverEvent() != null);
                }
            }
        }
        String first = lines(KeyboardView.grid(Size.MEDIUM, cells)).getFirst().get(3).content();
        assertTrue(first.indexOf(Size.MEDIUM.note(3, true)) >= 0);
        String ringed = lines(KeyboardView.grid(Size.MEDIUM, cells)).get(Size.MEDIUM.lines()).get(3).content();
        assertTrue(ringed.indexOf(Size.MEDIUM.ringChar(0)) >= 0);
        String unknown = lines(KeyboardView.grid(Size.MEDIUM, cells)).get(Size.MEDIUM.lines()).get(6).content();
        assertEquals(-1, unknown.indexOf(Size.MEDIUM.ringChar(0)));
    }

    @Test
    void chordMarksSitUnderTheCirclesAndNameTheChord() {
        Cell[] cells = idle();
        cells[4] = new Cell(false, -1, true);
        int markLine = Size.SMALL.lines() - 1;
        List<List<TextComponent>> lines = lines(KeyboardView.grid(Size.SMALL, cells));
        assertEquals(Component.text("G chord"), lines.get(markLine).get(4).hoverEvent().value());
        assertTrue(lines.get(markLine).get(4).content().indexOf(Size.SMALL.markChar(true)) >= 0);
        assertTrue(lines.get(markLine).get(5).content().indexOf(Size.SMALL.markChar(false)) >= 0);
    }

    @Test
    void optionsButtonIsAButtonGlyphWithACentredLabel() {
        List<List<TextComponent>> lines = lines(KeyboardView.optionsButton());
        assertEquals(3, lines.size());
        assertEquals(String.valueOf(KeyboardFont.BUTTON), lines.get(0).getFirst().content());
        assertEquals("Instrument Options...", lines.get(1).get(1).content());
        assertEquals(Key.key("minecraft", "default"), lines.get(1).get(1).style().font());
        assertEquals(KeyboardFontTest.advanceOf(lines.get(1).get(0).content()),
                KeyboardFontTest.advanceOf(lines.get(1).get(2).content()));
        assertEquals(KeyboardFont.advance(KeyboardFont.BUTTON_WIDTH), KeyboardFontTest.advanceOf(lines.get(2).getFirst().content()));
        for (List<TextComponent> line : lines) {
            for (TextComponent segment : line) {
                assertEquals(KeyboardView.OPTIONS, ((ClickEvent.Payload.Custom) segment.clickEvent().payload()).key());
            }
        }
    }

    @Test
    void buildsADialog() {
        try (DialogStubs stubs = new DialogStubs()) {
            assertNotNull(KeyboardView.dialog(Size.LARGE, idle()));
            assertEquals(1, stubs.created.size());
        }
    }
}
