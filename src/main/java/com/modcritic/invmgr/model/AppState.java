package com.modcritic.invmgr.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Everything about the current room that gets saved to a file.
 *
 * <p>This is the whole persisted state and nothing more. Things the app tracks but never
 * writes down — which item is selected, the search box's contents, the undo history, the
 * fit-mode zoom factor — are deliberately absent: they belong to the interface, not to the
 * document. Search in particular is <b>ephemeral by design</b> in the original app, and the
 * undo stack is cleared on load rather than restored.
 *
 * <p>The field order below is the order these are written to the file, which the HTML app's
 * files also use. Keeping them aligned is what allows a saved file to be byte-identical.
 */
public final class AppState {

    /** Hard ceiling on items in one file. Over this, a load is refused outright. */
    public static final int MAX_ITEMS = 500;

    public static final double MAX_COUNTER = 1e9;
    public static final double MAX_LAYER_FEET = 1e6;

    public Room room = new Room();

    public List<Item> items = new ArrayList<>();

    /** Counts every item ever added, and hands out {@link Item#serial}. */
    public double itemCounter;

    /** Counts every add and every finished drag, and hands out {@link Item#dragOrder}. */
    public double dragOrderCounter;

    /** Where the layer slider sits, in feet. Starts level with the ceiling. */
    public double layerFeet = Room.DEFAULT_H;

    /** While on, newly added items are ghosts rather than real boxes. */
    public boolean planMode;

    /** Display-only. Never changes a single stored measurement. */
    public boolean metricMode;

    /** While on, base heights are frozen instead of settling onto each other. */
    public boolean layerCollision;

    /** Preset slots; a {@code null} entry is an empty slot, and position matters. */
    public List<Preset> presets = new ArrayList<>(
            Arrays.asList(new Preset[Preset.DEFAULT_SLOT_COUNT]));
}
