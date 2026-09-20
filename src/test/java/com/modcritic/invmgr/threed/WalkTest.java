package com.modcritic.invmgr.threed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.Room;
import com.modcritic.invmgr.threed.WalkInput.Control;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Walking and looking around the 3D room.
 *
 * <p><b>Every movement test asks which direction, not merely how far.</b> That is the lesson
 * CLAUDE.md §5.7 item 2 was bought with: M5.1's tests all aimed the camera at the thing they were
 * checking and asked whether it was on screen, so every one of them passed against a room that was
 * reflected east to west. A test that walks nine feet and checks it traveled nine feet cannot see
 * a sign error either. So each case here names the axis <em>and</em> the sign, and the ones that
 * could be satisfied by moving the wrong way are written to fail if it does.
 */
class WalkTest {

    private static final double TOLERANCE = 1e-9;

    /** A room big enough that nothing here hits the clamp unless it means to. */
    private static Room room() {
        return new Room(20, 30, 8);
    }

    /** A camera in the middle of that room, at eye height, facing north and level. */
    private static CameraPose middle() {
        return new CameraPose(10, CameraPose.EYE_HEIGHT_FT, 15, 0, 0);
    }

    // ------------------------------------------------------------------ moving

    @Test
    @DisplayName("facing north, forward for one second goes nine feet north and nowhere else")
    void forwardAtYawZeroGoesNorth() {
        CameraPose cam = middle();
        Walk.step(cam, 1, 0, 0, Walk.MOVE_SPEED_FT_PER_S, false, 1, room());

        // North is -z. Asserting the sign is the point: z + 9 is the same distance and the
        // wrong way, and "it moved nine feet" would accept both.
        assertEquals(15 - 9, cam.z, TOLERANCE, "forward at yaw 0 must decrease z");
        assertEquals(10, cam.x, TOLERANCE, "forward must not drift east or west");
        assertEquals(CameraPose.EYE_HEIGHT_FT, cam.y, TOLERANCE, "forward must not change height");
    }

    @Test
    @DisplayName("backward is the exact opposite of forward")
    void backwardIsTheOppositeOfForward() {
        CameraPose cam = middle();
        Walk.step(cam, -1, 0, 0, Walk.MOVE_SPEED_FT_PER_S, false, 1, room());
        assertEquals(15 + 9, cam.z, TOLERANCE);
        assertEquals(10, cam.x, TOLERANCE);
    }

    @Test
    @DisplayName("turned to face east, forward goes east instead: movement follows the look")
    void forwardFollowsTheYaw() {
        CameraPose cam = middle();
        cam.yaw = Math.PI / 2;      // a quarter turn clockwise from north is east
        Walk.step(cam, 1, 0, 0, Walk.MOVE_SPEED_FT_PER_S, false, 1, room());

        assertEquals(10 + 9, cam.x, TOLERANCE, "facing east, forward must increase x");
        assertEquals(15, cam.z, TOLERANCE, "facing east, forward must not change z");
    }

    @Test
    @DisplayName("facing north, strafing right goes east and strafing left goes west")
    void strafeGoesSidewaysRelativeToTheLook() {
        CameraPose right = middle();
        Walk.step(right, 0, 1, 0, Walk.MOVE_SPEED_FT_PER_S, false, 1, room());
        assertEquals(10 + 9, right.x, TOLERANCE, "strafe right at yaw 0 must increase x (east)");
        assertEquals(15, right.z, TOLERANCE);

        CameraPose left = middle();
        Walk.step(left, 0, -1, 0, Walk.MOVE_SPEED_FT_PER_S, false, 1, room());
        assertEquals(10 - 9, left.x, TOLERANCE, "strafe left at yaw 0 must decrease x (west)");
        assertEquals(15, left.z, TOLERANCE);
    }

