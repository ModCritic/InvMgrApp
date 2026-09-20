package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.threed.CameraPose;
import com.modcritic.invmgr.threed.Transitions;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Landing the way in or the way out the moment you touch a control: CLAUDE.md §5.5 <b>D-11</b>.
 *
 * <p><b>Every case here is written so it cannot pass on a build where the skip does nothing.</b>
 * That is not automatic: "the view is open and the camera is at eye height" is true of an ordinary
 * entry too, just later. So each one waits for a stretch of time that is comfortably shorter than
 * the animation it claims to have cut short, and the difference is large: 3220 ms of entry against
 * about 800 ms of waiting, so it is not a race that a slow container could win by accident.
 *
 * <p>The class drives the real 3D button and the real return button, like {@code ThreeDEntryTest},
 * because the whole feature is about what happens between pressing one and arriving.
 */
class ThreeDSkipTest extends ApplicationTest {

    private static final int REFERENCE_WIDTH = 2560;
    private static final int REFERENCE_HEIGHT = 1440;

    /** The whole way in, plus slack. Nothing here should ever need to wait this long. */
    private static final long FULL_ENTRY_MS = (long) (Transitions.BARS_LEAVE_LAYOUT_MS
            + Transitions.OVERLAY_FADE_MS + Transitions.DESCENT_DELAY_MS
            + Transitions.DESCENT_MS) + 1000;

    /**
     * What a skipped entry is allowed. The chrome slide is deliberately NOT collapsed (the user's
     * decision), so it is that, plus the room build, plus a frame or two, plus slack for a slow
     * container. Still less than a third of {@link #FULL_ENTRY_MS}.
     */
    private static final long SKIPPED_ENTRY_MS = (long) Transitions.BARS_LEAVE_LAYOUT_MS + 700;

    private App app;
    private Scene scene;

    @Override
    public void start(Stage stage) {
        app = new App();
        app.start(stage);
        stage.setMaximized(false);
        stage.setWidth(REFERENCE_WIDTH);
        stage.setHeight(REFERENCE_HEIGHT);
        stage.setX(0);
        stage.setY(0);
        scene = stage.getScene();
    }

