package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.threed.Transitions;
import java.util.ArrayList;
import java.util.List;
import javafx.animation.AnimationTimer;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * A throwaway measuring probe, in the manner of {@code ScaleProbe} and {@code DescentProbe}: not
 * a test, and it asserts nothing.
 *
 * <p>It times the 3D transitions frame by frame and prints the distribution, because the user
 * reported the movement as laggy on weaker hardware and CLAUDE.md §2 says an optimization needs a
 * measurement behind it rather than an argument. This container has no GPU; Prism runs on Mesa's
 * software OpenGL, which makes it a reasonable stand-in for exactly the machines in question.
 *
 * <p>Run it deliberately: {@code mvn -B test -Dtest=TransitionCostProbe}.
 */
class TransitionCostProbe extends ApplicationTest {

    private static final int WIDTH = 1600;
    private static final int HEIGHT = 900;

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
    void measureTheJourney() {
        interact(() -> {
            for (int i = 0; i < 12; i++) {
                app.state().items.add(box("b" + i, i + 1,
                        (1 + (i % 4) * 2.5) * 96, (1 + (i / 4) * 2.5) * 96,
                        18 + i * 3, "hsl(" + (i * 29) + ",55%,42%)"));
            }
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
            }
        };
        interact(clock::start);

        long buildStart = System.nanoTime();
        interact(() -> app.topBar().threeDButtonNode().fire());
        settle((long) (Transitions.BARS_LEAVE_LAYOUT_MS + Transitions.DESCENT_DELAY_MS
                + Transitions.DESCENT_MS) + 800);
        long enteredMs = (System.nanoTime() - buildStart) / 1_000_000;

        report("DESCENT (whole entry, " + enteredMs + " ms wall clock)", frames);
        // WHERE the bad frame is, not just how bad. A hitch at the start is the room being built
        // or its textures reaching the card; one in the middle is something else entirely, and
        // the two call for completely different answers.
        whereIsTheWorst("DESCENT", frames);

        // STANDING IN THE ROOM, WHICH IS WHAT M6.7 ITEM 3 IS ACTUALLY ABOUT.
        //
        // The descent above is M5.2b's measurement and is a 2.2 second animation with the whole
        // 2D interface sliding away underneath it. What the user reported at M6.7 is the view
        // being laggy, which is this: landed, walking about, nothing animating but the camera.
        //
        // Both numbers are taken because they answer different questions. The frame gap is what a
        // hand feels, and it is capped by the pulse at about 16 ms, so it can only show frames
        // that OVERRAN. The snapshot is the work itself with no cap in the way, and it is the
        // number a fix has to move.
        frames.clear();
        double[] angle = {0};
        AnimationTimer turning = new AnimationTimer() {
            @Override
            public void handle(long now) {
                angle[0] += 0.6;
                app.view3d().camera().yaw = Math.toRadians(angle[0]);
                app.view3d().render();
            }
        };
        interact(turning::start);
        settle(2500);
        interact(turning::stop);
        report("STANDING IN THE ROOM, turning on the spot", frames);

        java.util.List<Long> draws = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            final double a = i * 5.0;
            interact(() -> {
                app.view3d().camera().yaw = Math.toRadians(a);
                app.view3d().render();
            });
            draws.add(WaitForAsyncUtils.waitForAsyncFx(10000, () -> {
                long t = System.nanoTime();
                scene.snapshot(null);
                return (System.nanoTime() - t) / 1_000_000;
            }));
        }
        draws.sort(null);
        System.out.printf("PROBE   drawing the WHOLE WINDOW once, standing in the room: "
                + "%d ms median of %d%n", draws.get(draws.size() / 2), draws.size());

        frames.clear();
        interact(() -> app.view3d().returnButton().fire());
        settle((long) Transitions.ASCENT_MS + 800);
        report("ASCENT", frames);

        interact(clock::stop);
        interact(() -> app.view3d().close());
        WaitForAsyncUtils.waitForFxEvents();

