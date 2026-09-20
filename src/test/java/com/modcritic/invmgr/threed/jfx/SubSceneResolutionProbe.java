package com.modcritic.invmgr.threed.jfx;

import java.util.ArrayList;
import java.util.List;
import javafx.animation.AnimationTimer;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * <b>Can a JavaFX {@code SubScene} be drawn at fewer pixels than it occupies? No.</b> This is the
 * measurement that says so, kept because the idea is an obvious one and somebody will have it
 * again.
 *
 * <h2>Why anyone would want to</h2>
 *
 * <p>M6.7 item 3 measured the 3D room as short of fill rate and nothing else: five hundred boxes,
 * the save format's own cap, cost under a millisecond to draw. Drawing the room smaller than the
 * window and letting it stretch back up is the standard answer to that, and the user asked for it
 * after a virtual machine running software OpenGL came back at ten frames a second.
 *
 * <p>The obvious way is to give the {@code SubScene} a smaller width and height and put a
 * {@code Scale} on it. <b>It does not work, and the reason it is worth a probe is that it LOOKS
 * like it works</b>: the SubScene really does report the smaller size, its bounds in the parent
 * really are the full size, and nothing anywhere reports a problem.
 *
 <h2>What was measured, on two different scenes, agreeing</h2>
 *
 * <p><b>The picture is identical, and that is the measurement that settles it.</b> Real screen
 * grabs, taken with {@code ffmpeg} from outside the process because nothing inside it can see this:
 *
 * <ul>
 *   <li>The app's own 200 x 200 ft room in a 1280 x 800 window, at full size and at 640 x 400
 *       stretched back over it: <b>byte for byte the same</b>. At 80 x 50, stretched sixteen
 *       times: <b>still byte for byte the same</b>.
 *   <li>This probe's sixty spinning boxes at 1000 x 700, against <b>63 x 44 stretched to 1008 px
 *       wide</b>: byte for byte the same again.
 * </ul>
 *
 * <p>A picture really rasterized at 63 x 44 and magnified sixteen times would be unmistakably
 * blocky. Nothing was saved because nothing was drawn smaller.
 *
 * <p><b>The frame counts agree, and are the weaker evidence.</b> On the room they showed no
 * difference at all (11.65 ms at full size, 11.28 at half, 12.02 at a quarter, with a quarter the
 * slowest). {@link #askingForFewerPixelsDoesNotGetFewerPixels} on this scene shows a small drift
 * that is easy to over-read as a saving; the pixels say it is not one. <b>Timings can be argued
 * with. Identical bytes cannot.</b>
 *
 * <p><b>⚠ {@code snapshot} is worse than useless here.</b> It re-rasterizes at the image's own
 * resolution, so it returns identical images whether or not any reduction is real, and it returns
 * them quickly enough to feel like evidence. Two hours went into it before a screen grab settled
 * the question.
 *
 * <p>The conclusion is that Prism rasterizes a SubScene's contents at the resolution they end up
 * occupying on the screen, and the declared size only sets the coordinate space inside it. That is
 * not documented anywhere; it is what the pixels say.
 *
 * <p>Run it deliberately: {@code mvn -B test -Dtest=SubSceneResolutionProbe}.
 */
class SubSceneResolutionProbe extends ApplicationTest {

    private static final int VIEW_W = 1000;
    private static final int VIEW_H = 700;
    private static final long SLICE_MS = 700;
    private static final int SLICES = 6;

    private final Group world = new Group();
    private final Pane host = new Pane();
    private final Rotate spin = new Rotate(0, Rotate.Y_AXIS);
    private final Scale stretch = new Scale(1, 1);
    private SubScene sub;

    @Override
    public void start(Stage stage) {
        // Enough geometry and enough overdraw that a real change in resolution would show.
        for (int i = 0; i < 60; i++) {
            Box box = new Box(40, 40, 40);
            box.setTranslateX((i % 10) * 60 - 270);
            box.setTranslateY((i / 10) * 60 - 150);
            box.setTranslateZ(200 + (i % 5) * 40);
            PhongMaterial paint = new PhongMaterial();
            paint.setDiffuseColor(Color.hsb(i * 6, 0.6, 0.5));
            box.setMaterial(paint);
            world.getChildren().add(box);
        }
        world.getChildren().add(new AmbientLight(Color.WHITE));
        world.getTransforms().add(spin);

        sub = new SubScene(world, VIEW_W, VIEW_H, true, SceneAntialiasing.DISABLED);
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setTranslateZ(-400);
        camera.setNearClip(1);
        camera.setFarClip(5000);
        sub.setCamera(camera);
        sub.setFill(Color.rgb(20, 20, 20));
        sub.getTransforms().setAll(stretch);
        host.getChildren().setAll(sub);

        stage.setScene(new Scene(new StackPane(host), VIEW_W, VIEW_H));
        stage.setX(0);
        stage.setY(0);
        stage.show();
    }

    /**
     * Holds the scene at one size long enough to be photographed from outside the process.
     *
     * <p>Pixels settle this where frame counts argue about it: at a sixteenth, every square of the
     * picture would be sixteen screen pixels across if the reduction were real.
     *
     * <pre>
     *   mvn -B test -Dtest=SubSceneResolutionProbe#holdForAPhotograph -DargLine="-Dprobe.scale=0.0625"
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
        interact(() -> {
            sub.setWidth(Math.max(1, VIEW_W * scale));
            sub.setHeight(Math.max(1, VIEW_H * scale));
            stretch.setX(1 / scale);
            stretch.setY(1 / scale);
            spin.setAngle(Double.parseDouble(System.getProperty("probe.angle", "25")));
        });
        WaitForAsyncUtils.waitForFxEvents();
        System.out.printf("PROBE holding at scale %.4f; subscene %.0fx%.0f, on screen %.0f wide%n",
                scale, sub.getWidth(), sub.getHeight(), sub.getBoundsInParent().getWidth());

        long until = System.currentTimeMillis() + 22_000;
        while (System.currentTimeMillis() < until) {
            WaitForAsyncUtils.sleep(50, java.util.concurrent.TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    @Test
    void askingForFewerPixelsDoesNotGetFewerPixels() {
        List<Long> full = new ArrayList<>();
        List<Long> half = new ArrayList<>();
        List<Long> sixteenth = new ArrayList<>();

        // Round robin, because this machine drifts more than several of the differences this
        // milestone has had to resolve.
        for (int slice = 0; slice < SLICES; slice++) {
            spinAt(1.0, full);
            spinAt(0.5, half);
            spinAt(0.0625, sixteenth);
        }

        report("full size", full);
        report("half size, so a quarter of the pixels", half);
        report("a sixteenth, so 0.4% of the pixels", sixteenth);
        System.out.println("PROBE if those three are the same, the SubScene's size is not what "
                + "decides how many pixels get drawn");
    }

    private void spinAt(double scale, List<Long> into) {
        interact(() -> {
            sub.setWidth(Math.max(1, VIEW_W * scale));
            sub.setHeight(Math.max(1, VIEW_H * scale));
            stretch.setX(1 / scale);
            stretch.setY(1 / scale);
        });
        WaitForAsyncUtils.waitForFxEvents();

        List<Long> frames = new ArrayList<>();
        AnimationTimer clock = new AnimationTimer() {
            private long previous;

            @Override
            public void handle(long now) {
                if (previous != 0) {
                    frames.add((now - previous) / 1_000_000);
                }
                previous = now;
                spin.setAngle(spin.getAngle() + 0.7);
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
        System.out.printf("PROBE %-38s %4d frames, median %2d ms, mean %5.2f ms%n",
                what, frames.size(), sorted.get(sorted.size() / 2),
                frames.stream().mapToLong(Long::longValue).average().orElse(0));
    }
}
