package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.threed.CameraPose;
import com.modcritic.invmgr.threed.Transitions;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Pressing the 3D button, and pressing the one that comes back: <b>through the real controls, in
 * a real window, with the real animations running</b>.
 *
 * <p>This class exists because every other M5.2 test is arithmetic. Those check that the numbers
 * describing the journey are right; this checks that pressing the button actually starts it, that
 * the 2D interface gets out of the way and comes back, and that the room you return to is the room
 * you left. Nothing about the *shape* of the movement is asserted here (a test that watches
 * frames go by cannot see an ease), and nothing about it needs to be, because
 * {@code TransitionsTest} owns that and can ask far sharper questions in a thousandth of the time.
 *
 * <p><b>It drives the button rather than the method behind it.</b> M3.5 shipped a hint fix that
 * could never have worked, green for a week, because its tests called a method the running app
 * never called. So the entry point here is {@code fire()} on the actual button, and the return is
 * a press on the actual overlay button.
 */
class ThreeDEntryTest extends ApplicationTest {

    private static final int REFERENCE_WIDTH = 2560;
    private static final int REFERENCE_HEIGHT = 1440;

    /**
     * Long enough for the whole way in: the bars leaving layout, the picture fading up, the pause,
     * and the fall itself, plus a second of slack, because this container is not fast and a test
     * that is merely usually long enough is a test that fails at random.
     */
    private static final long ENTRY_MS = (long) (Transitions.BARS_LEAVE_LAYOUT_MS
            + Transitions.DESCENT_DELAY_MS + Transitions.DESCENT_MS) + 1000;

    private static final long EXIT_MS = (long) Transitions.ASCENT_MS + 1000;

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

