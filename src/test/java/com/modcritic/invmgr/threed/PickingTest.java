package com.modcritic.invmgr.threed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Working out which box is under a point on screen.
 *
 * <p><b>The important cases here are the ones that ask which side of the screen something is on.</b>
 * A reflection leaves the middle of the screen exactly where it was, so a test that puts a box
 * straight ahead and checks the middle of the screen finds it will pass against a world mirrored
 * east to west, which is precisely how M5.1 shipped one. Every case below that could be satisfied
 * by picking in the wrong place says where it expects the hit <em>and</em> asserts a miss at the
 * mirror-image point.
 *
 * <p>The window used throughout is 1000 × 800, which makes the arithmetic checkable by hand: the
 * perspective distance is 600 px, so the vertical half-angle has a tangent of exactly 2/3 and the
 * aspect ratio is exactly 1.25.
 */
class PickingTest {

    private static final double WIDTH = 1000;
    private static final double HEIGHT = 800;

    /** In the middle of a room, at eye height, facing north and level. */
    private static CameraPose camera() {
        return new CameraPose(10, 6, 15, 0, 0);
    }

    private static Box3D box(String id, double x0, double x1, double y0, double y1,
            double z0, double z1) {
        return new Box3D(id, "hsl(0,55%,42%)", x0, x1, y0, y1, z0, z1);
    }

    private static String pickAt(double xPx, double yPx, CameraPose camera, List<Box3D> boxes) {
        return Picking.nearestHit(boxes, camera,
                Picking.rayThrough(xPx, yPx, WIDTH, HEIGHT, camera));
    }

    // -------------------------------------------------------------- the ray

    @Test
    @DisplayName("the middle of the screen looks exactly where the camera is pointing")
    void theCenterRayIsTheViewDirection() {
        double[] ray = Picking.rayThrough(WIDTH / 2, HEIGHT / 2, WIDTH, HEIGHT, camera());

        // Facing north at yaw 0 is (0, 0, -1). Named as a direction, not just a magnitude.
        assertEquals(0, ray[0], 1e-12, "no sideways component at the center");
        assertEquals(0, ray[1], 1e-12, "no vertical component at the center");
        assertEquals(-1, ray[2], 1e-12, "the center ray must point north");
    }

    @Test
    @DisplayName("every ray is a unit vector, so a hit distance is really in feet")
    void raysAreNormalized() {
        for (double[] point : new double[][] {{0, 0}, {WIDTH, 0}, {WIDTH / 2, HEIGHT},
                {WIDTH * 0.3, HEIGHT * 0.8}}) {
            double[] ray = Picking.rayThrough(point[0], point[1], WIDTH, HEIGHT, camera());
            assertEquals(1, Math.hypot(Math.hypot(ray[0], ray[1]), ray[2]), 1e-12);
        }
    }

    @Test
    @DisplayName("the screen edges sit at exactly the angles the window shape says they should")
    void theFrustumIsAsWideAndTallAsTheWindow() {
        // Found by the mutation sweep: inverting the aspect ratio, and dropping it entirely, both
        // left every box test green. A box is a solid several feet deep, so a ray that leaves the
        // camera at the wrong angle still lands somewhere inside it; the geometry absorbed the
        // error. Only asking the ray its angle directly can see this.
        //
        // tan(half the vertical field of view) is exactly 2/3 at 800 px tall, and the aspect ratio
        // is exactly 1.25, so the right edge of the screen is at 1.25 * 2/3 = 5/6 sideways for
        // every 1 forward, and the top edge is at 2/3 up for every 1 forward.
        double[] right = Picking.rayThrough(WIDTH, HEIGHT / 2, WIDTH, HEIGHT, camera());
        assertEquals(5.0 / 6.0, right[0] / -right[2], 1e-12,
                "the right edge must be aspect x tan(fov/2) sideways per unit forward");
        assertEquals(0, right[1], 1e-12);

        double[] top = Picking.rayThrough(WIDTH / 2, 0, WIDTH, HEIGHT, camera());
        assertEquals(2.0 / 3.0, top[1] / -top[2], 1e-12,
                "the top edge must be tan(fov/2) up per unit forward: no aspect on this one");
        assertEquals(0, top[0], 1e-12);

        // And the two are genuinely different numbers, which is what makes the assertions above
        // capable of telling the horizontal and vertical apart at all.
        assertTrue(Math.abs(5.0 / 6.0 - 2.0 / 3.0) > 0.1, "a square window would prove nothing");
    }

