package com.modcritic.invmgr.threed;

/**
 * One item's box in the 3D world: two opposite corners, in <b>feet</b>, plus the item it came
 * from so a hover can name it.
 *
 * <p><b>Feet, not the inches and pixels the rest of the app stores.</b> The model keeps item
 * dimensions in inches and positions in pixels at 96 px per foot, because that is what the 2D
 * canvas draws in; the 3D world is in feet because that is what the camera moves in: walking
 * speed is 9 ft/s and the room's own dimensions are already feet. Converting once, here, is what
 * stops a stray factor of 8 or 96 turning up in the middle of the camera code.
 *
 * <p>The axes follow {@code SPEC-3D-VIEW.md} §1: <b>x is east, z is south, y is up</b>. That is
 * <em>not</em> what JavaFX uses (its y points down) and the conversion happens once, at the
 * point the mesh is built, rather than being smeared through this class. Everything here is in
 * spec coordinates, which are also the coordinates the original's own code used, so the numbers
 * can be compared against it directly.
 */
public final class Box3D {

    /** The item's id, so a ray hit can be traced back to the thing that was hit. */
    public final String itemId;

    /** The item's color string, still in the {@code hsl(H,S%,L%)} form it was saved in. */
    public final String color;

    /** West edge, in feet from the room's north-west corner. */
    public final double x0;

    /** East edge, in feet. Always greater than {@link #x0}: items cannot have zero width. */
    public final double x1;

    /** Underside, in feet above the true floor. This is the item's stacked base height. */
    public final double y0;

    /** Top, in feet. */
    public final double y1;

    /** North edge, in feet from the room's north-west corner. */
    public final double z0;

    /** South edge, in feet. */
    public final double z1;

    Box3D(String itemId, String color,
            double x0, double x1, double y0, double y1, double z0, double z1) {
        this.itemId = itemId;
        this.color = color;
        this.x0 = x0;
        this.x1 = x1;
        this.y0 = y0;
        this.y1 = y1;
        this.z0 = z0;
        this.z1 = z1;
    }

    /** Width, east to west, in feet. */
    public double width() {
        return x1 - x0;
    }

    /** Height, in feet. */
    public double height() {
        return y1 - y0;
    }

    /** Length, north to south, in feet. */
    public double length() {
        return z1 - z0;
    }
}
