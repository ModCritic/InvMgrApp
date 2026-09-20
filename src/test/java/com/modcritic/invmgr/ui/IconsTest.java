package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The part of icon loading that is arithmetic on a string, checked with no window at all.
 *
 * <p>Small, and here for a specific reason: every icon the app actually ships is 128 px square,
 * so a version of {@link Icons#artboardOf} that ignored the file entirely and returned 128 would
 * produce identical results everywhere and no other test could tell. That is a rule nothing is
 * checking, which is the thing the mutation sweep exists to find; so the rule is given a size
 * the real files do not use, and asked about directly.
 */
class IconsTest {

    @Test
    @DisplayName("the artboard size comes from the file, not from what our own icons happen to be")
    void theArtboardIsRead() {
        assertEquals(64, Icons.artboardOf("0 0 64 64", "test.svg"), 0.001);
        assertEquals(128, Icons.artboardOf("0 0 128 128", "test.svg"), 0.001);

        // Commas are as legal as spaces in a viewBox, and a leading corner other than 0,0 is too.
        assertEquals(24, Icons.artboardOf("0,0,24,24", "test.svg"), 0.001);
        assertEquals(32, Icons.artboardOf(" 8 8 32 32 ", "test.svg"), 0.001);
    }

    @Test
    @DisplayName("a drawing that is not square still gets a square to sit in")
    void theLongerSideWins() {
        // Taking the smaller side would let the longer one hang outside the box the button lays
        // out for it: the same overflow that put the cube off its button in the first place.
        assertEquals(40, Icons.artboardOf("0 0 40 20", "wide.svg"), 0.001);
        assertEquals(40, Icons.artboardOf("0 0 20 40", "tall.svg"), 0.001);
    }

    @Test
    @DisplayName("an icon with no viewBox is refused loudly, naming the file")
    void aFileWithNoViewBoxIsRefused() {
        // Loudly, because the alternative is an icon that silently comes out the wrong size, and
        // that is exactly the class of failure this whole file exists to close.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> Icons.artboardOf("", "btn-new.svg"));
        assertTrue(thrown.getMessage().contains("btn-new.svg"),
                "the message must name the file, or it is no help at all: "
                        + thrown.getMessage());

        assertThrows(IllegalStateException.class, () -> Icons.artboardOf("0 0 128", "short.svg"));
    }
}
