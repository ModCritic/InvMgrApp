package com.modcritic.invmgr.threed;

import java.util.function.DoubleUnaryOperator;

/**
 * The three shapes a movement can have over time.
 *
 * <p>Each one takes a number from 0 to 1 meaning "how far through the animation are we" and returns
 * a number from 0 to 1 meaning "how far along the movement should we be". Feeding the first
 * straight back out would be a movement at a dead constant speed, which reads as mechanical; these
 * bend it so the movement starts or stops gently instead.
 *
 * <p>Ported from the original's {@code easeInOutSine3d}, {@code easeInCubic3d} and
 * {@code easeOutCubic3d} (original lines 2793-2796), and they are the whole set; the original
 * defines no others.
 *
 * <p>No JavaFX, and no clock. These are pure arithmetic, so every one of them can be checked
 * without opening a window. The clock lives in {@link CameraTween}'s caller.
 */
public final class Easing {

    /**
     * Slow at both ends, quickest in the middle. The default for anything moving through space.
     *
     * <p>{@code -(cos(π·u) - 1) / 2}, the first half of a cosine wave, flipped and rescaled into
     * 0…1.
     */
    public static final DoubleUnaryOperator IN_OUT_SINE = Easing::inOutSine;

    /**
     * Starts almost stopped and finishes fastest. {@code u³}.
     *
     * <p>Used for pitch on the way down, and the reason is written out at
     * {@link Transitions#descent}.
     */
    public static final DoubleUnaryOperator IN_CUBIC = Easing::inCubic;

    /**
     * Starts fastest and settles. {@code 1 - (1-u)³}. The mirror of {@link #IN_CUBIC}, used for
     * pitch and yaw on the way back up.
     */
    public static final DoubleUnaryOperator OUT_CUBIC = Easing::outCubic;

    private Easing() {
    }

    /** @see #IN_OUT_SINE */
    public static double inOutSine(double u) {
        return -(Math.cos(Math.PI * u) - 1) / 2;
    }

    /** @see #IN_CUBIC */
    public static double inCubic(double u) {
        return u * u * u;
    }

    /** @see #OUT_CUBIC */
    public static double outCubic(double u) {
        double v = 1 - u;
        return 1 - v * v * v;
    }
}
