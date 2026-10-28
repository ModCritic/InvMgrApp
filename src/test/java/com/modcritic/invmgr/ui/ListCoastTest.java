package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Room;
import java.util.List;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TouchEvent;
import javafx.scene.input.TouchPoint;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The item list's coast after a flick: the room's momentum, wired into the list.
 *
 * <p>{@code PanMomentumTest} proves the arithmetic decides correctly and {@code PanCoastTest} that
 * the room is connected to it. This proves the item list is, on the real panel with a list long
 * enough to scroll. It is driven the way {@code PanCoastTest} drives the room, and for the same
 * reason: no scroll gesture exists on a desktop, but the coast is fed by {@code vvalue} rather than
 * by the gesture's own deltas, so moving {@code vvalue} by hand between a {@code SCROLL_STARTED}
 * and a {@code SCROLL_FINISHED} is the input the phone produces.
 */
class ListCoastTest extends ApplicationTest {

    /** Far more rows than fit, so the list has somewhere to coast to. */
    private static final int ITEMS = 200;

    private ItemListPanel listPanel;
    private ScrollPane scroller;

    @Override
    public void start(Stage stage) {
        AppState state = new AppState();
        state.room = new Room(200, 200, 8);
        for (int i = 0; i < ITEMS; i++) {
            Item item = new Item();
            item.id = "item-id-" + String.format("%036d", i);
            item.serial = i + 1;
            item.dragOrder = i + 1;
            item.x_px = (2 + (i % 20) * 9) * 96;
            item.y_px = (2 + (i / 20) * 9) * 96;
            item.w_in = 24;
            item.l_in = 24;
            item.h_in = 12;
            item.color = "hsl(" + (i * 13 % 360) + ",55%,42%)";
            item.name = String.format("row%03d", i);
            state.items.add(item);
        }

        javafx.scene.layout.Pane ghostLayer = new javafx.scene.layout.Pane();
        listPanel = new ItemListPanel(state, new DragGhost(ghostLayer));
        listPanel.rebuild();

        stage.setScene(new Scene(new HBox(listPanel, ghostLayer), 1400, 900));
        // Pinned; TestFX reuses one stage across the whole run.
        stage.setMaximized(false);
        stage.setWidth(1400);
        stage.setHeight(900);
        stage.show();

        for (Node child : listPanel.getChildrenUnmodifiable()) {
            if (child instanceof ScrollPane found) {
                scroller = found;
            }
        }
    }

    @Test
    @DisplayName("a flick leaves the list coasting past where the finger left it")
    void aFlickLeavesTheListCoasting() {
        scrollTo(0.1);
        double landed = flick(0.01, 8);
        assertTrue(landed > 0.15, "the flick itself should have moved the list");

        double afterCoast = settle();
        assertTrue(afterCoast > landed + 0.01,
                "the list should carry on past where the finger left it, went from "
                        + landed + " to " + afterCoast);
    }

    @Test
    @DisplayName("a scroll that ends slowly stops where it was put")
    void aSlowScrollStopsWhereItWasPut() {
        // A long gap between steps, so the speed at release is near zero. TestFX's overhead can
        // only make a gap longer, so this case cannot be pushed into a fling by a slow machine.
        scrollTo(0.1);
        fireScroll(ScrollEvent.SCROLL_STARTED, 0, false);
        for (int i = 0; i < 5; i++) {
            double next = scroller.getVvalue() + 0.002;
            interact(() -> scroller.setVvalue(next));
            sleep(300);
        }
        fireScroll(ScrollEvent.SCROLL_FINISHED, 0, false);
        double landed = scroller.getVvalue();

        assertEquals(landed, settle(), 0.0005, "a considered scroll must end where it was left");
    }

