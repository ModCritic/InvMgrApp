package com.modcritic.invmgr.threed.jfx;

import com.modcritic.invmgr.threed.GridTile;
import com.modcritic.invmgr.threed.SurfaceMetrics;
import com.modcritic.invmgr.ui.Tokens;
import java.util.List;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

/**
 * Paints the two pictures a wall or the floor is made of: one square of grid, repeated across the
 * surface, and a soft darkening toward the edges laid over the top.
 *
 * <p>All the arithmetic (how big, how far apart, how thick, how far the darkening reaches) is in
 * {@link GridTile} and {@link SurfaceMetrics}, where it can be compared against the original's
 * numbers without a graphics card in the room. What is left here is the painting, which is short
 * and dull on purpose.
 *
 * <p>It is painted the same way the flat top-down view paints its floor: on a JavaFX canvas, with
 * the same fill, the same grid color and the same kind of gradient. That is deliberate: the two
 * views are meant to look like the same room seen two ways, and the surest way to keep them
 * agreeing is to draw them with the same tools and the same tokens.
 *
 * <h2>Why two pictures and not one</h2>
 *
 * <p>They cannot be one, and the reason is worth stating because it looks like an arbitrary
 * split. The grid repeats and the darkening does not. A repeating texture is what makes the grid
 * sharp at any room size (see {@link GridTile}), and a picture that repeats every foot cannot also
 * carry a gradient that spans the whole wall.
 *
 * <p>The order they go on in is not arbitrary either. The original paints the grid and <em>then</em>
 * the darkening, so the lines fade into the corners along with everything else rather than staying
 * bright over a darkened surface. Two quads reproduce that by putting the vignette a hair nearer
 * the room than the grid; {@code JfxRenderer3D.surfaceQuads} owns that offset and the measurement
 * behind it.
 */
public final class SurfaceTexture {

    /** The color the darkening fades toward at the outer edge. */
    private static final Color VIGNETTE_EDGE = Color.rgb(17, 17, 17, SurfaceMetrics.VIGNETTE_MAX_ALPHA);

    /** The color the darkening starts from in the middle, which is to say nothing at all. */
    private static final Color VIGNETTE_CENTER = Color.rgb(85, 85, 85, 0);

    /**
     * The one tile each way, kept because every surface uses the same one and a room rebuild would
     * otherwise repaint it five times over.
     *
     * <p>Safe to hold on to: an {@code Image} is immutable once painted, and both of these are
     * only ever read. Indexed by whether the grid is metric.
     */
    private static final Image[] TILES = new Image[2];

    private SurfaceTexture() {
    }

    /**
     * One square of grid, at the app's full 96 pixels to the foot however big the room is.
     *
     * <p>Must be called on the JavaFX thread, because taking a picture of a canvas is something
     * only the interface thread may do.
     *
     * <p><b>The lines are drawn in halves, at both edges rather than one.</b> That is what puts a
     * line's middle on the whole-foot mark once the tile repeats: the right-hand half of one tile
     * meets the left-hand half of the next and the two make a single line centered on the seam.
     * Drawing one whole line at the left edge instead would put every line a pixel off, which is
     * an eighth of an inch in the room and would show up against the flat view.
     *
     * @param metric whether the grid is drawn a meter apart instead of a foot
     */
    public static Image gridTile(boolean metric) {
        int slot = metric ? 1 : 0;
        if (TILES[slot] != null) {
            return TILES[slot];
        }
        GridTile tile = GridTile.forGrid(metric);
        Canvas canvas = new Canvas(tile.tilePx, tile.tilePx);
        GraphicsContext g = canvas.getGraphicsContext2D();

        g.setFill(Tokens.ROOM_FILL);
        g.fillRect(0, 0, tile.tilePx, tile.tilePx);

        double half = GridTile.LINE_WIDTH_PX / 2;
        g.setFill(Tokens.GRID_LINE);
        g.fillRect(0, 0, half, tile.tilePx);
        g.fillRect(tile.tilePx - half, 0, half, tile.tilePx);
        g.fillRect(0, 0, tile.tilePx, half);
        g.fillRect(0, tile.tilePx - half, tile.tilePx, half);

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        Image image = canvas.snapshot(params, null);
        reportIfBlank(image, tile.tilePx, tile.tilePx, "grid tile");
        TILES[slot] = image;
        return image;
    }

