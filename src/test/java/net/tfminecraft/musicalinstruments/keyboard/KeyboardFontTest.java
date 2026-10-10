package net.tfminecraft.musicalinstruments.keyboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.tfminecraft.musicalinstruments.keyboard.KeyboardFont.Size;
import org.junit.jupiter.api.Test;

class KeyboardFontTest {

    // Sums the advance of the space characters the way the resource-pack font defines them.
    static int advanceOf(String spaces) {
        int total = 0;
        for (char c : spaces.toCharArray()) {
            if (c >= 0xE400 && c < 0xE40A) {
                total += 1 << (c - 0xE400);
            } else if (c >= 0xE410 && c < 0xE41A) {
                total -= 1 << (c - 0xE410);
            } else {
                throw new AssertionError("not a space character: " + Integer.toHexString(c));
            }
        }
        return total;
    }

    @Test
    void spacesAddUpToTheRequestedAdvance() {
        assertEquals("", KeyboardFont.space(0));
        for (int amount : new int[]{1, 7, 54, 151, 512, 1023, 1500, -1, -53, -1500}) {
            assertEquals(amount, advanceOf(KeyboardFont.space(amount)), "amount " + amount);
        }
        // Larger steps first, one character per set bit.
        assertEquals("", KeyboardFont.space(513));
    }

    @Test
    void bitmapGlyphsAdvanceByTheirWidthPlusOne() {
        assertEquals(41, KeyboardFont.advance(40));
    }

    @Test
    void sizesDescribeTheGridAndTheirGlyphs() {
        assertEquals("Small", Size.SMALL.label());
        assertEquals(40, Size.MEDIUM.diameter());
        assertEquals(72, Size.LARGE.pitchX());
        assertEquals(7, Size.LARGE.lines());
        assertEquals('\uE030', Size.SMALL.markChar(false));
        assertEquals('\uE231', Size.LARGE.markChar(true));
        assertEquals(3, Size.MEDIUM.ringFrames());
        assertEquals(52, Size.MEDIUM.ring(2));
        assertEquals(7 * 44, Size.SMALL.gridWidth());
        assertEquals('', Size.SMALL.note(0, false));
        assertEquals('', Size.MEDIUM.note(6, true));
        assertEquals('', Size.LARGE.ringChar(2));
    }

    @Test
    void sizesAreFoundByNameWithAFallback() {
        assertEquals(Size.LARGE, Size.byName("large", Size.SMALL));
        assertEquals(Size.MEDIUM, Size.byName("MEDIUM", Size.SMALL));
        assertEquals(Size.SMALL, Size.byName("huge", Size.SMALL));
        assertEquals(Size.MEDIUM, Size.byName(null, Size.MEDIUM));
    }
}
