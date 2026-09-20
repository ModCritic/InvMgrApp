package com.modcritic.invmgr.ui;

import java.io.IOException;
import java.io.InputStream;
import javafx.scene.control.Labeled;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextBoundsType;
import javafx.scene.transform.Shear;

/**
 * The two typefaces the app carries inside itself, instead of borrowing the computer's.
 *
 * <p>Until 2026-07-31 the app asked for the font {@code "monospace"}. That is not a font; it is
 * a generic <em>alias</em>, and every system resolves it to a different typeface: Noto Sans Mono
 * on the author's Linux machine and on Android, Courier New on Windows, Menlo on macOS, DejaVu
 * Sans Mono in the build container. The interface therefore looked different on every platform.
 * Bundling the real files fixes the alias to one answer everywhere. See {@code CLAUDE.md} §5.5
 * <b>D-6</b> for why that is a deliberate divergence from the original app rather than a bug fix.
 *
 * <p><b>Why this class appears to be called by nothing.</b> Almost nothing references it
 * directly. It is reached through {@link Tokens#FONT_FAMILY}, which is initialized from
 * {@link #TEXT_FAMILY}. In Java, a {@code static final String} set to a plain literal is a
 * <em>compile-time constant</em> and the compiler copies the text straight into every place that
 * reads it, so reading it never runs any code in the class that declares it. Setting it from a
 * method call instead makes it an ordinary field, and reading it now forces {@code Tokens} to
 * initialize, which forces this class to initialize, which loads the fonts. The upshot is that
 * <b>anything able to ask for the font has already loaded it</b>, with no call to forget.
 *
 * <p>That matters because there is no shared base class for the interface tests: ten test classes
 * each build their own window, and two of them ({@code CanvasDragTest}, {@code
 * CanvasAppearanceTest}) never construct {@link com.modcritic.invmgr.App} at all. A rule of
 * "remember to call the loader first" would be one more thing for every future test to get right,
 * and getting it wrong is invisible: {@link Font#font(String, double)} answers an unknown family
 * with the default <em>proportional</em> System font rather than complaining, so the interface
 * would quietly render in the wrong typeface and every assertion would still pass.
 *
 * <p><b>Gotchas.</b>
 * <ul>
 *   <li>Do not turn {@link Tokens#FONT_FAMILY} back into a literal. It would compile, it would
 *       run, and it would silently disarm the paragraph above. {@code FontsTest} guards this.</li>
 *   <li>This class must never mention {@link Tokens}. Each would sit waiting for the other to
 *       finish initializing, and Java breaks that tie by handing one of them a half-built version
 *       of the other: a {@code null} family name, with nothing reported. The size passed to
 *       {@link Font#loadFont} is a plain number here for exactly that reason.</li>
 *   <li>Loading a font writes it to a temporary file first, so {@code java.io.tmpdir} has to be
 *       writable. Desktop is fine. On Android it may not be, which is why {@link #ensureLoaded()}
 *       exists and is called from a known point in {@code App.start}; M6 can set the property
 *       before that line and be certain it took effect.</li>
 * </ul>
 */
public final class Fonts {

    private static final String DIRECTORY = "/com/modcritic/invmgr/ui/fonts/";

    /** Every word of text in the interface. */
    private static final String TEXT_FILE = DIRECTORY + "NotoSansMono-Regular.ttf";

    /** Two button glyphs, and nothing else; see {@link #SYMBOL_FAMILY}. */
    private static final String SYMBOL_FILE = DIRECTORY + "NotoSansMath-Regular.ttf";

    /**
     * The size handed to {@link Font#loadFont}, which registers the family at every size.
     *
     * <p>A plain number rather than a token, because this class must not touch {@link Tokens};
     * see the class note. Only {@link Font#getFamily()} is read off the result, so the value is
     * irrelevant.
     */
    private static final double PROBE_SIZE = 12;

