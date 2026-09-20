package com.modcritic.invmgr.threed;

import com.modcritic.invmgr.model.Room;

/**
 * What the fingers that are <em>not</em> on the joystick mean while you are standing in the 3D room.
 *
 * <p>Port of the original's look and pan recognizer (original lines 3152-3210), plus CLAUDE.md §5.5
 * <b>D-15</b>, the pinch, which the original does not have.
 *
 * <ul>
 *   <li><b>One finger</b> looks around: swipe direction is look direction.
 *   <li><b>Two fingers</b> either slide the camera about, tracked by the point midway between them,
 *       <b>or</b> spread and squeeze to move you forward and back, one or the other, decided by
 *       whichever the hand does first and held until a finger lifts. See below.
 *   <li><b>A brief, near-still single finger</b> is a tap, and names the box under it instead.
 * </ul>
 *
 * <p>No JavaFX and no clock, the same as {@link Walk}, {@link Joystick} and {@link WalkInput}: every
 * position arrives as a number and every time arrives as a parameter, so all four meanings and the
 * awkward moments between them can be checked without a screen and without a second hand. It is the
 * 3D counterpart of {@code ui.TouchGesture}, which does the same job for a box in the flat room.
 *
 * <h2>This is where pixels become feet</h2>
 *
 * <p>{@link Walk#pan} and {@link Walk#dolly} take <b>feet</b>, and {@link Walk#look} takes radians
 * per pixel, on purpose: how far a camera travels is a fact about a room, and how far a finger has to
 * drag to move it that far is a fact about a hand on glass. The four constants that join the two live
 * here, in the one place that calls those functions with a finger's movement in it.
 *
 * <h2>Why the pinch is half as sensitive as the pan, exactly</h2>
 *
 * <p>{@link #PINCH_SENS_FT_PER_PX} is half {@link #PAN_SENS_FT_PER_PX} and that is arithmetic, not
 * taste. Sliding two fingers together moves their midpoint by however far one finger traveled;
 * spreading them changes the distance between them by <b>twice</b> that, because both fingers
 * contribute. The same number in both places would therefore make a pinch feel twice as strong as a
 * pan for identical movement of the hand.
 *
 * <h2>Why two fingers do one job at a time</h2>
 *
 * <p>They did both at once until 2026-08-11, which is what D-15 originally asked for, and on a real
 * hand it was wrong. <b>A pinch drags its own midpoint.</b> Pinch one-handed (thumb resting, index
 * finger doing the moving) and the midpoint travels exactly <em>half</em> the change in separation,
 * because only one of the two contributed to it. Multiply that half by the pan's 0.03 ft/px and the
 * whole separation by the pinch's 0.015, and the two come out <b>exactly equal</b>: every one-handed
 * pinch moved the camera at forty-five degrees, forward and sideways in equal measure, and which way
 * sideways depended on which finger the hand happened to anchor. The very halving that makes a pinch
 * and a pan feel matched for the same effort is what makes the artifact match the intent so precisely.
 *
 * <p>Reported by the user 2026-08-08 against {@code InvMgr-M6.3.apk}: <i>"it changes the angle of
 * movement seemingly with where the midpoint of the two fingers goes"</i>, and the fix is theirs
 * too: two fingers are a slide or a pinch, never both. Whichever the hand does first by
 * {@link #TWO_FINGER_DECIDE_PX} claims the pair, and the other does nothing until a finger lifts. It
 * is the same shape as the item list's direction lock and as {@code ui.TouchGesture}'s rule that
 * crossing the drag threshold is permanent. <b>It amends D-15</b>, which promised the two would
 * compose; see CLAUDE.md §5.5.
 *
 * <p>Nothing is thrown away by waiting. The movement up to the decision is applied in full the moment
 * it is made (see {@link #moved}), so the threshold delays the commitment without costing the hand
 * the pixels it spent making it.
 */
public final class WalkGesture {

