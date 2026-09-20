package com.modcritic.invmgr.threed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.persist.Fixtures;
import com.modcritic.invmgr.persist.Json;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks the vignette's arithmetic against the original's own {@code drawSurfaceCanvas}.
 *
 * <p>Worth a differential because of one number that is easy to get plausibly wrong: the radius is
 * a fraction of the <em>longer side</em> rather than of the half-diagonal. The OD-1 spike got that
 * wrong, using {@code hypot(cx, cy) * 0.7}, which is about 1.4x tighter, and the result still looks
 * like a vignette, which is exactly why it needs measuring rather than eyeballing.
 *
 * <p><b>The grid used to be checked here too, and moved to {@code GridTileTest} at M6.7</b>, along
 * with the grid itself. The golden file is the same one and the line positions are still compared
 * against it; only the class doing the comparing changed.
 *
 * <p>Regenerate with {@code node tools/golden/original-3d.js --run}.
 */
class SurfaceMetricsTest {

    private static final double TOLERANCE = 1e-12;

    /** The app's own scale, repeated here so the exact-comparison test can work out which rows fit. */
    private static final double MAX_PX_PER_FOOT = SurfaceMetrics.MAX_PX_PER_FOOT;

    @Test
    @DisplayName("all 16 surfaces put the vignette where the original puts it")
    void matchesTheOriginalSurfaceMetrics() throws IOException {
        // WHAT THIS COMPARES CHANGED AT M6.7, AND THE REASON IS WORTH READING BEFORE TRUSTING IT.
        //
        // It used to compare the picture's size in pixels against the original's, exactly. It
        // cannot any more, because the cap deliberately differs: the original sizes this picture
        // to hold a grid and we no longer put a grid on it. A 12 x 10 ft wall was 1152 x 960 there
        // and is 512 x 427 here.
        //
        // What is compared instead is everything about the vignette that a viewer can see, and
        // none of it depends on the cap: how far the darkening reaches AS A FRACTION of the
        // surface, and whether the picture has the surface's own proportions. Both would fail on
        // the half-diagonal mistake the OD-1 spike made, and both would fail on a picture
        // stretched to fill a square texture.
        List<?> cases = (List<?>) Json.parse(Fixtures.read("golden/3d-surfaces.expected.json"));
        assertTrue(cases.size() >= 16, "expected at least 16 surface cases");

        List<String> mismatches = new ArrayList<>();
        int exact = 0;
        for (Object entry : cases) {
            Map<String, Object> row = asMap(entry);
            double wFt = number(row.get("wFt"));
            double hFt = number(row.get("hFt"));
            Map<String, Object> want = asMap(row.get("metrics"));
            String where = wFt + "x" + hFt;

            double wantW = number(want.get("w"));
            double wantH = number(want.get("h"));
            SurfaceMetrics got = SurfaceMetrics.forSurface(wFt, hFt);

            compare(where + " vignette radius as a fraction of the longer side",
                    number(want.get("vignetteRadius")) / Math.max(wantW, wantH),
                    got.vignetteRadiusPx / Math.max(got.widthPx, got.heightPx), mismatches);

            // One pixel of slack on the shorter side, which is all that rounding two independently
            // rounded sides can account for. See bothSidesAreScaledByTheSameNumber.
            double wantShape = wantW / wantH;
            double gotShape = (double) got.widthPx / got.heightPx;
            if (Math.abs(wantShape - gotShape) > wantShape / Math.min(got.heightPx, wantH)) {
                mismatches.add(where + " proportions: want " + wantShape + " got " + gotShape);
            }

            // A surface small enough to sit under BOTH caps is still compared bit for bit, so at
            // least one anchor to the original's own numbers survives rather than none.
            if (Math.max(wFt, hFt) * MAX_PX_PER_FOOT <= SurfaceMetrics.MAX_TEXTURE_PX) {
                exact++;
                compare(where + " pixelsPerFoot", number(want.get("s")), got.pixelsPerFoot,
                        mismatches);
                compare(where + " widthPx", wantW, got.widthPx, mismatches);
                compare(where + " heightPx", wantH, got.heightPx, mismatches);
                compare(where + " vignetteRadiusPx", number(want.get("vignetteRadius")),
                        got.vignetteRadiusPx, mismatches);
            }
        }

        assertTrue(exact > 0, "no golden surface is small enough to compare exactly any more, so "
                + "this test has quietly stopped touching the original's own numbers at all");
        assertTrue(mismatches.isEmpty(),
                () -> mismatches.size() + " values disagree with the original:\n"
                        + String.join("\n", mismatches.subList(0, Math.min(20, mismatches.size()))));
    }

