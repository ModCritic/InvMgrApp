package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.engine.TextFormat;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Units;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Dragging a planned item out of the list and dropping it into the room.
 *
 * <p>This is the one gesture in the app that crosses from one panel to another, and the only
 * way a planned item ever becomes a real one. Driven with a real pointer rather than by calling
 * the commit code, because most of what can go wrong here is in the gesture — the movement
 * threshold, the sideways-versus-downward decision, and whether the drop point lands where the
 * box actually appears.
 */
class PlannedDragTest extends ApplicationTest {

    private static final int REFERENCE_WIDTH = 2560;
    private static final int REFERENCE_HEIGHT = 1440;

    private App app;
    private Scene scene;

    @Override
    public void start(Stage stage) {
        app = new App();
        app.start(stage);
        stage.setMaximized(false);
        stage.setWidth(REFERENCE_WIDTH);
        stage.setHeight(REFERENCE_HEIGHT);
        stage.setX(0);
        stage.setY(0);
        scene = stage.getScene();
    }

    /** Turns on Planning Mode and adds one ghost. */
    private Item addGhost(String name, double w, double l, double h) {
        clickOn(app.topBar().planButton());
        WaitForAsyncUtils.waitForFxEvents();

        clickOn(app.topBar().addButtonNode());
        WaitForAsyncUtils.waitForFxEvents();
        clickOn(app.addDialog().nameField()).write(name);
        retype(app.addDialog().widthField().textField(), TextFormat.number(w));
        retype(app.addDialog().lengthField().textField(), TextFormat.number(l));
        retype(app.addDialog().heightField().textField(), TextFormat.number(h));
        clickOn(app.addDialog().confirmButton());
        WaitForAsyncUtils.waitForFxEvents();

        return app.state().items.get(app.state().items.size() - 1);
    }

    private void retype(javafx.scene.control.TextInputControl field, String text) {
        clickOn(field);
        interact(field::selectAll);
        write(text);
    }

    /** A point in screen coordinates, given a point in the room's own pixels. */
    private Point2D inRoom(double roomX, double roomY) {
        Point2D origin = app.canvas().roomOriginInScene();
        double scale = app.canvas().fitScaleFactor();
        return new Point2D(origin.getX() + roomX * scale + scene.getWindow().getX(),
                origin.getY() + roomY * scale + scene.getWindow().getY());
    }

    private Point2D centreOfRow(Item item) {
        Bounds bounds = app.listPanel().rowFor(item.id)
                .localToScene(app.listPanel().rowFor(item.id).getBoundsInLocal());
        return new Point2D(bounds.getMinX() + bounds.getWidth() / 2 + scene.getWindow().getX(),
                bounds.getMinY() + bounds.getHeight() / 2 + scene.getWindow().getY());
    }

    @Test
    @DisplayName("the carried card leans when the hand moves and settles when it stops")
    void theCardSwingsWhileItIsCarried() {
        // TiltPendulumTest proves the swing is right. This proves it is CONNECTED -- that the
        // ticker in DragGhost runs, that it is fed the pointer, and that the angle reaches the
        // card. All of that is invisible to the arithmetic test, and a pendulum wired to nothing
        // passes every assertion in it.
        interact(() -> app.dragGhost().lift("Ghost [plan]", javafx.scene.paint.Color.GREEN, 200,
                2000, 300, 2040, 312));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(0, app.dragGhost().tiltDegrees(), 0.0, "a card hangs straight when lifted");

        // One big jump, which is a violent acceleration and swings it hard.
        interact(() -> app.dragGhost().moveTo(2300, 300, false));
        try {
            WaitForAsyncUtils.waitFor(2, java.util.concurrent.TimeUnit.SECONDS,
                    () -> Math.abs(app.dragGhost().tiltDegrees()) > 3);
        } catch (java.util.concurrent.TimeoutException e) {
            // Fall through: the assertion says what went wrong in English.
        }
        assertTrue(Math.abs(app.dragGhost().tiltDegrees()) > 3,
                "moving the hand should swing the card; it stayed at "
                        + app.dragGhost().tiltDegrees() + "°");

        // And it hangs from the point it was grabbed by, not from its middle. The card was lifted
        // with the pointer 40 px right and 12 px down from its corner, so that spot on the card
        // has to stay under the pointer however far it is leaning -- which is only true if the
        // rotation pivots there. Turning about the middle instead moves it by several pixels at
        // this angle, and the card visibly swims out from under the cursor as it swings.
        Point2D grabPoint = app.dragGhost().node().localToScene(40, 12);
        assertEquals(2300, grabPoint.getX(), 0.5, "the grabbed point should stay under the cursor");
        assertEquals(300, grabPoint.getY(), 0.5, "the grabbed point should stay under the cursor");

        // Now stop. The old rule had nothing running while the hand was still, so the card stayed
        // frozen at whatever the last mouse event said until it moved again -- this is the half of
        // the change that a test driven only by mouse events could never see.
        try {
            WaitForAsyncUtils.waitFor(3, java.util.concurrent.TimeUnit.SECONDS,
                    () -> Math.abs(app.dragGhost().tiltDegrees()) < 0.5);
        } catch (java.util.concurrent.TimeoutException e) {
            // Same.
        }
        assertEquals(0, app.dragGhost().tiltDegrees(), 0.5,
                "a still hand should let the card settle straight again");

        interact(() -> app.dragGhost().drop());
    }

