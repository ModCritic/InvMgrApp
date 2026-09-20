package com.modcritic.invmgr.perf;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.LatencyClock;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Units;
import com.modcritic.invmgr.threed.Transitions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javafx.animation.AnimationTimer;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.VerticalDirection;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * M6.7b's desktop lane: how long the app takes to show the result of each thing you can do to it.
 *
 * <p><b>This is a bench, not a test.</b> It asserts nothing, and {@code mvn test} never runs it:
 * surefire picks up {@code *Test} and nothing else, which is the same reason the dozen
 * {@code *Probe} classes beside it stay out of the suite. No exclusion list to keep current. Run it
 * deliberately, twice, once per screen:
 *
 * <pre>
 *   DISPLAY=:99 mvn -B test -Dtest=LatencyBench -Dinvmgr.perf.label=baseline
 *   DISPLAY=:0  mvn -B test -Dtest=LatencyBench -Dinvmgr.perf.label=baseline
 *   python3 tools/perf/perfstat.py report baseline
 * </pre>
 *
 * <h2>What it measures, and why it is the same number the phone reports</h2>
 *
 * <p>Every scenario drives the app with a real pointer or a real key through TestFX's robot, and
 * the timing comes from {@link LatencyClock}, which is the app's own code and is also what runs on
 * the phone. The desktop lanes read the samples as objects and the phone reads the identical
 * samples off logcat; one measuring implementation, three screens.
 *
 * <p>{@code PulseVsPixelsProbe} is what says the number means anything: against a camera on the
 * real display, the frame lands a median of 1.75 ms after the moment this calls it out.
 *
 * <h2>⚠ Repetitions, and why the worst one is the point</h2>
 *
 * <p>Nothing here reports a mean on its own. A drag that is smooth 39 times out of 40 and stalls
 * for 90 ms on the fortieth <b>feels broken</b>, and a mean hides that completely. Raw samples go
 * to {@code target/perf/} and {@code tools/perf/perfstat.py} is where the distribution, the worst
 * frame and the outlier hunt live.
 */
class LatencyBench extends ApplicationTest {

    /** The window both desktop lanes use. Fixed, because latency scales with pixels drawn. */
    private static final int WIDTH = 1600;
    private static final int HEIGHT = 900;

    /**
     * How many times each action is repeated.
     *
     * <p>The user asked for at least ten. Twelve is taken for the one-shot actions so that a
     * discarded first repetition still leaves eleven, and the dragging scenarios take far more
     * than that because every step of a drag is its own sample.
     */
    private static final int REPS = 12;

    /** Steps in one drag. Each one is an event, a frame, and a sample. */
    private static final int DRAG_STEPS = 24;

    private App app;
    private Scene scene;
    private PerfRun run;

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

    private void arm() {
        run = new PerfRun();
        interact(() -> LatencyClock.installFor(scene, run::accept));
        WaitForAsyncUtils.waitForFxEvents();
        System.out.printf("PERF  label %s, platform %s, window %d x %d%n",
                run.label(), run.platform(), WIDTH, HEIGHT);
        // The pointer parks in a corner between scenarios. A pointer left resting on a box keeps
        // it highlighted, and the highlight is a frame the next scenario did not ask for.
        park();
    }

    private void park() {
        moveTo(new Point2D(scene.getWindow().getX() + 6,
                scene.getWindow().getY() + HEIGHT - 6));
        WaitForAsyncUtils.waitForFxEvents();
        settle(250);
    }

    // ---- the worlds ---------------------------------------------------------------------
    //
    // Two, because the app's cost is dominated by how much there is to draw and a 20 ft room with
    // a dozen boxes measures none of what the user reported. The heavy one is the save format's
    // own limits: the biggest room the original allows and a serious number of items.

    private void small() {
        interact(() -> {
            app.state().room.w = 20;
            app.state().room.l = 16;
            app.state().room.h = 8;
            app.state().items.clear();
            for (int i = 0; i < 12; i++) {
                app.state().items.add(box("s" + i, i + 1,
                        (1 + (i % 4) * 2.5) * 96, (1 + (i / 4) * 2.5) * 96,
                        18 + i * 3, "hsl(" + (i * 29) + ",55%,42%)"));
            }
            app.canvas().setFitMode(true);
            app.canvas().rebuildRoom();
            app.canvas().rebuildItems();
            // Replacing the items behind the panel's back leaves it showing the old
            // list, so rowFor() returns null for everything here. Same for the slider,
            // whose track is built from the room's height.
            app.listPanel().rebuild();
            app.sliderDrawer().rebuild();
        });
        WaitForAsyncUtils.waitForFxEvents();
        settle(600);
    }