    /**
     * How far to lean a letter over when faking italics.
     *
     * <p>Noto Sans Mono has no italic face; the family ships upright only, at every weight. A
     * browser papers over that by slanting the upright letters itself, which is what the
     * reference screenshots show for planned rows. JavaFX does not: asking it for
     * {@link javafx.scene.text.FontPosture#ITALIC} on a family with no italic returns the
     * identical upright font, with no warning. So the slant has to be drawn by hand.
     *
     * <p><b>0.25 is {@code tan(14°)}</b>, and 14° is the angle CSS specifies for a faked oblique,
     * so this is the number that drew the reference screenshots, not one invented here.
     *
     * <p>Checked against {@code desktop-05-items-and-planned.png} by rendering the same string
     * and measuring the lean of the {@code ]} stroke in both. The reference measures 0.286. That
     * is not a contradiction: at 12 px the stroke's edge only moves in whole pixels, so the
     * measurement quantizes; a sweep here produced 0.190, then 0.262, then 0.321 with nothing in
     * between, and the reference's 0.286 falls inside that gap. The measurement is precise enough
     * to bracket the answer and not to pin it, so the specified angle wins over fitting a curve
     * to three points.
     */
    public static final double OBLIQUE_SLANT = 0.25;

    /** The family name of the text face, as JavaFX reports it: {@code "Noto Sans Mono"}. */
    public static final String TEXT_FAMILY;

    /**
     * The family name of the symbol face: {@code "Noto Sans Math"}.
     *
     * <p>The one place a second family is allowed, and only because Noto Sans Mono has no drawing
     * for two of the characters the interface uses as buttons: {@code ⤓} (export the list) and
     * {@code ↻} (swap width and length). Everything else, including the Add button's {@code ■},
     * the {@code ×} on the clear-search button and the {@code ·} on the layer ticks, is in the
     * text face. The original app had the same gap and the browser filled it silently from
     * whatever else was installed; naming a file makes that explicit and identical everywhere.
     */
    public static final String SYMBOL_FAMILY;

    static {
        TEXT_FAMILY = load(TEXT_FILE);
        SYMBOL_FAMILY = load(SYMBOL_FILE);
    }

    /** Not meant to be instantiated; this class is only a container for its constants. */
    private Fonts() {
    }

    /**
     * Loads the fonts if they are not loaded already, and does nothing otherwise.
     *
     * <p>The work happens in this class's initializer, so simply mentioning the class is what
     * triggers it; this method exists to give that a name and a deliberate moment. {@code App}
     * calls it as its first statement so a broken build fails before a window exists, and so M6
     * has somewhere to put the {@code java.io.tmpdir} fix described in the class note.
     */
    public static void ensureLoaded() {
        // Reaching this method has already run the static initializer above. Nothing to do.
    }

    /** The interface's typeface, at the given size. */
    public static Font mono(double size) {
        return Font.font(TEXT_FAMILY, size);
    }

    /** The typeface for {@code ⤓} and {@code ↻}, which the text face cannot draw. */
    public static Font symbol(double size) {
        return Font.font(SYMBOL_FAMILY, size);
    }

