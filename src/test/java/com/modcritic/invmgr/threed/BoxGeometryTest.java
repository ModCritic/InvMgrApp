package com.modcritic.invmgr.threed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The box's corners, its triangles, and (the one that matters) which way each triangle faces.
 *
 * <p><b>Why the winding gets its own test rather than being left to the eye.</b> The specification
 * has y pointing up and JavaFX has it pointing down, so every point is mirrored on the way in. A
 * mirror reverses which side of a triangle is the front, so unless the corner order is reversed to
 * compensate, every surface in the room is inside out. The OD-1 spike hit this and switched face
 * culling off, with a comment admitting the real port had to do better.
 *
 * <p>It is worth catching here because it is nearly invisible from where you normally stand. Inside
 * a room, an inside-out wall behind you is not on screen, and an inside-out box shows you the
 * inside of its far side, which is still box-colored. You would find out by walking out through a
 * wall and looking back, which is a thing the app allows and almost nobody does.
 *
 * <p>So instead of rendering anything, this works out each triangle's normal from the points as
 * emitted and checks it points <em>away</em> from the middle of the box. That is decidable with
 * arithmetic, needs no graphics card, and does not depend on knowing which winding JavaFX happens
 * to treat as the front.
 */
class BoxGeometryTest {

    private static final double TOLERANCE = 1e-9;

    private static Box3D box(double wIn, double lIn, double hIn) {
        Item it = new Item();
        it.id = "b";
        it.x_px = 96;
        it.y_px = 192;
        it.w_in = wIn;
        it.l_in = lIn;
        it.h_in = hIn;
        it.baseHeight_in = 12;
        it.color = "hsl(207,55%,42%)";
        return Geometry3D.boxFor(it);
    }

    @Test
    @DisplayName("every triangle faces outward, for boxes of every shape")
    void everyTriangleFacesOutward() {
        // Several shapes, because a cube is symmetric enough to hide a mistake that only shows on
        // one axis. The flat and tall ones are the shapes real inventory actually has.
        Box3D[] shapes = {
            box(12, 12, 12),      // a cube
            box(48, 6, 6),        // a long thin bar
            box(6, 48, 6),        // the same, turned
            box(6, 6, 48),        // a tall post
            box(36, 24, 18),      // an ordinary carton
        };

        for (Box3D b : shapes) {
            float[] points = BoxGeometry.points(b);
            int[] faces = BoxGeometry.faces(b);

            // The middle of the box, in JavaFX coordinates, so y AND z are negated here too.
            // Both of them: this point is what "outward" is measured from, and leaving z alone
            // puts it outside the box, at which point every normal in the room looks backwards.
            double cx = (b.x0 + b.x1) / 2;
            double cy = -(b.y0 + b.y1) / 2;
            double cz = -(b.z0 + b.z1) / 2;

            int checked = 0;
            for (int t = 0; t < faces.length; t += 18) {
                for (int tri = 0; tri < 3; tri++) {
                    int at = t + tri * 6;
                    if (at + 5 >= faces.length) {
                        break;
                    }
                    double[] p0 = point(points, faces[at]);
                    double[] p1 = point(points, faces[at + 2]);
                    double[] p2 = point(points, faces[at + 4]);

                    double[] n = cross(sub(p1, p0), sub(p2, p0));
                    double magnitude = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
                    if (magnitude < 1e-12) {
                        // A frame strip on a face too small to hold one collapses to zero area.
                        // Nothing is drawn and there is no direction to check.
                        continue;
                    }

                    // Outward means the normal agrees with the direction from the box's middle to
                    // the triangle. Strictly greater than zero: a normal at right angles to that
                    // would mean the triangle is edge-on to its own box, which is not a thing.
                    double[] outward = sub(p0, new double[] {cx, cy, cz});
                    double agreement = dot(n, outward) / magnitude;
                    assertTrue(agreement > TOLERANCE,
                            "a triangle faces inward on the "
                                    + b.width() + "x" + b.length() + "x" + b.height()
                                    + " ft box (agreement " + agreement + ")");
                    checked++;
                }
            }
            assertTrue(checked >= 50,
                    "expected most of the 60 triangles to have area, checked " + checked);
        }
    }

