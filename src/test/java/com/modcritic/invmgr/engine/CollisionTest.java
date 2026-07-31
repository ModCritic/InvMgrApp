package com.modcritic.invmgr.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Room;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Readable tests for the collision rules.
 *
 * <p>{@link EngineDifferentialTest} already proves this code matches the original over 250
 * scenarios. These tests exist for a different reason: to say <em>why</em> each rule is there,
 * in a form someone can read. A failure here names the broken behaviour instead of printing a
 * scenario index.
 */
class CollisionTest {

    private static final double PX_PER_FOOT = 96;

    @Test
    @DisplayName("an item is kept inside the room even with collision off")
    void clampsToRoomBounds() {
        AppState state = roomOf(12, 10);
        Item item = box("a", 1, 12, 12, 0, 0);
        state.items.add(item);

        // Dragged far past the south-east corner: a 12 in (96 px) box in a 12 x 10 ft room
        // can reach 1152 - 96 = 1056 across and 960 - 96 = 864 down.
        Collision.Point atCorner = Collision.clampItem(state, item, 99999, 99999);
        assertEquals(1056, atCorner.x());
        assertEquals(864, atCorner.y());

        // And past the north-west corner.
        Collision.Point atOrigin = Collision.clampItem(state, item, -500, -500);
        assertEquals(0, atOrigin.x());
        assertEquals(0, atOrigin.y());
    }

    @Test
    @DisplayName("an item bigger than the room pins to the corner instead of going negative")
    void oversizeItemPinsToCorner() {
        // The room is 2 ft (192 px) but the item is 100 in (800 px), so the usual "furthest
        // allowed position" is negative. It must not be flung outside the room.
        AppState state = roomOf(2, 2);
        Item item = box("a", 1, 100, 100, 0, 0);
        state.items.add(item);

        Collision.Point result = Collision.clampItem(state, item, 500, 500);
        assertEquals(0, result.x());
        assertEquals(0, result.y());
    }

    @Test
    @DisplayName("with Layer Collision off, items pass straight through each other")
    void noItemCollisionWhenModeIsOff() {
        AppState state = roomOf(30, 30);
        state.layerCollision = false;
        Item mover = box("a", 1, 12, 12, 0, 0);
        state.items.add(mover);
        state.items.add(box("b", 2, 12, 12, 200, 0));

        // Straight onto the other box, and allowed: only room bounds apply in this mode.
        Collision.Point result = Collision.clampItem(state, mover, 200, 0);
        assertEquals(200, result.x());
    }

    @Test
    @DisplayName("a blocked slide stops exactly against the obstacle, however big the jump")
    void slideStopsAtTheContactEdge() {
        AppState state = roomOf(30, 10);
        state.layerCollision = true;
        Item mover = box("a", 1, 12, 12, 0, 0);       // 96 px wide, at x = 0
        state.items.add(mover);
        state.items.add(box("b", 2, 12, 12, 1000, 0));

        // One enormous jump — far past the obstacle. The result must be flush against it:
        // 1000 - 96 = 904. This is the property a step-by-step search would miss, and
        // getting it wrong produced inconsistent gaps between boxes in the original.
        Collision.Point far = Collision.clampItem(state, mover, 5000, 0);
        assertEquals(904, far.x());

        // A tiny nudge from the same start lands in the same place, which is the point.
        Collision.Point crawl = Collision.clampItem(state, mover, 910, 0);
        assertEquals(904, crawl.x());
    }

    @Test
    @DisplayName("a box with something resting exactly on top can still be dragged")
    void zeroGapStackIsNotACollision() {
        AppState state = roomOf(20, 20);
        state.layerCollision = true;
        Item lower = box("lower", 1, 24, 24, 100, 100);
        lower.h_in = 18;
        Item upper = box("upper", 2, 24, 24, 100, 100);
        upper.h_in = 12;
        upper.baseHeight_in = 18;                     // sits exactly on lower's top
        state.items.add(lower);
        state.items.add(upper);

        // Without the zero-gap exception the two would count as interpenetrating and the
        // lower box could never be moved at all.
        Collision.Point result = Collision.clampItem(state, lower, 400, 100);
        assertEquals(400, result.x());
    }

    @Test
    @DisplayName("switching Layer Collision on over an existing overlap doesn't teleport anything")
    void alreadyOverlappingFallsBackToRoomBounds() {
        AppState state = roomOf(20, 20);
        state.layerCollision = true;
        Item a = box("a", 1, 24, 24, 100, 100);
        Item b = box("b", 2, 24, 24, 110, 110);       // overlapping a already
        state.items.add(a);
        state.items.add(b);

        // The drag is honoured as if there were no obstacles. Trying to resolve the existing
        // overlap would fling the box somewhere the user never asked for.
        Collision.Point result = Collision.clampItem(state, b, 300, 300);
        assertEquals(300, result.x());
        assertEquals(300, result.y());
    }