    /**
     * One symbol-face glyph as a node that sits on its own middle, for use as a button's graphic.
     *
     * <p><b>Why a button cannot simply be given the character as its text.</b> A button centers its
     * label using the <em>font's</em> line metrics: the ascent and descent of the whole typeface,
     * which are the same numbers whichever character is being drawn. That works when the face is
     * built for text. Noto Sans Math is not: it is a mathematics face, and its ascent has to leave
     * room for tall operators like {@code ∑} and full-height brackets. So its line box towers above
     * the baseline, centering that box puts the baseline low in the button, and a small arrow drawn
     * on that baseline rides down with it. Measured on the M3.3 build, {@code ⤓} ended up touching
     * the bottom border of the export button with six pixels of air above it, and {@code ↻} sat
     * twelve pixels below the top of its button and five above the bottom. The Add button's
     * {@code ■} (same centering code, but drawn in the <em>text</em> face) was dead center, which
     * is what shows the fault is the face's metrics rather than the layout.
     *
     * <p><b>What fixes it is making the glyph a graphic rather than text.</b> A button lays its
     * own text out through its skin, on a baseline derived from those font-wide metrics. A graphic
     * is just a node, and the button centers it by its layout bounds; no baseline involved. That
     * one change moves {@code ⤓} from two pixels low to dead center, which the pixel test in
     * {@code ChromeAppearanceTest} measures.
     *
     * <p><b>{@link TextBoundsType#VISUAL} is a guarantee on top, not the fix</b>, and it is worth
     * being exact about which is which. It makes a {@link Text} node report its <em>ink</em> (the
     * box actually covered by paint) as its layout bounds instead of the line box, so "centered"
     * means centered on the mark itself. Swapping it back to {@code LOGICAL} was tried: both
     * glyphs stay within a pixel of center, because this face's line box happens to sit roughly
     * symmetrically around these two particular characters. So it earns its place by removing a
     * dependence on that coincidence, not by moving anything visible today: a third symbol, or a
     * new version of the font, would have no reason to be so lucky. Do not drop it as redundant
     * without re-running that measurement.
     *
     * <p>Nothing here is a measured offset, so all of it stays correct if the size changes, if the
     * glyph changes, or if the face is ever replaced.
     *
     * <p><b>The fill binding is not decoration.</b> A {@link Text} node is a shape, and its fill
     * owes nothing to the {@code -fx-text-fill} that the buttons' style strings set; left alone it
     * would paint black on a dark button and the export button's hover color would stop changing.
     * Binding it to the owner's own {@code textFill} lets every existing style call keep working
     * untouched, including ones written later.
     *
     * <p>The original app centered both of these glyphs, so this restores its behavior rather than
     * departing from it; there is no {@code CLAUDE.md} §5.5 entry for it.
     *
     * @param owner the button this glyph will be the graphic of, whose text color it follows
     */
    public static Text symbolGlyph(String glyph, double size, Labeled owner) {
        Text node = new Text(glyph);
        node.setFont(symbol(size));
        node.setBoundsType(TextBoundsType.VISUAL);
        node.fillProperty().bind(owner.textFillProperty());
        return node;
    }

    /**
     * A button's words as a <b>drawn node</b> rather than as its text, so the button centers the
     * <em>ink</em> instead of the line box.
     *
     * <p><b>Why a control gets this wrong left alone.</b> JavaFX positions a {@link Labeled}'s text
     * from font metrics (ascent, descent and leading, all properties of the <em>face</em> rather
     * than of the letters), and the ink is whatever those particular letters happen to cover inside
     * that. Reported by the user 2026-08-13: <i>"the text within buttons is centered differently
     * than the original, heightwise"</i>. Measured against the original's own screenshot, the touch
     * buttons sat about <b>3.2 CSS px low</b>: Undo and Fit both, at two different type sizes.
     *
     * <p><b>An offset was tried first and did not work</b>, which is worth recording because it
     * looks obviously right: compute how far the ink sits from the line box's center and move the
     * padding by that much. The arithmetic says 0.3 px. The screen says 2.5. So JavaFX is not
     * centering the line box that reasoning assumed, and any correction built on that assumption is
     * a number that happens to be wrong rather than a rule. Handing over a node whose layout bounds
     * <em>are</em> the ink removes the question instead of answering it; the button has nothing
     * else to center.
     *
     * <p>This is {@link #symbolGlyph}'s mechanism, in the text face and at an ordinary size. The
     * fill is bound for the same reason it is bound there: a {@link Text} is a shape and owes
     * nothing to {@code -fx-text-fill}, so left alone it would paint black on a dark button and stop
     * following every hover and active-state change.
     *
     * <p>The caller keeps the button's own {@code setText} intact and shows this with
     * {@code ContentDisplay.GRAPHIC_ONLY}, so {@code getText()} still answers, which the tests, the
     * hints and the drawer tabs all rely on.
     *
     * @param string the words to draw
     * @param font   the font to draw them in
     * @param owner  the button this will be the graphic of, whose text color it follows
     */
    public static Text inkLabel(String string, Font font, Labeled owner) {
        Text node = new Text(string);
        node.setFont(font);
        node.setBoundsType(TextBoundsType.VISUAL);
        node.fillProperty().bind(owner.textFillProperty());
        return node;
    }