    /** Waits for real time to pass while the JavaFX thread keeps animating. */
    private void settle(long millis) {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            WaitForAsyncUtils.sleep(50, java.util.concurrent.TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    @DisplayName("the button flies the camera down into the room and the return button flies it out")
    void theWholeJourneyWorksFromTheButtons() {
        assertFalse(app.view3d().isOpen(), "not open before anything is pressed");

        interact(() -> app.topBar().threeDButtonNode().fire());
        settle(ENTRY_MS);

        assertTrue(app.view3d().isOpen(), "the 3D view should be open");
        assertFalse(app.view3d().isAnimating(), "and the descent should have finished");

        // Standing in the room, not still hanging over it. That is the descent's whole job, and
        // asserting only "is it open" would pass on a build where the camera never moved.
        CameraPose landed = app.view3d().camera().copy();
        assertEquals(CameraPose.EYE_HEIGHT_FT, landed.y, 0.001, "should be at eye height");
        assertEquals(0, landed.pitch, 0.001, "and looking level, not straight down");

        interact(() -> app.view3d().returnButton().fire());
        settle(EXIT_MS);

        assertFalse(app.view3d().isOpen(), "the 3D view should be closed again");
        assertFalse(app.view3d().isAnimating());
        assertEquals("Back to 2D.", app.statusBar().text());
    }

    @Test
    @DisplayName("the 2D chrome leaves on the way in and is back in place on the way out")
    void theChromeGoesAndComesBack() {
        assertTrue(app.topWrap().isManaged(), "the top bar takes up space to begin with");

        interact(() -> app.topBar().threeDButtonNode().fire());
        settle(ENTRY_MS);

        for (javafx.scene.Node piece : chrome()) {
            assertFalse(piece.isManaged(),
                    piece.getClass().getSimpleName() + " should have left the layout");
            assertFalse(piece.isVisible(),
                    piece.getClass().getSimpleName() + " should be hidden");
        }
        // And it left by sliding, not by vanishing -- a non-zero translate is the evidence.
        assertNotEquals(0.0, app.topWrap().getTranslateY(), "the top bar slid up");
        assertTrue(app.topWrap().getTranslateY() < 0, "upwards, specifically");
        assertTrue(app.statusBar().getTranslateY() > 0, "and the status bar downwards");

        interact(() -> app.view3d().returnButton().fire());
        settle(EXIT_MS + (long) Transitions.BAR_SLIDE_MS + 500);

        for (javafx.scene.Node piece : chrome()) {
            assertTrue(piece.isManaged(),
                    piece.getClass().getSimpleName() + " should be back in the layout");
            assertTrue(piece.isVisible());
        }
        assertEquals(0, app.topWrap().getTranslateY(), 0.5, "and slid back to where it was");
        assertEquals(0, app.statusBar().getTranslateY(), 0.5);
    }

    @Test
    @DisplayName("Fit is forced on for the 3D view, and your own Fit and scroll come back after")
    void theFitAndScrollAreRestored() {
        // Set up the state the original's prev2dState exists to protect: Fit off, scrolled away
        // from the corner. Coming back to a fitted room scrolled to the top left would lose the
        // place you were working in, which is the whole reason this is remembered.
        interact(() -> {
            app.canvas().setFitMode(false);
            app.topBar().setFitActive(false);
            app.canvas().setHvalue(0.6);
            app.canvas().setVvalue(0.4);
        });
        WaitForAsyncUtils.waitForFxEvents();
        double scrollX = app.canvas().getHvalue();
        double scrollY = app.canvas().getVvalue();

        interact(() -> app.topBar().threeDButtonNode().fire());
        settle(ENTRY_MS);

        assertTrue(app.canvas().isFitMode(), "3D forces Fit on so the room sits whole underneath");

        interact(() -> app.view3d().returnButton().fire());
        settle(EXIT_MS + 500);

        assertFalse(app.canvas().isFitMode(), "Fit was off before, so it must be off again");
        assertEquals(scrollX, app.canvas().getHvalue(), 0.02, "scrolled back to where you were");
        assertEquals(scrollY, app.canvas().getVvalue(), 0.02);
    }

    @Test
    @DisplayName("the return button cannot be pressed until the camera has landed")
    void theReturnButtonWaitsForTheDescent() {
        interact(() -> app.topBar().threeDButtonNode().fire());

        // Part-way in: the picture is up, the camera is still falling.
        settle((long) (Transitions.BARS_LEAVE_LAYOUT_MS + Transitions.DESCENT_DELAY_MS + 400));
        assertTrue(app.view3d().isAnimating(), "should still be on its way down");
        assertFalse(app.view3d().returnButton().isVisible(),
                "hidden, not merely transparent -- a transparent button is still clickable, and "
                        + "pressing it mid-descent would leave the app between two views");

        settle(ENTRY_MS);
        assertTrue(app.view3d().returnButton().isVisible(), "and it appears once you have landed");
    }

    @Test
    @DisplayName("pressing the 3D button again while it is already open does nothing")
    void theEntryCannotBeStartedTwice() {
        interact(() -> app.topBar().threeDButtonNode().fire());
        settle(ENTRY_MS);
        CameraPose landed = app.view3d().camera().copy();

        interact(() -> app.topBar().threeDButtonNode().fire());
        settle(600);

        assertTrue(app.view3d().isOpen(), "still open, not torn down and half-rebuilt");
        assertFalse(app.view3d().isAnimating(), "and no second descent was started");
        assertEquals(landed.y, app.view3d().camera().y, 0.001,
                "the camera should not have been thrown back overhead");
    }

    @Test
    @DisplayName("the view can be opened, left, and opened again")
    void theViewCanBeUsedMoreThanOnce() {
        // M5.1's bug 3 was that the 3D view worked exactly once per run, and the second press
        // threw an exception inside the button handler -- which prints to the console and is
        // otherwise ignored, so it looked like a button that simply did nothing. The re-entrancy
        // guard added for the transitions is the obvious way to reintroduce it, so this drives
        // the full round trip twice through the real buttons.
        for (int attempt = 1; attempt <= 2; attempt++) {
            interact(() -> app.topBar().threeDButtonNode().fire());
            settle(ENTRY_MS);
            assertTrue(app.view3d().isOpen(), "attempt " + attempt + ": should have opened");
            assertEquals(CameraPose.EYE_HEIGHT_FT, app.view3d().camera().y, 0.001,
                    "attempt " + attempt + ": should have descended");

            interact(() -> app.view3d().returnButton().fire());
            settle(EXIT_MS + 500);
            assertFalse(app.view3d().isOpen(), "attempt " + attempt + ": should have closed");
        }
    }

    private javafx.scene.Node[] chrome() {
        return new javafx.scene.Node[] {
            // topWrap, not topBar: since M6.4 the frame is what slides and what leaves the layout,
            // because the bar inside it has its own fold and the two must not fight over the
            // same translate.
            app.topWrap(), app.statusBar(), app.sliderDrawer(), app.listPanel(),
        };
    }
}
