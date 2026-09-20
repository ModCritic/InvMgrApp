package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The tooltip a tap puts up, which is a different thing from the one a hover follows.
 *
 * <p>A finger has no hover, so on a phone tapping a box is the only way to ask what it is, and
 * nothing ever tells the label to go away again, so it has to time itself out. It also has to keep
 * clear of the fingertip covering the box, and stay inside a screen the room reaches the edges of.
 */
class TouchTooltipTest extends ApplicationTest {

    private Pane layer;
    private ItemTooltip tooltip;

    @Override
    public void start(Stage stage) {
        layer = new Pane();
        tooltip = new ItemTooltip(layer);
        stage.setScene(new Scene(layer, 600, 400));
        // Pinned, because TestFX reuses one stage for the whole run and an earlier class may have
        // left it maximized. Every assertion below is also written against the layer's real size
        // rather than against 600, so this test says the same thing whatever window it gets.
        stage.setMaximized(false);
        stage.setWidth(600);
        stage.setHeight(400);
        stage.show();
    }

    @Test
    @DisplayName("a tapped tooltip sits above the point it was given, centered on it")
    void itSitsAboveAndCentered() {
        show("bin A, 24x24x12in", 300, 200);

        Bounds where = tooltip.node().getBoundsInParent();
        assertEquals(300, (where.getMinX() + where.getMaxX()) / 2, 1,
                "centered on the box, not starting at it");
        assertEquals(200, where.getMaxY(), 1,
                "its bottom edge on the anchor, so the fingertip does not cover it");
    }

    @Test
    @DisplayName("it is pushed back inside the window rather than hanging off the edge")
    void itIsClampedToTheWindow() {
        double width = layer.getWidth();
        show("a name long enough to run off the side of a phone", 10, 200);

        Bounds where = tooltip.node().getBoundsInParent();
        assertTrue(where.getMinX() >= 0, "it must not leave the window, got " + where.getMinX());

        show("a name long enough to run off the side of a phone", width - 10, 200);
        where = tooltip.node().getBoundsInParent();
        assertTrue(where.getMaxX() <= width,
                "nor on the other side, got " + where.getMaxX() + " in a window of " + width);
    }

    @Test
    @DisplayName("with no room above (a box against the top wall) it goes below instead")
    void itFlipsBelowWhenThereIsNoRoomAbove() {
        show("bin A", 300, 2);

        Bounds where = tooltip.node().getBoundsInParent();
        assertTrue(where.getMinY() > 2,
                "it should have flipped under the anchor, got " + where.getMinY());
        assertTrue(where.getMaxY() < layer.getHeight(), "and still be on screen");
    }