    /**
     * The font size at which a glyph's ink comes out a wanted number of pixels tall.
     *
     * <p>For the island's {@code ■}, whose <b>ink</b> the original draws at about three quarters of
     * the type size where this monospace face draws it at under a half. Reported by the user
     * 2026-08-13 (<i>"box icon in Add Item button is too small, compare to original"</i>) and
     * measured at 9 × 9 CSS px against the original's 14.3 × 14.3, barely three fifths of it.
     *
     * <p>The proportion is measured here rather than written down, for the same reason as above: how
     * much of an em {@code ■} covers is a fact about the bundled face and changes the day the face
     * does. Measured once at a large size, where rounding costs proportionally least, then scaled.
     *
     * @param glyph     the character to be drawn
     * @param targetInk how many pixels tall its ink should be
     */
    public static double sizeForInkHeight(String glyph, double targetInk) {
        double reference = 100;
        Text probe = new Text(glyph);
        probe.setFont(mono(reference));
        probe.setBoundsType(TextBoundsType.VISUAL);
        double inkAtReference = probe.getLayoutBounds().getHeight();
        if (inkAtReference <= 0) {
            return targetInk;              // a glyph with no ink at all; nothing to scale
        }
        return targetInk * reference / inkAtReference;
    }

    /**
     * The slant to apply to a label that should look italic.
     *
     * <p>Negative, and pivoted on the text's baseline, so the top of each letter leans right
     * while the bottom stays where it was, which is the direction a real italic leans, and the
     * direction a browser fakes.
     *
     * @param baselineY how far down the node the text sits, so the slant pivots there rather
     *                  than around the node's top edge
     */
    public static Shear oblique(double baselineY) {
        return new Shear(-OBLIQUE_SLANT, 0, 0, baselineY);
    }

    /**
     * Reads one font out of the jar and registers it with JavaFX.
     *
     * <p>Every failure here throws rather than falling back to the old {@code "monospace"}
     * alias. Falling back would restore exactly the behavior this change exists to remove, and
     * it would look completely normal: the app would start, text would render, nothing would be
     * red. That is the silent-fallback shape {@code CLAUDE.md} OD-1 already forbids for the 3D
     * pipeline. None of these failures can be caused by anything the person using the app did;
     * each one means the artifact was built wrong, so there is nothing to recover from.
     *
     * @return the family name JavaFX filed the font under, which is the authoritative one;
     *         guessing it and being wrong is itself one of the failures caught below
     */
    private static String load(String resource) {
        Font font;
        try (InputStream stream = Fonts.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("The bundled font " + resource
                        + " is missing from the build. Check that src/main/resources still holds"
                        + " it, and (on Android) that it is listed in the gluonfx plugin's"
                        + " <resourcesList> in pom.xml, which does not include resources unless"
                        + " they are named.");
            }
            font = Font.loadFont(stream, PROBE_SIZE);
        } catch (IOException cause) {
            throw new IllegalStateException("The bundled font " + resource
                    + " is present but could not be read.", cause);
        }
        if (font == null) {
            // loadFont reports failure by returning null rather than by throwing.
            throw new IllegalStateException("The bundled font " + resource
                    + " is present but JavaFX could not parse it. It is most likely truncated or"
                    + " corrupt; check its size against the one recorded in MANUAL.md.");
        }
        String family = font.getFamily();
        String resolved = Font.font(family, PROBE_SIZE).getFamily();
        if (!family.equals(resolved)) {
            throw new IllegalStateException("The bundled font " + resource + " loaded as \""
                    + family + "\", but asking JavaFX for that family gives back \"" + resolved
                    + "\". Text would silently render in the wrong typeface.");
        }
        return family;
    }
}
