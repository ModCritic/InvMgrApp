package com.modcritic.invmgr.threed;

import com.modcritic.invmgr.model.Units;

/**
 * One square of the room's grid, and where to start laying it down on a given surface.
 *
 * <p><b>What this replaces, and why.</b> The 3D room's floor and walls used to be painted as one
 * picture each, sized {@code min(96, 2048 / longest side)} pixels to the foot. That cap is what
 * made a large room blurry: a 200 ft floor got 10.24 pixels to the foot where the app draws at 96,
 * so every grid line on it was smeared across nine pixels' worth of world. The original has the
 * same line and the same blur; see {@code MILESTONES.md} M6.7 item 1.
 *
 * <p>The grid is periodic, so it does not need a picture of the whole floor. One square foot drawn
 * at the full 96 pixels to the foot, laid down as many times as the room is wide, is sharp in a
 * 200 ft room and in a 6 ft one alike, and costs the same 36 KB either way.
 *
 * <p><b>The one thing that makes this work at all was measured, not assumed.</b> JavaFX does not
 * document what a 3D mesh does with a texture coordinate greater than 1. {@code TextureWrapProbe}
 * renders a quad with coordinates running 0 to 4 and counts the stripes: <b>they repeat</b>. If
 * they had clamped, none of this would be possible.
 *
 * <p><b>This is not the tiling CLAUDE.md §1 forbids.</b> That rule is about the original's CSS3D
 * workarounds, which tiled walls to dodge a near-plane clipping problem JavaFX does not have. That
 * reasoning is untouched. This tiles for an unrelated reason, texture resolution, which the
 * original also suffers from and never solved.
 *
 * <h2>Where the grid starts, which is the whole subtlety</h2>
 *
 * <p>A repeating tile draws a line wherever its texture coordinate crosses a whole number, so the
 * only freedom is <em>where the count starts</em>. Two surfaces facing each other across the room
 * are drawn from opposite ends of the same world axis: the north wall's texture runs west to east,
 * the south wall's east to west. Start both counting from their own left edge and, in a room 12.5
 * ft across, one puts lines 1, 2 … 12 ft from the west wall and the other puts them at 0.5, 1.5 …
 * 11.5 ft from it. Every line sits half a foot from its opposite number and the corners disagree
 * visibly.
 *
 * <p>{@link #originX} is the fix, and it is the same fix the original made for the same bug
 * (original lines 2554-2566), arrived at from the geometry rather than copied: a mirrored surface
 * starts its count at the room's leftover part of a foot, so both surfaces land on the same world
 * positions. The second axis never needs this, because no pair of surfaces is drawn from opposite
 * ends of it: every wall runs its texture the same way up, and the floor's second axis agrees with
 * the walls it meets.
 *
 * <h2>The one line the original does not draw</h2>
 *
 * <p>The original starts its grid one step in and stops before the far edge, so the room's own
 * boundary never carries a line. <b>A repeating tile cannot do that</b>, and the reason is
 * arithmetic rather than effort: lines land wherever the count crosses a whole number, the room's
 * near edge is where the count starts, and no choice of starting point can give a line to every
 * whole foot except the first one. So each axis of each surface gains exactly one line the
 * original does not have, on the room's own boundary.
 *
 * <p>That is §5.5 D-25, and it lands where two surfaces already meet at a right angle: on the
 * crease between the floor and a wall, or in a corner between two walls.
 */
public final class GridTile {

    /** The app's own scale, the same 96 pixels to the foot the flat top-down view draws at. */
    public static final double PX_PER_FOOT = Units.PX_PER_FOOT;

    /**
     * How wide a grid line is drawn, in tile pixels.
     *
     * <p>Two pixels at 96 to the foot, so a quarter of an inch in the room. It is the same number
     * in metric: a meter tile is both 3.28 times wider in the world and 3.28 times more pixels, so
     * two pixels is a quarter of an inch either way.
     *
     * <p><b>There is no lower clamp here, and its absence is the point.</b> The old per-surface
     * painter had {@code max(1, 2 * s / 96)}, which stopped the line shrinking below one texture
     * pixel. In a 200 ft room that one pixel was 1.17 inches of floor, so the lines were not only
     * blurry but almost five times too thick. At a fixed 96 pixels to the foot the clamp has
     * nothing left to protect against.
     */
    public static final double LINE_WIDTH_PX = 2;

    /** How far apart the lines are, in feet: one foot, or one meter expressed in feet. */
    public final double stepFt;

    /** The tile's side, in pixels. 96 for a foot, 315 for a meter. */
    public final int tilePx;

    private GridTile(double stepFt, int tilePx) {
        this.stepFt = stepFt;
        this.tilePx = tilePx;
    }

    /** @param metric whether the grid is drawn a meter apart instead of a foot */
    public static GridTile forGrid(boolean metric) {
        double stepFt = metric ? 1 / Units.M_PER_FT : 1;
        return new GridTile(stepFt, (int) Math.round(stepFt * PX_PER_FOOT));
    }

    /**
     * How many tiles a surface this long needs, which is also its texture coordinate span.
     *
     * <p>Fractional on purpose. A 12.5 ft wall spans 12.5 tiles and the last one is cut off half
     * way, which is exactly what a room that is not a whole number of feet across should look
     * like.
     */
    public double span(double lengthFt) {
        return lengthFt / stepFt;
    }

    /**
     * The texture coordinate the surface's first corner starts at, along the surface's own x axis.
     *
     * <p>Zero for a surface whose texture runs the same way along its world axis as the floor's.
     * For a mirrored one, the room's leftover part of a tile, which is what puts its lines on the
     * same world positions as its opposite number's. See the class documentation for the bug this
     * exists to prevent, and {@code GridTileTest} for it asserted in world feet.
     *
     * <p>Only the fractional part matters, since the tile repeats every whole number, and it is
     * kept in {@code [0, 1)} so that a 200 ft room's coordinates stay small enough for a float to
     * hold precisely.
     *
     * @param widthFt the surface's own width, along the axis being placed
     * @param mirroredGridX whether this surface's texture x axis points the opposite way along its
     *     world axis from the floor's; true for the south and east walls
     */
    public double originX(double widthFt, boolean mirroredGridX) {
        if (!mirroredGridX) {
            return 0;
        }
        double fraction = -span(widthFt) % 1;
        return fraction < 0 ? fraction + 1 : fraction;
    }
}