    @Test
    @DisplayName("up is straight up even when the camera is looking at the floor")
    void verticalIsWorldUpAndNotViewRelative() {
        CameraPose cam = middle();
        cam.pitch = -1.5;       // very nearly straight down

        Walk.step(cam, 0, 0, 1, Walk.MOVE_SPEED_FT_PER_S, false, 1, room());

        // The trap this exists for: making vertical view-relative (the obvious "fix" when
        // someone assumes flying should follow the nose) would scale the rise by cos(pitch),
        // giving 0.64 ft rather than 9, and would also drag x or z along with it.
        assertEquals(CameraPose.EYE_HEIGHT_FT + 9, cam.y, TOLERANCE,
                "up must be the full nine feet regardless of pitch");
        assertEquals(10, cam.x, TOLERANCE, "up must not move the camera along the floor");
        assertEquals(15, cam.z, TOLERANCE, "up must not move the camera along the floor");
    }

    @Test
    @DisplayName("down is the opposite of up, and also ignores the pitch")
    void downIsWorldDown() {
        CameraPose cam = new CameraPose(10, 20, 15, 0, 1.5);   // looking nearly straight up
        Walk.step(cam, 0, 0, -1, Walk.MOVE_SPEED_FT_PER_S, false, 1, room());
        assertEquals(20 - 9, cam.y, TOLERANCE);
    }

    @Test
    @DisplayName("Shift doubles the distance covered, and doubles it exactly")
    void boostDoublesTheSpeed() {
        CameraPose plain = middle();
        Walk.step(plain, 1, 0, 0, Walk.MOVE_SPEED_FT_PER_S, false, 1, room());

        CameraPose boosted = middle();
        Walk.step(boosted, 1, 0, 0, Walk.MOVE_SPEED_FT_PER_S, true, 1, room());

        assertEquals(15 - 18, boosted.z, TOLERANCE, "boosted must cover 18 ft, not 9");

        // Bounded on both sides, per the lesson from M4's autosave test that passed on the
        // broken code as well as the working one: assert it is exactly twice, not merely more.
        assertEquals(2.0, (15 - boosted.z) / (15 - plain.z), TOLERANCE,
                "boost must be exactly BOOST_MULT, not just faster");
    }

    @Test
    @DisplayName("holding forward and back together stands still, and so does left and right")
    void oppositeControlsCancel() {
        WalkInput input = new WalkInput();
        input.set(Control.FORWARD, true);
        input.set(Control.BACK, true);
        assertEquals(0, input.forward(), TOLERANCE);

        input.set(Control.LEFT, true);
        input.set(Control.RIGHT, true);
        assertEquals(0, input.strafe(), TOLERANCE);

        input.set(Control.UP, true);
        input.set(Control.DOWN, true);
        assertEquals(0, input.vertical(), TOLERANCE);

        CameraPose cam = middle();
        Walk.step(cam, input.forward(), input.strafe(), input.vertical(),
                Walk.MOVE_SPEED_FT_PER_S, false, 1, room());
        assertEquals(10, cam.x, TOLERANCE);
        assertEquals(15, cam.z, TOLERANCE);
        assertEquals(CameraPose.EYE_HEIGHT_FT, cam.y, TOLERANCE);
    }

    @Test
    @DisplayName("a diagonal is faster than a straight line, faithfully and deliberately")
    void diagonalsAreNotNormalized() {
        CameraPose cam = middle();
        Walk.step(cam, 1, 1, 0, Walk.MOVE_SPEED_FT_PER_S, false, 1, room());

        double traveled = Math.hypot(cam.x - 10, cam.z - 15);

        // 12.73 ft, not 9. The original does not normalize the input vector and neither do we.
        // This test exists so that anyone "fixing" it into a unit vector trips instead of
        // quietly changing how the room feels to move around.
        assertEquals(Walk.MOVE_SPEED_FT_PER_S * Math.sqrt(2), traveled, TOLERANCE,
                "forward+right must cover 9*sqrt(2) ft: the original does not normalize");
    }

    // -------------------------------------------------------------- the clock

