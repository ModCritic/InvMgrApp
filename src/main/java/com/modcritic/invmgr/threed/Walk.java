package com.modcritic.invmgr.threed;

import com.modcritic.invmgr.model.Room;

/**
 * Moving and turning the camera: the arithmetic of walking around the room.
 *
 * <p>Port of the original's {@code loop3d} body (original lines 3044-3066) and the look half of
 * {@code bindControls3d} (original lines 3075-3151), with the constants from original lines
 * 2782-2788.
 *
 * <p>No JavaFX, and no clock. Everything here is a plain function of numbers you hand it, which is
 * what lets "pressing W walks north" be checked without opening a window; the same arrangement
 * {@link Easing}, {@link CameraTween} and {@link Transitions} use. The clock lives in
 * {@code ui.ThreeDControls}, which calls {@link #frameSeconds} once a frame and passes the answer
 * back in.
 *
 * <p><b>Speed and sensitivity are parameters, not constants read from inside.</b> That is
 * deliberate and it is what let M6.3's touch controls arrive as <em>callers</em>: the joystick moves
 * at {@link #TOUCH_SPEED_FT_PER_S} = 6 ft/s rather than 9 and one-finger look turns at 0.006 rad/px
 * rather than 0.0022, so they hand these same functions different numbers instead of bringing a
 * second copy of the math. {@code threed.WalkGesture} and {@code threed.Joystick} are those
 * callers.
 *
 * <h2>Four movements, one direction</h2>
 *
 * <p>{@link #step} walks, {@link #pan} slides sideways and vertically, {@link #dolly} moves flat
 * forward, and every one of them needs to know which way "forward" and "right" are. That is written
 * down <b>once</b>, in {@link #forwardX}/{@link #forwardZ}/{@link #rightX}/{@link #rightZ}, and
 * three callers read it. A second copy is exactly how a camera ends up strafing east while it walks
 * north, and the mirror bug of M5.1 (CLAUDE.md §5.7 item 2) is what that class of mistake costs.
 */
public final class Walk {

    /** How fast walking moves the camera, in feet per second. Original line 2782. */
    public static final double MOVE_SPEED_FT_PER_S = 9;

    /**
     * How fast the touch joystick moves the camera at full deflection, in feet per second. Original
     * line 2783; {@code SPEC-3D-VIEW.md} §9 calls it {@code TOUCH_SPEED_3D}.
     *
     * <p>Two thirds of {@link #MOVE_SPEED_FT_PER_S}, and <b>touch has no boost at all</b>; the
     * original gives the joystick no equivalent of Shift, because there is no second hand free to
     * hold one. A thumb also cannot let go and re-press as precisely as a finger lifts off a key,
     * so the slower speed is what makes a small room navigable rather than something you overshoot.
     */
    public static final double TOUCH_SPEED_FT_PER_S = 6;

    /** What holding Shift multiplies the speed by. Desktop only. Original line 2784. */
    public static final double BOOST_MULT = 2;

    /**
     * How far the camera turns per pixel of mouse movement, in radians. Original line 2785.
     *
     * <p>This is the figure for a <em>captured</em> pointer, where the mouse can keep traveling in
     * one direction forever. When the pointer is not captured and you are dragging instead, the
     * travel is limited by the window, so the same hand movement has to turn further, hence
     * {@link #DRAG_LOOK_MULT}.
     */
    public static final double MOUSE_SENS_RAD_PER_PX = 0.0022;

    /**
     * What {@link #MOUSE_SENS_RAD_PER_PX} is multiplied by on the drag fallback, giving
     * 0.00308 rad/px. {@code SPEC-3D-VIEW.md} §4.
     */
    public static final double DRAG_LOOK_MULT = 1.4;

    /**
     * The longest single frame the movement loop will believe, in seconds. Original line 3047.
     *
     * <p><b>This is not a rounding detail; it is what stops a hitch from teleporting you.</b>
     * Movement is speed × elapsed time, so if the program is starved for half a second (another
     * window opening, the machine paging, the window manager compositing), the next frame arrives
     * carrying 500 ms and you cross 4.5 feet in one step. Worse, the room build on the first entry
     * of a run costs about 300 ms all by itself. Capping the elapsed time at fifty thousandths of a
     * second means a stutter costs you a little distance rather than putting you somewhere you did
     * not walk to.
     */
    public static final double MAX_FRAME_SECONDS = 0.05;

