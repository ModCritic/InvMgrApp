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
 * What one selection change costs in a long item list, with the frame it causes included.
 *
 * <p><b>Why this exists rather than another run of the bench.</b> {@code ItemListPanel.setSelectedId}
 * restyles every row, so one click in a 200 item list touches 200 rows while at most two of them
 * look any different. Skipping the ones whose appearance has not changed ought to be free money.
 * The bench could not tell: {@code list-select-heavy} takes 24 samples, and three runs with the
 * skip read 4.32, 3.83 and 4.26 ms against 4.76 without it, which is inside its own spread. <b>A
 * scenario that cannot separate two arms is not evidence either way</b>, and this project's rule is
 * that an optimization earns its place with a number.
 *
 * <p>So this does the one thing, many times, with nothing else in the frame: change the selection,
 * force the CSS pass and a draw <b>of the list panel alone</b>, and time the three together.
 *
 * <p>⚠ <b>The list panel alone, and the first version of this got that wrong.</b> It drew the whole
 * window, which on Xvfb costs 35 to 50 ms of canvas and grid on its own, and the thing being
 * measured is under a millisecond of that. Two rounds each way came back 20% apart <b>at every row
 * count including twelve</b>, where the change can save at most ten calls, so the difference was
 * the machine rather than the code. A floor that large does not average out; it has to go.
 *
 * <p>Run it on both builds:
 *
 * <pre>
 *   DISPLAY=:99 mvn -B test -Dtest=RestyleCostProbe
 * </pre>
 *
 * <p>Not a test, and it asserts nothing.
 */
class RestyleCostProbe extends ApplicationTest {

    private static final int WIDTH = 1600;
    private static final int HEIGHT = 900;

    /** Enough changes that a median means something, in each of three sizes. */
    private static final int CHANGES = 400;

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
    void whatOneSelectionChangeCosts() {
        System.out.printf("PROBE %-12s %10s %10s %10s %10s%n",
                "rows", "median ms", "mean ms", "p90 ms", "worst ms");
        for (int rows : new int[] {12, 60, 200, 500}) {
            measure(rows);
        }
    }

    private void measure(int rows) {
        interact(() -> {
            app.state().room.w = 200;
            app.state().room.l = 200;
            app.state().items.clear();
            for (int i = 0; i < rows; i++) {
                app.state().items.add(box("r" + i, i + 1,
                        (2 + (i % 20) * 9) * 96, (2 + (i / 20) * 9) * 96,
                        12 + (i % 7) * 6, "hsl(" + (i * 13 % 360) + ",55%,42%)"));
            }
            app.canvas().rebuildItems();
            app.listPanel().rebuild();
        });
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.sleep(600, TimeUnit.MILLISECONDS);

        List<Double> times = new ArrayList<>();
        for (int i = 0; i < CHANGES; i++) {
            final String id = app.state().items.get(i % rows).id;
            times.add(WaitForAsyncUtils.waitForAsyncFx(30000, () -> {
                long at = System.nanoTime();
                app.listPanel().setSelectedId(id);
                // applyCss is the pass the change actually causes: the setter only marks the rows,
                // and without this the number would be the loop rather than the work. The snapshot
                // is of the panel, not the scene, so the room's own drawing stays out of it.
                // applyCss and NOTHING else. That is the pass an inline style change causes, and
                // it is the only thing the skip can affect. ⚠ Drawing the panel was still a 20 to
                // 40 ms floor on Xvfb, which put the arms 20% apart in both directions at
                // different row counts: a floor, not a result. Under a millisecond of work needs
                // an instrument that measures under a millisecond.
                app.listPanel().applyCss();
                return (System.nanoTime() - at) / 1e6;
            }));
        }
        // The first few pay for whatever the list was doing before; drop them rather than let
        // them decide a median taken over sixty.
        List<Double> settled = new ArrayList<>(times.subList(40, times.size()));
        settled.sort(null);
        double sum = 0;
        for (double v : settled) {
            sum += v;
        }
        System.out.printf("PROBE %-12d %10.4f %10.4f %10.4f %10.4f%n",
                rows, settled.get(settled.size() / 2), sum / settled.size(),
                settled.get((int) (settled.size() * 0.9)), settled.get(settled.size() - 1));
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
