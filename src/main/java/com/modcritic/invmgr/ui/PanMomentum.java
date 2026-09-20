package com.modcritic.invmgr.ui;

/**
 * The room's coasting after a pan: how fast the finger was going when it left the glass, and
 * where the room should be on each frame afterwards.
 *
 * <p>Pure arithmetic. No JavaFX, no clock, no scene graph. Every time is a parameter, exactly as
 * {@link TouchGesture} and {@link TiltPendulum} take theirs, so the whole of this can be driven
 * from a test at any speed without a window.
 *
 * <p><b>Why the app owns this at all, when the platform already has one.</b> On Android, and only
 * there, Glass builds a {@code ScrollGestureRecognizer} that flings the room by itself. It decides
 * whether to fling by asking <em>how long the whole gesture lasted</em>, and flings only when that
 * is under 300 ms. Release speed never enters into it. So a quick flick coasts, a slow careful pan
 * that ends with the same flick stops dead, and the room's behavior reads as arbitrary, which is
 * what the user reported. The two constants that shape that fling, {@code MAX_INITIAL_VELOCITY}
 * 1000 and {@code SCROLL_INERTIA_MILLIS} 1500, are plain literals in the recognizer's
 * {@code <clinit>} with no system property behind either, so the trigger cannot be corrected from
 * outside. The recognizer's inertia is therefore switched off and this replaces it.
 *
 * <p><b>It triggers on release speed instead.</b> That is the whole point of the exercise: the
 * browser the original app runs in flings on release velocity, which is why the original feels
 * consistent with no pan code of its own. Lift while still moving and the room coasts; slow to a
 * stop and lift, and it stays put, because by then the sampled speed is near zero.
 *
 * <p><b>The sampling window is what makes "slow to a stop" work.</b> Velocity is measured over the
 * last {@link #SAMPLE_WINDOW_MS} of movement rather than over the gesture, so a finger that
 * traveled the width of the room and then rested for a moment before lifting reports the rest,
 * not the travel. Averaging the whole gesture would fling hardest after the longest drag, which is
 * precisely backwards.
 */
public final class PanMomentum {

    /**
     * How much of the recent past decides the release speed, in milliseconds.
     *
     * <p>Short enough that a pause before lifting kills the fling, long enough to survive the gaps
     * between touch batches. Android delivers moves at roughly the display's refresh rate, so 100 ms
     * is about six samples on a 60 Hz panel and three on a slow frame, which is still an average
     * rather than one noisy difference.
     */
    static final double SAMPLE_WINDOW_MS = 100;

    /**
     * Below this speed at release, in pixels per second, nothing coasts.
     *
     * <p>The dead band that makes a deliberate pan end where it was put. Set at roughly the speed a
     * finger is still moving at when someone means to stop, so that placing the room somewhere
     * precise is not undone by a few pixels of drift.
     */
    static final double MIN_FLING_SPEED = 120;

    /**
     * The ceiling on release speed, in pixels per second.
     *
     * <p>A ceiling rather than a scale: a hard flick and a very hard flick coast the same distance.
     * Without it a fast swipe on a large room throws the view somewhere nobody aimed at, and the
     * distance traveled is {@code speed / -ln(SPEED_LEFT_AFTER_A_SECOND)}, so it grows with speed
     * without limit.
     */
    static final double MAX_FLING_SPEED = 4000;

    /**
     * The fraction of the coasting speed still left one second later.
     *
     * <p>The decay is exponential, {@code v(t) = v0 * SPEED_LEFT_AFTER_A_SECOND^t}, which is the
     * shape a browser's fling has and the shape that reads as friction rather than as an animation
     * running out. At 0.04 a fling from the {@link #MAX_FLING_SPEED} ceiling runs about 1.4 s
     * before it drops under {@link #STOP_SPEED}, which lands within a tenth of a second of the
     * platform's own {@code SCROLL_INERTIA_MILLIS} of 1500. That agreement is a check, not a
     * target: the complaint was never the length of the coast, only when it happened at all.
     */
    static final double SPEED_LEFT_AFTER_A_SECOND = 0.04;

    /**
     * The speed at which coasting ends, in pixels per second.
     *
     * <p>An exponential never reaches zero, so something has to say when it is over. Twenty pixels
     * a second is under one pixel per frame, which is where movement stops being visible.
     */
    static final double STOP_SPEED = 20;

    /** How many movement samples are kept. Enough to cover the window at any plausible frame rate. */
    private static final int SAMPLE_COUNT = 16;

    private final double[] sampleTime = new double[SAMPLE_COUNT];
    private final double[] sampleDx = new double[SAMPLE_COUNT];
    private final double[] sampleDy = new double[SAMPLE_COUNT];

    /** Where the next sample goes. Wraps, so the array is a ring and nothing is ever shifted. */
    private int nextSample;

    /** How many samples have been written since {@link #began}, capped at {@link #SAMPLE_COUNT}. */
    private int samplesHeld;

    private double velocityX;
    private double velocityY;
    private double lastStepTime;
    private boolean coasting;

    /** Starts a fresh gesture, discarding whatever the last one left behind. */
    public void began() {
        nextSample = 0;
        samplesHeld = 0;
        velocityX = 0;
        velocityY = 0;
        coasting = false;
    }