    /**
     * How far one finger turns the camera, in radians per pixel. The original's
     * {@code TOUCH_ROT_SENS_3D} (original line 2786).
     *
     * <p>Nearly three times the mouse's {@link Walk#MOUSE_SENS_RAD_PER_PX} and about twice its drag
     * fallback, for the same reason the fallback is more sensitive than a captured pointer: a finger
     * runs out of screen. A phone is 360 pixels wide, so at the mouse's sensitivity a full sweep
     * across the glass would turn you less than a sixth of the way round.
     */
    public static final double LOOK_SENS_RAD_PER_PX = 0.006;

    /**
     * How far two fingers slide the camera, in feet per pixel. The original's
     * {@code TOUCH_PAN_SENS_3D} (original line 2787).
     */
    public static final double PAN_SENS_FT_PER_PX = 0.03;

    /**
     * How far spreading two fingers moves the camera forward, in feet per pixel of extra separation.
     * CLAUDE.md §5.5 <b>D-15</b>; deliberately half {@link #PAN_SENS_FT_PER_PX}; see the note on
     * this class.
     */
    public static final double PINCH_SENS_FT_PER_PX = 0.015;

    /**
     * How far two fingers have to do something before it counts as having been meant, in pixels.
     *
     * <p>Measured from where the pair landed, so it is crossed once per gesture and never uncrossed,
     * and compared two ways at the same time: how far the midpoint has slid, and how much the
     * separation has changed. First past this number decides what the pair is for.
     *
     * <p><b>The value does not decide the outcome, only the moment.</b> A one-handed pinch changes
     * its separation exactly twice as fast as it slides its midpoint, so it crosses any threshold on
     * the pinch first whatever the threshold is; a slide barely changes its separation at all, so it
     * crosses on the slide. This number is therefore how long to wait before committing, not a
     * balance point between two gestures: twelve pixels is about three millimeters of glass on the
     * user's phone, enough that two resting fingers cannot decide by trembling and little enough that
     * nobody notices the wait.
     */
    public static final double TWO_FINGER_DECIDE_PX = 12;

    /**
     * How far a finger may wander and still be naming a box rather than looking around, in pixels.
     * The original's {@code TAP_MOVE_TOLERANCE_3D}.
     *
     * <p>Larger than the flat room's six, and it should be: there is no box to hold on to here, and
     * the thing being asked for is a name rather than a movement, so a wobble costs nothing.
     */
    public static final double TAP_MOVE_TOLERANCE_PX = 10;

    /**
     * Down longer than this and it was a look, however still it was. The original's
     * {@code TAP_MAX_MS_3D}.
     */
    public static final double TAP_MAX_MS = 500;

    /** How many fingers are on the glass, and therefore what kind of gesture this is. */
    public enum Mode {
        /** Nothing is down, or nothing that this recognizer owns. */
        NONE,
        /** One finger: turning your head. */
        LOOK,
        /** Two or more. What they are <em>for</em> is a separate question; see {@link Job}. */
        TWO_FINGERS
    }

    /**
     * What two fingers turned out to be for. Answered once per two-finger gesture and then held.
     *
     * <p>Separate from {@link Mode} because they are separate questions: how many fingers are down
     * is known the instant they land, and what they mean is not known until the hand has moved.
     */
    public enum Job {
        /** Two fingers are down but neither reading has passed {@link #TWO_FINGER_DECIDE_PX} yet. */
        UNDECIDED,
        /** Sliding the pair moves the camera sideways and up. The original's own gesture. */
        SLIDE,
        /** Spreading and squeezing moves the camera forward and back. D-15's addition. */
        PINCH
    }

    /** Where a tap landed, in whatever pixels the caller measured the fingers in. */
    public record Tap(double x, double y) {
    }

    private Mode mode = Mode.NONE;

    private Job job = Job.UNDECIDED;

    /**
     * Where the fingers were last time: the finger itself while looking, the midpoint while there
     * are two of them.
     *
     * <p><b>While the job is undecided these deliberately stay where the pair landed</b> rather than
     * following each frame, which is what lets the movement spent on deciding be applied in full
     * once the decision is made.
     */
    private double lastX;
    private double lastY;

    /** How far apart the two fingers were then. Only meaningful with two of them down. */
    private double lastSeparation;

    private boolean tapPending;
    private double tapX;
    private double tapY;
    private double tapStartMs;

