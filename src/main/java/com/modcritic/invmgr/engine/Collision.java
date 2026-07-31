package com.modcritic.invmgr.engine;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Units;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides where a dragged item is actually allowed to end up.
 *
 * <p>Two jobs: keep items inside the room, and — while Layer Collision is on — stop them
 * passing through each other.
 */
public final class Collision {

    /**
     * How close counts as "exactly touching" when comparing heights, in inches.
     *
     * <p>Needed because these are decimal numbers: a box resting on an 18-inch box might
     * have a base height of 18.000000000000004 after a few conversions, and that must
     * still count as resting on it rather than as sinking into it.
     */
    private static final double HEIGHT_EPSILON = 1e-3;

    private Collision() {
    }

    /** Which direction a slide is being resolved along. */
    public enum Axis { X, Y }

    /** A position in room pixels. */
    public record Point(double x, double y) {
    }

    /**
     * Works out how far an item can move along one axis before it touches something.
     *
     * <p><b>This calculates the exact contact point rather than testing a series of
     * candidate positions</b>, and that distinction is the reason dragging feels right. A
     * single mouse movement can jump a long way — far enough to pass straight over an
     * obstacle — and a step-by-step search would stop up to one step short of the real
     * contact edge. In the original app that produced inconsistent gaps between boxes, and
     * it was a genuine reported bug. Solving for the contact point directly means the
     * result is the same whether the pointer moved 2 pixels or 200.
     *
     * @param from where the item is now, along this axis
     * @param to where it wants to go
     * @param size the item's extent along this axis, in pixels
     * @param crossFrom the item's position along the <em>other</em> axis
     * @param crossSize the item's extent along the other axis
     * @param obstacles footprints that can block; already filtered by the caller
     * @param axis which axis this is; decides how each obstacle's edges are read
     * @return the furthest allowed position, never past {@code to}
     */
    public static double slideAxis(double from, double to, double size,
            double crossFrom, double crossSize, List<Rect> obstacles, Axis axis) {
        if (to == from) {
            return from;
        }
        double limit = to;
        if (to > from) {
            // Moving forward: only obstacles wholly ahead of the leading edge can block,
            // and the item stops with its leading edge against the nearest one.
            for (Rect o : obstacles) {
                if (!overlapsAcross(o, crossFrom, crossSize, axis)) {
                    continue;
                }
                double min = minOf(o, axis);
                if (min >= from + size) {
                    limit = Math.min(limit, min - size);
                }
            }
            return Math.max(from, limit);
        }
        // Moving backward: only obstacles wholly behind the trailing edge can block.
        for (Rect o : obstacles) {
            if (!overlapsAcross(o, crossFrom, crossSize, axis)) {
                continue;
            }
            double max = maxOf(o, axis);
            if (max <= from) {
                limit = Math.max(limit, max);
            }
        }
        return Math.min(from, limit);
    }

    /**
     * Where an item ends up if the user tries to move it to {@code (nx, ny)}.
     *
     * <p>Always keeps it inside the room. Additionally, while Layer Collision is on, slides
     * it up against anything it would otherwise pass through.
     *
     * @return the allowed position, rounded to whole pixels
     */
    public static Point clampItem(AppState state, Item item, double nx, double ny) {
        double widthPx = Units.inchesToPx(item.w_in);
        double lengthPx = Units.inchesToPx(item.l_in);

        // Room bounds always apply, whatever the mode.
        double cx = Math.round(clamp(nx, 0, Units.feetToPx(state.room.w) - widthPx));
        double cy = Math.round(clamp(ny, 0, Units.feetToPx(state.room.l) - lengthPx));

        if (!state.layerCollision) {
            return new Point(cx, cy);
        }

        List<Rect> obstacles = blockingObstacles(state, item);

        // If the item is already overlapping something — which happens when Layer Collision
        // is switched on while two boxes are sitting in the same place — don't try to
        // resolve it. Fighting to separate them would teleport the item somewhere the user
        // did not ask for. Room bounds only, and leave it alone.
        Rect currentRect = Rect.of(item);
        for (Rect o : obstacles) {
            if (currentRect.overlaps(o)) {
                return new Point(cx, cy);
            }
        }

        double rx = slideAxis(item.x_px, cx, widthPx, item.y_px, lengthPx, obstacles, Axis.X);
        // The Y pass is given rx — the X the item actually reached this frame — and NOT its
        // original x_px. With the stale X, the sideways-overlap test asks "would I hit this
        // obstacle from where I started", so an item can slide around the corner of
        // something that should have blocked it.
        double ry = slideAxis(item.y_px, cy, lengthPx, rx, widthPx, obstacles, Axis.Y);

        return new Point(Math.round(rx), Math.round(ry));
    }

    /**
     * The items that can block this one: not itself, not a ghost, and at a height that
     * genuinely overlaps this item's own.
     *
     * <p><b>The zero-gap exception is the subtle part.</b> Two boxes flush against each
     * other vertically — one resting exactly on the other's top, or sitting exactly
     * underneath it — are a clean stack, not two things occupying the same space. Without
     * this exception, a box could never be dragged while something rested on it. The check
     * runs in <em>both</em> directions, so it holds no matter which of the two is the one
     * being dragged.
     */
    private static List<Rect> blockingObstacles(AppState state, Item item) {
        double itemTop = item.baseHeight_in + item.h_in;
        List<Rect> obstacles = new ArrayList<>();
        for (Item other : state.items) {
            if (other.id.equals(item.id) || other.planned) {
                continue;
            }
            double otherTop = other.baseHeight_in + other.h_in;
            boolean heightsIntersect =
                    other.baseHeight_in <= itemTop && otherTop >= item.baseHeight_in;
            if (!heightsIntersect) {
                continue;
            }
            boolean restingOnItem = Math.abs(otherTop - item.baseHeight_in) <= HEIGHT_EPSILON;
            boolean itemRestingOnOther = Math.abs(itemTop - other.baseHeight_in) <= HEIGHT_EPSILON;
            if (restingOnItem || itemRestingOnOther) {
                continue;
            }
            obstacles.add(Rect.of(other));
        }
        return obstacles;
    }

    private static boolean overlapsAcross(Rect o, double crossFrom, double crossSize, Axis axis) {
        double crossMin = axis == Axis.X ? o.y() : o.x();
        double crossMax = axis == Axis.X ? o.y2() : o.x2();
        return crossFrom < crossMax && crossFrom + crossSize > crossMin;
    }

    private static double minOf(Rect o, Axis axis) {
        return axis == Axis.X ? o.x() : o.y();
    }

    private static double maxOf(Rect o, Axis axis) {
        return axis == Axis.X ? o.x2() : o.y2();
    }

    static double clamp(double value, double min, double max) {
        // max can be below min for an item wider than the room; Math.min first means the
        // item is pinned to the west/north wall rather than flung outside it.
        return Math.max(min, Math.min(max, value));
    }
}
