package com.modcritic.invmgr.threed.jfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.threed.SurfaceMetrics;
import com.modcritic.invmgr.ui.Tokens;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The two pictures a surface is made of, checked as pictures rather than as arithmetic.
 *
 * <p>{@code GridTileTest} and {@code SurfaceMetricsTest} check the numbers, which they can do with
 * no window in the room. These need one, because painting a canvas and taking its picture is
 * something only the interface thread may do, and because the questions here are about what came
 * out of the paint rather than about what went in.
 */
class SurfaceTextureTest extends ApplicationTest {

    /** The cap the vignette used to be drawn at, kept here as the thing to measure against. */
    private static final double OLD_CAP_PX = 2048;

    /** Shapes worth trying: a square floor, a long thin wall, an ordinary room, and a cupboard. */
    private static final double[][] SHAPES = {{200, 200}, {200, 10}, {40, 3}, {12, 10}, {1, 1}};

    @Override
    public void start(Stage stage) {
        stage.show();
    }

    @Test
    @DisplayName("shrinking the vignette's picture does not change the vignette")
    void theSmallerVignetteIsTheSamePicture() {
        // THE EVIDENCE FOR OI-4, and the reason it is a test rather than a paragraph.
        //
        // A vignette is a smooth radial gradient and nothing else, so drawing it at a fraction of
        // the resolution should cost nothing: there is no detail to lose. "Should" is the word
        // that needs checking. Both are sampled at the same FRACTIONAL positions across the
        // surface, because the two pictures are deliberately different sizes and comparing them
        // pixel index by pixel index would be comparing different parts of the wall.
        for (double[] shape : SHAPES) {
            Image small = paint(() -> SurfaceTexture.vignette(shape[0], shape[1]));
            Image large = paint(() -> SurfaceTexture.vignette(shape[0], shape[1], OLD_CAP_PX));

            double worst = 0;
            double total = 0;
            int samples = 0;
            for (int i = 0; i <= 40; i++) {
                for (int j = 0; j <= 40; j++) {
                    double u = i / 40.0;
                    double v = j / 40.0;
                    double a = alphaAt(small, u, v);
                    double b = alphaAt(large, u, v);
                    worst = Math.max(worst, Math.abs(a - b));
                    total += Math.abs(a - b);
                    samples++;
                }
            }

            String where = shape[0] + "x" + shape[1] + " ft";
            System.out.printf("PROBE %-14s cap %4.0f gives %dx%d, worst alpha difference from the "
                            + "2048 version %.4f, mean %.4f%n",
                    where, SurfaceMetrics.MAX_TEXTURE_PX,
                    (int) small.getWidth(), (int) small.getHeight(), worst, total / samples);

            // TIGHT ON PURPOSE, AND IT IS PINNING A DECISION RATHER THAN A TOLERANCE.
            //
            // At the shipped 512 this comes out at exactly 0.0039 on every shape, which is 1 of
            // 255: the smallest difference an 8-bit picture can hold. So the bar is set just above
            // that rather than somewhere comfortable. 256 measures 0.0078 and 128 measures 0.0118,
            // both still invisible on a dark gradient and both a few milliseconds cheaper, so a
            // loose bar here would let the cap drift downward with nobody deciding anything. If
            // this fails because someone lowered the cap deliberately, read OI-4 in
            // docs/claude/OPEN-IDEAS.md and then move the bar on purpose.
            assertTrue(worst < 0.005, where + ": the vignette at "
                    + SurfaceMetrics.MAX_TEXTURE_PX + " differs from the 2048 one by " + worst
                    + " at its worst, where 1 of 255 is 0.0039 and is what 512 gives");
        }
    }

    @Test
    @DisplayName("the vignette is clear in the middle and dark at the edge, whatever the shape")
    void theVignetteActuallyDarkensTheEdges() {
        // The test above compares two pictures and would be perfectly happy if both were blank.
        // This one says what the picture has to BE.
        for (double[] shape : SHAPES) {
            Image vignette = paint(() -> SurfaceTexture.vignette(shape[0], shape[1]));
            String where = shape[0] + "x" + shape[1] + " ft";

            assertEquals(0, alphaAt(vignette, 0.5, 0.5), 0.02,
                    where + ": the middle of a surface should not be darkened at all");

            // The corner is the furthest point from the middle, so it is the darkest.
            double corner = alphaAt(vignette, 0.99, 0.99);
            assertTrue(corner > alphaAt(vignette, 0.5, 0.5) + 0.05,
                    where + ": the corner should be darker than the middle, got " + corner);
            assertTrue(corner <= SurfaceMetrics.VIGNETTE_MAX_ALPHA + 0.01,
                    where + ": nothing may be darker than VIGNETTE_MAX_ALPHA, got " + corner);
        }
    }