    /**
     * A finger went down, or one that was already down was joined by another. Called with
     * <b>every</b> finger this recognizer owns, not just the new one.
     *
     * <p>The count is what decides the meaning, so this is also how looking becomes panning: a
     * second finger arriving is a fresh call with two positions in it.
     *
     * <p><b>A second finger cancels the pending tap outright</b>, before it can be too slow or move
     * too far. Two fingers is never a tap, and waiting to find that out would leave a pinch that
     * happened to be quick and small also naming a box.
     *
     * <p>Nothing at all in {@code xy} means nothing this recognizer owns is down (every finger on
     * the glass is the joystick's), and the current gesture is left exactly as it was rather than
     * being cleared. The original does the same ({@code if (!rest.length) return}), and it matters:
     * a thumb pressing the stick must not end a look that another finger is in the middle of.
     *
     * @param xy    the fingers, as x, y pairs. Two or more fingers use the first two.
     * @param nowMs the time, for deciding later whether this was quick enough to be a tap
     */
    public void began(double[] xy, double nowMs) {
        int fingers = xy.length / 2;
        if (fingers == 0) {
            return;
        }
        if (fingers == 1) {
            mode = Mode.LOOK;
            lastX = xy[0];
            lastY = xy[1];
            tapPending = true;
            tapX = xy[0];
            tapY = xy[1];
            tapStartMs = nowMs;
            return;
        }
        mode = Mode.TWO_FINGERS;
        followTwo(xy);
        tapPending = false;
    }

    /**
     * The fingers moved. Applies whatever that means straight to the camera.
     *
     * <p><b>The count has to still match the mode</b>, exactly as the original guards it. A movement
     * reported with a different number of fingers from the one the gesture was started with is
     * ignored rather than guessed at: the press or the release that changed the count will have
     * re-decided the meaning already, and acting on a mismatch here would use a midpoint belonging
     * to a different pair of fingers.
     *
     * <p><b>Two fingers do one job, and this is where it is chosen.</b> Until the pair has either
     * slid or separated by {@link #TWO_FINGER_DECIDE_PX} nothing happens at all; the first one past
     * it wins and the other is off for the rest of the gesture. The reason is on this class: a
     * one-handed pinch drags its own midpoint by exactly half its separation change, which at these
     * two sensitivities is exactly as much sideways movement as forward, so composing the two (as
     * D-15 first asked for, and as this did until 2026-08-11) sent every one-handed pinch off at
     * forty-five degrees.
     *
     * <p><b>The wait costs nothing.</b> The deciding call applies the movement measured from where
     * the fingers landed, not from the previous frame, so a fast pinch that clears the threshold in
     * one go moves the camera by the whole of it.
     *
     * @param xy   the fingers, as x, y pairs
     * @param cam  the live camera, moved in place
     * @param room what the camera is confined to
     */
    public void moved(double[] xy, CameraPose cam, Room room) {
        int fingers = xy.length / 2;
        if (mode == Mode.LOOK && fingers == 1) {
            double dx = xy[0] - lastX;
            double dy = xy[1] - lastY;
            lastX = xy[0];
            lastY = xy[1];
            Walk.look(cam, dx, dy, LOOK_SENS_RAD_PER_PX);

            // Far enough from where it went down and this was never a tap. Measured from the START,
            // not from the last position: a finger that creeps twenty pixels a pixel at a time has
            // still traveled twenty pixels, and comparing consecutive positions would never notice.
            if (tapPending && Math.hypot(xy[0] - tapX, xy[1] - tapY) > TAP_MOVE_TOLERANCE_PX) {
                tapPending = false;
            }
            return;
        }
        if (mode == Mode.TWO_FINGERS && fingers >= 2) {
            double midX = (xy[0] + xy[2]) / 2;
            double midY = (xy[1] + xy[3]) / 2;
            double separation = Math.hypot(xy[2] - xy[0], xy[3] - xy[1]);

            double dx = midX - lastX;
            double dy = midY - lastY;
            double spread = separation - lastSeparation;

            if (job == Job.UNDECIDED) {
                double slid = Math.hypot(dx, dy);
                double separated = Math.abs(spread);
                if (Math.max(slid, separated) < TWO_FINGER_DECIDE_PX) {
                    // Still deciding. lastX, lastY and lastSeparation are left alone on purpose, so
                    // the next call measures from where the fingers landed and whichever job wins
                    // gets everything the hand has done since, rather than only what it did in the
                    // frame that happened to tip the balance.
                    return;
                }
                // A tie goes to the slide, because the slide is what the original does and the
                // pinch is what was added. When the hand is ambiguous, be the original.
                job = slid >= separated ? Job.SLIDE : Job.PINCH;
            }

            // Both are updated whatever the job is: these mean "where the fingers were last time",
            // not "where the gesture last acted", and after a lift the other one is read again.
            lastX = midX;
            lastY = midY;
            lastSeparation = separation;

            if (job == Job.SLIDE) {
                // Dragging DOWN the screen lowers the camera, so the vertical is inverted: the
                // original's `cam3d.y += -dy * TOUCH_PAN_SENS_3D`, and the same convention as the
                // room moving with your fingers rather than against them.
                Walk.pan(cam, dx * PAN_SENS_FT_PER_PX, -dy * PAN_SENS_FT_PER_PX, room);
            } else {
                Walk.dolly(cam, spread * PINCH_SENS_FT_PER_PX, room);
            }
        }
    }

