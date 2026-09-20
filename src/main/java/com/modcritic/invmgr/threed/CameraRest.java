package com.modcritic.invmgr.threed;

/**
 * Notices the moment the camera stops moving, so the {@code 3d} verbose topic can say where it
 * ended up.
 *
 * <p><b>Why this exists.</b> Nothing in the app printed the camera's position or heading, so the
 * only way to know where you were standing in the 3D room was to look at the picture and guess.
 * That cost a round of the M6.7c device test: three boxes were hunted for by dragging at random
 * when all three were 0.75 to 3.75 ft in front of the camera and simply below the bottom of the
 * frame, because a 6 ft eye looking level clears a 1 ft box four feet away by about 53 degrees.
 * One line saying "facing N, level" would have settled it immediately.
 *
 * <p><b>It watches the camera, not the controls.</b> That is the whole design. Movement arrives
 * from the keys, the joystick, a mouse-look drag, a two-finger pan, a pinch and the landing at
 * the end of a descent, and asking each of those whether it is finished means six questions and
 * a seventh the day somebody adds another. Comparing the pose against the previous frame's asks
 * one question about the effect instead, and cannot miss a source.
 *
 * <p><b>Exact comparison, deliberately, not an epsilon.</b> Standing still really is bit for bit
 * identical here: {@link Walk#step} does nothing at all unless a movement control is held, and
 * the {@code clampPosition} it always calls assigns a value back unchanged when the camera is
 * inside the room. So "the numbers did not change" means the camera did not move, with no
 * tolerance to tune and no slow drift to mistake for motion.
 *
 * <p><b>Deciding and saying are separate</b>, which is why {@link #update} returns a boolean and
 * {@link #line} formats. It keeps the formatting out of the hot path (a frame that changed
 * nothing builds no string), it lets {@code Verbose.log} take a supplier and stay lazy, and it
 * means both halves can be tested without capturing {@code System.out}, the same way
 * {@code VerboseTest} avoids it.
 *
 * <p><b>How it connects:</b> {@code ThreeDControls}' walking timer calls {@link #update} once a
 * frame and logs {@link #line} when it comes back true. The timer runs the whole time the 3D
 * view is open, so the first rest it reports is the landing pose, with no special case for the
 * descent.
 */
public final class CameraRest {

    /** Compass points at 45 degree steps, starting at north and turning toward east. */
    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    /** Below this many degrees off level, the line says "level" rather than a number. */
    private static final double LEVEL_DEGREES = 0.5;

    private double x;
    private double y;
    private double z;
    private double yaw;
    private double pitch;

    /** False until the first {@link #update}, so the opening pose is not compared against zeros. */
    private boolean seen;

    /** Whether the pose changed on the previous {@link #update}. The falling edge is the event. */
    private boolean moving;

    /**
     * Takes one frame's pose and says whether the camera has just come to rest.
     *
     * <p>True exactly once per stop: on the first frame that finds the pose unchanged after a
     * frame that found it changed. A camera left alone reports true once and then nothing, which
     * is what keeps this off the per-frame log.
     *
     * <p>The very first call always counts as movement, so the pose the camera lands in is
     * reported on the frame after it lands rather than being swallowed as "it was always there".
     */
    public boolean update(CameraPose camera) {
        boolean changed = !seen
                || camera.x != x || camera.y != y || camera.z != z
                || camera.yaw != yaw || camera.pitch != pitch;

        x = camera.x;
        y = camera.y;
        z = camera.z;
        yaw = camera.yaw;
        pitch = camera.pitch;
        seen = true;

        if (changed) {
            moving = true;
            return false;
        }
        if (moving) {
            moving = false;
            return true;
        }
        return false;
    }

    /**
     * Forgets everything, so the next {@link #update} starts a fresh flight.
     *
     * <p>Called when the 3D view closes. Without it, reopening the view onto the same pose the
     * camera left in would look unchanged and report no rest at all, which is the one moment the
     * line is most worth having.
     */
    public void clear() {
        seen = false;
        moving = false;
    }

    /**
     * One line: where the camera is standing and which way it is looking.
     *
     * <p><b>The position is the camera's own signed coordinates, not a flipped bearing</b>, so a
     * camera that has walked out through the north wall reads {@code -4.3 S} rather than
     * {@code 4.3 N}. That looks odd the first time and is the right way round: the number stays
     * the {@code z} the model holds, which is what somebody debugging is comparing it against,
     * and leaving the room is allowed (see {@code CameraPose.ROOM_SLACK_FT}).
     */
    public String line() {
        return String.format("camera at rest: %.1f E, %.1f S, %.1f up (ft), facing %s (yaw %.0f), %s",
                x, z, y, compass(yaw), degreesInACircle(yaw), tilt(pitch));
    }

    /**
     * The compass point a yaw is nearest, as {@code SPEC-3D-VIEW.md} §1 defines them: yaw 0 faces
     * north and a positive turn takes the view toward east.
     */
    static String compass(double yawRadians) {
        return COMPASS[((int) Math.round(degreesInACircle(yawRadians) / 45)) % 8];
    }

    /** A yaw in degrees, folded into 0 up to 360 so the log never prints a negative heading. */
    static double degreesInACircle(double yawRadians) {
        double degrees = Math.toDegrees(yawRadians) % 360;
        return degrees < 0 ? degrees + 360 : degrees;
    }

    /**
     * How far from level the view is, in words.
     *
     * <p>Up and down rather than a signed number, because the sign is the thing a reader has to
     * look up: a positive pitch tilts the view up, and nothing on the screen says so.
     */
    static String tilt(double pitchRadians) {
        double degrees = Math.toDegrees(pitchRadians);
        if (Math.abs(degrees) < LEVEL_DEGREES) {
            return "level";
        }
        return degrees > 0
                ? String.format("looking up %.0f", degrees)
                : String.format("looking down %.0f", -degrees);
    }
}
