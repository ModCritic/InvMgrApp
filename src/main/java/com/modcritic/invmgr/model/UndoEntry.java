package com.modcritic.invmgr.model;

import java.util.List;

/**
 * One undoable action, remembering enough to put things back exactly as they were.
 *
 * <p>There are six kinds, and each stores something different — undoing a move needs the old
 * position, while undoing a deletion needs the whole item back. Written as a <b>sealed</b>
 * interface, which means this file lists every kind that can ever exist: the compiler then
 * refuses to build any code that handles some of them and forgets the rest. Six types with six
 * different shapes is exactly the situation where that matters.
 *
 * <p>Each kind is a {@code record} — a short way of writing a class whose values never change
 * after it is created. An undo entry describes something that already happened, so nothing about
 * it should ever be edited afterwards.
 *
 * <p><b>Why every entry carries a snapshot of every item's height.</b> Moving one box can change
 * the height of everything resting on it, and everything resting on those. A snapshot of just the
 * moved item would restore its position and leave the pile around it wrong. So the heights of all
 * items are captured, and undo <em>restores</em> them rather than recalculating — recalculating
 * would only reproduce today's answer, not the arrangement that actually existed.
 */
public sealed interface UndoEntry {

    /** What one item's stacking looked like: how high it sat, and where it came in drag order. */
    record HeightSnapshot(String id, double baseHeight_in, double dragOrder) {
    }

    /** The heights this action should restore, or an empty list if it does not affect them. */
    List<HeightSnapshot> heights();

    /** An item was added. Undoing removes it again. */
    record Added(String id) implements UndoEntry {
        @Override
        public List<HeightSnapshot> heights() {
            return List.of();
        }
    }

    /**
     * An item was deleted. Undoing puts it back.
     *
     * <p>Holds a <b>copy</b> of the item, not the item itself — the original is gone from the
     * room, and a copy cannot be changed underneath the entry by anything that happens later.
     */
    record Deleted(Item item, List<HeightSnapshot> heights) implements UndoEntry {
    }

    /** An item was dragged. Undoing returns it to where it was, in the order it was. */
    record Moved(String id, double x_px, double y_px, double dragOrder,
            List<HeightSnapshot> heights) implements UndoEntry {
    }

    /** A planned item was committed to the room. Undoing makes it a ghost again. */
    record Committed(String id) implements UndoEntry {
        @Override
        public List<HeightSnapshot> heights() {
            return List.of();
        }
    }

    /** An item's footprint was swapped (width and length exchanged). */
    record Swapped(String id, double w_in, double l_in, double x_px, double y_px,
            List<HeightSnapshot> heights) implements UndoEntry {
    }

    /** An item was edited. Undoing restores every field the dialog can change. */
    record Edited(String id, String name, String customId, double w_in, double l_in, double h_in,
            double x_px, double y_px, List<HeightSnapshot> heights) implements UndoEntry {
    }
}