    @Test
    @DisplayName("the field of view is measured off the window's height, not its width")
    void theFieldOfViewComesFromTheHeight() {
        // This looks like it should be covered by the case above and is not, which is why it has
        // its own. Perspective takes its distance as a fixed 0.75 of whatever it is given, so
        // tan(half the angle) is (d/2) / (d * 0.75) = 2/3 for ANY d; the width and the height
        // produce the identical angle, and swapping them changes nothing at all. The sweep
        // reported it as a survivor and was right to.
        //
        // They only diverge below the 300 px floor Perspective ports. No real window is 300 px
        // tall, but the rule "this angle comes from the height" is real, and this is the one
        // window shape that can see it: from the height, tan is 150/300 = 0.5; from the width it
        // would be 500/750 = 2/3.
        double shortHeight = 300;
        double[] top = Picking.rayThrough(WIDTH / 2, 0, WIDTH, shortHeight, camera());

        assertEquals(0.5, top[1] / -top[2], 1e-12,
                "at 300 px tall the half-angle's tangent is 0.5, which only the height gives");
    }

    @Test
    @DisplayName("turning the camera turns the rays with it")
    void theRayFollowsTheCameraAngles() {
        CameraPose east = camera();
        east.yaw = Math.PI / 2;
        double[] ray = Picking.rayThrough(WIDTH / 2, HEIGHT / 2, WIDTH, HEIGHT, east);
        assertEquals(1, ray[0], 1e-12, "facing east, the center ray points east");
        assertEquals(0, ray[2], 1e-12);

        CameraPose down = camera();
        down.pitch = -Math.PI / 2;
        double[] straightDown = Picking.rayThrough(WIDTH / 2, HEIGHT / 2, WIDTH, HEIGHT, down);
        assertEquals(-1, straightDown[1], 1e-12, "pitched fully down, the center ray points down");
    }

    // ------------------------------------------------------------- the hits

    @Test
    @DisplayName("a box straight ahead is picked from the middle of the screen")
    void aBoxAheadIsPickedAtTheCenter() {
        List<Box3D> boxes = List.of(box("ahead", 9, 11, 5, 7, 4, 6));
        assertEquals("ahead", pickAt(WIDTH / 2, HEIGHT / 2, camera(), boxes));
    }

    @Test
    @DisplayName("a box to the east is picked on the RIGHT of the screen and missed on the left")
    void aBoxToTheEastIsPickedOnTheRight() {
        // The camera faces north, so east is on the right. The box is centered 6 ft east and 10 ft
        // north of the camera, which is 31 degrees off the view direction, inside the horizontal
        // half-angle of 39.8 degrees, so it really is on screen.
        List<Box3D> boxes = List.of(box("east", 14, 18, 4, 8, 3, 7));

        assertEquals("east", pickAt(860, HEIGHT / 2, camera(), boxes),
                "a box to the east must be picked on the right-hand side");

        // The mirror image. This is the assertion the whole test exists for: reflect the world
        // east to west and the first line above still passes, because the box would then be on
        // the left and the left is where a reflected ray at x=860 would look.
        assertNull(pickAt(140, HEIGHT / 2, camera(), boxes),
                "the same box must NOT be found on the left-hand side");
    }

    @Test
    @DisplayName("a box to the west is picked on the left, which is the other half of the same rule")
    void aBoxToTheWestIsPickedOnTheLeft() {
        List<Box3D> boxes = List.of(box("west", 2, 6, 4, 8, 3, 7));
        assertEquals("west", pickAt(140, HEIGHT / 2, camera(), boxes));
        assertNull(pickAt(860, HEIGHT / 2, camera(), boxes));
    }

