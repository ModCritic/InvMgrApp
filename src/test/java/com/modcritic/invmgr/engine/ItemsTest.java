package com.modcritic.invmgr.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Units;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Adding, editing, rotating and committing boxes — the operations behind the dialogs. */
class ItemsTest {

    private static final Pattern HSL = Pattern.compile("^hsl\\((\\d+),(\\d+)%,(\\d+)%\\)$");

    // -------------------------------------------------------------------- add

    @Test
    void addingGivesTheBoxItsCountersAndDefaults() {
        AppState state = new AppState();
        UndoHistory undo = new UndoHistory();

        Item first = Items.add(state, undo, 12, 24, 18, "Blue Bin", "SKU-1");

        assertEquals(1, first.serial);
        assertEquals(1, first.dragOrder);
        assertEquals(12, first.w_in);
        assertEquals(24, first.l_in);
        assertEquals(18, first.h_in);
        assertEquals("Blue Bin", first.name);
        assertEquals("SKU-1", first.customId);
        assertEquals(0, first.baseHeight_in);
        assertFalse(first.planned);
        assertTrue(first.id.startsWith("item-id-"));
        assertEquals(1, state.items.size());

        Item second = Items.add(state, undo, 12, 12, 12, "", "");
        assertEquals(2, second.serial, "the counter carries on");
        assertEquals("item #2", second.displayName());
    }

    @Test
    void addingIsUndoable() {
        AppState state = new AppState();
        UndoHistory undo = new UndoHistory();
        Item added = Items.add(state, undo, 12, 12, 12, "Bin", "");

        assertEquals("Undid Bin", undo.undo(state));
        assertTrue(state.items.isEmpty());
        assertNotEquals(null, added.id);
    }

    @Test
    void twoBoxesAddedInARowDoNotLandOnTopOfEachOther() {
        // Without the open-spot search they would both be centred, and the room would look
        // like it held one box while holding two.
        AppState state = new AppState();
        UndoHistory undo = new UndoHistory();
        Item first = Items.add(state, undo, 12, 12, 12, "", "");
        Item second = Items.add(state, undo, 12, 12, 12, "", "");

        assertFalse(Rect.of(first).overlaps(Rect.of(second)));
    }

    @Test
    void aPlannedBoxIsCentredAndNeverStacks() {
        AppState state = new AppState();
        state.planMode = true;
        UndoHistory undo = new UndoHistory();

        Item ghost = Items.add(state, undo, 12, 12, 12, "Ghost", "");

        assertTrue(ghost.planned);
        assertEquals(Math.round((Units.feetToPx(state.room.w) - Units.inchesToPx(12)) / 2),
                ghost.x_px);
        assertEquals(Math.round((Units.feetToPx(state.room.l) - Units.inchesToPx(12)) / 2),
                ghost.y_px);
        assertEquals(0, ghost.baseHeight_in);
        assertEquals("Undid planning of Ghost", undo.undo(state),
                "the message says planning, because it was never in the room");
    }

    @Test
    void twoPlannedBoxesAreAllowedToSitInTheSamePlace() {
        // They are never drawn, so searching for a free spot for them would be wasted work.
        AppState state = new AppState();
        state.planMode = true;
        UndoHistory undo = new UndoHistory();
        Item first = Items.add(state, undo, 12, 12, 12, "", "");
        Item second = Items.add(state, undo, 12, 12, 12, "", "");

        assertEquals(first.x_px, second.x_px);
        assertEquals(first.y_px, second.y_px);
    }

    @Test
    void aPlannedBoxIsCentredEvenWhenTheCentreIsOccupied() {
        // The distinguishing case for "ghosts skip the open-spot search". With the centre free,
        // searching and not searching give the same answer, so only an occupied centre shows
        // the difference -- and a ghost must land on top of the box that is there, because it
        // is not in the room at all.
        AppState state = new AppState();
        UndoHistory undo = new UndoHistory();
        Item real = Items.add(state, undo, 12, 12, 12, "Real", "");

        state.planMode = true;
        Item ghost = Items.add(state, undo, 12, 12, 12, "Ghost", "");

        assertEquals(real.x_px, ghost.x_px, "the ghost is centred, not moved out of the way");
        assertEquals(real.y_px, ghost.y_px);
        assertTrue(Rect.of(real).overlaps(Rect.of(ghost)));
    }

