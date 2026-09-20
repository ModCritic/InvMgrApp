package com.modcritic.invmgr.threed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.Room;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One finger, two fingers, and the difference between a look and a question.
 *
 * <p>Written the way {@code WalkTest} is: every movement case names an axis and a sign, because a
 * camera that pans west when it should pan east covers exactly as much ground.
 *
 * <p><b>Everything here runs without a screen and without a second hand.</b> That is the whole
 * reason the recognizer is a plain object: a real pinch cannot be performed in this container, and
 * waiting half a second to find out whether something was a tap would put five hundred milliseconds
 * into the suite for every case that asks.
 */
class WalkGestureTest {

    private static final double TOLERANCE = 1e-9;

    private static Room room() {
        return new Room(20, 30, 8);
    }

    private static CameraPose middle() {
        return new CameraPose(10, CameraPose.EYE_HEIGHT_FT, 15, 0, 0);
    }

    private static double[] at(double... xy) {
        return xy;
    }

    // ------------------------------------------------------------------ looking

    @Test
    @DisplayName("one finger dragged right turns the camera right, at the touch sensitivity")
    void oneFingerLooks() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(100, 200), 0);
        assertEquals(WalkGesture.Mode.LOOK, gesture.mode());
        gesture.moved(at(150, 200), cam, room());

        // ⚠ The literal 0.3, not 50 * LOOK_SENS_RAD_PER_PX. A test that computes what it expects
        // out of the constant it is checking agrees with whatever that constant says: the sweep
        // dropped the sensitivity to the mouse's 0.0022 and this case stayed green, because both
        // sides moved together. Same lesson as D-9's color test, which writes the hex out.
        assertEquals(0.3, cam.yaw, TOLERANCE, "50 px at 0.006 rad/px is 0.3 rad");
        assertTrue(cam.yaw > 0, "dragging right must turn toward east");
        assertEquals(0, cam.pitch, TOLERANCE, "a horizontal drag must not tilt the view");
    }

    @Test
    @DisplayName("dragging a finger down the screen looks down")
    void oneFingerPitchIsInverted() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(100, 200), 0);
        gesture.moved(at(100, 260), cam, room());

        assertEquals(-0.36, cam.pitch, TOLERANCE, "60 px down at 0.006 rad/px is -0.36 rad");
        assertTrue(cam.pitch < 0, "a downward drag must lower the view, not raise it");
    }

    @Test
    @DisplayName("the camera does not move while you are only looking")
    void lookingDoesNotWalk() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(100, 200), 0);
        gesture.moved(at(300, 400), cam, room());

        assertEquals(10, cam.x, TOLERANCE);
        assertEquals(15, cam.z, TOLERANCE);
        assertEquals(CameraPose.EYE_HEIGHT_FT, cam.y, TOLERANCE);
    }

    // ------------------------------------------------------------------ tapping

    @Test
    @DisplayName("a quick still touch is a tap, reported where the finger went down")
    void aQuickStillTouchIsATap() {
        WalkGesture gesture = new WalkGesture();

        gesture.began(at(120, 240), 1000);
        WalkGesture.Tap tap = gesture.ended(at(), 1300);

        assertNotNull(tap, "300 ms and no movement is a tap");
        assertEquals(120, tap.x(), TOLERANCE);
        assertEquals(240, tap.y(), TOLERANCE);
        assertEquals(WalkGesture.Mode.NONE, gesture.mode(), "and nothing is down afterwards");
    }

    @Test
    @DisplayName("the tap names where the finger landed, not where it left")
    void theTapIsTheStartingPoint() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(120, 240), 0);
        gesture.moved(at(126, 244), cam, room());          // 7.2 px, still inside the tolerance
        WalkGesture.Tap tap = gesture.ended(at(), 200);

        assertNotNull(tap);
        assertEquals(120, tap.x(), TOLERANCE, "the box you aimed at is the one under where you put "
                + "your finger, not under where it had drifted to");
        assertEquals(240, tap.y(), TOLERANCE);
    }

    @Test
    @DisplayName("held too long it is a look, however still the finger was")
    void aSlowTouchIsNotATap() {
        WalkGesture gesture = new WalkGesture();

        gesture.began(at(120, 240), 1000);
        assertNull(gesture.ended(at(), 1000 + WalkGesture.TAP_MAX_MS),
                "exactly 500 ms is already too slow");

        gesture.began(at(120, 240), 2000);
        assertNotNull(gesture.ended(at(), 2000 + WalkGesture.TAP_MAX_MS - 1),
                "and a millisecond under it is not");
    }

    @Test
    @DisplayName("moved more than ten pixels it is a look, however quick")
    void aMovedTouchIsNotATap() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(100, 100), 0);
        gesture.moved(at(112, 100), cam, room());          // 12 px, past the tolerance
        assertFalse(gesture.isTapPending());
        assertNull(gesture.ended(at(), 50), "quick, but it was a look");

        // And the boundary from the other side, so the tolerance cannot be quietly widened.
        gesture.began(at(100, 100), 0);
        gesture.moved(at(109, 100), cam, room());
        assertTrue(gesture.isTapPending(), "9 px is still a tap");
        assertNotNull(gesture.ended(at(), 50));
    }

    @Test
    @DisplayName("a finger that creeps is still a finger that moved")
    void theTapToleranceIsMeasuredFromWhereItLanded() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(100, 100), 0);
        for (int i = 1; i <= 20; i++) {
            gesture.moved(at(100 + i, 100), cam, room());   // one pixel at a time, to 20
        }

        // Comparing consecutive positions instead of measuring from the start would never see a
        // single pixel exceed ten, and a slow drag right across the room would end up naming a box.
        assertFalse(gesture.isTapPending(), "twenty pixels is twenty pixels however slowly");
        assertNull(gesture.ended(at(), 100));
    }

    @Test
    @DisplayName("a second finger cancels the tap the moment it lands")
    void aSecondFingerRulesOutATap() {
        WalkGesture gesture = new WalkGesture();

        gesture.began(at(100, 100), 0);
        assertTrue(gesture.isTapPending());
        gesture.began(at(100, 100, 200, 100), 10);

        assertFalse(gesture.isTapPending(), "two fingers is never a tap");
        assertEquals(WalkGesture.Mode.TWO_FINGERS, gesture.mode());
        assertNull(gesture.ended(at(200, 100), 20),
                "and lifting one of them quickly must not name a box");
    }

    // ------------------------------------------------------------------ panning

    @Test
    @DisplayName("two fingers slid right pan the camera east, and slid up they lift it")
    void twoFingersPan() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(100, 300, 200, 300), 0);
        gesture.moved(at(140, 300, 240, 300), cam, room());     // midpoint 150 -> 190

        assertEquals(10 + 40 * WalkGesture.PAN_SENS_FT_PER_PX, cam.x, TOLERANCE,
                "40 px right at 0.03 ft/px is 1.2 ft east");
        assertEquals(15, cam.z, TOLERANCE, "a sideways pan at yaw 0 must not move you north");
        assertEquals(0, cam.yaw, TOLERANCE, "panning must not turn the camera");
    }

    @Test
    @DisplayName("dragging two fingers up the screen raises the camera")
    void panningUpRises() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(100, 300, 200, 300), 0);
        gesture.moved(at(100, 250, 200, 250), cam, room());     // 50 px up the screen

        // Screen y counts downward, so up the screen is a negative dy and the sign is inverted
        // once. The failure this rules out is the room sinking when you drag it up.
        assertEquals(CameraPose.EYE_HEIGHT_FT + 50 * WalkGesture.PAN_SENS_FT_PER_PX, cam.y,
                TOLERANCE);
    }

    @Test
    @DisplayName("panning follows the yaw, so right means right of where you are looking")
    void panFollowsTheYaw() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();
        cam.yaw = Math.PI / 2;                                  // facing east

        gesture.began(at(100, 300, 200, 300), 0);
        gesture.moved(at(130, 300, 230, 300), cam, room());

        assertEquals(15 + 30 * WalkGesture.PAN_SENS_FT_PER_PX, cam.z, TOLERANCE,
                "right of east is south");
        assertEquals(10, cam.x, TOLERANCE);
    }

    // ------------------------------------------------------- the pinch, D-15

    @Test
    @DisplayName("spreading two fingers moves you forward and squeezing them moves you back")
    void spreadingDolliesForward() {
        WalkGesture spread = new WalkGesture();
        CameraPose forward = middle();
        spread.began(at(150, 300, 250, 300), 0);                // 100 px apart
        spread.moved(at(100, 300, 300, 300), forward, room());  // 200 px apart, same midpoint

        assertEquals(15 - 100 * WalkGesture.PINCH_SENS_FT_PER_PX, forward.z, TOLERANCE,
                "100 px more separation at 0.015 ft/px is 1.5 ft north");
        assertEquals(10, forward.x, TOLERANCE, "an even spread must not slide you sideways");
        assertEquals(CameraPose.EYE_HEIGHT_FT, forward.y, TOLERANCE, "nor lift you");

        WalkGesture squeeze = new WalkGesture();
        CameraPose back = middle();
        squeeze.began(at(100, 300, 300, 300), 0);
        squeeze.moved(at(150, 300, 250, 300), back, room());

        assertEquals(15 + 100 * WalkGesture.PINCH_SENS_FT_PER_PX, back.z, TOLERANCE,
                "squeezing is the exact opposite");
    }

    @Test
    @DisplayName("a spread while looking at the ceiling does not lift you off the floor")
    void theDollyIgnoresThePitch() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();
        cam.pitch = 1.5;                                        // very nearly straight up

        gesture.began(at(150, 300, 250, 300), 0);
        gesture.moved(at(100, 300, 300, 300), cam, room());

        // D-15's second sentence, checked through the gesture rather than only through Walk.dolly,
        // because "flat forward" has to survive the wiring as well as the arithmetic.
        assertEquals(CameraPose.EYE_HEIGHT_FT, cam.y, TOLERANCE);
        assertEquals(15 - 1.5, cam.z, TOLERANCE);
    }

    @Test
    @DisplayName("the same movement of the hand pinches exactly as far as it pans")
    void thePinchIsHalfSensitiveBecauseSeparationChangesTwiceAsFast() {
        // The reason PINCH_SENS is half PAN_SENS, stated as the thing it is for. Two fingers each
        // moving 25 px in the SAME direction slide the midpoint 25 px. The same two fingers each
        // moving 25 px in OPPOSITE directions change their separation by 50. If both constants were
        // 0.03 the second would move the camera twice as far for the same effort.
        WalkGesture panning = new WalkGesture();
        CameraPose panned = middle();
        panning.began(at(100, 300, 200, 300), 0);
        panning.moved(at(125, 300, 225, 300), panned, room());

        WalkGesture pinching = new WalkGesture();
        CameraPose pinched = middle();
        pinching.began(at(100, 300, 200, 300), 0);
        pinching.moved(at(75, 300, 225, 300), pinched, room());

        double panDistance = Math.abs(panned.x - 10);
        double pinchDistance = Math.abs(pinched.z - 15);

        assertEquals(0.75, panDistance, TOLERANCE, "25 px of midpoint at 0.03 ft/px");
        assertEquals(panDistance, pinchDistance, TOLERANCE,
                "the same 25 px of finger travel must move the camera the same distance either way");
    }

    // ------------------------------------- one job at a time, the 2026-08-11 amendment to D-15

    @Test
    @DisplayName("sliding and spreading at once does the bigger of the two and only that")
    void theBiggerOfTheTwoClaimsTheFingers() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(100, 300, 200, 300), 0);
        // The pair moves 40 px right AND spreads by 100: 100 -> 90, 200 -> 290.
        gesture.moved(at(90, 300, 290, 300), cam, room());

        // Until 2026-08-11 both of these happened and the camera traveled diagonally. The user
        // asked for one at a time; the separation moved further than the midpoint, so it wins.
        assertEquals(WalkGesture.Job.PINCH, gesture.job());
        assertEquals(15 - 100 * WalkGesture.PINCH_SENS_FT_PER_PX, cam.z, TOLERANCE,
                "the separation went from 100 to 200, so the dolly happens");
        assertEquals(10, cam.x, TOLERANCE,
                "and the 40 px of midpoint does NOT also pan: this is the reported bug");
    }

    @Test
    @DisplayName("a one-handed pinch is a pinch however far it goes, because it always wins by two")
    void aOneHandedPinchIsAlwaysAPinch() {
        // The reason TWO_FINGER_DECIDE_PX is not a balance point, stated as the thing it is for.
        // A thumb rests and an index finger does the moving, which is how a pinch is actually
        // performed on a phone: the separation changes by the whole distance and the midpoint by
        // exactly half of it, at every instant. So the pinch crosses first by a factor of two
        // whatever the threshold is, and changing the number cannot change the answer.
        for (double travel : new double[] { 13, 40, 400 }) {
            WalkGesture gesture = new WalkGesture();
            CameraPose cam = middle();

            gesture.began(at(100, 300, 200, 300), 0);
            gesture.moved(at(100, 300, 200 + travel, 300), cam, room());

            assertEquals(WalkGesture.Job.PINCH, gesture.job(),
                    travel + " px of one finger must read as a pinch, not a slide");
            assertEquals(15 - travel * WalkGesture.PINCH_SENS_FT_PER_PX, cam.z, TOLERANCE,
                    "and it moves you forward by the whole separation change");
            assertEquals(10, cam.x, TOLERANCE,
                    "with no sideways drift at all: the forty-five degrees the user reported");
        }
    }

    @Test
    @DisplayName("a slide with fingers that splay a little is still a slide")
    void aSlideThatSplaysIsStillASlide() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(100, 300, 200, 300), 0);
        // Both fingers travel right (40 and 55), so the pair slides 47.5 and splays by 15.
        gesture.moved(at(140, 300, 255, 300), cam, room());

        assertEquals(WalkGesture.Job.SLIDE, gesture.job());
        assertEquals(10 + 47.5 * WalkGesture.PAN_SENS_FT_PER_PX, cam.x, TOLERANCE,
                "the hand meant to slide and a wandering thumb must not turn that into a pinch");
        assertEquals(15, cam.z, TOLERANCE, "and the 15 px of splay moves you nowhere");
    }

    @Test
    @DisplayName("nothing happens at all until the hand has said which it meant")
    void neitherHappensUntilTheHandHasDecided() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(100, 300, 200, 300), 0);
        gesture.moved(at(105, 300, 205, 300), cam, room());      // 5 px of slide, no separation

        assertEquals(WalkGesture.Job.UNDECIDED, gesture.job());
        assertEquals(10, cam.x, TOLERANCE, "5 px is under the threshold, so the camera waits");
        assertEquals(15, cam.z, TOLERANCE);
        assertEquals(CameraPose.EYE_HEIGHT_FT, cam.y, TOLERANCE);
    }

    @Test
    @DisplayName("the pixels spent deciding are not thrown away")
    void thePixelsSpentDecidingAreNotThrownAway() {
        WalkGesture creeping = new WalkGesture();
        CameraPose crept = middle();

        creeping.began(at(100, 300, 200, 300), 0);
        // Four calls of five pixels each. The first two are under the threshold; the third crosses
        // it at fifteen, and the camera must move by the whole fifteen rather than by the five that
        // happened to tip it over.
        creeping.moved(at(105, 300, 205, 300), crept, room());
        creeping.moved(at(110, 300, 210, 300), crept, room());
        creeping.moved(at(115, 300, 215, 300), crept, room());
        creeping.moved(at(120, 300, 220, 300), crept, room());

        WalkGesture inOneGo = new WalkGesture();
        CameraPose swept = middle();
        inOneGo.began(at(100, 300, 200, 300), 0);
        inOneGo.moved(at(120, 300, 220, 300), swept, room());

        assertEquals(10 + 20 * WalkGesture.PAN_SENS_FT_PER_PX, crept.x, TOLERANCE,
                "twenty pixels of finger is twenty pixels of pan however many calls it arrived in");
        assertEquals(swept.x, crept.x, TOLERANCE,
                "so creeping there and sweeping there must land in the same place");
    }

    @Test
    @DisplayName("once the fingers have a job they keep it until one of them lifts")
    void theJobIsHeldForTheWholeGesture() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(100, 300, 200, 300), 0);
        gesture.moved(at(140, 300, 240, 300), cam, room());      // 40 px slide: latches to SLIDE
        assertEquals(WalkGesture.Job.SLIDE, gesture.job());

        double afterTheSlide = cam.z;
        gesture.moved(at(90, 300, 290, 300), cam, room());       // now spread hard, by 150 px

        assertEquals(WalkGesture.Job.SLIDE, gesture.job(), "the job must not change mid-gesture");
        assertEquals(afterTheSlide, cam.z, TOLERANCE,
                "so a spread inside a slide moves you nowhere forward");
    }

    @Test
    @DisplayName("a hand that slides exactly as much as it spreads gets the original's gesture")
    void aTieGoesToTheSlide() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(100, 300, 200, 300), 0);
        // One finger travels 30 px right and the other 10: the midpoint slides 20 and the pair
        // closes by 20. Dead level, and the tie-break is a decision: the slide is what the
        // original does and the pinch is what D-15 added, so an ambiguous hand gets the original.
        gesture.moved(at(130, 300, 210, 300), cam, room());

        assertEquals(WalkGesture.Job.SLIDE, gesture.job());
        assertEquals(10 + 20 * WalkGesture.PAN_SENS_FT_PER_PX, cam.x, TOLERANCE);
        assertEquals(15, cam.z, TOLERANCE, "and the 20 px it closed by moves you nowhere forward");
    }

    @Test
    @DisplayName("a canceled pair is not still holding a job")
    void cancelingForgetsTheJob() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(100, 300, 200, 300), 0);
        gesture.moved(at(140, 300, 240, 300), cam, room());
        assertEquals(WalkGesture.Job.SLIDE, gesture.job(), "latched, so there is something to clear");

        gesture.canceled();

        assertEquals(WalkGesture.Job.UNDECIDED, gesture.job(),
                "mode() says nothing is down, so job() must not still be reporting a slide");
    }

    @Test
    @DisplayName("lifting a finger asks the question again")
    void liftingAFingerAsksAgain() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(0, 300, 100, 300, 400, 300), 0);
        gesture.moved(at(0, 300, 100, 300, 400, 300), cam, room());
        gesture.moved(at(40, 300, 140, 300, 440, 300), cam, room());
        assertEquals(WalkGesture.Job.SLIDE, gesture.job(), "three fingers slid: a slide");

        gesture.ended(at(140, 300, 440, 300), 100);

        assertEquals(WalkGesture.Job.UNDECIDED, gesture.job(),
                "a pair that has just lost a member is a new pair and has not said what it is for");
    }

    // ------------------------------------------------ fingers arriving and leaving

    @Test
    @DisplayName("a movement reported with the wrong number of fingers is ignored, not guessed at")
    void aMismatchedCountDoesNothing() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(100, 100), 0);                          // looking
        // ⚠ The first finger has MOVED, 60 px, and that is the point of the case. Reporting the two
        // fingers with the first one still at its original position could never see this: the
        // sweep loosened the guard from "exactly one finger" to "at least one", and a first finger
        // that had not moved computed a turn of zero either way, so the case passed while testing
        // nothing.
        gesture.moved(at(160, 100, 300, 100), cam, room());      // two fingers reported

        assertEquals(0, cam.yaw, TOLERANCE, "a two-finger report must not drive a one-finger look");
        assertEquals(10, cam.x, TOLERANCE, "nor a pan the gesture never entered");
    }

    @Test
    @DisplayName("lifting to one finger carries on looking from where that finger is")
    void liftingToOneFingerResumesLooking() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(100, 300, 500, 300), 0);
        gesture.ended(at(500, 300), 100);                        // the first finger lifted

        assertEquals(WalkGesture.Mode.LOOK, gesture.mode());

        gesture.moved(at(510, 300), cam, room());

        // Ten pixels of look, not four hundred: the remaining finger's position is taken as the
        // new starting point rather than being measured against the pair's old midpoint.
        assertEquals(10 * WalkGesture.LOOK_SENS_RAD_PER_PX, cam.yaw, TOLERANCE);
    }

    @Test
    @DisplayName("lifting one of three fingers does not teleport the camera")
    void liftingToTwoFingersReseedsTheMidpoint() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(0, 300, 100, 300, 400, 300), 0);        // midpoint 50, separation 100
        gesture.ended(at(100, 300, 400, 300), 100);              // the first lifted: mid 250, sep 300

        assertEquals(WalkGesture.Mode.TWO_FINGERS, gesture.mode());

        gesture.moved(at(100, 300, 400, 300), cam, room());      // nobody moved

        // The original leaves the stale midpoint in place here, and the next movement subtracts
        // 50 from 250 and slides the camera six feet east, then adds 200 px of "spread" and moves
        // it three feet north, from a hand that did not move at all.
        assertEquals(10, cam.x, TOLERANCE, "fingers that did not move must not move the camera");
        assertEquals(15, cam.z, TOLERANCE);
    }

    @Test
    @DisplayName("a joystick-only press leaves a look already under way alone")
    void anEmptyPressDoesNotClobberTheGesture() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(100, 100), 0);
        gesture.began(at(), 10);            // a thumb landed on the stick; nothing here owns it

        assertEquals(WalkGesture.Mode.LOOK, gesture.mode(), "the look survives");
        gesture.moved(at(120, 100), cam, room());
        assertEquals(20 * WalkGesture.LOOK_SENS_RAD_PER_PX, cam.yaw, TOLERANCE,
                "and it is still measured from where the finger actually was");
    }

    @Test
    @DisplayName("a canceled gesture is not a tap and moves nothing afterwards")
    void cancelingClearsEverything() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(100, 100), 0);
        gesture.canceled();

        assertEquals(WalkGesture.Mode.NONE, gesture.mode());
        assertFalse(gesture.isTapPending());
        assertNull(gesture.ended(at(), 50), "a canceled touch must not become a tap on the way out");

        gesture.moved(at(200, 100), cam, room());
        assertEquals(0, cam.yaw, TOLERANCE);
    }

    @Test
    @DisplayName("panning is stopped by the same walls walking is")
    void aPanIsClamped() {
        WalkGesture gesture = new WalkGesture();
        CameraPose cam = middle();

        gesture.began(at(4000, 300, 4100, 300), 0);
        gesture.moved(at(0, 300, 100, 300), cam, room());        // 4000 px left, 120 ft

        assertEquals(-CameraPose.ROOM_SLACK_FT, cam.x, TOLERANCE,
                "a long pan must stop at the slack rather than leave the world");
    }
}
