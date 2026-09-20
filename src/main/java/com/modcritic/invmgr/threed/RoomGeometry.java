package com.modcritic.invmgr.threed;

import com.modcritic.invmgr.model.Room;

/**
 * The room's five surfaces (a floor and four walls) as corner points ready for JavaFX.
 *
 * <p><b>There is no ceiling, and that is deliberate.</b> Looking up shows the void behind
 * everything, which is what the original does. It is not an oversight to be tidied up later: an
 * open top is what stops a small room feeling like a sealed box, and the specification calls it out.
 *
 * <p><b>These face inward, where an item's faces face outward.</b> You stand inside the room and
 * outside the boxes. That is the whole difference, and it is the half the OD-1 spike never had to
 * get right, because it switched face culling off entirely. With culling on, getting it backwards
 * means standing in a room with no walls, every one of them invisible from the only place you can
 * stand.
 *
 * <p>As with {@link BoxGeometry}, y and z are both negated on the way out. That pair is a half
 * turn rather than a mirror, so the corners go through in the order they are written and the
 * walls face the way they were wound. Read that class's note before changing either negation:
 * dropping one of them reflects the room east-to-west, which is invisible in an empty room and
 * shipped once.
 */
public final class RoomGeometry {

    /** How many surfaces a room has. Five: four walls and a floor, and no ceiling. */
    public static final int SURFACE_COUNT = 5;

    /** Index of the floor among {@link #surfacesOf}'s results. */
    public static final int FLOOR = 0;

    private RoomGeometry() {
    }

    /**
     * One surface: its four corners in JavaFX coordinates, and how big it is in feet so its
     * texture can be sized to match.
     */
    public static final class Surface {
        /** A name, used as the node's id so a test can find one and a pick can rule one out. */
        public final String name;

        /** Twelve numbers: four corners, three each, wound so the visible side faces the room. */
        public final float[] corners;

        /** How wide the surface is in feet, along its texture's horizontal axis. */
        public final double widthFt;

        /** How tall (or deep, for the floor) the surface is in feet. */
        public final double heightFt;

        /**
         * Whether this surface's texture runs backwards along its world axis compared with the
         * floor's.
         *
         * <p>True for the south and east walls, and it follows from the winding rather than being
         * a choice: a surface's texture x-axis is its first corner-to-corner edge, and a wall has
         * to be wound so its visible side faces into the room. The north wall's first edge
         * therefore runs west-to-east and the south wall's runs east-to-west, because they are
         * seen from opposite sides. The same for west and east.
         *
         * <p>It only matters when a room dimension is not a whole number of feet: see
         * {@link SurfaceMetrics#verticalLines()}, where it decides which end of the wall gets the
         * leftover part of a foot.
         */
        public final boolean mirroredGridX;

        Surface(String name, float[] corners, double widthFt, double heightFt,
                boolean mirroredGridX) {
            this.name = name;
            this.corners = corners;
            this.widthFt = widthFt;
            this.heightFt = heightFt;
            this.mirroredGridX = mirroredGridX;
        }
    }

