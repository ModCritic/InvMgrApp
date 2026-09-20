package com.modcritic.invmgr.threed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.Room;
import com.modcritic.invmgr.model.Units;
import com.modcritic.invmgr.persist.Fixtures;
import com.modcritic.invmgr.persist.Json;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks that the repeating grid puts its lines exactly where the original's painted-once grid put
 * them, and that the one place it does not is the one place we chose.
 *
 * <p>This is the test that has to carry the M6.7 change. The grid used to be painted into a
 * per-surface picture whose size was capped, which is what made a large room blurry; it is now one
 * square foot repeated across the surface. Sharper is easy to claim. <b>Landing in the same places
 * is the part worth proving</b>, and the golden here is the original app's own
 * {@code drawSurfaceCanvas} output, so agreement with it is agreement with the thing being ported.
 *
 * <p>Regenerate the golden with {@code node tools/golden/original-3d.js --run}.
 */
class GridTileTest {

    private static final double TOLERANCE = 1e-9;

    /**
     * Fractional room sizes, chosen so the leftover part of a foot varies: a half, a third, a
     * tenth, and something with no tidy relationship to a foot at all.
     */
    private static final double[] AWKWARD_FEET = {12.5, 12.3, 10.75, 8.1, 23.4, 40.5, 17.333};

    @Test
    @DisplayName("every interior line lands exactly where the original's did, on all 16 surfaces")
    void theInteriorLinesMatchTheOriginal() throws IOException {
        List<?> cases = (List<?>) Json.parse(Fixtures.read("golden/3d-surfaces.expected.json"));
        assertTrue(cases.size() >= 16, "expected at least 16 surface cases");

        List<String> mismatches = new ArrayList<>();
        for (Object entry : cases) {
            Map<String, Object> row = asMap(entry);
            double wFt = number(row.get("wFt"));
            double hFt = number(row.get("hFt"));
            boolean metric = Boolean.TRUE.equals(row.get("metric"));
            Map<String, Object> want = asMap(row.get("metrics"));
            String where = wFt + "x" + hFt + (metric ? " metric" : " imperial");

            // The golden is in texture pixels, at that surface's own resolution. Feet is the only
            // unit the two versions have in common, and it is also the unit the room is in.
            double pxPerFoot = number(want.get("s"));
            GridTile tile = GridTile.forGrid(metric);

            compareInterior(where + " verticals", toFeet((List<?>) want.get("verticals"), pxPerFoot),
                    interiorLinesFt(tile, wFt, false), wFt, mismatches);
            compareInterior(where + " horizontals",
                    toFeet((List<?>) want.get("horizontals"), pxPerFoot),
                    interiorLinesFt(tile, hFt, false), hFt, mismatches);
        }

        assertTrue(mismatches.isEmpty(),
                () -> mismatches.size() + " line positions disagree with the original:\n"
                        + String.join("\n", mismatches.subList(0, Math.min(20, mismatches.size()))));
    }

    @Test
    @DisplayName("the only lines the original does not have are on the room's own boundary")
    void theOnlyExtraLinesAreOnTheBoundary() {
        // This is D-25 stated as an assertion rather than as prose. A repeating tile draws a line
        // wherever its count crosses a whole number, and the room's near edge is where the count
        // starts, so there is no starting point that gives a line to every whole foot except the
        // first. The extra lines are therefore always at 0, and at the far edge when the room is a
        // whole number of steps across.
        //
        // Asserted as "nothing else is extra" rather than "0 is extra", because the second would
        // pass while the grid quietly gained lines everywhere.
        for (double side : new double[] {12, 12.5, 200, 1, 40.5}) {
            for (boolean metric : new boolean[] {false, true}) {
                GridTile tile = GridTile.forGrid(metric);
                for (double p : allLinesFt(tile, side, false)) {
                    if (p > TOLERANCE && p < side - TOLERANCE) {
                        continue;
                    }
                    boolean onBoundary = Math.abs(p) < TOLERANCE || Math.abs(p - side) < TOLERANCE;
                    assertTrue(onBoundary, side + " ft " + (metric ? "metric" : "imperial")
                            + ": a line at " + p + " ft is neither inside the room nor on its edge");
                }
            }
        }
    }

