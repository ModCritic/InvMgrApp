package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The room's coasting after a pan, tested as arithmetic rather than through a window.
 *
 * <p><b>The test that matters most is {@link #aPanThatSlowsToAStopDoesNotCoast}</b>, because it is
 * the user's complaint stated as a rule. The platform's own recognizer decides by asking how long
 * the whole gesture lasted, so it flings after a quick flick and stops dead after a careful pan
 * that ended with the identical movement. Every case here asks about the speed at the moment of
 * release and nothing else.
 *
 * <p>None of this needs a phone, which is the point of keeping it out of {@link RoomCanvasView}.
 * The recognizer that generates the real events exists only on Android, so anything living in the
 * view can be verified there and nowhere else; the arithmetic can be driven at any frame rate from
 * here.
 */
class PanMomentumTest {

    /** A frame at 60 Hz in milliseconds, which is what a finger gets sampled at on most phones. */
    private static final double FRAME_MS = 1000.0 / 60;

    /** Feeds a straight drag at a constant speed, returning the time it ended at. */
    private static double drag(PanMomentum pan, double pxPerFrame, int frames, double startMs) {
        double now = startMs;
        for (int i = 0; i < frames; i++) {
            now += FRAME_MS;
            pan.moved(pxPerFrame, 0, now);
        }
        return now;
    }

    /** Runs a coast to its end, returning how far the room traveled in total. */
    private static double[] coastToRest(PanMomentum pan, double startMs) {
        double[] step = new double[2];
        double totalX = 0;
        double totalY = 0;
        double now = startMs;
        for (int i = 0; i < 600 && pan.isCoasting(); i++) {
            now += FRAME_MS;
            pan.advance(now, step);
            totalX += step[0];
            totalY += step[1];
        }
        return new double[] {totalX, totalY, now - startMs};
    }

    // ------------------------------------------------------------------ the reported bug

    @Test
    @DisplayName("a pan that slows to a stop does not coast")
    void aPanThatSlowsToAStopDoesNotCoast() {
        PanMomentum pan = new PanMomentum();
        pan.began();

        // A long, fast travel across the room, then the hand settles for four frames before
        // lifting. The platform would fling this if the whole thing happened inside 300 ms and
        // would not otherwise, which is the inconsistency being fixed.
        // Eight settling frames is 133 ms, deliberately longer than the 100 ms window. At four
        // frames the window still reaches back into the fast travel and the room does fling, which
        // is correct behavior rather than a bug: a hand that only just stopped has only just
        // stopped. The rule being pinned is that settling for longer than the window kills it.
        double now = drag(pan, 30, 20, 0);
        for (int i = 0; i < 8; i++) {
            now += FRAME_MS;
            pan.moved(0.2, 0, now);
        }

        assertFalse(pan.released(now), "settling before the lift must not fling the room");
        assertFalse(pan.isCoasting(), "and nothing should be coasting afterwards");
    }

    @Test
    @DisplayName("a flick still coasting at the lift does coast")
    void aFlickStillCoastingAtTheLiftDoesCoast() {
        PanMomentum pan = new PanMomentum();
        pan.began();
        double now = drag(pan, 30, 6, 0);

        assertTrue(pan.released(now), "lifting at speed must fling the room");
        assertTrue(pan.isCoasting(), "and it should be coasting");
    }

    @Test
    @DisplayName("the same release speed flings the same, however long the gesture was")
    void theSameReleaseSpeedFlingsTheSameHoweverLongTheGestureWas() {
        // This is the platform's rule stated as its opposite. A three-frame gesture and a
        // eighty-frame one that end at the same speed must produce the same coast; the recognizer
        // would fling only the short one, because its test is the total duration.
        PanMomentum quick = new PanMomentum();
        quick.began();
        double quickEnd = drag(quick, 25, 3, 0);
        assertTrue(quick.released(quickEnd));
        double[] quickRun = coastToRest(quick, quickEnd);

        PanMomentum slow = new PanMomentum();
        slow.began();
        double slowEnd = drag(slow, 25, 80, 0);
        assertTrue(slow.released(slowEnd));
        double[] slowRun = coastToRest(slow, slowEnd);

        assertEquals(quickRun[0], slowRun[0], 0.5,
                "a long pan and a short one released at the same speed must coast the same distance");
    }

    // ------------------------------------------------------------------ the sampling window

    @Test
    @DisplayName("only the last hundred milliseconds decide the speed")
    void onlyTheLastHundredMillisecondsDecideTheSpeed() {
        PanMomentum pan = new PanMomentum();
        pan.began();

        // Fast for a while, then genuinely slow for longer than the window before lifting. If the
        // whole gesture were averaged this would fling hard, which is backwards: the longer you
        // drag, the harder it would throw.
        double now = drag(pan, 40, 10, 0);
        now = drag(pan, 1, 8, now);

        assertFalse(pan.released(now),
                "movement older than the window must not reach the release speed");
    }

    @Test
    @DisplayName("a release with no movement at all flings nothing and stays a number")
    void aReleaseWithNoMovementAtAllFlingsNothingAndStaysANumber() {
        PanMomentum pan = new PanMomentum();
        pan.began();

        // A finger that landed on the room and lifted without moving, which is every tap on bare
        // floor. There are no samples, so the window is empty and the span is zero.
        assertFalse(pan.released(100), "a tap must not fling the room");
        assertFalse(pan.isCoasting());

        // ⚠ AND IT MUST STILL BE A NUMBER. Without the span guard the movement sums to zero over a
        // zero span, and 0.0/0.0 is NaN. Every comparison against NaN is false, so the "too slow to
        // fling" test and the speed ceiling BOTH fall through and the room coasts at NaN, which
        // moves it nowhere describable. Asserting only "did not fling" misses this: the mutation
        // that weakened the guard survived a suite that checked exactly that and nothing else.
        assertFalse(Double.isNaN(pan.velocityX()), "the horizontal speed must not be NaN");
        assertFalse(Double.isNaN(pan.velocityY()), "the vertical speed must not be NaN");
    }

    @Test
    @DisplayName("a single sample cannot set a speed")
    void aSingleSampleCannotSetASpeed() {
        PanMomentum pan = new PanMomentum();
        pan.began();
        pan.moved(200, 0, 50);

        // One sample has no span to divide by. Dividing by the window instead would invent a speed
        // the finger never reached, and a one-event gesture is exactly a tap that grazed the room.
        assertFalse(pan.released(60), "one movement is not enough to measure a speed from");
    }

    // ------------------------------------------------------------------ the limits

    @Test
    @DisplayName("a gentle pan is under the fling threshold and a firm one is over it")
    void aGentlePanIsUnderTheFlingThresholdAndAFirmOneIsOverIt() {
        // Bounded on both sides deliberately. A threshold asserted from one direction only passes
        // just as well when nothing ever flings.
        PanMomentum gentle = new PanMomentum();
        gentle.began();
        double gentleEnd = drag(gentle, 1.5, 8, 0);      // 90 px/s, under MIN_FLING_SPEED
        assertFalse(gentle.released(gentleEnd), "90 px/s should not coast");

        PanMomentum firm = new PanMomentum();
        firm.began();
        double firmEnd = drag(firm, 4, 8, 0);            // 240 px/s, over it
        assertTrue(firm.released(firmEnd), "240 px/s should coast");
    }

    @Test
    @DisplayName("a diagonal flick is clamped on its resultant, not per axis")
    void aDiagonalFlickIsClampedOnItsResultantNotPerAxis() {
        PanMomentum pan = new PanMomentum();
        pan.began();
        double now = 0;
        // Far beyond the ceiling on both axes at once.
        for (int i = 0; i < 8; i++) {
            now += FRAME_MS;
            pan.moved(300, 300, now);
        }
        assertTrue(pan.released(now));

        double speed = Math.hypot(pan.velocityX(), pan.velocityY());
        assertEquals(PanMomentum.MAX_FLING_SPEED, speed, 1.0,
                "the resultant speed is what gets clamped");

        // Clamping each axis to the ceiling would leave the resultant at ceiling * sqrt(2).
        assertTrue(speed < PanMomentum.MAX_FLING_SPEED * 1.2,
                "a diagonal must not travel faster than a straight flick of the same ceiling");
        assertEquals(pan.velocityX(), pan.velocityY(), 1.0,
                "and clamping must not bend the direction");
    }

    // ------------------------------------------------------------------ the coast itself

    @Test
    @DisplayName("the coast slows down and stops on its own")
    void theCoastSlowsDownAndStopsOnItsOwn() {
        PanMomentum pan = new PanMomentum();
        pan.began();
        double end = drag(pan, 40, 8, 0);
        assertTrue(pan.released(end));

        // Both frames the SAME length. Comparing a 16 ms frame against a 333 ms one would compare
        // the integral over twenty times as much time and the later one wins, which says nothing
        // about whether the room is slowing down.
        double[] step = new double[2];
        double now = end + FRAME_MS;
        pan.advance(now, step);
        double firstFrame = step[0];

        double laterFrame = firstFrame;
        for (int i = 0; i < 20; i++) {
            now += FRAME_MS;
            pan.advance(now, step);
            laterFrame = step[0];
        }

        assertTrue(laterFrame < firstFrame,
                "a later frame must move the room less than an earlier one of the same length");

        double[] run = coastToRest(pan, end);
        assertFalse(pan.isCoasting(), "the coast has to end by itself");

        // Bounded on both sides. A coast that ended instantly would satisfy an upper bound alone,
        // and one that ran for a minute would satisfy a lower bound alone.
        assertTrue(run[2] > 200, "a real fling should coast for more than 200 ms, was " + run[2]);
        assertTrue(run[2] < 2500, "and should be over within 2.5 s, was " + run[2]);
    }

    @Test
    @DisplayName("the distance coasted matches the closed form of its decay")
    void theDistanceCoastedMatchesTheClosedFormOfItsDecay() {
        PanMomentum pan = new PanMomentum();
        pan.began();
        double end = drag(pan, 25, 8, 0);
        assertTrue(pan.released(end));
        double v0 = pan.velocityX();

        double[] run = coastToRest(pan, end);

        // Integrating v0 * k^t until the speed drops under STOP_SPEED gives exactly
        // (v0 - STOP_SPEED) / -ln(k). Summing velocity * frameLength instead overshoots it, and by
        // more the longer a frame runs, so a dropped frame throws the room further than the decay
        // says it should. Nothing else here would notice: the coast's DURATION comes from the
        // velocity decay, which is right either way.
        double expected = (v0 - PanMomentum.STOP_SPEED)
                / -Math.log(PanMomentum.SPEED_LEFT_AFTER_A_SECOND);
        assertEquals(expected, run[0], expected * 0.02,
                "the coast should travel the integral of its own decay");
    }

    @Test
    @DisplayName("a dropped frame coasts the same distance as smooth frames")
    void aDroppedFrameCoastsTheSameDistanceAsSmoothFrames() {
        // The same fling served at 60 Hz and at a stuttering 10 Hz must land in the same place.
        // This is the case the rectangle rule gets wrong and the integral gets right.
        double smooth = coastDistanceAtFrameLength(FRAME_MS);
        double stuttering = coastDistanceAtFrameLength(FRAME_MS * 6);

        assertEquals(smooth, stuttering, smooth * 0.02,
                "frame rate must not change where a fling ends up");
    }

    /** Flings at a fixed speed and coasts it out with frames of a given length. */
    private static double coastDistanceAtFrameLength(double frameMs) {
        PanMomentum pan = new PanMomentum();
        pan.began();
        double end = drag(pan, 25, 8, 0);
        pan.released(end);

        double[] step = new double[2];
        double total = 0;
        double now = end;
        for (int i = 0; i < 2000 && pan.isCoasting(); i++) {
            now += frameMs;
            pan.advance(now, step);
            total += step[0];
        }
        return total;
    }

    @Test
    @DisplayName("the coast keeps the direction it was released in")
    void theCoastKeepsTheDirectionItWasReleasedIn() {
        PanMomentum pan = new PanMomentum();
        pan.began();
        double now = 0;
        for (int i = 0; i < 8; i++) {
            now += FRAME_MS;
            pan.moved(-20, 10, now);
        }
        assertTrue(pan.released(now));

        double[] run = coastToRest(pan, now);
        assertTrue(run[0] < 0, "a leftward flick must coast left, went " + run[0]);
        assertTrue(run[1] > 0, "an upward-value flick must keep its sign, went " + run[1]);
        assertEquals(-2.0, run[0] / run[1], 0.1, "and must hold its angle while it slows");
    }

    @Test
    @DisplayName("a frame that arrives no later than the last one moves nothing")
    void aFrameThatArrivesNoLaterThanTheLastOneMovesNothing() {
        PanMomentum pan = new PanMomentum();
        pan.began();
        double end = drag(pan, 30, 8, 0);
        assertTrue(pan.released(end));

        double[] step = new double[2];
        // Released and stepped inside the same millisecond, which happens when a fling lands just
        // before a frame is served.
        assertTrue(pan.advance(end, step), "it is still coasting");
        assertEquals(0, step[0], 0, "no time has passed, so nothing moves");
        assertEquals(0, step[1], 0);

        // ⚠ AND A FRAME THAT ARRIVES EARLIER THAN THE LAST ONE. The zero case above passes with or
        // without the guard, because the decay of a zero span is 1 and the integral of it is 0; it
        // was the only case here at first and the mutation that deleted the guard survived it. A
        // NEGATIVE span is the one that bites: the decay of it is greater than 1, so the room
        // moves backwards and the fling speeds up instead of slowing down.
        double speedBefore = Math.hypot(pan.velocityX(), pan.velocityY());
        assertTrue(pan.advance(end - 50, step), "still coasting");
        assertEquals(0, step[0], 0, "a frame from the past must move the room nowhere");
        assertEquals(0, step[1], 0);
        assertEquals(speedBefore, Math.hypot(pan.velocityX(), pan.velocityY()), 0.001,
                "and must not make the fling faster than it was");
    }

    // ------------------------------------------------------------------ housekeeping

    @Test
    @DisplayName("a new touch stops the coast dead")
    void aNewTouchStopsTheCoastDead() {
        PanMomentum pan = new PanMomentum();
        pan.began();
        double end = drag(pan, 40, 8, 0);
        assertTrue(pan.released(end));
        assertTrue(pan.isCoasting());

        pan.stop();

        assertFalse(pan.isCoasting(), "a finger landing means the old fling is not wanted");
        double[] step = new double[2];
        assertFalse(pan.advance(end + FRAME_MS, step));
        assertEquals(0, step[0], 0, "and a stopped coast moves nothing");
    }

    @Test
    @DisplayName("a new gesture does not inherit the last one's speed")
    void aNewGestureDoesNotInheritTheLastOnesSpeed() {
        PanMomentum pan = new PanMomentum();
        pan.began();
        double end = drag(pan, 40, 8, 0);
        assertTrue(pan.released(end));

        // ⚠ THE SECOND GESTURE MUST BE SHORTER THAN THE FIRST, and half a second later would prove
        // nothing. began() resets where the next sample is written but the ring still holds the old
        // ones, so a second gesture that writes as many samples as the first overwrites all of them
        // and a stale entry can never be read. The hole only opens when FEWER are written and the
        // leftovers are recent enough to fall inside the window. The first version of this test
        // panned eight frames after a 500 ms pause, and the mutation that stopped began() clearing
        // the count survived it.
        pan.began();
        double now = end;
        for (int i = 0; i < 3; i++) {
            now += FRAME_MS;
            pan.moved(0.5, 0, now);
        }
        assertFalse(pan.released(now),
                "a slow pan must not fling on the strength of the gesture before it");
    }
}
