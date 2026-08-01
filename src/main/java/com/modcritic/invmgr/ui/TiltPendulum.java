package com.modcritic.invmgr.ui;

/**
 * The lean of the card you are carrying while you drag a planned item into the room.
 *
 * <p>This is the arithmetic only — it knows nothing about JavaFX, and it draws nothing. Feed it
 * where the pointer is and how long since you last fed it, and it hands back an angle in degrees.
 * {@link DragGhost} is what turns that into a rotated card. Kept apart because a pendulum is
 * exactly the kind of thing that needs testing at a hundred frames a second across a dozen
 * made-up hand movements, and doing that through a real window would be slow and flaky.
 *
 * <h2>What it models</h2>
 *
 * <p>A card hanging from the point your cursor grabbed it, with weight and with air resistance.
 * Move your hand and the card swings; hold a steady speed and it hangs straight; stop and it
 * swings past straight, comes back, and settles.
 *
 * <p><b>The lean comes from acceleration, not speed.</b> That is the whole point of this class and
 * it is worth being precise about, because the app did the other thing until 2026-08-01 and the
 * difference is what the user noticed. Something hanging from your hand does not care how fast you
 * are going — it cares how hard you are changing it. Walk across a room at a constant speed
 * carrying a bag and the bag hangs straight down; start, stop or turn and it swings. The angle it
 * settles at is {@code atan(acceleration / gravity)}, which is what {@link #GRAVITY_PX_PER_S2} and
 * the {@code atan} below are.
 *
 * <h2>Why it is a spring and not just that formula</h2>
 *
 * <p>Three reasons, and each one is a bug that the plain formula has:
 *
 * <ul>
 *   <li><b>Noise.</b> A mouse reports whole pixels, so a slow, steady hand arrives as
 *       1, 1, 0, 1, 1, 0 px per frame. Acceleration is the change in the change of position, so it
 *       amplifies that stutter rather than ignoring it — the naive version would jitter
 *       <em>worse</em> than the old speed-based one, not better. A spring only moves as far as the
 *       force applied over the time it is applied, so a one-frame spike barely shifts it while a
 *       real shove over ten frames moves it the whole way. Measured in {@code TiltPendulumTest}:
 *       one frame of pixel noise is worth an eighth of a degree.
 *   <li><b>It would snap.</b> A real card overshoots and settles. The formula on its own would
 *       jump straight to the answer and stop dead there.
 *   <li><b>Stopping.</b> With no spring there is nothing to swing back, so letting go of the idea
 *       of momentum also loses the settle at the end of a drag, which is the part that reads as
 *       weight.
 * </ul>
 *
 * <h2>Which way it leans</h2>
 *
 * <p><b>The way a real hanging card leans, which is not the way the original app leaned.</b> Push
 * something hanging to the right and its bottom trails to the <em>left</em>. The original tilts it
 * clockwise instead — see {@code docs/original/InvMgr_V1.3.0.html} line 2046, where a rightward
 * movement produces a positive {@code rotate()}. This class copied that at first, on the grounds
 * that it was the look the user had signed off without ever having seen the alternative. Having
 * seen both, they chose the physical one on 2026-08-01, so it is now a deliberate divergence —
 * {@code CLAUDE.md} §5.5, D-8. It is the minus sign on {@code acceleration} in {@link #step} and
 * nothing else.
 *
 * <h2>One thing it deliberately cannot do</h2>
 *
 * <p><b>It cannot tell the end of a rightward movement from the start of a leftward one</b>, and
 * neither can anything else that works from the pointer alone: both are a leftward change in speed
 * and they are the same numbers. So the settle at the end of a drag and the lean at the start of
 * one are not separately adjustable. When the user asked on 2026-08-01 for the settle to be about a
 * fifth less aggressive, the honest way to give them that was to make the whole swing a fifth less
 * aggressive, which is what {@link #SWINGS_PER_SECOND} and {@link #DAMPING_RATIO} now do.
 */
public final class TiltPendulum {

