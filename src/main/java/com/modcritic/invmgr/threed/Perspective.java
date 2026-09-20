package com.modcritic.invmgr.threed;

import com.modcritic.invmgr.model.Room;

/**
 * How wide the camera's view is, and how far back it has to sit to show the whole room from
 * above.
 *
 * <p><b>Where the field of view actually comes from.</b> The original never wrote an angle down.
 * It set a CSS {@code perspective} distance in pixels (original line 2750):
 *
 * <pre>normalP = max(300, viewportHeight * 0.75)</pre>
 *
 * which is the distance from the eye to the screen. The half-angle you can see is then
 * {@code atan((viewportHeight/2) / normalP)}, so for any viewport taller than 400 px the ratio is
 * a fixed 0.75, {@code tan(fov/2)} is exactly <b>2/3</b>, and the vertical field of view is
 * <b>2·atan(2/3) ≈ 67.380°</b>.
 *
 * <p>The spec rounds that to "about 67°" and the OD-1 spike used 67 flat. Both are close enough
 * to look right and neither is the number the original had. Using the exact value costs nothing
 * and makes {@link #overheadHeightFt} agree with the original's own arithmetic to the last
 * decimal place instead of carrying a 0.6% error into every entry animation.
 *
 * <p><b>The 300 px floor is ported too</b>, even though nothing will hit it. Below a 400 px-tall
 * window the distance stops shrinking, so the view gets <em>narrower</em> rather than wider. No
 * real window is that short, but porting the whole expression is cheaper than porting most of it
 * and then explaining which part was left out.
 */
public final class Perspective {

    /** The floor under the perspective distance, in pixels (original line 2750). */
    public static final double MIN_PERSPECTIVE_PX = 300;

    /** Perspective distance as a fraction of viewport height, above that floor. */
    public static final double PERSPECTIVE_RATIO = 0.75;

    /**
     * How much more than the bare minimum distance the overhead view backs off by, so the room
     * does not touch the edges of the screen. 6%.
     */
    public static final double OVERHEAD_MARGIN = 1.06;

    /**
     * How far above the ceiling the overhead camera must stay at the very least, in feet.
     *
     * <p>Matters for a tall, narrow room: the fit calculation only cares about floor area, so a
     * 4 ft × 4 ft × 20 ft closet would put the camera <em>inside</em> its own walls without this.
     */
    public static final double OVERHEAD_CEILING_CLEARANCE_FT = 2;

    private Perspective() {
    }

    /**
     * The original's {@code normalP}, in pixels, for a viewport this tall.
     *
     * <p><b>Rounded to a whole pixel, as the original rounds it</b> (original line 2751). It did
     * that because the value went into a CSS {@code perspective: Npx} declaration; a real 3D
     * camera has no such constraint and would be happy with the fraction. Ported anyway, because
     * it costs nothing and it is what makes the overhead height agree with the original's
     * <em>exactly</em> rather than to within a rounding error, and an exact differential is
     * worth more than a fraction of a pixel of purity. The practical effect is that the field of
     * view moves by a few thousandths of a degree as the window is resized, which is what the
     * original did too.
     */
    public static double perspectiveDistancePx(double viewportHeightPx) {
        return Math.round(Math.max(MIN_PERSPECTIVE_PX, viewportHeightPx * PERSPECTIVE_RATIO));
    }

    /**
     * The vertical field of view, in degrees, that reproduces the original's perspective distance,
     * which is what a real 3D camera needs instead.
     */
    public static double verticalFovDegrees(double viewportHeightPx) {
        double half = Math.atan((viewportHeightPx / 2) / perspectiveDistancePx(viewportHeightPx));
        return Math.toDegrees(2 * half);
    }

    /**
     * How high the camera sits for the overhead view the entry animation starts from and the exit
     * animation returns to. Port of the CSS3D adapter's {@code overhead()} (original lines
     * 2734-2738).
     *
     * <p>In plain terms: far enough back that the room's length fills the screen vertically, or
     * its width fills it horizontally (whichever needs more distance), plus 6%, and never closer
     * than 2 ft above the ceiling.
     *
     * <p>Written in the original's own pixel terms rather than re-derived from an angle. The two
     * are the same calculation, but this way there is nothing to get wrong: the FOV the camera is
     * given comes from {@link #verticalFovDegrees}, which is derived from the same
     * {@link #perspectiveDistancePx}, so the fit cannot drift out of step with the lens.
     */
    public static double overheadHeightFt(Room room, double viewportWidthPx,
            double viewportHeightPx) {
        double vw = Math.max(1, viewportWidthPx);
        double vh = Math.max(1, viewportHeightPx);
        double p = perspectiveDistancePx(vh);

        double toFitLength = room.l * p / vh;
        double toFitWidth = room.w * p / vw;
        double y = Math.max(toFitLength, toFitWidth) * OVERHEAD_MARGIN;

        return Math.max(y, room.h + OVERHEAD_CEILING_CLEARANCE_FT);
    }
}
