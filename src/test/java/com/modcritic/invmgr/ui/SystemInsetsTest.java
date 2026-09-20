package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How much of the screen the phone keeps for itself, and the two different ways the two edges
 * arrive at their number.
 */
class SystemInsetsTest {

    /** The user's phone: 2220 real pixels tall at a scale of three. */
    private static final double PHONE_SCREEN_HEIGHT = 740;

    /** Its metrics reported 2076 real pixels (the navigation bar already taken off). */
    private static final double PHONE_VISUAL_HEIGHT = 692;

    @Test
    @DisplayName("the navigation bar is measured, not assumed, when the screen sizes disagree")
    void theNavBarIsMeasured() {
        SystemInsets insets = SystemInsets.forAndroid(PHONE_SCREEN_HEIGHT, PHONE_VISUAL_HEIGHT);

        // 740 - 692 = 48, which is also what the user's logcat reported as 144 real pixels at a
        // scale of three. Measurement and standard agree here; the test uses the arithmetic.
        assertEquals(48, insets.bottom(), 1e-9);
    }

    @Test
    @DisplayName("a phone whose two screen heights match falls back to the standard bar")
    void equalHeightsFallBackToTheStandard() {
        // This is what a platform that never implemented the distinction reports, and it is the
        // likely case on Android, hence the fallback existing at all.
        SystemInsets insets = SystemInsets.forAndroid(740, 740);

        assertEquals(SystemInsets.ANDROID_NAV_BAR, insets.bottom(), 1e-9);
    }

    @Test
    @DisplayName("a nonsense difference is rejected rather than believed")
    void anImplausibleDifferenceIsRejected() {
        // Subtracting the wrong pair of sizes can produce anything. 400 is not a navigation bar,
        // and reserving 400 pixels would hide over half the interface.
        SystemInsets insets = SystemInsets.forAndroid(740, 340);

        assertEquals(SystemInsets.ANDROID_NAV_BAR, insets.bottom(), 1e-9);
    }

    @Test
    @DisplayName("a negative difference is rejected too")
    void aNegativeDifferenceIsRejected() {
        // Visual bounds larger than the screen is impossible, so it means the numbers are not the
        // pair assumed. Without this guard the app would reserve a negative amount and pull the
        // top bar UP off the screen instead of down.
        SystemInsets insets = SystemInsets.forAndroid(740, 800);

        assertEquals(SystemInsets.ANDROID_NAV_BAR, insets.bottom(), 1e-9);
    }

    @Test
    @DisplayName("the status bar is always Android's standard, because nothing can measure it")
    void theStatusBarIsTheStandard() {
        // Bound on BOTH sides: it must be the standard whether the measurement succeeded or not,
        // because the measurement only ever concerns the bottom edge. A test that checked only the
        // fallback case would pass on an implementation that wrongly derived the top from the same
        // subtraction.
        assertEquals(24, SystemInsets.forAndroid(740, 692).top(), 1e-9);
        assertEquals(24, SystemInsets.forAndroid(740, 740).top(), 1e-9);
        assertEquals(SystemInsets.ANDROID_STATUS_BAR,
                SystemInsets.forAndroid(740, 692).top(), 1e-9);
    }

    @Test
    @DisplayName("nothing is reserved at the sides, on any of the paths")
    void theSidesAreClear() {
        SystemInsets measured = SystemInsets.forAndroid(740, 692);
        SystemInsets fallenBack = SystemInsets.forAndroid(740, 740);

        assertEquals(0, measured.left(), 1e-9);
        assertEquals(0, measured.right(), 1e-9);
        assertEquals(0, fallenBack.left(), 1e-9);
        assertEquals(0, fallenBack.right(), 1e-9);
    }

    @Test
    @DisplayName("NONE reserves nothing and says so")
    void noneIsEmpty() {
        assertTrue(SystemInsets.NONE.areNone());
        assertEquals(0, SystemInsets.NONE.top(), 1e-9);
        assertEquals(0, SystemInsets.NONE.bottom(), 1e-9);
    }

    @Test
    @DisplayName("anything reserved is not NONE")
    void somethingReservedIsNotNone() {
        // Guards the obvious wrong implementation of areNone: one that returns true always, or
        // that checks only one edge.
        assertFalse(new SystemInsets(1, 0, 0, 0).areNone());
        assertFalse(new SystemInsets(0, 1, 0, 0).areNone());
        assertFalse(new SystemInsets(0, 0, 1, 0).areNone());
        assertFalse(new SystemInsets(0, 0, 0, 1).areNone());
        assertFalse(SystemInsets.forAndroid(740, 692).areNone());
    }

    @Test
    @DisplayName("the four edges are not swapped for one another")
    void theEdgesAreDistinct() {
        // Four different numbers, so a constructor that assigns them in the wrong order (the
        // classic mistake with a four-argument value class, and CSS order is top/right/bottom/left
        // while many APIs use top/left/bottom/right) cannot pass.
        SystemInsets insets = new SystemInsets(1, 2, 3, 4);

        assertEquals(1, insets.top(), 1e-9);
        assertEquals(2, insets.right(), 1e-9);
        assertEquals(3, insets.bottom(), 1e-9);
        assertEquals(4, insets.left(), 1e-9);
    }
}
