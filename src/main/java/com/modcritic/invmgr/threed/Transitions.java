package com.modcritic.invmgr.threed;

import com.modcritic.invmgr.model.Room;
import java.util.Map;

/**
 * The two journeys the camera makes: down into the room when the 3D view opens, and back up out of
 * it when you leave.
 *
 * <p>Everything here is a number or a curve. Nothing in this class knows what a frame is, what a
 * window is, or how long a second lasts; {@code ui/View3D} owns the clock and asks this for a
 * pose. That split is what lets the part of M5.2 most likely to be got wrong be checked without a
 * screen: {@code TransitionsTest} can ask "where is pitch a third of the way down, and where is
 * height at that same moment" and compare them, which is the one question a screenshot cannot ask.
 *
 * <p>Ported from {@code startDescent3d} and {@code exit3D} (original lines 2971-3022).
 */
public final class Transitions {

    /** How long the camera takes to come down into the room, in milliseconds. */
    public static final double DESCENT_MS = 2230;

    /** How long it takes to rise back out, in milliseconds. */
    public static final double ASCENT_MS = 1920;

    /**
     * How long the 2D bars take to slide out of the way, in milliseconds.
     *
     * <p>The original's {@code #top-wrap, #status-bar { transition: transform 0.35s ease; }}
     * (original line 427).
     */
    public static final double BAR_SLIDE_MS = 350;

    /**
     * How long the side panels take, in milliseconds, <b>and it is not the same as the bars.</b>
     *
     * <p>The item list and the layer-slider drawer carry their own
     * {@code transition: transform 0.22s ease} from their ordinary rules (original lines 149 and
     * 234), and the 3D rule only changes where they slide to, not how fast. So the sides leave
     * noticeably quicker than the top and bottom. Easy to miss, and easy to "tidy" into one
     * number.
     */
    public static final double PANEL_SLIDE_MS = 220;

    /**
     * How far off-screen everything slides, as a fraction of its own size.
     *
     * <p>105%, not 100%: the extra 5% carries the border and any shadow clear of the edge as well,
     * so nothing is left as a bright line along the side of the screen.
     */
    public static final double SLIDE_FRACTION = 1.05;

    /**
     * How long after the slide starts before the bars stop taking up space, in milliseconds.
     *
     * <p>370, i.e. twenty past the end of the 350 ms slide. They cannot leave the layout any
     * earlier, because the moment they do the room resizes to fill the gap, and doing that while
     * they are still visibly sliding would show the room jumping outward behind them.
     */
    public static final double BARS_LEAVE_LAYOUT_MS = 370;

    /** How long the 3D picture takes to fade up once it is built, in milliseconds. */
    public static final double OVERLAY_FADE_MS = 300;

    /**
     * The pause between the 3D picture starting to fade in and the descent starting, in
     * milliseconds.
     *
     * <p>320, from the original's {@code setTimeout(startDescent3d, 320)}. Just longer than the
     * 300 ms fade, so you see the room from overhead, still and fully drawn, for a moment
     * before it starts to move.
     */
    public static final double DESCENT_DELAY_MS = 320;

    /** How long the 2D-return button takes to appear once the descent lands, in milliseconds. */
    public static final double RETURN_BUTTON_FADE_MS = 400;

    /**
     * The fraction of the ascent that finishes before the picture starts fading out.
     *
     * <p>0.7, so the fade happens over the last 30%, once the camera is most of the way back to
     * overhead and the view already resembles the flat room you are returning to.
     */
    public static final double ASCENT_FADE_FROM = 0.7;

    private Transitions() {
    }

    /**
     * The way down: from directly overhead to standing in the room at eye height.
     *
     * <p>Only two things move. Height comes down from wherever overhead is to 6 ft, and pitch
     * swings from straight down (−π/2) to level. Where you are standing is already decided before
     * this starts; the camera is placed at the middle of the room, so x, z and yaw do not move
     * at all.
     *
     * <p><b>Pitch gets its own curve, and this is the single most important line in M5.2.</b> It
     * eases <em>in</em>: it stays looking almost straight down through the early part and only
     * rotates to level near the end, while height uses the default symmetric curve. The original's
     * own comment explains why they cannot share one (original lines 2977-2983): an equal fraction
     * of pitch's total swing is far more noticeable early on than the same fraction of a very tall
     * drop, so matching them mathematically still <em>looked</em> like the camera leveled off
     * before it had finished descending. Unify these curves and the descent goes back to looking
     * front-loaded. A test compares the two channels at the same instant for exactly this reason.
     */
    public static CameraTween descent(CameraPose overhead) {
        CameraPose landed = overhead.copy();
        landed.y = CameraPose.EYE_HEIGHT_FT;
        landed.pitch = 0;
        return new CameraTween(overhead, landed, Map.of(CameraTween.Channel.PITCH, Easing.IN_CUBIC));
    }

    /**
     * The way back up: from wherever you are standing to directly over the middle of the room.
     *
     * <p>The mirror of the descent. Position uses the default curve; <b>pitch and yaw ease
     * out</b>: they snap back toward overhead early and settle over the rest of the climb, which
     * is the same argument as the descent's, reversed.
     *
     * <p><b>The starting yaw is normalized first</b>, and dropping that line is a real bug rather
     * than a nicety. Yaw accumulates as you turn, so after three turns to the right it might read
     * 7.5 radians while the destination is 0. Interpolating straight from 7.5 to 0 spins the
     * camera back through more than a full turn on the way out. Wrapping it into −π…π first makes
     * it take the short way round: from 170° the camera turns 10° west, not 170° east.
     *
     * @param standing where the camera is now
     * @param room     the room, for the middle of the floor
     * @param overheadY how high overhead is, from
     *                 {@link Perspective#overheadHeightFt(Room, double, double)}
     */
    public static CameraTween ascent(CameraPose standing, Room room, double overheadY) {
        CameraPose start = standing.copy();
        start.yaw = CameraPose.normYaw(start.yaw);

        CameraPose over = new CameraPose(room.w / 2, overheadY, room.l / 2, 0, -Math.PI / 2);

        return new CameraTween(start, over, Map.of(
                CameraTween.Channel.PITCH, Easing.OUT_CUBIC,
                CameraTween.Channel.YAW, Easing.OUT_CUBIC));
    }

    /**
     * How opaque the 3D picture should be at this point in the ascent, 1 being solid.
     *
     * <p>Solid until {@link #ASCENT_FADE_FROM}, then straight down to nothing by the end. The
     * original does this by hand rather than with a fade of its own, because the fade has to be
     * tied to the camera's progress rather than to a clock of its own that could drift out of step
     * with it.
     */
    public static double overlayOpacity(double ascentProgress) {
        double p = Math.max(0, Math.min(1, ascentProgress));
        if (p <= ASCENT_FADE_FROM) {
            return 1;
        }
        return Math.max(0, 1 - (p - ASCENT_FADE_FROM) / (1 - ASCENT_FADE_FROM));
    }
}