    @Test
    @DisplayName("a stutter cannot teleport you: half a second moves no further than a fiftieth")
    void theFrameLengthIsCapped() {
        long start = 1_000_000_000L;

        double half = Walk.frameSeconds(start + 500_000_000L, start);
        double fiftieth = Walk.frameSeconds(start + 50_000_000L, start);
        double short20ms = Walk.frameSeconds(start + 20_000_000L, start);

        assertEquals(Walk.MAX_FRAME_SECONDS, half, TOLERANCE, "500 ms must be capped at 50 ms");
        assertEquals(Walk.MAX_FRAME_SECONDS, fiftieth, TOLERANCE, "50 ms is exactly the cap");
        assertEquals(0.02, short20ms, TOLERANCE, "anything under the cap passes through");

        // And the consequence, which is the thing that actually matters: the same movement over
        // a stuttered frame and a capped frame covers the same ground.
        CameraPose stuttered = middle();
        Walk.step(stuttered, 1, 0, 0, Walk.MOVE_SPEED_FT_PER_S, false, half, room());
        CameraPose normal = middle();
        Walk.step(normal, 1, 0, 0, Walk.MOVE_SPEED_FT_PER_S, false, fiftieth, room());
        assertEquals(normal.z, stuttered.z, TOLERANCE);
    }

    @Test
    @DisplayName("the first frame of a run moves nothing at all")
    void theFirstFrameHasNoPreviousOne() {
        // lastNanos == 0 means "there is no previous frame". Without this the first frame would
        // be handed the whole time since the machine started and the cap would be the only thing
        // between you and the far wall.
        assertEquals(0, Walk.frameSeconds(9_999_999_999L, 0), TOLERANCE);

        // A clock that goes backwards must not walk backwards.
        assertEquals(0, Walk.frameSeconds(1_000L, 2_000L), TOLERANCE);
    }

    // ------------------------------------------------------------- the clamp

    @Test
    @DisplayName("you can walk out through a wall, and are stopped sixty feet past it")
    void theCameraLeavesTheRoomAndStopsAtTheSlack() {
        Room room = room();
        CameraPose cam = middle();
        cam.yaw = -Math.PI / 2;                 // facing west

        // One second at nine feet a second, twenty times: 180 ft west from x = 10.
        for (int i = 0; i < 20; i++) {
            Walk.step(cam, 1, 0, 0, Walk.MOVE_SPEED_FT_PER_S, false, 1, room);
        }
        assertEquals(-CameraPose.ROOM_SLACK_FT, cam.x, TOLERANCE,
                "west travel must stop at the slack, not at the wall");

        // The other half of the same rule, and the half a clamp test usually forgets: partway
        // out is genuinely outside the room and is NOT pulled back to the wall.
        CameraPose escaping = middle();
        escaping.yaw = -Math.PI / 2;
        Walk.step(escaping, 1, 0, 0, Walk.MOVE_SPEED_FT_PER_S, false, 2, room);
        assertEquals(10 - 18, escaping.x, TOLERANCE, "-8 ft is outside the room and allowed");
        assertTrue(escaping.x < 0, "the camera really is west of the west wall");
    }

    @Test
    @DisplayName("height stops just above the floor and well above any ceiling")
    void heightIsClampedAtBothEnds() {
        Room room = room();

        CameraPose sinking = middle();
        for (int i = 0; i < 10; i++) {
            Walk.step(sinking, 0, 0, -1, Walk.MOVE_SPEED_FT_PER_S, false, 1, room);
        }
        assertEquals(CameraPose.MIN_Y_FT, sinking.y, TOLERANCE);

        CameraPose rising = middle();
        for (int i = 0; i < 60; i++) {
            Walk.step(rising, 0, 0, 1, Walk.MOVE_SPEED_FT_PER_S, false, 1, room);
        }
        assertEquals(CameraPose.MAX_Y_FT, rising.y, TOLERANCE);

        // The ceiling is 8 ft and the camera passed straight through it on the way to 300.
        // The room has no ceiling surface at all (see RoomGeometry), so this is correct.
        assertTrue(CameraPose.MAX_Y_FT > room.h, "the camera is not confined by the room height");
    }

