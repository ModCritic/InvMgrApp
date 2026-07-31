package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.engine.Layers;
import com.modcritic.invmgr.engine.Rect;
import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Room;
import com.modcritic.invmgr.model.Units;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Drives the room with a real pointer, to prove the view is actually wired to the collision
 * engine.
 *
 * <p>{@code CollisionTest} proves the maths is right and {@code EngineDifferentialTest} proves it
 * matches the original — but neither would notice if the canvas simply never called any of it.
 * These tests move the mouse.
 */
class CanvasDragTest extends ApplicationTest {

    private RoomCanvasView canvas;
    private AppState state;
    private Item box;

    @Override
    public void start(Stage stage) {
        state = new AppState();
        state.room = new Room(20, 16, 8);

        box = item("item-id-11111111-2222-4333-8444-555555555555", 1, 24, 24, 192, 192);
        state.items.add(box);

        canvas = new RoomCanvasView(state);
        // The view delegates "an item was clicked without being dragged" to its owner, so the
        // test wires it the same way App does.
        canvas.setOnItemActivated(clicked -> canvas.setSelectedId(clicked.id));
        LayerSliderDrawer drawer = new LayerSliderDrawer(state);
        HBox main = new HBox(drawer, canvas);
        HBox.setHgrow(canvas, Priority.ALWAYS);

        stage.setScene(new Scene(main, 1400, 900));
        stage.show();
    }

    @Test
    @DisplayName("dragging a box moves it")
    void dragMovesTheItem() {
        double startX = box.x_px;
        double startY = box.y_px;

        dragBy(box, 200, 100);

        assertEquals(startX + 200, box.x_px, 2, "the box should follow the pointer across");
        assertEquals(startY + 100, box.y_px, 2, "and down");
    }

    @Test
    @DisplayName("a drag cannot push a box out through a wall")
    void dragIsClampedToTheRoom() {
        // Shove it far past the east wall. A 20 ft room is 1920 px across and the box is 192 px
        // wide, so the furthest its left edge can go is 1728.
        dragBy(box, 5000, 0);

        assertEquals(1728, box.x_px, 1, "the box should stop at the east wall");
        assertTrue(box.x_px + Units.inchesToPx(box.w_in) <= Units.feetToPx(state.room.w),
                "no part of the box may leave the room");
    }

    @Test
    @DisplayName("a click without movement does not nudge the box")
    void clickAloneDoesNotMove() {
        double startX = box.x_px;
        double startY = box.y_px;

        Point2D centre = centreOf(box);
        moveTo(centre.getX(), centre.getY());
        press(MouseButton.PRIMARY);
        release(MouseButton.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();

        // The whole point of the movement threshold: clicking must not shift the box by the
        // pixel or two the mouse twitches between press and release.
        assertEquals(startX, box.x_px);
        assertEquals(startY, box.y_px);
    }

    @Test
    @DisplayName("a click selects the box, and clicking the floor deselects")
    void clickSelectsAndFloorDeselects() {
        Point2D centre = centreOf(box);
        clickOn(centre.getX(), centre.getY());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(box.id, canvas.selectedId(), "clicking a box should select it");

        // Somewhere in the room well clear of the box.
        Point2D origin = canvas.roomOriginInScene();
        clickOn(origin.getX() + 1200, origin.getY() + 700);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(null, canvas.selectedId(), "clicking bare floor should clear the selection");
    }

    @Test
    @DisplayName("with Layer Collision on, a dragged box stops against another")
    void dragIsBlockedByAnotherItem() {
        Item blocker = item("item-id-22222222-3333-4444-8555-666666666666", 2, 24, 24, 768, 192);
        interact(() -> {
            state.layerCollision = true;
            state.items.add(blocker);
            canvas.rebuildItems();
        });
        WaitForAsyncUtils.waitForFxEvents();

        // Aimed straight through the blocker. The box is 192 px wide and the blocker starts at
        // 768, so it must come to rest with its leading edge against it, at 576.
        dragBy(box, 900, 0);

        assertEquals(576, box.x_px, 2,
                "the dragged box should stop flush against the blocker, not pass through it");
    }

    @Test
    @DisplayName("finishing a drag puts the box on top of what it now overlaps")
    void dragUpdatesStackingOrder() {
        Item other = item("item-id-33333333-4444-4555-8666-777777777777", 5, 24, 24, 600, 192);
        other.h_in = 18;
        interact(() -> {
            state.items.add(other);
            canvas.rebuildItems();
        });
        WaitForAsyncUtils.waitForFxEvents();

        // Drag our box on top of the taller one: it should end up resting on it, at 18 inches.
        dragBy(box, 408, 0);

        assertTrue(box.dragOrder > other.dragOrder,
                "the box just moved should now be the most recently touched");
        assertEquals(18, box.baseHeight_in,
                "and should be resting on the 18 inch box it was dropped onto");
    }

    @Test
    @DisplayName("a legacy file's stale drag-order counter does not strand the dragged box below")
    void dragBeatsAStaleDragOrderCounter() {
        // Exactly the shape of a file saved before the dragOrder field existed: no counter, so it
        // loads as 0, while the items take their drag order from their serial numbers. The
        // original app would assign the dragged box 1 here, leaving it under everything.
        Item high = item("item-id-44444444-5555-4666-8777-888888888888", 8, 24, 24, 600, 192);
        high.h_in = 18;
        interact(() -> {
            state.dragOrderCounter = 0;
            box.dragOrder = 7;
            state.items.add(high);
            canvas.rebuildItems();
        });
        WaitForAsyncUtils.waitForFxEvents();

        dragBy(box, 408, 0);

        assertTrue(box.dragOrder > high.dragOrder,
                "expected the dragged box above drag order 8, got " + box.dragOrder);
        assertEquals(18, box.baseHeight_in, "so it rests on the box it was dropped onto");
    }

    @Test
    @DisplayName("a consistent counter still just increments, exactly as the original does")
    void dragIncrementsAConsistentCounter() {
        interact(() -> {
            state.dragOrderCounter = 12;
            box.dragOrder = 12;
        });
        WaitForAsyncUtils.waitForFxEvents();

        dragBy(box, 100, 0);

        // The hardening above must not change behaviour in the normal case.
        assertEquals(13, state.dragOrderCounter);
        assertEquals(13, box.dragOrder);
    }

    @Test
    @DisplayName("right-click deletes a box, and undo brings it back where it was")
    void rightClickDeletesAndUndoRestores() {
        double x = box.x_px;
        double y = box.y_px;
        assertEquals(1, state.items.size());

        Point2D centre = centreOf(box);
        moveTo(centre.getX(), centre.getY());
        clickOn(MouseButton.SECONDARY);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(state.items.isEmpty(), "right-click should delete immediately, no confirmation");

        interact(() -> canvas.undo());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1, state.items.size(), "undo should bring the box back");
        assertEquals(x, state.items.get(0).x_px);
        assertEquals(y, state.items.get(0).y_px);
    }