    @Test
    @DisplayName("a finger landing stops the list dead")
    void aFingerLandingStopsTheList() {
        scrollTo(0.1);
        flick(0.01, 8);
        assertTrue(listPanel.isCoasting(), "the flick should have started a coast");

        interact(() -> Event.fireEvent(scroller, touchPressed()));
        WaitForAsyncUtils.waitForFxEvents();
        double stopped = scroller.getVvalue();

        assertFalse(listPanel.isCoasting(), "a finger landing must end the coast");
        assertEquals(stopped, settle(), 0.0005, "the list must not creep on after a finger lands");
    }

    @Test
    @DisplayName("JavaFX's own fling is swallowed, and an ordinary scroll still moves the list")
    void javaFxsOwnFlingIsSwallowed() {
        // Bounded both ways: a filter that swallowed every scroll would pass the first half and
        // leave the list unscrollable by the mouse wheel.
        //
        // Fired at the rows rather than at the scroll pane, because that is where a real scroll
        // lands, and the pane's skin only scrolls for events that pass up through its viewport.
        scrollTo(0.5);
        double before = scroller.getVvalue();
        Node rows = scroller.getContent();
        interact(() -> Event.fireEvent(rows, scroll(ScrollEvent.SCROLL, -120, true)));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(before, scroller.getVvalue(), 0.0,
                "a scroll from JavaFX's own fling must not move the list; the coast does that");

        interact(() -> Event.fireEvent(rows, scroll(ScrollEvent.SCROLL, -120, false)));
        WaitForAsyncUtils.waitForFxEvents();
        assertNotEquals(before, scroller.getVvalue(), "an ordinary scroll must still move the list");
    }

    @Test
    @DisplayName("a coast that reaches the bottom of the list stops there")
    void aCoastStopsAtTheBottom() {
        scrollTo(0.9);
        flick(0.01, 4);

        // Checked well before a coast from the speed ceiling would die out by itself, at about
        // 1.4 s, or a coast that pressed on against the end would pass too.
        sleep(600);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1.0, scroller.getVvalue(), 0.0, "the coast should run the list to its last row");
        assertFalse(listPanel.isCoasting(), "and stop there rather than coast against the end");
    }

    // ------------------------------------------------------------------ helpers

    private void scrollTo(double where) {
        interact(() -> scroller.setVvalue(where));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * A whole flick inside one pass on the JavaFX thread.
     *
     * <p>A separate {@code interact()} per step is a round trip to the JavaFX thread and back, longer
     * than {@code PanMomentum}'s hundred-millisecond window, so every sample would fall outside it
     * and nothing would ever coast. In one pass the steps are microseconds apart, which lands on
     * the speed ceiling, a real path, and this test is about the wiring rather than the speed.
     */
    private double flick(double perStep, int steps) {
        interact(() -> {
            Event.fireEvent(scroller, scroll(ScrollEvent.SCROLL_STARTED, 0, false));
            for (int i = 0; i < steps; i++) {
                scroller.setVvalue(Math.min(1, scroller.getVvalue() + perStep));
            }
            Event.fireEvent(scroller, scroll(ScrollEvent.SCROLL_FINISHED, 0, false));
        });
        return scroller.getVvalue();
    }

    /** Waits longer than any coast lasts, and reports where the list settled. */
    private double settle() {
        sleep(2200);
        WaitForAsyncUtils.waitForFxEvents();
        return scroller.getVvalue();
    }

    private void fireScroll(EventType<ScrollEvent> type, double dy, boolean inertia) {
        ScrollEvent event = scroll(type, dy, inertia);
        interact(() -> Event.fireEvent(scroller, event));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static ScrollEvent scroll(EventType<ScrollEvent> type, double dy, boolean inertia) {
        return new ScrollEvent(
                type,
                50, 50, 50, 50,
                false, false, false, false,
                true,                    // direct: it came from a finger
                inertia,
                0, dy, 0, dy,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0,
                1, null);
    }

    private static TouchEvent touchPressed() {
        TouchPoint point = new TouchPoint(1, TouchPoint.State.PRESSED, 50, 50, 50, 50, null, null);
        return new TouchEvent(TouchEvent.TOUCH_PRESSED, point,
                List.of(point), 1, false, false, false, false);
    }
}
