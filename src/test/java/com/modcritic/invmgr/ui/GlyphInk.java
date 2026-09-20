package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.geometry.Bounds;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

/**
 * Finds where a button's glyph was actually painted, by reading the pixels back out of a
 * screenshot.
 *
 * <p><b>Why measure paint rather than ask the scene graph.</b> The bug this exists for looked
 * perfect from the scene graph's side: the label was centered, its bounds were centered, every
 * number agreed, yet the arrow still sat on the bottom border of the button. The disagreement
 * was that a button centers the <em>line box</em> of its font, and in a mathematics face the line
 * box is much taller than anything it will ever be asked to draw, so the visible mark ends up low
 * inside a correctly-centered box. Any assertion phrased in terms of node bounds would have passed
 * on the broken build. Counting lit pixels is the only version of the question that would have
 * failed, and it is also the question the person looking at the screen is asking.
 *
 * <p>Deliberately knows nothing about which glyph, which font, or which button. It takes the
 * background color from a corner of the button's own interior and calls everything sufficiently
 * different from that "ink", so it works equally for {@code ⤓} drawn as a graphic and for
 * {@code ■} drawn as ordinary button text.
 */
final class GlyphInk {

    /**
     * How far a pixel must differ from the button's background, per channel, to count as ink.
     *
     * <p>Generous on purpose. The glyphs are a light gray on a dark gray button and the gap
     * between the two is over a hundred, so the only thing this threshold decides is how much of
     * each anti-aliased edge is counted, and since it is applied identically to all four sides of
     * the mark, whatever it trims is trimmed symmetrically and the center does not move.
     */
    private static final int INK_THRESHOLD = 24;

    /** Skips the button's 1 px border and the anti-aliasing just inside it. */
    private static final int BORDER_INSET = 3;

    private GlyphInk() {
    }

    /**
     * Asserts that what was painted inside {@code button} is centered in it, on both axes.
     *
     * @param tolerance in pixels; 1 is right for a mark whose bounding box is a whole number of
     *                  pixels but whose true center need not be
     */
    static void assertCentered(String what, WritableImage shot, javafx.scene.Node button,
            double tolerance) {
        Bounds box = button.localToScene(button.getBoundsInLocal());
        int left = (int) Math.round(box.getMinX()) + BORDER_INSET;
        int top = (int) Math.round(box.getMinY()) + BORDER_INSET;
        int right = (int) Math.round(box.getMaxX()) - BORDER_INSET;
        int bottom = (int) Math.round(box.getMaxY()) - BORDER_INSET;

        int[] ink = inkBounds(what, shot, left, top, right, bottom);
        int minX = ink[0];
        int maxX = ink[1];
        int minY = ink[2];
        int maxY = ink[3];

        // The searched region is [left, right) x [top, bottom), so its center is one half-pixel
        // short of the far edge. Comparing against the button's own center instead would bake the
        // inset into the answer.
        double wantedX = (left + right - 1) / 2.0;
        double wantedY = (top + bottom - 1) / 2.0;
        double gotX = (minX + maxX) / 2.0;
        double gotY = (minY + maxY) / 2.0;

        String detail = String.format(
                "%s: ink %d..%d x %d..%d, center (%.1f, %.1f), wanted (%.1f, %.1f)"
                        + ", off by (%+.1f, %+.1f). Gaps: %d above, %d below, %d left, %d right.",
                what, minX, maxX, minY, maxY, gotX, gotY, wantedX, wantedY,
                gotX - wantedX, gotY - wantedY,
                minY - top, bottom - 1 - maxY, minX - left, right - 1 - maxX);

        assertTrue(Math.abs(gotX - wantedX) <= tolerance, detail);
        assertTrue(Math.abs(gotY - wantedY) <= tolerance, detail);
    }

    /**
     * Asserts how big the painted mark is, in pixels.
     *
     * <p>Separate from {@link #assertCentered} because they catch different mistakes: an icon can be
     * perfectly centered and the wrong size, which is what the 3D button's cube was, drawn from the
     * Add button's 18 px font instead of the top bar's own 13 px, so 6.5% too large and centered
     * beautifully.
     *
     * <p>Measured against the reference screenshot rather than against the source file, because the
     * question is what ends up on the screen. Anti-aliasing means the answer is a pixel wide either
     * way, hence the tolerance.
     */
    static void assertInkSize(String what, WritableImage shot, javafx.scene.Node button,
            int expectedWidth, int expectedHeight, int tolerance) {
        Bounds box = button.localToScene(button.getBoundsInLocal());
        int[] ink = inkBounds(what, shot,
                (int) Math.round(box.getMinX()) + BORDER_INSET,
                (int) Math.round(box.getMinY()) + BORDER_INSET,
                (int) Math.round(box.getMaxX()) - BORDER_INSET,
                (int) Math.round(box.getMaxY()) - BORDER_INSET);

        int width = ink[1] - ink[0] + 1;
        int height = ink[3] - ink[2] + 1;
        String detail = String.format("%s: painted %d x %d px, expected %d x %d (±%d)",
                what, width, height, expectedWidth, expectedHeight, tolerance);
        assertTrue(Math.abs(width - expectedWidth) <= tolerance, detail);
        assertTrue(Math.abs(height - expectedHeight) <= tolerance, detail);
    }

    /** The box the painted mark occupies, as {@code minX, maxX, minY, maxY}. */
    private static int[] inkBounds(String what, WritableImage shot,
            int left, int top, int right, int bottom) {
        PixelReader pixels = shot.getPixelReader();
        Color background = pixels.getColor(left, top);

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                if (differs(pixels.getColor(x, y), background)) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        assertTrue(minX <= maxX, what + ": nothing was painted inside the button at all: the"
                + " glyph is missing, not merely off-center");
        return new int[] {minX, maxX, minY, maxY};
    }

    private static boolean differs(Color pixel, Color background) {
        return to255(pixel.getRed()) - to255(background.getRed()) > INK_THRESHOLD
                || to255(pixel.getGreen()) - to255(background.getGreen()) > INK_THRESHOLD
                || to255(pixel.getBlue()) - to255(background.getBlue()) > INK_THRESHOLD;
    }

    private static int to255(double channel) {
        return (int) Math.round(channel * 255);
    }
}
