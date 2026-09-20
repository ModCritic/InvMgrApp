package com.modcritic.invmgr.threed.jfx;

import com.modcritic.invmgr.threed.RenderScale;
import javafx.scene.Node;
import javafx.scene.image.PixelReader;
import javafx.scene.SnapshotParameters;
import javafx.scene.SubScene;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;

/**
 * Draws the 3D room at fewer pixels than the window, by rendering it into a smaller picture each
 * frame and stretching that picture back over the view.
 *
 * <p>{@link RenderScale} decides <em>whether</em> and <em>how much</em>; this does it. Off by
 * default, and when it is off this class hands back the {@code SubScene} itself and costs nothing
 * at all.
 *
 * <h2>Why a snapshot, when the obvious way is smaller and simpler</h2>
 *
 * <p>The obvious way is to give the {@code SubScene} a smaller width and height and put a
 * {@code Scale} on it. <b>That does nothing</b>, and worse, it looks like it works: the SubScene
 * reports the smaller size, its bounds in the parent report the full size, and the picture on
 * screen comes back <b>byte for byte identical</b> to the unscaled one at a sixteenth the requested
 * size. Prism rasterizes a SubScene's contents at the size they end up occupying on screen, and the
 * declared size only sets the coordinate space inside. That is not documented anywhere; it is what
 * the pixels say, and {@code SubSceneResolutionProbe} is the measurement.
 *
 * <p>A snapshot with a scaling {@code SnapshotParameters} does honor the size asked for. Proven
 * the same way, in {@code SnapshotScalingProbe}: a quarter-size snapshot of a 200 ft room matches
 * the full-size render sampled every fourth pixel (0.53 out of 255) and not its top-left corner
 * (11.15), which are the only two things it could have been.
 *
 * <h2>⚠ What it costs, which is why it is off by default</h2>
 *
 * <p>The snapshot has a fixed cost of its own, measured at <b>2.9 ms a frame</b> on the machine
 * this was written on, whatever size it is taken at. Against an 11 ms frame that eats most of what
 * the reduction saves: half size came out 8% ahead of drawing directly, a quarter 16%. On a machine
 * where the drawing itself costs 99 ms the same fixed cost is a far smaller share, which is the
 * whole reason this exists, but <b>that has not been measured</b> and the default reflects it.
 *
 * <h2>Two things it deliberately does not break</h2>
 *
 * <p><b>Picking still works</b>, because {@code JfxRenderer3D.pickItem} is arithmetic in
 * {@code threed.Picking} against the window's own size and the camera pose. It never asked JavaFX
 * what was under the pointer, so it does not care that what is on screen is an image.
 *
 * <p><b>{@code View3D.subScene()} still returns the live SubScene</b>, so every appearance test
 * that inspects the room by walking its nodes carries on working. The SubScene is still built,
 * still rendered and still up to date; it is just not the thing mounted on screen.
 */
public final class ScaledSurface {

    private final ImageView shown = new ImageView();
    private final SnapshotParameters params = new SnapshotParameters();

    private SubScene source;
    private WritableImage buffer;

    /**
     * Whether the room has ever actually been drawn, which decides whether it can be snapshotted.
     *
     * <p><b>⚠ A SubScene that has never been in the scene graph snapshots as its own fill color and
     * nothing else.</b> Not an error, not a warning, just a flat rectangle the color of the room's
     * background. Measured in {@code DetachedSnapshotProbe}: never mounted gives a mean step
     * between neighboring pixels of <b>0.0000</b>; after a single frame on screen the same call
     * gives 0.3612, and it keeps working after the SubScene is taken back out again.
     *
     * <p>That is what made the 3D view come up empty. {@code View3D.buildRoom} mounts what
     * {@link #node} hands it, so with the reduction switched on the room went straight into an
     * ImageView and was never once on screen. The whole descent showed the screen-wide vignette
     * over nothing. <b>Every test passed</b>, because none of them looks at pixels mid-flight.
     */
    private boolean roomHasBeenDrawn;
    private double factor = RenderScale.FULL;
    private double widthPx = 1;
    private double heightPx = 1;

    public ScaledSurface() {
        // The snapshot is of the SubScene, which paints its own background, so this fill only
        // covers anything the SubScene leaves clear. Transparent keeps it out of the way.
        params.setFill(Color.TRANSPARENT);
    }

