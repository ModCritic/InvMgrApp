package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Menu window's size on a phone, worked out without a phone or a window.
 *
 * <p>The rule is the user's, from 2026-09-24: half the height of the phone held upright, and the
 * same size on its side. Each case below is a screen whose numbers make one of the limits in
 * {@link MenuWindow#phoneSide} the one that decides, so deleting any limit fails at least one of
 * them.
 */
class MenuWindowTest {

    /** The phone's own bars held upright: 24 at the top, 48 along the bottom. */
    private static final SystemInsets UPRIGHT = new SystemInsets(24, 0, 48, 0);

    /** And on its side, as the test phone reports it: the navigation bar moves to the right. */
    private static final SystemInsets SIDEWAYS = new SystemInsets(24, 48, 0, 0);

    @Test
    @DisplayName("the test phone gets 334 both ways up, the number in the M7 row")
    void theTestPhone() {
        assertEquals(334, MenuWindow.phoneSide(360, 740, UPRIGHT));
        assertEquals(334, MenuWindow.phoneSide(740, 360, SIDEWAYS));
    }

    @Test
    @DisplayName("a taller phone shrinks to the height it has on its side, both ways up")
    void aTallerPhone() {
        // Half of 800 less the bars is 364, and on its side only 336 is left below the clock.
        // The window is meant to be the same square both ways up, so the sideways limit applies;
        // without it, upright comes out 344.
        assertEquals(336, MenuWindow.phoneSide(360, 800, UPRIGHT));
        assertEquals(336, MenuWindow.phoneSide(800, 360, SIDEWAYS));
    }

    @Test
    @DisplayName("with the bars hidden the upright width decides, and still both ways up")
    void theBarsHidden() {
        // The Hide system bars setting. Half the height is 370 and the short side is 360, so the
        // 8 px margins either side of an upright phone are what keep it off the edges. Read from
        // the CURRENT width instead, sideways would come out 360 and upright 344.
        assertEquals(344, MenuWindow.phoneSide(360, 740, SystemInsets.NONE));
        assertEquals(344, MenuWindow.phoneSide(740, 360, SystemInsets.NONE));
    }

    @Test
    @DisplayName("a navigation bar that stays along the bottom on its side still leaves it on the glass")
    void aBarThatStaysAtTheBottom() {
        // The sideways limit assumes the bar moves to the side. Here it does not, so only 288 is
        // left between the bars, and the window has to take that rather than the 334 it expects.
        double side = MenuWindow.phoneSide(740, 360, UPRIGHT);
        assertEquals(288, side);
        assertTrue(side <= 360 - 24 - 48, "never taller than the space between the bars");
    }

    @Test
    @DisplayName("something down both sides of the screen narrows it to what is left")
    void somethingDownTheSides() {
        // A camera cutout or a curved edge that reserves 30 px a side. Nothing on the test phone
        // does this; it is the width-now limit's own case.
        assertEquals(284, MenuWindow.phoneSide(360, 740, new SystemInsets(24, 30, 48, 30)));
    }

    @Test
    @DisplayName("and it lands on a whole pixel")
    void aWholePixel() {
        // 741 tall makes half the upright height 334.5, which would put the window's edges on a
        // half pixel and draw its border blurred.
        assertEquals(334, MenuWindow.phoneSide(360, 741, UPRIGHT));
    }
}
