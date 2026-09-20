package com.modcritic.invmgr.threed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.Room;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The descent and the ascent: how long they take, which curve each channel travels along, and
 * where they start and finish.
 *
 * <p><b>The per-channel curves are the point of this class.</b> {@code SPEC-3D-VIEW.md} §3 says
 * the original needed several attempts to learn that position and orientation must not share a
 * curve, and that unifying them makes the descent read as front-loaded even when the numbers are
 * in step. That is invisible to a screenshot and invisible to any test that only checks the camera
 * arrived, so the tests below compare two channels <em>at the same instant</em>, which is the
 * only question that can tell the difference.
 */
class TransitionsTest {

    private static final double TOLERANCE = 1e-12;

    private static Room room(double w, double l, double h) {
        return new Room(w, l, h);
    }

    private static CameraPose overhead(double y) {
        return new CameraPose(6, y, 5, 0, -Math.PI / 2);
    }

    @Test
    @DisplayName("the two durations are the ones the original uses")
    void theDurationsAreTheOriginals() {
        assertEquals(2230, Transitions.DESCENT_MS, TOLERANCE);
        assertEquals(1920, Transitions.ASCENT_MS, TOLERANCE);
        assertTrue(Transitions.ASCENT_MS < Transitions.DESCENT_MS,
                "coming back out is quicker than going in, and deliberately so");
    }

    @Test
    @DisplayName("the bars and the side panels slide at different speeds")
    void theChromeTimingsAreNotAllTheSame() {
        // Easy to collapse into one number while tidying. The bars carry
        // `transition: transform 0.35s` from the 3D rules; the item list and the slider drawer
        // carry their own 0.22s from their ordinary rules, which the 3D rules never override.
        assertEquals(350, Transitions.BAR_SLIDE_MS, TOLERANCE);
        assertEquals(220, Transitions.PANEL_SLIDE_MS, TOLERANCE);
        assertTrue(Transitions.PANEL_SLIDE_MS < Transitions.BAR_SLIDE_MS,
                "the sides leave quicker than the top and bottom");

        // And the bars must not stop taking up space until after they have finished sliding, or
        // the room visibly jumps outward to fill the gap while they are still on their way out.
        assertTrue(Transitions.BARS_LEAVE_LAYOUT_MS > Transitions.BAR_SLIDE_MS,
                "the bars leave layout after the slide ends, not during it");

        assertEquals(1.05, Transitions.SLIDE_FRACTION, TOLERANCE,
                "105%, so borders and shadows clear the edge too");
    }

    @Test
    @DisplayName("the descent drops to eye height and levels off, and moves nothing else")
    void theDescentGoesWhereItShould() {
        CameraTween descent = Transitions.descent(overhead(140));

        CameraPose start = descent.at(0);
        assertEquals(140, start.y, TOLERANCE);
        assertEquals(-Math.PI / 2, start.pitch, TOLERANCE, "starts looking straight down");

        CameraPose end = descent.at(1);
        assertEquals(CameraPose.EYE_HEIGHT_FT, end.y, TOLERANCE);
        assertEquals(0, end.pitch, TOLERANCE, "ends level");

        // Where you are standing is decided before the descent starts, so it must not drift.
        assertEquals(6, end.x, TOLERANCE);
        assertEquals(5, end.z, TOLERANCE);
        assertEquals(0, end.yaw, TOLERANCE);
    }

    @Test
    @DisplayName("on the way down pitch lags height: they do NOT share a curve")
    void theDescentGivesPitchItsOwnCurve() {
        CameraTween descent = Transitions.descent(overhead(140));

        // Read both channels as "fraction of the journey completed" so they are comparable, then
        // ask whether pitch is behind height at the same instant. Unify the curves and these two
        // become equal at every p, and this test is what notices.
        for (double p : new double[] {0.15, 0.3, 0.45, 0.6}) {
            CameraPose at = descent.at(p);
            double heightDone = (140 - at.y) / (140 - CameraPose.EYE_HEIGHT_FT);
            double pitchDone = (at.pitch - -Math.PI / 2) / (Math.PI / 2);
            assertTrue(pitchDone < heightDone,
                    "at " + p + " the camera should still be looking down while it falls: "
                            + "pitch " + pitchDone + " vs height " + heightDone);
        }

        assertEquals(Easing.IN_CUBIC, descent.curveFor(CameraTween.Channel.PITCH));
        assertEquals(Easing.IN_OUT_SINE, descent.curveFor(CameraTween.Channel.Y));
    }

    @Test
    @DisplayName("the ascent returns to the middle of the room, straight down, facing north")
    void theAscentGoesBackOverhead() {
        Room r = room(12, 10, 8);
        CameraPose standing = new CameraPose(3, CameraPose.EYE_HEIGHT_FT, 9, 0.8, -0.2);
        CameraTween ascent = Transitions.ascent(standing, r, 140);

        CameraPose end = ascent.at(1);
        assertEquals(6, end.x, TOLERANCE, "half the room's width");
        assertEquals(5, end.z, TOLERANCE, "half the room's length");
        assertEquals(140, end.y, TOLERANCE);
        assertEquals(0, end.yaw, TOLERANCE);
        assertEquals(-Math.PI / 2, end.pitch, TOLERANCE);
    }

