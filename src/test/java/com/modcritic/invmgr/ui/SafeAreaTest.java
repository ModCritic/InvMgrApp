package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Keeping the interface clear of a phone's own clock and navigation buttons, and, just as much,
 * keeping the seam out of the top of the screen while doing it.
 *
 * <p>The user reported both halves of this off the first Android build: the top bar came up
 * underneath the clock, and the strip above it was the window's {@code #1a1a1a} against the bar's
 * {@code #252525}, so it read as two pieces rather than one. The color half is the reason the
 * space is given to the bars themselves instead of padded around the whole interface, and it is
 * the half a layout-only test would miss entirely; hence the pixel reads below.
 */
class SafeAreaTest extends ApplicationTest {

    /** The user's phone reserves this much at the top: 72 real pixels at a scale of three. */
    private static final double STATUS_BAR = 24;

    /** The one-pixel {@code #444} rule the frame draws along its own bottom edge. */
    private static final double BOTTOM_RULE = 1;

    private TopBar topBar;
    private TopWrap topWrap;
    private StatusBar statusBar;
    private Scene scene;

    @Override
    public void start(Stage stage) {
        topBar = new TopBar(new AppState());
        // M6.4 moved the reservation one level out, onto the frame the bar sits in, because the bar
        // itself now folds away on a phone and the space behind the clock must not fold with it.
        // Re-derived rather than ported: these ask the same questions of the piece that answers
        // them now, and one new question that only became askable once the bar could fold.
        topWrap = new TopWrap(topBar);
        statusBar = new StatusBar();
        VBox column = new VBox(topWrap, statusBar);
        scene = new Scene(column, 900, 300);
        // ⚠ Pinned, because TestFX reuses ONE stage for the whole run. Without this the
        // window keeps whatever size the previous test class left it at, the scene is squeezed
        // into it, and every coordinate in this class addresses the wrong place. It fails as a
        // wrong ANSWER rather than as an error, and only when another class happens to run
        // first: this one passed alone and failed after PresetTouchTest, which pins itself to
        // 800x400.
        stage.setMaximized(false);
        stage.setWidth(900);
        stage.setHeight(300);
        stage.setScene(scene);
        stage.setX(0);
        stage.setY(0);
        stage.show();
    }

    @Test
    @DisplayName("reserving space grows the top bar downward-out, by exactly what was asked")
    void theBarGrowsByTheAmountReserved() {
        double before = WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            topWrap.reserveTop(0);
            topWrap.applyCss();
            topWrap.layout();
            return topWrap.getHeight();
        });

        double after = WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            topWrap.reserveTop(STATUS_BAR);
            topWrap.getParent().applyCss();
            topWrap.getParent().layout();
            return topWrap.getHeight();
        });

        // Bounded on both sides. "Taller than before" alone would pass on a bar that grew by one
        // pixel or by three hundred, and the whole point is that the reserved strip matches the
        // system bar exactly: too little leaves the clock overlapping, too much wastes screen.
        assertEquals(before + STATUS_BAR, after, 0.5,
                "the bar should grow by exactly the reserved amount");
    }

    @Test
    @DisplayName("the reserved strip is the top bar's own color, not the window's")
    void theReservedStripCarriesTheBarsColor() {
        WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            topWrap.reserveTop(STATUS_BAR);
            topWrap.getParent().applyCss();
            topWrap.getParent().layout();
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();

        WritableImage image =
                WaitForAsyncUtils.waitForAsyncFx(5000, () -> scene.snapshot(null));

        // Two pixels down is inside the strip the phone's clock sits over. This is the pixel the
        // user was looking at when they said it did not match.
        Color strip = image.getPixelReader().getColor(400, 2);

        assertClose("the strip behind the status bar", strip, Tokens.TOP_BAR_BG);

        // And it must NOT be the window background, which is what it was. Stated separately and
        // explicitly: the two colors are only 11 points apart per channel, so a generous
        // tolerance on the assertion above could accept the bug it exists to catch.
        assertFar("the strip behind the status bar", strip, Tokens.BODY_BG);
    }

    @Test
    @DisplayName("the bar's contents move down out of the reserved strip, rather than sitting in it")
    void theContentsClearTheStrip() {
        double before = WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            topWrap.reserveTop(0);
            topWrap.getParent().applyCss();
            topWrap.getParent().layout();
            return firstChildTop();
        });

        double after = WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            topWrap.reserveTop(STATUS_BAR);
            topWrap.getParent().applyCss();
            topWrap.getParent().layout();
            return firstChildTop();
        });

        // The color test above would still pass if the reservation painted a taller bar and left
        // the controls where they were, which is precisely the bug being fixed, the clock sitting
        // on top of the width field. This is the assertion that says they actually moved.
        assertEquals(before + STATUS_BAR, after, 0.5,
                "the first control should move down by the reserved amount");
    }

    @Test
    @DisplayName("folding the bar away does not take the strip behind the clock with it")
    void theStripSurvivesTheBarFolding() {
        // The assertion the M6.4 move exists for, and the one nothing before it could have made.
        // The reservation used to be the top bar's own top padding. The moment the bar could fold
        // (which is what the touch island's ▼ does), that padding went with it, and the island
        // would have slid up underneath the phone's clock. Reserving on the frame instead is only
        // correct if the frame keeps the space while the bar inside it is gone.
        double reserved = WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            topWrap.reserveTop(STATUS_BAR);
            topBar.setCollapsed(true, false);
            topWrap.getParent().applyCss();
            topWrap.getParent().layout();
            return topWrap.getHeight();
        });

        assertEquals(0, WaitForAsyncUtils.waitForAsyncFx(5000, () -> topBar.getHeight()), 0.5,
                "the bar itself should have folded to nothing");
        assertEquals(STATUS_BAR + BOTTOM_RULE, reserved, 0.5,
                "and the frame should be left holding exactly the reserved strip, plus its rule");

        // Bounded the other way too: unfolding must give the bar back, not leave it at zero.
        double back = WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            topBar.setCollapsed(false, false);
            topWrap.reserveTop(0);
            topWrap.getParent().applyCss();
            topWrap.getParent().layout();
            return topBar.getHeight();
        });
        assertTrue(back > 20, "unfolding brings the bar back, and it came back " + back);
    }

    @Test
    @DisplayName("reserving space at the bottom grows the status bar, and leaves its color alone")
    void theStatusBarGrowsDownward() {
        double before = WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            statusBar.reserveBottom(0);
            statusBar.getParent().applyCss();
            statusBar.getParent().layout();
            return statusBar.getHeight();
        });

        double after = WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            statusBar.reserveBottom(48);
            statusBar.getParent().applyCss();
            statusBar.getParent().layout();
            return statusBar.getHeight();
        });

        assertEquals(before + 48, after, 0.5);
    }

    @Test
    @DisplayName("a desktop reserves nothing, so neither bar changes size")
    void aDesktopIsUntouched() {
        // The regression that would hurt most: shipping a phone fix that quietly moved every
        // desktop control down 24 pixels. SystemInsets.NONE has to be a true no-op.
        double topBefore = WaitForAsyncUtils.waitForAsyncFx(5000, () -> topWrap.getHeight());
        double bottomBefore = WaitForAsyncUtils.waitForAsyncFx(5000, () -> statusBar.getHeight());

        double topAfter = WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            topWrap.reserveTop(SystemInsets.NONE.top());
            statusBar.reserveBottom(SystemInsets.NONE.bottom());
            topWrap.getParent().applyCss();
            topWrap.getParent().layout();
            return topWrap.getHeight();
        });
        double bottomAfter = WaitForAsyncUtils.waitForAsyncFx(5000, () -> statusBar.getHeight());

        assertEquals(topBefore, topAfter, 0.5);
        assertEquals(bottomBefore, bottomAfter, 0.5);
    }

    @Test
    @DisplayName("in 3D the floating controls move inward, and by both edges independently")
    void theThreeDControlsMoveInsideTheSafeArea() {
        View3D view = WaitForAsyncUtils.waitForAsyncFx(5000, View3D::new);

        javafx.geometry.Insets before = WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            view.setSafeArea(SystemInsets.NONE);
            return javafx.scene.layout.StackPane.getMargin(view.returnButton());
        });

        javafx.geometry.Insets after = WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            view.setSafeArea(new SystemInsets(24, 0, 48, 0));
            return javafx.scene.layout.StackPane.getMargin(view.returnButton());
        });

        // The return button sits top-right, so it answers to the top inset and would answer to a
        // side inset if a sideways phone ever produced one. Asserting the delta rather than the
        // absolute value keeps this from re-stating the button's ordinary margin, which is a
        // design token and not what this test is about.
        assertEquals(before.getTop() + 24, after.getTop(), 1e-9);
        assertEquals(before.getRight(), after.getRight(), 1e-9,
                "a phone held upright reserves nothing at the sides");
    }

    private double firstChildTop() {
        return topBar.getChildrenUnmodifiable().get(0)
                .localToScene(topBar.getChildrenUnmodifiable().get(0).getBoundsInLocal())
                .getMinY();
    }

    private void assertClose(String what, Color actual, Color expected) {
        assertTrue(distance(actual, expected) <= 6,
                what + " should be " + Tokens.hex(expected) + " but was " + Tokens.hex(actual));
    }

    private void assertFar(String what, Color actual, Color wrong) {
        assertTrue(distance(actual, wrong) > 6,
                what + " must not be " + Tokens.hex(wrong) + " but was " + Tokens.hex(actual));
    }

    private int distance(Color a, Color b) {
        return Math.max(Math.max(channel(a.getRed(), b.getRed()), channel(a.getGreen(), b.getGreen())),
                channel(a.getBlue(), b.getBlue()));
    }

    private int channel(double a, double b) {
        return Math.abs((int) Math.round(a * 255) - (int) Math.round(b * 255));
    }
}
