package com.modcritic.invmgr.threed;

import com.modcritic.invmgr.model.Units;

/**
 * Builds the raw numbers for one item's box: where its corners are, which triangles join them, and
 * which color each triangle takes. No JavaFX: these are plain arrays, so the geometry can be
 * checked in a test with no window and no graphics card.
 *
 * <p><b>Each face is five flat pieces, not one.</b> A box in the original has a darker line around
 * the edge of every face, and that line is a CSS {@code border: 2px solid} on the face itself
 * (original line 2521). Under {@code box-sizing: border-box} the border sits <em>inside</em> the
 * face, and the face's own pixel space is 96 px to the foot, so the line is a frame inset by
 * exactly 2/96 of a foot, drawn in the world rather than on the screen. It gets thicker as you
 * walk up to a box and thinner as you back away, which is what a real border under a perspective
 * transform does.
 *
 * <p>That last point is the one that decides how to build it. §5.7 of the ground rules guessed the
 * answer would be to bake the outline into each face's texture; reading the original shows that
 * would be solving the wrong problem, because a baked line has to be redrawn per face at a real
 * pixels-per-foot resolution and costs roughly a megabyte per item. Making the frame out of
 * <em>geometry</em> instead costs sixty triangles, is exact at every distance for free, and cannot
 * shimmer, which matters on a phone, where antialiasing is refused outright.
 *
 * <p>So every face is a 4×4 grid of points carved into five quads: a center in the face's own
 * shade, and four strips around it in the edge shade. They are coplanar and do not overlap, so
 * there is no depth fighting and no offset to tune.
 *
 * <p><b>Y and Z are both negated, and the pair of them is the whole conversion.</b> The
 * specification has y pointing up and z pointing south; JavaFX has y pointing down and its camera
 * looking along +z when it has not been turned. Negating both axes is a half turn about x, which
 * settles all three questions at once: the world is the right way up, yaw 0 looks north with no
 * correction applied to the camera, and (because a half turn is a rotation rather than a
 * reflection) the triangles keep facing the way they were wound. Culling stays on.
 *
 * <p><b>Negating y alone is the trap, and this code fell into it.</b> Flipping one axis mirrors
 * the world. That reverses every triangle, which is visible and was duly corrected; what it also
 * does is reverse east and west, which is not visible at all in an empty room and cannot be
 * undone by pointing the camera somewhere else: no rigid movement of a camera un-mirrors a
 * mirrored world. The M5.1 build turned the camera half a turn to make it face north again, which
 * looked right and left the room reflected. The user found it in a minute by putting a box
 * against a wall and noticing it was on the wrong side.
 */
public final class BoxGeometry {

    /**
     * How far the edge frame reaches in from a face's border, in feet.
     *
     * <p>2 pixels at the app's 96 pixels to the foot, a quarter of an inch. Not a number chosen
     * for looks: it is the original's CSS border width, converted.
     */
    public static final double EDGE_INSET_FT = 2.0 / Units.PX_PER_FOOT;

    /** Points per face: a 4×4 grid, giving the outer edge and the frame's inner edge. */
    public static final int POINTS_PER_FACE = 16;

    /** Faces on a box. Six: there is no seventh, and none is ever omitted. */
    public static final int FACE_COUNT = 6;

    /** Quads per face: a center plus four frame strips. */
    public static final int QUADS_PER_FACE = 5;

    /** Which block of the color strip a surface samples. */
    public static final int BLOCK_TOP = 0;
    public static final int BLOCK_NORTH_SOUTH = 1;
    public static final int BLOCK_EAST_WEST = 2;
    public static final int BLOCK_BOTTOM = 3;
    public static final int BLOCK_EDGE = 4;

    /** How many color blocks the strip texture holds. */
    public static final int BLOCK_COUNT = 5;