    private void heavy() {
        interact(() -> {
            app.state().room.w = 200;
            app.state().room.l = 200;
            app.state().room.h = 12;
            app.state().items.clear();
            for (int i = 0; i < 200; i++) {
                app.state().items.add(box("h" + i, i + 1,
                        (2 + (i % 20) * 9) * 96, (2 + (i / 20) * 9) * 96,
                        12 + (i % 7) * 6, "hsl(" + (i * 13 % 360) + ",55%,42%)"));
            }
            app.canvas().setFitMode(true);
            app.canvas().rebuildRoom();
            app.canvas().rebuildItems();
            // Replacing the items behind the panel's back leaves it showing the old
            // list, so rowFor() returns null for everything here. Same for the slider,
            // whose track is built from the room's height.
            app.listPanel().rebuild();
            app.sliderDrawer().rebuild();
        });
        WaitForAsyncUtils.waitForFxEvents();
        settle(1200);
    }

    // ---- the scenarios ------------------------------------------------------------------

    @Test
    void theCanvasInASmallRoom() {
        arm();
        small();
        selectAnItem("select-item-small");
        dragAnItem("drag-item-small");
        panTheRoom("pan-small");
        zoomTheRoom("zoom-small");
        typeInSearch("search-small");
        dragTheLayerSlider("layer-slider-small");
    }

    @Test
    void theCanvasInAHugeRoom() {
        arm();
        heavy();
        selectAnItem("select-item-heavy");
        dragAnItem("drag-item-heavy");
        panTheRoom("pan-heavy");
        zoomTheRoom("zoom-heavy");
        typeInSearch("search-heavy");
        dragTheLayerSlider("layer-slider-heavy");
    }

    @Test
    void theListAndTheCarriedCard() {
        arm();
        small();
        clickListRows("list-select-small");
        carryAPlannedCard("planned-drag-small");
    }

    @Test
    void theItemList() {
        arm();
        heavy();
        scrollTheList("scroll-list-heavy");
        clickListRows("list-select-heavy");
        small();
        scrollTheList("scroll-list-small");
    }

    @Test
    void theThreeDView() {
        arm();
        heavy();
        flyInAndOut("flight-heavy");
        turnOnTheSpot("turn-3d-heavy");
        drawTheWholeWindow("draw-3d-heavy");
    }

    // ---- what each one does -------------------------------------------------------------

    /** Click a box, which draws a ring round it. One press and one release per repetition. */
    private void selectAnItem(String name) {
        run.begin();
        for (int rep = 0; rep < REPS; rep++) {
            run.nextRep(rep);
            Item target = app.state().items.get(rep % Math.min(8, app.state().items.size()));
            clickOn(centerOf(target));
            WaitForAsyncUtils.waitForFxEvents();
            settle(90);
        }
        park();
        run.write(name, "click a box on the canvas; the app draws a selection ring round it");
    }

    /** Carry a box across the room. Every step runs collision and stacking, then redraws. */
    private void dragAnItem(String name) {
        run.begin();
        Item target = app.state().items.get(0);
        for (int rep = 0; rep < 3; rep++) {
            run.nextRep(rep);
            Point2D from = centerOf(target);
            moveTo(from);
            press(MouseButton.PRIMARY);
            double step = rep % 2 == 0 ? 9 : -9;
            for (int i = 0; i < DRAG_STEPS; i++) {
                moveBy(step, step / 3);
            }
            release(MouseButton.PRIMARY);
            WaitForAsyncUtils.waitForFxEvents();
            settle(160);
        }
        park();
        run.write(name, "drag a box " + DRAG_STEPS + " steps; each step is collision, stacking "
                + "and a redraw");
    }

