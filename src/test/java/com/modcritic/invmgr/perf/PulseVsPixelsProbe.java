package com.modcritic.invmgr.perf;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.model.Item;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.animation.AnimationTimer;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Phase 0 of M6.7b: does the app's idea of "the frame is out" match the pixels on the glass?
 *
 * <p>The latency suite rests on one claim, and the phone cannot check it: <b>that the start of the
 * second pulse after a change is a fair stand-in for the moment the screen changed.</b> It is
 * checked here, against a camera pointed at the real display. The camera is {@code java.awt.Robot}
 * grabbing a small rectangle; the cadence it managed is printed with every reading, because a
 * camera slower than the thing it is filming would invent the answer.
 *
 * <h2>Two questions, deliberately taken apart</h2>
 *
 * <p><b>A. From a change made on the FX thread to pixels.</b> No input involved. This is the one
 * that validates the pulse model, and it is measured on its own because the first version of this
 * probe measured it through a mouse click and got nonsense: TestFX moves the pointer before it
 * presses, the box under the pointer highlights on hover, and the camera saw <b>that</b> change
 * 43 ms before the pulse it was supposed to be judging. A reading that lands before the event it
 * is timing is not a fast app, it is the wrong event.
 *
 * <p><b>B. From an OS-level key press to the app's own event filter.</b> The input path's share,
 * which adds to A rather than overlapping it.
 *
 * <p>Not a test, and it asserts nothing. Run it deliberately:
 * {@code mvn -B test -Dtest=PulseVsPixelsProbe}.
 */
class PulseVsPixelsProbe extends ApplicationTest {

    private static final int WIDTH = 1200;
    private static final int HEIGHT = 800;
    private static final int REPS = 14;

    private App app;
    private Scene scene;

    /** t0, then the next two pulse starts. Nanoseconds, 0 until stamped. */
    private final long[] marks = new long[3];
    private int pulsesSeen;

    @Override
    public void start(Stage stage) {
        app = new App();
        app.start(stage);
        stage.setMaximized(false);
        stage.setWidth(WIDTH);
        stage.setHeight(HEIGHT);
        stage.setX(0);
        stage.setY(0);
        scene = stage.getScene();
    }

    /** One grab: when it was taken, and a cheap summary of what it showed. */
    private record Grab(long atNs, long fingerprint) { }

