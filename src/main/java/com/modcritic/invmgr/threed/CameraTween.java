package com.modcritic.invmgr.threed;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

/**
 * Moves the camera from one pose to another over time, <b>with a separate curve allowed for each
 * of the five things that are moving</b>.
 *
 * <p>That last part is the whole reason this class exists rather than five separate numbers being
 * blended by hand, and {@code SPEC-3D-VIEW.md} §3 is emphatic about it: giving position and
 * orientation the same curve makes the descent look front-loaded and staggered <em>even when the
 * numbers are perfectly in step</em>. The original found that out over several attempts. See
 * {@link Transitions} for which channel gets which curve and why.
 *
 * <p><b>Why this is not the original's {@code playKeys3d}.</b> The original's player takes a list
 * of keyframes at arbitrary points along the timeline and picks the right segment for the moment
 * being drawn. It is called exactly twice in the whole app, and both times with a list of two:
 * a start and an end. Porting the segment machinery would be porting a capability nothing uses,
 * which CLAUDE.md §2 forbids for the same reason the dead {@code zoom} channel was dropped. Two
 * poses and a curve each is what the app actually does. If a future animation ever needs a
 * waypoint in the middle, this grows a list; until then it does not have one to get wrong.
 *
 * <p>No JavaFX and no clock: {@link #at(double)} is handed the fraction of the way through, so the
 * caller owns the timing and every rule in here can be tested without a window.
 */
public final class CameraTween {

    /** The five numbers a pose is made of, so a curve can be attached to one of them by name. */
    public enum Channel {
        /** Feet east. */
        X,
        /** Feet up. */
        Y,
        /** Feet south. */
        Z,
        /** Radians clockwise from north. */
        YAW,
        /** Radians above level. */
        PITCH
    }

    private final CameraPose from;
    private final CameraPose to;
    private final Map<Channel, DoubleUnaryOperator> curves;

    /**
     * @param from    where the camera starts. Copied, so the live pose can be animated away from it
     * @param to      where it ends up. Also copied
     * @param curves  a curve for any channel that should not use {@link Easing#IN_OUT_SINE}.
     *                Channels absent from the map get the default
     */
    public CameraTween(CameraPose from, CameraPose to, Map<Channel, DoubleUnaryOperator> curves) {
        this.from = from.copy();
        this.to = to.copy();
        this.curves = curves == null || curves.isEmpty()
                ? Map.of()
                : new EnumMap<>(curves);
    }

    /** A tween where every channel uses the default curve. */
    public CameraTween(CameraPose from, CameraPose to) {
        this(from, to, Map.of());
    }

    /** Where the camera started. A copy; changing it cannot reach inside the tween. */
    public CameraPose from() {
        return from.copy();
    }

    /** Where the camera is headed. A copy. */
    public CameraPose to() {
        return to.copy();
    }

    /** The curve this channel will be moved along. */
    public DoubleUnaryOperator curveFor(Channel channel) {
        return curves.getOrDefault(channel, Easing.IN_OUT_SINE);
    }

    /**
     * The pose at {@code progress} of the way through, where 0 is the start and 1 is the end.
     *
     * <p><b>Clamped at both ends.</b> A frame can arrive slightly after the animation was due to
     * finish (the clock does not tick in exact multiples of a frame), and without the clamp the
     * curve would carry on past its endpoint and overshoot the pose it was supposed to arrive at.
     * Asking for 1.4 gives the same answer as asking for 1.
     */
    public CameraPose at(double progress) {
        double p = Math.max(0, Math.min(1, progress));
        return new CameraPose(
                blend(Channel.X, from.x, to.x, p),
                blend(Channel.Y, from.y, to.y, p),
                blend(Channel.Z, from.z, to.z, p),
                blend(Channel.YAW, from.yaw, to.yaw, p),
                blend(Channel.PITCH, from.pitch, to.pitch, p));
    }

    /**
     * Writes the pose at {@code progress} straight into an existing camera.
     *
     * <p>The render loop calls this sixty times a second, and a new {@link CameraPose} per frame
     * would be sixty allocations a second for nothing. Same arithmetic as {@link #at(double)}.
     */
    public void applyTo(CameraPose camera, double progress) {
        camera.set(at(progress));
    }

    private double blend(Channel channel, double start, double end, double p) {
        return start + (end - start) * curveFor(channel).applyAsDouble(p);
    }
}
