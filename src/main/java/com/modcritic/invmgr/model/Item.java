package com.modcritic.invmgr.model;

/**
 * One box in the room.
 *
 * <p><b>Units are not negotiable.</b> {@code w_in}, {@code l_in}, {@code h_in} and
 * {@code baseHeight_in} are inches; {@code x_px} and {@code y_px} are screen pixels at
 * 100% zoom, measured from the room's west and north walls. Nothing here is ever metric —
 * centimetres exist only as a label at the moment a number is shown to the user. The field
 * names keep their {@code _in} and {@code _px} suffixes precisely so a unit mistake is
 * visible at the point of use rather than three functions away.
 *
 * <p>Field names match the save file's keys exactly, and the declaration order below is the
 * order they are written in. Both are part of the compatibility contract with the HTML app.
 */
public final class Item {

    public static final double MIN_DIMENSION_IN = 1;
    public static final double MAX_DIMENSION_IN = 1000;
    public static final double DEFAULT_DIMENSION_IN = 12;

    public static final double MAX_POSITION_PX = 1_000_000;
    public static final double DEFAULT_POSITION_PX = 10;

    public static final double MAX_BASE_HEIGHT_IN = 1_000_000;

    public static final int MAX_NAME_LENGTH = 200;
    public static final int MAX_CUSTOM_ID_LENGTH = 60;

    /** {@code "item-id-<uuid>"}, or {@code "i<digits>_<digits>"} in files predating that. */
    public String id;

    /** 1-based creation number. Drives the default display name, "item #3". */
    public double serial;

    /** Higher means more recently added or dragged, which means painted on top. */
    public double dragOrder;

    /** Width in inches, west to east. */
    public double w_in;

    /** Length in inches, north to south. */
    public double l_in;

    /** Height in inches, floor to top. */
    public double h_in;

    /** Left edge in pixels from the room's west wall. */
    public double x_px;

    /** Top edge in pixels from the room's north wall. */
    public double y_px;

    /** {@code "hsl(H,S%,L%)"} with no spaces — the exact shape the format allows. */
    public String color;

    /** The user's name for it. Empty means "use the default", not "no name". */
    public String name;

    /** The user's own ID or SKU. Empty means unset. */
    public String customId;

    /** How far off the true floor this item sits, in inches. 0 means on the floor. */
    public double baseHeight_in;

    /** A planned item is a ghost: not in the room, not an obstacle, never drawn. */
    public boolean planned;

    /**
     * The name to show in the list and in tooltips: the user's name if they gave one,
     * otherwise {@code "item #"} and the serial number.
     *
     * <p>Kept as a method rather than being stored, because it has to follow the name
     * field. Storing it would let the two drift apart after an edit.
     */
    public String displayName() {
        if (name != null && !name.isEmpty()) {
            return name;
        }
        // The serial is a whole number, so print it without a trailing ".0".
        return "item #" + (long) serial;
    }
}
