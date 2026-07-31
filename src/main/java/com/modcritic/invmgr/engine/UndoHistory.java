package com.modcritic.invmgr.engine;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.UndoEntry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The list of things that can be undone, and the code that undoes them.
 *
 * <p><b>Capped at {@value #MAX_ENTRIES} actions</b>, oldest thrown away first. The original app has
 * no limit at all, which grows without bound for as long as it stays open — every entry holds a
 * snapshot of every item, so the entries are not free. 500 is far more than anyone reaches by hand
 * and exists purely so a long session cannot grow forever (OD-6, decided by the user).
 *
 * <p><b>Deliberately not part of {@link AppState}.</b> Undo history is never written to a save
 * file, and the original clears it on load rather than restoring it. Keeping it out of the state
 * object is what makes that impossible to get wrong.
 *
 * <p>Undo <b>restores</b> heights from the snapshot rather than recalculating them. Recalculating
 * would produce a correct-looking arrangement for the room as it is now, not the arrangement that
 * actually existed before the action.
 */
public final class UndoHistory {

    /** OD-6: the user's chosen cap. */
    public static final int MAX_ENTRIES = 500;

    /** What the status bar says when there is nothing left to undo. */
    public static final String NOTHING_TO_UNDO = "Nothing to undo.";

    private final Deque<UndoEntry> entries = new ArrayDeque<>();

    /** Records an action. If the history is full, the oldest entry is dropped. */
    public void push(UndoEntry entry) {
        entries.addLast(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Forgotten on load, exactly as the original does — a new room has no history. */
    public void clear() {
        entries.clear();
    }

    /**
     * Captures every item's height and drag order.
     *
     * <p>Take this <b>before</b> making a change, and hand it to the entry describing that change.
     */
    public static List<UndoEntry.HeightSnapshot> snapshotHeights(AppState state) {
        List<UndoEntry.HeightSnapshot> snapshot = new ArrayList<>(state.items.size());
        for (Item item : state.items) {
            snapshot.add(new UndoEntry.HeightSnapshot(item.id, item.baseHeight_in, item.dragOrder));
        }
        return List.copyOf(snapshot);
    }

    /**
     * Undoes the most recent action.
     *
     * @return the message to show, including {@value #NOTHING_TO_UNDO} when the history is empty.
     *     The caller redraws afterwards; this only changes the data.
     */
    public String undo(AppState state) {
        if (entries.isEmpty()) {
            return NOTHING_TO_UNDO;
        }
        UndoEntry entry = entries.removeLast();

        // Each branch names the item in its own message, because "Undone." tells you nothing when
        // you are several steps back and trying to work out what you just reversed.
        if (entry instanceof UndoEntry.Added added) {
            return undoAdd(state, added);
        }
        if (entry instanceof UndoEntry.Deleted deleted) {
            return undoDelete(state, deleted);
        }
        if (entry instanceof UndoEntry.Moved moved) {
            return undoMove(state, moved);
        }
        if (entry instanceof UndoEntry.Committed committed) {
            return undoCommit(state, committed);
        }
        if (entry instanceof UndoEntry.Swapped swapped) {
            return undoSwap(state, swapped);
        }
        if (entry instanceof UndoEntry.Edited edited) {
            return undoEdit(state, edited);
        }
        // Unreachable: UndoEntry is sealed and every kind is handled above. If a seventh kind is
        // ever added, the compiler will not catch this line, so it fails loudly instead of
        // silently doing nothing.
        throw new IllegalStateException("unhandled undo entry: " + entry.getClass());
    }

    private String undoAdd(AppState state, UndoEntry.Added added) {
        Item item = find(state, added.id());
        String name = item == null ? "item" : item.displayName();
        boolean wasPlanned = item != null && item.planned;
        state.items.removeIf(candidate -> candidate.id.equals(added.id()));
        // A planned item was never in the room, so say "planning" rather than implying it was.
        return wasPlanned ? "Undid planning of " + name : "Undid " + name;
    }

    private String undoDelete(AppState state, UndoEntry.Deleted deleted) {
        // Another copy, so undoing twice — delete, undo, delete, undo — cannot hand out the same
        // object twice and let the two share a position.
        Item restored = copyOf(deleted.item());
        state.items.add(restored);
        restoreHeights(state, deleted.heights());
        return "Undid deletion of " + restored.displayName();
    }

    private String undoMove(AppState state, UndoEntry.Moved moved) {
        Item item = find(state, moved.id());
        if (item == null) {
            return "Undo done.";
        }
        item.x_px = moved.x_px();
        item.y_px = moved.y_px();
        item.dragOrder = moved.dragOrder();
        restoreHeights(state, moved.heights());
        return "Undid drag of " + item.displayName();
    }

    private String undoCommit(AppState state, UndoEntry.Committed committed) {
        Item item = find(state, committed.id());
        if (item == null) {
            return "Undo done.";
        }
        item.planned = true;
        return "Undid " + item.displayName();
    }

    private String undoSwap(AppState state, UndoEntry.Swapped swapped) {
        Item item = find(state, swapped.id());
        if (item == null) {
            return "Undo done.";
        }
        item.w_in = swapped.w_in();
        item.l_in = swapped.l_in();
        item.x_px = swapped.x_px();
        item.y_px = swapped.y_px();
        restoreHeights(state, swapped.heights());
        return "Undid swap of " + item.displayName();
    }

    private String undoEdit(AppState state, UndoEntry.Edited edited) {
        Item item = find(state, edited.id());
        if (item == null) {
            return "Undo done.";
        }
        item.name = edited.name();
        item.customId = edited.customId() == null ? "" : edited.customId();
        item.w_in = edited.w_in();
        item.l_in = edited.l_in();
        item.h_in = edited.h_in();
        item.x_px = edited.x_px();
        item.y_px = edited.y_px();
        restoreHeights(state, edited.heights());
        // Read after restoring, so a renamed item is announced under the name being restored.
        return "Undid edit of " + item.displayName();
    }

    /** Replays a snapshot. Items that no longer exist are skipped rather than resurrected. */
    private static void restoreHeights(AppState state, List<UndoEntry.HeightSnapshot> heights) {
        for (UndoEntry.HeightSnapshot snapshot : heights) {
            Item item = find(state, snapshot.id());
            if (item != null) {
                item.baseHeight_in = snapshot.baseHeight_in();
                item.dragOrder = snapshot.dragOrder();
            }
        }
    }

    private static Item find(AppState state, String id) {
        for (Item item : state.items) {
            if (item.id.equals(id)) {
                return item;
            }
        }
        return null;
    }

    /** A field-for-field copy, so an entry can never be changed by later edits to the original. */
    public static Item copyOf(Item source) {
        Item copy = new Item();
        copy.id = source.id;
        copy.serial = source.serial;
        copy.dragOrder = source.dragOrder;
        copy.w_in = source.w_in;
        copy.l_in = source.l_in;
        copy.h_in = source.h_in;
        copy.x_px = source.x_px;
        copy.y_px = source.y_px;
        copy.color = source.color;
        copy.name = source.name;
        copy.customId = source.customId;
        copy.baseHeight_in = source.baseHeight_in;
        copy.planned = source.planned;
        return copy;
    }
}
