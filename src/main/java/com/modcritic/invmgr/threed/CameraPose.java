package com.modcritic.invmgr.threed;

import com.modcritic.invmgr.model.Room;

/**
 * Where the camera is and which way it is looking: a position in feet plus a yaw and a pitch in
 * radians. Mutable on purpose: there is one of these per 3D session and the render loop moves it
 * sixty times a second, so allocating a new one per frame would be churn for no gain.
 *
 * <p>Axes follow {@code SPEC-3D-VIEW.md} §1: x east, z south, y up, and <b>yaw 0 faces north</b>,
 * i.e. toward −z. Pitch 0 is level, negative is looking down, so straight down is −π/2.
 *
 * <p>No JavaFX. The camera is the same camera whatever draws it, and keeping it that way is what
 * OD-1's binding consequence 1 asks for: the renderer decision stays reversible only while the
 * things in front of the adapter know nothing about the renderer behind it. It also means every
 * rule here can be tested with no window at all.
 */
public final class CameraPose {

    /**
     * How far outside the room the camera may go, in feet, on x and z.
     *
     * <p><b>You can walk out through a wall and look back in, and that is a feature.</b> The
     * original says so in as many words (original line 2865): rendering correctly near a wall is
     * the renderer's job, not something to be enforced by fencing the camera in. Do not add wall
     * collision.
     */
    public static final double ROOM_SLACK_FT = 60;

    /** Lowest the camera may go, in feet. Just above the floor rather than on it. */
    public static final double MIN_Y_FT = 0.5;

    /** Highest the camera may go, in feet. Well above any legal room. */
    public static final double MAX_Y_FT = 300;

    /**
     * How far up or down the camera may look, in radians (about 88.8°).
     *
     * <p>Just short of straight up and straight down. At exactly ±90° the yaw axis and the view
     * direction line up and the camera loses a degree of freedom, which shows up as the view
     * spinning when the mouse moves sideways. Stopping a degree short costs nothing visible and
     * avoids it entirely.
     */
    public static final double PITCH_LIMIT_RAD = 1.55;

    /** Eye height when walking, in feet. */
    public static final double EYE_HEIGHT_FT = 6;

    /** Feet east of the room's north-west corner. */
    public double x;

    /** Feet above the true floor. */
    public double y;

    /** Feet south of the room's north-west corner. */
    public double z;

    /** Radians clockwise from north. */
    public double yaw;

    /** Radians above level. Negative looks down. */
    public double pitch;

    /** A camera at the origin, at eye height, facing north and level: the original's start. */
    public CameraPose() {
        this(0, EYE_HEIGHT_FT, 0, 0, 0);
    }

    public CameraPose(double x, double y, double z, double yaw, double pitch) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /** A copy, for keeping a start pose while the live one is animated away from it. */
    public CameraPose copy() {
        return new CameraPose(x, y, z, yaw, pitch);
    }

    /** Overwrites this pose with another's values, without allocating. */
    public void set(CameraPose other) {
        this.x = other.x;
        this.y = other.y;
        this.z = other.z;
        this.yaw = other.yaw;
        this.pitch = other.pitch;
    }

    /**
     * Pulls the position back inside the allowed volume: the room plus 60 ft of slack on each
     * side horizontally, and 0.5 to 300 ft vertically. Port of {@code clampCamPosition3d}
     * (original lines 2870-2873).
     */
    public void clampPosition(Room room) {
        x = clamp(x, -ROOM_SLACK_FT, room.w + ROOM_SLACK_FT);
        z = clamp(z, -ROOM_SLACK_FT, room.l + ROOM_SLACK_FT);
        y = clamp(y, MIN_Y_FT, MAX_Y_FT);
    }

    /** Pulls the pitch back inside ±{@link #PITCH_LIMIT_RAD}. */
    public void clampPitch() {
        pitch = clamp(pitch, -PITCH_LIMIT_RAD, PITCH_LIMIT_RAD);
    }

    /**
     * Wraps an angle into −π…π. Port of {@code normYaw3d} (original lines 2859-2863).
     *
     * <p>Used before interpolating a yaw so the camera takes the short way round. Turning from
     * 170° to −170° is 20° the near way and 340° the far way; without this the exit animation
     * would spin the camera almost all the way round to arrive at the same place.
     */
    public static double normYaw(double radians) {
        double y = radians % (2 * Math.PI);
        if (y > Math.PI) {
            y -= 2 * Math.PI;
        }
        if (y < -Math.PI) {
            y += 2 * Math.PI;
        }
        return y;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
