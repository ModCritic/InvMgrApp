package com.modcritic.invmgr.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Room;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Readable tests for the two stacking passes, and for why they are not interchangeable. */
class StackingTest {

    @Test
    @DisplayName("a box dropped on another lands on its top")
    void stacksOnOverlappingItems() {
        AppState state = room();
        Item lower = box("lower", 1, 24, 24, 96, 96, 18);
        Item upper = box("upper", 2, 24, 24, 96, 96, 12);
        state.items.add(lower);
        state.items.add(upper);

        Stacking.recomputeAllBaseHeights(state);

        assertEquals(0, lower.baseHeight_in);
        assertEquals(18, upper.baseHeight_in, "the later-dragged box rests on the earlier one");
    }

    @Test
    @DisplayName("boxes that don't overlap both stay on the floor")
    void separateItemsStayOnTheFloor() {
        AppState state = room();
        Item a = box("a", 1, 12, 12, 0, 0, 12);
        Item b = box("b", 2, 12, 12, 500, 500, 12);
        state.items.add(a);
        state.items.add(b);

        Stacking.recomputeAllBaseHeights(state);

        assertEquals(0, a.baseHeight_in);
        assertEquals(0, b.baseHeight_in);
    }

    @Test
    @DisplayName("drag order decides who ends up on top, not list order")
    void dragOrderDecidesWhoIsOnTop() {
        AppState state = room();
        // b was dragged FIRST (lower dragOrder) despite being added second, so a lands on b.
        Item a = box("a", 5, 24, 24, 96, 96, 10);
        Item b = box("b", 1, 24, 24, 96, 96, 15);
        state.items.add(a);
        state.items.add(b);

        Stacking.recomputeAllBaseHeights(state);

        assertEquals(0, b.baseHeight_in, "the earlier-dragged box is underneath");
        assertEquals(15, a.baseHeight_in, "the most recently dragged box goes on top");
    }

    @Test
    @DisplayName("heights are completely frozen while Layer Collision is on")
    void recomputeIsANoOpWhileFrozen() {
        AppState state = room();
        state.layerCollision = true;
        Item floating = box("floating", 1, 24, 24, 96, 96, 12);
        floating.baseHeight_in = 47;                 // deliberately impossible without support
        state.items.add(floating);

        Stacking.recomputeAllBaseHeights(state);

        assertEquals(47, floating.baseHeight_in,
                "Layer Collision means heights change for no reason at all, including this one");
    }

    @Test
    @DisplayName("ghosts neither hold anything up nor get a height")
    void plannedItemsAreExcluded() {
        AppState state = room();
        Item ground = box("ground", 1, 24, 24, 96, 96, 18);
        Item ghost = box("ghost", 2, 24, 24, 96, 96, 18);
        ghost.planned = true;
        ghost.baseHeight_in = 99;
        Item top = box("top", 3, 24, 24, 96, 96, 12);
        state.items.add(ground);
        state.items.add(ghost);
        state.items.add(top);

        Stacking.recomputeAllBaseHeights(state);

        assertEquals(18, top.baseHeight_in,
                "top rests on ground at 18, not on the ghost — a ghost is not in the room");
        assertEquals(99, ghost.baseHeight_in, "and the ghost's own height is left untouched");
    }

    @Test
    @DisplayName("settling resolves a whole tower in one pass, lowest first")
    void settleCascadesInOnePass() {
        AppState state = room();
        // A three-high tower where the middle box has shrunk to 6 in tall. Everything above
        // it needs to drop, in order, and it has to happen in a single pass.
        Item bottom = box("bottom", 1, 24, 24, 96, 96, 12);
        bottom.baseHeight_in = 0;
        Item middle = box("middle", 2, 24, 24, 96, 96, 6);
        middle.baseHeight_in = 12;
        Item top = box("top", 3, 24, 24, 96, 96, 12);
        top.baseHeight_in = 36;                      // stale: was resting on a taller middle

        state.items.add(bottom);
        state.items.add(middle);
        state.items.add(top);

        Stacking.settleAllBaseHeights(state);

        assertEquals(0, bottom.baseHeight_in);
        assertEquals(12, middle.baseHeight_in);
        assertEquals(18, top.baseHeight_in, "top should drop to sit on the shrunken middle");
    }

    @Test
    @DisplayName("settling ignores drag order, which is meaningless after Layer Collision")
    void settleUsesCurrentHeightsNotDragOrder() {
        AppState state = room();
        // The physically LOWER box has the HIGHER drag order. Sorting by drag order here
        // would put the floor box on top of the one above it — the exact failure this pass
        // exists to avoid, because during Layer Collision drag order says nothing about who
        // is resting on whom.
        Item low = box("low", 99, 24, 24, 96, 96, 20);
        low.baseHeight_in = 0;
        Item high = box("high", 1, 24, 24, 96, 96, 10);
        high.baseHeight_in = 20;
        state.items.add(low);
        state.items.add(high);

        Stacking.settleAllBaseHeights(state);

        assertEquals(0, low.baseHeight_in, "the box on the floor stays on the floor");
        assertEquals(20, high.baseHeight_in, "the box above it still rests on its top");
    }

    @Test
    @DisplayName("a box resting on nothing is put back on the floor")
    void recomputeResetsUnsupportedItems() {
        AppState state = room();
        Item stale = box("stale", 1, 12, 12, 0, 0, 12);
        stale.baseHeight_in = 60;                    // left over from a stack that moved away
        state.items.add(stale);

        Stacking.recomputeAllBaseHeights(state);

        assertEquals(0, stale.baseHeight_in);
    }

    private static AppState room() {
        AppState state = new AppState();
        state.room = new Room(20, 20, 12);
        state.items.clear();
        return state;
    }

    private static Item box(String id, double dragOrder, double w_in, double l_in,
            double x_px, double y_px, double h_in) {
        Item item = new Item();
        item.id = id;
        item.dragOrder = dragOrder;
        item.serial = dragOrder;
        item.w_in = w_in;
        item.l_in = l_in;
        item.h_in = h_in;
        item.x_px = x_px;
        item.y_px = y_px;
        item.baseHeight_in = 0;
        item.planned = false;
        item.name = "";
        item.customId = "";
        item.color = "hsl(0,55%,42%)";
        return item;
    }
}