    private Walk() {
    }

    /**
     * How long the last frame took, in seconds, capped at {@link #MAX_FRAME_SECONDS}.
     *
     * <p>Takes nanoseconds because that is what a JavaFX {@code AnimationTimer} deals in.
     *
     * <p><b>Returns 0 when {@code lastNanos} is 0</b>, which is how the caller says "this is the
     * first frame and there is no previous one". Without that the first frame after starting the
     * loop would be handed the entire time since the machine booted. {@code ui.DragGhost} solves
     * the same problem with an {@code if} at the call site; doing it here instead means the rule is
     * testable and cannot be forgotten by a second caller.
     *
     * <p>A negative gap is treated as zero rather than trusted. It should not happen, but a clock
     * that goes backwards must not move the camera backwards.
     */
    public static double frameSeconds(long nowNanos, long lastNanos) {
        if (lastNanos == 0) {
            return 0;
        }
        double seconds = (nowNanos - lastNanos) / 1e9;
        if (seconds < 0) {
            return 0;
        }
        return Math.min(MAX_FRAME_SECONDS, seconds);
    }

    /**
     * Moves the camera for one frame, then pulls it back inside the allowed volume.
     *
     * <p>The three directions are each −1, 0 or +1 for the keyboard, and anything in between for a
     * joystick. {@code fwd} is positive going forward, {@code str} positive going right, and
     * {@code vert} positive going up.
     *
     * <p><b>Forward and right are worked out from the yaw, so walking follows where you are
     * looking</b>; forward is {@code (sin yaw, 0, −cos yaw)} and right is {@code (cos yaw, 0,
     * sin yaw)}, which at yaw 0 point north and east respectively.
     *
     * <p><b>Up is not.</b> {@code vert} adds straight to {@code y} whatever the pitch is, so
     * looking at the floor and pressing the up key still rises rather than flying into the ground.
     * The original does the same and it is easy to "fix" by accident into a view-relative move.
     *
     * <p><b>Diagonals are deliberately not normalized.</b> Holding forward and right together
     * covers 9·√2 ≈ 12.7 ft/s rather than 9. That is faster than either alone and it is what the
     * original does; a test pins it so nobody tidies it into a unit vector.
     *
     * <p>The clamp runs <b>every call, not only when something moved</b>, exactly as the original
     * places {@code clampCamPosition3d} outside its {@code if}. It costs nothing and it means a
     * camera left somewhere illegal by anything else (a resized room, a loaded file) is corrected
     * on the next frame rather than staying there.
     *
     * <p>Not ported: the original's {@code if (ad3d.dolly) ad3d.dolly(dF, cam3d)} on the line
     * after the move. No adapter in the original ever defines {@code dolly}, so the branch is dead
     * there too. <b>{@link #dolly} below is not that hook resurrected</b>; it is §5.5 D-15, a
     * gesture the original does not have, and it happens to want the same word.
     */
    public static void step(CameraPose cam, double fwd, double str, double vert,
            double speedFtPerS, boolean boost, double dtSeconds, Room room) {
        if (fwd != 0 || str != 0 || vert != 0) {
            double sp = speedFtPerS * (boost ? BOOST_MULT : 1) * dtSeconds;
            cam.x += forwardX(cam.yaw) * fwd * sp + rightX(cam.yaw) * str * sp;
            cam.z += forwardZ(cam.yaw) * fwd * sp + rightZ(cam.yaw) * str * sp;
            cam.y += vert * sp;
        }
        cam.clampPosition(room);
    }

    /**
     * Turns the camera by a mouse or finger movement, then pulls the pitch back inside its limit.
     *
     * <p>{@code dxPx} and {@code dyPx} are the movement in screen pixels, with y counted downward
     * as every pointer event reports it.
     *
     * <p><b>Pitch subtracts.</b> Moving the mouse down looks down, which is the direction it has to
     * be for the camera to feel like a head rather than a lever, and it is what the original does.
     * The sign is the single easiest thing to get backwards here, so a test pins it.
     *
     * <p><b>Yaw is deliberately left unwrapped</b> and will accumulate past 2π if you keep turning.
     * Nothing cares (sine and cosine are periodic) and the one place that does normalize it is
     * {@link Transitions#ascent}, so it can take the short way round when leaving. Normalizing here
     * as well would be harmless today and would quietly do that job twice.
     */
    public static void look(CameraPose cam, double dxPx, double dyPx, double sensitivity) {
        cam.yaw += dxPx * sensitivity;
        cam.pitch -= dyPx * sensitivity;
        cam.clampPitch();
    }