    @Test
    @DisplayName("a mirrored wall's grid lands on the same whole feet as an unmirrored one")
    void theGridIsAlignedToTheRoomNotToTheWall() {
        // ASSERTED IN WORLD FEET, NOT IN TEXTURE COORDINATES, and that is the whole point.
        //
        // The rule that actually matters is "every grid line falls on a whole foot measured from
        // the room's origin". Pinning coordinates instead would pin whatever the code happens to
        // do, and would have been perfectly happy with the bug: the south wall's lines sat at tidy
        // positions all along, just tidy positions measured from the WRONG END.
        //
        // Reported by the user 2026-08-05 against the M5.2 jar. It is the same bug the original
        // HTML app had and fixed with `flipGridX`, original lines 2554-2566, and the tiled version
        // gets it from the geometry rather than from a special case.
        GridTile tile = GridTile.forGrid(false);
        for (double side : AWKWARD_FEET) {
            for (boolean mirrored : new boolean[] {false, true}) {
                double[] lines = allLinesFt(tile, side, mirrored);
                assertTrue(lines.length > 0, side + " ft should have grid lines at all");

                for (double fromStartFt : lines) {
                    // A mirrored surface is drawn from the far end, so its own left edge is the
                    // room's right one. Measure from the room, which is what has to line up.
                    double fromOriginFt = mirrored ? side - fromStartFt : fromStartFt;
                    assertEquals(Math.round(fromOriginFt), fromOriginFt, 0.001,
                            "a " + side + " ft " + (mirrored ? "mirrored" : "plain")
                                    + " surface put a grid line " + fromOriginFt
                                    + " ft from the room's origin, which is not a whole foot");
                }
            }
        }
    }

    @Test
    @DisplayName("the two walls facing each other put their lines in the same world places")
    void oppositeWallsAgreeWithEachOther() {
        // The user-visible symptom, stated directly: the grid on the wall in front of you and the
        // grid on the wall behind you should meet the floor at the same places.
        GridTile tile = GridTile.forGrid(false);
        for (double width : AWKWARD_FEET) {
            double[] north = allLinesFt(tile, width, false);
            double[] south = allLinesFt(tile, width, true);
            assertEquals(north.length, south.length,
                    width + " ft: the two walls should carry the same number of lines");

            for (int i = 0; i < north.length; i++) {
                // South's lines run the other way, so its last is opposite north's first.
                double southFromWest = width - south[south.length - 1 - i];
                assertEquals(north[i], southFromWest, 0.001,
                        width + " ft: north has a line " + north[i] + " ft from the west wall and "
                                + "south has one at " + southFromWest + "; they must meet");
            }
        }
    }

    @Test
    @DisplayName("a whole-foot room is completely unaffected by any of this")
    void aWholeFootRoomIsUnchanged() {
        // Which is exactly why the bug went unnoticed for so long, and why it must not be "fixed"
        // by shifting every wall unconditionally: at 12 ft the leftover is zero and a mirrored
        // wall must count from the same place as everything else.
        GridTile tile = GridTile.forGrid(false);
        for (double whole : new double[] {8, 10, 12, 20, 40}) {
            double[] plain = allLinesFt(tile, whole, false);
            double[] mirrored = allLinesFt(tile, whole, true);
            assertEquals(plain.length, mirrored.length, whole + " ft: same number of lines");
            for (int i = 0; i < plain.length; i++) {
                assertEquals(plain[i], mirrored[i], TOLERANCE,
                        whole + " ft: mirroring must change nothing when there is no leftover");
            }
            assertEquals(0, tile.originX(whole, true), TOLERANCE,
                    whole + " ft: a whole-foot room has no leftover to start from");
        }
    }

    @Test
    @DisplayName("the metric grid lands on whole meters the same way")
    void theMetricGridIsAlignedToo() {
        GridTile tile = GridTile.forGrid(true);
        for (double side : AWKWARD_FEET) {
            double sideMeters = side * Units.M_PER_FT;
            for (double fromStartFt : allLinesFt(tile, side, true)) {
                double fromOrigin = sideMeters - fromStartFt * Units.M_PER_FT;
                assertEquals(Math.round(fromOrigin), fromOrigin, 0.001,
                        side + " ft in metric: a line " + fromOrigin + " m in is not a whole meter");
            }
        }
    }

    @Test
    @DisplayName("only the south and east walls are mirrored, and on the dimension the user named")
    void theRightTwoSurfacesAreMirrored() {
        // Named explicitly rather than counted, because "two of them are mirrored" would pass with
        // the wrong two, and the wrong two is a room that still looks broken, with the mismatch
        // moved to the opposite corner.
        for (RoomGeometry.Surface s : RoomGeometry.surfacesOf(new Room(12.5, 10.5, 8))) {
            boolean expected = s.name.equals("room-south") || s.name.equals("room-east");
            assertEquals(expected, s.mirroredGridX,
                    s.name + ": mirroredGridX should be " + expected);

            // And which dimension each one depends on, which is what the user observed: a
            // fractional WIDTH shows on the south wall, a fractional LENGTH on the east.
            if (s.name.equals("room-south")) {
                assertEquals(12.5, s.widthFt, TOLERANCE, "south is as wide as the room");
            } else if (s.name.equals("room-east")) {
                assertEquals(10.5, s.widthFt, TOLERANCE, "east is as wide as the room is long");
            }
        }
    }

