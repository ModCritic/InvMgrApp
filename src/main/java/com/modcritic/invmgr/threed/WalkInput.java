package com.modcritic.invmgr.threed;

import java.util.EnumSet;

/**
 * Which movement controls are currently held down, and what they add up to.
 *
 * <p>The original keeps the same thing as a plain object of seven counters,
 * {@code keys3d = { f, b, l, r, up, dn, boost }} (original line 2780). This is that object, with
 * the "what do they add up to" arithmetic moved in beside it so it can be tested; "forward and
 * back together cancel" is a rule, and a rule with no test is how the sweep ends up measuring the
 * wrong surface.
 *
 * <p>Mutable and not thread-safe, which is correct: there is one of these per 3D session and only
 * the JavaFX application thread ever touches it.
 *
 * <p>No JavaFX. A key <em>event</em> is a JavaFX thing and belongs in {@code ui.ThreeDControls};
 * which controls are down is not.
 */
public final class WalkInput {

    /** The seven things that can be held down. */
    public enum Control {
        FORWARD, BACK, LEFT, RIGHT, UP, DOWN, BOOST
    }

    private final EnumSet<Control> held = EnumSet.noneOf(Control.class);

    /**
     * Records a control going down or coming up.
     *
     * <p><b>Returns true only when this actually changed something</b>, that is, on the edge from
     * up to down or from down to up, and not on the repeats an operating system sends while a key
     * stays held. The 3D view's "any input lands the transition" rule (CLAUDE.md §5.5 <b>D-11</b>)
     * is built on exactly that edge, so it gets it from here rather than keeping a second copy of
     * the key state to compare against.
     */
    public boolean set(Control control, boolean pressed) {
        return pressed ? held.add(control) : held.remove(control);
    }

    /** −1 going backward, +1 going forward, 0 for neither or both. */
    public double forward() {
        return axis(Control.FORWARD, Control.BACK);
    }

    /** −1 going left, +1 going right, 0 for neither or both. */
    public double strafe() {
        return axis(Control.RIGHT, Control.LEFT);
    }

    /** −1 going down, +1 going up, 0 for neither or both. */
    public double vertical() {
        return axis(Control.UP, Control.DOWN);
    }

    /** Whether Shift is held. Desktop only: the original gives touch no boost. */
    public boolean isBoosting() {
        return held.contains(Control.BOOST);
    }

    /**
     * Whether nothing at all is held.
     *
     * <p>Note this asks about the <em>keys</em>, not about the movement: forward and back held
     * together cancel out to a standstill but are not idle, because letting go of one of them
     * starts you moving with no new key going down.
     */
    public boolean isIdle() {
        return held.isEmpty();
    }

    /**
     * Forgets everything that is held.
     *
     * <p>Exists for one specific failure. Alt-tab away with a key held and the window never
     * receives the key-up, because the key came up somewhere else, so without this the camera
     * walks away by itself and does not stop until you come back and press that key again. The
     * original inherits the same bug from the browser; it is a bug rather than a behavior, so
     * fixing it is not a divergence.
     */
    public void clear() {
        held.clear();
    }

    private double axis(Control positive, Control negative) {
        return (held.contains(positive) ? 1 : 0) - (held.contains(negative) ? 1 : 0);
    }
}