    @Test
    @DisplayName("reversing the corner order would be caught, not silently accepted")
    void theOutwardCheckIsNotVacuous() {
        // The test above is only worth anything if it fails when the winding is wrong. Rather than
        // trusting that, this reverses two corners by hand and confirms the same arithmetic says
        // "inward", so a mutation that removes the swap in BoxGeometry cannot pass.
        Box3D b = box(12, 12, 12);
        float[] points = BoxGeometry.points(b);
        int[] faces = BoxGeometry.faces(b);

        double cx = (b.x0 + b.x1) / 2;
        double cy = -(b.y0 + b.y1) / 2;
        double cz = -(b.z0 + b.z1) / 2;

        double[] p0 = point(points, faces[0]);
        double[] p1 = point(points, faces[2]);
        double[] p2 = point(points, faces[4]);

        double[] rightWayRound = cross(sub(p1, p0), sub(p2, p0));
        double[] backwards = cross(sub(p2, p0), sub(p1, p0));
        double[] outward = sub(p0, new double[] {cx, cy, cz});

        assertTrue(dot(rightWayRound, outward) > 0, "as emitted, the triangle faces outward");
        assertTrue(dot(backwards, outward) < 0, "reversed, the same triangle faces inward");
    }

    @Test
    @DisplayName("y is negated on the way into JavaFX's coordinates")
    void theVerticalAxisIsFlipped() {
        // The item sits at base height 12 in = 1 ft and is 12 in tall, so in the specification's
        // coordinates it spans y 1 to 2, upward. In JavaFX's it must span -1 to -2.
        Box3D b = box(12, 12, 12);
        assertEquals(1, b.y0, TOLERANCE);
        assertEquals(2, b.y1, TOLERANCE);

        float[] points = BoxGeometry.points(b);
        double lowest = Double.POSITIVE_INFINITY;
        double highest = Double.NEGATIVE_INFINITY;
        for (int i = 1; i < points.length; i += 3) {
            lowest = Math.min(lowest, points[i]);
            highest = Math.max(highest, points[i]);
        }
        assertEquals(-2, lowest, TOLERANCE, "the top of the box is the most negative y");
        assertEquals(-1, highest, TOLERANCE);
    }

    @Test
    @DisplayName("the edge frame is a quarter of an inch, matching the original's 2px border")
    void theFrameIsTheOriginalsBorderWidth() {
        assertEquals(2.0 / 96, BoxGeometry.EDGE_INSET_FT, TOLERANCE);

        // On a 4 ft face the frame is 2/96 of a foot, i.e. 1/192 of the face.
        assertEquals(BoxGeometry.EDGE_INSET_FT / 4, BoxGeometry.insetFraction(4), TOLERANCE);

        // Bounded on both sides so it cannot quietly become a percentage of the face: the frame on
        // a big face and a small face must be the same width in the ROOM, not the same fraction.
        double onSmall = BoxGeometry.insetFraction(1) * 1;
        double onLarge = BoxGeometry.insetFraction(8) * 8;
        assertEquals(onSmall, onLarge, TOLERANCE,
                "the frame is a fixed width in feet, not a fixed share of the face");
    }

    @Test
    @DisplayName("a face too small to hold a frame turns entirely into the edge color")
    void aTinyFaceCollapsesToItsEdge() {
        // An item may legally be one inch across, thinner than two frames. Without a cap the
        // frame's inner edge would cross the outer one and turn the face inside out. At exactly a
        // half the middle collapses to nothing and the strips meet, so the face comes out solid
        // edge color. That is what a browser does when a border is wider than its box, so it is
        // the faithful answer as well as the safe one.
        assertEquals(0.5, BoxGeometry.insetFraction(1.0 / 96), TOLERANCE);
        assertEquals(0.5, BoxGeometry.insetFraction(0), TOLERANCE);
        assertTrue(BoxGeometry.insetFraction(0.01) <= 0.5);

        // And the geometry still holds together: the winding test above includes no tiny box, so
        // check here that one produces the right number of points and no NaN.
        float[] points = BoxGeometry.points(box(1, 1, 1));
        assertEquals(BoxGeometry.FACE_COUNT * BoxGeometry.POINTS_PER_FACE * 3, points.length);
        for (float p : points) {
            assertTrue(Float.isFinite(p), "a one-inch box produced a non-finite corner");
        }
    }

    @Test
    @DisplayName("the counts are six faces of five quads, and nothing is missing")
    void theCountsAreRight() {
        Box3D b = box(12, 12, 12);
        assertEquals(6 * 16 * 3, BoxGeometry.points(b).length, "96 points, three numbers each");
        // 6 faces x 5 quads x 2 triangles x (3 corners x 2 indices)
        assertEquals(6 * 5 * 2 * 6, BoxGeometry.faces(b).length);
        assertEquals(60, BoxGeometry.faces(b).length / 6, "sixty triangles per box");
    }