    @Test
    @DisplayName("dragging a ghost from the list into the room makes it a real box")
    void draggingAGhostIntoTheRoomCommitsIt() {
        Item ghost = addGhost("Ghost", 24, 24, 12);
        assertTrue(ghost.planned);

        Point2D from = centreOfRow(ghost);
        Point2D to = inRoom(400, 300);

        moveTo(from);
        press(javafx.scene.input.MouseButton.PRIMARY);
        // A couple of intermediate moves: the drag only begins once the pointer has passed the
        // 6 px threshold.
        moveTo(new Point2D(from.getX() - 60, from.getY()));
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(app.dragGhost().isShowing(),
                "a sideways drag past the threshold lifts a ghost card out of the row");

        moveTo(new Point2D((from.getX() + to.getX()) / 2, (from.getY() + to.getY()) / 2));
        moveTo(to);
        WaitForAsyncUtils.waitForFxEvents();
        release(javafx.scene.input.MouseButton.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(app.dragGhost().isShowing(), "and the card goes away on release");

        assertFalse(ghost.planned, "dropping it in the room commits it");
        assertEquals("Committed Ghost to room.", app.statusBar().text());
        assertEquals(ghost.id, app.canvas().selectedId(), "and it becomes the selected box");

        // Dropped centred on the pointer, not hanging off its corner. findOpenSpot rounds to
        // whole pixels and may nudge it to avoid an overlap, so this allows a little slack.
        double expectedX = 400 - Units.inchesToPx(ghost.w_in) / 2;
        double expectedY = 300 - Units.inchesToPx(ghost.l_in) / 2;
        assertTrue(Math.abs(ghost.x_px - expectedX) <= 2,
                "expected x near " + expectedX + " but was " + ghost.x_px);
        assertTrue(Math.abs(ghost.y_px - expectedY) <= 2,
                "expected y near " + expectedY + " but was " + ghost.y_px);
    }

    @Test
    @DisplayName("undo turns a committed box back into a ghost")
    void committingIsUndoable() {
        Item ghost = addGhost("Ghost", 24, 24, 12);
        dragTo(ghost, inRoom(400, 300));
        assertFalse(ghost.planned);

        clickOn(app.topBar().undoButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(ghost.planned, "it goes back to being a plan");
        assertEquals("Undid Ghost", app.statusBar().text());
    }

    @Test
    @DisplayName("dropping outside the room cancels, and the ghost stays a ghost")
    void droppingOutsideTheRoomCancels() {
        Item ghost = addGhost("Ghost", 24, 24, 12);

        // The status bar is well clear of the room area.
        Bounds status = app.statusBar().localToScene(app.statusBar().getBoundsInLocal());
        dragTo(ghost, new Point2D(status.getMinX() + 200 + scene.getWindow().getX(),
                status.getMinY() + 5 + scene.getWindow().getY()));

        assertTrue(ghost.planned, "a drop that missed the room must change nothing");
        assertFalse(app.statusBar().text().startsWith("Committed"));
    }

    @Test
    @DisplayName("a mostly-downward drag scrolls rather than lifting the row out")
    void aVerticalDragIsNotADrag() {
        Item ghost = addGhost("Ghost", 24, 24, 12);
        Point2D from = centreOfRow(ghost);

        moveTo(from);
        press(javafx.scene.input.MouseButton.PRIMARY);
        // Straight down, past the threshold. Whichever of the two distances is larger when the
        // threshold is crossed decides, and this one is vertical — which is what leaves the
        // list scrollable instead of every attempt to scroll it lifting a row out.
        moveTo(new Point2D(from.getX(), from.getY() + 80));
        WaitForAsyncUtils.waitForFxEvents();

        // Checked mid-gesture, not after. Checking only the end state passes either way: a
        // drag that wrongly started here would be released over the list panel, miss the room,
        // and cancel — leaving the ghost a ghost and the test none the wiser. Found by
        // deliberately making the direction lock always say "drag" and watching this pass.
        assertFalse(app.dragGhost().isShowing(), "no card should have been lifted");

        release(javafx.scene.input.MouseButton.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(ghost.planned, "and nothing should have been committed");
    }

    /** Presses on a ghost's row, drags to a point, and releases. */
    private void dragTo(Item ghost, Point2D target) {
        Point2D from = centreOfRow(ghost);
        moveTo(from);
        press(javafx.scene.input.MouseButton.PRIMARY);
        moveTo(new Point2D(from.getX() - 60, from.getY()));
        moveTo(target);
        WaitForAsyncUtils.waitForFxEvents();
        release(javafx.scene.input.MouseButton.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();
    }
}
