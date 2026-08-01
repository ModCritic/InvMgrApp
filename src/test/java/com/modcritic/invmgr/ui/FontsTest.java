package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import javafx.scene.text.Font;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks on the two typefaces the app carries inside itself.
 *
 * <p>These are deliberately not rendering tests. They read the shipped {@code .ttf} files and ask
 * them directly which characters they can draw, which is the question that actually matters and
 * the one that was got wrong while planning this change: the published metadata for a font is
 * <em>not</em> the same thing as the font's own character table, and trusting it picked a font
 * that turned out to be missing both glyphs it was chosen for.
 */
class FontsTest {

    private static final String DIRECTORY = "/com/modcritic/invmgr/ui/fonts/";

    /**
     * Every non-ASCII character the interface puts on screen, and where.
     *
     * <p>If a character is added to a label or a button, it belongs in this list. A character the
     * bundled font cannot draw does not fail loudly — it renders as an empty box, or gets
     * silently borrowed from some other font on that computer, which is exactly the
     * platform-dependence the bundled fonts exist to remove.
     */
    private static final char[] TEXT_FACE_CHARACTERS = {
        '■',    // TopBar, the Add button
        '×',    // ItemListPanel, the clear-search button
        '·',    // LayerSliderDrawer, a half-foot tick
        '—',    // TopBar and PresetSlots, in status and placeholder text
        '°',    // used in text
        'é',    // not ours, but names are user text and Latin-1 must survive
    };

    /** The two characters the text face cannot draw, and the only reason a second font exists. */
    private static final char[] SYMBOL_FACE_CHARACTERS = {
        '⤓',    // ItemListPanel, the export button
        '↻',    // EditItemDialog, the swap button
    };

    private static java.awt.Font read(String file) throws Exception {
        try (InputStream stream = Fonts.class.getResourceAsStream(DIRECTORY + file)) {
            assertTrue(stream != null, DIRECTORY + file
                    + " is not on the classpath — the build is not packaging it");
            return java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, stream);
        }
    }

    @Test
    @DisplayName("the text face can draw every character the interface puts on screen")
    void textFaceCoversTheInterface() throws Exception {
        java.awt.Font face = read("NotoSansMono-Regular.ttf");
        for (char character : TEXT_FACE_CHARACTERS) {
            assertTrue(face.canDisplay(character),
                    String.format("the interface draws '%c' (U+%04X) but the bundled text face"
                            + " has no glyph for it — it would render as an empty box",
                            character, (int) character));
        }
        for (char character = 0x20; character < 0x7F; character++) {
            assertTrue(face.canDisplay(character),
                    "the text face is missing the printable ASCII character " + character);
        }
    }

    @Test
    @DisplayName("the symbol face can draw the two button glyphs it exists for")
    void symbolFaceCoversItsTwoButtons() throws Exception {
        java.awt.Font face = read("NotoSansMath-Regular.ttf");
        for (char character : SYMBOL_FACE_CHARACTERS) {
            assertTrue(face.canDisplay(character),
                    String.format("the symbol face is bundled solely to draw '%c' (U+%04X) and"
                            + " cannot", character, (int) character));
        }
    }

    @Test
    @DisplayName("the second font is still necessary — the text face still lacks ⤓ and ↻")
    void theSecondFontIsStillEarningItsPlace() throws Exception {
        java.awt.Font face = read("NotoSansMono-Regular.ttf");
        for (char character : SYMBOL_FACE_CHARACTERS) {
            assertFalse(face.canDisplay(character),
                    String.format("the text face can now draw '%c' (U+%04X). That is good news:"
                            + " if it covers both symbols, the second font can be deleted and"
                            + " Tokens.FONT_FAMILY_SYMBOL folded back into FONT_FAMILY.",
                            character, (int) character));
        }
    }

    @Test
    @DisplayName("both families are registered with JavaFX under the names the tokens use")
    void bothFamiliesResolveToThemselves() {
        assertTrue(Font.getFamilies().contains(Fonts.TEXT_FAMILY),
                "JavaFX does not know a family called " + Fonts.TEXT_FAMILY);
        assertTrue(Font.getFamilies().contains(Fonts.SYMBOL_FAMILY),
                "JavaFX does not know a family called " + Fonts.SYMBOL_FAMILY);

        // The failure this guards is silent: asking for a family that is not loaded gives back
        // the default proportional System font rather than an error.
        assertEquals(Fonts.TEXT_FAMILY, Font.font(Fonts.TEXT_FAMILY, 13).getFamily());
        assertEquals(Fonts.SYMBOL_FAMILY, Font.font(Fonts.SYMBOL_FAMILY, 13).getFamily());
    }

    @Test
    @DisplayName("reading a Tokens font name is enough to load the fonts")
    void touchingTokensArmsTheLoader() {
        assertEquals(Fonts.TEXT_FAMILY, Tokens.FONT_FAMILY);
        assertEquals(Fonts.SYMBOL_FAMILY, Tokens.FONT_FAMILY_SYMBOL);
        assertEquals(Tokens.FONT_FAMILY, Font.font(Tokens.FONT_FAMILY, 13).getFamily(),
                "the interface's typeface is not loaded, so text would render in a"
                        + " proportional system font");
    }

    @Test
    @DisplayName("the font names are still fetched at runtime, not baked in as text")
    @SuppressWarnings("StringEquality")
    void theFontNamesAreNotCompileTimeConstants() {
        // Deliberately == and not equals(). This is the one place in the project where the
        // distinction is the entire point.
        //
        // Tokens.FONT_FAMILY has to be a value fetched while the app runs, because fetching it
        // is what loads the font files — see Fonts. Written as a plain literal instead, Java
        // would copy the text into every place that uses it, nothing would ever ask Fonts for
        // anything, and no font would load. The interface would fall back to a proportional
        // system typeface, in silence, on every platform.
        //
        // That mistake is invisible to every other test here: the moment a test mentions Fonts
        // it loads the fonts itself and the evidence is destroyed. So this checks the mechanism
        // rather than the result. Java pools identical literals, so a baked-in name would be the
        // very same object as the one written below; a name read from a font file cannot be.
        assertTrue(Tokens.FONT_FAMILY != "Noto Sans Mono",
                "Tokens.FONT_FAMILY has been turned into a plain literal. It compiles, it looks"
                        + " tidier, and it stops the fonts ever loading — read the note on"
                        + " Tokens.FONT_FAMILY before changing this back.");
        assertTrue(Tokens.FONT_FAMILY_SYMBOL != "Noto Sans Math",
                "Tokens.FONT_FAMILY_SYMBOL has been turned into a plain literal — see above.");

        // And the names really are the ones expected, which == deliberately does not tell us.
        assertEquals("Noto Sans Mono", Tokens.FONT_FAMILY);
        assertEquals("Noto Sans Math", Tokens.FONT_FAMILY_SYMBOL);
    }

    @Test
    @DisplayName("the faked italic leans the top of a letter to the right")
    void obliqueLeansTheRightWay() {
        double pivot = 12;
        javafx.scene.transform.Shear slant = Fonts.oblique(pivot);

        // Above the pivot, x must increase; below it, decrease. That is a forward lean.
        assertTrue(slant.transform(0, 0).getX() > 0,
                "the top of the text should lean right, but it leans left");
        assertTrue(slant.transform(0, pivot * 2).getX() < 0,
                "the bottom of the text should trail left of the pivot");
        assertEquals(0, slant.transform(0, pivot).getX(), 1e-9,
                "text on the pivot line should not move sideways at all");
    }
}
