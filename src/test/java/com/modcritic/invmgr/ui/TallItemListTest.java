package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.model.Item;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.VerticalDirection;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The item list on a tall touch screen, scrolled the way a finger scrolls it.
 *
 * <p><b>The bug this exists for.</b> On a phone set to a tablet's size (1706 x 960), three swipes
 * down a 103 item list left about two rows of blank space at the bottom of the panel, where the next
 * items belonged, and it stayed there. Two faults in {@code ItemListPanel.refill} made it, and either
 * one is enough to make a gap:
 *
 * <ul>
 *   <li><b>A busy row kept outside the window broke the layout.</b> The list keeps a row node it
 *       must not recycle (the last one pressed, say) even after it scrolls away, and it used to lay
 *       that row out directly after the top spacer, with nothing standing in for the rows between it
 *       and the ones on screen. Every row on screen moved up by that many rows and the list got
 *       shorter by the same amount, so the rows ran out before the viewport did.
 *       {@link #aRowTappedThenScrolledAwayLeavesNoGap} is that one alone.
 *   <li><b>A swipe that started on a row never let go of it.</b> Every scroll step rebuilt the
 *       scroller's child list, which takes each row out of the scene and puts it back. JavaFX drops
 *       a node that leaves the scene from the press it is part of, and sends the rest of the
 *       gesture, release included, to its parent, so the row never heard the finger lift and stayed
 *       busy for good. On a phone every swipe starts on a row. {@link #aSwipeLetsGoOfTheRowItStartedOn}
 *       is that one alone.
 * </ul>
 *
 * <p><b>How a finger is stood in for.</b> On a phone JavaFX's {@code ScrollPaneSkin} pans the list
 * from the mouse events Android makes out of a finger, because the platform reports a touch screen.
 * This desktop reports none, and the same drag code in the skin runs instead when the scroll pane is
 * {@linkplain ScrollPane#setPannable pannable} (both conditions guard one branch, read out of the
 * JavaFX 21 skin's bytecode), so these tests switch that on. The fling that carries on after a fast
 * swipe arrives as scroll events, the kind a mouse wheel sends, and it leaves the last row pressed
 * far out of the window; the tapped-then-scrolled test makes that state with the wheel.
 */
class TallItemListTest extends ApplicationTest {

    /** The size of the display the bug was found on, in the units JavaFX lays out in. */
    private static final int WIDTH = 1706;
    private static final int HEIGHT = 960;

    /** Enough that forty rows down is nowhere near either end. */
    private static final int ITEMS = 150;

    /** How far the list is scrolled, in rows: about where the gap showed on the phone. */
    private static final int[] SWIPES = {15, 13, 12};

    /**
     * The window every other class in the suite expects to find, put back afterwards. TestFX
     * reuses one stage for the whole run and clicks in screen coordinates, so a class that leaves
     * it smaller sends every later class's clicks off the edge.
     */
    private static final int SHARED_STAGE_WIDTH = 2560;
    private static final int SHARED_STAGE_HEIGHT = 1440;

    private static String restorePlatform;
    private static Stage sharedStage;

    private App app;

    @BeforeAll
    static void forceTheTouchLayout() {
        restorePlatform = System.getProperty(Device.OVERRIDE_PROPERTY);
        System.setProperty(Device.OVERRIDE_PROPERTY, "android");
    }

    @AfterAll
    static void putEverythingBack() {
        if (restorePlatform == null) {
            System.clearProperty(Device.OVERRIDE_PROPERTY);
        } else {
            System.setProperty(Device.OVERRIDE_PROPERTY, restorePlatform);
        }
        if (sharedStage != null) {
            WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
                sharedStage.setWidth(SHARED_STAGE_WIDTH);
                sharedStage.setHeight(SHARED_STAGE_HEIGHT);
                sharedStage.setX(0);
                sharedStage.setY(0);
                return null;
            });
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    @Override
    public void start(Stage stage) {
        sharedStage = stage;
        app = new App();
        app.start(stage);
        stage.setMaximized(false);
        stage.setWidth(WIDTH);
        stage.setHeight(HEIGHT);
        stage.setX(0);
        stage.setY(0);
    }

    // ------------------------------------------------------------------ the bug as found

    @Test
    @DisplayName("rows reach the bottom of a tall list after swiping forty rows down it")
    void aSwipedListCoversItsViewport() {
        openFullList();
        for (int rows : SWIPES) {
            swipeUp(firstRowFullyAboveBottom(), rows);
            assertRowsCoverTheViewport("after a swipe of " + rows + " rows");
        }
    }

    // ------------------------------------------------------------------ each fault alone

    @Test
    @DisplayName("a row tapped and then scrolled far past leaves no gap in the list")
    void aRowTappedThenScrolledAwayLeavesNoGap() {
        openFullList();

        // Tapped, so it is the last row pressed and must keep its node for a double-tap. Then the
        // list is scrolled forty rows with the wheel, which is how a desktop scrolls and what a
        // phone's fling sends once the finger has lifted. A wheel step is one row here.
        Node tapped = rowsOnScreen().get(1);
        clickOn(middleOf(tapped));
        WaitForAsyncUtils.waitForFxEvents();

        // Checked after every step. Once the layout is wrong it shifts with each step, so where it
        // happens to land after forty is luck: run whole, the broken list sometimes ended covered.
        for (int step = 1; step <= 40; step++) {
            scroll(1, VerticalDirection.DOWN);
            WaitForAsyncUtils.waitForFxEvents();
            assertRowsCoverTheViewport("after tapping a row and scrolling " + step
                    + " wheel steps past it");
        }
    }

    @Test
    @DisplayName("a swipe that starts on a row lets go of that row when the finger lifts")
    void aSwipeLetsGoOfTheRowItStartedOn() {
        List<Item> items = openFullList();

        Node start = firstRowFullyAboveBottom();
        Item startedOn = itemShownBy(start);
        swipeUp(start, 10);

        // Another row pressed, so the first is no longer the last one pressed either. From here
        // the only thing that could keep its node alive is a press that never ended.
        clickOn(middleOf(rowsOnScreen().get(1)));
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> app.listPanel().scrollTo(items.get(ITEMS - 20).id));
        settle(200);

        assertNull(app.listPanel().rowFor(startedOn.id),
                "the row a swipe started on still has a node a hundred rows away, so it never "
                        + "heard the finger lift and will never be recycled; each swipe leaks one");
    }

    // ------------------------------------------------------------------ what the fix exposed

    /**
     * A swipe that starts on a row does not choose that row.
     *
     * <p>Until the release reached the row, JavaFX's click never did either, so no swipe ever
     * selected anything, by accident. With the release delivered, the press and the release land
     * on the same node (the row travels with the finger), and JavaFX sends that node a click.
     *
     * <p>Fired by hand, because a desktop robot cannot make the {@code synthesized} flag that marks
     * the finger's copy of the mouse on a phone. The click is fired too, the way JavaFX's own
     * click generator would send it: with the release's position and its flag.
     */
    @Test
    @DisplayName("a finger swipe that starts on a row does not select it")
    void aFingerSwipeDoesNotSelectTheRowItStartedOn() {
        openFullList();
        Node row = rowsOnScreen().get(5);
        double rowHeight = WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> row.getBoundsInParent().getHeight());

        finger(row, MouseEvent.MOUSE_PRESSED, 0);
        finger(row, MouseEvent.MOUSE_DRAGGED, -rowHeight);
        finger(row, MouseEvent.MOUSE_DRAGGED, -3 * rowHeight);
        finger(row, MouseEvent.MOUSE_RELEASED, -3 * rowHeight);
        finger(row, MouseEvent.MOUSE_CLICKED, -3 * rowHeight);

        assertNull(app.canvas().selectedId(),
                "a swipe that started on a row selected it; on a phone every scroll would");
    }

    @Test
    @DisplayName("a finger tap on a row still selects it")
    void aFingerTapStillSelects() {
        openFullList();
        Node row = rowsOnScreen().get(5);
        Item shown = itemShownBy(row);

        // A real fingertip wobbles a little, so the tap moves a few pixels and is still a tap.
        finger(row, MouseEvent.MOUSE_PRESSED, 0);
        finger(row, MouseEvent.MOUSE_DRAGGED, Tokens.TAP_SLOP / 2);
        finger(row, MouseEvent.MOUSE_RELEASED, Tokens.TAP_SLOP / 2);
        finger(row, MouseEvent.MOUSE_CLICKED, Tokens.TAP_SLOP / 2);

        assertEquals(shown.id, app.canvas().selectedId(), "a tap on a row should select its item");
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * One mouse event from a finger, flagged synthesized the way a phone flags it, aimed at the
     * middle of {@code row} as it was when the gesture started and {@code dy} below that.
     */
    private void finger(Node row, EventType<MouseEvent> type, double dy) {
        if (type == MouseEvent.MOUSE_PRESSED) {
            fingerDown = WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
                Bounds box = row.localToScene(row.getBoundsInLocal());
                return new Point2D(box.getCenterX(), box.getCenterY());
            });
        }
        MouseEvent event = new MouseEvent(type, fingerDown.getX(), fingerDown.getY() + dy, 0, 0,
                MouseButton.PRIMARY, 1, false, false, false, false,
                type == MouseEvent.MOUSE_PRESSED || type == MouseEvent.MOUSE_DRAGGED,
                false, false, true, false, dy == 0, null);
        interact(() -> Event.fireEvent(row, event));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Where the current hand-made finger went down, in the window's coordinates. */
    private Point2D fingerDown;

    /** Fills the room, opens the list drawer, and makes the list pan from a drag like a phone's. */
    private List<Item> openFullList() {
        interact(() -> {
            app.state().room.w = 200;
            app.state().room.l = 200;
            app.state().items.clear();
            for (int i = 0; i < ITEMS; i++) {
                Item item = new Item();
                item.id = "item-id-" + String.format("%036d", i);
                item.serial = i + 1;
                item.dragOrder = i + 1;
                item.x_px = (2 + (i % 15) * 12) * 96;
                item.y_px = (2 + (i / 15) * 12) * 96;
                item.w_in = 24;
                item.l_in = 24;
                item.h_in = 12;
                item.color = "hsl(" + (i * 13 % 360) + ",55%,42%)";
                // Zero padded, so name order is index order.
                item.name = String.format("row%03d", i);
                app.state().items.add(item);
            }
            app.canvas().rebuildItems();
            app.listPanel().rebuild();
            scroller().setPannable(true);
            app.drawers().toggle(app.listPanel());
        });
        // The drawer slides in, and the rows are measured once the panel has a size.
        settle(800);
        assertNotNull(app.drawers().openDrawer(), "the list drawer should be open");
        return app.state().items;
    }

    /**
     * A finger's swipe up: down on {@code row}, up the screen by {@code rows} rows in small steps,
     * and off again. Moves the list down by about that many rows.
     */
    private void swipeUp(Node row, int rows) {
        Point2D from = middleOf(row);
        double rowHeight = WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> row.getBoundsInParent().getHeight());
        moveTo(from);
        press(MouseButton.PRIMARY);
        int steps = 20;
        for (int i = 1; i <= steps; i++) {
            moveTo(new Point2D(from.getX(), from.getY() - rows * rowHeight * i / steps));
        }
        release(MouseButton.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();
        settle(200);
    }

    /**
     * Fails unless the row nodes cover the viewport from its top edge to its bottom edge with no
     * gap, which is the one thing the bug broke. Measured in the window's coordinates, from where
     * the rows are actually drawn, rather than from anything the panel says about itself.
     */
    private void assertRowsCoverTheViewport(String when) {
        WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            Bounds viewport = viewportInScene();
            List<Bounds> rows = new ArrayList<>();
            for (Node row : allRows()) {
                rows.add(row.localToScene(row.getBoundsInLocal()));
            }
            rows.sort((a, b) -> Double.compare(a.getMinY(), b.getMinY()));

            double coveredTo = viewport.getMinY();
            for (Bounds row : rows) {
                if (row.getMaxY() <= coveredTo) {
                    continue;
                }
                if (row.getMinY() > coveredTo + 0.5) {
                    break;
                }
                coveredTo = row.getMaxY();
                if (coveredTo >= viewport.getMaxY()) {
                    return null;
                }
            }
            fail(when + ": the rows stop " + String.format("%.1f", viewport.getMaxY() - coveredTo)
                    + " px short of the bottom of a " + String.format("%.1f", viewport.getHeight())
                    + " px viewport, and the list below them is blank");
            return null;
        });
    }

    /** The lowest row that is wholly inside the viewport, which is where a thumb starts a swipe. */
    private Node firstRowFullyAboveBottom() {
        return WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            Bounds viewport = viewportInScene();
            Node lowest = null;
            for (Node row : allRows()) {
                Bounds at = row.localToScene(row.getBoundsInLocal());
                if (at.getMinY() >= viewport.getMinY() && at.getMaxY() <= viewport.getMaxY()
                        && (lowest == null || at.getMinY() > lowest.localToScene(
                                lowest.getBoundsInLocal()).getMinY())) {
                    lowest = row;
                }
            }
            assertNotNull(lowest, "no row is wholly inside the viewport");
            return lowest;
        });
    }

    /** Row nodes at least partly inside the viewport, top to bottom. */
    private List<Node> rowsOnScreen() {
        return WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            Bounds viewport = viewportInScene();
            List<Node> found = new ArrayList<>();
            for (Node row : allRows()) {
                Bounds at = row.localToScene(row.getBoundsInLocal());
                if (at.getMaxY() > viewport.getMinY() && at.getMinY() < viewport.getMaxY()) {
                    found.add(row);
                }
            }
            found.sort((a, b) -> Double.compare(a.getBoundsInParent().getMinY(),
                    b.getBoundsInParent().getMinY()));
            return found;
        });
    }

    /** Every row node the scroller holds, on screen or not. The spacers have no label. */
    private List<Node> allRows() {
        List<Node> rows = new ArrayList<>();
        for (Node child : ((Region) scroller().getContent()).getChildrenUnmodifiable()) {
            if (child.isVisible() && nameOn(child) != null) {
                rows.add(child);
            }
        }
        return rows;
    }

    private Bounds viewportInScene() {
        Bounds viewport = scroller().getViewportBounds();
        return scroller().localToScene(new BoundingBox(scroller().snappedLeftInset(),
                scroller().snappedTopInset(), viewport.getWidth(), viewport.getHeight()));
    }

    private ScrollPane scroller() {
        for (Node child : app.listPanel().getChildrenUnmodifiable()) {
            if (child instanceof ScrollPane found) {
                return found;
            }
        }
        throw new IllegalStateException("the item list has no ScrollPane in it any more");
    }

    private Item itemShownBy(Node row) {
        String name = WaitForAsyncUtils.waitForAsyncFx(5000, () -> nameOn(row));
        for (Item item : app.state().items) {
            if (item.name.equals(name)) {
                return item;
            }
        }
        throw new IllegalStateException("no item named " + name);
    }

    /** The text on a row, or null if this node is not a row. */
    private static String nameOn(Node row) {
        if (!(row instanceof Region region)) {
            return null;
        }
        for (Node child : region.getChildrenUnmodifiable()) {
            if (child instanceof Label label) {
                return label.getText();
            }
        }
        return null;
    }

    private Point2D middleOf(Node row) {
        Bounds onScreen = WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> row.localToScreen(row.getBoundsInLocal()));
        return new Point2D(onScreen.getCenterX(), onScreen.getCenterY());
    }

    private void settle(long millis) {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            WaitForAsyncUtils.sleep(10, TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
    }
}
