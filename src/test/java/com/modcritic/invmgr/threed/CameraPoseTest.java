package com.modcritic.invmgr.threed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.Room;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The camera's own rules: where it may go, how far it may look up and down, and how an angle is
 * wrapped before it is animated.
 *
 * <p>All of it is arithmetic, so all of it runs with no window, the same reason
 * {@code TiltPendulumTest} can test the drag ghost's swing without drawing one.
 */
class CameraPoseTest {

    private static final double TOLERANCE = 1e-12;

    private static Room room(double w, double l, double h) {
        return new Room(w, l, h);
    }

    @Test
    @DisplayName("walking out through a wall is allowed, up to 60 ft past it")
    void theCameraMayLeaveTheRoom() {
        // This is a feature, not an oversight, and the original says so in a comment (line 2865).
        // You are meant to be able to step outside and look back in. A test that only checked
        // "the camera stays in the room" would be pinning the opposite of the intended
        // behavior, so this one deliberately walks out and asserts it got there.
        Room r = room(12, 10, 8);
        CameraPose cam = new CameraPose(-30, 6, -30, 0, 0);
        cam.clampPosition(r);
        assertEquals(-30, cam.x, TOLERANCE, "30 ft west of the room is well within the slack");
        assertEquals(-30, cam.z, TOLERANCE);

        // And the slack has an edge. Bounded on both sides so a mutation that removes the clamp
        // entirely fails, and so does one that tightens it to the room's own walls.
        cam = new CameraPose(-500, 6, 500, 0, 0);
        cam.clampPosition(r);
        assertEquals(-CameraPose.ROOM_SLACK_FT, cam.x, TOLERANCE);
        assertEquals(r.l + CameraPose.ROOM_SLACK_FT, cam.z, TOLERANCE);
        assertTrue(cam.x < 0, "the clamp must not pull the camera back inside the room");
    }

    @Test
    @DisplayName("the camera cannot go through the floor or off into space")
    void theHeightIsBounded() {
        Room r = room(12, 10, 8);

        CameraPose low = new CameraPose(6, -100, 5, 0, 0);
        low.clampPosition(r);
        assertEquals(CameraPose.MIN_Y_FT, low.y, TOLERANCE);
        assertTrue(low.y > 0, "stopping at 0 would put the eye exactly in the floor plane");

        CameraPose high = new CameraPose(6, 10_000, 5, 0, 0);
        high.clampPosition(r);
        assertEquals(CameraPose.MAX_Y_FT, high.y, TOLERANCE);
        // Bounded below as well: the ceiling must clear the tallest legal room's overhead view,
        // or entering 3D in a 200x200 room would fight its own clamp.
        assertTrue(CameraPose.MAX_Y_FT > 225,
                "the height ceiling must be above the overhead height of the largest legal room");
    }

    @Test
    @DisplayName("looking up or down stops just short of straight, avoiding the pole")
    void thePitchStopsShortOfVertical() {
        CameraPose cam = new CameraPose();
        cam.pitch = -Math.PI;
        cam.clampPitch();
        assertEquals(-CameraPose.PITCH_LIMIT_RAD, cam.pitch, TOLERANCE);

        cam.pitch = Math.PI;
        cam.clampPitch();
        assertEquals(CameraPose.PITCH_LIMIT_RAD, cam.pitch, TOLERANCE);

        // The point of 1.55 rather than π/2 is that it is SHORT of vertical. Bounded both ways:
        // close enough that you can effectively look straight down, far enough that the yaw axis
        // and the view direction never line up.
        assertTrue(CameraPose.PITCH_LIMIT_RAD < Math.PI / 2,
                "the limit must be short of straight down, or the view spins at the pole");
        assertTrue(CameraPose.PITCH_LIMIT_RAD > 1.5,
                "the limit must still let you look very nearly straight down");
    }

    @Test
    @DisplayName("a yaw is wrapped so the camera turns the short way round")
    void yawWrapsToTheNearHalf() {
        // The reason this exists: the exit animation interpolates from wherever you were looking
        // back to north. Turning from 170° to -170° is 20° the near way and 340° the far way,
        // and without wrapping the camera spins almost all the way round to arrive in the same
        // place. Easy to miss, very obvious once seen.
        assertEquals(0, CameraPose.normYaw(0), TOLERANCE);
        assertEquals(0, CameraPose.normYaw(2 * Math.PI), TOLERANCE);
        assertEquals(0, CameraPose.normYaw(-2 * Math.PI), TOLERANCE);
        assertEquals(Math.PI / 2, CameraPose.normYaw(Math.PI / 2 + 4 * Math.PI), TOLERANCE);

        // Just past half a turn comes back as a small NEGATIVE angle, not a large positive one.
        double justPast = CameraPose.normYaw(Math.PI + 0.1);
        assertTrue(justPast < 0, "just past half a turn should wrap to the negative side");
        assertEquals(-(Math.PI - 0.1), justPast, TOLERANCE);

        // Everything lands inside -π..π, whatever it started as.
        for (double a = -20; a <= 20; a += 0.37) {
            double wrapped = CameraPose.normYaw(a);
            assertTrue(wrapped >= -Math.PI - TOLERANCE && wrapped <= Math.PI + TOLERANCE,
                    a + " wrapped to " + wrapped + ", outside -π..π");
        }
    }

    @Test
    @DisplayName("a copy is independent, and set() overwrites in place")
    void copyingAndSettingBehave() {
        // The render loop moves one pose sixty times a second and the exit animation needs the
        // pose it started from, so these two are load-bearing rather than convenience.
        CameraPose original = new CameraPose(1, 2, 3, 0.4, 0.5);
        CameraPose copy = original.copy();
        original.x = 99;
        assertEquals(1, copy.x, TOLERANCE, "a copy must not follow the original");

        copy.set(original);
        assertEquals(99, copy.x, TOLERANCE);
        assertEquals(0.5, copy.pitch, TOLERANCE);
    }

    @Test
    @DisplayName("the default pose is at eye height, facing north and level")
    void theDefaultPoseIsTheOriginals() {
        CameraPose cam = new CameraPose();
        assertEquals(CameraPose.EYE_HEIGHT_FT, cam.y, TOLERANCE);
        assertEquals(6, cam.y, TOLERANCE, "the original's walking height is 6 ft");
        assertEquals(0, cam.yaw, TOLERANCE, "yaw 0 faces north");
        assertEquals(0, cam.pitch, TOLERANCE, "pitch 0 is level");
    }
}
