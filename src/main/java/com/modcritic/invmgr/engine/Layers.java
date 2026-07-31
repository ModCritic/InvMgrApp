package com.modcritic.invmgr.engine;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;

/**
 * The three rules about what an item looks like relative to the others: which one paints on
 * top, whether the layer slider has hidden it, and whether it dims out of the way of the
 * selected item.
 *
 * <p><b>Nothing here reads the Layer Collision toggle any more.</b> Paint order and dimming both
 * used to switch rule with it, and that was the cause of divergence D-5 — see
 * {@link #comparePaint}. They now share one rule, so they cannot contradict each other, and the
 * only state either of them needs is the layer slider's height, for {@link #isVisible}.
 */
public final class Layers {

    private Layers() {
    }

    /**
     * How to order two items front to back. A positive result means {@code a} paints on top.
     *
     * <p><b>Physical height decides it, and drag order only breaks ties.</b> An item that is
     * lower in the room can never be drawn over one above it, whatever was touched most
     * recently — that is the whole point of a top-down view of a stack. Where two items sit at
     * the same height, the one moved most recently wins, which is the familiar "I just put this
     * down, it is on top" behaviour.
     *
     * <p><b>This is divergence D-5, and it is a real fix, not a preference.</b> The original
     * switches rule on the Layer Collision toggle: height while it is on, drag order while it is
     * off (`zIndexFor`, original line 1043). With the toggle off, dragging a box at base 0 in
     * underneath a box at base 12 in gives the dragged box the highest drag order, so it paints
     * <em>over</em> the box it just went under. Reported by the user from the M3 jar.
     *
     * <p>Note how narrow the divergence actually is. In a flat room every item is at base 0, the
     * heights tie, and this reduces to drag order — bit-for-bit the original's behaviour. It only
     * differs when heights differ, which is exactly the broken case. And with Layer Collision on
     * it only adds a tiebreak where the original left the order undefined.
     *
     * <p>No {@link AppState} parameter, deliberately: the rule no longer depends on the toggle,
     * and taking a state it does not read would imply it might.
     */
    public static int comparePaint(Item a, Item b) {
        int byHeight = Double.compare(a.baseHeight_in, b.baseHeight_in);
        if (byHeight != 0) {
            return byHeight;
        }
        return Double.compare(a.dragOrder, b.dragOrder);
    }

    /**
     * Whether {@code item} is drawn on top of {@code other}.
     *
     * <p>Defined in terms of {@link #comparePaint} rather than repeating its rule, because the
     * dimming below has to agree with what is actually painted on top or the fade looks
     * arbitrary. Two copies of the rule is how they would drift apart.
     */
    public static boolean isAbove(Item item, Item other) {
        return comparePaint(item, other) > 0;
    }

    /**
     * Whether the layer slider is currently showing this item.
     *
     * <p>Sliding down hides everything at or above that height, letting the user peel back
     * upper layers and see what is underneath.
     *
     * <p><b>The comparison is strict:</b> an item whose base sits exactly at the slider's
     * height is hidden, not shown. Otherwise pulling the slider down to a shelf's height
     * would leave the shelf itself visible, which reads as the slider not working.
     */
    public static boolean isVisible(AppState state, Item item) {
        return item.baseHeight_in < state.layerFeet * 12;
    }

    /**
     * Whether this item should fade back to reveal the selected item underneath it.
     *
     * <p>Only items that are actually in the way fade: something stacked above the selection
     * <em>and</em> overlapping its footprint. An item elsewhere in the room is not obscuring
     * anything and stays at full strength.
     *
     * <p>"Above" means exactly {@link #isAbove} — the same rule that decides what is painted on
     * top, because an item that fades to reveal the selection but is drawn behind it anyway is
     * just a box that went dim for no visible reason. The original kept two rules here and
     * switched both on the Layer Collision toggle; part of divergence D-5 is that there is now
     * one rule, used by both.
     *
     * @param selected the currently selected item, or {@code null} if nothing is selected
     */
    public static boolean shouldDim(Item item, Item selected) {
        if (selected == null || item.planned) {
            return false;
        }
        return isAbove(item, selected) && Rect.of(item).overlaps(Rect.of(selected));
    }
}
