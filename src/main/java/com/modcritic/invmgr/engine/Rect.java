package com.modcritic.invmgr.engine;

import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Units;

/**
 * An item's footprint seen from directly above, in screen pixels.
 *
 * <p>Height is deliberately absent. A footprint is the 2D shadow an item casts on the
 * floor, which is what collision and stacking both work with; how tall the item is and how
 * far off the floor it sits are handled separately.
 *
 * <p>{@code x}/{@code y} are the top-left (north-west) corner, {@code x2}/{@code y2} the
 * bottom-right. X grows east, Y grows <b>south</b> — screen coordinates, so Y counts
 * downward, not upward.
 *
 * <p>This is a {@code record}, a short way of writing a class whose values never change
 * once created. Recomputing one is cheap, and immutability means a rect can never
 * accidentally fall out of step with the item it came from.
 */
public record Rect(double x, double y, double x2, double y2) {

    /** The footprint of an item, converting its inch dimensions to pixels. */
    public static Rect of(Item item) {
        return new Rect(
                item.x_px,
                item.y_px,
                item.x_px + Units.inchesToPx(item.w_in),
                item.y_px + Units.inchesToPx(item.l_in));
    }

    /** A footprint of the given pixel size positioned at {@code (x, y)}. */
    public static Rect at(double x, double y, double widthPx, double lengthPx) {
        return new Rect(x, y, x + widthPx, y + lengthPx);
    }

    /**
     * Whether two footprints genuinely overlap.
     *
     * <p><b>The comparisons are strict on purpose: footprints that merely touch do not
     * overlap.</b> Two boxes pushed flush against each other share an edge exactly, and
     * treating that as a collision would make it impossible to place boxes side by side —
     * they would jitter apart or refuse to sit together. This one detail is relied on
     * throughout the collision and stacking code.
     */
    public boolean overlaps(Rect other) {
        return x < other.x2 && x2 > other.x && y < other.y2 && y2 > other.y;
    }
}
