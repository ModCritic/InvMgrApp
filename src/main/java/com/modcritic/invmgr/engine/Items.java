package com.modcritic.invmgr.engine;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.UndoEntry;
import com.modcritic.invmgr.model.Units;
import java.util.Random;
import java.util.UUID;

/**
 * Adding, editing, rotating and committing boxes — everything the dialogs do to the room,
 * with none of the drawing.
 *
 * <p>Kept apart from the dialogs on purpose. Each of these is a handful of steps that have to
 * happen in a fixed order (record the undo entry <em>before</em> changing anything, restack
 * <em>after</em>), and that order is the sort of thing that quietly rots when it lives inside a
 * button handler. Here it can be tested without opening a window.
 *
 * <p>None of these methods touch the screen or the selection. The caller redraws and decides
 * what ends up selected, which is also what keeps the rule about not rebuilding the item list
 * on selection enforceable from one place.
 */
public final class Items {

    /**
     * Saturation and lightness for every box colour, fixed.
     *
     * <p>Only the hue is random. That is what keeps the palette muted and consistent instead of
     * throwing the occasional neon box into an otherwise calm room — randomising all three
     * would look like a bug.
     */
    private static final int COLOR_SATURATION_PERCENT = 55;
    private static final int COLOR_LIGHTNESS_PERCENT = 42;

    private static final Random RANDOM = new Random();

    private Items() {
    }

    /**
     * A new box colour: a random hue at the fixed saturation and lightness.
     *
     * <p>The text shape — {@code hsl(207,55%,42%)}, no spaces — is what the save format allows,
     * so this is also what makes a file written here loadable by the original app.
     */
    public static String randomColor() {
        return "hsl(" + RANDOM.nextInt(360) + ","
                + COLOR_SATURATION_PERCENT + "%," + COLOR_LIGHTNESS_PERCENT + "%)";
    }

    /**
     * Adds a box to the room.
     *
     * <p>A real box gets dropped into the nearest free space rather than the middle, so that
     * adding several in a row does not pile them all on one spot where only the last is
     * visible. A <b>planned</b> box skips that search and is simply centred — it is never drawn
     * in the room, so where it "is" does not matter, and searching for a free spot for
     * something invisible would only slow the add down.
     *
     * @param name the user's name, or empty for the automatic "item #3"
     * @param customId the user's own ID or SKU, or empty
     * @return the box that was added
     */
    public static Item add(AppState state, UndoHistory undo, double w_in, double l_in,
            double h_in, String name, String customId) {
        state.itemCounter++;
        state.dragOrderCounter++;

        Item item = new Item();
        item.id = "item-id-" + UUID.randomUUID();
        item.serial = state.itemCounter;
        item.dragOrder = state.dragOrderCounter;
        item.w_in = Units.clampDimension(w_in);
        item.l_in = Units.clampDimension(l_in);
        item.h_in = Units.clampDimension(h_in);
        item.color = randomColor();
        item.name = name == null ? "" : name.trim();
        item.customId = truncate(customId == null ? "" : customId.trim(),
                Item.MAX_CUSTOM_ID_LENGTH);
        item.baseHeight_in = 0;
        item.planned = state.planMode;

        if (item.planned) {
            item.x_px = centred(Units.feetToPx(state.room.w), Units.inchesToPx(item.w_in));
            item.y_px = centred(Units.feetToPx(state.room.l), Units.inchesToPx(item.l_in));
        } else {
            Collision.Point spot = Placement.findOpenSpot(state, item.w_in, item.l_in);
            item.x_px = spot.x();
            item.y_px = spot.y();
        }

        state.items.add(item);
        if (!item.planned) {
            Stacking.recomputeAllBaseHeights(state);
        }
        undo.push(new UndoEntry.Added(item.id));
        return item;
    }

    /** Where a box sits if it is simply centred, never off the near wall. */
    private static double centred(double roomPx, double itemPx) {
        return Math.round(Math.max(0, (roomPx - itemPx) / 2));
    }

