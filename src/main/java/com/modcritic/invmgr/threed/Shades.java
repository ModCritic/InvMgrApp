package com.modcritic.invmgr.threed;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The five colors a box is drawn in: one per face group, plus the darker line around every
 * face's edge. This is the app's signature 3D look, and it is <b>not</b> lighting: there is no
 * light source, no surface normals and no shadows anywhere in the 3D view. Each face is a fixed
 * ratio off the item's own color, so a box looks the same from any direction and at any time of
 * day, which is what makes the view read as a diagram rather than a scene.
 *
 * <p><b>Lightness is HSL's, not HSB's, and the difference is not cosmetic.</b> The original's
 * {@code itemShades} (original line 2853) multiplies the <em>L</em> out of the item's stored
 * {@code hsl(H,S%,L%)} string. HSB "brightness" is a different quantity computed a different way,
 * and the two disagree for every color that is not pure black or pure gray. The early spike that
 * proved JavaFX 3D works used {@code Color.hsb(...)}; that was fine for proving a GPU renders flat
 * faces, and would have been a visible color error had it been carried into the app.
 * {@code Tokens.parseHsl} carries the same warning for the same reason.
 *
 * <p><b>Why this class holds integers rather than colors.</b> Two reasons, and both matter. The
 * original rounds the multiplied lightness to a whole percent <em>before</em> clamping it, so the
 * intermediate value is genuinely an integer and any test comparing against the original can
 * demand exact equality rather than picking a tolerance out of the air. And keeping this class
 * free of {@code javafx.scene.paint.Color} keeps it free of JavaFX entirely, so it runs in a
 * plain unit test with no window, no Xvfb and no rendering pipeline, the same reason
 * {@code ui.TiltPendulum} is pure arithmetic.
 *
 * @see com.modcritic.invmgr.threed.jfx.FaceStrip for the conversion to real colors
 */
public final class Shades {

    /** Lightness multiplier for the upward face. The lightest of the five. */
    public static final double TOP = 1.22;

    /** Lightness multiplier for the two faces along the room's north-south walls. */
    public static final double NORTH_SOUTH = 1.00;

    /** Lightness multiplier for the two faces along the room's east-west walls. */
    public static final double EAST_WEST = 0.80;

    /** Lightness multiplier for the downward face, which you only see from underneath. */
    public static final double BOTTOM = 0.55;

    /** Lightness multiplier for the line drawn around the edge of every face. Darkest. */
    public static final double EDGE = 0.45;

    /** Lightness floor, in whole percent. Below this a dark box loses its edge line entirely. */
    public static final int MIN_LIGHTNESS = 4;

    /** Lightness ceiling, in whole percent. Above this a light box's top face blows out to white. */
    public static final int MAX_LIGHTNESS = 92;

    /**
     * The original's own color pattern, ported character for character (original line 2854).
     *
     * <p><b>Deliberately stricter than {@code Tokens.parseHsl}</b>, which accepts decimals and
     * stray whitespace. Matching the original exactly is what lets the differential test assert
     * equality instead of approximate agreement: a looser parser would accept strings the
     * original rejects and then disagree with it about the result.
     */
    private static final Pattern HSL =
            Pattern.compile("^hsl\\((\\d+),\\s*(\\d+)%,\\s*(\\d+)%\\)$");

    /**
     * What the original falls back to when the color string does not match: hue 0, which is
     * <b>red</b>, at the app's standard saturation and lightness.
     *
     * <p>A conspicuous wrong answer rather than a quiet one, and that is the useful behavior:
     * every item the save format produces matches the pattern, so a red box in the 3D view means
     * something upstream wrote a color it should not have.
     */
    private static final int FALLBACK_HUE = 0;

    private static final int FALLBACK_SATURATION = 55;

    private static final int FALLBACK_LIGHTNESS = 42;

    /** The most saturated a color can be. CSS caps it here, and {@code Tokens.hsl} does too. */
    private static final int MAX_SATURATION = 100;

    /**
     * Hue in degrees, 0-359. The app only ever makes hues in that range, and those come through
     * unchanged; a larger one from a hand-edited file is wrapped around the color wheel the way CSS
     * wraps it, so the box is the color the original draws. Checked in two browsers on 2026-10-07:
     * both draw 400 as 40, and Chromium 153 draws 99999999999 exactly as this does, as 279; Firefox
     * 156 puts a hue that long a few degrees off.
     */
    public final int hue;

