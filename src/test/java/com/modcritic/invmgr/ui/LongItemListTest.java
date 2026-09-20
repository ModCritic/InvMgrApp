package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.model.Item;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The item list with more rows in it than fit on the screen.
 *
 * <p><b>Why this exists.</b> Every other test of the list uses a handful of items, and a handful
 * of items is the case where nothing interesting can go wrong. M6.7b measured a 200 row list on the
 * phone at half the frame rate of a 12 row one, and the fix for that is to stop keeping a node per
 * item, which means <b>the node under your finger can stop being the row it was</b>. That is the
 * exact situation {@code ItemListPanel}'s class documentation says destroys double-click.
 *
 * <p>So these are the behaviors that a long list has to keep whatever the panel does underneath,
 * and <b>every one of them passes against the version that keeps a node per item</b>. That is the
 * point: they describe the app, not the implementation, and they were confirmed green before the
 * implementation changed so that a later failure means a regression rather than a wrong test.
 *
 * <p>The one exception is {@link #theListDoesNotKeepANodePerItem}, which is the new requirement and
 * is expected to fail until the panel is virtualized. It is marked as such.
 */
class LongItemListTest extends ApplicationTest {

    private static final int WIDTH = 1600;
    private static final int HEIGHT = 900;

    /** Comfortably more rows than fit, so scrolling has somewhere to go. */
    private static final int ITEMS = 200;

    private App app;

    @Override
    public void start(Stage stage) {
        app = new App();
        app.start(stage);
        stage.setMaximized(false);
        stage.setWidth(WIDTH);
        stage.setHeight(HEIGHT);
        stage.setX(0);
        stage.setY(0);
    }

    /**
     * Fills the room with {@link #ITEMS} boxes, named so that name order is also index order.
     *
     * <p>Zero padded on purpose: the list sorts by name with numbers read as numbers, and a test
     * that depended on that subtlety would be testing the sort rather than the scrolling.
     */
    private List<Item> fill() {
        interact(() -> {
            app.state().room.w = 200;
            app.state().room.l = 200;
            app.state().items.clear();
            for (int i = 0; i < ITEMS; i++) {
                Item item = new Item();
                item.id = "item-id-" + String.format("%036d", i);
                item.serial = i + 1;
                item.dragOrder = i + 1;
                item.x_px = (2 + (i % 20) * 9) * 96;
                item.y_px = (2 + (i / 20) * 9) * 96;
                item.w_in = 24;
                item.l_in = 24;
                item.h_in = 12 + (i % 7) * 6;
                item.color = "hsl(" + (i * 13 % 360) + ",55%,42%)";
                item.name = String.format("row%03d", i);
                app.state().items.add(item);
            }
            app.canvas().rebuildItems();
            app.listPanel().rebuild();
        });
        WaitForAsyncUtils.waitForFxEvents();
        settle(400);
        return app.state().items;
    }

    // ---------------------------------------------------------------- the behaviors

    @Test
    @DisplayName("every row can be reached by scrolling, in order, with none missing or doubled")
    void scrollingReachesEveryRow() {
        fill();
        Set<String> seen = namesSeenWhileScrollingTop();

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < ITEMS; i++) {
            expected.add(String.format("row%03d", i));
        }
        assertEquals(expected, new ArrayList<>(seen),
                "scrolling from top to bottom should show every name exactly once and in order");
    }

    @Test
    @DisplayName("a row shows the item it acts on, after the list has been scrolled a long way")
    void aRowActsOnTheItemItShows() {
        fill();
        scrollTo(0.75);

        List<Node> onScreen = rowsOnScreen();
        assertTrue(onScreen.size() >= 3, "expected rows on screen, got " + onScreen.size());

        // The middle one, so it is nowhere near a boundary a recycler might be fiddling with.
        Node row = onScreen.get(onScreen.size() / 2);
        String shown = nameOn(row);
        clickOn(middleOf(row));
        WaitForAsyncUtils.waitForFxEvents();

        Item selected = itemNamed(shown);
        assertEquals(selected.id, app.canvas().selectedId(),
                "clicking the row reading '" + shown + "' must select that item and no other");
    }

    @Test
    @DisplayName("double-clicking a row opens its dialog, not another row's")
    void doubleClickEditsTheRightItem() {
        fill();
        scrollTo(0.5);

        Node row = rowsOnScreen().get(2);
        String shown = nameOn(row);
        doubleClickOn(middleOf(row));
        WaitForAsyncUtils.waitForFxEvents();
        settle(200);

        assertTrue(app.editDialog().isShowing(),
                "double-clicking a row in a long list must open the edit dialog; it did not, which "
                        + "is the failure ItemListPanel's own class documentation is about");
        assertEquals(shown, app.editDialog().nameField().getText(),
                "and the dialog must be editing the item whose name was on the row clicked");
    }

    @Test
    @DisplayName("a click and then a double-click on the same row still reaches the dialog")
    void aSecondClickOnTheSameRowStillEdits() {
        fill();
        scrollTo(0.3);

        Node row = rowsOnScreen().get(2);
        String shown = nameOn(row);
        Point2D at = middleOf(row);

        // Select first, which is what a person does, and is what used to destroy the node.
        clickOn(at);
        WaitForAsyncUtils.waitForFxEvents();
        settle(150);
        doubleClickOn(at);
        WaitForAsyncUtils.waitForFxEvents();
        settle(200);

        assertTrue(app.editDialog().isShowing(),
                "selecting a row and then double-clicking it must still open the dialog");
        assertEquals(shown, app.editDialog().nameField().getText());
    }

    @Test
    @DisplayName("selecting an item off screen shows as selected once it is scrolled to")
    void selectionSurvivesBeingScrolledAwayFromAndBackTo() {
        List<Item> items = fill();
        Item far = items.get(ITEMS - 3);

        interact(() -> app.listPanel().setSelectedId(far.id));
        WaitForAsyncUtils.waitForFxEvents();
        scrollTo(1.0);

        assertTrue(app.listPanel().isRowSelected(far.id),
                "a row scrolled into view must come back drawn as the selected one");
    }

    @Test
    @DisplayName("scrollTo brings a row that was never on screen into view")
    void scrollToReachesARowThatWasNeverBuilt() {
        List<Item> items = fill();
        Item far = items.get(ITEMS - 5);

        interact(() -> app.listPanel().scrollTo(far.id));
        WaitForAsyncUtils.waitForFxEvents();
        settle(200);

        Region row = app.listPanel().rowFor(far.id);
        assertNotNull(row, "after scrollTo, the row asked for must exist");
        assertTrue(isWithinViewport(row),
                "and it must actually be inside the viewport, not merely built");
    }

    @Test
    @DisplayName("the whole list is as tall as its rows, so the scrollbar means what it did")
    void theScrollableHeightCoversEveryRow() {
        fill();
        double content = WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> scroller().getContent().getBoundsInLocal().getHeight());
        double viewport = WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> scroller().getViewportBounds().getHeight());

        // One row is about 30 px at the desktop sizes, so 200 of them is several thousand. The
        // bar is a check that the content is genuinely the length of the list rather than the
        // length of whatever happens to be built.
        assertTrue(content > viewport * 4,
                "200 rows should make the content far taller than the viewport; content was "
                        + content + " against a viewport of " + viewport);
    }

    @Test
    @DisplayName("dragging a planned row out still commits THAT row, even after scrolling")
    void aPlannedDragAfterScrollingCommitsTheRowThatWasLifted() {
        fill();
        interact(() -> {
            Item plan = app.state().items.get(ITEMS - 1);
            plan.planned = true;
            plan.name = "zzz-plan";
            app.listPanel().rebuild();
            app.canvas().rebuildItems();
        });
        WaitForAsyncUtils.waitForFxEvents();
        settle(300);

        // It sorts last, so it is off screen until the list is scrolled to the bottom.
        interact(() -> app.listPanel().scrollTo(app.state().items.get(ITEMS - 1).id));
        WaitForAsyncUtils.waitForFxEvents();
        settle(300);

        Region row = app.listPanel().rowFor(app.state().items.get(ITEMS - 1).id);
        assertNotNull(row, "the planned row should be on screen after scrolling to it");
        Point2D from = middleOf(row);

        moveTo(from);
        press(MouseButton.PRIMARY);
        // Sideways first, which is what tells the list this is a lift and not a scroll.
        moveTo(new Point2D(from.getX() - 80, from.getY()));
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(app.dragGhost().isShowing(), "the drag should have lifted a card");

        moveTo(inRoom(400, 400));
        release(MouseButton.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();
        settle(400);

        Item plan = itemNamed("zzz-plan");
        assertTrue(!plan.planned,
                "dropping the lifted row in the room must commit THAT item; it is still planned");
    }

    @Test
    @DisplayName("the heights the spacers are built from are the heights the rows come out as")
    void theMeasuredHeightsMatchTheRealOnes() {
        fill();
        // A name long enough to wrap onto a second line, which is what makes rows differ in
        // height at all. Without one of these the check would pass on a list of identical rows
        // and say nothing about the case that can go wrong.
        interact(() -> {
            app.state().items.get(5).name =
                    "a name long enough that it has to wrap onto a second line in this panel";
            app.listPanel().rebuild();
        });
        WaitForAsyncUtils.waitForFxEvents();
        settle(300);
        interact(() -> app.listPanel().scrollTo(app.state().items.get(5).id));
        WaitForAsyncUtils.waitForFxEvents();
        settle(300);

        List<Node> rows = rowsOnScreen();
        assertTrue(rows.size() >= 3, "expected rows on screen");
        boolean sawATallOne = false;
        for (Node row : rows) {
            double real = WaitForAsyncUtils.waitForAsyncFx(5000,
                    () -> row.getBoundsInParent().getHeight());
            double planned = plannedHeightOf(nameOn(row));
            assertEquals(planned, real, 0.5,
                    "the row reading '" + nameOn(row) + "' was measured at " + planned
                            + " px for the spacers and came out " + real + " px. The spacers are "
                            + "built from those measurements, so a mismatch makes the list the "
                            + "wrong length and the scrollbar a lie");
            if (real > 40) {
                sawATallOne = true;
            }
        }
        assertTrue(sawATallOne,
                "the wrapped name should have made one row visibly taller than the rest; if every "
                        + "row is one line this test is not checking what it says it checks");
    }

    @Test
    @DisplayName("a row with the mouse down on it is not recycled, however far the list scrolls")
    void aRowMidGestureKeepsItsNode() {
        fill();
        scrollTo(0.0);

        Node row = rowsOnScreen().get(3);
        String shown = nameOn(row);
        moveTo(middleOf(row));
        press(MouseButton.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();

        // Scroll the list right past it while the button is still down. Without the pinning rule
        // this node would be pointed at some item near the bottom, and the release would act for
        // that one instead.
        scrollTo(0.9);

        assertEquals(shown, nameOn(row),
                "a row with the mouse down on it must still be showing the same item after the "
                        + "list has scrolled away from it; this is the rule that keeps a drag and "
                        + "a double-click acting on what they started on");

        release(MouseButton.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    @DisplayName("a row just clicked keeps its node even if the list scrolls away from it")
    void aRowJustClickedKeepsItsNodeIfTheListScrolls() {
        fill();
        scrollTo(0.2);

        // The last row on screen, so a small scroll is enough to push it out of the window the
        // recycler keeps. That is the only place this can go wrong, and it is where a thumb on a
        // phone lands it: a tap wobbles, the list moves a few pixels, and the second half of a
        // double-tap arrives after the node has gone.
        List<Node> onScreen = rowsOnScreen();
        Node row = onScreen.get(onScreen.size() - 1);
        String shown = nameOn(row);
        clickOn(middleOf(row));
        WaitForAsyncUtils.waitForFxEvents();

        scrollTo(0.6);

        assertEquals(shown, nameOn(row),
                "the row last clicked must keep its node when the list scrolls: JavaFX delivers "
                        + "the CLICKED half of a double-click to the node the press landed on, so "
                        + "a node recycled in between takes double-click-to-edit with it");
    }

    @Test
    @DisplayName("rowCount counts the rows in the list, not the handful of nodes built for them")
    void rowCountCountsRowsNotNodes() {
        fill();
        int rows = app.listPanel().rowCount();
        int nodes = WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> ((Region) scroller().getContent()).getChildrenUnmodifiable().size());

        assertEquals(ITEMS, rows,
                "rowCount is how many items got through the search, and every caller reads it "
                        + "that way; it is not how many nodes happen to exist");
        assertTrue(nodes < rows / 2,
                "and this test only means something while the two numbers differ: " + nodes
                        + " nodes against " + rows + " rows");
    }

    // ---------------------------------------------------------------- the new requirement

    @Test
    @DisplayName("a long list does not keep a node per item")
    void theListDoesNotKeepANodePerItem() {
        fill();
        int nodes = WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> ((Region) scroller().getContent()).getChildrenUnmodifiable().size());

        // The measurement this exists for: on the phone a 200 row list ran at 32 ms a frame and a
        // 12 row one at 16, and hiding the rows nobody can see halved it exactly. A node per item
        // is what makes that cost, so the check is on the node count rather than on a timing,
        // which would be a different number on every machine.
        System.out.printf("LONGLIST a %d item list built %d children of the scroller%n",
                ITEMS, nodes);
        assertTrue(nodes < ITEMS / 2,
                "a " + ITEMS + " item list should build far fewer than " + ITEMS + " row nodes; "
                        + "it built " + nodes);
    }

    // ---------------------------------------------------------------- plumbing

    /** Scrolls from the top to the bottom in small steps, collecting every name that appears. */
    private Set<String> namesSeenWhileScrollingTop() {
        Set<String> seen = new LinkedHashSet<>();
        int steps = 60;
        for (int i = 0; i <= steps; i++) {
            scrollTo(i / (double) steps);
            for (Node row : rowsOnScreen()) {
                String name = nameOn(row);
                if (name != null) {
                    seen.add(name);
                }
            }
        }
        return seen;
    }

    private void scrollTo(double where) {
        interact(() -> scroller().setVvalue(where));
        WaitForAsyncUtils.waitForFxEvents();
        settle(60);
    }

    private ScrollPane scroller() {
        for (Node child : app.listPanel().getChildrenUnmodifiable()) {
            if (child instanceof ScrollPane found) {
                return found;
            }
        }
        throw new IllegalStateException("the item list has no ScrollPane in it any more");
    }

    /** Row nodes whose bounds overlap the viewport, top to bottom. */
    private List<Node> rowsOnScreen() {
        return WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            List<Node> found = new ArrayList<>();
            for (Node child : ((Region) scroller().getContent()).getChildrenUnmodifiable()) {
                if (child.isVisible() && nameOn(child) != null && isWithinViewport(child)) {
                    found.add(child);
                }
            }
            found.sort((a, b) -> Double.compare(
                    a.getBoundsInParent().getMinY(), b.getBoundsInParent().getMinY()));
            return found;
        });
    }

    /** Whether a node inside the scroller is at least partly showing through the viewport. */
    private boolean isWithinViewport(Node node) {
        Bounds inViewport = scroller().getViewportBounds();
        Bounds inScene = node.localToScene(node.getBoundsInLocal());
        Bounds viewportInScene = scroller().localToScene(
                new javafx.geometry.BoundingBox(0, 0, inViewport.getWidth(),
                        inViewport.getHeight()));
        return inScene.getMaxY() > viewportInScene.getMinY()
                && inScene.getMinY() < viewportInScene.getMaxY();
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

    private Point2D inRoom(double roomX, double roomY) {
        Point2D origin = app.canvas().roomOriginInScene();
        double scale = app.canvas().fitScaleFactor();
        return new Point2D(
                origin.getX() + roomX * scale + app.canvas().getScene().getWindow().getX(),
                origin.getY() + roomY * scale + app.canvas().getScene().getWindow().getY());
    }

    /** What the panel measured this row at, read back out of the list's own row tops. */
    private double plannedHeightOf(String name) {
        Item item = itemNamed(name);
        return WaitForAsyncUtils.waitForAsyncFx(5000, () -> app.listPanel().measuredHeightOf(item.id));
    }

    private Item itemNamed(String name) {
        for (Item item : app.state().items) {
            if (name.equals(item.name) || name.startsWith(item.name)) {
                return item;
            }
        }
        throw new IllegalStateException("no item named " + name);
    }

    private void settle(long millis) {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            WaitForAsyncUtils.sleep(10, TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
    }
}
