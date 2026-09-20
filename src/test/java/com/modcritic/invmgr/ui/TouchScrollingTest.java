package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Room;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * That a finger can still scroll the lists: the half of the touch work that is about consuming
 * <em>less</em>.
 *
 * <h2>Why this needs testing at all</h2>
 *
 * <p>Android does not send scroll gestures. What it sends for a finger dragging across the glass
 * is a mouse press and a stream of mouse drags, each flagged as synthesized, and JavaFX's own
 * {@code ScrollPaneSkin} pans its content from exactly those. So on a phone <b>the scrolling and
 * the double-firing problem are the same events</b>: suppress them too widely and every list in
 * the app freezes, and it freezes only on the phone, because a desktop mouse never sets that flag.
 *
 * <p>None of that can be seen by watching the app here. What can be checked, and is what these
 * tests do, is whether the event survives its journey: {@code isConsumed()} after dispatch.
 */
class TouchScrollingTest extends ApplicationTest {

    private ItemListPanel listPanel;
    private RoomCanvasView canvas;
    private AppState state;
    private Item planned;
    private Item placed;

    @Override
    public void start(Stage stage) {
        state = new AppState();
        state.room = new Room(20, 16, 8);

        placed = item("item-id-11111111-2222-4333-8444-555555555555", 1, false);
        planned = item("item-id-22222222-3333-4444-8555-666666666666", 2, true);
        state.items.add(placed);
        state.items.add(planned);

        canvas = new RoomCanvasView(state);

        javafx.scene.layout.Pane ghostLayer = new javafx.scene.layout.Pane();
        ghostLayer.setMouseTransparent(true);
        listPanel = new ItemListPanel(state, new DragGhost(ghostLayer));
        listPanel.rebuild();

        StackPane main = new StackPane(canvas, ghostLayer);
        Scene scene = new Scene(new javafx.scene.layout.HBox(listPanel, main), 1400, 900);
        stage.setScene(scene);
        // Pinned; TestFX reuses one stage across the whole run.
        stage.setMaximized(false);
        stage.setWidth(1400);
        stage.setHeight(900);
        stage.show();
    }

    @Test
    @DisplayName("dragging a planned row mostly downward leaves the event for the scroller")
    void aVerticalDragOnAPlannedRowIsNotEaten() {
        Region row = listPanel.rowFor(planned.id);

        reachedThePanel(row, MouseEvent.MOUSE_PRESSED, 40, 10);

        assertTrue(reachedThePanel(row, MouseEvent.MOUSE_DRAGGED, 42, 60),
                "a mostly-vertical drag is someone scrolling the list, and the ScrollPane above "
                        + "this row can only see it if the row lets it past");
    }

    @Test
    @DisplayName("dragging a planned row sideways is taken, because that is lifting it out")
    void aHorizontalDragOnAPlannedRowIsEaten() {
        Region row = listPanel.rowFor(planned.id);

        reachedThePanel(row, MouseEvent.MOUSE_PRESSED, 40, 10);

        assertFalse(reachedThePanel(row, MouseEvent.MOUSE_DRAGGED, 120, 12),
                "a sideways drag is the row being pulled into the room, and the list must not "
                        + "scroll at the same time");
    }

    @Test
    @DisplayName("the press on a row is never eaten, whichever way the finger later goes")
    void thePressIsNeverEaten() {
        Region row = listPanel.rowFor(planned.id);

        // The ScrollPane records where a pan started from on the press. Eat it and the pan has no
        // origin, so the list does not move however far the finger travels afterwards.
        assertTrue(reachedThePanel(row, MouseEvent.MOUSE_PRESSED, 40, 10),
                "the scroller needs the press to know where a pan began");
    }

