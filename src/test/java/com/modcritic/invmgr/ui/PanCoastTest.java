package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Room;
import com.modcritic.invmgr.model.Units;
import java.util.List;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TouchEvent;
import javafx.scene.input.TouchPoint;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The room's coasting, wired up: B9.
 *
 * <p>{@code PanMomentumTest} proves the arithmetic decides correctly. This proves the view is
 * connected to it, and that the platform's own fling is thrown away before it can arrive.
 *
 * <p><b>What can be proved here and what genuinely cannot.</b> The gesture that pans the room on a
 * phone comes from a {@code ScrollGestureRecognizer} that Glass builds only when the app's own
 * Android launcher has set {@code com.sun.javafx.gestures.scroll=true}, so no scroll gesture
 * exists in this container at all and {@code ScrollPaneSkin}'s touch pan cannot be provoked. That
 * is why B2 took
 * six attempts and why a test for it passed identically with the fix deleted.
 *
 * <p>This one is different, and the difference is worth stating. A {@code ScrollEvent} carrying the
 * inertia flag is publicly constructible, so the suppression can be fired at the real view and
 * watched. And the sampling hangs off {@code hvalue} and {@code vvalue} rather than off the event's
 * deltas, so moving those two values by hand is exactly the input the phone would produce. What
 * remains unproven here is only that Android's own event stream has the shape assumed: that a pan
 * arrives as SCROLL_STARTED, some movement, then SCROLL_FINISHED. That still needs the phone.
 */
class PanCoastTest extends ApplicationTest {

    private RoomCanvasView canvas;
    private AppState state;
    private Item box;

    /** Counts the scroll events that got past the view, which is how consumption is observed. */
    private int reachedTheParent;

    @Override
    public void start(Stage stage) {
        state = new AppState();
        // Deliberately far bigger than the window, or there is nothing to pan and every span is
        // zero. A 60 ft room is 5760 px across against a 1400 px viewport.
        state.room = new Room(60, 50, 8);

        box = new Item();
        box.id = "item-id-11111111-2222-4333-8444-555555555555";
        box.name = "";
        box.serial = 1;
        box.w_in = 24;
        box.l_in = 24;
        box.h_in = 12;
        box.x_px = 192;
        box.y_px = 192;
        box.color = "hsl(200 60% 50%)";
        box.dragOrder = 1;
        state.items.add(box);

        canvas = new RoomCanvasView(state);
        HBox main = new HBox(canvas);
        HBox.setHgrow(canvas, Priority.ALWAYS);

        // ⚠ ON THE PARENT, not on the canvas. Event.fireEvent dispatches a COPY, so asking the
        // original event whether it was consumed always reads false; whether an ancestor was
        // reached is the observable question.
        main.addEventHandler(ScrollEvent.ANY, event -> reachedTheParent++);

        stage.setScene(new Scene(main, 1400, 900));
        stage.setMaximized(false);
        stage.setWidth(1400);
        stage.setHeight(900);
        stage.show();
    }

    // ------------------------------------------------------------------ the suppression

