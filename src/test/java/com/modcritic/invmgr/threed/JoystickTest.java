package com.modcritic.invmgr.threed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.Room;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The virtual joystick's arithmetic: where the knob goes, and which way that is.
 *
 * <p>Written the same way {@code WalkTest} is: every case names an axis <em>and</em> a sign, because
 * a thumbstick that walks backwards covers exactly the same ground as one that walks forwards, and
 * "the camera moved" cannot tell them apart.
 */
class JoystickTest {

    private static final double TOLERANCE = 1e-9;

    private static Joystick held(double dxPx, double dyPx) {
        Joystick joystick = new Joystick();
        joystick.grab(7, dxPx, dyPx);
        return joystick;
    }

    @Test
    @DisplayName("a stick nobody is holding is in the middle and asks for no movement")
    void anUntouchedStickIsStill() {
        Joystick joystick = new Joystick();

        assertFalse(joystick.isActive());
        assertEquals(0, joystick.knobX(), TOLERANCE);
        assertEquals(0, joystick.knobY(), TOLERANCE);
        assertEquals(0, joystick.forward(), TOLERANCE);
        assertEquals(0, joystick.strafe(), TOLERANCE);

        // And a stray movement cannot deflect it. On a phone every finger anywhere on the glass
        // produces movement events; only the one that pressed the base may move this.
        joystick.moveTo(30, 0);
        assertEquals(0, joystick.knobX(), TOLERANCE, "a stick nobody grabbed must not deflect");
        assertEquals(0, joystick.strafe(), TOLERANCE);
    }

    @Test
    @DisplayName("pressing the edge of the base already asks for movement, without moving first")
    void grabbingAppliesTheFirstPositionImmediately() {
        Joystick joystick = held(0, -20);

        assertTrue(joystick.isActive());
        assertEquals(-20, joystick.knobY(), TOLERANCE);
        assertEquals(20 / Joystick.RADIUS_PX, joystick.forward(), TOLERANCE,
                "a thumb pressed above the middle must walk forward at once");
    }

    @Test
    @DisplayName("a thumb above the middle walks forward and below it walks back")
    void forwardIsTheNegativeOfTheScreensDownwardY() {
        // The screen counts y downward, so "up the screen" is a negative dy. One sign, and it is
        // the easiest thing in this class to get backwards; a stick that walks the wrong way is
        // still a stick that walks.
        assertTrue(held(0, -30).forward() > 0, "thumb up the screen must walk forward");
        assertTrue(held(0, 30).forward() < 0, "thumb down the screen must walk back");
        assertEquals(1, held(0, -30).forward(), TOLERANCE, "full deflection forward is exactly 1");
        assertEquals(-1, held(0, 30).forward(), TOLERANCE);

        assertTrue(held(30, 0).strafe() > 0, "thumb right must strafe right");
        assertTrue(held(-30, 0).strafe() < 0, "thumb left must strafe left");
        assertEquals(1, held(30, 0).strafe(), TOLERANCE);
    }

    @Test
    @DisplayName("the knob stops at thirty pixels however far the thumb slides away")
    void deflectionIsClampedToTheRadius() {
        Joystick joystick = held(400, 300);      // a 500 px displacement, 3-4-5

        assertEquals(Joystick.RADIUS_PX, Math.hypot(joystick.knobX(), joystick.knobY()), TOLERANCE,
                "the knob must sit exactly on the radius");

        // Clamped along the same direction, not squared off per axis: 400/300 in, 24/18 out.
        assertEquals(24, joystick.knobX(), TOLERANCE);
        assertEquals(18, joystick.knobY(), TOLERANCE);
        assertEquals(1, Math.hypot(joystick.forward(), joystick.strafe()), TOLERANCE,
                "full deflection is 1 in whatever direction it points");
    }

    @Test
    @DisplayName("inside the radius the knob follows the thumb exactly")
    void insideTheRadiusNothingIsClamped() {
        Joystick joystick = held(9, -12);        // 15 px out, comfortably inside 30

        assertEquals(9, joystick.knobX(), TOLERANCE);
        assertEquals(-12, joystick.knobY(), TOLERANCE);
        assertEquals(0.3, joystick.strafe(), TOLERANCE, "9 of 30 is three tenths");
        assertEquals(0.4, joystick.forward(), TOLERANCE);
    }

