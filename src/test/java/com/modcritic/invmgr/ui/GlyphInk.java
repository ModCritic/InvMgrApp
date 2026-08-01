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
 * perfect from the scene graph's side: the label was centred, its bounds were centred, every
 * number agreed — and the arrow still sat on the bottom border of the button. The disagreement
 * was that a button centres the <em>line box</em> of its font, and in a mathematics face the line
 * box is much taller than anything it will ever be asked to draw, so the visible mark ends up low
 * inside a correctly-centred box. Any assertion phrased in terms of node bounds would have passed
 * on the broken build. Counting lit pixels is the only version of the question that would have
 * failed, and it is also the question the person looking at the screen is asking.
 *
 * <p>Deliberately knows nothing about which glyph, which font, or which button. It takes the
 * background colour from a corner of the button's own interior and calls everything sufficiently
 * different from that "ink", so it works equally for {@code ⤓} drawn as a graphic and for
 * {@code ■} drawn as ordinary button text.
 */
final class GlyphInk {

    /**
     * How far a pixel must differ from the button's background, per channel, to count as ink.
     *
     * <p>Generous on purpose. The glyphs are a light grey on a dark grey button and the gap
     * between the two is over a hundred, so the only thing this threshold decides is how much of
     * each anti-aliased edge is counted — and since it is applied identically to all four sides of
     * the mark, whatever it trims is trimmed symmetrically and the centre does not move.
     */
    private static final int INK_THRESHOLD = 24;

    /** Skips the button's 1 px border and the anti-aliasing just inside it. */
    private static final int BORDER_INSET = 3;

    private GlyphInk() {
    }

    /**
     * Asserts that what was painted inside {@code button} is centred in it, on both axes.
     *
     * @param tolerance in pixels; 1 is right for a mark whose bounding box is a whole number of
     *                  pixels but whose true centre need not be
     */
    static void assertCentred(String what, WritableImage shot, javafx.scene.Node button,
            double tolerance) {
        Bounds box = button.localToScene(button.getBoundsInLocal());
        int left = (int) Math.round(box.getMinX()) + BORDER_INSET;
        int top = (int) Math.round(box.getMinY()) + BORDER_INSET;
        int right = (int) Math.round(box.getMaxX()) - BORDER_INSET;
        int bottom = (int) Math.round(box.getMaxY()) - BORDER_INSET;

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
        assertTrue(minX <= maxX, what + ": nothing was painted inside the button at all — the"
                + " glyph is missing, not merely off-centre");

        // The searched region is [left, right) x [top, bottom), so its centre is one half-pixel
        // short of the far edge. Comparing against the button's own centre instead would bake the
        // inset into the answer.
        double wantedX = (left + right - 1) / 2.0;
        double wantedY = (top + bottom - 1) / 2.0;
        double gotX = (minX + maxX) / 2.0;
        double gotY = (minY + maxY) / 2.0;

        String detail = String.format(
                "%s: ink %d..%d x %d..%d, centre (%.1f, %.1f), wanted (%.1f, %.1f)"
                        + " — off by (%+.1f, %+.1f). Gaps: %d above, %d below, %d left, %d right.",
                what, minX, maxX, minY, maxY, gotX, gotY, wantedX, wantedY,
                gotX - wantedX, gotY - wantedY,
                minY - top, bottom - 1 - maxY, minX - left, right - 1 - maxX);

        assertTrue(Math.abs(gotX - wantedX) <= tolerance, detail);
        assertTrue(Math.abs(gotY - wantedY) <= tolerance, detail);
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