    @Test
    @DisplayName("only the middle of each face takes the face's own color; the frame takes the edge")
    void theFrameIsTheEdgeColorAndTheMiddleIsNot() {
        Box3D b = box(12, 12, 12);
        int[] faces = BoxGeometry.faces(b);

        int edgeTriangles = 0;
        int centerTriangles = 0;
        for (int at = 0; at < faces.length; at += 6) {
            if (faces[at + 1] == BoxGeometry.BLOCK_EDGE) {
                edgeTriangles++;
            } else {
                centerTriangles++;
            }
        }
        // Four strips of two triangles on each of six faces, and one middle of two triangles.
        assertEquals(6 * 4 * 2, edgeTriangles);
        assertEquals(6 * 2, centerTriangles);

        // And the middles are the right colors: one top, one bottom, two north/south, two
        // east/west. Getting this wrong would light the box from the side.
        int top = 0;
        int bottom = 0;
        int northSouth = 0;
        int eastWest = 0;
        for (int at = 0; at < faces.length; at += 6) {
            switch (faces[at + 1]) {
                case BoxGeometry.BLOCK_TOP -> top++;
                case BoxGeometry.BLOCK_BOTTOM -> bottom++;
                case BoxGeometry.BLOCK_NORTH_SOUTH -> northSouth++;
                case BoxGeometry.BLOCK_EAST_WEST -> eastWest++;
                default -> { }
            }
        }
        assertEquals(2, top, "one face is up-facing, and it is two triangles");
        assertEquals(2, bottom);
        assertEquals(4, northSouth, "two faces");
        assertEquals(4, eastWest, "two faces");
    }

    @Test
    @DisplayName("all three corners of a triangle sample the same texel, so a face is perfectly flat")
    void everyTriangleReadsOneColor() {
        // This is what makes the shading flat with no light in the scene: there is nothing for the
        // graphics card to blend between, because all three corners point at the same pixel.
        Box3D b = box(12, 12, 12);
        int[] faces = BoxGeometry.faces(b);
        for (int at = 0; at < faces.length; at += 6) {
            assertEquals(faces[at + 1], faces[at + 3], "corner 2 samples a different block");
            assertEquals(faces[at + 1], faces[at + 5], "corner 3 samples a different block");
        }

        // And each block's coordinate is in the middle of its block, not on a boundary where
        // filtering could pick up the neighboring color.
        float[] uv = BoxGeometry.texCoords();
        assertEquals(BoxGeometry.BLOCK_COUNT * 2, uv.length);
        for (int i = 0; i < BoxGeometry.BLOCK_COUNT; i++) {
            assertEquals((i + 0.5f) / BoxGeometry.BLOCK_COUNT, uv[i * 2], 1e-6);
            assertEquals(0.5f, uv[i * 2 + 1], 1e-6);
        }
    }

    @Test
    @DisplayName("the five quads cover the face exactly, with no gap and no overlap")
    void theQuadsTileTheFace() {
        // A gap would show the inside of the box through a hairline crack; an overlap would fight
        // for depth and flicker. The areas summing to the face's area rules out both at once.
        Box3D b = box(36, 24, 18);
        float[] points = BoxGeometry.points(b);
        int[] faces = BoxGeometry.faces(b);

        double[] faceArea = {b.width() * b.length(), b.width() * b.length(),
            b.width() * b.height(), b.width() * b.height(),
            b.height() * b.length(), b.height() * b.length()};

        int trianglesPerFace = BoxGeometry.QUADS_PER_FACE * 2;
        for (int f = 0; f < BoxGeometry.FACE_COUNT; f++) {
            double area = 0;
            for (int t = 0; t < trianglesPerFace; t++) {
                int at = (f * trianglesPerFace + t) * 6;
                double[] p0 = point(points, faces[at]);
                double[] p1 = point(points, faces[at + 2]);
                double[] p2 = point(points, faces[at + 4]);
                double[] n = cross(sub(p1, p0), sub(p2, p0));
                area += 0.5 * Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
            }
            assertEquals(faceArea[f], area, 1e-9,
                    "face " + f + "'s five quads do not add up to its area");
        }
    }

    private static double[] point(float[] points, int index) {
        return new double[] {points[index * 3], points[index * 3 + 1], points[index * 3 + 2]};
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
}
