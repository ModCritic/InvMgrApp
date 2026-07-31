package com.modcritic.invmgr.model;

/**
 * The room's real-world size, in <b>feet</b>.
 *
 * <p>Feet, not inches — the room and the items deliberately use different units, because
 * that is what the save format has always done and changing it would break every existing
 * file. Items are in inches; see {@link Item}.
 *
 * <p>The fields are plain and directly writable rather than hidden behind getters and
 * setters. That is a deliberate choice for the model classes: the canvas edits these
 * values constantly, the original app's algorithms are written against exactly this shape,
 * and keeping the port literal makes it far easier to check the Java against the
 * JavaScript it came from.
 */
public final class Room {

    public static final double MIN_W = 1;
    public static final double MAX_W = 200;
    public static final double DEFAULT_W = 12;

    public static final double MIN_L = 1;
    public static final double MAX_L = 200;
    public static final double DEFAULT_L = 10;

    public static final double MIN_H = 1;
    public static final double MAX_H = 50;
    public static final double DEFAULT_H = 8;

    /** Width in feet, west to east. */
    public double w;

    /** Length in feet, north to south. */
    public double l;

    /** Ceiling height in feet. */
    public double h;

    /** A default 12 × 10 × 8 ft room, the same starting room the HTML app opens with. */
    public Room() {
        this(DEFAULT_W, DEFAULT_L, DEFAULT_H);
    }

    public Room(double w, double l, double h) {
        this.w = w;
        this.l = l;
        this.h = h;
    }
}