    @Test
    @DisplayName("the platform's own fling is swallowed and an ordinary scroll is not")
    void thePlatformsOwnFlingIsSwallowedAndAnOrdinaryScrollIsNot() {
        // Bounded both ways on purpose. A filter that consumed everything would satisfy the first
        // half alone, and would take the room's ability to be panned at all with it.
        fireScroll(ScrollEvent.SCROLL, 40, 0, true);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(0, reachedTheParent, "an inertia scroll must not get past the view");

        fireScroll(ScrollEvent.SCROLL, 40, 0, false);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, reachedTheParent, "an ordinary scroll must still reach the room");
    }

    // ------------------------------------------------------------------ the coast

    @Test
    @DisplayName("a flick leaves the room coasting past the finger")
    void aFlickLeavesTheRoomCoastingPastTheFinger() {
        double landed = pan(0.02, 8, 0);
        assertTrue(landed > 0, "the pan itself should have moved the room");

        double afterCoast = settle();
        assertTrue(afterCoast > landed + 0.001,
                "the room should carry on past where the finger left it, went from "
                        + landed + " to " + afterCoast);
    }

    @Test
    @DisplayName("a pan that ends slowly stops where it was put")
    void aPanThatEndsSlowlyStopsWhereItWasPut() {
        // 300 ms a step, which is far longer than it needs to be, and that is the point. TestFX's
        // own overhead is unpredictable and can only make a gap LONGER, so it can only make the
        // measured speed lower; a "must not fling" case is therefore safe against it, where a
        // "must fling" case is not. That is why the flick above asks for no gap at all rather than
        // a realistic one, and why the threshold itself is pinned in PanMomentumTest, where the
        // clock is a parameter and none of this arises.
        double landed = pan(0.0004, 5, 300);
        double afterCoast = settle();

        assertEquals(landed, afterCoast, 0.0005,
                "a considered pan must end where the finger left it");
    }

    @Test
    @DisplayName("a finger landing stops the room dead")
    void aFingerLandingStopsTheRoomDead() {
        pan(0.02, 8, 0);
        double moving = canvas.getHvalue();

        // A touch, the way every scrolling list on the phone answers one.
        Event.fireEvent(canvas, touchPressed());
        WaitForAsyncUtils.waitForFxEvents();
        double stopped = canvas.getHvalue();

        double afterWaiting = settle();
        assertEquals(stopped, afterWaiting, 0.0005,
                "the room must not creep on after a finger has landed on it");
        assertTrue(stopped < moving + 0.05, "and it must stop promptly, not eventually");
    }

    @Test
    @DisplayName("the scroll left over from a box drag never starts a coast")
    void theScrollLeftOverFromABoxDragNeverStartsACoast() {
        // ⚠ THE BOX HAS TO BE REALLY DRAGGED, and the first version of this case did not bother.
        // It fired a bare pair of scroll events with no box under a finger, so the guard it exists
        // to pin was not armed, and the mutation that deleted that guard survived it.
        //
        // The moment being reproduced is the 1.6 s AFTER a drag, when the platform's recognizer is
        // still sending and the app's own hold on hvalue has let go. A real deliberate pan cannot
        // land here, because a pan starts with a touch and a touch cancels the guard; only the
        // leftovers of the drag itself arrive with no new finger.
        Point2D center = centerOfBox();
        touch(TouchEvent.TOUCH_PRESSED, center.getX(), center.getY());
        touch(TouchEvent.TOUCH_MOVED, center.getX() + 120, center.getY());
        touch(TouchEvent.TOUCH_RELEASED, center.getX() + 120, center.getY());
        WaitForAsyncUtils.waitForFxEvents();

        // The flick moves hvalue itself, standing in for whatever the skin would have done with
        // the leftover gesture. What is being asserted is not that the room stayed put during it,
        // but that it did not carry on AFTERWARDS.
        double landed = flick(0.02, 8);
        assertEquals(landed, settle(), 0.0005,
                "a drag's leftover scroll must not leave the room coasting");
    }

    // ------------------------------------------------------------------ helpers

    private Point2D centerOfBox() {
        Point2D origin = canvas.roomOriginInScene();
        double scale = canvas.fitScaleFactor();
        return new Point2D(
                origin.getX() + (box.x_px + Units.inchesToPx(box.w_in) / 2) * scale,
                origin.getY() + (box.y_px + Units.inchesToPx(box.l_in) / 2) * scale);
    }

    private void touch(EventType<TouchEvent> type, double sceneX, double sceneY) {
        Rectangle rect = canvas.itemRect(box.id);
        TouchPoint point = new TouchPoint(1, TouchPoint.State.MOVED,
                sceneX, sceneY, sceneX, sceneY, rect, null);
        interact(() -> Event.fireEvent(rect, new TouchEvent(type, point,
                List.of(point), 1, false, false, false, false)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Drives one pan gesture by moving the pane the way the platform's skin would.
     *
     * @param perStep how much of the scrollable span each step covers
     * @param steps   how many steps
     * @param gapMs   how long to leave between them, which is what sets the speed
     * @return where the room ended up
     */
    private double pan(double perStep, int steps, long gapMs) {
        if (gapMs == 0) {
            return flick(perStep, steps);
        }
        fireScroll(ScrollEvent.SCROLL_STARTED, 0, 0, false);
        WaitForAsyncUtils.waitForFxEvents();

        for (int i = 0; i < steps; i++) {
            double next = canvas.getHvalue() + perStep;
            interact(() -> canvas.setHvalue(Math.min(1, next)));
            sleep(gapMs);
        }

        fireScroll(ScrollEvent.SCROLL_FINISHED, 0, 0, false);
        WaitForAsyncUtils.waitForFxEvents();
        return canvas.getHvalue();
    }

    /**
     * The whole gesture inside one pass on the JavaFX thread.
     *
     * <p><b>Forced, and the reason is the one this project keeps relearning.</b> A separate
     * {@code interact()} per step costs a full round trip to the JavaFX thread and back, and that
     * round trip is longer than {@code PanMomentum}'s hundred-millisecond window. So every sample
     * fell outside the window, the release measured no movement at all, and the room correctly
     * declined to fling. The first version of this test read that as a bug in the code; it was
     * measuring TestFX. Driving the samples inside a single pass puts them microseconds apart,
     * which is far faster than a finger and therefore lands on the speed ceiling, but the ceiling
     * is a real path and this case is about the wiring rather than about the speed.
     */
    private double flick(double perStep, int steps) {
        interact(() -> {
            Event.fireEvent(canvas, scroll(ScrollEvent.SCROLL_STARTED, 0, 0, false));
            for (int i = 0; i < steps; i++) {
                canvas.setHvalue(Math.min(1, canvas.getHvalue() + perStep));
            }
            Event.fireEvent(canvas, scroll(ScrollEvent.SCROLL_FINISHED, 0, 0, false));
        });
        return canvas.getHvalue();
    }

    /** Waits long enough for any coast to have finished, and reports where the room settled. */
    private double settle() {
        sleep(2200);
        WaitForAsyncUtils.waitForFxEvents();
        return canvas.getHvalue();
    }

    private void fireScroll(EventType<ScrollEvent> type, double dx, double dy, boolean inertia) {
        ScrollEvent event = scroll(type, dx, dy, inertia);
        interact(() -> Event.fireEvent(canvas, event));
    }

    private static ScrollEvent scroll(EventType<ScrollEvent> type, double dx, double dy,
            boolean inertia) {
        return new ScrollEvent(
                type,
                50, 50, 50, 50,
                false, false, false, false,
                true,                    // direct: it came from a finger
                inertia,
                dx, dy, dx, dy,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0,
                1, null);
    }

    private TouchEvent touchPressed() {
        TouchPoint point = new TouchPoint(1, TouchPoint.State.PRESSED, 50, 50, 50, 50, null, null);
        return new TouchEvent(TouchEvent.TOUCH_PRESSED, point,
                List.of(point), 1, false, false, false, false);
    }
}