    /**
     * Points this at the room to draw, and at the size the window wants it.
     *
     * <p>Called after {@code JfxRenderer3D.init}, because the SubScene does not exist until then.
     */
    public void attach(SubScene source, double widthPx, double heightPx) {
        this.source = source;
        // A different room, which has not been drawn either. See the field's note.
        this.roomHasBeenDrawn = false;
        resize(widthPx, heightPx);
    }

    /** Follows the window. Throws away the picture buffer, which is the wrong size now. */
    public void resize(double widthPx, double heightPx) {
        this.widthPx = Math.max(1, widthPx);
        this.heightPx = Math.max(1, heightPx);
        shown.setFitWidth(this.widthPx);
        shown.setFitHeight(this.heightPx);
        buffer = null;
    }

    /**
     * Sets how much of the window's resolution to draw at, 1.0 being all of it.
     *
     * @return whether {@link #node} now returns something different, so the caller knows to mount
     *     it. Swapping between the SubScene and the picture is the one thing this class cannot do
     *     for itself, because it does not know where it is mounted.
     */
    public boolean setFactor(double factor) {
        Node before = node();
        double wanted = Math.max(RenderScale.SMALLEST, Math.min(RenderScale.FULL, factor));
        if (wanted != this.factor) {
            this.factor = wanted;
            buffer = null;
        }
        return node() != before;
    }

    /** What is being drawn at now. */
    public double factor() {
        return factor;
    }

    /**
     * The node to mount: the room itself when this is off, the picture of it when it is on.
     *
     * <p><b>And the room itself until the room has been drawn once</b>, however the factor is set,
     * because until then there is nothing to take a picture of. See {@link #roomHasBeenDrawn}.
     */
    public Node node() {
        return factor < RenderScale.FULL && roomHasBeenDrawn ? shown : source;
    }

    /**
     * Takes the next picture, if this is on.
     *
     * <p>Call once per frame, straight after the renderer has moved the camera. Does nothing when
     * the factor is 1, which is what makes the off case free.
     *
     * <p>The buffer is made once and written into every frame after that: allocating a fresh image
     * per frame would hand the garbage collector several megabytes a second and measure the
     * allocator rather than the drawing.
     */
    public boolean refresh() {
        if (source == null || factor >= RenderScale.FULL) {
            return false;
        }
        Node before = node();
        if (buffer == null) {
            int w = (int) Math.max(1, Math.round(widthPx * factor));
            int h = (int) Math.max(1, Math.round(heightPx * factor));
            buffer = new WritableImage(w, h);
            params.setTransform(new Scale(factor, factor));
        }
        WritableImage taken = source.snapshot(params, buffer);
        if (!roomHasBeenDrawn) {
            // ⚠ THE PICTURE IS ASKED, NOT ASSUMED. The alternative is to count frames and hope
            // one of them was a real render, and the one place this goes wrong is a synchronous
            // build where no frame has happened yet, which is exactly where counting would be
            // wrong. A picture that is one flat color has not been drawn; staying on the SubScene
            // for another frame costs one unscaled frame and fixes itself.
            if (isOneFlatColor(taken)) {
                return false;
            }
            roomHasBeenDrawn = true;
        }
        shown.setImage(taken);
        return node() != before;
    }

    /**
     * Whether every pixel sampled is the same, which is what a room that has not been drawn looks
     * like: the SubScene's fill and nothing else.
     *
     * <p>Twenty-five points rather than the whole picture, and only while warming up. Once the
     * room has been drawn once this is never called again.
     *
     * <p>A real room could in principle come back flat, from a camera pressed against a wall. The
     * cost of that is one more unscaled frame and another try, so it is worth no more care than
     * this.
     */
    private static boolean isOneFlatColor(WritableImage picture) {
        PixelReader pixels = picture.getPixelReader();
        int w = (int) picture.getWidth();
        int h = (int) picture.getHeight();
        int first = pixels.getArgb(0, 0);
        for (int row = 0; row < 5; row++) {
            for (int column = 0; column < 5; column++) {
                int x = Math.min(w - 1, column * w / 4);
                int y = Math.min(h - 1, row * h / 4);
                if (pixels.getArgb(x, y) != first) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Lets go of the room and the picture, so closing the 3D view frees both. */
    public void dispose() {
        shown.setImage(null);
        source = null;
        buffer = null;
        roomHasBeenDrawn = false;
    }
}