    @Test
    @DisplayName("a box above eye level is picked in the TOP half of the screen")
    void aBoxAboveIsPickedNearTheTop() {
        // Pointer y counts downward, so the top of the screen is y = 0. A box overhead must be
        // found there and not at the bottom: the vertical equivalent of the mirror test above.
        List<Box3D> boxes = List.of(box("high", 9, 11, 11.9, 12.1, 5, 7));

        assertEquals("high", pickAt(WIDTH / 2, 0, camera(), boxes),
                "a box overhead must be picked at the top of the screen");
        assertNull(pickAt(WIDTH / 2, HEIGHT, camera(), boxes),
                "and must not be picked at the bottom");
    }

    @Test
    @DisplayName("the view really is 67 degrees tall: one foot higher and the box is off screen")
    void theFieldOfViewIsVerticalAndIsTheOriginals() {
        // At the top edge of the screen the ray rises 2/3 of a foot for every foot it travels
        // north, because tan(half the vertical field of view) is exactly 2/3 at this window size.
        // From eye height 6, at the box's distance, that puts the topmost visible point at about
        // 12 ft. A box straddling 12 is on screen; a box at 13 is not.
        List<Box3D> justInside = List.of(box("inside", 9, 11, 11.9, 12.1, 5, 7));
        List<Box3D> justOutside = List.of(box("outside", 9, 11, 13, 13.2, 5, 7));

        assertNotNull(pickAt(WIDTH / 2, 0, camera(), justInside));
        assertNull(pickAt(WIDTH / 2, 0, camera(), justOutside),
                "a box above the top of the frustum must not be pickable at the top of the screen");
    }

    @Test
    @DisplayName("looking down at 45 degrees picks the floor ahead, not the floor underfoot")
    void aPitchedCameraPicksWhereItIsLooking() {
        CameraPose pitched = new CameraPose(10, 10, 15, 0, -Math.PI / 4);

        // Down at 45 degrees from 10 ft up lands about 10 ft north: z = 5, not z = 15.
        List<Box3D> ahead = List.of(box("ahead", 8, 12, 0, 2, 2, 8));
        List<Box3D> underfoot = List.of(box("underfoot", 8, 12, 0, 2, 13, 17));

        assertEquals("ahead", pickAt(WIDTH / 2, HEIGHT / 2, pitched, ahead));
        assertNull(pickAt(WIDTH / 2, HEIGHT / 2, pitched, underfoot),
                "what is directly below must not be picked when looking forward and down");
    }

    // ------------------------------------------------------------ the misses

    @Test
    @DisplayName("pointing at nothing picks nothing")
    void emptySpacePicksNothing() {
        List<Box3D> boxes = List.of(box("far-east", 60, 64, 4, 8, 3, 7));
        assertNull(pickAt(WIDTH / 2, HEIGHT / 2, camera(), boxes));
        assertNull(pickAt(WIDTH / 2, HEIGHT / 2, camera(), List.of()), "and an empty room too");
    }

    @Test
    @DisplayName("a box behind you is never picked, however you point")
    void nothingBehindTheCameraIsPicked() {
        // The camera faces north; this box is south of it. The ray's LINE passes through the box;
        // dropping the "must be in front" rule makes this pass, and it would show up in the app
        // as a tooltip for something you cannot see.
        List<Box3D> behind = List.of(box("behind", 9, 11, 5, 7, 20, 22));

        assertNull(pickAt(WIDTH / 2, HEIGHT / 2, camera(), behind));
        assertNull(pickAt(0, 0, camera(), behind));
        assertNull(pickAt(WIDTH, HEIGHT, camera(), behind));
    }

    @Test
    @DisplayName("a box beside the ray is missed even though it is in front")
    void aBoxOffToTheSideIsMissed() {
        List<Box3D> boxes = List.of(box("beside", 20, 22, 5, 7, 4, 6));
        assertNull(pickAt(WIDTH / 2, HEIGHT / 2, camera(), boxes));
    }