    /**
     * The five quads of a face, as corners of the 4×4 grid, wound counter-clockwise seen from
     * outside the box. Column then row; column 0 and 3 are the face's own edges, 1 and 2 the
     * frame's inner edge.
     */
    private static final int[][] QUADS = {
        {0, 0, 3, 0, 3, 1, 0, 1},   // the strip along the near edge
        {0, 2, 3, 2, 3, 3, 0, 3},   // the strip along the far edge
        {0, 1, 1, 1, 1, 2, 0, 2},   // the strip down one side
        {2, 1, 3, 1, 3, 2, 2, 2},   // the strip down the other
        {1, 1, 2, 1, 2, 2, 1, 2},   // the middle, which is the only part in the face's own shade
    };

    /** The center quad is the only one that is not the edge color. */
    private static final int CENTER_QUAD = 4;

    private BoxGeometry() {
    }

    /**
     * The 96 corner points of one box (16 per face) as x, y, z triples in feet, <b>already
     * converted into JavaFX's axes</b>.
     */
    public static float[] points(Box3D box) {
        float[] points = new float[FACE_COUNT * POINTS_PER_FACE * 3];
        int at = 0;
        for (Face face : facesOf(box)) {
            double insetAlongA = insetFraction(face.lengthA);
            double insetAlongB = insetFraction(face.lengthB);
            double[] sAlongA = {0, insetAlongA, 1 - insetAlongA, 1};
            double[] sAlongB = {0, insetAlongB, 1 - insetAlongB, 1};

            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < 4; col++) {
                    double s = sAlongA[col];
                    double t = sAlongB[row];
                    points[at++] = (float) (face.ox + face.ax * s + face.bx * t);
                    // The one place the specification's axes become JavaFX's. Both negations, or
                    // neither: y alone leaves the room reflected east-to-west. See the class note.
                    points[at++] = (float) -(face.oy + face.ay * s + face.by * t);
                    points[at++] = (float) -(face.oz + face.az * s + face.bz * t);
                }
            }
        }
        return points;
    }

    /**
     * The triangles, as JavaFX wants them: point index and texture index for each of three
     * corners, two triangles per quad.
     */
    public static int[] faces(Box3D box) {
        Face[] faces = facesOf(box);
        int[] out = new int[FACE_COUNT * QUADS_PER_FACE * 2 * 6];
        int at = 0;

        for (int f = 0; f < FACE_COUNT; f++) {
            int base = f * POINTS_PER_FACE;
            for (int q = 0; q < QUADS_PER_FACE; q++) {
                int[] corners = QUADS[q];
                int block = q == CENTER_QUAD ? faces[f].block : BLOCK_EDGE;

                int p0 = base + corners[1] * 4 + corners[0];
                int p1 = base + corners[3] * 4 + corners[2];
                int p2 = base + corners[5] * 4 + corners[4];
                int p3 = base + corners[7] * 4 + corners[6];

                // Wound counter-clockwise from outside in the specification's coordinates, and
                // left that way. Negating y and z together is a rotation, not a reflection, so it
                // carries the winding through untouched, which is what lets back-face culling
                // stay switched on with no correction to argue about.
                at = triangle(out, at, p0, p1, p2, block);
                at = triangle(out, at, p0, p2, p3, block);
            }
        }
        return out;
    }

    /**
     * The texture coordinates: one per color block, each landing dead in the middle of its block
     * so that all three corners of a triangle sample the same texel.
     *
     * <p>That is what makes a face perfectly flat rather than subtly graded. Every corner reads the
     * same color, so there is nothing for the graphics card to blend between, which is how a
     * renderer with no lighting still produces the app's flat-shaded look.
     */
    public static float[] texCoords() {
        return texCoords(0, 1);
    }

    /**
     * The same coordinates, pointed at one row of a picture holding several items' colors.
     *
     * <p>Added at M6.7b. Every box used to get a picture of its own, and a machine with no graphics
     * card charges about 1.55 ms for the first draw of each one however small it is: a 500 item room
     * stalled for 780 ms on the frame it first became visible. One picture with a row per color
     * costs that once, so the row has to be selectable from here.
     *
     * <p><b>The middle of the row, for the same reason as the middle of the block.</b> A row is
     * {@code FaceStrip.BLOCK_PX} pixels tall, so its center falls exactly between the two middle
     * texels of that row and a card blending them reads the same color twice. Nothing bleeds in
     * from the row above or below. With one row this is 0.5, which is what it has always been, so
     * a single-color picture is sampled exactly where it always was.
     *
     * @param row which item's row to read, counting from the top
     * @param rowCount how many rows the picture has
     */
    public static float[] texCoords(int row, int rowCount) {
        float[] coords = new float[BLOCK_COUNT * 2];
        for (int i = 0; i < BLOCK_COUNT; i++) {
            coords[i * 2] = (i + 0.5f) / BLOCK_COUNT;
            coords[i * 2 + 1] = (row + 0.5f) / rowCount;
        }
        return coords;
    }

    /**
     * How far in the frame reaches, as a fraction of this side's length.
     *
     * <p><b>Capped at half.</b> An item may legally be one inch across, which is thinner than two
     * frames, and without the cap the inner edge would cross over the outer one and turn the face
     * inside out. At exactly a half the middle collapses to nothing and the four strips meet, so
     * the face comes out entirely in the edge color, which is what a browser does when a border
     * is wider than the box it is on, so it is the faithful answer as well as the safe one.
     */
    static double insetFraction(double sideLengthFt) {
        if (sideLengthFt <= 0) {
            return 0.5;
        }
        return Math.min(0.5, EDGE_INSET_FT / sideLengthFt);
    }

    /**
     * The six faces, each as a corner and two edge vectors, ordered so that walking the corner,
     * then along the first vector, then the second, goes counter-clockwise seen from <em>outside</em>
     * the box.
     */
    static Face[] facesOf(Box3D box) {
        double w = box.width();
        double h = box.height();
        double l = box.length();
        double x0 = box.x0;
        double y0 = box.y0;
        double z0 = box.z0;

        return new Face[] {
            // top, facing up
            new Face(x0, box.y1, z0, 0, 0, l, w, 0, 0, l, w, BLOCK_TOP),
            // bottom, facing down
            new Face(x0, y0, z0, w, 0, 0, 0, 0, l, w, l, BLOCK_BOTTOM),
            // north, facing away from the room's south wall
            new Face(x0, y0, z0, 0, h, 0, w, 0, 0, h, w, BLOCK_NORTH_SOUTH),
            // south
            new Face(x0, y0, box.z1, w, 0, 0, 0, h, 0, w, h, BLOCK_NORTH_SOUTH),
            // east
            new Face(box.x1, y0, z0, 0, h, 0, 0, 0, l, h, l, BLOCK_EAST_WEST),
            // west
            new Face(x0, y0, z0, 0, 0, l, 0, h, 0, l, h, BLOCK_EAST_WEST),
        };
    }

    private static int triangle(int[] out, int at, int a, int b, int c, int block) {
        out[at++] = a;
        out[at++] = block;
        out[at++] = b;
        out[at++] = block;
        out[at++] = c;
        out[at++] = block;
        return at;
    }

    /** One face: a starting corner, two edge vectors, their lengths, and its color block. */
    static final class Face {
        final double ox;
        final double oy;
        final double oz;
        final double ax;
        final double ay;
        final double az;
        final double bx;
        final double by;
        final double bz;
        final double lengthA;
        final double lengthB;
        final int block;

        Face(double ox, double oy, double oz,
                double ax, double ay, double az,
                double bx, double by, double bz,
                double lengthA, double lengthB, int block) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.ax = ax;
            this.ay = ay;
            this.az = az;
            this.bx = bx;
            this.by = by;
            this.bz = bz;
            this.lengthA = lengthA;
            this.lengthB = lengthB;
            this.block = block;
        }
    }
}