    @Test
    @DisplayName("undo after a drag returns the box to where it started")
    void undoAfterDrag() {
        double startX = box.x_px;
        double startY = box.y_px;

        dragBy(box, 300, 200);
        assertEquals(startX + 300, box.x_px, 2);

        String[] message = new String[1];
        interact(() -> message[0] = canvas.undo());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(startX, box.x_px);
        assertEquals(startY, box.y_px);
        assertTrue(message[0].startsWith("Undid drag of"), "got: " + message[0]);
    }

    @Test
    @DisplayName("a click that never became a drag leaves nothing to undo")
    void clickAloneRecordsNothing() {
        Point2D centre = centreOf(box);
        moveTo(centre.getX(), centre.getY());
        press(MouseButton.PRIMARY);
        release(MouseButton.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();

        // Undoing a click nobody made would be baffling.
        assertTrue(canvas.undoHistory().isEmpty(),
                "a click is not an action worth undoing");
    }

    @Test
    @DisplayName("deleting a box lets whatever rested on it fall, and undo puts it back up")
    void deleteAndUndoRestoresStacking() {
        Item upper = item("item-id-55555555-6666-4777-8888-999999999999", 2, 24, 24, 192, 192);
        interact(() -> {
            box.h_in = 18;
            state.items.add(upper);
            com.modcritic.invmgr.engine.Stacking.recomputeAllBaseHeights(state);
            canvas.rebuildItems();
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(18, upper.baseHeight_in, "the second box should be resting on the first");

        interact(() -> canvas.deleteItem(box.id));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(0, upper.baseHeight_in, "with its support gone, it should fall to the floor");

        interact(() -> canvas.undo());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(18, upper.baseHeight_in, "undo restores the whole arrangement, not just the box");
    }

    @Test
    @DisplayName("the selection highlight travels with the box during the drag, not after it")
    void highlightFollowsTheItemMidDrag() {
        interact(() -> canvas.setSelectedId(box.id));
        WaitForAsyncUtils.waitForFxEvents();

        javafx.scene.shape.Rectangle outline = canvas.selectionOutline(box.id);
        double outlineStartX = outline.getX();

        // Press and move but deliberately do NOT release: the highlight is a separate node from
        // the box, so unless the drag handler moves it too it sits at the old position for the
        // whole gesture and only snaps across on release. Checking after the release would pass
        // either way, which is exactly why this test stops mid-gesture.
        Point2D centre = centreOf(box);
        moveTo(centre.getX(), centre.getY());
        press(MouseButton.PRIMARY);
        moveBy(80, 40);
        WaitForAsyncUtils.waitForFxEvents();

        double inset = Tokens.SELECTION_OUTLINE_OFFSET + Tokens.SELECTION_OUTLINE_WIDTH;
        assertEquals(box.x_px - inset, outline.getX(), 0.5,
                "the highlight should be around the box's live position mid-drag");
        assertEquals(box.y_px - inset, outline.getY(), 0.5,
                "the highlight should be around the box's live position mid-drag");
        assertTrue(outline.getX() > outlineStartX, "and it should actually have moved");

        release(MouseButton.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    @DisplayName("turning Layer Collision off does not lift a low box above one it is under")
    void collisionOffKeepsALowBoxUnderneath() {
        // The state the user reached in the M3 jar, with all boxes 12 in tall and 12 in square:
        //   A on the floor; B sitting half on top of A, so B is a layer up at base 12;
        //   C on the floor, dragged (with Layer Collision on, which is what freezes its height)
        //   into the part of B that overhangs A -- so C is UNDERNEATH B while still at base 0.
        // Collision is then switched off, and C keeps base 0. The heights are set here rather
        // than recomputed precisely because that frozen-then-released state is the point: a
        // stacking pass would lift C onto B and the case would vanish.
        Item a = item("item-id-aaaaaaaa-1111-4111-8111-111111111111", 1, 12, 12, 96, 96);
        Item b = item("item-id-bbbbbbbb-2222-4222-8222-222222222222", 2, 12, 12, 144, 96);
        Item c = item("item-id-cccccccc-3333-4333-8333-333333333333", 9, 12, 12, 192, 96);
        b.baseHeight_in = 12;
        c.baseHeight_in = 0;
        interact(() -> {
            state.layerCollision = false;
            state.items.clear();
            state.items.add(a);
            state.items.add(b);
            state.items.add(c);
            canvas.rebuildItems();
        });
        WaitForAsyncUtils.waitForFxEvents();

        // Sanity-check the geometry actually reproduces the case, or the test proves nothing:
        // C must overlap B (or there is no stack to get wrong) and must be lower than it.
        assertTrue(Rect.of(c).overlaps(Rect.of(b)), "C must be under B's footprint");
        assertTrue(c.baseHeight_in < b.baseHeight_in, "and physically below it");
        assertTrue(c.dragOrder > b.dragOrder, "while having moved more recently, which is the trap");

        // Under the original's rule -- drag order while Layer Collision is off -- C would paint
        // over the box it had just been pushed underneath.
        assertTrue(Layers.comparePaint(c, b) < 0,
                "C is physically beneath B and must be drawn beneath it, however recently it moved");
        assertTrue(paintIndexOf(c) < paintIndexOf(b),
                "and the scene graph order must agree, since that is what actually draws");
    }

    /** Where an item's rectangle sits in the draw order — later means painted on top. */
    private int paintIndexOf(Item item) {
        return canvas.itemLayerChildren().indexOf(canvas.itemRect(item.id));
    }

    // ------------------------------------------------------------------ helpers

    /** Presses in the middle of an item, moves by a screen-pixel offset, and releases. */
    private void dragBy(Item item, double dx, double dy) {
        Point2D centre = centreOf(item);
        moveTo(centre.getX(), centre.getY());
        press(MouseButton.PRIMARY);
        // Two steps rather than one: a single jump is a legitimate drag, but moving twice also
        // exercises the case where the position is recalculated from the original press point
        // each time rather than accumulated.
        moveBy(dx / 2, dy / 2);
        moveBy(dx / 2, dy / 2);
        release(MouseButton.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private Point2D centreOf(Item item) {
        Point2D origin = canvas.roomOriginInScene();
        Point2D sceneCentre = new Point2D(
                origin.getX() + item.x_px + Units.inchesToPx(item.w_in) / 2,
                origin.getY() + item.y_px + Units.inchesToPx(item.l_in) / 2);
        // The robot works in screen coordinates, so the window's own position has to be added.
        Scene scene = canvas.getScene();
        return new Point2D(
                scene.getWindow().getX() + scene.getX() + sceneCentre.getX(),
                scene.getWindow().getY() + scene.getY() + sceneCentre.getY());
    }

    private static Item item(String id, double dragOrder, double w_in, double l_in,
            double x_px, double y_px) {
        Item item = new Item();
        item.id = id;
        item.serial = dragOrder;
        item.dragOrder = dragOrder;
        item.w_in = w_in;
        item.l_in = l_in;
        item.h_in = 12;
        item.x_px = x_px;
        item.y_px = y_px;
        item.baseHeight_in = 0;
        item.planned = false;
        item.name = "";
        item.customId = "";
        item.color = "hsl(122,55%,42%)";
        return item;
    }
}