    /**
     * Slides the camera sideways and vertically without turning it, then pulls it back inside the
     * allowed volume. The original's two-finger pan (original lines 3186-3195).
     *
     * <p><b>Both arguments are in feet, not pixels.</b> Turning a finger's travel into a distance is
     * the caller's job, because the number that does it ({@code TOUCH_PAN_SENS_3D}, 0.03 ft/px) is
     * a property of a hand on glass rather than of a camera in a room. {@code threed.WalkGesture} is
     * where it lives.
     *
     * <p>{@code rightFt} is positive going to the camera's right, along {@code (cos yaw, 0, sin
     * yaw)}, <b>the same right vector {@link #step} strafes along</b>, which is the whole reason
     * both read it from {@link #rightX}. {@code upFt} is positive going up, and like {@code step}'s
     * vertical it ignores the pitch entirely: panning while looking at the ceiling still slides, it
     * does not climb.
     *
     * <p>There is deliberately no "did anything move" guard of the kind {@code step} has. A pan is
     * only ever called from a finger that has actually moved, and the clamp has to run regardless,
     * which is the same reasoning that puts {@code step}'s clamp outside its own {@code if}.
     */
    public static void pan(CameraPose cam, double rightFt, double upFt, Room room) {
        cam.x += rightX(cam.yaw) * rightFt;
        cam.z += rightZ(cam.yaw) * rightFt;
        cam.y += upFt;
        cam.clampPosition(room);
    }

    /**
     * Moves the camera along the direction it faces, flat, then pulls it back inside the allowed
     * volume. CLAUDE.md §5.5 <b>D-15</b>: spreading two fingers moves you forward, squeezing them
     * moves you back.
     *
     * <p><b>New behavior, not a port.</b> The original reads only the <em>midpoint</em> of two
     * fingers and never the distance between them, so a pinch does nothing at all there. Asked for
     * by the user 2026-08-06 on the grounds that the gesture was carrying one meaning where a hand
     * expects two: every map and every photograph on a phone answers a spread by moving you closer.
     *
     * <p><b>Flat forward, and the pitch is ignored; that is the decision, not a simplification.</b>
     * The user chose it over flying along the aim point, so that the view has one idea of "forward"
     * shared with W, S and the joystick. Flying toward where you happen to be looking would make the
     * identical gesture rise when you look up and sink when you look down, which is the same
     * argument that keeps {@code step}'s vertical out of the pitch.
     *
     * <p>{@code forwardFt} is in feet, positive going forward, for the same reason {@link #pan}'s
     * arguments are: {@code TOUCH_PINCH_SENS_3D} describes fingers, not cameras.
     */
    public static void dolly(CameraPose cam, double forwardFt, Room room) {
        cam.x += forwardX(cam.yaw) * forwardFt;
        cam.z += forwardZ(cam.yaw) * forwardFt;
        cam.clampPosition(room);
    }

    // ------------------------------------------------------- which way is forward
    //
    // One copy, read by step, pan and dolly. At yaw 0 forward is north and right is east, which is
    // what the camera faces the moment it lands; see Transitions and CLAUDE.md §5.7 item 2, where
    // negating y and z together is what makes that true with no correction anywhere else.
    //
    // Package-private rather than public: WalkTest pins the directions directly, and nothing outside
    // this package has any business asking. Four scalar methods rather than one returning a pair,
    // because these are read sixty times a second and a pair means an allocation per frame to hold
    // two numbers that are already in registers.

    /** The east-west part of "forward", from a yaw. */
    static double forwardX(double yaw) {
        return Math.sin(yaw);
    }

    /** The north-south part of "forward", from a yaw. Negative, because north is −z. */
    static double forwardZ(double yaw) {
        return -Math.cos(yaw);
    }

    /** The east-west part of "right", from a yaw. */
    static double rightX(double yaw) {
        return Math.cos(yaw);
    }

    /** The north-south part of "right", from a yaw. */
    static double rightZ(double yaw) {
        return Math.sin(yaw);
    }
}