    /** Saturation in whole percent, capped at 100 as CSS caps it. Every item the app makes has 55. */
    public final int saturation;

    /** Lightness of the top face, in whole percent. */
    public final int top;

    /** Lightness of the north and south faces, in whole percent. */
    public final int northSouth;

    /** Lightness of the east and west faces, in whole percent. */
    public final int eastWest;

    /** Lightness of the bottom face, in whole percent. */
    public final int bottom;

    /** Lightness of the edge line drawn around every face, in whole percent. */
    public final int edge;

    private Shades(int hue, int saturation,
            int top, int northSouth, int eastWest, int bottom, int edge) {
        this.hue = hue;
        this.saturation = saturation;
        this.top = top;
        this.northSouth = northSouth;
        this.eastWest = eastWest;
        this.bottom = bottom;
        this.edge = edge;
    }

    /**
     * Works out an item's five face lightnesses from the color string it was saved with.
     *
     * <p><b>The pattern puts no limit on how many digits a number has</b>, and neither does the
     * save file's own check, so a hand-edited file can hold {@code hsl(99999999999,55%,42%)}. The
     * original reads that as a JavaScript number and the browser draws it. Reading it with
     * {@code Integer.parseInt} threw instead, which stopped the 3D view opening at all, and the
     * autosave kept the color, so it stayed that way on every launch. So no number here is read
     * into an {@code int} that could overflow: see {@link #hueOf} and {@link #shade}.
     *
     * @param css the item's color, of the form {@code hsl(207,55%,42%)}
     * @return the five lightnesses, plus the hue and saturation they share
     */
    public static Shades of(String css) {
        int h = FALLBACK_HUE;
        int s = FALLBACK_SATURATION;
        double l = FALLBACK_LIGHTNESS;

        Matcher matched = HSL.matcher(css == null ? "" : css);
        if (matched.matches()) {
            h = hueOf(matched.group(1));
            // A run of digits always parses as a double, at worst as infinity, which the cap
            // brings back to 100.
            s = (int) Math.min(Double.parseDouble(matched.group(2)), MAX_SATURATION);
            l = Double.parseDouble(matched.group(3));
        }

        return new Shades(h, s,
                shade(l, TOP), shade(l, NORTH_SOUTH), shade(l, EAST_WEST),
                shade(l, BOTTOM), shade(l, EDGE));
    }

    /**
     * The hue a run of digits stands for, wrapped into 0-359 the way CSS wraps an {@code hsl()}
     * hue.
     *
     * <p>Worked out one digit at a time, keeping only the remainder after dividing by 360. Only
     * the remainder decides the color, and a remainder below 360 times ten cannot overflow, however
     * many digits the file holds.
     */
    static int hueOf(String digits) {
        int hue = 0;
        for (int i = 0; i < digits.length(); i++) {
            hue = (hue * 10 + (digits.charAt(i) - '0')) % 360;
        }
        return hue;
    }

    /**
     * One face's lightness: multiply, round to a whole percent, then clamp.
     *
     * <p><b>The order is the original's, and it happens not to matter, which is worth writing
     * down so nobody spends time on it twice.</b> The obvious worry is that clamping first would
     * give a different answer at the boundaries. It cannot: the bounds are whole numbers and
     * rounding is monotonic, so {@code clamp(round(x))} and {@code round(clamp(x))} agree for
     * every input. This was checked rather than assumed, and there is deliberately no mutation
     * case for it, because a mutation nothing can detect is not a blind spot in the tests.
     *
     * <p><b>Rounding itself very much does matter.</b> Dropping it leaves fractional percentages
     * that the original never produces, so the differential no longer holds exactly and every
     * comparison would need a tolerance instead, which is precisely what this class exists to
     * avoid. That one is in the sweep.
     */
    static int shade(double lightness, double multiplier) {
        // A double, so a lightness with any number of digits works out as the original's does.
        // Math.round on a double stops at Long.MAX_VALUE instead of wrapping around, even for
        // infinity, and the clamp then gives 92: the same as JavaScript's
        // Math.min(92, Math.round(Infinity)).
        long rounded = Math.round(lightness * multiplier);
        return (int) Math.max(MIN_LIGHTNESS, Math.min(MAX_LIGHTNESS, rounded));
    }
}
