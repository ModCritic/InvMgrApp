package com.modcritic.invmgr.threed.jfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.threed.CameraPose;
import com.modcritic.invmgr.threed.RenderScale;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * {@link ScaledSurface} against a real room, because the thing it does cannot be checked any other
 * way: whether a picture really came out at fewer pixels is a question about pixels.
 *
 * <p><b>Every assertion here is about content, not about sizes being reported back.</b> That is
 * deliberate and it is the lesson the approach this replaced taught: a {@code SubScene} asked for a
 * sixteenth of the pixels reports every number correctly and draws exactly as many pixels as it did
 * before. Asking an object what it did is worthless when the object is wrong; the picture is the
 * only witness.
 */
class ScaledSurfaceTest extends ApplicationTest {

    private static final int VIEW_W = 600;
    private static final int VIEW_H = 400;

    private final StackPane host = new StackPane();
    private final JfxRenderer3D renderer = new JfxRenderer3D();
    private final ScaledSurface surface = new ScaledSurface();

    @Override
    public void start(Stage stage) {
        stage.setScene(new Scene(host, VIEW_W, VIEW_H));
        stage.show();

        AppState state = new AppState();
        state.room.w = 40;
        state.room.l = 40;
        state.room.h = 10;

        renderer.init(VIEW_W, VIEW_H);
        renderer.build(state);
        renderer.render(new CameraPose(20, CameraPose.EYE_HEIGHT_FT, 36, 0, Math.toRadians(-8)));

        surface.attach(renderer.node(), VIEW_W, VIEW_H);
        host.getChildren().setAll(surface.node());
    }

    @Test
    @DisplayName("switched off, it hands back the room itself and costs nothing")
    void offMeansTheSubSceneItself() {
        assertSame(renderer.node(), surface.node(),
                "with no reduction asked for there is nothing to put in the way");
        assertEquals(RenderScale.FULL, surface.factor(), 1e-9);

        // And refreshing is a no-op rather than a snapshot nobody wanted.
        interact(surface::refresh);
        assertSame(renderer.node(), surface.node());
    }

    @Test
    @DisplayName("switched on, the picture really is smaller and really has the room in it")
    void onMeansAPictureAtTheSizeAskedFor() {
        boolean swapped = swapTo(0.5);
        assertTrue(swapped, "turning it on changes which node is mounted, and the caller needs "
                + "to be told or the room disappears");

        ImageView view = (ImageView) surface.node();
        Image picture = view.getImage();
        assertNotNull(picture, "refresh should have taken one");
        assertEquals(VIEW_W / 2, picture.getWidth(), 0.5);
        assertEquals(VIEW_H / 2, picture.getHeight(), 0.5);

        // ⚠ The size alone proves nothing: a blank image is also 300 x 200. This is the assertion
        // that says the room is in it.
        assertTrue(detail(picture) > 0.01,
                "the picture is flat, so the room was not drawn into it: detail " + detail(picture));

        // And it still fills the window, or the room would sit in a corner at half size.
        assertEquals(VIEW_W, view.getFitWidth(), 1e-9);
        assertEquals(VIEW_H, view.getFitHeight(), 1e-9);
    }

    @Test
    @DisplayName("a quarter really is coarser than a half, rather than the same picture resized")
    void smallerReallyIsCoarser() {
        swapTo(0.5);
        Image half = ((ImageView) surface.node()).getImage();
        double halfDetail = detail(half);

        swapTo(0.25);
        Image quarter = ((ImageView) surface.node()).getImage();

        assertEquals(VIEW_W / 4, quarter.getWidth(), 0.5);
        assertTrue(detail(quarter) > 0.01, "still has to have the room in it");
        // Fewer, larger pixels across the same view means more change between neighbors, because
        // detail that used to be spread over four pixels is now in one. The direction is the
        // evidence; the exact ratio depends on the room and is not worth pinning.
        assertTrue(detail(quarter) > halfDetail,
                "a quarter-size render should be busier per pixel than a half-size one: "
                        + detail(quarter) + " against " + halfDetail);
    }

    @Test
    @DisplayName("going back to full puts the room itself back on screen")
    void turningItOffAgainSwapsBack() {
        swapTo(0.5);
        assertTrue(surface.node() instanceof ImageView);

        assertTrue(surface.setFactor(RenderScale.FULL), "and says the node changed");
        assertSame(renderer.node(), surface.node());
    }

    @Test
    @DisplayName("moving between two reduced sizes does not change which node is mounted")
    void steppingDownDoesNotNeedRemounting() {
        swapTo(0.5);
        // Both are the same ImageView, so the caller is told nothing changed and does not touch
        // the scene graph mid-flight. The buffer inside is still rebuilt.
        assertFalse(surface.setFactor(0.25));
        interact(surface::refresh);
        assertEquals(VIEW_W / 4, ((ImageView) surface.node()).getImage().getWidth(), 0.5);
    }

