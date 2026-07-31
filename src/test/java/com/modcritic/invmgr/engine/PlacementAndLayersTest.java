package com.modcritic.invmgr.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Room;
import com.modcritic.invmgr.model.Units;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Readable tests for finding a spot for a new box, and for the three layering rules. */
class PlacementAndLayersTest {

    @Nested
    @DisplayName("finding a spot for a new box")
    class Placement_ {

        @Test
        @DisplayName("an empty room puts the box in the middle")
        void centresInAnEmptyRoom() {
            AppState state = room(12, 10);
            // Room is 1152 x 960 px; a 24 in box is 192 px, so centred means (480, 384).
            Collision.Point spot = Placement.findOpenSpot(state, 24, 24);
            assertEquals(480, spot.x());
            assertEquals(384, spot.y());
        }

        @Test
        @DisplayName("a box already in the middle makes the next one step aside")
        void stepsAsideWhenTheCentreIsTaken() {
            AppState state = room(12, 10);
            state.items.add(box("blocker", 1, 24, 24, 480, 384));

            Collision.Point spot = Placement.findOpenSpot(state, 24, 24);

            // The point of the search: adding several boxes in a row must not pile them on
            // one spot where each hides the ones underneath.
            assertTrue(isFree(state, spot, 24, 24), "the chosen spot must actually be free");
            assertFalse(spot.x() == 480 && spot.y() == 384, "and must not be the taken centre");
        }

        @Test
        @DisplayName("the nearest free spot wins, not merely a free one")
        void prefersTheClosestSpot() {
            AppState state = room(12, 10);
            state.items.add(box("blocker", 1, 24, 24, 480, 384));

            Collision.Point spot = Placement.findOpenSpot(state, 24, 24);

            // One step of 192 px away, not several rings out.
            double distance = Math.hypot(spot.x() - 480, spot.y() - 384);
            assertTrue(distance <= 200, "expected an adjacent spot, got distance " + distance);
        }

        @Test
        @DisplayName("a preferred point is honoured when it's free")
        void honoursThePreferredPoint() {
            AppState state = room(20, 20);
            Collision.Point spot = Placement.findOpenSpot(state, 12, 12, 300, 400);
            assertEquals(300, spot.x());
            assertEquals(400, spot.y());
        }

        @Test
        @DisplayName("a preferred point outside the room is pulled back inside")
        void clampsThePreferredPoint() {
            AppState state = room(12, 10);
            Collision.Point spot = Placement.findOpenSpot(state, 12, 12, -900, 99999);
            assertEquals(0, spot.x());
            assertEquals(864, spot.y(), "960 px room minus the 96 px box");
        }

        @Test
        @DisplayName("ghosts don't occupy space")
        void ignoresPlannedItems() {
            AppState state = room(12, 10);
            Item ghost = box("ghost", 1, 24, 24, 480, 384);
            ghost.planned = true;
            state.items.add(ghost);

            Collision.Point spot = Placement.findOpenSpot(state, 24, 24);
            assertEquals(480, spot.x(), "the centre is still free — a ghost isn't really there");
            assertEquals(384, spot.y());
        }

        @Test
        @DisplayName("when nothing is free, the box is placed anyway and allowed to overlap")
        void givesUpGracefullyInAFullRoom() {
            // A tiny room with a box nearly as large as it, and an obstacle positioned so that
            // every reachable position overlaps it. Refusing to add the item would be worse
            // than a visible overlap the user can drag apart.
            AppState state = room(4, 4);
            state.items.add(box("blocker", 1, 1, 1, 64, 64));

            Collision.Point spot = Placement.findOpenSpot(state, 40, 40);

            assertEquals(32, spot.x(), "falls back to the preferred (centred) point");
            assertEquals(32, spot.y());
            assertFalse(isFree(state, spot, 40, 40), "and yes, it overlaps — deliberately");
        }
    }

    @Nested
    @DisplayName("layering rules")
    class Layers_ {

        @Test
        @DisplayName("at equal height the most recently moved box paints on top")
        void paintOrderBreaksTiesByDragOrder() {
            // Both at base 0, which is every box in a flat room — so this is also the case that
            // proves D-5 leaves the original's ordinary behaviour untouched.
            Item older = box("older", 3, 12, 12, 0, 0);
            Item newer = box("newer", 7, 12, 12, 0, 0);

            assertTrue(Layers.comparePaint(newer, older) > 0);
        }

