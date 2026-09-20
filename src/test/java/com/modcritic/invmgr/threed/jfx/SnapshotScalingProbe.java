package com.modcritic.invmgr.threed.jfx;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.threed.CameraPose;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javafx.animation.AnimationTimer;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.SubScene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The other way of drawing the 3D view at fewer pixels: render the room into a smaller image each
 * frame and show that image stretched back over the view.
 *
 * <p>{@code SubSceneResolutionProbe} established that shrinking a {@code SubScene} and stretching
 * it back does nothing at all. This is the remaining candidate, because {@code snapshot} with a
 * scaling {@code SnapshotParameters} is documented to rasterize at the size asked for.
 *
 * <h2>⚠ It drives the real renderer, and the first version of it did not</h2>
 *
 * <p>This probe originally built its own scene: forty big flat quads stacked in Z. That scene is
 * <b>one wall filling the whole view</b>, because the nearest quad is 700 units tall at 418 units
 * from the camera. Every pixel of it is the same color, which has two consequences that together
 * produced a result that could not be true:
 *
 * <ul>
 *   <li><b>Shrinking a flat picture and stretching it back changes nothing</b>, so the screen grabs
 *       came back identical and were read as "the reduction never happened".
 *   <li><b>Drawing a flat picture costs almost nothing</b>, so the frame times fell and were read as
 *       "the reduction worked".
 * </ul>
 *
 * <p>Neither reading was about snapshot at all. The scene was measured directly and admitted it:
 * mean step between neighboring pixels <b>0.00 out of 255</b>, for the whole scene, at full size.
 * <b>A probe whose scene is a solid color cannot answer a question about resolution</b>, and this
 * one now drives {@link JfxRenderer3D} on a 200 ft room instead: the actual thing the user's virtual
 * machine is slow at, grid lines, vignette, boundary lines and all.
 *
 * <h2>Both halves get a control</h2>
 *
 * <p>{@link #doesTheScaleTransformActuallyReduceTheRaster} is the pixel control, and it needs no
 * camera outside the process: take the same room at full size and at a quarter, then ask which
 * alignment the small one matches. If the scale really applies, small(x,y) lines up with
 * big(4x,4y); if it is ignored and the picture cropped, small(x,y) lines up with big(x,y). It
 * prints the detail in each image first, because <b>a flat image matches anything</b> and that is
 * how the earlier version fooled itself.
 *
 * <p>{@link #isSnapshotScalingCheaperThanJustDrawing} is the timing control: a fourth arm snapshots
 * at <b>full size</b> and shows that stretched. Same machinery, same {@code ImageView}, no
 * reduction. Anything it already saves was never about pixels.
 *
 * <p>What is established either way: this machine runs the same {@code llvmpipe (LLVM 22.1.8)}
 * software rasterizer as the virtual machine the whole question is about, so whatever is true here
 * about pixel counts is true there. What differs is how much processor is behind it.
 *
 * <p>Run it deliberately: {@code mvn -B test -Dtest=SnapshotScalingProbe}.
 */
class SnapshotScalingProbe extends ApplicationTest {

    private static final int VIEW_W = 1200;
    private static final int VIEW_H = 800;
    private static final double ROOM_FT = 200;
    private static final double EYE_HEIGHT_FT = 6;
    private static final double STAND_BACK_FT = 4;
    private static final long SLICE_MS = 800;
    private static final int SLICES = 5;

    private final StackPane host = new StackPane();
    private final CameraPose pose = new CameraPose();
    private JfxRenderer3D renderer;
    private Scene scene;
    private SubScene sub;
    private ImageView shown;

    @Override
    public void start(Stage stage) {
        scene = new Scene(host, VIEW_W, VIEW_H);
        stage.setScene(scene);
        stage.setX(0);
        stage.setY(0);
        stage.show();

        AppState state = new AppState();
        state.room.w = ROOM_FT;
        state.room.l = ROOM_FT;
        state.room.h = 10;

        renderer = new JfxRenderer3D();
        renderer.init(VIEW_W, VIEW_H);
        renderer.build(state);

        // Standing near one end looking down the length, which is the view with the most room in
        // it and the one the slow machine was reported on.
        pose.x = ROOM_FT / 2;
        pose.y = EYE_HEIGHT_FT;
        pose.z = ROOM_FT - STAND_BACK_FT;
        pose.yaw = 0;
        pose.pitch = Math.toRadians(-8);
        renderer.render(pose);

        sub = renderer.node();
        shown = new ImageView();
        shown.setFitWidth(VIEW_W);
        shown.setFitHeight(VIEW_H);
        host.getChildren().setAll(sub);
    }

    /**
     * <b>The pixel control.</b> Does a scaling {@code SnapshotParameters} rasterize the room at the
     * smaller size, or render it normally and hand back a smaller picture of the same raster?
     *
     * <p>Nothing outside the process is involved: the two images are compared against each other at
     * both alignments, and the answer is whichever one matches. The detail figures come first
     * because they are the check that there is anything in the pictures to compare.
     */
    @Test
    @DisplayName("a quarter-size snapshot: really a quarter-size render, or the same one cropped?")
    void doesTheScaleTransformActuallyReduceTheRaster() {
        Image big = take(1.0);
        Image small = take(0.25);
        write(big, "snapshot-full");
        write(small, "snapshot-quarter");

        System.out.printf("PROBE full size came back %.0f x %.0f, quarter came back %.0f x %.0f%n",
                big.getWidth(), big.getHeight(), small.getWidth(), small.getHeight());
        System.out.printf("PROBE detail in each (mean step between neighbors, out of 255): "
                + "full %.2f, quarter %.2f%n", detail(big), detail(small));
        System.out.println("PROBE ⚠ if either of those is near zero the picture is blank and "
                + "nothing below it means anything");

        System.out.printf("PROBE quarter against full at 4:1 (the scale really applied)  %.2f%n",
                compare(small, big, 4));
        System.out.printf("PROBE quarter against full at 1:1 (it was cropped instead)    %.2f%n",
                compare(small, big, 1));
        System.out.println("PROBE whichever of those is near zero is what snapshot actually did");
    }

    /** One snapshot of the room at the given scale, into a buffer sized for it. */
    private Image take(double scale) {
        int w = (int) Math.max(1, Math.round(VIEW_W * scale));
        int h = (int) Math.max(1, Math.round(VIEW_H * scale));
        WritableImage buffer = new WritableImage(w, h);
        SnapshotParameters params = new SnapshotParameters();
        params.setTransform(new Scale(scale, scale));
        params.setFill(Color.TRANSPARENT);
        Image[] out = new Image[1];
        interact(() -> out[0] = sub.snapshot(params, buffer));
        WaitForAsyncUtils.waitForFxEvents();
        return out[0];
    }

    /**
     * Mean color difference between the small image and the big one, sampling the big one every
     * {@code step} pixels. A step of 4 tests "the quarter-size image is this view at a quarter the
     * resolution"; a step of 1 tests "it is the top-left corner of the full-size render".
     */
    private static double compare(Image small, Image big, int step) {
        PixelReader a = small.getPixelReader();
        PixelReader b = big.getPixelReader();
        int w = (int) small.getWidth();
        int h = (int) small.getHeight();
        double total = 0;
        long counted = 0;
        for (int y = 0; y < h && y * step < big.getHeight(); y++) {
            for (int x = 0; x < w && x * step < big.getWidth(); x++) {
                total += difference(a.getColor(x, y), b.getColor(x * step, y * step));
                counted++;
            }
        }
        return counted == 0 ? -1 : total / counted;
    }

    /**
     * How much the picture changes from one pixel to the next, averaged, out of 255.
     *
     * <p><b>This is the check the first version of this probe did not have.</b> A blank or flat
     * image reads near zero here, and a flat image matches every other image, so without this the
     * comparison above reports a perfect match on two pictures of nothing.
     */
    private static double detail(Image image) {
        PixelReader pixels = image.getPixelReader();
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        double total = 0;
        long counted = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 1; x < w; x++) {
                total += difference(pixels.getColor(x - 1, y), pixels.getColor(x, y));
                counted++;
            }
        }
        return counted == 0 ? -1 : total / counted;
    }

    private static double difference(Color one, Color two) {
        return (Math.abs(one.getRed() - two.getRed())
                + Math.abs(one.getGreen() - two.getGreen())
                + Math.abs(one.getBlue() - two.getBlue())) / 3 * 255;
    }

    /**
     * Holds one approach on screen so the picture can be photographed from outside the process.
     *
     * <p><b>Takes a yaw as well as a scale, and that is not decoration.</b> Two grabs at different
     * yaws must differ, or the camera is not photographing this window and a pair of identical
     * grabs means nothing. That control was missing when this probe first reported identical pixels.
     *
     * <pre>
     *   mvn -B test -Dtest=SnapshotScalingProbe#holdForAPhotograph \
     *       -DargLine="-Dprobe.scale=0.25 -Dprobe.yaw=0"
     * </pre>
     */
    @Test
    void holdForAPhotograph() {
        String asked = System.getProperty("probe.scale");
        if (asked == null) {
            System.out.println("PROBE no -Dprobe.scale given, nothing to hold");
            return;
        }
        double scale = Double.parseDouble(asked);
        double yaw = Double.parseDouble(System.getProperty("probe.yaw", "0"));
        interact(() -> {
            pose.yaw = Math.toRadians(yaw);
            renderer.render(pose);
        });
        WaitForAsyncUtils.waitForFxEvents();

        if (scale >= 1) {
            interact(() -> host.getChildren().setAll(sub));
        } else {
            int w = (int) Math.round(VIEW_W * scale);
            int h = (int) Math.round(VIEW_H * scale);
            WritableImage buffer = new WritableImage(w, h);
            SnapshotParameters params = new SnapshotParameters();
            params.setTransform(new Scale(scale, scale));
            params.setFill(Color.TRANSPARENT);
            interact(() -> {
                shown.setImage(sub.snapshot(params, buffer));
                host.getChildren().setAll(shown);
            });
        }
        WaitForAsyncUtils.waitForFxEvents();
        System.out.printf("PROBE holding at scale %.4f, yaw %.1f degrees%n", scale, yaw);
        long until = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < until) {
            WaitForAsyncUtils.sleep(50, java.util.concurrent.TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    @Test
    @DisplayName("is going through a smaller snapshot cheaper than drawing the room directly?")
    void isSnapshotScalingCheaperThanJustDrawing() {
        List<Long> direct = new ArrayList<>();
        List<Long> viaFull = new ArrayList<>();
        List<Long> viaHalf = new ArrayList<>();
        List<Long> viaQuarter = new ArrayList<>();

        // Round robin, because this machine drifts more than several of the differences this
        // milestone has had to resolve.
        List<Long> quarterRough = new ArrayList<>();
        List<Long> halfRough = new ArrayList<>();
        for (int slice = 0; slice < SLICES; slice++) {
            spinDirect(direct);
            spinThroughSnapshot(1.0, viaFull, true);
            spinThroughSnapshot(0.5, viaHalf, true);
            spinThroughSnapshot(0.25, viaQuarter, true);
            spinThroughSnapshot(0.5, halfRough, false);
            spinThroughSnapshot(0.25, quarterRough, false);
        }

        report("drawn straight to the screen", direct);
        report("snapshot at FULL size, stretched", viaFull);
        report("snapshot at half, smooth upscale", viaHalf);
        report("snapshot at half, ROUGH upscale", halfRough);
        report("snapshot at a quarter, smooth", viaQuarter);
        report("snapshot at a quarter, ROUGH", quarterRough);
        System.out.println("PROBE the full-size arm is the control: same machinery as the other "
                + "two minus the reduction, so whatever it saves was never about pixels");
    }

    /** The room drawing itself, which is what the app does today. */
    private void spinDirect(List<Long> into) {
        interact(() -> host.getChildren().setAll(sub));
        WaitForAsyncUtils.waitForFxEvents();
        count(into, () -> {
            pose.yaw += 0.01;
            renderer.render(pose);
        });
    }

    /**
     * The room rendered into an image each frame, and that image shown stretched to fill the view.
     *
     * <p>The image is made once and written into every frame, because allocating a fresh one per
     * frame would measure the allocator rather than the drawing.
     */
    private void spinThroughSnapshot(double scale, List<Long> into, boolean smooth) {
        shown.setSmooth(smooth);
        int w = (int) Math.max(1, Math.round(VIEW_W * scale));
        int h = (int) Math.max(1, Math.round(VIEW_H * scale));
        WritableImage buffer = new WritableImage(w, h);
        SnapshotParameters params = new SnapshotParameters();
        params.setTransform(new Scale(scale, scale));
        params.setFill(Color.TRANSPARENT);

        interact(() -> host.getChildren().setAll(shown));
        WaitForAsyncUtils.waitForFxEvents();
        count(into, () -> {
            pose.yaw += 0.01;
            renderer.render(pose);
            shown.setImage(sub.snapshot(params, buffer));
        });
    }

    private void count(List<Long> into, Runnable eachFrame) {
        List<Long> frames = new ArrayList<>();
        AnimationTimer clock = new AnimationTimer() {
            private long previous;

            @Override
            public void handle(long now) {
                if (previous != 0) {
                    frames.add((now - previous) / 1_000_000);
                }
                previous = now;
                eachFrame.run();
            }
        };
        interact(clock::start);
        long until = System.currentTimeMillis() + SLICE_MS;
        while (System.currentTimeMillis() < until) {
            WaitForAsyncUtils.sleep(10, java.util.concurrent.TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
        interact(clock::stop);
        if (!frames.isEmpty()) {
            into.addAll(frames.subList(1, frames.size()));
        }
    }

    private static void report(String what, List<Long> frames) {
        if (frames.isEmpty()) {
            System.out.println("PROBE " + what + ": no frames");
            return;
        }
        List<Long> sorted = new ArrayList<>(frames);
        sorted.sort(null);
        System.out.printf("PROBE %-36s %4d frames, median %2d ms, mean %6.2f ms%n",
                what, frames.size(), sorted.get(sorted.size() / 2),
                frames.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    private static void write(Image image, String name) {
        try {
            File directory = new File("target/screenshots");
            if (directory.isDirectory() || directory.mkdirs()) {
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                        new File(directory, name + ".png"));
            }
        } catch (IOException e) {
            System.err.println("could not write screenshot " + name + ": " + e.getMessage());
        }
    }
}
