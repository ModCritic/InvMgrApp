package com.modcritic.invmgr.perf;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.model.Item;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * What is in the one enormous frame every entry into the 3D view pays for?
 *
 * <p>M6.7b's baseline found it and could not explain it: <b>a 200 x 200 ft room with 200 boxes has
 * one frame of 400 to 616 ms about a fifth of the way into the descent</b>, once per entry, in all
 * three descents of every run and in both arms. It is the largest single stall anywhere in the app,
 * four times what M6.7a saw in a smaller room.
 *
 * <p>M6.7a's answer was "the view becomes visible and pays for every texture at once", which is a
 * reasonable story and <b>the arithmetic does not support it</b>: a box's texture is a
 * {@code FaceStrip}, 40 x 8 pixels, 1,280 bytes. Two hundred of them is 256 KB. Nothing uploads
 * 256 KB in half a second. So the story is wrong somewhere and this finds out where.
 *
 * <p>It times the FIRST draw of a freshly built room against the ones after it, and varies one
 * thing at a time: how many boxes, how many distinct colors among them, and how big the room is.
 * Whichever number the first draw follows is the one that matters.
 *
 * <p>Not a test, and it asserts nothing. Run it deliberately:
 * {@code mvn -B test -Dtest=FirstDrawProbe}.
 */
class FirstDrawProbe extends ApplicationTest {

    private static final int WIDTH = 1600;
    private static final int HEIGHT = 900;

    /** Draws after the first one, to say what the steady state costs. */
    private static final int AFTER = 6;

    /**
     * How many times each arm is built from scratch.
     *
     * <p>⚠ The first version of this took ONE reading per arm and the table it printed had a row
     * that contradicted two others: a 200 ft room with 200 boxes in 200 colors read 743 ms while
     * the same 200 boxes in a 20 ft room read 329, which cannot both describe a per-box cost. With
     * three builds and a median the arms separate cleanly. One sample is not a measurement, which
     * is the same rule the rest of M6.7b is built on.
     */
    private static final int BUILDS = 3;

    private App app;
    private Scene scene;

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

    @Test
    void whatIsInThatFrame() {
        System.out.printf("PROBE %-34s %10s %10s %10s%n",
                "arm", "first ms", "then ms", "first/then");
        // One thing at a time. The boxes column moves alone in the first block, the colors column
        // alone in the second, and the room alone in the third.
        measure("empty 200ft room, 0 boxes", 200, 0, 1);
        measure("200ft room, 50 boxes, 1 color", 200, 50, 1);
        measure("200ft room, 200 boxes, 1 color", 200, 200, 1);
        measure("200ft room, 500 boxes, 1 color", 200, 500, 1);

        measure("200ft room, 200 boxes, 4 colors", 200, 200, 4);
        measure("200ft room, 200 boxes, 200 colors", 200, 200, 200);

        measure("20ft room, 200 boxes, 200 colors", 20, 200, 200);
        measure("60ft room, 200 boxes, 200 colors", 60, 200, 200);
        measure("empty 20ft room, 0 boxes", 20, 0, 1);
    }

    /**
     * Builds one room from scratch and times its first drawn frame against its later ones.
     *
     * <p>The room is torn down and rebuilt each time. Reusing one would measure a second entry,
     * which is the cheap case and not the one being asked about.
     */
    private void measure(String what, int roomFt, int boxes, int colors) {
        List<Double> firsts = new ArrayList<>();
        List<Double> steadies = new ArrayList<>();
        for (int build = 0; build < BUILDS; build++) {
            double[] one = buildAndDraw(roomFt, boxes, colors);
            firsts.add(one[0]);
            steadies.add(one[1]);
        }
        firsts.sort(null);
        steadies.sort(null);
        double first = firsts.get(firsts.size() / 2);
        double steady = steadies.get(steadies.size() / 2);
        System.out.printf("PROBE %-34s %10.1f %10.1f %10.1fx   (firsts %s)%n",
                what, first, steady, steady == 0 ? 0 : first / steady,
                firsts.stream().map(v -> String.format("%.0f", v)).toList());
    }

    /** One build from nothing, its first drawn frame, and the median of the frames after it. */
    private double[] buildAndDraw(int roomFt, int boxes, int colors) {
        interact(() -> {
            app.view3d().close();
            app.state().room.w = roomFt;
            app.state().room.l = roomFt;
            app.state().room.h = 12;
            app.state().items.clear();
            double span = roomFt * 96.0;
            int perRow = Math.max(1, (int) Math.sqrt(Math.max(1, boxes)));
            for (int i = 0; i < boxes; i++) {
                double gap = span / (perRow + 1);
                app.state().items.add(box("b" + i, i + 1,
                        gap * (1 + i % perRow), gap * (1 + i / perRow),
                        12 + (i % 7) * 6,
                        "hsl(" + (colors == 1 ? 210 : (i % colors) * (360 / colors)) + ",55%,42%)"));
            }
        });
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.sleep(400, TimeUnit.MILLISECONDS);

        // open() builds the room AND shows it, which is the path prepare() takes minus the
        // animation. The first snapshot afterwards is the first time Prism is asked to rasterize
        // any of it.
        interact(() -> app.view3d().open(app.state(), scene));
        WaitForAsyncUtils.waitForFxEvents();

        double first = drawOnce();
        List<Double> then = new ArrayList<>();
        for (int i = 0; i < AFTER; i++) {
            interact(() -> {
                app.view3d().camera().yaw += Math.toRadians(3);
                app.view3d().render();
            });
            then.add(drawOnce());
        }
        then.sort(null);
        interact(() -> app.view3d().close());
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.sleep(250, TimeUnit.MILLISECONDS);
        return new double[] {first, then.get(then.size() / 2)};
    }

    /** One synchronous draw of the whole window, in milliseconds. */
    private double drawOnce() {
        return WaitForAsyncUtils.waitForAsyncFx(60000, () -> {
            long at = System.nanoTime();
            scene.snapshot(null);
            return (System.nanoTime() - at) / 1e6;
        });
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