    @Test
    @DisplayName("a value below the floor is clamped rather than taken literally")
    void itWillNotGoBelowTheFloor() {
        swapTo(0.05);
        assertEquals(RenderScale.SMALLEST, surface.factor(), 1e-9);
    }

    @Test
    @DisplayName("disposing lets go of the picture, so closing the room frees it")
    void disposeLetsGo() {
        swapTo(0.5);
        ImageView view = (ImageView) surface.node();

        interact(surface::dispose);
        assertNull(view.getImage(), "several megabytes, held until the next time 3D opens");
        // And refreshing after that does nothing rather than throwing on the room it let go of.
        interact(surface::refresh);
        assertNull(view.getImage());
    }


    @Test
    @DisplayName("⚠ a room that has never been on screen is left alone until it has")
    void itWillNotShowAPictureOfARoomThatWasNeverDrawn() {
        // THIS IS THE TEST THE BLANK DESCENT BOUGHT. A SubScene that has never been in the scene
        // graph snapshots as its own fill color and nothing else: no error, no warning, a flat
        // rectangle. View3D.buildRoom mounts whatever node() hands it, so with the reduction on
        // the room went straight into an ImageView, was never once drawn, and the entire descent
        // showed the vignette over nothing. Every test in the project passed, because none of
        // them looks at pixels while the camera is moving.
        //
        // DetachedSnapshotProbe is the measurement: never mounted 0.0000, after one frame 0.3612.
        JfxRenderer3D cold = new JfxRenderer3D();
        ScaledSurface fresh = new ScaledSurface();
        AppState state = new AppState();
        state.room.w = 40;
        state.room.l = 40;
        state.room.h = 10;

        interact(() -> {
            cold.init(VIEW_W, VIEW_H);
            cold.build(state);
            cold.render(new CameraPose(20, CameraPose.EYE_HEIGHT_FT, 36, 0, Math.toRadians(-8)));
            // Attached and switched on, but never mounted anywhere.
            fresh.attach(cold.node(), VIEW_W, VIEW_H);
            fresh.setFactor(0.25);
        });
        WaitForAsyncUtils.waitForFxEvents();

        boolean[] swapped = new boolean[1];
        interact(() -> swapped[0] = fresh.refresh());
        assertFalse(swapped[0], "it must not ask to be mounted with a blank picture");
        assertSame(cold.node(), fresh.node(),
                "and node() must still be the room itself, or the view goes empty");

        // Put it on screen for a frame, and it should take the reduction from then on.
        interact(() -> host.getChildren().setAll(fresh.node()));
        WaitForAsyncUtils.waitForFxEvents();

        boolean[] nowSwapped = new boolean[1];
        interact(() -> nowSwapped[0] = fresh.refresh());
        assertTrue(nowSwapped[0], "once the room has been drawn it should ask to be swapped in");
        assertTrue(fresh.node() instanceof ImageView);
        assertTrue(detail(((ImageView) fresh.node()).getImage()) > 0.01,
                "and the picture must have the room in it");

        interact(fresh::dispose);
    }

    @Test
    @DisplayName("refresh says when the node changed, and only on the frame it changed")
    void refreshReportsTheSwapExactlyOnce() {
        swapTo(0.5);
        boolean[] again = new boolean[1];
        interact(() -> again[0] = surface.refresh());
        assertFalse(again[0], "a steady state must not ask the caller to touch the scene graph");
    }

    /** Sets the factor and draws one frame, returning whether the mounted node changed. */
    private boolean swapTo(double factor) {
        boolean[] changed = new boolean[1];
        interact(() -> {
            changed[0] = surface.setFactor(factor);
            changed[0] |= surface.refresh();
            host.getChildren().setAll(surface.node());
        });
        WaitForAsyncUtils.waitForFxEvents();
        return changed[0];
    }

    /**
     * How much the picture changes from one pixel to the next, averaged, out of 255.
     *
     * <p>Near zero means blank or flat, and <b>a flat picture passes every other check here</b>,
     * which is exactly how the probe that came before this fooled itself for an afternoon.
     */
    private static double detail(Image image) {
        PixelReader pixels = image.getPixelReader();
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        double total = 0;
        long counted = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 1; x < w; x++) {
                Color one = pixels.getColor(x - 1, y);
                Color two = pixels.getColor(x, y);
                total += (Math.abs(one.getRed() - two.getRed())
                        + Math.abs(one.getGreen() - two.getGreen())
                        + Math.abs(one.getBlue() - two.getBlue())) / 3 * 255;
                counted++;
            }
        }
        return counted == 0 ? 0 : total / counted;
    }
}