    private void settle(long millis) {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            WaitForAsyncUtils.sleep(20, java.util.concurrent.TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Presses the 3D button, waits, then taps a key, and reports how long the whole thing took. */
    private long enterAndSkipAfter(long waitMs, KeyCode key) {
        long started = System.currentTimeMillis();
        interact(() -> app.topBar().threeDButtonNode().fire());
        settle(waitMs);
        type(key);
        settle(SKIPPED_ENTRY_MS);
        return System.currentTimeMillis() - started;
    }

    @Test
    @DisplayName("a request made before the camera has even started is remembered, not dropped")
    void skippingBeforeTheFlightExists() {
        // 200 ms in, App is still sliding the chrome away and View3D has not been asked to fade
        // anything yet; there is no flight to interrupt and no fade to stop. The request has
        // to be remembered rather than dropped, which is the `entering` flag's whole job.
        //
        // ASKED DIRECTLY RATHER THAN THROUGH A KEY, and that is not shortcutting. TestFX's
        // type() costs about half a second, measured, so a test that "presses W at 200 ms"
        // actually presses it somewhere past 700; by then openWithDescent has run and set
        // `entering` itself, and the latch in prepare() is never exercised at all. The sweep
        // proved it: deleting that latch left this test green. Which key reaches skipTransition
        // is covered by the cases below; this one is about the 370 ms window where no animation
        // exists yet, and the only way to hit it reliably is to ask on the spot.
        long[] clockStartedAt = new long[1];

        // Both in ONE block, so the skip is asked for in the very same pulse the button was
        // pressed in. A settle(200) between them was tried first and the guard below caught it:
        // TestFX's own overhead meant "200 ms" arrived past 370, by which time openWithDescent
        // had run and there was an ordinary fade to interrupt after all. This way the moment is
        // exact: prepare() has run, the chrome is sliding, and nothing is animating in here.
        //
        // The clock also starts INSIDE the block, after the button has been fired. That keeps
        // the room build out of the measurement: it is about 300 ms on the first entry of a run,
        // it happens synchronously inside fire(), and it is not skippable; including it would
        // mean the bound below had to be loose enough to be useless.
        interact(() -> {
            app.topBar().threeDButtonNode().fire();
            assertFalse(app.view3d().isAnimating(),
                    "nothing should be animating yet: this test would be pointless if it were, "
                            + "since there would be something ordinary to interrupt");
            app.view3d().skipTransition();
            clockStartedAt[0] = System.currentTimeMillis();
        });

        long landedAfter = waitUntilLanded();

        assertTrue(app.view3d().isOpen(), "should be in the room");
        assertFalse(app.view3d().isAnimating(), "and not still flying");
        assertEquals(CameraPose.EYE_HEIGHT_FT, app.view3d().camera().y, 0.001,
                "standing at eye height, exactly where the descent would have put us");
        assertEquals(0, app.view3d().camera().pitch, 0.001, "and looking level");

        long elapsed = landedAfter - clockStartedAt[0];

        // The bound is tight on purpose, and this is the second thing the sweep taught here.
        // A loose one ("shorter than the whole 3220 ms entry") passes even when the latched
        // request is ignored by openWithDescent, because the pause still fires at 990 ms and the
        // descent still lands on its first frame. The two outcomes are 370 ms and 990 ms, so the
        // bound has to sit between them rather than merely below the unskipped total.
        assertTrue(elapsed < 700,
                "landed " + elapsed + " ms after the button, which is not the ~370 ms a collapsed "
                        + "entry takes: the latched request was ignored somewhere");
    }

    /**
     * Waits until the camera is actually standing in the room, and says when that happened.
     *
     * <p>The condition is the camera's <b>height</b>, not "open and not animating". That pair is
     * already true the instant {@code prepare} has run and before anything has started moving;
     * the first version of this returned immediately with the camera still ten feet up, which is
     * a poll that answers before the thing it is waiting for has begun.
     */
    private long waitUntilLanded() {
        long deadline = System.currentTimeMillis() + FULL_ENTRY_MS;
        while (System.currentTimeMillis() < deadline) {
            WaitForAsyncUtils.sleep(10, java.util.concurrent.TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
            if (app.view3d().isOpen() && !app.view3d().isAnimating()
                    && Math.abs(app.view3d().camera().y - CameraPose.EYE_HEIGHT_FT) < 1e-6) {
                return System.currentTimeMillis();
            }
        }
        return System.currentTimeMillis();
    }

    @Test
    @DisplayName("skipping during the fade cuts the fade short there and then")
    void skippingDuringTheFadeCollapsesItImmediately() {
        // The fade and the pause together are 620 ms in the middle of the way in, and asking to
        // skip inside them must stop both and fall immediately, not merely set a flag and let
        // the pause run out.
        //
        // DRIVEN THROUGH View3D DIRECTLY, and it took two failures to work out that it had to be.
        // The wall-clock version pressed the button and settled 420 ms; a probe showed the app
        // was by then at opacity 1, animating, and 4 ft into the descent; TestFX's own overhead
        // is larger than the entire 990 ms lead-in, so that test was silently a duplicate of
        // "skip during the descent" and could never reach the code it was named after. The sweep
        // is what exposed it: deleting the collapse outright left it green.
        //
        // So the fade and the pause are started here and interrupted in the next pulse, which is
        // the only way to be inside a 620 ms window from a test harness with half-second
        // latency. What App does before this point has its own tests.
        interact(() -> {
            app.view3d().prepare(app.state(), scene);
            app.view3d().openWithDescent(app.state(), scene, () -> { });
            assertEquals(0, app.view3d().getOpacity(), 0.001,
                    "the fade should have just started from nothing: if this is already 1 the "
                            + "window being tested has been missed again");
            assertTrue(app.view3d().camera().y > CameraPose.EYE_HEIGHT_FT + 1,
                    "and the camera should still be overhead, with nothing having fallen yet");
            app.view3d().skipTransition();
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1, app.view3d().getOpacity(), 0.001,
                "the picture must be solid the moment the skip is asked for, not when the fade "
                        + "would have finished on its own");

        // And you are already standing in the room, within the single pulse that
        // waitForFxEvents just ran. The first draft asserted "the descent must have started" and
        // failed, because it had started AND finished, which is the actual behavior and a
        // stronger claim: the fade, the pause and the 2230 ms fall all collapse into one frame.
        //
        // Without the collapse this fails, which is the point: the pause would still be sitting
        // there with 570 ms to run and the camera still overhead.
        assertEquals(CameraPose.EYE_HEIGHT_FT, app.view3d().camera().y, 0.001,
                "the camera should already be standing in the room, in one frame");
        assertFalse(app.view3d().isAnimating(), "with nothing left to animate");
    }

    @Test
    @DisplayName("a key pressed during the way in lands it, through the real key handlers")
    void aRealKeyPressSkips() {
        long elapsed = enterAndSkipAfter(450, KeyCode.D);

        assertTrue(app.view3d().isOpen());
        assertFalse(app.view3d().isAnimating());
        assertEquals(CameraPose.EYE_HEIGHT_FT, app.view3d().camera().y, 0.001);
        assertEquals(1, app.view3d().getOpacity(), 0.001,
                "the picture must be fully solid, not caught half-faded");
        assertTrue(elapsed < FULL_ENTRY_MS - 800, "took " + elapsed + " ms");
    }

    @Test
    @DisplayName("a key pressed mid-descent lands you in the room")
    void skippingDuringTheDescent() {
        // 1400 ms in: well past the 990 ms of chrome, fade and pause, so the camera is genuinely
        // falling and there is a flight to cut short.
        long elapsed = enterAndSkipAfter(1400, KeyCode.SPACE);

        assertTrue(app.view3d().isOpen());
        assertFalse(app.view3d().isAnimating());
        assertEquals(CameraPose.EYE_HEIGHT_FT, app.view3d().camera().y, 0.001);
        assertTrue(elapsed < FULL_ENTRY_MS - 500, "took " + elapsed + " ms");
    }

    @Test
    @DisplayName("a skipped landing is bit-for-bit the landing you would have got anyway")
    void theSkippedPoseIsIdenticalToTheNaturalOne() {
        // THIS is the test that says there is no second arrival path. The skip does not "finish
        // things off"; it makes the flight's next frame compute a fraction of 1 and lets the
        // ordinary ending run. If that were ever replaced by code that set the pose by hand, the
        // two would drift and this is what would notice.
        interact(() -> app.topBar().threeDButtonNode().fire());
        settle(FULL_ENTRY_MS);
        CameraPose natural = app.view3d().camera().copy();

        interact(() -> app.view3d().returnButton().fire());
        settle((long) Transitions.ASCENT_MS + 800);

        enterAndSkipAfter(300, KeyCode.W);
        CameraPose skipped = app.view3d().camera().copy();

        assertEquals(natural.x, skipped.x, 1e-9);
        assertEquals(natural.y, skipped.y, 1e-9);
        assertEquals(natural.z, skipped.z, 1e-9);
        assertEquals(natural.yaw, skipped.yaw, 1e-9);
        assertEquals(natural.pitch, skipped.pitch, 1e-9);
    }

    @Test
    @DisplayName("the key that skipped also walks: you are moving, not merely standing")
    void theWalkLoopIsLiveTheInstantYouLand() {
        interact(() -> app.topBar().threeDButtonNode().fire());
        settle(300);

        // Held down rather than tapped, so the same press both lands the descent and walks.
        press(KeyCode.W);
        settle(SKIPPED_ENTRY_MS + 300);
        double z = app.view3d().camera().z;
        release(KeyCode.W);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(app.view3d().isOpen());
        // The room is 10 ft long, so a landed camera starts at z = 5. Anything meaningfully north
        // of that means the walk loop was running, not merely that the camera arrived.
        assertTrue(z < 4.5, "should have walked north after landing; z = " + z);
    }

    @Test
    @DisplayName("a mouse press lands the way in, but sweeping the pointer across it does not")
    void theMouseSkipsOnlyWhenItMeansIt() {
        // The user's own decision: watching the camera fall while the pointer happens to be moving
        // across the window must not cut it short.
        interact(() -> app.topBar().threeDButtonNode().fire());
        settle(1100);

        // ONE move, and it is deliberately one.
        //
        // The first version swept the pointer twelve times and failed. Measured rather than
        // guessed at: each TestFX move costs about 500 ms even on Motion.DIRECT, so twelve of
        // them ran 2045 ms (longer than the descent had left) and the camera landed on its own
        // while the test was still moving the mouse. Nothing had skipped anything. A test that
        // slow cannot tell "did not skip" from "ran out of animation", and the same slowness
        // would make it pass on a build where bare movement DID skip.
        moveTo(new javafx.geometry.Point2D(1600, 900), org.testfx.robot.Motion.DIRECT);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(app.view3d().isAnimating(),
                "bare pointer movement must not land the descent");
        // And the direct evidence, which does not depend on a flag: the camera is still up in the
        // air on its way down, rather than standing at eye height.
        assertTrue(app.view3d().camera().y > CameraPose.EYE_HEIGHT_FT + 0.5,
                "the camera should still be descending; y = " + app.view3d().camera().y);

        // A press, though, is deliberate.
        press(javafx.scene.input.MouseButton.PRIMARY);
        release(javafx.scene.input.MouseButton.PRIMARY);
        settle(400);

        assertFalse(app.view3d().isAnimating(), "a mouse press should have landed it");
        assertEquals(CameraPose.EYE_HEIGHT_FT, app.view3d().camera().y, 0.001);
    }

    @Test
    @DisplayName("the way out can be skipped too, and the 2D view comes back correct")
    void skippingTheAscentRestoresEverything() {
        // The one that would break if the skip had its own arrival path: leaving 3D restores Fit,
        // the scroll position, and the chrome, and every bit of that lives in App's callback.
        interact(() -> app.canvas().setFitMode(false));
        WaitForAsyncUtils.waitForFxEvents();

        interact(() -> app.topBar().threeDButtonNode().fire());
        settle(FULL_ENTRY_MS);

        interact(() -> app.view3d().returnButton().fire());
        settle(300);
        long skippedAt = System.currentTimeMillis();
        type(KeyCode.W);
        long climbAfterTheSkip = waitForLanding() - skippedAt;
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(app.view3d().isOpen(), "the 3D view should be closed");
        assertFalse(app.view3d().isAnimating());
        assertEquals(1, app.view3d().getOpacity(), 0.001,
                "the view must be left ready to open again, not stuck transparent");
        assertEquals("Back to 2D.", app.statusBar().text(),
                "the arrival callback must have run in full");
        assertFalse(app.canvas().isFitMode(), "Fit must be restored to what it was before");

        for (javafx.scene.Node piece : new javafx.scene.Node[] {
            app.topBar(), app.statusBar(), app.sliderDrawer(), app.listPanel()}) {
            assertTrue(piece.isManaged(), "chrome must be back in the layout");
            assertTrue(piece.isVisible(), "and visible");
        }

        // ⚠ Measure the climb, NOT the test's own waiting. This assertion used to start its
        // clock before the return button and stop it after settle(300) + settle(900), so 1200 ms
        // of deliberate sleeping sat inside a 1920 ms budget and left about 500 ms of headroom
        // for everything else. It failed intermittently at 1965 to 2077 ms on a busy machine,
        // which looked like a bug in the skip and was the test timing itself. See the memory
        // note "a test that measures its own overhead".
        assertTrue(climbAfterTheSkip < Transitions.ASCENT_MS / 2,
                "the climb ran " + climbAfterTheSkip + " ms after the skip, which is not clearly "
                        + "shorter than the " + Transitions.ASCENT_MS + " ms ascent: the skip "
                        + "did nothing");
    }

    /**
     * Blocks until the view stops animating and reports the wall-clock moment it stopped.
     *
     * <p>Polls rather than sleeping a fixed amount, which is the whole point: a fixed sleep
     * measures the sleep. Fails rather than returning a wrong answer if nothing lands, because a
     * timing assertion fed a deadline that quietly expired is worse than no assertion.
     */
    private long waitForLanding() {
        long deadline = System.currentTimeMillis() + (long) Transitions.ASCENT_MS + 2000;
        while (System.currentTimeMillis() < deadline) {
            WaitForAsyncUtils.waitForFxEvents();
            if (!WaitForAsyncUtils.waitForAsyncFx(5000, () -> app.view3d().isAnimating())) {
                return System.currentTimeMillis();
            }
            WaitForAsyncUtils.sleep(10, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        throw new AssertionError("the view never stopped animating");
    }

    @Test
    @DisplayName("the pointer comes back as the climb starts, not when it lands")
    void theExitClimbGivesThePointerBackImmediately() {
        // Parity finding 7, decided by the user 2026-09-11. The original releases the lock as the
        // exit starts (original line 3002); this used to hold it until detach() at the END of the
        // 1920 ms ascent, so the cursor stayed hidden for two seconds and then reappeared in the
        // middle of the window instead of where it was grabbed.
        interact(() -> app.topBar().threeDButtonNode().fire());
        settle(FULL_ENTRY_MS);

        click();
        assertTrue(app.view3d().controls().isCapturing(),
                "the test never captured the pointer, so it cannot see what it is testing");

        interact(() -> app.view3d().returnButton().fire());
        WaitForAsyncUtils.waitForFxEvents();

        // Both halves matter. Without the second, a release that happened because the climb had
        // already finished would pass, which is exactly the behavior this replaced.
        assertFalse(app.view3d().controls().isCapturing(),
                "the pointer must be back the moment the climb starts");
        assertTrue(app.view3d().isAnimating(),
                "and the climb must still be running, or this proves nothing");

        waitForLanding();
    }

    /** A press and release in the middle of the 3D view, which is what toggles the capture. */
    private void click() {
        for (javafx.event.EventType<javafx.scene.input.MouseEvent> type
                : java.util.List.of(javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                                    javafx.scene.input.MouseEvent.MOUSE_RELEASED)) {
            interact(() -> app.view3d().getScene().getRoot().fireEvent(
                    new javafx.scene.input.MouseEvent(type, 400, 300, 400, 300,
                            javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false,
                            false, false, false, null)));
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    @Test
    @DisplayName("a skipped way in does not leave the way out already skipped")
    void aSkipDoesNotLeakIntoTheNextJourney() {
        // The flag is cleared in the flight's arrival branch and NOT in stopFlight, which is the
        // subtle half. If it leaked, the ascent would land on its first frame with nobody asking.
        enterAndSkipAfter(250, KeyCode.W);
        assertTrue(app.view3d().isOpen());

        // The request must not outlive the journey it was made for. Asserted directly, because
        // the sweep showed that deleting the clear in fly()'s arrival changed nothing visible:
        // the clear at the top of closeWithAscent happened to cover the path below, so the two
        // were hiding each other's absence.
        assertFalse(app.view3d().isSkipRequested(),
                "a landed flight must leave no request behind");

        long started = System.currentTimeMillis();
        interact(() -> app.view3d().returnButton().fire());
        settle(400);

        assertTrue(app.view3d().isAnimating(),
                "the ascent should still be running 400 ms in, not already finished");

        settle((long) Transitions.ASCENT_MS + 600);
        assertFalse(app.view3d().isOpen());
        assertTrue(System.currentTimeMillis() - started > Transitions.ASCENT_MS * 0.8,
                "the ascent took its time, as nobody asked to skip it");
    }

    @Test
    @DisplayName("Escape lands the way in first, and a second Escape leaves")
    void escapeLandsThenLeaves() {
        // CLAUDE.md §5.5 D-12, and deliberately not a special case: Escape counts as input like
        // any other key, so during the way in it lands you, and leaveThreeD refuses while a
        // transition is running. One rule, no exception, and the second press works almost at
        // once because the first removed the wait.
        interact(() -> app.topBar().threeDButtonNode().fire());
        settle(1400);
        assertTrue(app.view3d().isAnimating(), "the camera should still be falling");

        type(KeyCode.ESCAPE);
        settle(500);

        assertTrue(app.view3d().isOpen(), "the first Escape lands you rather than leaving");
        assertFalse(app.view3d().isAnimating());
        assertEquals(CameraPose.EYE_HEIGHT_FT, app.view3d().camera().y, 0.001);

        type(KeyCode.ESCAPE);
        settle((long) Transitions.ASCENT_MS + 800);

        assertFalse(app.view3d().isOpen(), "the second Escape leaves");
        assertEquals("Back to 2D.", app.statusBar().text());
    }

    @Test
    @DisplayName("the controls hint appears on landing and takes itself away")
    void theControlsHintShowsAndFades() {
        interact(() -> app.topBar().threeDButtonNode().fire());
        settle(FULL_ENTRY_MS);

        javafx.scene.control.Label hint = app.view3d().controlsHintNode();
        assertTrue(hint.isVisible(), "the hint should be up after landing");
        assertTrue(hint.getOpacity() > 0.5, "and faded in");
        assertTrue(hint.getText().contains("WASD") && hint.getText().contains("Esc"),
                "it should actually name the controls: " + hint.getText());
        assertTrue(hint.isMouseTransparent(), "and never swallow a click meant for the room");

        settle((long) (View3D.HINT_HOLD_MS + Transitions.RETURN_BUTTON_FADE_MS + 600));
        assertFalse(hint.isVisible(), "and should have taken itself away again");
    }

    @Test
    @DisplayName("leaving takes the hint with it rather than leaving it over the flat room")
    void theHintGoesWhenYouLeave() {
        interact(() -> app.topBar().threeDButtonNode().fire());
        settle(FULL_ENTRY_MS);
        assertTrue(app.view3d().controlsHintNode().isVisible());

        interact(() -> app.view3d().returnButton().fire());
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(app.view3d().controlsHintNode().isVisible(),
                "the hint must go the moment the way out starts, not ride the ascent up");
    }

    @Test
    @DisplayName("asking to skip when nothing is happening does nothing at all")
    void skippingOutsideATransitionIsHarmless() {
        interact(() -> app.view3d().skipTransition());
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(app.view3d().isOpen(), "skipping with no 3D view up must not open one");

        interact(() -> app.topBar().threeDButtonNode().fire());
        settle(FULL_ENTRY_MS);
        CameraPose landed = app.view3d().camera().copy();

        interact(() -> app.view3d().skipTransition());
        settle(200);

        assertEquals(landed.y, app.view3d().camera().y, 0.001,
                "skipping while simply standing in the room must not move anything");
        assertTrue(app.view3d().isOpen());
    }
}