    @Test
    @DisplayName("z is clamped against the room's own length, not a fixed number")
    void theClampFollowsTheRoomSize() {
        Room small = new Room(4, 5, 8);
        CameraPose cam = new CameraPose(2, 6, 2.5, 0, 0);
        for (int i = 0; i < 20; i++) {
            Walk.step(cam, -1, 0, 0, Walk.MOVE_SPEED_FT_PER_S, false, 1, small);
        }
        assertEquals(5 + CameraPose.ROOM_SLACK_FT, cam.z, TOLERANCE);
    }

    // ------------------------------------------------------------------ looking

    @Test
    @DisplayName("a hundred pixels right turns the camera by exactly a hundred times the sensitivity")
    void yawFollowsHorizontalMovement() {
        CameraPose cam = middle();
        Walk.look(cam, 100, 0, Walk.MOUSE_SENS_RAD_PER_PX);

        assertEquals(0.22, cam.yaw, TOLERANCE, "100 px at 0.0022 rad/px is 0.22 rad");
        assertEquals(0, cam.pitch, TOLERANCE, "a horizontal movement must not change the pitch");

        // And the sign: turning right must go clockwise from north, i.e. toward east.
        assertTrue(cam.yaw > 0, "moving the mouse right must increase yaw");
    }

    @Test
    @DisplayName("moving the mouse down looks down")
    void pitchIsInverted() {
        CameraPose cam = middle();
        Walk.look(cam, 0, 100, Walk.MOUSE_SENS_RAD_PER_PX);

        // Pointer y counts downward, and pitch subtracts it, so a downward drag lowers the view.
        // One minus sign, and it is the easiest thing in this class to get backwards.
        assertEquals(-0.22, cam.pitch, TOLERANCE, "mouse down must decrease pitch");
        assertEquals(0, cam.yaw, TOLERANCE);

        CameraPose up = middle();
        Walk.look(up, 0, -100, Walk.MOUSE_SENS_RAD_PER_PX);
        assertEquals(0.22, up.pitch, TOLERANCE, "mouse up must increase pitch");
    }

    @Test
    @DisplayName("dragging turns 1.4 times as far as a captured pointer does")
    void theDragFallbackIsMoreSensitive() {
        CameraPose captured = middle();
        Walk.look(captured, 100, 0, Walk.MOUSE_SENS_RAD_PER_PX);

        CameraPose dragged = middle();
        Walk.look(dragged, 100, 0, Walk.MOUSE_SENS_RAD_PER_PX * Walk.DRAG_LOOK_MULT);

        assertEquals(Walk.DRAG_LOOK_MULT, dragged.yaw / captured.yaw, TOLERANCE,
                "the drag path must be exactly 1.4x, not merely faster");
        assertEquals(0.308, dragged.yaw, 1e-12, "0.0022 * 1.4 * 100 px");
    }

    @Test
    @DisplayName("looking up or down stops just short of vertical and does not flip over")
    void pitchIsClampedRatherThanWrapped() {
        CameraPose cam = middle();
        for (int i = 0; i < 20; i++) {
            Walk.look(cam, 0, -1000, Walk.MOUSE_SENS_RAD_PER_PX);
        }
        assertEquals(CameraPose.PITCH_LIMIT_RAD, cam.pitch, TOLERANCE);

        // Wrapping instead of clamping would put the camera upside down here, which is the
        // failure this rules out: at exactly +-90 degrees the yaw axis and the view direction
        // line up and turning sideways spins the whole view.
        assertTrue(cam.pitch < Math.PI / 2, "the pitch must stop short of straight up");

        CameraPose down = middle();
        for (int i = 0; i < 20; i++) {
            Walk.look(down, 0, 1000, Walk.MOUSE_SENS_RAD_PER_PX);
        }
        assertEquals(-CameraPose.PITCH_LIMIT_RAD, down.pitch, TOLERANCE);
    }