        // Where the hitch actually is. Timed on the FX thread, one piece at a time.
        interact(() -> {
            long t0 = System.nanoTime();
            var textures = new java.util.ArrayList<javafx.scene.image.Image>();
            // The grid tile is painted once for the whole room and then shared, so it is timed
            // once rather than five times. Since M6.7 the per-surface cost is the vignette alone.
            long tileStart = System.nanoTime();
            textures.add(com.modcritic.invmgr.threed.jfx.SurfaceTexture.gridTile(false));
            System.out.printf("PROBE   grid tile (shared by every surface) -> %5.1f ms%n",
                    (System.nanoTime() - tileStart) / 1e6);
            for (var s : com.modcritic.invmgr.threed.RoomGeometry.surfacesOf(app.state().room)) {
                long each = System.nanoTime();
                textures.add(com.modcritic.invmgr.threed.jfx.SurfaceTexture.vignette(s.widthFt,
                        s.heightFt));
                System.out.printf("PROBE   vignette %-12s %4.0f x %-4.0f ft -> %5.1f ms%n",
                        s.name, s.widthFt, s.heightFt, (System.nanoTime() - each) / 1e6);
            }
            System.out.printf("PROBE tile plus five vignettes: %.1f ms%n",
                    (System.nanoTime() - t0) / 1e6);

            long meshes = System.nanoTime();
            for (var b : com.modcritic.invmgr.threed.Geometry3D.boxesFor(app.state())) {
                var mesh = new javafx.scene.shape.TriangleMesh();
                mesh.getPoints().setAll(
                        com.modcritic.invmgr.threed.BoxGeometry.points(b));
                mesh.getTexCoords().setAll(
                        com.modcritic.invmgr.threed.BoxGeometry.texCoords());
                mesh.getFaces().setAll(com.modcritic.invmgr.threed.BoxGeometry.faces(b));
            }
            System.out.printf("PROBE 12 box meshes: %.1f ms%n", (System.nanoTime() - meshes) / 1e6);

            long full = System.nanoTime();
            app.view3d().open(app.state(), scene);
            System.out.printf("PROBE whole init+build+render+show: %.1f ms%n",
                    (System.nanoTime() - full) / 1e6);
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void settle(long millis) {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            WaitForAsyncUtils.sleep(10, java.util.concurrent.TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    /** Prints the first few frames and says how far into the run the worst one landed. */
    private static void whereIsTheWorst(String what, List<Long> frames) {
        if (frames.size() < 4) {
            return;
        }
        int worstAt = 0;
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i) > frames.get(worstAt)) {
                worstAt = i;
            }
        }
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < Math.min(8, frames.size()); i++) {
            head.append(frames.get(i)).append(i < 7 ? ", " : "");
        }
        System.out.printf("PROBE   %s: first frames %s ... worst (%d ms) is frame %d of %d, "
                        + "%.0f%% of the way in%n",
                what, head, frames.get(worstAt), worstAt + 1, frames.size(),
                100.0 * worstAt / frames.size());
    }

    private static void report(String what, List<Long> frames) {
        if (frames.isEmpty()) {
            System.out.println("PROBE " + what + ": no frames");
            return;
        }
        List<Long> sorted = new ArrayList<>(frames);
        sorted.sort(null);
        long total = 0;
        long worst = 0;
        int over33 = 0;
        for (long f : frames) {
            total += f;
            worst = Math.max(worst, f);
            if (f > 33) {
                over33++;
            }
        }
        System.out.printf(
                "PROBE %s: %d frames, median %d ms, 90th %d ms, worst %d ms, "
                        + "%d dropped below 30fps (%.0f%%), mean %.1f ms%n",
                what, frames.size(), sorted.get(sorted.size() / 2),
                sorted.get((int) (sorted.size() * 0.9)), worst, over33,
                100.0 * over33 / frames.size(), (double) total / frames.size());
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