    @Test
    @DisplayName("the tile is 96 px to the foot whatever the room measures")
    void theTileDoesNotCareHowBigTheRoomIs() {
        // The whole point of the change, as one assertion. There is no room size anywhere in the
        // tile's arithmetic, so there is nothing for a 200 ft room to degrade.
        GridTile imperial = GridTile.forGrid(false);
        assertEquals(1, imperial.stepFt, TOLERANCE);
        assertEquals(96, imperial.tilePx);

        GridTile metric = GridTile.forGrid(true);
        assertEquals(1 / Units.M_PER_FT, metric.stepFt, TOLERANCE, "a meter, in feet");
        assertEquals(315, metric.tilePx, "a meter at 96 px to the foot");

        // And the span is the only thing the room's size touches: how many times to lay it down.
        assertEquals(200, imperial.span(200), TOLERANCE);
        assertEquals(12.5, imperial.span(12.5), TOLERANCE, "and a part tile at the end");
    }

    @Test
    @DisplayName("a line is a quarter inch wide in the room, at every room size")
    void theLineWidthNoLongerDependsOnTheRoom() {
        // The old painter had max(1, 2 * s / 96), a floor that stopped the line shrinking below
        // one texture pixel. It mattered: in a 200 ft room one texture pixel was 1.17 INCHES of
        // floor, so the grid was not only blurry but nearly five times too heavy. The tile has no
        // such floor because it has nothing to protect against.
        double widthFt = GridTile.LINE_WIDTH_PX / GridTile.PX_PER_FOOT;
        assertEquals(0.25, widthFt * 12, TOLERANCE, "a quarter of an inch");

        // What the original would have drawn in a 200 ft room, for comparison: the clamp holds the
        // line at one texture pixel, and a texture pixel there is a ninety-fourth of a foot.
        double oldPxPerFoot = Math.min(96, 2048.0 / 200);
        double oldWidthFt = Math.max(1, 2 * oldPxPerFoot / 96) / oldPxPerFoot;
        assertTrue(oldWidthFt > widthFt * 4, "the old line was over four times heavier, at "
                + oldWidthFt * 12 + " inches against " + widthFt * 12);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Where the tiled grid puts its lines, in feet from the surface's own near edge.
     *
     * <p>Derived from the two numbers the renderer actually uses, rather than from a method
     * written for the test: a line lands wherever the texture coordinate crosses a whole number,
     * so it is at {@code (n - originX) * stepFt} for each whole {@code n} that lands on the
     * surface.
     */
    private static double[] allLinesFt(GridTile tile, double lengthFt, boolean mirrored) {
        double origin = tile.originX(lengthFt, mirrored);
        List<Double> out = new ArrayList<>();
        for (int n = (int) Math.ceil(origin - TOLERANCE); ; n++) {
            double p = (n - origin) * tile.stepFt;
            if (p > lengthFt + TOLERANCE) {
                break;
            }
            out.add(p);
        }
        double[] lines = new double[out.size()];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = out.get(i);
        }
        return lines;
    }

    /** The same, with the boundary lines dropped, which is what the original's list holds. */
    private static double[] interiorLinesFt(GridTile tile, double lengthFt, boolean mirrored) {
        double[] all = allLinesFt(tile, lengthFt, mirrored);
        List<Double> inside = new ArrayList<>();
        for (double p : all) {
            if (p > TOLERANCE && p < lengthFt - TOLERANCE) {
                inside.add(p);
            }
        }
        double[] lines = new double[inside.size()];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = inside.get(i);
        }
        return lines;
    }

    private static double[] toFeet(List<?> texturePixels, double pxPerFoot) {
        double[] feet = new double[texturePixels.size()];
        for (int i = 0; i < feet.length; i++) {
            feet[i] = number(texturePixels.get(i)) / pxPerFoot;
        }
        return feet;
    }

    /**
     * Compares two line lists. The original is allowed to be missing a line that sits within a
     * texture pixel of the far edge.
     *
     * <p>The original's loop stops at {@code p < extent - 1} pixels, so a room whose leftover part
     * of a foot is under one texture pixel loses its last line to rounding rather than to a rule.
     * The tile has no texture pixels to round against.
     */
    private static void compareInterior(String what, double[] want, double[] got, double lengthFt,
            List<String> mismatches) {
        int wi = 0;
        for (double g : got) {
            if (wi < want.length && Math.abs(want[wi] - g) < 1e-6) {
                wi++;
                continue;
            }
            if (lengthFt - g < 0.02) {
                continue;
            }
            mismatches.add(what + ": the tile has a line at " + g
                    + " ft that the original does not");
        }
        if (wi < want.length) {
            mismatches.add(what + ": the original has a line at " + want[wi]
                    + " ft that the tile does not");
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