    @Test
    @DisplayName("the vignette reaches 70% of the longer side, not of the half-diagonal")
    void theVignetteRadiusIsTheOriginalsNotTheSpikes() {
        // The spike used hypot(halfWidth, halfHeight) * 0.7. For a square surface that is 0.495
        // of the side where the original is 0.7 of it: a noticeably tighter, darker vignette.
        SurfaceMetrics square = SurfaceMetrics.forSurface(10, 10);
        double side = square.widthPx;
        assertEquals(0.7 * side, square.vignetteRadiusPx, TOLERANCE);

        double spikeWouldBe = Math.hypot(side / 2, side / 2) * 0.7;
        assertTrue(square.vignetteRadiusPx > spikeWouldBe * 1.3,
                "the original's radius is about 1.4x the spike's, got "
                        + square.vignetteRadiusPx + " vs " + spikeWouldBe);

        // And on a long, thin wall it follows the LONGER side, so the vignette stays circular
        // rather than stretching to the surface's shape.
        SurfaceMetrics wall = SurfaceMetrics.forSurface(40, 3);
        assertEquals(0.7 * Math.max(wall.widthPx, wall.heightPx), wall.vignetteRadiusPx, TOLERANCE);
    }

    @Test
    @DisplayName("the picture keeps the surface's own proportions, which is what keeps it circular")
    void bothSidesAreScaledByTheSameNumber() {
        // The radius above is a circle in the picture. It only arrives on the wall as a circle if
        // the picture has the wall's proportions, so scaling the two sides independently to fill a
        // square texture would undo the test above without failing it.
        for (double[] shape : new double[][] {{40, 3}, {200, 50}, {12, 10}, {1, 1}}) {
            SurfaceMetrics m = SurfaceMetrics.forSurface(shape[0], shape[1]);
            double want = shape[0] / shape[1];

            // Both sides are rounded to whole pixels, so the ratio cannot be exact: a 40 x 3 ft
            // wall wants 2048 x 153.6 and gets 154. One pixel of slack on the shorter side is the
            // most that rounding can account for, and it is nowhere near enough slack to let the
            // mistake through: a picture stretched to fill a square texture would report 1.0 here
            // against 13.3.
            assertEquals(want, (double) m.widthPx / m.heightPx, want / m.heightPx,
                    shape[0] + "x" + shape[1] + " ft became " + m.widthPx + "x" + m.heightPx
                            + ", which is a different shape from the wall");
        }
    }

    @Test
    @DisplayName("no picture exceeds the cap, and a small one is still drawn at full detail")
    void theTextureIsCapped() {
        // Bounded on both sides: big surfaces must be capped, and small ones must NOT be. A cap
        // that applied everywhere would be a different bug from a cap that applied nowhere and
        // this catches both.
        SurfaceMetrics huge = SurfaceMetrics.forSurface(200, 200);
        assertTrue(Math.max(huge.widthPx, huge.heightPx) <= SurfaceMetrics.MAX_TEXTURE_PX,
                "got " + huge.widthPx + "x" + huge.heightPx);

        SurfaceMetrics wall = SurfaceMetrics.forSurface(200, 50);
        assertTrue(Math.max(wall.widthPx, wall.heightPx) <= SurfaceMetrics.MAX_TEXTURE_PX);

        // A cupboard wall is under the cap and gets the app's own 96 pixels to the foot.
        SurfaceMetrics small = SurfaceMetrics.forSurface(4, 3);
        assertEquals(96, small.pixelsPerFoot, TOLERANCE);
        assertEquals(384, small.widthPx);
        assertEquals(288, small.heightPx);

        // And the cap really is the smaller one now. Stated against the old number rather than
        // against the constant, so that reverting the constant fails here rather than passing
        // quietly against itself.
        assertTrue(SurfaceMetrics.MAX_TEXTURE_PX < 2048,
                "M6.7 lowered this below the original's 2048; see OI-4 and SurfaceTextureTest");
    }

    private void compare(String what, double want, double got, List<String> mismatches) {
        if (Math.abs(want - got) > TOLERANCE) {
            mismatches.add(what + ": want " + want + " got " + got);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }
}
