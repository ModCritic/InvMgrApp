package com.modcritic.invmgr.threed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.Room;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The room's five surfaces, and the thing that is easy to get exactly backwards: they must face
 * <em>into</em> the room, because that is the only side you can ever stand on.
 */
class RoomGeometryTest {

    private static final double TOLERANCE = 1e-6;

    private static final Room ROOM = new Room(12, 10, 8);

    @Test
    @DisplayName("there are five surfaces and none of them is a ceiling")
    void thereIsNoCeiling() {
        RoomGeometry.Surface[] surfaces = RoomGeometry.surfacesOf(ROOM);
        assertEquals(RoomGeometry.SURFACE_COUNT, surfaces.length,
                "SURFACE_COUNT and what surfacesOf actually returns have drifted apart");

        // In JavaFX coordinates the floor is at y = 0 and the top of the room is at y = -8. A
        // ceiling would be four corners all sitting at -8. Nothing may.
        for (RoomGeometry.Surface s : surfaces) {
            boolean allAtCeiling = true;
            for (int i = 1; i < s.corners.length; i += 3) {
                if (Math.abs(s.corners[i] + ROOM.h) > TOLERANCE) {
                    allAtCeiling = false;
                    break;
                }
            }
            assertTrue(!allAtCeiling, s.name + " is a ceiling, and the room must not have one");
        }

        assertEquals("room-floor", surfaces[RoomGeometry.FLOOR].name);
        assertTrue(Arrays.stream(surfaces).anyMatch(s -> s.name.equals("room-north")));
        assertTrue(Arrays.stream(surfaces).anyMatch(s -> s.name.equals("room-east")));
    }

    @Test
    @DisplayName("every surface faces into the room, where you are standing")
    void everySurfaceFacesInward() {
        // The same arithmetic as the box's winding test, with the sense reversed: a wall's normal
        // must point TOWARD the middle of the room, not away from it. Get this backwards and you
        // stand in a room with no walls, because each one is only visible from outside.
        RoomGeometry.Surface[] surfaces = RoomGeometry.surfacesOf(ROOM);
        double[] middle = {ROOM.w / 2, -ROOM.h / 2, -ROOM.l / 2};

        for (RoomGeometry.Surface s : surfaces) {
            double[] p0 = corner(s, 0);
            double[] p1 = corner(s, 1);
            double[] p2 = corner(s, 2);
            double[] n = cross(sub(p1, p0), sub(p2, p0));

            double[] inward = sub(middle, p0);
            double agreement = dot(n, inward);
            assertTrue(agreement > TOLERANCE,
                    s.name + " faces away from the room (agreement " + agreement + ")");
        }
    }

    @Test
    @DisplayName("reversing a wall would be caught, so the inward check is not vacuous")
    void theInwardCheckWouldFail() {
        RoomGeometry.Surface floor = RoomGeometry.surfacesOf(ROOM)[RoomGeometry.FLOOR];
        double[] middle = {ROOM.w / 2, -ROOM.h / 2, -ROOM.l / 2};
        double[] p0 = corner(floor, 0);
        double[] p1 = corner(floor, 1);
        double[] p2 = corner(floor, 2);

        double[] asBuilt = cross(sub(p1, p0), sub(p2, p0));
        double[] reversed = cross(sub(p2, p0), sub(p1, p0));
        double[] inward = sub(middle, p0);

        assertTrue(dot(asBuilt, inward) > 0, "as built, the floor faces up into the room");
        assertTrue(dot(reversed, inward) < 0, "reversed, it would face down into the ground");
    }

    @Test
    @DisplayName("a box's faces and a room's surfaces face opposite ways")
    void theRoomAndTheBoxesDisagreeOnPurpose() {
        // Stated as its own test because it is the single fact that makes the two winding rules
        // different, and someone unifying them "for consistency" would break the room.
        RoomGeometry.Surface floor = RoomGeometry.surfacesOf(ROOM)[RoomGeometry.FLOOR];
        double[] floorNormal = cross(sub(corner(floor, 1), corner(floor, 0)),
                sub(corner(floor, 2), corner(floor, 0)));

        // The floor's visible side points up. In JavaFX's downward y, up is negative.
        assertTrue(floorNormal[1] < 0, "the floor must be visible from above, got " + floorNormal[1]);
    }

    @Test
    @DisplayName("each surface knows its own size in feet, so its texture can match")
    void theSurfaceSizesAreRight() {
        RoomGeometry.Surface[] surfaces = RoomGeometry.surfacesOf(ROOM);
        RoomGeometry.Surface floor = surfaces[RoomGeometry.FLOOR];
        assertEquals(12, floor.widthFt, TOLERANCE, "the floor is as wide as the room");
        assertEquals(10, floor.heightFt, TOLERANCE, "and as deep as it is long");

        RoomGeometry.Surface north = find(surfaces, "room-north");
        assertEquals(12, north.widthFt, TOLERANCE);
        assertEquals(8, north.heightFt, TOLERANCE, "a wall's second dimension is the ceiling");

        RoomGeometry.Surface east = find(surfaces, "room-east");
        assertEquals(10, east.widthFt, TOLERANCE, "the side walls run the room's length");
        assertEquals(8, east.heightFt, TOLERANCE);
    }

