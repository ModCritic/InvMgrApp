package com.modcritic.invmgr.engine;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import java.util.Comparator;
import java.util.List;

/**
 * Works out how high off the floor every item sits.
 *
 * <p>There are two passes here, with the <b>same inner loop but different sort orders</b>,
 * and they are <b>not</b> interchangeable. Swapping one for the other produces stacks that
 * look almost right, which is the worst kind of wrong.
 *
 * <ul>
 *   <li>{@link #recomputeAllBaseHeights} — the everyday pass. Sorted by {@code dragOrder}:
 *       "whatever you just moved goes on top", which is what dropping a box on a pile
 *       should feel like.
 *   <li>{@link #settleAllBaseHeights} — the gravity pass, used in exactly one situation:
 *       when Layer Collision is switched off. Sorted by current height, lowest first.
 * </ul>
 */
public final class Stacking {

    private Stacking() {
    }

    /**
     * Recomputes every item's base height from scratch, in drag order.
     *
     * <p>Each item is placed on top of the tallest thing its footprint overlaps that was
     * processed before it. Processing in {@code dragOrder} means the most recently moved
     * item lands on top of what was already there.
     *
     * <p><b>Does nothing at all while Layer Collision is on.</b> That is not an
     * optimisation — it is the whole meaning of the mode: heights freeze for every cause,
     * including drags, adds, deletes, edits and swaps. Everything falls into place in one
     * pass when the mode is switched off, never continuously while it is on.
     *
     * <p><b>Planned items are skipped entirely.</b> A ghost is not in the room, so it
     * neither holds anything up nor receives a height of its own.
     */
    public static void recomputeAllBaseHeights(AppState state) {
        if (state.layerCollision) {
            return;
        }
        List<Item> sorted = realItemsSortedBy(state, Comparator.comparingDouble(it -> it.dragOrder));
        // Everything starts on the floor, then stacks up.
        //
        // This pass is provably redundant, and is kept deliberately. stackInOrder assigns a
        // height to every item in `sorted`: the first gets 0 because it has nothing before
        // it, and each later one reads only items already assigned in the same pass, so no
        // stale value can ever be observed. A mutation test confirms it — deleting these
        // three lines changes no result in 250 scenarios.
        //
        // It stays because it matches the original line for line, it costs one pass over a
        // list capped at 500, and it states the intent ("everything starts on the floor")
        // that the loop below then relies on. Note the gravity pass does NOT do this, and
        // for it that is not optional — see its own comment.
        for (Item item : sorted) {
            item.baseHeight_in = 0;
        }
        stackInOrder(sorted);
    }

    /**
     * Lets everything fall into place, processing the lowest items first.
     *
     * <p>Used only on the Layer Collision off switch. While that mode was on, an item's
     * position had nothing to do with when it was last dragged, so {@code dragOrder} no
     * longer says anything about who is resting on whom — sorting by it would produce
     * nonsense. Sorting by current height instead means that by the time a higher item
     * settles, whatever it lands on has already found its own true resting height, so a
     * cascade of several stacked items resolves correctly in a single pass.
     *
     * <p><b>Unlike the recompute pass, this does not zero the heights first.</b> It cannot:
     * the current heights are the sort key, so clearing them would destroy the very
     * ordering the pass depends on.
     */
    public static void settleAllBaseHeights(AppState state) {
        List<Item> sorted =
                realItemsSortedBy(state, Comparator.comparingDouble(it -> it.baseHeight_in));
        stackInOrder(sorted);
    }

    /**
     * The shared inner loop: each item rests on the tallest already-processed item its
     * footprint overlaps, or on the floor if it overlaps nothing.
     */
    private static void stackInOrder(List<Item> sorted) {
        for (int i = 0; i < sorted.size(); i++) {
            Item current = sorted.get(i);
            Rect currentRect = Rect.of(current);
            double maxTop = 0;
            for (int j = 0; j < i; j++) {
                Item earlier = sorted.get(j);
                if (currentRect.overlaps(Rect.of(earlier))) {
                    maxTop = Math.max(maxTop, earlier.baseHeight_in + earlier.h_in);
                }
            }
            current.baseHeight_in = maxTop;
        }
    }

    /**
     * The real items, sorted, as a new list — the app's own item order is never disturbed,
     * because that order drives the item list panel.
     *
     * <p>The sort is stable, so items with equal keys keep their existing relative order,
     * matching the original.
     */
    private static List<Item> realItemsSortedBy(AppState state, Comparator<Item> order) {
        return state.items.stream().filter(it -> !it.planned).sorted(order).toList();
    }
}
