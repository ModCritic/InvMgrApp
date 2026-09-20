package com.modcritic.invmgr.threed;

import java.util.List;

/**
 * What is under a point on screen: the arithmetic behind hovering a box in the 3D view.
 *
 * <p>Two steps. {@link #rayThrough} turns a point on the drawing surface into a direction pointing
 * out of the camera through that point; {@link #nearestHit} asks which box that direction runs into
 * first. The answer is the item's id, which is what a tooltip needs to look the item up.
 *
 * <p>No JavaFX, and that is a decision rather than a habit. <b>JavaFX can already do this</b>;
 * {@code MouseEvent.getPickResult().getIntersectedNode()} gives a triangle-exact hit with correct
 * occlusion and no math at all. Three reasons it is not used:
 *
 * <ol>
 *   <li><b>It only exists inside a mouse event</b>, so no rule about picking could be checked
 *       without a window, on a machine with no screen. Every question below (which side of the
 *       screen a box is on, whether the nearer of two wins, whether something behind you can be
 *       picked) is answerable here in milliseconds and headless.</li>
 *   <li><b>It would put picking behind the renderer boundary.</b> OD-1's binding consequence 1
 *       keeps the 3D decision reversible by keeping that boundary thin, and picking is a large
 *       thing to push across it. This way a replacement renderer inherits picking for free.</li>
 *   <li><b>On Android {@code getIntersectedDistance()} is not in world units</b> (SPIKE-OD-1: it
 *       reported 1380.9 for a few feet). Nearest-hit still works there, but the number does not,
 *       and a caller that ever wants the real distance would find out the hard way. Here the
 *       distance is in feet on every platform because we computed it.</li>
 * </ol>
 *
 * <p><b>And it loses nothing, because an item <em>is</em> a box.</b> A bounding box is normally an
 * approximation of a shape; here the shape is an axis-aligned box, so the bounding box is the
 * geometry exactly. There is no case where the cheap test and the exact test disagree.
 */
public final class Picking {

    private Picking() {
    }

    /**
     * The direction pointing out of the camera through a point on the drawing surface, as a unit
     * vector {@code {x, y, z}} in world feet.
     *
     * <p>{@code xPx} and {@code yPx} are in the surface's own pixels, measured from its top-left
     * corner with y counted downward, which is what every pointer event reports.
     *
     * <p>The camera's basis comes straight from {@code SPEC-3D-VIEW.md} §1: forward is
     * {@code (sin yaw · cos pitch, sin pitch, −cos yaw · cos pitch)}, right is
     * {@code (cos yaw, 0, sin yaw)}, and up is their cross product. At yaw 0 and pitch 0 those are
     * north, east and up, which is the check worth doing by hand if this is ever changed.
     *
     * <p><b>The field of view is vertical</b>, and comes from
     * {@link Perspective#verticalFovDegrees} given the surface <em>height</em>. That matches how
     * the renderer sets up its own camera ({@code setVerticalFieldOfView(true)}), and getting it
     * from the width instead is a mistake that looks almost right; it only shows up as picks
     * drifting further off the further you are from the middle of the screen.
     */
    public static double[] rayThrough(double xPx, double yPx, double widthPx, double heightPx,
            CameraPose camera) {
        double w = Math.max(1, widthPx);
        double h = Math.max(1, heightPx);

        // -1 at the left edge, +1 at the right; -1 at the bottom, +1 at the top.
        double ndcX = (xPx / w) * 2 - 1;
        double ndcY = 1 - (yPx / h) * 2;

        double tanHalfFov = Math.tan(Math.toRadians(Perspective.verticalFovDegrees(h)) / 2);
        double aspect = w / h;

        double cosPitch = Math.cos(camera.pitch);
        double fx = Math.sin(camera.yaw) * cosPitch;
        double fy = Math.sin(camera.pitch);
        double fz = -Math.cos(camera.yaw) * cosPitch;

        double rx = Math.cos(camera.yaw);
        double rz = Math.sin(camera.yaw);

        // up = right x forward. At yaw 0 that is (1,0,0) x (0,0,-1) = (0,1,0), straight up.
        double ux = -rz * fy;
        double uy = rz * fx - rx * fz;
        double uz = rx * fy;

        double sx = ndcX * aspect * tanHalfFov;
        double sy = ndcY * tanHalfFov;

        double dx = fx + rx * sx + ux * sy;
        double dy = fy + uy * sy;
        double dz = fz + rz * sx + uz * sy;

        // Normalized so the t that comes back from nearestHit is a real distance in feet. Nothing
        // depends on that for choosing a winner (every t along one ray scales together), but a
        // distance that means something is far easier to test and to debug than one that does not.
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new double[] {dx / length, dy / length, dz / length};
    }

    /**
     * Which box the ray runs into first, or {@code null} if it misses everything.
     *
     * <p>A standard slab test: for each axis, work out the stretch of the ray that is inside the
     * box's range on that axis, and keep the overlap of all three. If the overlap is empty the ray
     * misses.
     *
     * <p><b>Anything entirely behind the camera is not a hit.</b> Without that, standing with your
     * back to a box would pick it; the ray's line passes through it, just in the wrong direction.
     *
     * <p><b>Standing inside a box is a hit, at distance zero.</b> That falls out of the same rule
     * rather than being a special case, and it is right: a box you are inside fills the screen, so
     * it is what you are pointing at.
     */
    public static String nearestHit(List<Box3D> boxes, CameraPose camera, double[] direction) {
        String best = null;
        double bestDistance = Double.POSITIVE_INFINITY;

        for (Box3D box : boxes) {
            double distance = distanceTo(box, camera, direction);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = box.itemId;
            }
        }
        return best;
    }

    /**
     * How far along the ray this box starts, in feet, or {@link Double#POSITIVE_INFINITY} for a
     * miss. Zero when the camera is inside the box.
     *
     * <p>Package-private rather than private <b>so the distance itself can be asserted</b>.
     * {@link #nearestHit} only ever compares these numbers, so a mutation that returns the wrong
     * one (a negative distance from inside a box, say) still picks the same winner and slips
     * past any test that can only see the id. Found exactly that way.
     */
    static double distanceTo(Box3D box, CameraPose camera, double[] direction) {
        double near = Double.NEGATIVE_INFINITY;
        double far = Double.POSITIVE_INFINITY;

        double[] origin = {camera.x, camera.y, camera.z};
        double[] low = {box.x0, box.y0, box.z0};
        double[] high = {box.x1, box.y1, box.z1};

        for (int axis = 0; axis < 3; axis++) {
            double d = direction[axis];
            if (d == 0) {
                // The ray never moves along this axis, so it is either always inside the box's
                // range on it or never. Dividing would give an infinity or, worse, a NaN when the
                // origin sits exactly on a face; one NaN silently turns every comparison
                // below into false, which reads as a hit rather than a miss.
                if (origin[axis] < low[axis] || origin[axis] > high[axis]) {
                    return Double.POSITIVE_INFINITY;
                }
                continue;
            }
            double t0 = (low[axis] - origin[axis]) / d;
            double t1 = (high[axis] - origin[axis]) / d;
            if (t0 > t1) {
                double swap = t0;
                t0 = t1;
                t1 = swap;
            }
            near = Math.max(near, t0);
            far = Math.min(far, t1);
            if (far < near) {
                return Double.POSITIVE_INFINITY;
            }
        }

        if (far <= 0) {
            return Double.POSITIVE_INFINITY;      // entirely behind the camera
        }
        return Math.max(near, 0);                 // 0 when the camera is inside the box
    }
}