    @Test
    @DisplayName("it takes itself away again, because nothing else ever will")
    void itTimesItselfOut() {
        show("bin A", 300, 200);
        assertTrue(tooltip.isShowing());

        // The real wait. There is no hover to end a touch tooltip, so if this does not expire it
        // stays on screen over the room until something else happens to move it.
        WaitForAsyncUtils.sleep((long) Tokens.TOUCH_TOOLTIP_HOLD_MS + 700,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(tooltip.isShowing(), "the tapped tooltip should have expired by itself");
    }

    @Test
    @DisplayName("hiding it cancels the countdown, so it cannot hide a later hover tooltip")
    void hidingStopsTheCountdown() {
        show("bin A", 300, 200);
        interact(tooltip::hide);

        // The desktop tooltip now, put up well within the tap's 2.5 seconds. If hide() had left
        // the countdown running it would sweep this one away for no reason anybody could see.
        interact(() -> tooltip.show("bin B", 100, 100));
        WaitForAsyncUtils.sleep((long) Tokens.TOUCH_TOOLTIP_HOLD_MS + 700,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(tooltip.isShowing(), "a hover tooltip has no timeout and must not inherit one");
        assertEquals("bin B", tooltip.text());
    }

    private void show(String text, double sceneX, double sceneY) {
        interact(() -> tooltip.showForTouch(text, sceneX, sceneY));
        WaitForAsyncUtils.waitForFxEvents();
    }

    // ------------------------------------------------------- the marquee, M6.4

    /** Far longer than any budget in this class, so every case below has to shorten it. */
    private static final String TOO_LONG =
            "a storage bin with a name so long that no phone ever made could show all of it at once"
                    + "  36in W x 24in L x 18in H  base:12in";

    @Test
    @DisplayName("a name that fits does not scroll, and the box is only as wide as the words")
    void aNameThatFitsDoesNotScroll() {
        show("bin A", 300, 200);

        assertFalse(tooltip.isScrolling(), "there is nothing to scroll past");
        Bounds where = tooltip.node().getBoundsInParent();
        assertTrue(where.getWidth() < 120,
                "and the box shrink-wraps the words rather than filling its budget, got "
                        + where.getWidth());
    }

    @Test
    @DisplayName("a name too long for the room it has scrolls instead of being cut off")
    void aNameTooLongScrolls() {
        show(TOO_LONG, 300, 200);

        assertTrue(tooltip.isScrolling(), "the name does not fit, so it must slide past");
        Bounds where = tooltip.node().getBoundsInParent();
        assertTrue(where.getWidth() <= layer.getWidth() - 16,
                "and the box is held inside the window, got " + where.getWidth());
    }

    @Test
    @DisplayName("the width it may use is the narrower side doubled, so it cannot lean off-center")
    void theBudgetIsTheNarrowerSideDoubled() {
        // The box sits nearer the left edge than the right. The original records having used the
        // whole viewport here at first, which let the tooltip stretch to the right and read as
        // off-center by an amount that changed with where the box was.
        double anchorX = 100;
        show(TOO_LONG, anchorX, 200);

        Bounds where = tooltip.node().getBoundsInParent();
        double narrowerSide = anchorX - Tokens.TOUCH_TOOLTIP_MARGIN;

        assertEquals(2 * narrowerSide, where.getWidth(), 1,
                "twice the smaller gap, not the whole window");
        assertEquals(anchorX, (where.getMinX() + where.getMaxX()) / 2, 1,
                "which is exactly the width that leaves it still centered on the box");
        assertTrue(where.getMinX() >= Tokens.TOUCH_TOOLTIP_MARGIN - 0.5,
                "and it just reaches the margin rather than crossing it");
    }

    @Test
    @DisplayName("it actually slides, after a moment to read the beginning")
    void itActuallySlides() {
        show(TOO_LONG, 300, 200);

        // Bounded on BOTH sides, and the lower bound is the point: setting up an animation and
        // never starting it would pass every other case in this class. The upper bound is the
        // delay; a marquee that began immediately would already have moved by now.
        assertEquals(0, tooltip.scrolledBy(), 0.01, "it holds still while the delay runs");

        WaitForAsyncUtils.sleep((long) Tokens.TOUCH_TOOLTIP_MARQUEE_DELAY_MS + 600,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        WaitForAsyncUtils.waitForFxEvents();

        double slid = tooltip.scrolledBy();
        assertTrue(slid > 0, "and then it moves, got " + slid);
        // 600 ms at 55 px/s is 33 px. Generous either way, because the clock here is a real one.
        assertTrue(slid < 200, "at a readable pace rather than all at once, got " + slid);
    }

    @Test
    @DisplayName("a scrolling name is not taken away halfway through its first pass")
    void aScrollingNameWaitsForItsPass() {
        show(TOO_LONG, 300, 200);

        // Past the point a short name would have gone. A long one has a whole pass to finish, and
        // snatching it away mid-sentence is worse than never having scrolled it.
        WaitForAsyncUtils.sleep((long) Tokens.TOUCH_TOOLTIP_HOLD_MS + 400,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(tooltip.isShowing(), "it should still be up, reading itself out");
    }

    @Test
    @DisplayName("a tapped tooltip is set in the larger touch type, not the desktop's")
    void itUsesTheTouchType() {
        // Measured against the HOVER tooltip carrying the same words, rather than against a
        // computed height. The first version of this bounded the box's height between two numbers
        // and the difference between 12 px type and 14 is smaller than that band was; the desktop
        // font survived the mutation with the case green. Words long enough that the type, and not
        // the two extra pixels of padding, is what moves the number.
        String words = "a storage bin with a reasonably long name 24x24x12in";
        interact(() -> tooltip.show(words, 20, 200));
        WaitForAsyncUtils.waitForFxEvents();
        double hovered = tooltip.node().getBoundsInParent().getWidth();

        show(words, 300, 200);
        double tapped = tooltip.node().getBoundsInParent().getWidth();

        assertFalse(tooltip.isScrolling(), "this has to fit, or it is the budget being measured");
        assertTrue(tapped > hovered * 1.10,
                "14 px type against 12 is a tenth wider at least: got " + tapped + " against "
                        + hovered);
    }

    @Test
    @DisplayName("a name only barely too long still takes two seconds to read itself out")
    void aBarelyTooLongNameStillTakesAWholePass() {
        // Near the left edge, so the budget is small and six characters overflow it. The slide is
        // then only about eighty pixels, which at 55 px/s would be over in a second and a half,
        // fast enough to be a flicker rather than something anybody could read.
        show("bin AB", 40, 200);
        assertTrue(tooltip.isScrolling(), "this case is only about a name that does overflow");

        // The literal, not the constant: computing the expectation out of the number under test is
        // how a sensitivity assertion went blind in M6.3.
        assertEquals(2, tooltip.passSeconds(), 0.01,
                "the distance is short, so the floor is what sets the pace");

        show(TOO_LONG, 300, 200);
        assertTrue(tooltip.passSeconds() > 3,
                "and a genuinely long name is paced by its own length instead, got "
                        + tooltip.passSeconds());
    }

    @Test
    @DisplayName("the status bar tells a finger what a finger can do, not what a mouse can")
    void theRemindersMatchTheInput() {
        // On a phone the desktop line named a button that does not exist: "Right-click: delete"
        // is not advice a thumb can take, and it is advice about the one gesture M6.2 replaced.
        assertEquals("Click: edit. Right-click: delete. Hover: tooltip.",
                StatusBar.DESKTOP_INSTRUCTIONS, "the original's desktop line, verbatim");
        // The WHOLE line now, tail included: original line 672, character for character, double
        // spaces and all. M6.2 wrote the first half and left a note saying the tail would arrive
        // "with the touch layout at M6.4"; M6.4 built the three tabs the tail names and never came
        // back for the sentence, so a phone spent a milestone being told about drawers it could not
        // see named. Nothing checked that promise, which is why the tail is asserted here now.
        assertEquals("Tap: tooltip. Double-tap: edit. Hold 1.5s: delete. Drag: move."
                        + " ► layer  ◄ items  ▼ menu",
                StatusBar.TOUCH_INSTRUCTIONS,
                "and the touch line names all four gestures AND the three drawer tabs");

        // Named one at a time as well, so a failure says WHICH half went missing. The tail has gone
        // astray once already, and "the string is not what it was" is a poor way to be told.
        assertTrue(StatusBar.TOUCH_INSTRUCTIONS.contains("► layer"), "the layer-slider tab is named");
        assertTrue(StatusBar.TOUCH_INSTRUCTIONS.contains("◄ items"), "the item-list tab is named");
        assertTrue(StatusBar.TOUCH_INSTRUCTIONS.contains("▼ menu"), "and the bar's own fold arrow");

        // Wording is invisible to every other kind of test, exactly as with the button hints in
        // D-7: nothing draws these until the app is running and idle.
        assertEquals(StatusBar.DESKTOP_INSTRUCTIONS, StatusBar.instructions(),
                "a desktop must get the desktop line");
    }
}