    @Test
    @DisplayName("a surface's texture is laid on the way round its own size says it is")
    void theTextureAxesFollowTheStatedSize() {
        // The corners are handed to JavaFX with texture coordinates (0,0), (1,0), (1,1), (0,1) in
        // that order, so the first edge is the picture's width and the second is its height. If a
        // surface reports 12 x 10 and its first edge is the 10 ft one, the picture is laid across
        // the surface turned ninety degrees, and because it is stretched to fit either way, the
        // result is not a rotated grid but a grid of the wrong SHAPE: 1.2 ft one way and 0.83 ft
        // the other, on a floor whose squares are supposed to be a foot.
        //
        // The floor was exactly that, in the M5.1 jar. It is invisible in a square room, which is
        // both why it survived and why this test uses a room that is 12 by 10 by 8, three
        // different numbers, so nothing can agree by coincidence.
        for (RoomGeometry.Surface s : RoomGeometry.surfacesOf(ROOM)) {
            assertEquals(s.widthFt, distance(corner(s, 0), corner(s, 1)), TOLERANCE,
                    s.name + ": the first edge must be the width its texture is drawn at");
            assertEquals(s.heightFt, distance(corner(s, 1), corner(s, 2)), TOLERANCE,
                    s.name + ": the second edge must be the height its texture is drawn at");
        }
    }

    @Test
    @DisplayName("the walls meet the floor and the corners line up")
    void theRoomIsClosedAtTheBottom() {
        // Every wall must have two corners on the floor and two at the ceiling. A wall that
        // floated would leave a gap you could see the void through.
        for (RoomGeometry.Surface s : RoomGeometry.surfacesOf(ROOM)) {
            if (s.name.equals("room-floor")) {
                continue;
            }
            int onFloor = 0;
            int atCeiling = 0;
            for (int i = 1; i < s.corners.length; i += 3) {
                if (Math.abs(s.corners[i]) < TOLERANCE) {
                    onFloor++;
                } else if (Math.abs(s.corners[i] + ROOM.h) < TOLERANCE) {
                    atCeiling++;
                }
            }
            assertEquals(2, onFloor, s.name + " should have two corners on the floor");
            assertEquals(2, atCeiling, s.name + " should reach the ceiling with two corners");
        }
    }

    private static RoomGeometry.Surface find(RoomGeometry.Surface[] surfaces, String name) {
        return Arrays.stream(surfaces).filter(s -> s.name.equals(name)).findFirst().orElseThrow();
    }

    private static double distance(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0])
                + (a[1] - b[1]) * (a[1] - b[1])
                + (a[2] - b[2]) * (a[2] - b[2]));
    }

    private static double[] corner(RoomGeometry.Surface s, int index) {
        return new double[] {s.corners[index * 3], s.corners[index * 3 + 1],
            s.corners[index * 3 + 2]};
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        };
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    @Test
    @DisplayName("a lifted layer moves into the room, by exactly the distance asked for")
    void theLiftGoesInwardsAndNotOutwards() {
        // Since M6.7 every surface is two quads, a grid with a vignette floating just off it, and
        // this is what decides which way "off it" is. Get the sign wrong and the vignette ends up
        // on the far side of the wall, where it is invisible and where nothing else would notice:
        // the room would simply stop being darkened at the edges and still pass every pixel test
        // that samples the middle of a wall.
        Room room = new Room(12, 10, 8);
        float[] center = RoomGeometry.centerOf(room);

        for (RoomGeometry.Surface s : RoomGeometry.surfacesOf(room)) {
            float[] lifted = RoomGeometry.liftedTowards(s.corners, center, 0.25);

            for (int i = 0; i < 4; i++) {
                double before = distance(s.corners, i, center);
                double after = distance(lifted, i, center);
                assertTrue(after < before, s.name + " corner " + i
                        + " moved away from the middle of the room, not towards it");
                assertEquals(0.25, moved(s.corners, lifted, i), 1e-4,
                        s.name + " corner " + i + " moved the wrong distance");
            }

            // And it stays flat: all four corners move the same way, or the quad would be bent.
            for (int i = 1; i < 4; i++) {
                for (int axis = 0; axis < 3; axis++) {
                    assertEquals(lifted[axis] - s.corners[axis],
                            lifted[i * 3 + axis] - s.corners[i * 3 + axis], 1e-4,
                            s.name + " was bent rather than moved");
                }
            }
        }
    }

    @Test
    @DisplayName("the room's middle is inside the room")
    void theCenterIsWhereItSays() {
        // Trivial to write and not trivial to get right: the corners are built with y and z
        // NEGATED (see surface()), so a middle worked out in plain room coordinates would sit
        // outside the room and send every lift the wrong way.
        Room room = new Room(12, 10, 8);
        float[] center = RoomGeometry.centerOf(room);
        assertEquals(6, center[0], 1e-6);
        assertEquals(-4, center[1], 1e-6, "half the height, negated, like the corners");
        assertEquals(-5, center[2], 1e-6, "half the length, negated, like the corners");
    }

    private static double distance(float[] corners, int corner, float[] point) {
        return Math.sqrt(Math.pow(corners[corner * 3] - point[0], 2)
                + Math.pow(corners[corner * 3 + 1] - point[1], 2)
                + Math.pow(corners[corner * 3 + 2] - point[2], 2));
    }

    private static double moved(float[] from, float[] to, int corner) {
        return Math.sqrt(Math.pow(to[corner * 3] - from[corner * 3], 2)
                + Math.pow(to[corner * 3 + 1] - from[corner * 3 + 1], 2)
                + Math.pow(to[corner * 3 + 2] - from[corner * 3 + 2], 2));
    }
}