    /**
     * The darkening that goes over a surface, transparent in the middle and reaching
     * {@link SurfaceMetrics#VIGNETTE_MAX_ALPHA} at the outside.
     *
     * <p>Must be called on the JavaFX thread, for the same reason as above.
     *
     * <p>A circle, not an oval. The radius is in pixels and is measured from the surface's longer
     * side, so a long wall gets a round pool of light in the middle rather than a stretched one,
     * which is what the original does, and is where the OD-1 spike went wrong.
     */
    public static Image vignette(double widthFt, double heightFt) {
        return vignette(widthFt, heightFt, SurfaceMetrics.MAX_TEXTURE_PX);
    }

    /** The same, at a stated cap, so a test can photograph two sizes of the same gradient. */
    public static Image vignette(double widthFt, double heightFt, double maxTexturePx) {
        SurfaceMetrics metrics = SurfaceMetrics.forSurface(widthFt, heightFt, maxTexturePx);
        Canvas canvas = new Canvas(metrics.widthPx, metrics.heightPx);
        GraphicsContext g = canvas.getGraphicsContext2D();

        g.setFill(new RadialGradient(0, 0,
                metrics.widthPx / 2.0, metrics.heightPx / 2.0, metrics.vignetteRadiusPx,
                false, CycleMethod.NO_CYCLE,
                List.of(new Stop(0, VIGNETTE_CENTER), new Stop(1, VIGNETTE_EDGE))));
        g.fillRect(0, 0, metrics.widthPx, metrics.heightPx);

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        return canvas.snapshot(params, null);
    }

    /**
     * Says so, loudly, when the snapshot above did not actually draw.
     *
     * <p><b>Why this exists (B11).</b> {@code snapshot} hands its work to the render thread and
     * waits. If the renderer cannot allocate a buffer for it, the failure is thrown and logged
     * <em>there</em> and this method still gets an {@code Image} back, so the 3D room is built
     * from a blank texture and the floor comes out dark with nothing anywhere saying why. That is
     * exactly what a phone did with a 30 ft x 40 ft room, and the only trace was one
     * {@code NullPointerException} in {@code NGCanvas$RenderBuf.validate} that named neither this
     * class nor the surface it was drawing.
     *
     * <p><b>It watches the tile and not the vignette, and that is the right way round now.</b> The
     * vignette is <em>supposed</em> to be transparent in the middle, so the old center-pixel test
     * would call every one of them a failure. The tile is filled edge to edge with
     * {@link Tokens#ROOM_FILL} before anything else is drawn and the snapshot's own fill is
     * transparent, so a transparent center there still means the fill never happened.
     *
     * <p>M6.7 made the thing it guards somewhat less likely and did not remove it: the grid no
     * longer needs a picture of the whole wall, but the vignette still takes one, and that is
     * still up to 2048 x 2048 five times over. See OI-4 for the reduction that is sitting there
     * unclaimed. The check stays because a dark floor with no explanation cost a day once already.
     *
     * <p>It reports rather than throws, deliberately. A dark floor is bad; a 3D view that refuses
     * to open at all is worse, and D-10 already decided which way that trade goes for the 3D
     * button. The line is written so a logcat can be searched for it.
     */
    private static void reportIfBlank(Image image, int widthPx, int heightPx, String what) {
        if (image == null) {
            System.out.println("InvMgr: ⚠ 3D " + what + " came back null at "
                    + widthPx + "x" + heightPx);
            return;
        }
        PixelReader pixels = image.getPixelReader();
        if (pixels == null) {
            System.out.println("InvMgr: ⚠ 3D " + what + " is unreadable at "
                    + widthPx + "x" + heightPx);
            return;
        }
        if (pixels.getColor((int) image.getWidth() / 2, (int) image.getHeight() / 2)
                .getOpacity() > 0) {
            return;
        }
        System.out.println("InvMgr: ⚠ 3D " + what.toUpperCase(java.util.Locale.ROOT)
                + " DID NOT RENDER: " + widthPx + "x" + heightPx
                + " came back transparent, so every surface will draw black.");
        System.out.println("InvMgr:   Almost certainly out of graphics memory rather than a "
                + "drawing fault. A big room's 2D floor takes the pool first; see B11.");
    }
}