    /** Drag bare floor, which slides the whole room under the window. */
    private void panTheRoom(String name) {
        interact(() -> app.canvas().setFitMode(false));
        WaitForAsyncUtils.waitForFxEvents();
        settle(400);
        run.begin();
        for (int rep = 0; rep < 3; rep++) {
            run.nextRep(rep);
            moveTo(new Point2D(scene.getWindow().getX() + WIDTH * 0.55,
                    scene.getWindow().getY() + HEIGHT * 0.6));
            press(MouseButton.PRIMARY);
            double step = rep % 2 == 0 ? -7 : 7;
            for (int i = 0; i < DRAG_STEPS; i++) {
                moveBy(step, step / 2);
            }
            release(MouseButton.PRIMARY);
            WaitForAsyncUtils.waitForFxEvents();
            settle(400);
        }
        park();
        interact(() -> app.canvas().setFitMode(true));
        WaitForAsyncUtils.waitForFxEvents();
        settle(400);
        run.write(name, "drag bare floor " + DRAG_STEPS + " steps, which slides the whole room");
    }

    /** The wheel over the canvas, which rescales everything drawn. */
    private void zoomTheRoom(String name) {
        interact(() -> app.canvas().setFitMode(false));
        WaitForAsyncUtils.waitForFxEvents();
        settle(400);
        run.begin();
        moveTo(new Point2D(scene.getWindow().getX() + WIDTH * 0.5,
                scene.getWindow().getY() + HEIGHT * 0.55));
        for (int rep = 0; rep < REPS; rep++) {
            run.nextRep(rep);
            scroll(2, rep % 2 == 0 ? VerticalDirection.UP : VerticalDirection.DOWN);
            WaitForAsyncUtils.waitForFxEvents();
            settle(120);
        }
        park();
        interact(() -> app.canvas().setFitMode(true));
        WaitForAsyncUtils.waitForFxEvents();
        settle(400);
        run.write(name, "the wheel over the canvas, which rescales everything drawn");
    }

    /** A key at a time into the search box, which refilters the list and dims the canvas. */
    private void typeInSearch(String name) {
        clickOn(app.listPanel().searchField());
        WaitForAsyncUtils.waitForFxEvents();
        settle(200);
        run.begin();
        KeyCode[] word = {KeyCode.S, KeyCode.DIGIT1, KeyCode.BACK_SPACE, KeyCode.DIGIT2,
                KeyCode.BACK_SPACE, KeyCode.BACK_SPACE, KeyCode.H, KeyCode.DIGIT1,
                KeyCode.BACK_SPACE, KeyCode.BACK_SPACE, KeyCode.B, KeyCode.BACK_SPACE};
        for (int rep = 0; rep < word.length; rep++) {
            run.nextRep(rep);
            push(word[rep]);
            WaitForAsyncUtils.waitForFxEvents();
            settle(120);
        }
        interact(() -> app.listPanel().searchField().clear());
        WaitForAsyncUtils.waitForFxEvents();
        park();
        run.write(name, "one key at a time into the search box; the list refilters and the "
                + "canvas dims what no longer matches");
    }