    @Test
    @DisplayName("the direction the finger chose first sticks for the rest of the gesture")
    void theDecisionSticks() {
        Region row = listPanel.rowFor(planned.id);

        reachedThePanel(row, MouseEvent.MOUSE_PRESSED, 40, 10);
        assertFalse(reachedThePanel(row, MouseEvent.MOUSE_DRAGGED, 120, 12), "it started as a lift");

        // Now curl upward, hard. It is still a lift: a hand that starts sideways and drifts is not
        // asking for the list to start scrolling half way through.
        assertFalse(reachedThePanel(row, MouseEvent.MOUSE_DRAGGED, 130, 400), "and it stays one");
    }

    @Test
    @DisplayName("an ordinary row eats nothing at all, so the list scrolls anywhere on it")
    void anOrdinaryRowIsTransparentToTheScroller() {
        Region row = listPanel.rowFor(placed.id);

        // Only planned rows can be dragged out, so an ordinary row has nothing to claim. Most of
        // a full list is ordinary rows, so this is most of the list's scrollable area.
        assertTrue(reachedThePanel(row, MouseEvent.MOUSE_PRESSED, 40, 10));
        assertTrue(reachedThePanel(row, MouseEvent.MOUSE_DRAGGED, 42, 90));
    }

    @Test
    @DisplayName("tapping an ordinary row still selects it, through the mouse click Android invents")
    void tappingAnOrdinaryRowStillSelects() {
        Item[] selected = new Item[1];
        listPanel.setOnSelect(item -> selected[0] = item);

        Region row = listPanel.rowFor(placed.id);
        reachedThePanel(row, MouseEvent.MOUSE_CLICKED, 40, 10);

        // A tap on a row arrives as a synthesized click and nothing else. The original relies on
        // the browser's synthetic click in exactly the same way. If a filter is ever added on the
        // list to stop double-firing, this is the test that says what it cost.
        assertEquals(placed, selected[0], "a tap on a row should select it");
    }

    // -------------------------------------------------------------------- helpers

    /**
     * Fires one synthesized mouse event at a row and reports whether it got past the row to the
     * panel above it. Coordinates are relative to the row, which is how a finger arrives.
     *
     * <p><b>Asking an ancestor is the only way to ask this.</b> {@code Event.fireEvent} dispatches
     * a <em>copy</em>, so calling {@code isConsumed()} on the object handed in always answers
     * false however thoroughly the row ate it: a test written that way fails on correct code and,
     * worse, would pass on broken code once the assertion was flipped to match it.
     *
     * <p>The spy sits on the row's immediate parent rather than on the panel, because a
     * {@code ScrollPane} consumes mouse presses itself (its behavior class installs handlers that
     * auto-consume), so nothing that reaches the scroller ever gets any further. One step above
     * the row is where "did the row let it past?" is still answerable.
     */
    private boolean reachedThePanel(Node row, EventType<MouseEvent> type, double x, double y) {
        boolean[] arrived = {false};
        javafx.event.EventHandler<MouseEvent> spy = event -> arrived[0] = true;
        javafx.scene.Parent above = row.getParent();
        above.addEventHandler(type, spy);
        try {
            javafx.geometry.Point2D inScene = row.localToScene(x, y);
            MouseEvent event = new MouseEvent(type,
                    inScene.getX(), inScene.getY(), inScene.getX(), inScene.getY(),
                    MouseButton.PRIMARY, 1,
                    false, false, false, false,
                    true,              // primaryButtonDown, as a finger's press always is
                    false, false,
                    true,              // synthesized: this is Android's twin of a touch
                    false, false, null);
            interact(() -> Event.fireEvent(row, event));
            WaitForAsyncUtils.waitForFxEvents();
        } finally {
            above.removeEventHandler(type, spy);
        }
        return arrived[0];
    }

    private static Item item(String id, int serial, boolean planned) {
        Item made = new Item();
        made.id = id;
        made.name = "box " + serial;
        made.serial = serial;
        made.w_in = 24;
        made.l_in = 24;
        made.h_in = 12;
        made.x_px = 100 * serial;
        made.y_px = 100;
        made.color = "hsl(200 60% 50%)";
        made.dragOrder = serial;
        made.planned = planned;
        return made;
    }
}