    @Test
    @DisplayName("yaw keeps accumulating past a full turn instead of being wrapped here")
    void yawIsNotNormalizedByLooking() {
        CameraPose cam = middle();
        for (int i = 0; i < 40; i++) {
            Walk.look(cam, 100, 0, Walk.MOUSE_SENS_RAD_PER_PX);
        }
        // 40 * 0.22 = 8.8 rad, which is past 2*pi. Left unwrapped on purpose: sine and cosine
        // do not care, and Transitions.ascent is the one place that normalizes, so that it can
        // take the short way round when leaving. Normalizing here too would do that job twice.
        assertEquals(8.8, cam.yaw, 1e-9);
        assertTrue(cam.yaw > 2 * Math.PI, "yaw must not be wrapped by look()");
    }

    // ---------------------------------------------------- panning and dollying
    //
    // The two-finger gestures. Pan is the original's (lines 3186-3195); the dolly is CLAUDE.md
    // §5.5 D-15 and is new behavior, so its rules are pinned here rather than differentially.

    @Test
    @DisplayName("facing north, panning right slides east and up rises, with no turn")
    void panSlidesSidewaysAndVertically() {
        CameraPose cam = middle();
        Walk.pan(cam, 3, 2, room());

        assertEquals(10 + 3, cam.x, TOLERANCE, "pan right at yaw 0 must increase x (east)");
        assertEquals(15, cam.z, TOLERANCE, "a sideways pan must not move you north or south");
        assertEquals(CameraPose.EYE_HEIGHT_FT + 2, cam.y, TOLERANCE, "positive up must rise");
        assertEquals(0, cam.yaw, TOLERANCE, "panning must not turn the camera");
        assertEquals(0, cam.pitch, TOLERANCE);
    }

    @Test
    @DisplayName("panning follows the yaw: facing east, panning right slides south")
    void panFollowsTheYaw() {
        CameraPose cam = middle();
        cam.yaw = Math.PI / 2;      // facing east

        Walk.pan(cam, 4, 0, room());

        // Right of east is south, and south is +z. Asserting the axis and the sign is the point:
        // "it moved four feet" would accept north, and a pan along the FORWARD vector instead of
        // the right one is the mistake that produces exactly that.
        assertEquals(15 + 4, cam.z, TOLERANCE, "facing east, right is south");
        assertEquals(10, cam.x, TOLERANCE, "facing east, a sideways pan must not change x");
    }

    @Test
    @DisplayName("panning up is straight up even when the camera is looking at the floor")
    void panVerticalIgnoresThePitch() {
        CameraPose cam = middle();
        cam.pitch = -1.5;           // very nearly straight down

        Walk.pan(cam, 0, 5, room());

        assertEquals(CameraPose.EYE_HEIGHT_FT + 5, cam.y, TOLERANCE,
                "up must be the full five feet regardless of pitch");
        assertEquals(10, cam.x, TOLERANCE, "rising must not drag the camera along the floor");
        assertEquals(15, cam.z, TOLERANCE);
    }

    @Test
    @DisplayName("a pan is stopped by the same walls walking is")
    void panIsClamped() {
        Room room = room();
        CameraPose cam = middle();

        Walk.pan(cam, -500, 0, room);
        assertEquals(-CameraPose.ROOM_SLACK_FT, cam.x, TOLERANCE,
                "panning west must stop at the slack, not carry on forever");

        CameraPose sinking = middle();
        Walk.pan(sinking, 0, -500, room);
        assertEquals(CameraPose.MIN_Y_FT, sinking.y, TOLERANCE);
    }

    @Test
    @DisplayName("spreading two fingers moves you forward and squeezing them moves you back")
    void dollyGoesForwardAndBack() {
        CameraPose forward = middle();
        Walk.dolly(forward, 6, room());
        assertEquals(15 - 6, forward.z, TOLERANCE, "a positive dolly at yaw 0 must go north");
        assertEquals(10, forward.x, TOLERANCE, "a dolly must not drift east or west");

        CameraPose back = middle();
        Walk.dolly(back, -6, room());
        assertEquals(15 + 6, back.z, TOLERANCE, "a negative dolly is the exact opposite");
    }

