package com.modcritic.invmgr.perf;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.model.Item;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * What does a long item list actually cost while it is being scrolled, and which part of it?
 *
 * <p><b>The phone found this and the desktop cannot feel it.</b> On the wired Galaxy S9, flinging a
 * 200 row list runs at 32 ms a frame while a 12 row list runs at 16, so the list halves the frame
 * rate and nothing else on the phone does: panning a 200 box room and turning in the 3D view both
 * sit at 16. On this desktop the same scroll is 1.5 ms either way, which is why the answer has to
 * be teased out rather than observed.
 *
 * <p>The user asked for a bounded look before anyone rewrites {@code ItemListPanel}, so this sweeps
 * the row count and takes the rows apart three ways. Each arm keeps the same number of rows in the
 * list and changes only what JavaFX is asked to do with the ones nobody can see:
 *
 * <ul>
 *   <li><b>as it ships</b>: every row a visible, managed node.</li>
 *   <li><b>off-screen rows unmanaged</b>: still drawn, but out of the layout pass. Separates layout
 *       from painting.</li>
 *   <li><b>off-screen rows invisible</b>: neither laid out nor drawn, which is what virtualizing
 *       the list would buy without anything being rewritten to find out.</li>
 * </ul>
 *
 * <p>Not a test, and it asserts nothing. Run it deliberately:
 * {@code mvn -B test -Dtest=ListScrollCostProbe}.
 */
class ListScrollCostProbe extends ApplicationTest {

    private static final int WIDTH = 1600;
    private static final int HEIGHT = 900;

    /** Scroll steps per arm. The first few are dropped; see {@link #measure}. */
    private static final int STEPS = 40;

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

    /** What to do with the rows nobody can see. */
    private enum Arm {
        AS_IT_SHIPS("as it ships"),
        UNMANAGED("off-screen rows unmanaged"),
        INVISIBLE("off-screen rows invisible");

        final String label;

        Arm(String label) {
            this.label = label;
        }
    }

    @Test
    void whereDoesAScrollSpendItsTime() {
        System.out.printf("PROBE %-28s %6s %10s %10s %10s%n",
                "arm", "rows", "median ms", "p90 ms", "worst ms");
        for (int rows : new int[] {12, 60, 200, 500}) {
            for (Arm arm : Arm.values()) {
                measure(rows, arm);
            }
        }
    }

    private void measure(int rows, Arm arm) {
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
        WaitForAsyncUtils.sleep(500, TimeUnit.MILLISECONDS);

        ScrollPane scroller = findScroller();
        List<Double> times = new ArrayList<>();
        for (int step = 0; step < STEPS; step++) {
            final double where = (step % 20) / 19.0;
            times.add(WaitForAsyncUtils.waitForAsyncFx(30000, () -> {
                scroller.setVvalue(where);
                applyArm(arm, scroller);
                long at = System.nanoTime();
                // The panel, not the scene. Drawing the whole window on Xvfb costs 35 to 50 ms of
                // room and grid, and the thing being measured is a fraction of that; the first
                // version of this probe reported all three arms as identical for that reason
                // alone, at every row count, which is a floor rather than a result.
                app.listPanel().applyCss();
                app.listPanel().layout();
                app.listPanel().snapshot(null, null);
                return (System.nanoTime() - at) / 1e6;
            }));
        }
        // Drop the first few: the list was just rebuilt and the first frames pay for that.
        List<Double> settled = new ArrayList<>(times.subList(8, times.size()));
        settled.sort(null);
        System.out.printf("PROBE %-28s %6d %10.2f %10.2f %10.2f%n",
                arm.label, rows, settled.get(settled.size() / 2),
                settled.get((int) (settled.size() * 0.9)), settled.get(settled.size() - 1));

        // Put every row back, so the next arm starts from the shipped state rather than from
        // whatever the last one left behind.
        interact(() -> {
            for (Node row : rowsOf(scroller)) {
                row.setVisible(true);
                row.setManaged(true);
            }
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Hides or unmanages whatever is currently outside the viewport. Runs on the FX thread. */
    private void applyArm(Arm arm, ScrollPane scroller) {
        if (arm == Arm.AS_IT_SHIPS) {
            return;
        }
        double top = scroller.getVvalue()
                * (contentHeight(scroller) - scroller.getViewportBounds().getHeight());
        double bottom = top + scroller.getViewportBounds().getHeight();
        for (Node row : rowsOf(scroller)) {
            double rowTop = row.getBoundsInParent().getMinY();
            double rowBottom = row.getBoundsInParent().getMaxY();
            boolean onScreen = rowBottom >= top && rowTop <= bottom;
            if (arm == Arm.INVISIBLE) {
                row.setVisible(onScreen);
            } else {
                row.setManaged(onScreen);
            }
        }
    }

    private static double contentHeight(ScrollPane scroller) {
        return scroller.getContent().getBoundsInLocal().getHeight();
    }

    private static List<Node> rowsOf(ScrollPane scroller) {
        return ((Region) scroller.getContent()).getChildrenUnmodifiable();
    }

    /** The item list's scroller, found by walking the panel rather than by adding an accessor. */
    private ScrollPane findScroller() {
        return WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            for (Node child : app.listPanel().getChildrenUnmodifiable()) {
                if (child instanceof ScrollPane found) {
                    return found;
                }
            }
            throw new IllegalStateException("the item list has no ScrollPane in it any more");
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
