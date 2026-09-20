package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.ui.TouchGesture.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What one finger on a box turns out to have meant.
 *
 * <p>Every time here is a number handed in, so a hold that takes a second and a half in the app
 * takes no time at all to test, and the awkward boundaries, which are the part most likely to be
 * got wrong, can be tested exactly rather than approximately.
 */
class TouchGestureTest {

    private final TouchGesture gesture = new TouchGesture();

    @Test
    @DisplayName("down and quickly up without moving is a tap")
    void aQuickStillTouchIsATap() {
        gesture.began(100, 100, 0);
        assertEquals(Outcome.TAP, gesture.ended(100));
    }

    @Test
    @DisplayName("a second quick tap soon after the first is a double tap")
    void twoQuickTapsAreADoubleTap() {
        gesture.began(100, 100, 0);
        assertEquals(Outcome.TAP, gesture.ended(100));

        gesture.began(100, 100, 200);
        assertEquals(Outcome.DOUBLE_TAP, gesture.ended(300));
    }

    @Test
    @DisplayName("a second tap too long after the first is just another tap")
    void aLateSecondTapIsAnotherTap() {
        gesture.began(100, 100, 0);
        assertEquals(Outcome.TAP, gesture.ended(100));

        // Lifted 400 ms after the first tap's lift (the boundary itself, which is excluded).
        gesture.began(100, 100, 400);
        assertEquals(Outcome.TAP, gesture.ended(500));
    }

    @Test
    @DisplayName("a third tap cannot pair with the tap that already paired")
    void aDoubleTapConsumesItsFirstHalf() {
        gesture.began(100, 100, 0);
        gesture.ended(50);
        gesture.began(100, 100, 100);
        assertEquals(Outcome.DOUBLE_TAP, gesture.ended(150));

        // Without clearing the pending tap, this third one would pair again and open the dialog a
        // second time from a single extra touch.
        gesture.began(100, 100, 200);
        assertEquals(Outcome.TAP, gesture.ended(250));
    }

    @Test
    @DisplayName("moving past six pixels makes it a drag")
    void movingFarEnoughIsADrag() {
        gesture.began(100, 100, 0);
        assertFalse(gesture.moved(106, 100), "six pixels is not past six pixels");
        assertTrue(gesture.moved(107, 100));
        assertEquals(Outcome.DRAG, gesture.ended(50));
    }

    @Test
    @DisplayName("a drag stays a drag even if the finger comes back")
    void crossingTheThresholdIsPermanent() {
        gesture.began(100, 100, 0);
        gesture.moved(120, 100);
        gesture.moved(100, 100);

        // Bound on both sides: returning to the start must not turn a drag back into a tap, or a
        // box could be dragged out and back and then open a dialog on release.
        assertTrue(gesture.moved(100, 100));
        assertEquals(Outcome.DRAG, gesture.ended(50));
    }

    @Test
    @DisplayName("movement counts on either axis, and either direction")
    void theThresholdIsNotOneSided() {
        for (double[] delta : new double[][] {{7, 0}, {-7, 0}, {0, 7}, {0, -7}}) {
            TouchGesture fresh = new TouchGesture();
            fresh.began(100, 100, 0);
            assertTrue(fresh.moved(100 + delta[0], 100 + delta[1]),
                    "should be a drag for delta " + delta[0] + "," + delta[1]);
        }
    }

    @Test
    @DisplayName("held still for a second and a half, it deletes")
    void holdingStillFires() {
        gesture.began(100, 100, 0);

        assertFalse(gesture.holdFired(1499));
        assertTrue(gesture.holdFired(1500));
    }

    @Test
    @DisplayName("the hold fires once, however often it is asked")
    void theHoldFiresOnlyOnce() {
        // A per-frame timer asks this sixty times a second. Firing every time would delete the box
        // and then keep trying to delete it.
        gesture.began(100, 100, 0);

        assertTrue(gesture.holdFired(1500));
        assertFalse(gesture.holdFired(1501));
        assertFalse(gesture.holdFired(3000));
    }

    @Test
    @DisplayName("moving cancels the hold, permanently")
    void movingCancelsTheHold() {
        gesture.began(100, 100, 0);
        gesture.moved(120, 100);

        assertFalse(gesture.holdFired(1500));
        assertFalse(gesture.holdFired(9000));
    }

    @Test
    @DisplayName("lifting after a delete does nothing else")
    void theLiftAfterADeleteIsSpent() {
        gesture.began(100, 100, 0);
        assertTrue(gesture.holdFired(1500));

        // The box is already gone. A TAP here would try to show a tooltip for it.
        assertEquals(Outcome.NOTHING, gesture.ended(1600));
    }

    @Test
    @DisplayName("held too long to be a tap but released before the delete does nothing")
    void theDeadBandDoesNothing() {
        // This is the escape hatch: the way to call off a delete you started by accident is to
        // lift before the ring finishes. It only works because lifting here does nothing.
        gesture.began(100, 100, 0);
        assertEquals(Outcome.NOTHING, gesture.ended(600));

        TouchGesture other = new TouchGesture();
        other.began(100, 100, 0);
        assertEquals(Outcome.NOTHING, other.ended(1499));
    }

