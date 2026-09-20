package com.modcritic.invmgr.threed;

/**
 * Where the thumb is on the virtual joystick, and what that means as a direction to walk.
 *
 * <p>Port of the original's {@code joy3d} object and {@code wireJoystick3d}'s {@code updateFromTouch}
 * (original lines 2905-2941), plus the deadzone {@code loop3d} applies to it (original line 3052).
 *
 * <p>No JavaFX, no clock: the same arrangement as {@link Walk} and {@link WalkInput}. The
 * circles on screen are {@code ui.JoystickView}, and the fingers arrive from
 * {@code ui.ThreeDControls}. What is left here is the arithmetic: clamp the thumb's displacement to
 * a radius, turn it into a number between −1 and 1, and decide when it is close enough to the middle
 * to mean "not moving".
 *
 * <h2>Why it remembers a touch identifier</h2>
 *
 * <p>A thumbstick has to keep working once your thumb slides off the picture of it. That is how
 * every game pad on a phone behaves, and in the original it is a <b>deliberate fix</b> rather than
 * an accident: the joystick tracks its finger by identifier and not by hit-testing, so the drag
 * survives leaving the base's bounds. The same identifier is what excludes this finger from the
 * look and pan recognizer next door ({@link WalkGesture}), so a thumb on the stick and a finger
 * dragging elsewhere work at the same time.
 *
 * <p><b>The identifier is stored, never interpreted.</b> This class never asks what it means or
 * where it came from; it only ever compares it for equality, which is why holding it here costs this
 * class none of its independence from JavaFX.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>There is no up or down on the joystick, and no boost. The original reads vertical movement from
 * the keyboard only ({@code vert = keys3d.up - keys3d.dn}, original line 3053) and gives touch no
 * equivalent of Shift, so on a phone the stick walks and the two-finger pan is what climbs. That is
 * not an omission to fill in later; a thumbstick with four meanings on it is a thumbstick you
 * cannot use without looking at your hand.
 */
public final class Joystick {

    /**
     * How far the knob may travel from the middle of the base, in pixels. The original's
     * {@code JOY_RADIUS_3D} (original line 2789).
     *
     * <p>Thirty against a base of {@link #BASE_DIAMETER_PX}, so full deflection is a little over
     * halfway out; the knob never reaches the rim, and the ring of base still showing around it is
     * what tells a thumb it has room left.
     */
    public static final double RADIUS_PX = 30;

    /**
     * How far out of the middle the thumb has to be before the camera moves at all, as a fraction of
     * full deflection. The original's {@code JOY_DEADZONE_3D} (original line 2790).
     *
     * <p>A thumb resting on a stick is never exactly in the middle, and without this the camera
     * creeps whenever a hand is merely present. Twelve percent of 30 px is 3.6 px, which is under a
     * millimeter on the user's phone.
     */
    public static final double DEADZONE = 0.12;

    /** The circle you put your thumb on, in pixels. {@code SPEC-3D-VIEW.md} §5. */
    public static final double BASE_DIAMETER_PX = 108;

    /** The circle that follows your thumb, in pixels. {@code SPEC-3D-VIEW.md} §5. */
    public static final double KNOB_DIAMETER_PX = 50;

    private boolean active;
    private int touchId;

    /** Where the knob is relative to the middle of the base, in pixels, already clamped. */
    private double knobX;
    private double knobY;

    /**
     * A finger landed on the base. Everything after this is measured from the middle of the base.
     *
     * <p><b>The first position is applied immediately</b>, as the original does: press the edge of
     * the base and you are already walking, rather than having to move before anything happens.
     *
     * @param touchId  what to recognize this finger by for the rest of the gesture
     * @param dxPx     how far right of the base's middle the finger is
     * @param dyPx     how far below the base's middle the finger is
     */
    public void grab(int touchId, double dxPx, double dyPx) {
        this.active = true;
        this.touchId = touchId;
        moveTo(dxPx, dyPx);
    }

    /**
     * The finger moved. Positions are relative to the middle of the base and may be anywhere at all;
     * the knob is what gets clamped, not the thumb.
     *
     * <p>Ignored when nothing is being dragged, so a stray movement cannot deflect a stick that
     * nobody is holding.
     */
    public void moveTo(double dxPx, double dyPx) {
        if (!active) {
            return;
        }
        double magnitude = Math.hypot(dxPx, dyPx);
        if (magnitude > RADIUS_PX) {
            knobX = dxPx / magnitude * RADIUS_PX;
            knobY = dyPx / magnitude * RADIUS_PX;
        } else {
            knobX = dxPx;
            knobY = dyPx;
        }
    }

    /** The finger lifted, or the gesture was taken away. The knob is back in the middle. */
    public void release() {
        active = false;
        touchId = 0;
        knobX = 0;
        knobY = 0;
    }

    /** Whether a finger is on the stick right now. */
    public boolean isActive() {
        return active;
    }

    /** Whether this is the finger holding the stick. False whenever nothing is being held. */
    public boolean owns(int touchId) {
        return active && this.touchId == touchId;
    }

    /** How far right of the middle the knob is drawn, in pixels. */
    public double knobX() {
        return knobX;
    }

    /** How far below the middle the knob is drawn, in pixels. */
    public double knobY() {
        return knobY;
    }

    /**
     * How far forward to walk, from −1 to 1.
     *
     * <p><b>The screen's y counts downward and forward is up</b>, so this is the negative of the
     * knob's vertical deflection, the original's {@code fwd = -joy3d.y}, and the one sign in here
     * that is easy to write backwards.
     */
    public double forward() {
        return inDeadzone() ? 0 : -knobY / RADIUS_PX;
    }

    /** How far right to strafe, from −1 to 1. */
    public double strafe() {
        return inDeadzone() ? 0 : knobX / RADIUS_PX;
    }

    /**
     * Whether the thumb is close enough to the middle to mean "not moving".
     *
     * <p><b>Measured on the distance from the middle, not on either axis</b>, and it therefore zeroes
     * <em>both</em>, which is the original's rule (original line 3052) and is what a deadzone has to
     * be. Testing each axis separately would leave a thumb held 11% right and 11% forward walking
     * diagonally at a speed nobody asked for, while the same thumb held 12% straight ahead did
     * nothing.
     */
    private boolean inDeadzone() {
        return Math.hypot(knobX, knobY) / RADIUS_PX < DEADZONE;
    }
}