    @Test
    void doesTheSecondPulseMeanThePixelsChanged() throws Exception {
        interact(() -> {
            app.state().room.w = 12;
            app.state().room.l = 10;
            for (int i = 0; i < 8; i++) {
                app.state().items.add(box("b" + i, i + 1,
                        (1 + (i % 4) * 2.5) * 96, (1 + (i / 4) * 2.5) * 96,
                        18 + i * 3, "hsl(" + (i * 41) + ",55%,42%)"));
            }
            app.canvas().rebuildItems();
        });
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.sleep(900, TimeUnit.MILLISECONDS);

        // Park the pointer in a corner, far from anything that highlights on hover. This is the
        // fix for what the first version of this probe measured by mistake.
        moveTo(new javafx.geometry.Point2D(4, HEIGHT - 4));
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.sleep(400, TimeUnit.MILLISECONDS);

        AnimationTimer pulses = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (marks[0] != 0 && pulsesSeen < 2) {
                    marks[1 + pulsesSeen] = System.nanoTime();
                    pulsesSeen++;
                }
            }
        };
        interact(pulses::start);
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.sleep(300, TimeUnit.MILLISECONDS);

        System.out.println("PROBE ---- A: an FX-thread change, watched by a camera ----");
        List<Double> offsets = new ArrayList<>();
        List<Double> toPixels = new ArrayList<>();
        List<Double> toPulse2 = new ArrayList<>();
        for (int rep = 0; rep < REPS; rep++) {
            double[] reading = changeAndWatch(rep);
            if (reading != null) {
                toPixels.add(reading[0]);
                toPulse2.add(reading[1]);
                offsets.add(reading[0] - reading[1]);
            }
        }
        interact(pulses::stop);
        report("pixels", toPixels);
        report("pulse2", toPulse2);
        report("pixels minus pulse2", offsets);

        System.out.println("PROBE ---- B: an OS key press reaching the app's event filter ----");
        measureTheInputPath();
    }

    /**
     * Toggles the selection ring on one box and watches the screen for it.
     *
     * @return {@code {toPixelsMs, toPulse2Ms}}, or null if the camera saw nothing
     */
    private double[] changeAndWatch(int rep) throws Exception {
        Item target = app.state().items.get(3);
        Bounds onScreen = WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            Node node = app.canvas().itemRect(target.id);
            return node.localToScreen(node.getBoundsInLocal());
        });
        Rectangle watch = new Rectangle(
                (int) onScreen.getMinX() - 8, (int) onScreen.getMinY() - 8,
                (int) onScreen.getWidth() + 16, (int) onScreen.getHeight() + 16);

        List<Grab> grabs = new ArrayList<>(8000);
        AtomicBoolean grabbing = new AtomicBoolean(true);
        Robot robot = new Robot();
        Thread camera = new Thread(() -> {
            while (grabbing.get()) {
                BufferedImage shot = robot.createScreenCapture(watch);
                grabs.add(new Grab(System.nanoTime(), fingerprint(shot)));
            }
        }, "camera");
        camera.setDaemon(true);
        camera.start();
        Thread.sleep(250);

        long before = grabs.get(grabs.size() - 1).fingerprint();
        boolean select = rep % 2 == 0;

        marks[0] = 0;
        marks[1] = 0;
        marks[2] = 0;
        pulsesSeen = 0;
        interact(() -> {
            marks[0] = System.nanoTime();
            app.canvas().setSelectedId(select ? target.id : null);
        });
        WaitForAsyncUtils.waitForFxEvents();
        Thread.sleep(300);
        grabbing.set(false);
        camera.join(2000);

        long firstChangeNs = 0;
        for (Grab g : grabs) {
            if (g.atNs() > marks[0] && g.fingerprint() != before) {
                firstChangeNs = g.atNs();
                break;
            }
        }
        double cadence = (grabs.get(grabs.size() - 1).atNs() - grabs.get(0).atNs())
                / 1e6 / (grabs.size() - 1);
        if (marks[2] == 0 || firstChangeNs == 0) {
            System.out.printf("PROBE   rep %2d %-8s: camera saw nothing change "
                            + "(%d grabs, %.2f ms apart)%n",
                    rep, select ? "select" : "clear", grabs.size(), cadence);
            return null;
        }
        double pixels = (firstChangeNs - marks[0]) / 1e6;
        double pulse1 = (marks[1] - marks[0]) / 1e6;
        double pulse2 = (marks[2] - marks[0]) / 1e6;
        System.out.printf("PROBE   rep %2d %-8s: pulse1 %6.2f  pulse2 %6.2f  PIXELS %6.2f  "
                        + "(%+6.2f ms; camera %.2f ms apart)%n",
                rep, select ? "select" : "clear", pulse1, pulse2, pixels, pixels - pulse2, cadence);
        return new double[] {pixels, pulse2};
    }

    /** How long an OS key press takes to reach an event filter on the scene. */
    private void measureTheInputPath() {
        long[] arrived = new long[1];
        interact(() -> scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> arrived[0] = System.nanoTime()));
        WaitForAsyncUtils.waitForFxEvents();
        List<Double> path = new ArrayList<>();
        for (int rep = 0; rep < REPS; rep++) {
            arrived[0] = 0;
            long sent = System.nanoTime();
            push(KeyCode.SHIFT);
            WaitForAsyncUtils.waitForFxEvents();
            if (arrived[0] != 0) {
                path.add((arrived[0] - sent) / 1e6);
            }
            WaitForAsyncUtils.sleep(60, TimeUnit.MILLISECONDS);
        }
        report("robot press to event filter", path);
    }

    private static void report(String what, List<Double> values) {
        if (values.isEmpty()) {
            System.out.printf("PROBE %-28s no readings%n", what);
            return;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(null);
        double sum = 0;
        for (double v : sorted) {
            sum += v;
        }
        System.out.printf("PROBE %-28s n=%2d  min %6.2f  median %6.2f  mean %6.2f  "
                        + "p90 %6.2f  max %6.2f%n",
                what, sorted.size(), sorted.get(0), sorted.get(sorted.size() / 2),
                sum / sorted.size(), sorted.get((int) (sorted.size() * 0.9)),
                sorted.get(sorted.size() - 1));
    }

    /** A cheap, position-sensitive summary of a grab. Any changed pixel changes it. */
    private static long fingerprint(BufferedImage shot) {
        long hash = 1469598103934665603L;
        for (int y = 0; y < shot.getHeight(); y += 2) {
            for (int x = 0; x < shot.getWidth(); x += 2) {
                hash = (hash ^ shot.getRGB(x, y)) * 1099511628211L;
            }
        }
        return hash;
    }

    private static Item box(String id, int serial, double xpx, double ypx, double heightIn,
            String color) {
        Item item = new Item();
        item.id = id;
        item.serial = serial;
        item.dragOrder = serial;
        item.x_px = xpx;
        item.y_px = ypx;
        item.w_in = 24;
        item.l_in = 24;
        item.h_in = heightIn;
        item.color = color;
        return item;
    }
}
