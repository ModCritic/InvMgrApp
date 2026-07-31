package com.modcritic.invmgr.model;

/**
 * Every measurement conversion in InvMgr, in one place.
 *
 * <p><b>The rule this file protects:</b> item sizes are stored in <b>inches</b>, the room
 * is stored in <b>feet</b>, and <b>nothing is ever stored in metric</b>. Centimetres and
 * metres exist only as a display conversion at the moment a number is shown to the user
 * or typed in by them. Toggling the Units button re-labels the screen without changing a
 * single stored value.
 *
 * <p>Ported from SPEC-2D-ENGINE.md §2, which pins these formulas to specific lines of the
 * original HTML app. The numbers are not approximations to be tidied up: 2.54 cm per inch
 * and 0.3048 m per foot are the exact international definitions.
 */
public final class Units {

    /** Screen pixels per inch, at 100% zoom. */
    public static final double PX_PER_INCH = 8.0;

    /** Screen pixels per foot, at 100% zoom. Exactly 12 inches' worth. */
    public static final double PX_PER_FOOT = 96.0;

    /** Centimetres in an inch — exact by international definition. */
    public static final double CM_PER_IN = 2.54;

    /** Metres in a foot — exact by international definition. */
    public static final double M_PER_FT = 0.3048;

    /** Pixels per metre. Used for the metric grid; works out to ~314.96. */
    public static final double PX_PER_METER = PX_PER_FOOT / M_PER_FT;

    /** Smallest storable item dimension, inches. */
    public static final double MIN_DIMENSION_IN = 1.0;

    /** Largest storable item dimension, inches. */
    public static final double MAX_DIMENSION_IN = 1000.0;

    private Units() {
    }

    public static double inchesToPx(double inches) {
        return inches * PX_PER_INCH;
    }

    public static double feetToPx(double feet) {
        return feet * PX_PER_FOOT;
    }

    public static double inToCm(double inches) {
        return inches * CM_PER_IN;
    }

    public static double cmToIn(double cm) {
        return cm / CM_PER_IN;
    }

    public static double ftToM(double feet) {
        return feet * M_PER_FT;
    }

    public static double mToFt(double metres) {
        return metres / M_PER_FT;
    }

    /** Rounds to 3 decimal places — the precision the input fields accept. */
    public static double round3(double n) {
        return Math.round(n * 1_000.0) / 1_000.0;
    }

    /** Rounds to 6 decimal places — the precision dimensions are stored at. */
    public static double round6(double n) {
        return Math.round(n * 1_000_000.0) / 1_000_000.0;
    }

    /**
     * Turns a dimension the user typed <b>in centimetres</b> into the inches value that
     * gets stored, following SPEC-2D-ENGINE.md §2's four steps in order:
     *
     * <ol>
     *   <li>round to 3 decimals <em>while still in centimetres</em>
     *   <li>convert to inches
     *   <li>round to 6 decimals
     *   <li>clamp to the legal dimension range
     * </ol>
     *
     * <p><b>Do not collapse this into a single rounding step.</b> It looks like four
     * fiddly operations that could be one, and it is not: rounding in the unit the user
     * typed is what makes the value convert back to the same centimetre reading it
     * started as. Round only at the end and stored values drift every time the user
     * edits an item — a box entered as 12.346 cm redisplays as something slightly else,
     * and the difference compounds. {@code UnitsTest} pins a concrete case where the two
     * orders disagree.
     *
     * <p>Storing 6 decimals of an inch is finer than any centimetre or millimetre the UI
     * can display, which is what lets it absorb conversion noise invisibly.
     */
    public static double cmDimensionInputToInches(double enteredCm) {
        double cm = round3(enteredCm);
        double inches = round6(cmToIn(cm));
        return clampDimension(inches);
    }

    /**
     * Turns a dimension the user typed into the inches value that gets stored, whichever unit
     * they happened to be looking at.
     *
     * <p>The single entry point for every dimension field in the app — Add Item, Edit Item and
     * the preset dialog all come through here. Having one is what stops the four-step metric
     * rule above from being written out again, slightly differently, in each of them; that is
     * exactly how the drift it exists to prevent would creep back in.
     *
     * <p>In imperial nothing is converted, because inches are already what gets stored — the
     * value is rounded to 3 decimals, matching what the fields accept, and clamped.
     */
    public static double dimensionInputToInches(double entered, boolean metric) {
        return metric ? cmDimensionInputToInches(entered) : clampDimension(round3(entered));
    }

    /** Holds an item dimension inside the legal range, in inches. */
    public static double clampDimension(double inches) {
        return Math.min(MAX_DIMENSION_IN, Math.max(MIN_DIMENSION_IN, inches));
    }
}