    /**
     * A finger lifted. Says whether that finished a tap, and works out what the fingers still down
     * now mean.
     *
     * <p>The tap is reported from <b>where the finger went down</b> rather than where it came up.
     * They are within ten pixels of each other by definition, and the place it was put is the place
     * it was aimed.
     *
     * <p><b>Two or more fingers left is re-seeded rather than left alone, and that is a bug fix.</b>
     * The original re-establishes only the one-finger case ({@code else if (rest.length === 1)}), so
     * lifting one of three fingers leaves it panning from the midpoint of a pair that no longer
     * exists, and the next movement subtracts that stale midpoint from the new one and jumps the
     * camera by the whole distance between them, which on a phone is several feet. Nothing is
     * intentional about that, so it is not a §5.5 divergence; it is the same kind of inherited fault
     * as the keys a window never hears released.
     *
     * @param xy the fingers <b>still down</b>, the lifted one already removed
     * @return where a tap landed, or null if this lift was not one
     */
    public Tap ended(double[] xy, double nowMs) {
        Tap tap = null;
        if (tapPending && nowMs - tapStartMs < TAP_MAX_MS) {
            tap = new Tap(tapX, tapY);
        }
        tapPending = false;

        int fingers = xy.length / 2;
        if (fingers == 0) {
            mode = Mode.NONE;
        } else if (fingers == 1) {
            mode = Mode.LOOK;
            lastX = xy[0];
            lastY = xy[1];
        } else {
            mode = Mode.TWO_FINGERS;
            followTwo(xy);
        }
        return tap;
    }

    /**
     * The gesture was taken away: the window lost focus, or the 3D view is being torn down.
     *
     * <p>Clears the pending tap as well as the gesture. A canceled touch is not a tap, so it must
     * not be able to name a box on the way out.
     */
    public void canceled() {
        mode = Mode.NONE;
        job = Job.UNDECIDED;
        tapPending = false;
        lastSeparation = 0;
    }

    /** What the fingers are doing. Exposed so a test can ask without watching the camera. */
    public Mode mode() {
        return mode;
    }

    /** What two fingers turned out to be for, once they have said. */
    public Job job() {
        return job;
    }

    /** Whether a lift right now could still be a tap. */
    public boolean isTapPending() {
        return tapPending;
    }

    /**
     * Starts following the midpoint of the first two fingers, and how far apart they are.
     *
     * <p>The job goes back to undecided every time this runs, which is every time the set of fingers
     * changes. A pair that has just gained or lost a member is a new pair and has not said what it
     * is for yet, and re-asking is also what stops a slide latched by three fingers carrying on as
     * a slide when two of them are left doing something else entirely.
     */
    private void followTwo(double[] xy) {
        lastX = (xy[0] + xy[2]) / 2;
        lastY = (xy[1] + xy[3]) / 2;
        lastSeparation = Math.hypot(xy[2] - xy[0], xy[3] - xy[1]);
        job = Job.UNDECIDED;
    }
}
