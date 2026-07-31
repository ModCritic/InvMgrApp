package com.modcritic.invmgr.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Room;
import com.modcritic.invmgr.model.UndoEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests the undo history: what it restores, what it says, and where it stops growing. */
class UndoHistoryTest {

    @Test
    @DisplayName("nothing to undo says so, rather than doing something surprising")
    void emptyHistory() {
        AppState state = room();
        assertEquals(UndoHistory.NOTHING_TO_UNDO, new UndoHistory().undo(state));
    }

    @Test
    @DisplayName("undoing a drag restores the position, the drag order, and the message")
    void undoMove() {
        AppState state = room();
        Item box = box("a", 1, 96, 96);
        state.items.add(box);
        box.name = "Blue Bin";

        UndoHistory history = new UndoHistory();
        history.push(new UndoEntry.Moved(box.id, box.x_px, box.y_px, box.dragOrder,
                UndoHistory.snapshotHeights(state)));

        box.x_px = 500;
        box.y_px = 400;
        box.dragOrder = 9;

        assertEquals("Undid drag of Blue Bin", history.undo(state));
        assertEquals(96, box.x_px);
        assertEquals(96, box.y_px);
        assertEquals(1, box.dragOrder, "drag order is part of the move, so it comes back too");
    }

    @Test
    @DisplayName("undoing a deletion puts the item back with its stacking intact")
    void undoDelete() {
        AppState state = room();
        Item lower = box("lower", 1, 96, 96);
        lower.h_in = 18;
        Item upper = box("upper", 2, 96, 96);
        upper.baseHeight_in = 18;
        state.items.add(lower);
        state.items.add(upper);

        UndoHistory history = new UndoHistory();
        history.push(new UndoEntry.Deleted(UndoHistory.copyOf(lower),
                UndoHistory.snapshotHeights(state)));
        state.items.remove(lower);
        // With the lower box gone, the upper one would have fallen to the floor.
        upper.baseHeight_in = 0;

        assertEquals("Undid deletion of item #1", history.undo(state));
        assertEquals(2, state.items.size());
        assertEquals(18, upper.baseHeight_in,
                "the box that was resting on top must be back up where it was");
    }

    @Test
    @DisplayName("heights are restored, not recalculated")
    void restoresRatherThanRecomputes() {
        // The distinction matters: a box parked at an impossible height during Layer Collision
        // must come back to that height, not be dropped to where physics would put it now.
        AppState state = room();
        Item floating = box("floating", 1, 96, 96);
        floating.baseHeight_in = 47;
        state.items.add(floating);

        UndoHistory history = new UndoHistory();
        history.push(new UndoEntry.Moved(floating.id, floating.x_px, floating.y_px,
                floating.dragOrder, UndoHistory.snapshotHeights(state)));

        floating.baseHeight_in = 0;
        history.undo(state);

        assertEquals(47, floating.baseHeight_in);
    }

    @Test
    @DisplayName("undoing an add removes the item, and says so differently for a ghost")
    void undoAdd() {
        AppState state = room();
        Item real = box("real", 1, 96, 96);
        real.name = "Crate";
        state.items.add(real);

        UndoHistory history = new UndoHistory();
        history.push(new UndoEntry.Added(real.id));
        assertEquals("Undid Crate", history.undo(state));
        assertTrue(state.items.isEmpty());

        Item ghost = box("ghost", 2, 96, 96);
        ghost.name = "Spare";
        ghost.planned = true;
        state.items.add(ghost);
        history.push(new UndoEntry.Added(ghost.id));
        assertEquals("Undid planning of Spare", history.undo(state),
                "a planned item was never in the room, so the wording differs");
    }

    @Test
    @DisplayName("undoing a commit makes the item a ghost again")
    void undoCommit() {
        AppState state = room();
        Item item = box("a", 1, 96, 96);
        item.name = "Spare Parts";
        state.items.add(item);

        UndoHistory history = new UndoHistory();
        history.push(new UndoEntry.Committed(item.id));

        assertEquals("Undid Spare Parts", history.undo(state));
        assertTrue(item.planned);
    }

    @Test
    @DisplayName("undoing an edit restores every field the dialog can change")
    void undoEdit() {
        AppState state = room();
        Item item = box("a", 1, 96, 96);
        item.name = "Old";
        item.customId = "SKU-1";
        state.items.add(item);

        UndoHistory history = new UndoHistory();
        history.push(new UndoEntry.Edited(item.id, "Old", "SKU-1", 12, 12, 12, 96, 96,
                UndoHistory.snapshotHeights(state)));

        item.name = "New";
        item.customId = "SKU-2";
        item.w_in = 40;
        item.l_in = 40;
        item.h_in = 40;
        item.x_px = 800;
        item.y_px = 800;

        assertEquals("Undid edit of Old", history.undo(state));
        assertEquals("Old", item.name);
        assertEquals("SKU-1", item.customId);
        assertEquals(12, item.w_in);
        assertEquals(12, item.h_in);
        assertEquals(96, item.x_px);
    }

