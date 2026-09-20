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
 * and the two disagree for every color that is not pure black or pure gray. The OD-1 spike used
 * {@code Color.hsb(...)}; that was fine for proving a GPU renders flat faces, and would have been
 * a visible color error had it been carried into the app. {@code Tokens.parseHsl} carries the
 * same warning for the same reason.
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

    /** Hue in degrees, 0-359, carried through from the item unchanged. */
    public final int hue;

    /** Saturation in whole percent, carried through from the item unchanged. */
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
     * @param css the item's color, of the form {@code hsl(207,55%,42%)}
     * @return the five lightnesses, plus the hue and saturation they share
     */
    public static Shades of(String css) {
        int h = FALLBACK_HUE;
        int s = FALLBACK_SATURATION;
        int l = FALLBACK_LIGHTNESS;

        Matcher matched = HSL.matcher(css == null ? "" : css);
        if (matched.matches()) {
            h = Integer.parseInt(matched.group(1));
            s = Integer.parseInt(matched.group(2));
            l = Integer.parseInt(matched.group(3));
        }

        return new Shades(h, s,
                shade(l, TOP), shade(l, NORTH_SOUTH), shade(l, EAST_WEST),
                shade(l, BOTTOM), shade(l, EDGE));
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
    static int shade(int lightness, double multiplier) {
        long rounded = Math.round(lightness * multiplier);
        return (int) Math.max(MIN_LIGHTNESS, Math.min(MAX_LIGHTNESS, rounded));
    }
}
