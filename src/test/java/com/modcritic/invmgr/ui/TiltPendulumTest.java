package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The lean of the drag ghost, tested as arithmetic rather than through a window.
 *
 * <p>Every number asserted here was measured off the real class before it was written down — see
 * the milestone note in {@code CLAUDE.md}. They are not round numbers and they are not supposed to
 * be; they are the observed behaviour with a little slack, so that a change to the constants in
 * {@link TiltPendulum} shows up here as a failure rather than as a feel nobody can describe.
 *
 * <p><b>The two tests that matter most are the two the old code would fail:</b>
 * {@link #aSlowCrawlDoesNotJitter}, which is the bug the user reported on 2026-08-01, and
 * {@link #steadySpeedHangsStraightWhateverTheSpeed}, which is the difference between leaning with
 * acceleration and leaning with speed. The old rule scored 1.4° of frame-to-frame flicker on the
 * first and a permanent lean on the second.
 */
class TiltPendulumTest {

    /** A frame at 60 Hz, which is what a hand movement gets sampled at on most screens. */
    private static final double FRAME = 1.0 / 60;

    // ------------------------------------------------------------------ the reported bug

    @Test
    @DisplayName("a slow crawl does not jitter")
    void aSlowCrawlDoesNotJitter() {
        // 40 px/s is the speed the user was moving at when they reported the shake. A mouse
        // reports whole pixels, so at 60 Hz that arrives as 1, 1, 0, 1, 1, 0 — and it was that
        // stutter, not the hand, that the old rule was reading.
        double[] track = constantSpeed(120, 40, 120);
        double[] angles = run(track, FRAME);

        double worstJump = 0;
        for (int i = 1; i < angles.length; i++) {
            worstJump = Math.max(worstJump, Math.abs(angles[i] - angles[i - 1]));
        }

        // Measured worst case is 0.129°, all of it in the first few frames as the card leaves
        // rest. A quarter of a degree leaves room for that without leaving room for the failure:
        // the old rule alternated between 0° and 1.4° every single frame on this exact track,
        // which is nearly nine times this bound.
        assertTrue(worstJump < 0.25,
                "a slow crawl should not shake the card; worst frame-to-frame jump was "
                        + String.format("%.3f", worstJump) + "°");
    }

    // ------------------------------------------------------------------ acceleration, not speed

    @Test
    @DisplayName("a steady speed hangs straight, however fast it is")
    void steadySpeedHangsStraightWhateverTheSpeed() {
        // This is the whole point of the change. A carried thing does not care how fast you are
        // going, only how hard you are changing it -- so three very different constant speeds
        // must all end up at the same angle, and that angle must be zero. Under the old rule the
        // three would have come out at roughly 1.4°, 14° and 16°.
        for (double speed : new double[] {40, 400, 1500}) {
            double[] angles = run(constantSpeed(200, speed, 120), FRAME);
            double settled = angles[angles.length - 1];
            assertEquals(0, settled, 0.1,
                    "at a constant " + speed + " px/s the card should hang straight, not lean");
        }
    }

    @Test
    @DisplayName("starting leans one way and stopping leans the other")
    void startingAndStoppingLeanOppositeWays() {
        // A flick: up to 1200 px/s in an eighth of a second, held, then the hand stops dead.
        double[] angles = run(stopAfter(rampTo(1200, 0.125, 30), 30), FRAME);

        double leanWhileSpeedingUp = peakWithin(angles, 0, 30);
        double leanWhileStopping = peakWithin(angles, 30, angles.length);

        // Measured -11.62° into the flick and +9.12° coming out of it. Negative first, because as
        // of 2026-08-01 the card trails the way a hanging thing does rather than the way the
        // original drew it -- see theLeanFollowsTheDirectionOfTheChange.
        assertTrue(leanWhileSpeedingUp < -8,
                "speeding up should lean the card over; got " + leanWhileSpeedingUp);
        assertTrue(leanWhileStopping > 5,
                "stopping should swing it back the other way; got " + leanWhileStopping);

        // And then it settles, rather than staying where it was left. The old rule froze at
        // whatever the last mouse event said and stayed there until the hand moved again.
        assertEquals(0, angles[angles.length - 1], 0.2,
                "the swing should settle once the hand is still");
    }

    @Test
    @DisplayName("the lean follows the direction of the change")
    void theLeanFollowsTheDirectionOfTheChange() {
        // The card trails its own movement, the way anything hanging from your hand does: shove it
        // right and the bottom swings left. This is BACKWARDS from the original app, which tilts
        // clockwise for a rightward move (HTML line 2046) -- so it is a deliberate divergence, made
        // on the user's decision on 2026-08-01 after seeing both. CLAUDE.md §5.5, D-8. Pinned here
        // so that anyone restoring faithfulness to the original trips this rather than shipping it.
        double rightward = peakWithin(run(rampTo(1200, 0.125, 20), FRAME), 0, 20);
        double leftward = peakWithin(run(rampTo(-1200, 0.125, 20), FRAME), 0, 20);
        assertTrue(rightward < 0, "accelerating right should tilt anticlockwise");
        assertTrue(leftward > 0, "accelerating left should tilt clockwise");
    }

    @Test
    @DisplayName("the swing back at the end of a movement stays as gentle as it was asked to be")
    void theSwingBackIsAsGentleAsItWasAskedToBe() {
        // The user asked on 2026-08-01 for this to be about a fifth less aggressive than the first
        // version shipped, which measured 11.28° of swing-back turning at up to 131°/s. Both halves
        // are pinned, because "aggressive" is both how far it goes and how fast it gets there, and
        // the two are set by different constants -- DAMPING_RATIO and SWINGS_PER_SECOND. Tuning one
        // without the other passes half of this test.
        double[] angles = run(stopAfter(rampTo(1200, 0.125, 30), 40), FRAME);

        double swingBack = Math.abs(peakWithin(angles, 30, angles.length));
        double fastestTurn = 0;
        for (int i = 31; i < angles.length; i++) {
            fastestTurn = Math.max(fastestTurn, Math.abs(angles[i] - angles[i - 1]) / FRAME);
        }

        // Measured 9.12° and 108°/s, which are 81% and 82% of what they were. Both bands are
        // two-sided, and the LOWER bound on the rate is not decoration: a fifth gentler was the
        // request, so drifting to a third gentler is as much a regression as not moving at all.
        // Dropping the swing rate to 1.9 Hz -- which reads as the card lagging behind your hand
        // rather than hanging from it -- survived this test until the lower bound was added.
        assertTrue(swingBack > 7.5 && swingBack < 10.5,
                "the swing back should be around 9.1°, a fifth gentler than the 11.3° that was too "
                        + "aggressive; got " + String.format("%.2f", swingBack) + "°");
        assertTrue(fastestTurn > 95 && fastestTurn < 118,
                "and it should get there at around 108°/s -- down from 131, but not so far down "
                        + "that the card lags; got " + String.format("%.0f", fastestTurn) + "°/s");
    }

    // ------------------------------------------------------------------ bounds and stability

    @Test
    @DisplayName("no amount of shaking makes it spin")
    void shakingCannotMakeItRunAway() {
        // A spring driven at its own frequency is the one input that can pump energy in, so this
        // sweeps the shake across and past that frequency rather than testing one convenient rate.
        for (double hz : new double[] {1.5, 2.0, 2.5, 3.0, 4.0, 6.0}) {
            double[] track = new double[300];
            for (int i = 0; i < track.length; i++) {
                track[i] = Math.round(400 * Math.sin(2 * Math.PI * hz * i * FRAME));
            }
            double peak = 0;
            for (double angle : run(track, FRAME)) {
                peak = Math.max(peak, Math.abs(angle));
            }
            // Worst measured across the sweep is 16.71°, at 1.5 Hz -- barely past the 16° the
            // target is clamped to, because the damping raised on 2026-08-01 leaves little room
            // for momentum to carry it further. It was 20.93° before that. See MAX_TILT_DEGREES.
            assertTrue(peak < 17,
                    "shaking at " + hz + " Hz drove the lean to " + String.format("%.2f", peak)
                            + "°, which is past the bound the constants are supposed to hold");
        }
    }

    @Test
    @DisplayName("the same hand movement looks the same on a fast screen and a slow one")
    void theFeelDoesNotDependOnTheFrameRate() {
        // The old rule had no time in it at all -- it read the distance between two mouse events,
        // so halving the frame rate doubled the lean. That is the flaw this test exists for.
        double at30 = peakOfRampAt(30);
        double at60 = peakOfRampAt(60);
        double at144 = peakOfRampAt(144);

        // Measured 11.82, 11.62 and 11.28 -- a spread of 0.54° across a five-fold change in frame
        // rate. One degree holds that without admitting the failure, which is a factor of two.
        assertEquals(at60, at30, 1.0, "a 30 Hz screen should not lean further than a 60 Hz one");
        assertEquals(at60, at144, 1.0, "nor a 144 Hz one less");
    }

    @Test
    @DisplayName("two frames at the same instant do not break it")
    void aZeroLengthFrameIsIgnored() {
        // Dividing by this step would put an infinity into the speed, and from there every later
        // angle is NaN -- a card that vanishes for the rest of the drag rather than an error.
        TiltPendulum pendulum = new TiltPendulum();
        pendulum.reset(100);
        pendulum.step(140, FRAME);
        double before = pendulum.angleDegrees();
        assertEquals(before, pendulum.step(180, 0), 0.0, "a zero-length frame changes nothing");
        assertTrue(Double.isFinite(pendulum.step(200, FRAME)), "and does not poison what follows");
    }

    @Test
    @DisplayName("lifting a second card does not inherit the first one's swing")
    void resetClearsTheSwing() {
        TiltPendulum pendulum = new TiltPendulum();
        pendulum.reset(500);
        for (double x : rampTo(1200, 0.125, 15)) {
            pendulum.step(x, FRAME);
        }
        assertTrue(Math.abs(pendulum.angleDegrees()) > 5, "mid-swing, as the setup for the below");

        pendulum.reset(900);
        assertEquals(0, pendulum.angleDegrees(), 0.0, "a freshly lifted card hangs straight");
        // And the momentum is gone too, not just the angle: without clearing the speed, the first
        // step of the new drag would read the jump from 500 to 900 as an enormous shove.
        assertEquals(0, pendulum.step(900, FRAME), 0.0, "and has no leftover momentum");
    }

    // ------------------------------------------------------------------ helpers

    /** Runs a whole pointer track through a fresh pendulum and collects the angle each frame. */
    private double[] run(double[] track, double step) {
        TiltPendulum pendulum = new TiltPendulum();
        pendulum.reset(track[0]);
        double[] angles = new double[track.length];
        for (int i = 0; i < track.length; i++) {
            angles[i] = pendulum.step(track[i], step);
        }
        return angles;
    }

    /** A pointer moving at a constant speed, rounded to whole pixels the way a mouse reports. */
    private double[] constantSpeed(double from, double pxPerSecond, int frames) {
        double[] track = new double[frames];
        for (int i = 0; i < frames; i++) {
            track[i] = from + Math.round(i * pxPerSecond * FRAME);
        }
        return track;
    }

    /** Speeds up to a top speed over a time and then holds it. Whole pixels throughout. */
    private double[] rampTo(double topSpeed, double overSeconds, int frames) {
        double[] track = new double[frames];
        double x = 500;
        for (int i = 0; i < frames; i++) {
            double elapsed = i * FRAME;
            double speed = elapsed < overSeconds ? topSpeed * (elapsed / overSeconds) : topSpeed;
            x += speed * FRAME;
            track[i] = Math.round(x);
        }
        return track;
    }

    /** The same track, then the hand stops dead. */
    private double[] stopAfter(double[] moving, int stillFrames) {
        double[] track = new double[moving.length + stillFrames];
        System.arraycopy(moving, 0, track, 0, moving.length);
        java.util.Arrays.fill(track, moving.length, track.length, moving[moving.length - 1]);
        return track;
    }

    /** The furthest from straight the card got over a stretch of frames, keeping its sign. */
    private double peakWithin(double[] angles, int from, int to) {
        double peak = 0;
        for (int i = from; i < to; i++) {
            if (Math.abs(angles[i]) > Math.abs(peak)) {
                peak = angles[i];
            }
        }
        return peak;
    }

    /** The same flick, sampled at a given screen rate. */
    private double peakOfRampAt(double framesPerSecond) {
        double step = 1 / framesPerSecond;
        TiltPendulum pendulum = new TiltPendulum();
        pendulum.reset(500);
        double x = 500;
        double peak = 0;
        for (int i = 0; i < (int) (framesPerSecond * 0.6); i++) {
            double elapsed = i * step;
            double speed = elapsed < 0.125 ? 1200 * (elapsed / 0.125) : 1200;
            x += speed * step;
            peak = Math.max(peak, Math.abs(pendulum.step(Math.round(x), step)));
        }
        return peak;
    }
}