    @Test
    void aPlannedBoxIsNotAnObstacleForARealOne() {
        AppState state = new AppState();
        UndoHistory undo = new UndoHistory();
        state.planMode = true;
        Item ghost = Items.add(state, undo, 12, 12, 12, "", "");
        state.planMode = false;
        Item real = Items.add(state, undo, 12, 12, 12, "", "");

        assertEquals(ghost.x_px, real.x_px, "the real box takes the centre the ghost occupies");
        assertEquals(ghost.y_px, real.y_px);
    }

    @Test
    void dimensionsAndTheIdAreHeldToTheirLimits() {
        AppState state = new AppState();
        UndoHistory undo = new UndoHistory();
        Item huge = Items.add(state, undo, 5000, -3, 12, "  Trimmed  ", "x".repeat(80));

        assertEquals(Item.MAX_DIMENSION_IN, huge.w_in);
        assertEquals(Item.MIN_DIMENSION_IN, huge.l_in);
        assertEquals("Trimmed", huge.name, "surrounding spaces are dropped");
        assertEquals(Item.MAX_CUSTOM_ID_LENGTH, huge.customId.length());
    }

    @Test
    void newBoxesStackOnWhateverTheyLandOn() {
        AppState state = new AppState();
        UndoHistory undo = new UndoHistory();
        // A room only just big enough for one box, so the second has nowhere free to go.
        state.room.w = 1;
        state.room.l = 1;

        Items.add(state, undo, 12, 12, 12, "", "");
        Item second = Items.add(state, undo, 12, 12, 12, "", "");

        assertEquals(12, second.baseHeight_in, "the second box rests on the first");
    }

    // ------------------------------------------------------------------ colour

    @Test
    void everyColourIsAMutedHslWithFixedSaturationAndLightness() {
        for (int i = 0; i < 200; i++) {
            Matcher matcher = HSL.matcher(Items.randomColor());
            assertTrue(matcher.matches(), "colour must be hsl(H,S%,L%) with no spaces");
            int hue = Integer.parseInt(matcher.group(1));
            assertTrue(hue >= 0 && hue <= 359, "hue out of range: " + hue);
            assertEquals("55", matcher.group(2), "saturation is fixed, not random");
            assertEquals("42", matcher.group(3), "lightness is fixed, not random");
        }
    }

    // ----------------------------------------------------------------- commit

    @Test
    void committingPutsAGhostInTheRoomNearWhereItWasDropped() {
        AppState state = new AppState();
        state.planMode = true;
        UndoHistory undo = new UndoHistory();
        Item ghost = Items.add(state, undo, 12, 12, 12, "Ghost", "");
        state.planMode = false;

        assertTrue(Items.commit(state, undo, ghost, 200, 300));

        assertFalse(ghost.planned);
        assertEquals(200, ghost.x_px, "the drop point was free, so it lands exactly there");
        assertEquals(300, ghost.y_px);
        assertFalse(Items.commit(state, undo, ghost, 0, 0), "committing twice does nothing");
    }

    @Test
    void committingIsUndoableBackToAGhost() {
        AppState state = new AppState();
        state.planMode = true;
        UndoHistory undo = new UndoHistory();
        Item ghost = Items.add(state, undo, 12, 12, 12, "Ghost", "");
        state.planMode = false;
        Items.commit(state, undo, ghost, 200, 300);

        assertEquals("Undid Ghost", undo.undo(state));
        assertTrue(ghost.planned);
    }

    // ------------------------------------------------------------------- edit

    @Test
    void pressingOkWithoutChangingAnythingRecordsNothing() {
        // Otherwise the undo stack fills with entries that undo nothing, and pressing Undo
        // looks broken.
        AppState state = new AppState();
        UndoHistory undo = new UndoHistory();
        Item bin = Items.add(state, undo, 12, 12, 12, "Bin", "SKU");
        int before = undo.size();

        assertFalse(Items.edit(state, undo, bin, "Bin", "SKU", 12, 12, 12));
        assertEquals(before, undo.size());
    }