    @Test
    @DisplayName("the deadzone is a circle around the middle, and it zeroes both axes together")
    void theDeadzoneIsMeasuredOnTheDistanceNotOnEitherAxis() {
        // Two pixels out is a thumb resting, not a thumb pushing.
        Joystick resting = held(2, 0);
        assertEquals(0, resting.strafe(), TOLERANCE, "2 px of 30 is inside the deadzone");
        assertEquals(0, resting.forward(), TOLERANCE);
        assertEquals(2, resting.knobX(), TOLERANCE, "the KNOB still follows the thumb");

        // Well outside, and it passes through untouched; the deadzone subtracts nothing.
        Joystick pushing = held(6, 0);
        assertEquals(0.2, pushing.strafe(), TOLERANCE, "6 px of 30 is a fifth, not a fifth minus 0.12");

        // The case that separates a circular deadzone from a per-axis one, and the reason the rule
        // is written on the distance: 3 px right and 3 px forward is 4.24 px out, which is past the
        // 3.6 px deadzone even though NEITHER axis is. A per-axis test would sit still here.
        Joystick diagonal = held(3, -3);
        assertTrue(diagonal.forward() > 0, "a diagonal past the deadzone must move");
        assertTrue(diagonal.strafe() > 0);

        // And the other half: inside the circle, BOTH axes are zero, not just the smaller one.
        Joystick nearlyStill = held(2.5, -2.5);
        assertEquals(0, nearlyStill.forward(), TOLERANCE, "inside the circle, both axes are zero");
        assertEquals(0, nearlyStill.strafe(), TOLERANCE);
    }

    @Test
    @DisplayName("the stick belongs to one finger and forgets it on release")
    void itTracksItsOwnFingerAndOnlyItsOwn() {
        Joystick joystick = held(10, 0);

        assertTrue(joystick.owns(7), "the finger that grabbed it");
        assertFalse(joystick.owns(8), "a second finger elsewhere is not the stick's");

        joystick.release();

        assertFalse(joystick.isActive());
        assertFalse(joystick.owns(7), "a released stick belongs to nobody, not still to 7");
        assertEquals(0, joystick.knobX(), TOLERANCE, "the knob returns to the middle");
        assertEquals(0, joystick.knobY(), TOLERANCE);
        assertEquals(0, joystick.forward(), TOLERANCE, "and asks for no more movement");

        // A stick nobody holds owns no identifier at all, including 0, which is what the
        // identifier is reset to. Without the active guard in owns(), a real finger numbered 0
        // would find itself holding a stick it never touched.
        assertFalse(joystick.owns(0), "0 is a real touch identifier, not a spare 'none' value");
    }

    @Test
    @DisplayName("the thumb slides off the base and the stick keeps working")
    void theDragSurvivesLeavingTheBase() {
        Joystick joystick = held(0, -5);
        // 200 px away is nowhere near the 108 px base. Tracking by identifier rather than by
        // hit-testing is what makes this work, and in the original it is a deliberate fix.
        joystick.moveTo(0, -200);

        assertTrue(joystick.isActive(), "the stick is still held");
        assertEquals(1, joystick.forward(), TOLERANCE, "and still asking for full speed forward");
    }

    @Test
    @DisplayName("a thumb pushed forward walks the camera north at the touch speed")
    void theStickDrivesTheSameWalkTheKeyboardDoes() {
        Room room = new Room(20, 30, 8);
        CameraPose cam = new CameraPose(10, CameraPose.EYE_HEIGHT_FT, 15, 0, 0);
        Joystick joystick = held(0, -30);

        Walk.step(cam, joystick.forward(), joystick.strafe(), 0,
                Walk.TOUCH_SPEED_FT_PER_S, false, 1, room);

        assertEquals(15 - 6, cam.z, TOLERANCE,
                "one second of full forward on the stick is six feet north, not nine");
        assertEquals(10, cam.x, TOLERANCE);
        assertEquals(CameraPose.EYE_HEIGHT_FT, cam.y, TOLERANCE,
                "the stick has no up or down: the original reads vertical from the keys alone");
    }
}