    @Test
    @DisplayName("on the way up pitch and yaw lead position: again, not one shared curve")
    void theAscentGivesOrientationItsOwnCurve() {
        Room r = room(12, 10, 8);
        CameraPose standing = new CameraPose(0, CameraPose.EYE_HEIGHT_FT, 0, 0, 0);
        CameraTween ascent = Transitions.ascent(standing, r, 140);

        for (double p : new double[] {0.15, 0.3, 0.45, 0.6}) {
            CameraPose at = ascent.at(p);
            double heightDone = (at.y - CameraPose.EYE_HEIGHT_FT) / (140 - CameraPose.EYE_HEIGHT_FT);
            double pitchDone = (0 - at.pitch) / (Math.PI / 2);
            assertTrue(pitchDone > heightDone,
                    "at " + p + " the camera should already be tipping down while it still "
                            + "climbs: pitch " + pitchDone + " vs height " + heightDone);
        }

        assertEquals(Easing.OUT_CUBIC, ascent.curveFor(CameraTween.Channel.PITCH));
        assertEquals(Easing.OUT_CUBIC, ascent.curveFor(CameraTween.Channel.YAW));
        assertEquals(Easing.IN_OUT_SINE, ascent.curveFor(CameraTween.Channel.Y));
        assertEquals(Easing.IN_OUT_SINE, ascent.curveFor(CameraTween.Channel.X));
    }

    @Test
    @DisplayName("the ascent takes the short way round rather than unwinding every turn")
    void theStartingYawIsNormalized() {
        Room r = room(12, 10, 8);

        // Facing 170 degrees, i.e. almost due south. The near way back to north is 10 degrees
        // westward; the far way is 170 degrees eastward. Without the normalization the camera
        // would take the far one.
        CameraPose facingSouthish = new CameraPose(6, 6, 5, Math.toRadians(170), 0);
        CameraTween ascent = Transitions.ascent(facingSouthish, r, 140);
        assertEquals(Math.toRadians(170), ascent.from().yaw, 1e-9,
                "170 degrees is already inside -180..180 and must be left alone");

        // Three full turns to the right and then a little more. The camera has spun up 1100
        // degrees of yaw; it must still take the same 10-degree route home.
        CameraPose spunUp = new CameraPose(6, 6, 5, Math.toRadians(170 + 720), 0);
        CameraTween wound = Transitions.ascent(spunUp, r, 140);
        assertEquals(Math.toRadians(170), wound.from().yaw, 1e-9,
                "two extra turns must be unwound before interpolating, not traveled through");

        // Past half a turn the near way is the OTHER way, and the sign has to flip for that.
        CameraPose justPastSouth = new CameraPose(6, 6, 5, Math.toRadians(190), 0);
        double startYaw = Transitions.ascent(justPastSouth, r, 140).from().yaw;
        assertEquals(Math.toRadians(-170), startYaw, 1e-9,
                "190 degrees east is 170 degrees west, and the short way home is eastward");
        assertTrue(startYaw < 0, "so the interpolation must run through negative yaw, not positive");
    }

    @Test
    @DisplayName("the picture stays solid until the last 30% of the ascent, then fades out")
    void theOverlayFadesOverTheLastThirty() {
        assertEquals(1, Transitions.overlayOpacity(0), TOLERANCE);
        assertEquals(1, Transitions.overlayOpacity(0.5), TOLERANCE);
        assertEquals(1, Transitions.overlayOpacity(0.7), TOLERANCE, "solid right up to the point");

        assertEquals(0.5, Transitions.overlayOpacity(0.85), 1e-9, "halfway through the fade");
        assertEquals(0, Transitions.overlayOpacity(1), TOLERANCE, "gone by the end");

        // Bounded on both sides: a fade that starts too early is as wrong as one that never runs.
        assertTrue(Transitions.overlayOpacity(0.69) > 0.99, "must not have begun before 0.7");
        assertTrue(Transitions.overlayOpacity(0.71) < 1, "must have begun just after 0.7");

        // Clamped, since a frame can arrive past the end.
        assertEquals(0, Transitions.overlayOpacity(1.5), TOLERANCE);
        assertEquals(1, Transitions.overlayOpacity(-1), TOLERANCE);
    }

    @Test
    @DisplayName("the overhead height the ascent returns to is the one the lens asks for")
    void theAscentUsesThePerspectiveHeight() {
        // Not recomputed here -- handed in, so there is exactly one overhead formula in the app
        // and the descent's start and the ascent's end cannot drift apart. Checked at three room
        // sizes so a hardcoded height would fail.
        Room[] rooms = {room(12, 10, 8), room(40, 30, 12), room(8, 60, 9)};
        for (Room r : rooms) {
            double expected = Perspective.overheadHeightFt(r, 2560, 1440);
            CameraTween ascent = Transitions.ascent(new CameraPose(1, 6, 1, 0, 0), r, expected);
            assertEquals(expected, ascent.at(1).y, TOLERANCE,
                    "a " + r.w + "x" + r.l + " room must return to its own overhead height");
            assertTrue(expected >= r.h + Perspective.OVERHEAD_CEILING_CLEARANCE_FT,
                    "and never lower than the ceiling plus clearance");
        }
    }
}