    @Test
    void editingRenamesAndResizes() {
        AppState state = new AppState();
        UndoHistory undo = new UndoHistory();
        Item bin = Items.add(state, undo, 12, 12, 12, "Bin", "");

        assertTrue(Items.edit(state, undo, bin, "Crate", "SKU-9", 24, 36, 18));
        assertEquals("Crate", bin.name);
        assertEquals("SKU-9", bin.customId);
        assertEquals(24, bin.w_in);
        assertEquals(36, bin.l_in);
        assertEquals(18, bin.h_in);
    }

    @Test
    void aResizedBoxKeepsItsMiddleWhereItWas() {
        AppState state = new AppState();
        UndoHistory undo = new UndoHistory();
        Item bin = Items.add(state, undo, 12, 12, 12, "Bin", "");
        bin.x_px = 300;
        bin.y_px = 300;
        double centreX = bin.x_px + Units.inchesToPx(bin.w_in) / 2;

        Items.edit(state, undo, bin, "Bin", "", 24, 12, 12);

        assertEquals(centreX, bin.x_px + Units.inchesToPx(bin.w_in) / 2,
                "growing from the corner would shove the box across the room");
    }

    @Test
    void aBoxGrownAtTheWallIsPushedBackInside() {
        AppState state = new AppState();
        UndoHistory undo = new UndoHistory();
        Item bin = Items.add(state, undo, 12, 12, 12, "Bin", "");
        bin.x_px = Units.feetToPx(state.room.w) - Units.inchesToPx(12);   // flush east

        Items.edit(state, undo, bin, "Bin", "", 48, 12, 12);

        assertTrue(bin.x_px + Units.inchesToPx(bin.w_in) <= Units.feetToPx(state.room.w),
                "a box made bigger must not end up sticking through the wall");
    }

    @Test
    void editingIsUndoableIncludingTheSize() {
        AppState state = new AppState();
        UndoHistory undo = new UndoHistory();
        Item bin = Items.add(state, undo, 12, 12, 12, "Bin", "");
        double x = bin.x_px;

        Items.edit(state, undo, bin, "Crate", "SKU", 24, 36, 18);
        assertEquals("Undid edit of Bin", undo.undo(state));

        assertEquals("Bin", bin.name);
        assertEquals("", bin.customId);
        assertEquals(12, bin.w_in);
        assertEquals(12, bin.l_in);
        assertEquals(12, bin.h_in);
        assertEquals(x, bin.x_px);
    }

    // ------------------------------------------------------------------- swap

    @Test
    void swappingTurnsTheBoxAQuarterTurnAroundItsMiddle() {
        AppState state = new AppState();
        UndoHistory undo = new UndoHistory();
        Item bin = Items.add(state, undo, 12, 36, 18, "Bin", "");
        bin.x_px = 200;
        bin.y_px = 200;
        double centreX = bin.x_px + Units.inchesToPx(bin.w_in) / 2;
        double centreY = bin.y_px + Units.inchesToPx(bin.l_in) / 2;

        Items.swap(state, undo, bin);

        assertEquals(36, bin.w_in);
        assertEquals(12, bin.l_in);
        assertEquals(18, bin.h_in, "height is untouched -- this turns the box, it does not tip it");
        assertEquals(centreX, bin.x_px + Units.inchesToPx(bin.w_in) / 2);
        assertEquals(centreY, bin.y_px + Units.inchesToPx(bin.l_in) / 2);
    }

    @Test
    void swappingIsUndoable() {
        AppState state = new AppState();
        UndoHistory undo = new UndoHistory();
        Item bin = Items.add(state, undo, 12, 36, 18, "Bin", "");
        double x = bin.x_px;
        double y = bin.y_px;

        Items.swap(state, undo, bin);
        assertEquals("Undid swap of Bin", undo.undo(state));

        assertEquals(12, bin.w_in);
        assertEquals(36, bin.l_in);
        assertEquals(x, bin.x_px);
        assertEquals(y, bin.y_px);
    }
}