    @Test
    @DisplayName("599 milliseconds is still a tap, 600 is not")
    void theTapBoundaryIsExact() {
        gesture.began(100, 100, 0);
        assertEquals(Outcome.TAP, gesture.ended(599));

        TouchGesture other = new TouchGesture();
        other.began(100, 100, 0);
        assertEquals(Outcome.NOTHING, other.ended(600));
    }

    @Test
    @DisplayName("a press in the dead band cannot become half of a double tap")
    void theDeadBandClearsThePendingTap() {
        gesture.began(100, 100, 0);
        assertEquals(Outcome.TAP, gesture.ended(50));

        gesture.began(100, 100, 100);
        assertEquals(Outcome.NOTHING, gesture.ended(800));   // dead band, clears the pending tap

        gesture.began(100, 100, 900);
        assertEquals(Outcome.TAP, gesture.ended(950), "should not pair with the tap before last");
    }

    @Test
    @DisplayName("a drag cannot become half of a double tap")
    void draggingClearsThePendingTap() {
        gesture.began(100, 100, 0);
        assertEquals(Outcome.TAP, gesture.ended(50));

        gesture.began(100, 100, 100);
        gesture.moved(200, 100);
        assertEquals(Outcome.DRAG, gesture.ended(150));

        gesture.began(100, 100, 200);
        assertEquals(Outcome.TAP, gesture.ended(250));
    }

    @Test
    @DisplayName("a canceled gesture means nothing and leaves nothing behind")
    void cancelingClearsEverything() {
        gesture.began(100, 100, 0);
        gesture.canceled();

        assertEquals(Outcome.NOTHING, gesture.ended(50));
        assertFalse(gesture.holdFired(2000));

        // And it must not have left a pending tap that a later touch could pair with.
        gesture.began(100, 100, 100);
        assertEquals(Outcome.TAP, gesture.ended(150));
    }

    @Test
    @DisplayName("nothing happens without a finger down")
    void eventsWithoutATouchAreIgnored() {
        assertFalse(gesture.moved(500, 500));
        assertFalse(gesture.holdFired(9000));
        assertEquals(Outcome.NOTHING, gesture.ended(9000));
        assertFalse(gesture.isHolding());
    }

    @Test
    @DisplayName("the warning ring runs only while a delete is still possible")
    void theRingTracksThePossibilityOfADelete() {
        assertFalse(gesture.isHolding(), "nothing is held before a finger arrives");

        gesture.began(100, 100, 0);
        assertTrue(gesture.isHolding());

        gesture.moved(200, 100);
        assertFalse(gesture.isHolding(), "a drag is not a pending delete");

        TouchGesture other = new TouchGesture();
        other.began(100, 100, 0);
        other.holdFired(1500);
        assertFalse(other.isHolding(), "the delete already happened");

        TouchGesture third = new TouchGesture();
        third.began(100, 100, 0);
        third.ended(50);
        assertFalse(third.isHolding(), "the finger has lifted");
    }

    @Test
    @DisplayName("the ring's fade runs from nothing to full across the whole 1500 ms")
    void theRingFillsOverTheWholeHold() {
        gesture.began(100, 100, 0);

        assertEquals(0, gesture.holdProgress(0), 1e-9, "nothing has elapsed yet");
        assertEquals(0.5, gesture.holdProgress(750), 1e-9, "half way through the hold");
        assertEquals(1, gesture.holdProgress(1500), 1e-9, "the moment the delete is due");
    }

    @Test
    @DisplayName("the fade stops at full rather than carrying on past it")
    void theRingDoesNotOverfill() {
        gesture.began(100, 100, 0);

        // Only reachable if the caller polls before asking holdFired, which a per-frame timer
        // does. Without the clamp the ring's opacity would climb past 1 and JavaFX would take it
        // silently, so nothing on screen would show the arithmetic had gone wrong.
        assertEquals(1, gesture.holdProgress(9000), 1e-9);
    }

    @Test
    @DisplayName("the ring is empty whenever it has no business being on screen")
    void theRingIsEmptyWhenNothingIsPending() {
        assertEquals(0, gesture.holdProgress(750), 1e-9, "no finger is down");

        gesture.began(100, 100, 0);
        gesture.moved(200, 100);
        assertEquals(0, gesture.holdProgress(750), 1e-9, "a drag cancels the delete");

        TouchGesture fired = new TouchGesture();
        fired.began(100, 100, 0);
        fired.holdFired(1500);
        assertEquals(0, fired.holdProgress(1600), 1e-9, "the delete already happened");

        TouchGesture lifted = new TouchGesture();
        lifted.began(100, 100, 0);
        lifted.ended(50);
        assertEquals(0, lifted.holdProgress(750), 1e-9, "the finger has lifted");
    }
}