    /** Drag the layer slider, which re-decides what every box looks like on every step. */
    private void dragTheLayerSlider(String name) {
        Bounds track = WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            var node = app.sliderDrawer().slider();
            return node.localToScreen(node.getBoundsInLocal());
        });
        run.begin();
        for (int rep = 0; rep < 3; rep++) {
            run.nextRep(rep);
            moveTo(new Point2D(track.getCenterX(),
                    rep % 2 == 0 ? track.getMaxY() - 12 : track.getMinY() + 12));
            press(MouseButton.PRIMARY);
            double step = (track.getHeight() - 24) / DRAG_STEPS * (rep % 2 == 0 ? -1 : 1);
            for (int i = 0; i < DRAG_STEPS; i++) {
                moveBy(0, step);
            }
            release(MouseButton.PRIMARY);
            WaitForAsyncUtils.waitForFxEvents();
            settle(200);
        }
        park();
        run.write(name, "drag the layer slider end to end; each step re-decides how every box "
                + "is drawn");
    }

    /** Click rows in the item list, which highlights the row and rings the box. */
    private void clickListRows(String name) {
        run.begin();
        for (int rep = 0; rep < REPS; rep++) {
            run.nextRep(rep);
            Item target = app.state().items.get(rep % Math.min(8, app.state().items.size()));
            clickOn(centerOfRow(target));
            WaitForAsyncUtils.waitForFxEvents();
            settle(110);
        }
        park();
        run.write(name, "click a row in the item list; the row highlights and the box is ringed");
    }

    /**
     * Drag the item list up and down.
     *
     * <p>Here because the phone found it: a 200-row list flung on the wired Galaxy S9 reported a
     * median of 38.3 ms, worst 120.4, which is about 26 frames a second and the worst sustained
     * number anywhere in the baseline. Nothing on the desktop lane covered it, so nothing here
     * could say whether it was the phone or the list.
     */
    private void scrollTheList(String name) {
        run.begin();
        Bounds panel = WaitForAsyncUtils.waitForAsyncFx(5000, () ->
                app.listPanel().localToScreen(app.listPanel().getBoundsInLocal()));
        for (int rep = 0; rep < 4; rep++) {
            run.nextRep(rep);
            moveTo(new Point2D(panel.getCenterX(), panel.getCenterY()));
            scroll(14, rep % 2 == 0 ? VerticalDirection.DOWN : VerticalDirection.UP);
            WaitForAsyncUtils.waitForFxEvents();
            settle(200);
        }
        // And by dragging, which is what a thumb does and what the phone measured.
        for (int rep = 4; rep < 8; rep++) {
            run.nextRep(rep);
            moveTo(new Point2D(panel.getCenterX(), panel.getCenterY() + 140));
            press(MouseButton.PRIMARY);
            for (int i = 0; i < DRAG_STEPS; i++) {
                moveBy(0, rep % 2 == 0 ? -7 : 7);
            }
            release(MouseButton.PRIMARY);
            WaitForAsyncUtils.waitForFxEvents();
            settle(200);
        }
        park();
        run.write(name, "scroll the item list, by wheel and then by dragging it");
    }

    /**
     * Carry a planned item's card out of the list.
     *
     * <p>This is the object M6.7a item 2 cached: a card with the app's one drop shadow, turning as
     * it moves. Its cost is per frame of the carry, so the drag is long.
     */
    private void carryAPlannedCard(String name) {
        Item ghost = interactPlanned();
        run.begin();
        for (int rep = 0; rep < 3; rep++) {
            run.nextRep(rep);
            Point2D from = centerOfRow(ghost);
            moveTo(from);
            press(MouseButton.PRIMARY);
            // Sideways first: that is what tells the list this is a carry and not a scroll.
            moveBy(-70, 0);
            for (int i = 0; i < DRAG_STEPS; i++) {
                moveBy(rep % 2 == 0 ? -11 : 11, 5);
            }
            // Back to the row and release there, so the plan is never committed and the next
            // repetition has the same card to pick up.
            moveTo(from);
            release(MouseButton.PRIMARY);
            WaitForAsyncUtils.waitForFxEvents();
            settle(300);
        }
        park();
        run.write(name, "carry a planned item's card out of the list; a drop shadow and a tilt "
                + "on every frame");
    }

    private Item interactPlanned() {
        interact(() -> {
            Item plan = box("plan-1", 99, 96, 96, 12, "hsl(120,55%,42%)");
            plan.planned = true;
            plan.name = "Plan";
            app.state().items.add(plan);
            app.listPanel().rebuild();
            app.canvas().rebuildItems();
        });
        WaitForAsyncUtils.waitForFxEvents();
        settle(400);
        return app.state().items.get(app.state().items.size() - 1);
    }

    /** Into the 3D view and back, timing every frame of both flights. */
    private void flyInAndOut(String name) {
        List<Double> descent = new ArrayList<>();
        List<Double> ascent = new ArrayList<>();
        for (int rep = 0; rep < 3; rep++) {
            descent.addAll(framesDuring(() -> interact(() -> app.topBar().threeDButtonNode().fire()),
                    (long) (Transitions.BARS_LEAVE_LAYOUT_MS + Transitions.DESCENT_DELAY_MS
                            + Transitions.DESCENT_MS) + 700));
            ascent.addAll(framesDuring(() -> interact(() -> app.view3d().returnButton().fire()),
                    (long) Transitions.ASCENT_MS + 700));
            settle(400);
        }
        run.writeDurations(name + "-descent",
                "every frame of three descents into the 3D view", "frame", descent);
        run.writeDurations(name + "-ascent",
                "every frame of three ascents out of the 3D view", "frame", ascent);
    }

    /** Standing in the room, dragging to turn. This is what the user called laggy. */
    private void turnOnTheSpot(String name) {
        interact(() -> app.topBar().threeDButtonNode().fire());
        settle((long) (Transitions.BARS_LEAVE_LAYOUT_MS + Transitions.DESCENT_DELAY_MS
                + Transitions.DESCENT_MS) + 900);
        run.begin();
        for (int rep = 0; rep < 3; rep++) {
            run.nextRep(rep);
            moveTo(new Point2D(scene.getWindow().getX() + WIDTH / 2.0,
                    scene.getWindow().getY() + HEIGHT / 2.0));
            press(MouseButton.PRIMARY);
            for (int i = 0; i < DRAG_STEPS; i++) {
                moveBy(rep % 2 == 0 ? 8 : -8, 0);
            }
            release(MouseButton.PRIMARY);
            WaitForAsyncUtils.waitForFxEvents();
            settle(250);
        }
        run.write(name, "drag to turn on the spot in the 3D room; one redraw of the whole room "
                + "per step");
    }

    /**
     * The whole window, drawn once, with nothing capping it.
     *
     * <p>Every other number here is bounded below by the pulse, so on a machine that keeps up they
     * all read about one frame and a change of a few milliseconds is invisible in them. This one is
     * the work itself, and it is the number an optimization has to move. Same measurement M6.7a
     * item 3 used.
     */
    private void drawTheWholeWindow(String name) {
        List<Double> draws = new ArrayList<>();
        for (int rep = 0; rep < REPS; rep++) {
            final double angle = rep * 7.0;
            interact(() -> {
                app.view3d().camera().yaw = Math.toRadians(angle);
                app.view3d().render();
            });
            draws.add(WaitForAsyncUtils.waitForAsyncFx(20000, () -> {
                long at = System.nanoTime();
                scene.snapshot(null);
                return (System.nanoTime() - at) / 1e6;
            }));
        }
        interact(() -> app.view3d().returnButton().fire());
        settle((long) Transitions.ASCENT_MS + 700);
        run.writeDurations(name, "the whole window drawn once, standing in the 3D room, with "
                + "nothing capping it", "draw", draws);
    }

    // ---- plumbing -----------------------------------------------------------------------

    /** Runs something and returns the gap between every frame while it settles, in ms. */
    private List<Double> framesDuring(Runnable action, long settleMs) {
        List<Double> gaps = new ArrayList<>();
        long[] previous = {0};
        AnimationTimer clock = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (previous[0] != 0) {
                    gaps.add((now - previous[0]) / 1e6);
                }
                previous[0] = now;
            }
        };
        interact(clock::start);
        action.run();
        settle(settleMs);
        interact(clock::stop);
        return gaps;
    }

    private void settle(long millis) {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            WaitForAsyncUtils.sleep(10, TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    /** A box's middle, in screen coordinates, which is what the robot works in. */
    private Point2D centerOf(Item item) {
        Point2D origin = app.canvas().roomOriginInScene();
        double scale = app.canvas().fitScaleFactor();
        return new Point2D(
                scene.getWindow().getX() + scene.getX() + origin.getX()
                        + (item.x_px + Units.inchesToPx(item.w_in) / 2) * scale,
                scene.getWindow().getY() + scene.getY() + origin.getY()
                        + (item.y_px + Units.inchesToPx(item.l_in) / 2) * scale);
    }

    /**
     * A list row's middle, in screen coordinates.
     *
     * <p>Scrolls to it first: a row below the fold has no node, {@code rowFor} returns null, and
     * that arrives as a null pointer inside a lambda on the FX thread, which reads like a bug in
     * the app rather than a bench driving it wrong.
     */
    private Point2D centerOfRow(Item item) {
        interact(() -> app.listPanel().scrollTo(item.id));
        WaitForAsyncUtils.waitForFxEvents();
        settle(120);
        Bounds bounds = WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            var row = app.listPanel().rowFor(item.id);
            if (row == null) {
                throw new IllegalStateException("no row on screen for the item the bench wants to "
                        + "click; the list panel was not told the items changed");
            }
            return row.localToScreen(row.getBoundsInLocal());
        });
        return new Point2D(bounds.getCenterX(), bounds.getCenterY());
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