    /**
     * How strongly the card wants to hang straight, in pixels per second squared.
     *
     * <p>It sets the exchange rate between how hard you shove and how far it leans, because the
     * resting angle is {@code atan(acceleration / this)}. Chosen so that <b>8000 px/s²</b> — a
     * firm flick, roughly nought to 1200 px/s in an eighth of a second — is the point where the
     * lean reaches {@link #MAX_TILT_DEGREES} and stops growing. A gentle start of around
     * 1600 px/s² comes out near 3°, so ordinary careful dragging still says something rather than
     * pinning at either end.
     *
     * <p>It is not 9.81 m/s² in disguise and is not trying to be. The card is not a real object at
     * a real size, and the pointer is not a real hand — the number that matters is the one where
     * the range of accelerations a hand actually produces maps onto the range of angles that look
     * right, and that was found by measurement, not by unit conversion.
     */
    private static final double GRAVITY_PX_PER_S2 = 27_900;

    /**
     * The furthest it will lean while you are pushing it, in degrees.
     *
     * <p>The original's limit, kept exactly — but it is a limit on how far the card is ever
     * <em>pulled</em>, not on where it can be seen. <b>Momentum carries it past</b>, which the
     * original could not do because it had none.
     *
     * <p><b>The worst case is 16.7°, and that is measured, not derived.</b> Deliberately shaking
     * the pointer at 1.5 Hz — near enough the spring's own frequency to pump it, which is the one
     * input that can put more energy in than it takes out — reaches 16.71°. So under the damping
     * this now runs at, momentum is worth less than a degree past the limit, where before the
     * 2026-08-01 change it was worth nearly five. {@code TiltPendulumTest} sweeps the shake across
     * 1.5–6 Hz and holds the bound at 17, so if a future change to the damping or the frequency
     * lets the swing run away, that is what says so.
     *
     * <p>The old bound is worth keeping a note of, because <b>the obvious way to predict it is
     * wrong</b>. At the previous damping the measured worst case was 20.9°; deriving it from the
     * damping ratio gives 18.6°, because that derivation assumes the spring is driven by a sine.
     * The clamp on this line turns the input into something much closer to a square wave, and a
     * square wave's fundamental is 27% taller than the square. Measure this one; do not reason
     * about it.
     */
    private static final double MAX_TILT_DEGREES = 16;

    /**
     * How quickly the card swings, in swings per second.
     *
     * <p>This is the knob that decides whether it feels like a luggage tag or a wet towel, and
     * together with {@link #DAMPING_RATIO} it is how hard the card whips round when your hand
     * stops. Set by measuring the response to a realistic flick: at 2.3 Hz a shove reaches most of
     * its lean within about an eighth of a second, which is fast enough that the lean still looks
     * caused by the movement rather than trailing after it.
     *
     * <p>Was 2.5 until 2026-08-01, when the user asked for the snap at the end of a movement to be
     * around a fifth less aggressive. This constant is the <em>rate</em> half of that: it took the
     * fastest the card ever turns from 131°/s down to 108°/s. Dropping it further keeps working —
     * 1.9 Hz gives 92°/s — but the card then takes over half a second to stop moving after your
     * hand has, which reads as lag rather than as weight.
     */
    private static final double SWINGS_PER_SECOND = 2.3;

    /**
     * How much the swinging is damped, where 1 would mean it never overshoots at all.
     *
     * <p>Below about 0.3 it wobbles several times and looks like jelly; at 1 it slides into place
     * with no swing at all and the weight disappears. Seven tenths sits nearer the calm end of
     * that: the card swings back once when your hand stops, does not cross straight a second time,
     * and is still.
     *
     * <p>Was 0.5 until 2026-08-01, and this constant is the <em>size</em> half of the fifth-less
     * -aggressive change the user asked for: the swing-back after a firm flick went from 11.3° to
     * 9.1°. What it costs is the small return past straight that 0.5 had — about a degree, once,
     * around a third of a second after the hand stopped. That is the one piece of the old feel this
     * change spends, and it was the cheapest thing available: the alternative was to pull the whole
     * range down with {@link #GRAVITY_PX_PER_S2}, which would have flattened careful slow dragging
     * as well, and careful slow dragging is what this feature is mostly used for.
     */
    private static final double DAMPING_RATIO = 0.7;

