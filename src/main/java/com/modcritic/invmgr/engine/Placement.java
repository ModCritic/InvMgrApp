package com.modcritic.invmgr.engine;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Units;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds somewhere to put a new box.
 *
 * <p>Without this, adding several boxes in a row would drop them all on the same spot, and
 * each would hide the ones underneath — the room would look empty while holding five boxes.
 */
public final class Placement {

    /** Give up after this many rings and settle for the preferred point. */
    private static final int MAX_RINGS = 300;

    /**
     * Give up after this many position checks.
     *
     * <p>A safety valve, not a tuning knob: in a crowded room the ring search grows as the
     * square of the ring number, so without a hard ceiling adding one box to a full room
     * could hang the app.
     */
    private static final int MAX_CHECKS = 4000;

    /** Never search in steps smaller than this many pixels — two inches. */
    private static final double MIN_STEP_PX = 16;

    private Placement() {
    }

    /** An open spot near the middle of the room. */
    public static Collision.Point findOpenSpot(AppState state, double w_in, double l_in) {
        double widthPx = Units.inchesToPx(w_in);
        double lengthPx = Units.inchesToPx(l_in);
        // Centred means the item's middle is at the room's middle, so the top-left corner
        // sits half the item's size back from it.
        return findOpenSpot(state, w_in, l_in,
                (Units.feetToPx(state.room.w) - widthPx) / 2,
                (Units.feetToPx(state.room.l) - lengthPx) / 2);
    }

    /**
     * An open spot as near as possible to {@code (preferredX, preferredY)}.
     *
     * <p>Searches outward in expanding square rings. Within each ring, nearer candidates are
     * tried first, so the result is the closest free spot rather than merely a free one.
     *
     * <p><b>If nothing is free, it returns the preferred point and allows the overlap.</b>
     * Refusing to add the item would be worse: the user asked for a box, and a visible
     * overlap they can drag apart beats a silent no.
     *
     * @param w_in the new item's width in inches
     * @param l_in the new item's length in inches
     */
    public static Collision.Point findOpenSpot(AppState state, double w_in, double l_in,
            double preferredX, double preferredY) {
        double widthPx = Units.inchesToPx(w_in);
        double lengthPx = Units.inchesToPx(l_in);
        double maxX = Units.feetToPx(state.room.w) - widthPx;
        double maxY = Units.feetToPx(state.room.l) - lengthPx;

        double startX = Math.round(Collision.clamp(preferredX, 0, maxX));
        double startY = Math.round(Collision.clamp(preferredY, 0, maxY));

        // Ghosts are not in the room, so they never block a real box.
        List<Rect> obstacles = new ArrayList<>();
        for (Item item : state.items) {
            if (!item.planned) {
                obstacles.add(Rect.of(item));
            }
        }

        Collision.Point preferred = new Collision.Point(startX, startY);
        if (isFree(startX, startY, widthPx, lengthPx, obstacles)) {
            return preferred;
        }

        // Step scales with the item so a big box doesn't crawl outward in tiny increments,
        // with a floor so a small one doesn't either.
        double step = Math.max(MIN_STEP_PX, Math.min(widthPx, lengthPx));
        int checks = 0;

        for (int ring = 1; ring <= MAX_RINGS; ring++) {
            List<Collision.Point> candidates = ringCandidates(ring, startX, startY, step, maxX, maxY);
            // Nearest-first within the ring. Distances are measured after clamping, because
            // clamping is what pulls candidates back inside the room — several may collapse
            // onto the same wall position, and those duplicates are kept so the search
            // behaves identically to the original's.
            candidates.sort(Comparator.comparingDouble(c -> squaredDistance(c, startX, startY)));

            for (Collision.Point candidate : candidates) {
                if (++checks > MAX_CHECKS) {
                    return preferred;
                }
                if (isFree(candidate.x(), candidate.y(), widthPx, lengthPx, obstacles)) {
                    return candidate;
                }
            }
        }
        return preferred;
    }

    /** Every position on the square ring at distance {@code ring} steps from the centre. */
    private static List<Collision.Point> ringCandidates(int ring, double startX, double startY,
            double step, double maxX, double maxY) {
        List<Collision.Point> candidates = new ArrayList<>();
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dy = -ring; dy <= ring; dy++) {
                // Only the ring's edge, not its filled interior — the inside was covered by
                // earlier, smaller rings.
                if (Math.max(Math.abs(dx), Math.abs(dy)) != ring) {
                    continue;
                }
                candidates.add(new Collision.Point(
                        Math.round(Collision.clamp(startX + dx * step, 0, maxX)),
                        Math.round(Collision.clamp(startY + dy * step, 0, maxY))));
            }
        }
        return candidates;
    }

    private static double squaredDistance(Collision.Point p, double x, double y) {
        double dx = p.x() - x;
        double dy = p.y() - y;
        return dx * dx + dy * dy;
    }

    private static boolean isFree(double x, double y, double widthPx, double lengthPx,
            List<Rect> obstacles) {
        Rect candidate = Rect.at(x, y, widthPx, lengthPx);
        for (Rect o : obstacles) {
            if (candidate.overlaps(o)) {
                return false;
            }
        }
        return true;
    }
}