    @Test
    @DisplayName("items at non-overlapping heights ignore each other entirely")
    void differentHeightBandsDoNotBlock() {
        AppState state = roomOf(30, 30);
        state.layerCollision = true;
        Item low = box("low", 1, 12, 12, 0, 0);
        low.h_in = 6;                                 // occupies 0-6 in
        Item high = box("high", 2, 12, 12, 200, 0);
        high.baseHeight_in = 40;                      // occupies 40-52 in
        high.h_in = 12;
        state.items.add(low);
        state.items.add(high);

        // Nothing to collide with: the shelf overhead is at a completely different height.
        Collision.Point result = Collision.clampItem(state, low, 400, 0);
        assertEquals(400, result.x());
    }

    @Test
    @DisplayName("the Y slide uses the X the item actually reached, not the X it started at")
    void yPassUsesThePostSlideX() {
        // A REGRESSION TEST for a specific bug, and one worth understanding.
        //
        // The mover starts at (0, 0). blockX stops its rightward travel at x = 104.
        // blockY sits at x 150-246, which the mover's ORIGINAL x (0-96) does not reach, but
        // its POST-SLIDE x (104-200) does. So blockY must stop the downward travel at
        // 300 - 96 = 204.
        //
        // Feeding the stale x = 0 into the Y pass makes blockY invisible and the box slides
        // right past its corner, down to y = 1000. That bug was invisible to 250 differential
        // scenarios until this exact geometry was constructed for it.
        AppState state = roomOf(30, 30);
        state.layerCollision = true;
        Item mover = box("a", 1, 12, 12, 0, 0);
        state.items.add(mover);
        state.items.add(box("blockX", 2, 12, 12, 200, 0));
        state.items.add(box("blockY", 3, 12, 12, 150, 300));

        Collision.Point result = Collision.clampItem(state, mover, 1000, 1000);
        assertEquals(104, result.x(), "X should stop flush against blockX");
        assertEquals(204, result.y(), "Y should be stopped by blockY, reachable only after the X slide");
    }

    @Test
    @DisplayName("ghosts are never obstacles")
    void plannedItemsDoNotBlock() {
        AppState state = roomOf(30, 30);
        state.layerCollision = true;
        Item mover = box("a", 1, 12, 12, 0, 0);
        Item ghost = box("ghost", 2, 12, 12, 200, 0);
        ghost.planned = true;
        state.items.add(mover);
        state.items.add(ghost);

        Collision.Point result = Collision.clampItem(state, mover, 500, 0);
        assertEquals(500, result.x(), "a planned item is not in the room and cannot block");
    }

    @Test
    @DisplayName("a slide never moves the item backwards past where it started")
    void slideNeverOvershootsBackwards() {
        // Guards the Math.max(from, limit) / Math.min(from, limit) guards in slideAxis: an
        // obstacle behind the item must not drag it backwards when it is moving forwards.
        double forward = Collision.slideAxis(100, 300, 96, 0, 96,
                java.util.List.of(new Rect(0, 0, 50, 96)), Collision.Axis.X);
        assertTrue(forward >= 100, "moving forward must not end up behind the start");

        double backward = Collision.slideAxis(300, 100, 96, 0, 96,
                java.util.List.of(new Rect(500, 0, 600, 96)), Collision.Axis.X);
        assertTrue(backward <= 300, "moving backward must not end up ahead of the start");
    }

    @Test
    @DisplayName("asking to stay put is a no-op")
    void noMovementReturnsTheSamePosition() {
        assertEquals(250, Collision.slideAxis(250, 250, 96, 0, 96,
                java.util.List.of(new Rect(0, 0, 5000, 5000)), Collision.Axis.X),
                "even buried in obstacles, not moving cannot fail");
    }

    // ------------------------------------------------------------------ helpers

    private static AppState roomOf(double widthFeet, double lengthFeet) {
        AppState state = new AppState();
        state.room = new Room(widthFeet, lengthFeet, 8);
        state.items.clear();
        return state;
    }

    /** A 12-inch-tall box on the floor, sized in inches and positioned in pixels. */
    private static Item box(String id, double dragOrder, double w_in, double l_in,
            double x_px, double y_px) {
        Item item = new Item();
        item.id = id;
        item.dragOrder = dragOrder;
        item.serial = dragOrder;
        item.w_in = w_in;
        item.l_in = l_in;
        item.h_in = 12;
        item.x_px = x_px;
        item.y_px = y_px;
        item.baseHeight_in = 0;
        item.planned = false;
        item.name = "";
        item.customId = "";
        item.color = "hsl(0,55%,42%)";
        return item;
    }

    static double pxPerFoot() {
        return PX_PER_FOOT;
    }
}