        @Test
        @DisplayName("physical height beats drag order, whatever the Layer Collision toggle says")
        void paintOrderPutsHeightFirst() {
            // Divergence D-5, and the bug the user reported: the lower box was dragged more
            // recently, so the original would paint it OVER the box it had just been pushed
            // underneath. Height decides instead, and it decides in BOTH toggle states -- which
            // is why neither this test nor the rule mentions layerCollision at all.
            Item low = box("low", 9, 12, 12, 0, 0);
            low.baseHeight_in = 0;
            Item high = box("high", 1, 12, 12, 0, 0);
            high.baseHeight_in = 12;

            assertTrue(Layers.comparePaint(high, low) > 0,
                    "the higher box must paint on top even though the lower one moved last");
            assertTrue(Layers.comparePaint(low, high) < 0, "and the comparison must be symmetric");
        }

        @Test
        @DisplayName("half-inch height differences don't collapse together")
        void paintOrderKeepsSmallHeightsApart() {
            // The old rule rounded height*10 into an int, so this needed the x10 to survive.
            // Comparing the heights directly cannot lose precision at all, but the case is worth
            // keeping: it is the one that would break if anyone reintroduced rounding.
            Item a = box("a", 1, 12, 12, 0, 0);
            a.baseHeight_in = 12;
            Item b = box("b", 2, 12, 12, 0, 0);
            b.baseHeight_in = 12.5;

            assertTrue(Layers.comparePaint(b, a) > 0);
        }

        @Test
        @DisplayName("the layer slider hides an item sitting exactly at its height")
        void visibilityIsStrict() {
            AppState state = room(12, 10);
            state.layerFeet = 3;                      // 36 inches
            Item below = box("below", 1, 12, 12, 0, 0);
            below.baseHeight_in = 35.9;
            Item exactly = box("exactly", 2, 12, 12, 0, 0);
            exactly.baseHeight_in = 36;

            assertTrue(Layers.isVisible(state, below));
            assertFalse(Layers.isVisible(state, exactly),
                    "otherwise dragging the slider down to a shelf leaves the shelf showing");
        }

        @Test
        @DisplayName("only boxes actually in the way dim for the selection")
        void dimmingNeedsBothHeightAndOverlap() {
            AppState state = room(20, 20);
            Item selected = box("sel", 1, 24, 24, 96, 96);
            Item aboveAndOver = box("over", 2, 24, 24, 96, 96);
            Item aboveElsewhere = box("elsewhere", 3, 24, 24, 900, 900);
            Item belowAndOver = box("below", 0, 24, 24, 96, 96);

            assertTrue(Layers.shouldDim(aboveAndOver, selected));
            assertFalse(Layers.shouldDim(aboveElsewhere, selected),
                    "a box across the room isn't hiding anything");
            assertFalse(Layers.shouldDim(belowAndOver, selected),
                    "a box underneath the selection isn't hiding it either");
        }

        @Test
        @DisplayName("nothing dims when nothing is selected, and ghosts never dim")
        void noSelectionAndGhosts() {
            AppState state = room(20, 20);
            Item selected = box("sel", 1, 24, 24, 96, 96);
            Item ghost = box("ghost", 5, 24, 24, 96, 96);
            ghost.planned = true;

            assertFalse(Layers.shouldDim(box("any", 9, 24, 24, 96, 96), null));
            assertFalse(Layers.shouldDim(ghost, selected));
        }

        @Test
        @DisplayName("dimming uses the same height-first rule as paint order")
        void dimmingFollowsTheSameRuleAsPaintOrder() {
            Item selected = box("sel", 9, 24, 24, 96, 96);
            selected.baseHeight_in = 24;
            Item higherButOlder = box("higher", 1, 24, 24, 96, 96);
            higherButOlder.baseHeight_in = 48;

            // By drag order this box is "below" the selection; by height it is above. Height
            // decides, because dimming has to agree with what is actually painted on top — a box
            // that fades to reveal the selection while being drawn behind it anyway just looks
            // like it went dim at random. Note there is no layerCollision setup here any more:
            // under D-5 one rule covers both toggle states.
            assertTrue(Layers.shouldDim(higherButOlder, selected));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static AppState room(double widthFeet, double lengthFeet) {
        AppState state = new AppState();
        state.room = new Room(widthFeet, lengthFeet, 8);
        state.items.clear();
        return state;
    }

    private static boolean isFree(AppState state, Collision.Point spot, double w_in, double l_in) {
        Rect candidate = Rect.at(spot.x(), spot.y(),
                Units.inchesToPx(w_in), Units.inchesToPx(l_in));
        for (Item item : state.items) {
            if (!item.planned && candidate.overlaps(Rect.of(item))) {
                return false;
            }
        }
        return true;
    }

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
}