    @Test
    @DisplayName("the dolly goes where you are facing, the same way W does")
    void dollyFollowsTheYaw() {
        CameraPose dollied = middle();
        dollied.yaw = Math.PI / 2;              // facing east
        Walk.dolly(dollied, 9, room());

        // Bound to walking rather than to a copy of the arithmetic: one second of W at 9 ft/s must
        // land in exactly the same place. That is what "one idea of forward, shared with the
        // keyboard and the joystick" means, and it is D-15's stated reason for flat forward.
        CameraPose walked = middle();
        walked.yaw = Math.PI / 2;
        Walk.step(walked, 1, 0, 0, Walk.MOVE_SPEED_FT_PER_S, false, 1, room());

        assertEquals(walked.x, dollied.x, TOLERANCE, "a 9 ft dolly must land where 9 ft of W does");
        assertEquals(walked.z, dollied.z, TOLERANCE);
        assertEquals(10 + 9, dollied.x, TOLERANCE, "facing east, forward increases x");
    }

    @Test
    @DisplayName("looking at the ceiling and spreading does not lift you off the floor")
    void dollyIgnoresThePitchEntirely() {
        CameraPose cam = middle();
        cam.pitch = 1.5;            // very nearly straight up

        Walk.dolly(cam, 10, room());

        // This is the whole of D-15's second sentence and the user's own choice between two
        // options. Flying along the aim point (the obvious alternative, and what "dolly" means
        // in most cameras) would give y + 9.97 and z - 0.71 here. Both halves are asserted, so
        // neither can be quietly reintroduced.
        assertEquals(CameraPose.EYE_HEIGHT_FT, cam.y, TOLERANCE,
                "a dolly must never change the camera's height");
        assertEquals(15 - 10, cam.z, TOLERANCE,
                "and it must cover the full ten feet flat, not ten feet along the nose");
    }

    @Test
    @DisplayName("a dolly is stopped by the same walls walking is")
    void dollyIsClamped() {
        CameraPose cam = middle();
        Walk.dolly(cam, 500, room());
        assertEquals(-CameraPose.ROOM_SLACK_FT, cam.z, TOLERANCE,
                "north travel must stop at the slack");
    }

    @Test
    @DisplayName("panning and dollying compose: the pan is kept and the pinch adds to it")
    void panAndDollyCompose() {
        CameraPose cam = middle();
        cam.yaw = Math.PI / 2;                  // facing east: forward is +x, right is +z

        Walk.pan(cam, 3, 0, room());
        Walk.dolly(cam, 4, room());

        // D-15 keeps the original's pan and adds the dolly on top of the same gesture, so the two
        // must be independent: 3 ft right and 4 ft forward, not one instead of the other.
        assertEquals(15 + 3, cam.z, TOLERANCE, "the pan's 3 ft south survives the dolly");
        assertEquals(10 + 4, cam.x, TOLERANCE, "the dolly's 4 ft east survives the pan");
    }

    @Test
    @DisplayName("forward and right are one pair of directions, not three copies of them")
    void thereIsOnlyOneForwardVector() {
        // At yaw 0: forward is north, which is (0, -1); right is east, which is (1, 0).
        assertEquals(0, Walk.forwardX(0), TOLERANCE);
        assertEquals(-1, Walk.forwardZ(0), TOLERANCE, "north is -z");
        assertEquals(1, Walk.rightX(0), TOLERANCE, "east is +x");
        assertEquals(0, Walk.rightZ(0), TOLERANCE);

        // Perpendicular at every angle, which is the property that would break first if one of
        // the four ever picked up a stray sign.
        for (double yaw = -3; yaw <= 3; yaw += 0.37) {
            assertEquals(0, Walk.forwardX(yaw) * Walk.rightX(yaw) + Walk.forwardZ(yaw) * Walk.rightZ(yaw),
                    1e-12, "forward and right must stay at a right angle at yaw " + yaw);
            assertEquals(1, Math.hypot(Walk.forwardX(yaw), Walk.forwardZ(yaw)), 1e-12,
                    "forward must stay a unit vector at yaw " + yaw);
        }

        // And right is forward turned a quarter clockwise, which is what makes strafing right
        // agree with walking forward after a quarter turn.
        double yaw = 0.9;
        assertEquals(Walk.forwardX(yaw + Math.PI / 2), Walk.rightX(yaw), 1e-12);
        assertEquals(Walk.forwardZ(yaw + Math.PI / 2), Walk.rightZ(yaw), 1e-12);
    }