    /**
     * The five surfaces for this room, floor first.
     *
     * <p>Each is wound counter-clockwise <em>seen from inside the room</em>, and stays that way
     * through the axis conversion.
     */
    public static Surface[] surfacesOf(Room room) {
        double w = room.w;
        double l = room.l;
        double h = room.h;

        return new Surface[] {
            // Floor, seen from above. Starting at the south-west corner rather than the north-west
            // one is not arbitrary: the first edge of a surface is the horizontal axis of its
            // texture, so it has to be the edge that is `w` feet long. Starting a corner earlier
            // makes the first edge the room's LENGTH, which lays a 12 x 10 picture across a 10 x 12
            // patch of floor and stretches the grid into 1.2 by 0.83 ft rectangles.
            //
            // Rotating the four corners like this keeps them in the same cycle, so the floor still
            // faces up; only where the cycle starts has moved.
            // The floor is the reference the four walls are measured against: its texture runs
            // west-to-east, so it is never the mirrored one.
            surface("room-floor", w, l, false,
                    0, 0, l,
                    w, 0, l,
                    w, 0, 0,
                    0, 0, 0),
            // North wall, at z = 0, seen from inside (that is, from the south). First edge runs
            // west-to-east, the same way as the floor's.
            surface("room-north", w, h, false,
                    0, 0, 0,
                    w, 0, 0,
                    w, h, 0,
                    0, h, 0),
            // South wall, at z = l, seen from the north. Seen from the other side, so its first
            // edge runs east-to-WEST, mirrored, and it is the room's WIDTH that decides whether
            // that shows.
            surface("room-south", w, h, true,
                    w, 0, l,
                    0, 0, l,
                    0, h, l,
                    w, h, l),
            // West wall, at x = 0, seen from the east. Its first edge runs south-to-north, which
            // is the direction the floor's second axis runs, so it agrees.
            surface("room-west", l, h, false,
                    0, 0, l,
                    0, 0, 0,
                    0, h, 0,
                    0, h, l),
            // East wall, at x = w, seen from the west. First edge runs north-to-south, mirrored,
            // and here it is the room's LENGTH that decides whether that shows.
            surface("room-east", l, h, true,
                    w, 0, 0,
                    w, 0, l,
                    w, h, l,
                    w, h, 0),
        };
    }

    /** The middle of the room, in the same coordinates {@link #surfacesOf} builds its corners in. */
    public static float[] centerOf(Room room) {
        return new float[] {(float) (room.w / 2), (float) (-room.h / 2), (float) (-room.l / 2)};
    }

    /**
     * The same four corners, moved {@code distanceFt} off the surface towards a point.
     *
     * <p>Used to float one layer of a surface just clear of another: since M6.7 a wall is a grid
     * quad with a vignette quad over it, and two quads in exactly the same plane fight over the
     * depth buffer instead of stacking.
     *
     * <p><b>Which way is off the surface comes from the corners themselves</b>, rather than from a
     * list of which surface is which. The cross product of two edges gives the plane's normal, and
     * pointing it at {@code target} settles the sign. A surface added later gets this right without
     * anyone remembering to add a case for it.
     *
     * <p>Lives here rather than in the renderer because it is arithmetic about a room, with no
     * JavaFX in it, which is also what lets {@code RoomGeometryTest} check it without a window and
     * what stops the probes keeping their own copy that quietly disagrees.
     *
     * @param corners twelve floats, four corners of three coordinates each
     * @param target the point to move towards, usually {@link #centerOf}
     */
    public static float[] liftedTowards(float[] corners, float[] target, double distanceFt) {
        float[] edge1 = {corners[3] - corners[0], corners[4] - corners[1], corners[5] - corners[2]};
        float[] edge2 = {corners[9] - corners[0], corners[10] - corners[1],
            corners[11] - corners[2]};
        float nx = edge1[1] * edge2[2] - edge1[2] * edge2[1];
        float ny = edge1[2] * edge2[0] - edge1[0] * edge2[2];
        float nz = edge1[0] * edge2[1] - edge1[1] * edge2[0];
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length == 0) {
            return corners.clone();
        }
        nx /= (float) length;
        ny /= (float) length;
        nz /= (float) length;
        if (nx * (target[0] - corners[0]) + ny * (target[1] - corners[1])
                + nz * (target[2] - corners[2]) < 0) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }

        float[] lifted = corners.clone();
        for (int i = 0; i < 4; i++) {
            lifted[i * 3] += nx * distanceFt;
            lifted[i * 3 + 1] += ny * distanceFt;
            lifted[i * 3 + 2] += nz * distanceFt;
        }
        return lifted;
    }

    private static Surface surface(String name, double widthFt, double heightFt,
            boolean mirroredGridX, double... spec) {
        // Negate y and z, and leave the corners in the order they were written. A half turn about
        // x does not change which side of a surface is the front, so nothing needs undoing.
        float[] corners = new float[12];
        for (int i = 0; i < 4; i++) {
            int from = i * 3;
            corners[i * 3] = (float) spec[from];
            corners[i * 3 + 1] = (float) -spec[from + 1];
            corners[i * 3 + 2] = (float) -spec[from + 2];
        }
        return new Surface(name, corners, widthFt, heightFt, mirroredGridX);
    }
}