    @Test
    @DisplayName("the grid tile is one square of floor with half a line down each edge")
    void theTileIsBuiltForRepeating() {
        Image tile = paint(() -> SurfaceTexture.gridTile(false));
        assertEquals(96, (int) tile.getWidth(), "one foot at 96 pixels to the foot");
        assertEquals(96, (int) tile.getHeight());

        // Half a line at each edge, so that two tiles side by side make one whole line centered on
        // the seam. Drawing a whole line at one edge instead puts every line half a line off,
        // which is an eighth of an inch in the room and shows against the flat view.
        assertLine(tile, 0, 48, "the left edge");
        assertLine(tile, 95, 48, "the right edge");
        assertLine(tile, 48, 0, "the top edge");
        assertLine(tile, 48, 95, "the bottom edge");

        // And everything between the edges is plain floor, or the grid would be denser than one
        // line to the foot. Checked one pixel in from each edge as well as in the middle, which is
        // what makes the half-line assertions above mean "half" rather than "at least half".
        for (int x : new int[] {1, 48, 94}) {
            assertEquals(Tokens.ROOM_FILL, tile.getPixelReader().getColor(x, 48),
                    "x=" + x + " of a tile is floor, not a line");
        }

        // The same tile comes back next time rather than being repainted: every surface in the
        // room asks for it, and a rebuild would otherwise pay for it five times over.
        assertTrue(tile == paint(() -> SurfaceTexture.gridTile(false)),
                "the tile should be kept, not repainted");
    }

    /**
     * Paints on the interface thread and hands the picture back here.
     *
     * <p>Both painters take a picture of a canvas, which JavaFX allows only on its own thread. The
     * first version of this class called them directly and every test threw
     * {@code IllegalStateException: Not on FX application thread} before it reached an assertion.
     */
    private static Image paint(java.util.concurrent.Callable<Image> painter) {
        return WaitForAsyncUtils.waitForAsyncFx(5000, painter);
    }

    /**
     * Asserts a tile pixel carries a grid line.
     *
     * <p><b>Against the blend, not against the token.</b> {@code Tokens.GRID_LINE} is
     * {@code rgba(0, 0, 0, 0.5)}, so what lands on the tile is half of it over the floor
     * underneath, and the first version of this test compared the painted pixel with the raw token
     * and failed on a tile that was perfectly correct. Working the blend out from both tokens
     * rather than writing {@code #1d1d1d} means this stays true if either token is ever changed.
     */
    private static void assertLine(Image tile, int x, int y, String where) {
        double a = Tokens.GRID_LINE.getOpacity();
        Color over = Tokens.GRID_LINE;
        Color under = Tokens.ROOM_FILL;
        Color want = new Color(
                under.getRed() * (1 - a) + over.getRed() * a,
                under.getGreen() * (1 - a) + over.getGreen() * a,
                under.getBlue() * (1 - a) + over.getBlue() * a,
                1);
        Color got = tile.getPixelReader().getColor(x, y);
        assertEquals(want.getRed(), got.getRed(), 1.0 / 255,
                where + " of the tile should carry a grid line, at " + x + "," + y
                        + "; wanted " + want + " and got " + got);
        assertEquals(want.getGreen(), got.getGreen(), 1.0 / 255, where + " green");
        assertEquals(want.getBlue(), got.getBlue(), 1.0 / 255, where + " blue");
    }

    /** How opaque the vignette is a given fraction of the way across and down it. */
    private static double alphaAt(Image image, double u, double v) {
        PixelReader pixels = image.getPixelReader();
        int x = (int) Math.min(image.getWidth() - 1, Math.round(u * (image.getWidth() - 1)));
        int y = (int) Math.min(image.getHeight() - 1, Math.round(v * (image.getHeight() - 1)));
        Color c = pixels.getColor(x, y);
        return c.getOpacity();
    }
}
