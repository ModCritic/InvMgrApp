package com.modcritic.invmgr.ui;

/**
 * What a finger on a box in the room turned out to mean.
 *
 * <p>One finger can mean four different things here, and which one it was is not known until it
 * lifts, or for a delete, until enough time passes while it is still down:
 *
 * <ul>
 *   <li><b>Drag</b>: it moved more than six pixels, so the box moves with it.
 *   <li><b>Tap</b>: down and up quickly without moving: show the box's details and select it.
 *   <li><b>Double tap</b>: a second tap soon after the first: open the edit dialog.
 *   <li><b>Hold</b>: held still for a second and a half: delete the box.
 * </ul>
 *
 * <p>This class is that decision and nothing else. It holds no JavaFX and reads no clock (every
 * time arrives as a number from the caller), so all four outcomes and the awkward gaps between
 * them can be tested without a screen and without waiting a second and a half. It is the same
 * arrangement as the 3D view's {@code Walk}: the arithmetic is a plain object, and the JavaFX half
 * is a caller.
 *
 * <h2>Why hold-to-delete is a touch gesture and only a touch gesture</h2>
 *
 * <p>On the desktop a box is deleted by right-clicking it. That is a deliberate split (§5.5
 * <b>D-1</b>): <b>one deletion gesture per kind of input</b>, hold for fingers and right-click for
 * a mouse. Nothing should ever add a hold to the desktop.
 *
 * <h2>The gaps, which are the interesting part</h2>
 *
 * <p>The thresholds do not tile the space, and that is the original's design rather than an
 * oversight. A finger held between 600 ms and 1500 ms and then lifted does <b>nothing at all</b>;
 * it is too slow to have been a tap and too quick to have been a delete. That dead band is what
 * makes an accidental long press safe: the way to cancel a delete you did not mean to start is to
 * lift before the ring finishes, and it would be no use if lifting instead opened a dialog.
 */
public final class TouchGesture {

    /** How far a finger may wander and still count as a tap rather than a drag. */
    public static final double DRAG_THRESHOLD_PX = Tokens.DRAG_THRESHOLD_TOUCH_PX;

    /** Held still this long and the box is deleted, with the warning ring running throughout. */
    public static final double HOLD_TO_DELETE_MS = 1500;

    /** Down longer than this and it is not a tap any more, however little it moved. */
    public static final double TAP_MAX_MS = 600;

    /** A second tap starting within this of the first counts as a double tap. */
    public static final double DOUBLE_TAP_MS = 400;

    /** What the finger turned out to mean. */
    public enum Outcome {
        /** It moved: the box has been dragged and the move should be committed. */
        DRAG,
        /** A quick touch: show the details and select. */
        TAP,
        /** A second quick touch soon after the first: open the edit dialog. */
        DOUBLE_TAP,
        /** Deliberately nothing: the dead band, or a gesture already spent on a delete. */
        NOTHING
    }

    /** Where the finger went down, in the same pixels the item's position is in. */
    private double startX;
    private double startY;
    private double startMs;

    /** True once the finger has traveled far enough that this can only be a drag. */
    private boolean moved;

    /** True once the hold has fired, so the lift afterwards does nothing more. */
    private boolean deleted;

    /** True between a touch going down and it lifting. */
    private boolean down;

    /**
     * When the last clean single tap happened, or 0 for none pending.
     *
     * <p>This is the one piece of state that outlives a gesture, because a double tap is by
     * definition a relationship between two of them.
     */
    private double lastTapMs;

    /** A finger went down on the box. Any pending double tap survives; everything else resets. */
    public void began(double x, double y, double nowMs) {
        startX = x;
        startY = y;
        startMs = nowMs;
        moved = false;
        deleted = false;
        down = true;
    }

    /**
     * The finger moved. Returns true once this is a drag, and keeps returning true afterwards.
     *
     * <p>Crossing the threshold is <b>permanent for the gesture</b>: moving eight pixels out and
     * seven back is still a drag, not a tap. Anything else would let a box be dragged somewhere,
     * returned near its origin, and then have the lift open a dialog.
     */
    public boolean moved(double x, double y) {
        if (!down) {
            return false;
        }
        if (Math.abs(x - startX) > DRAG_THRESHOLD_PX || Math.abs(y - startY) > DRAG_THRESHOLD_PX) {
            moved = true;
        }
        return moved;
    }

    /**
     * Has the finger now been held still long enough to delete?
     *
     * <p>Asked repeatedly by a per-frame timer rather than answered by one. It returns true
     * <b>once</b> per gesture and false forever after, so the caller cannot delete twice by asking
     * twice, which a timer polling every frame absolutely would.
     */
    public boolean holdFired(double nowMs) {
        if (!down || moved || deleted || nowMs - startMs < HOLD_TO_DELETE_MS) {
            return false;
        }
        deleted = true;
        // A delete ends any pending double tap. Without this, deleting one box and then tapping
        // another within 400 ms would open the second box's edit dialog.
        lastTapMs = 0;
        return true;
    }

    /** Is the warning ring supposed to be running: finger down, still, and not yet fired? */
    public boolean isHolding() {
        return down && !moved && !deleted;
    }

    /**
     * How far through the hold the finger has got, from 0 to 1. The warning ring's fade rides on
     * this.
     *
     * <p>Zero whenever the ring has no business being on screen at all (nothing down, the finger
     * moved, or the delete has already happened), so the caller never has to ask two questions.
     *
     * <p>It lives here, next to {@link #holdFired}, so the ring and the delete read the <b>same
     * clock</b>. Handing the fade to a JavaFX animation instead would have been two clocks started
     * at almost the same moment, and the ring finishing a little before or after the box vanished
     * is exactly the sort of thing that is invisible until it is on a phone. Being here also makes
     * the ramp testable without a screen, like everything else in this class.
     */
    public double holdProgress(double nowMs) {
        if (!isHolding()) {
            return 0;
        }
        return Math.min(1, (nowMs - startMs) / HOLD_TO_DELETE_MS);
    }

    /**
     * The finger lifted. Returns what it meant.
     *
     * <p>The order of the checks is the behavior. A gesture that already deleted is spent; a
     * gesture that moved is a drag whatever its timing; only a gesture that did neither can be a
     * tap, and only if it was brief.
     */
    public Outcome ended(double nowMs) {
        boolean wasDown = down;
        down = false;

        if (!wasDown || deleted) {
            return Outcome.NOTHING;
        }
        if (moved) {
            // Movement clears any pending double tap: a drag and then a quick tap elsewhere must
            // not be read as two taps.
            lastTapMs = 0;
            return Outcome.DRAG;
        }
        if (nowMs - startMs >= TAP_MAX_MS) {
            // The dead band: held too long to be a tap, released too soon to delete. Deliberately
            // nothing, and it clears the double tap so a slow press cannot become half of one.
            lastTapMs = 0;
            return Outcome.NOTHING;
        }
        if (lastTapMs > 0 && nowMs - lastTapMs < DOUBLE_TAP_MS) {
            lastTapMs = 0;
            return Outcome.DOUBLE_TAP;
        }
        lastTapMs = nowMs;
        return Outcome.TAP;
    }

    /**
     * The gesture was taken away: a second finger arrived, or the window lost the touch.
     *
     * <p>Clears the pending double tap as well as the current gesture. A canceled touch is not a
     * tap, so it must not be able to become the first half of one.
     */
    public void canceled() {
        down = false;
        moved = false;
        deleted = false;
        lastTapMs = 0;
    }
}
