package com.modcritic.invmgr.threed;

/**
 * How big a wall or floor's vignette picture is, and how far its darkening reaches. Every number
 * that decides what gets drawn, with none of the drawing.
 *
 * <p>Split off from the drawing on purpose. The arithmetic is where the mistakes are, and it can
 * be compared against the original's own output exactly, in a test with no window in it. The
 * drawing is then a short, boring routine that puts paint where these numbers say.
 *
 * <p>Port of the sizing half of {@code drawSurfaceCanvas} (original lines 2830-2841). That
 * function is worth knowing about: it is the surface painter the original wrote <b>for a real 3D
 * renderer</b> and then never shipped, because the CSS3D version had to tile its walls instead.
 * The comment above it says it is "kept for when that variant is revisited". This is that.
 *
 * <h2>⚠ This used to describe the grid as well, and at M6.7 it stopped</h2>
 *
 * <p>A surface was one picture: flat gray, a one-foot grid, and a darkening toward the edges, all
 * painted together and stretched over the whole wall. The grid has moved to {@link GridTile},
 * which repeats one square foot at full resolution instead, because the cap below is what made a
 * large room blurry and a periodic pattern does not need a picture of the whole floor.
 *
 * <p>What is left here is the vignette, and it is the half the cap never hurt: a radial gradient
 * has no detail to lose, so being drawn at a tenth of the resolution costs it nothing.
 *
 * <p><b>The cap did come down, on 2026-09-15.</b> It was 2048, the original's own number, and a
 * 200 ft floor allocated 2048 x 2048 to hold a smooth gradient: 16 MB to say something a few
 * hundred pixels could say, on the same graphics memory B11 ran out of on the phone. It is now
 * {@link #MAX_TEXTURE_PX} = 512, which is the one number in this class that no longer matches the
 * original; that field's own documentation carries the measurement and what it bought. OI-4
 * closed with it.
 */
public final class SurfaceMetrics {

    /**
     * The most detail a vignette is drawn at, in pixels per foot.
     *
     * <p>96 is the app's own scale, the same 96 pixels to the foot the 2D canvas draws at. In
     * practice only a small room ever reaches it, and it would not matter if none did: see the
     * class documentation for why a gradient does not care.
     */
    public static final double MAX_PX_PER_FOOT = GridTile.PX_PER_FOOT;

    /**
     * The longest a vignette may be along either side, in pixels.
     *
     * <p><b>512, down from the original's 2048 at M6.7, and it is the one number here that no
     * longer matches the original.</b> That 2048 was the reason a large room's grid used to be
     * blurry: a 200 ft wall at 96 px/ft would be 19,200 pixels across, so the cap traded detail
     * for being able to draw the room at all. The grid stopped paying that price when it moved to
     * {@link GridTile}, which left this picture carrying a smooth radial gradient and nothing
     * else, and a gradient has no detail for a cap to take away.
     *
     * <p><b>Measured rather than asserted.</b> {@code SurfaceTextureTest} paints the same surface
     * at this cap and at 2048 and compares them at the same fractional positions: <b>the worst
     * pixel differs by 1 of 255</b>, which is the smallest difference an 8-bit picture can hold, so
     * the two are the same gradient to the limit of what the format can express. 256 was tried
     * too and reaches 2 of 255, still invisible and a few milliseconds cheaper again; 512 is
     * chosen because "identical within rounding" is a stronger thing to be able to say and the
     * milliseconds are not the bottleneck.
     *
     * <p><b>What it bought.</b> Building a 200 ft room went from 41.4 ms to 14.0 ms and a 30 ft
     * room from 69.6 ms to 16.0 ms, and about 15 MB of graphics memory per room came back. That is
     * the same pool B11 ran out of on a phone, on a room far smaller than 200 ft.
     */
    public static final double MAX_TEXTURE_PX = 512;

    /** How far out the vignette reaches, as a fraction of the surface's longer side. */
    public static final double VIGNETTE_RADIUS_FRACTION = 0.7;

    /** How dark the vignette gets at its outer edge. */
    public static final double VIGNETTE_MAX_ALPHA = 0.7;

    /** Texture resolution, in pixels per foot. */
    public final double pixelsPerFoot;

    /** Texture width, in pixels. */
    public final int widthPx;

    /** Texture height, in pixels. */
    public final int heightPx;

    /** Radius of the vignette, in texture pixels. */
    public final double vignetteRadiusPx;

    private SurfaceMetrics(double pixelsPerFoot, int widthPx, int heightPx,
            double vignetteRadiusPx) {
        this.pixelsPerFoot = pixelsPerFoot;
        this.widthPx = widthPx;
        this.heightPx = heightPx;
        this.vignetteRadiusPx = vignetteRadiusPx;
    }

    /**
     * Works out the vignette picture for a surface this many feet across and this many high (or
     * deep, for the floor).
     *
     * <p><b>The two sides are scaled by the same number</b>, so the picture has the surface's own
     * proportions. That is what keeps the vignette a circle on the wall rather than an oval: the
     * radius below is a circle in the picture, and a picture with the wall's proportions stretches
     * onto it without distorting. Scaling the sides independently to fill a square texture is
     * exactly where the OD-1 spike went wrong.
     */
    public static SurfaceMetrics forSurface(double widthFt, double heightFt) {
        return forSurface(widthFt, heightFt, MAX_TEXTURE_PX);
    }

    /**
     * The same, with the cap made an argument instead of a constant.
     *
     * <p>Exists because the cap is a thing worth asking questions about rather than a number to
     * take on trust. {@code SurfaceTextureTest.theSmallerVignetteIsTheSamePicture} builds the same
     * surface at this cap and at the old 2048 and compares them pixel by pixel, which is the
     * evidence for the reduction rather than an argument for it.
     */
    public static SurfaceMetrics forSurface(double widthFt, double heightFt, double maxTexturePx) {
        double s = Math.min(MAX_PX_PER_FOOT, maxTexturePx / Math.max(widthFt, heightFt));
        int w = (int) Math.max(2, Math.round(widthFt * s));
        int h = (int) Math.max(2, Math.round(heightFt * s));
        double vignetteRadius = VIGNETTE_RADIUS_FRACTION * Math.max(w, h);
        return new SurfaceMetrics(s, w, h, vignetteRadius);
    }
}