    @Test
    @DisplayName("undoing a swap restores the footprint")
    void undoSwap() {
        AppState state = room();
        Item item = box("a", 1, 96, 96);
        item.name = "Bin";
        item.w_in = 24;
        item.l_in = 12;
        state.items.add(item);

        UndoHistory history = new UndoHistory();
        history.push(new UndoEntry.Swapped(item.id, 24, 12, 96, 96,
                UndoHistory.snapshotHeights(state)));
        item.w_in = 12;
        item.l_in = 24;

        assertEquals("Undid swap of Bin", history.undo(state));
        assertEquals(24, item.w_in);
        assertEquals(12, item.l_in);
    }

    @Test
    @DisplayName("the history stops at 500 entries, dropping the oldest")
    void cappedAtFiveHundred() {
        AppState state = room();
        Item item = box("a", 1, 96, 96);
        state.items.add(item);

        UndoHistory history = new UndoHistory();
        // 600 moves, each remembering a different position, so which ones survive is checkable.
        for (int i = 0; i < 600; i++) {
            history.push(new UndoEntry.Moved(item.id, i, 0, 1, java.util.List.of()));
        }

        assertEquals(UndoHistory.MAX_ENTRIES, history.size());
        assertEquals(500, UndoHistory.MAX_ENTRIES, "OD-6: the user chose 500");

        // The newest entry must still be there — the cap discards from the old end.
        history.undo(state);
        assertEquals(599, item.x_px, "the most recent action must be the first one undone");

        // Drain it: 500 entries means 500 undos, then nothing.
        for (int i = 0; i < 499; i++) {
            history.undo(state);
        }
        assertTrue(history.isEmpty());
        assertEquals(UndoHistory.NOTHING_TO_UNDO, history.undo(state));
        // The 100 oldest were dropped, so the earliest recoverable position is the 100th.
        assertEquals(100, item.x_px, "the oldest 100 entries should have been discarded");
    }

    @Test
    @DisplayName("undoing several actions unwinds them newest first")
    void undoIsALine() {
        AppState state = room();
        Item item = box("a", 1, 0, 0);
        state.items.add(item);

        UndoHistory history = new UndoHistory();
        history.push(new UndoEntry.Moved(item.id, 10, 0, 1, java.util.List.of()));
        history.push(new UndoEntry.Moved(item.id, 20, 0, 1, java.util.List.of()));
        history.push(new UndoEntry.Moved(item.id, 30, 0, 1, java.util.List.of()));

        history.undo(state);
        assertEquals(30, item.x_px);
        history.undo(state);
        assertEquals(20, item.x_px);
        history.undo(state);
        assertEquals(10, item.x_px);
        assertTrue(history.isEmpty());
    }

    @Test
    @DisplayName("a deleted item's entry keeps a copy, not the item itself")
    void deletedEntryHoldsACopy() {
        AppState state = room();
        Item item = box("a", 1, 96, 96);
        item.name = "Original";
        state.items.add(item);

        UndoHistory history = new UndoHistory();
        history.push(new UndoEntry.Deleted(UndoHistory.copyOf(item),
                UndoHistory.snapshotHeights(state)));
        state.items.remove(item);

        // Changing the now-removed object must not reach into the undo entry.
        item.name = "Changed after deletion";
        item.x_px = 9999;

        history.undo(state);
        Item restored = state.items.get(0);
        assertEquals("Original", restored.name);
        assertEquals(96, restored.x_px);
        assertFalse(restored == item, "the restored item is a copy, not the same object");
    }

    @Test
    @DisplayName("clearing forgets everything, as a load does")
    void clearing() {
        UndoHistory history = new UndoHistory();
        history.push(new UndoEntry.Added("a"));
        history.clear();
        assertTrue(history.isEmpty());
        assertEquals(UndoHistory.NOTHING_TO_UNDO, history.undo(room()));
    }

    private static AppState room() {
        AppState state = new AppState();
        state.room = new Room(20, 20, 10);
        state.items.clear();
        return state;
    }

    private static Item box(String id, double serial, double x_px, double y_px) {
        Item item = new Item();
        item.id = id;
        item.serial = serial;
        item.dragOrder = serial;
        item.w_in = 12;
        item.l_in = 12;
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