    /**
     * Records one piece of movement.
     *
     * @param dx     how far the room moved horizontally since the previous event, in pixels
     * @param dy     how far it moved vertically
     * @param timeMs when, in milliseconds on any monotonic scale
     */
    public void moved(double dx, double dy, double timeMs) {
        sampleTime[nextSample] = timeMs;
        sampleDx[nextSample] = dx;
        sampleDy[nextSample] = dy;
        nextSample = (nextSample + 1) % SAMPLE_COUNT;
        if (samplesHeld < SAMPLE_COUNT) {
            samplesHeld++;
        }
    }

    /**
     * The finger lifted. Works out whether the room should coast, and how fast.
     *
     * @param timeMs when the lift happened
     * @return true if a coast started, false if the gesture ended slowly enough to stop dead
     */
    public boolean released(double timeMs) {
        double windowStart = timeMs - SAMPLE_WINDOW_MS;
        double earliest = timeMs;

        for (int i = 0; i < samplesHeld; i++) {
            if (sampleTime[i] < windowStart) {
                continue;
            }
            earliest = Math.min(earliest, sampleTime[i]);
        }

        // No samples in the window leaves earliest at now, so this catches an empty window as well
        // as a zero span. There is deliberately no "fewer than two samples" guard beside it: one
        // sample is its own earliest, nothing is later than it, so the movement below sums to zero
        // and a lone sample cannot fling by construction. That guard was written, its mutation
        // survived, and the survival is what proved it unreachable.
        double span = timeMs - earliest;
        if (span <= 0) {
            coasting = false;
            return false;
        }

        // ⚠ THE EARLIEST SAMPLE CONTRIBUTES ITS TIME AND NOT ITS MOVEMENT, and leaving it in was a
        // real bug caught by the test that panned the same speed over three frames and over eighty.
        // A sample records how far the room moved in the interval ENDING at its timestamp, so that
        // movement happened before the span starts. Counting it divided n frames of travel by the
        // n-1 frames between the first sample and the lift, which reads 50% fast on a three-frame
        // flick and 17% fast on a seven-frame one. Both now measure the 1500 px/s they really are.
        double totalDx = 0;
        double totalDy = 0;
        for (int i = 0; i < samplesHeld; i++) {
            if (sampleTime[i] > earliest) {
                totalDx += sampleDx[i];
                totalDy += sampleDy[i];
            }
        }

        double vx = totalDx / span * 1000;
        double vy = totalDy / span * 1000;
        double speed = Math.hypot(vx, vy);
        if (speed < MIN_FLING_SPEED) {
            coasting = false;
            return false;
        }

        // Clamped on the resultant, never per axis. Clamping each axis would let a diagonal fling
        // travel MAX_FLING_SPEED * sqrt(2) and would also bend its direction toward the diagonal.
        if (speed > MAX_FLING_SPEED) {
            vx *= MAX_FLING_SPEED / speed;
            vy *= MAX_FLING_SPEED / speed;
        }

        velocityX = vx;
        velocityY = vy;
        lastStepTime = timeMs;
        coasting = true;
        return true;
    }

    /**
     * Advances the coast to a moment in time and says how far the room should move for it.
     *
     * <p>Returns the movement for this frame in {@code into[0]} and {@code into[1]}, rather than
     * allocating, because this runs on every frame of every fling.
     *
     * @param timeMs now
     * @param into   a two-element array that receives the horizontal and vertical movement
     * @return true while still coasting, false once it has stopped
     */
    public boolean advance(double timeMs, double[] into) {
        into[0] = 0;
        into[1] = 0;
        if (!coasting) {
            return false;
        }

        double elapsed = (timeMs - lastStepTime) / 1000.0;
        if (elapsed <= 0) {
            // A frame that arrives at or before the last one must not integrate backwards. It can
            // happen when a fling is released inside the same millisecond a frame is served.
            return true;
        }
        lastStepTime = timeMs;

        double decay = Math.pow(SPEED_LEFT_AFTER_A_SECOND, elapsed);

        // The exact integral of the decay across this frame, not velocity times elapsed. At 60 Hz
        // the difference is small, but a dropped frame makes the rectangle rule overshoot by
        // several pixels, and the overshoot is worst exactly when the room is moving fastest.
        double travel = (1 - decay) / -Math.log(SPEED_LEFT_AFTER_A_SECOND);
        into[0] = velocityX * travel;
        into[1] = velocityY * travel;

        velocityX *= decay;
        velocityY *= decay;

        if (Math.hypot(velocityX, velocityY) < STOP_SPEED) {
            coasting = false;
        }
        return coasting;
    }

    /** Stops any coast at once. A new touch means the old fling is no longer what is being asked for. */
    public void stop() {
        coasting = false;
        velocityX = 0;
        velocityY = 0;
    }

    /** Whether the room is currently coasting. */
    public boolean isCoasting() {
        return coasting;
    }

    /** The current horizontal speed in pixels per second, for tests and diagnostics. */
    double velocityX() {
        return velocityX;
    }

    /** The current vertical speed in pixels per second, for tests and diagnostics. */
    double velocityY() {
        return velocityY;
    }
}
