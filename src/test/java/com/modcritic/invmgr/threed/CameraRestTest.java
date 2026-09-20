package com.modcritic.invmgr.threed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.Room;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Noticing the moment the camera stops, and saying where it stopped.
 *
 * <p>No window and no captured output: {@link CameraRest} returns a boolean and a string rather
 * than printing, so both halves are ordinary values to assert on. That is the same reason
 * {@code VerboseTest} gives for splitting its own work up.
 *
 * <p><b>The rule being pinned is "once per stop".</b> A line on every frame would be worse than
 * no line at all, since walking for a second would bury the answer under sixty copies of it.
 */
class CameraRestTest {

    private static CameraPose at(double x, double y, double z, double yaw, double pitch) {
        return new CameraPose(x, y, z, yaw, pitch);
    }

    @Test
    @DisplayName("the pose the camera lands in is reported, one frame after it lands")
    void theLandingPoseIsReported() {
        CameraRest rest = new CameraRest();
        CameraPose camera = at(6, 6, 5, 0, 0);

        assertFalse(rest.update(camera), "the first frame is movement, not rest");
        assertTrue(rest.update(camera), "the frame after arriving is the rest");
    }

    @Test
    @DisplayName("standing still reports once and then says nothing")
    void standingStillReportsOnce() {
        CameraRest rest = new CameraRest();
        CameraPose camera = at(6, 6, 5, 0, 0);
        rest.update(camera);
        assertTrue(rest.update(camera), "the first still frame is the rest");

        for (int frame = 0; frame < 120; frame++) {
            assertFalse(rest.update(camera),
                    "a camera nobody is touching must not report a second time, or two seconds "
                            + "of standing still buries the line under sixty copies");
        }
    }

    @Test
    @DisplayName("a frame that moves reports nothing, and the stop after it does")
    void movementIsSilentUntilItStops() {
        CameraRest rest = new CameraRest();
        CameraPose camera = at(6, 6, 5, 0, 0);
        rest.update(camera);
        rest.update(camera);                       // came to rest at the start

        for (int frame = 0; frame < 30; frame++) {
            camera.x += 0.1;
            assertFalse(rest.update(camera), "a moving camera must stay quiet");
        }
        assertTrue(rest.update(camera), "the frame the movement stops is the one that reports");
        assertFalse(rest.update(camera), "and only that one");
    }

    @Test
    @DisplayName("a turn on the spot counts as movement, the same as walking does")
    void turningCountsAsMovement() {
        CameraRest rest = new CameraRest();
        CameraPose camera = at(6, 6, 5, 0, 0);
        rest.update(camera);
        rest.update(camera);

        camera.yaw = 1.2;
        assertFalse(rest.update(camera), "a drag that only turns the head is still movement");
        assertTrue(rest.update(camera), "and letting go of it is a rest");

        camera.pitch = -0.4;
        assertFalse(rest.update(camera), "so is a tilt");
        assertTrue(rest.update(camera));
    }

    @Test
    @DisplayName("walking with nothing held does not read as movement")
    void clampingAloneIsNotMovement() {
        // Walk.step does nothing unless a control is held, but it always clamps. If clamping
        // rewrote the numbers, every frame would look like movement and the line would never
        // arrive. This asserts the property CameraRest's exact comparison depends on.
        CameraRest rest = new CameraRest();
        Room room = new Room();
        CameraPose camera = at(6, 6, 5, 0, 0);
        rest.update(camera);
        rest.update(camera);

        for (int frame = 0; frame < 60; frame++) {
            Walk.step(camera, 0, 0, 0, Walk.MOVE_SPEED_FT_PER_S, false, 1.0 / 60, room);
            assertFalse(rest.update(camera),
                    "standing still must be bit for bit identical, or exact comparison is wrong");
        }
    }

    @Test
    @DisplayName("reopening the view reports the pose it lands in again")
    void clearingMakesTheNextLandingReport() {
        CameraRest rest = new CameraRest();
        CameraPose camera = at(6, 6, 5, 0, 0);
        rest.update(camera);
        assertTrue(rest.update(camera));

        rest.clear();
        assertFalse(rest.update(camera), "after a clear the first frame is movement again");
        assertTrue(rest.update(camera),
                "reopening onto the same pose must still report, or the landing goes unlogged");
    }

    @Test
    @DisplayName("the line says where the camera is and which way it faces")
    void theLineReadsAsAPlaceAndAHeading() {
        CameraRest rest = new CameraRest();
        CameraPose camera = at(6, 6, 5, 0, 0);
        rest.update(camera);
        rest.update(camera);

        assertEquals("camera at rest: 6.0 E, 5.0 S, 6.0 up (ft), facing N (yaw 0), level",
                rest.line());
    }

    @Test
    @DisplayName("a pitched view says which way it is tilted, in words")
    void theLineSaysUpOrDownRatherThanASign() {
        CameraRest rest = new CameraRest();
        CameraPose camera = at(1.5, 6, 2.25, Math.PI, -Math.toRadians(53));
        rest.update(camera);
        rest.update(camera);

        assertEquals("camera at rest: 1.5 E, 2.3 S, 6.0 up (ft), facing S (yaw 180), "
                + "looking down 53", rest.line());
    }

    @Test
    @DisplayName("yaw 0 is north and a positive turn goes toward east")
    void theCompassFollowsTheSpecsAxes() {
        // SPEC-3D-VIEW.md §1, restated in CameraPose's own class documentation. Getting this
        // backwards would make every line actively misleading, which is worse than no line.
        assertEquals("N", CameraRest.compass(0));
        assertEquals("E", CameraRest.compass(Math.toRadians(90)));
        assertEquals("S", CameraRest.compass(Math.toRadians(180)));
        assertEquals("W", CameraRest.compass(Math.toRadians(270)));
        assertEquals("NE", CameraRest.compass(Math.toRadians(45)));
    }

    @Test
    @DisplayName("a heading is never printed as a negative number or past a full turn")
    void headingsAreFoldedIntoOneCircle() {
        assertEquals("W", CameraRest.compass(Math.toRadians(-90)),
                "turning left from north three times is west");
        assertEquals(270, CameraRest.degreesInACircle(Math.toRadians(-90)), 1e-9);
        assertEquals(90, CameraRest.degreesInACircle(Math.toRadians(450)), 1e-9,
                "a camera turned round more than once still reports a plain heading");
    }

    @Test
    @DisplayName("almost level reads as level rather than as a distracting zero")
    void almostLevelIsLevel() {
        assertEquals("level", CameraRest.tilt(0));
        assertEquals("level", CameraRest.tilt(Math.toRadians(0.2)));
        assertEquals("looking up 12", CameraRest.tilt(Math.toRadians(12)));
        assertEquals("looking down 12", CameraRest.tilt(Math.toRadians(-12)));
    }
}