    // -------------------------------------------------------------- key state

    @Test
    @DisplayName("only the first press of a held key reports as a change")
    void theKeyStateReportsEdgesNotRepeats() {
        WalkInput input = new WalkInput();

        assertTrue(input.set(Control.FORWARD, true), "the first press is an edge");
        assertFalse(input.set(Control.FORWARD, true), "an auto-repeat is not");
        assertFalse(input.set(Control.FORWARD, true), "still not");
        assertTrue(input.set(Control.FORWARD, false), "letting go is an edge");
        assertFalse(input.set(Control.FORWARD, false), "a second key-up is not");

        // This is what D-11's "a fresh press lands the transition, an auto-repeat does not"
        // is built on, so it is a rule rather than an implementation detail.
    }

    @Test
    @DisplayName("each control alone reports the sign the movement math expects")
    void eachAxisPointsTheRightWayOnItsOwn() {
        // Found by the mutation sweep: reversing strafe() and vertical() left everything above
        // green, because the only tests touching them held BOTH keys, and a canceled pair
        // cancels whichever way round the axis is. Holding one key is the question that can see
        // it, and it is the same shape of blind spot as M5.1's "is the box on screen".
        assertEquals(1, single(Control.FORWARD).forward(), TOLERANCE, "forward alone is +1");
        assertEquals(-1, single(Control.BACK).forward(), TOLERANCE, "back alone is -1");
        assertEquals(1, single(Control.RIGHT).strafe(), TOLERANCE, "right alone is +1");
        assertEquals(-1, single(Control.LEFT).strafe(), TOLERANCE, "left alone is -1");
        assertEquals(1, single(Control.UP).vertical(), TOLERANCE, "up alone is +1");
        assertEquals(-1, single(Control.DOWN).vertical(), TOLERANCE, "down alone is -1");

        // And the signs mean what the rest of this class says they mean: +1 strafe goes east at
        // yaw 0, +1 vertical rises. Without this the two halves could be consistently backwards.
        CameraPose cam = middle();
        WalkInput input = single(Control.RIGHT);
        Walk.step(cam, input.forward(), input.strafe(), input.vertical(),
                Walk.MOVE_SPEED_FT_PER_S, false, 1, room());
        assertTrue(cam.x > 10, "the right key must actually take the camera east");

        CameraPose up = middle();
        WalkInput rising = single(Control.UP);
        Walk.step(up, rising.forward(), rising.strafe(), rising.vertical(),
                Walk.MOVE_SPEED_FT_PER_S, false, 1, room());
        assertTrue(up.y > CameraPose.EYE_HEIGHT_FT, "the up key must actually rise");
    }

    private static WalkInput single(Control control) {
        WalkInput input = new WalkInput();
        input.set(control, true);
        return input;
    }

    @Test
    @DisplayName("clearing forgets every held key, which is what a lost window needs")
    void clearingForgetsEverything() {
        WalkInput input = new WalkInput();
        input.set(Control.FORWARD, true);
        input.set(Control.RIGHT, true);
        input.set(Control.BOOST, true);
        assertFalse(input.isIdle());
        assertTrue(input.isBoosting());

        input.clear();

        assertTrue(input.isIdle(), "nothing may still be held after a clear");
        assertFalse(input.isBoosting());
        assertEquals(0, input.forward(), TOLERANCE);
        assertEquals(0, input.strafe(), TOLERANCE);
    }

    @Test
    @DisplayName("holding two keys that cancel is standing still, but it is not idle")
    void cancelingIsNotIdle() {
        WalkInput input = new WalkInput();
        input.set(Control.FORWARD, true);
        input.set(Control.BACK, true);

        assertEquals(0, input.forward(), TOLERANCE, "the movement cancels");
        assertFalse(input.isIdle(), "but two keys are still down");

        // The difference matters: letting go of one of them starts you moving without any new
        // key going down, so a loop that stopped itself on "no movement" would not restart.
    }
}
