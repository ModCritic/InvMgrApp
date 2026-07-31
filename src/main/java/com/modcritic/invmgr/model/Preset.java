package com.modcritic.invmgr.model;

/**
 * A saved box size, shown as a small slot the user can tap to fill in the Add Item dialog.
 *
 * <p>The name is capped at 2 characters because the slot is 28 pixels wide — it holds
 * "SM", not "Small". Dimensions are inches, like {@link Item}'s.
 *
 * <p>An empty slot is represented by {@code null} in the preset list rather than by an
 * empty {@code Preset}, matching the save format, where a slot is literally {@code null}.
 * Slot position is meaningful: the list order is the display order.
 */
public final class Preset {

    public static final int MAX_NAME_LENGTH = 2;

    /** Maximum number of slots read from a file. Extra slots are dropped. */
    public static final int MAX_SLOTS = 50;

    /** How many slots a fresh app (or a file with none) starts with. */
    public static final int DEFAULT_SLOT_COUNT = 3;

    public String name;
    public double w_in;
    public double l_in;
    public double h_in;

    public Preset(String name, double w_in, double l_in, double h_in) {
        this.name = name;
        this.w_in = w_in;
        this.l_in = l_in;
        this.h_in = h_in;
    }
}