    // ------------------------------------------------------------- occlusion

    @Test
    @DisplayName("the nearer of two boxes in line wins, whichever order they are listed in")
    void theNearestHitWins() {
        Box3D near = box("near", 9, 11, 5, 7, 10, 12);
        Box3D far = box("far", 9, 11, 5, 7, 4, 6);

        assertEquals("near", pickAt(WIDTH / 2, HEIGHT / 2, camera(), Arrays.asList(near, far)));
        assertEquals("near", pickAt(WIDTH / 2, HEIGHT / 2, camera(), Arrays.asList(far, near)),
                "list order must not decide which box is in front");
    }

    @Test
    @DisplayName("standing inside a box picks that box, at distance nothing")
    void aBoxYouAreInsideWins() {
        // Falls out of the same rule rather than being a special case: the ray starts inside, so
        // the box begins at distance zero and beats anything else in the room.
        Box3D around = box("around", 8, 12, 4, 8, 13, 17);
        Box3D ahead = box("ahead", 9, 11, 5, 7, 4, 6);

        assertEquals("around", pickAt(WIDTH / 2, HEIGHT / 2, camera(),
                Arrays.asList(ahead, around)));

        // And the distance really is zero, which the line above cannot see. The sweep proved that:
        // letting the distance go negative from inside a box still picks the same winner, so the
        // id told us nothing. This test used to claim "at distance nothing" in its name and check
        // no such thing.
        double[] ray = Picking.rayThrough(WIDTH / 2, HEIGHT / 2, WIDTH, HEIGHT, camera());
        assertEquals(0, Picking.distanceTo(around, camera(), ray), 1e-12,
                "a box you are inside starts at zero, never behind you");
    }

    @Test
    @DisplayName("the distance to a hit is in real feet")
    void theHitDistanceIsMeasuredInFeet() {
        // The camera is at z = 15 facing north; the box's near face is at z = 6. That is nine
        // feet, and it is nine because the ray is a unit vector; if it were not, this number
        // would be nine divided by whatever length the ray happened to have.
        Box3D ahead = box("ahead", 9, 11, 5, 7, 4, 6);
        double[] ray = Picking.rayThrough(WIDTH / 2, HEIGHT / 2, WIDTH, HEIGHT, camera());

        assertEquals(9, Picking.distanceTo(ahead, camera(), ray), 1e-12);

        Box3D behind = box("behind", 9, 11, 5, 7, 20, 22);
        assertEquals(Double.POSITIVE_INFINITY, Picking.distanceTo(behind, camera(), ray),
                "a miss is infinitely far, so it can never win a comparison");
    }

    @Test
    @DisplayName("a ray running exactly along a face does not become a phantom hit")
    void anAxisAlignedRayIsHandledWithoutArithmeticAccidents() {
        // The camera faces north, so the ray's x and y components are exactly zero. Dividing by
        // them would give infinities, or a NaN where the camera sits exactly on a face's plane,
        // and one NaN makes every comparison false, which reads as a hit rather than a miss.
        CameraPose onThePlane = new CameraPose(9, 5, 15, 0, 0);
        List<Box3D> boxes = List.of(box("ahead", 9, 11, 5, 7, 4, 6));

        assertEquals("ahead", pickAt(WIDTH / 2, HEIGHT / 2, onThePlane, boxes),
                "sitting level with a face is still a hit, not a NaN");

        CameraPose besideThePlane = new CameraPose(8.99, 4.99, 15, 0, 0);
        assertNull(pickAt(WIDTH / 2, HEIGHT / 2, besideThePlane, boxes),
                "a hair outside the same face is a clean miss");
    }

    @Test
    @DisplayName("the picked id is the item's own, so a tooltip can look the item up")
    void theAnswerIsTheItemId() {
        Box3D box = box("item-42", 9, 11, 5, 7, 4, 6);
        assertEquals("item-42", pickAt(WIDTH / 2, HEIGHT / 2, camera(), List.of(box)));
        assertTrue(box.itemId.equals("item-42"), "and it came from the box, not from the list index");
    }
}