    /**
     * How long the speed estimate takes to catch up, in seconds.
     *
     * <p>The first line of defence against whole-pixel mouse reports. Twenty-hundredths would
     * smear a real flick into mush and two would let the stutter through; a twentieth of a second
     * is about three frames at 60 Hz, which is long enough to average out the 1, 1, 0 pattern and
     * short enough that a deliberate movement is not delayed enough to see.
     */
    private static final double SPEED_SETTLE_SECONDS = 0.05;

    /**
     * The longest step it will ever integrate in one go, in seconds.
     *
     * <p>Two things, and they happen to want the same number. A dropped frame or a stalled window
     * would otherwise hand this a step of several seconds and the card would fly off in one jump;
     * and the simple integrator below is only stable while the step is comfortably under
     * {@code 2 / (2π × SWINGS_PER_SECOND)}, which is 138 ms. A twentieth of a second is well
     * inside that and is already three frames' worth, so nothing that happens in normal use ever
     * reaches it.
     */
    private static final double MAX_STEP_SECONDS = 1.0 / 20;

    private double lastPointerX;
    private double speed;
    private double angle;
    private double angularSpeed;

    /**
     * Hangs the card straight down and forgets every previous movement.
     *
     * <p>Called when a card is lifted out of the list. Without it the next drag would begin
     * mid-swing, wearing the last one's momentum.
     *
     * @param pointerX where the pointer is now, so the first step measures no movement rather than
     *     a jump from wherever the previous drag ended
     */
    public void reset(double pointerX) {
        lastPointerX = pointerX;
        speed = 0;
        angle = 0;
        angularSpeed = 0;
    }

    /**
     * Advances the swing by one frame and reports the new lean.
     *
     * @param pointerX where the pointer is, in scene coordinates — the same measure the original
     *     used, and deliberately not the zoomed layer's, because how fast a hand sweeps across the
     *     glass is a physical fact that does not change when the interface is drawn larger
     * @param seconds how long since the last call. Clamped; see {@link #MAX_STEP_SECONDS}
     * @return the lean in degrees, positive clockwise
     */
    public double step(double pointerX, double seconds) {
        double dt = Math.min(seconds, MAX_STEP_SECONDS);
        if (dt <= 0) {
            // Two frames reported at the same instant. Dividing by it would produce infinities
            // that then persist in the angle forever, which is a far worse outcome than skipping
            // a frame nothing moved in.
            return angle;
        }

        // Speed, smoothed. The smoothing fraction is worked out from the step rather than being a
        // constant, so the feel does not change between a 60 Hz screen and a 144 Hz one.
        double measuredSpeed = (pointerX - lastPointerX) / dt;
        lastPointerX = pointerX;
        double previousSpeed = speed;
        speed += (measuredSpeed - speed) * (1 - Math.exp(-dt / SPEED_SETTLE_SECONDS));

        // And the change in speed, which is what a hanging thing actually responds to.
        double acceleration = (speed - previousSpeed) / dt;

        // The minus sign is the entire direction of the effect, and it is the one place the card
        // now behaves unlike the original: shove it right and the bottom trails left, the way
        // anything hanging does. See the class note.
        double hang = Math.toDegrees(Math.atan(-acceleration / GRAVITY_PX_PER_S2));
        double target = Math.max(-MAX_TILT_DEGREES, Math.min(MAX_TILT_DEGREES, hang));

        // A damped spring pulling the lean towards where it would hang. Speed is updated before
        // the angle rather than after, which is what keeps the swing from gaining energy of its
        // own over a long drag.
        double stiffness = 2 * Math.PI * SWINGS_PER_SECOND;
        angularSpeed += (stiffness * stiffness * (target - angle)
                - 2 * DAMPING_RATIO * stiffness * angularSpeed) * dt;
        angle += angularSpeed * dt;
        return angle;
    }

    /** The current lean in degrees, without advancing anything. */
    public double angleDegrees() {
        return angle;
    }
}