    /**
     * Turns a planned ghost into a real box, dropped as near as possible to where it was let go.
     *
     * <p>"As near as possible" rather than exactly there, because the drop point may already be
     * occupied — landing a box inside another one and leaving the user to notice would be
     * worse than putting it a few inches over.
     *
     * @return false if the box has already been committed, in which case nothing happened
     */
    public static boolean commit(AppState state, UndoHistory undo, Item item,
            double preferredX, double preferredY) {
        if (!item.planned) {
            return false;
        }
        Collision.Point spot =
                Placement.findOpenSpot(state, item.w_in, item.l_in, preferredX, preferredY);
        item.x_px = spot.x();
        item.y_px = spot.y();
        item.planned = false;

        undo.push(new UndoEntry.Committed(item.id));
        Stacking.recomputeAllBaseHeights(state);
        return true;
    }

    /**
     * Applies the Edit dialog's fields to a box.
     *
     * <p>Nothing is recorded and nothing changes if the user opened the dialog and pressed OK
     * without touching anything — otherwise the undo stack would fill with entries that undo
     * nothing, and pressing Undo would appear broken.
     *
     * <p>When the size changes, the box stays <b>centred where it was</b> rather than keeping
     * its top-left corner. Growing a box from its corner shoves it south-east across the room,
     * which is not what resizing something in place should look like. The new corner then goes
     * through the ordinary drag clamp, so a box made bigger cannot end up sticking through a
     * wall or, under Layer Collision, through its neighbour.
     *
     * @return true if anything actually changed
     */
    public static boolean edit(AppState state, UndoHistory undo, Item item, String name,
            String customId, double w_in, double l_in, double h_in) {
        String newName = name == null ? "" : name.trim();
        String newCustomId = truncate(customId == null ? "" : customId.trim(),
                Item.MAX_CUSTOM_ID_LENGTH);
        double newW = Units.clampDimension(w_in);
        double newL = Units.clampDimension(l_in);
        double newH = Units.clampDimension(h_in);

        boolean dimensionsChanged = newW != item.w_in || newL != item.l_in || newH != item.h_in;
        boolean changed = dimensionsChanged
                || !newName.equals(item.name)
                || !newCustomId.equals(item.customId);
        if (!changed) {
            return false;
        }

        undo.push(new UndoEntry.Edited(item.id, item.name, item.customId, item.w_in, item.l_in,
                item.h_in, item.x_px, item.y_px, UndoHistory.snapshotHeights(state)));

        item.name = newName;
        item.customId = newCustomId;
        if (dimensionsChanged) {
            resizeAroundCentre(state, item, newW, newL, newH);
            Stacking.recomputeAllBaseHeights(state);
        }
        return true;
    }

    /**
     * Rotates a box a quarter turn by exchanging its width and length.
     *
     * <p>Height is untouched — this turns a box on the floor, it does not tip it over.
     */
    public static void swap(AppState state, UndoHistory undo, Item item) {
        undo.push(new UndoEntry.Swapped(item.id, item.w_in, item.l_in, item.x_px, item.y_px,
                UndoHistory.snapshotHeights(state)));

        resizeAroundCentre(state, item, item.l_in, item.w_in, item.h_in);
        Stacking.recomputeAllBaseHeights(state);
    }

    /**
     * Changes a box's size while keeping its middle where it was, then puts the result
     * somewhere legal.
     *
     * <p>Shared by editing and rotating because both have exactly the same problem: the box's
     * footprint changes underneath a position that was chosen for the old one.
     */
    private static void resizeAroundCentre(AppState state, Item item, double w_in, double l_in,
            double h_in) {
        double centreX = item.x_px + Units.inchesToPx(item.w_in) / 2;
        double centreY = item.y_px + Units.inchesToPx(item.l_in) / 2;

        item.w_in = w_in;
        item.l_in = l_in;
        item.h_in = h_in;

        // The clamp has to run after the new size is in place: it measures the item to work out
        // how far it may go, and measuring the old size would let a grown box overhang a wall.
        Collision.Point placed = Collision.clampItem(state, item,
                centreX - Units.inchesToPx(item.w_in) / 2,
                centreY - Units.inchesToPx(item.l_in) / 2);
        item.x_px = placed.x();
        item.y_px = placed.y();
    }

    private static String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
