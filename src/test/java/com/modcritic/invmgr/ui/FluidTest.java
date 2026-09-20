package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic behind CSS's {@code clamp(12px, 4.5cqi, 16px)}, checked at widths no screenshot
 * would ever be taken at.
 *
 * <p>This is the whole reason the calculation was pulled out of the width listener that runs it.
 * A listener can only be checked at whatever width a window happens to be; a function can be asked
 * a hundred questions in a millisecond, and the thing that actually has to hold, that the island's
 * three type sizes keep their order at <em>every</em> width, is a hundred-question sort of claim.
 */
class FluidTest {

    // The island's three real triples, so these tests move if the design does.
    private static final double[] BASE =
            {Tokens.ISLAND_FONT_MIN, Tokens.ISLAND_FONT_PERCENT, Tokens.ISLAND_FONT_MAX};
    private static final double[] ADD =
            {Tokens.ISLAND_ADD_FONT_MIN, Tokens.ISLAND_ADD_FONT_PERCENT, Tokens.ISLAND_ADD_FONT_MAX};
    private static final double[] TOGGLE = {Tokens.ISLAND_TOGGLE_FONT_MIN,
            Tokens.ISLAND_TOGGLE_FONT_PERCENT, Tokens.ISLAND_TOGGLE_FONT_MAX};

    private static double at(double width, double[] triple) {
        return Fluid.size(width, triple[0], triple[1], triple[2]);
    }

    @Test
    @DisplayName("in the middle of the range it is simply the percentage")
    void thePercentageIsWhatItIs() {
        // 4.5% of 300 is 13.5, which is between the floor of 12 and the ceiling of 16.
        assertEquals(13.5, Fluid.size(300, 12, 4.5, 16), 1e-9);
    }

    @Test
    @DisplayName("a narrow screen is held at the floor, not shrunk to nothing")
    void theFloorHolds() {
        // 4.5% of 200 is 9, which is unreadable, so the floor takes over.
        assertEquals(12, Fluid.size(200, 12, 4.5, 16), 1e-9);
    }

    @Test
    @DisplayName("a wide screen is held at the ceiling, not grown past the design size")
    void theCeilingHolds() {
        // The point of the ceiling: turn the phone sideways and the buttons stop shrinking rather
        // than growing into something that was never drawn.
        assertEquals(16, Fluid.size(800, 12, 4.5, 16), 1e-9);
    }

    @Test
    @DisplayName("a box that has not been laid out yet still gives a legible size")
    void noWidthYieldsTheFloor() {
        // A node reports zero width before its first layout, and the island seeds itself once at
        // construction. Zero must come out legible rather than invisible.
        assertEquals(12, Fluid.size(0, 12, 4.5, 16), 1e-9);
    }

    @Test
    @DisplayName("an impossible pair resolves the way CSS resolves it: the floor wins")
    void theFloorBeatsTheCeiling() {
        // CSS applies the ceiling first and the floor second, so clamp(20, x, 10) is 20. Kept
        // rather than "fixed", because the floor is the smallest still-legible size and
        // legibility is the thing that must not be given up.
        assertEquals(20, Fluid.size(300, 20, 4.5, 10), 1e-9);
    }

    @Test
    @DisplayName("the island's three sizes keep their order at every width a phone can be")
    void theHierarchyHoldsEverywhere() {
        // The ratios ARE the design: Add bigger than the base, the toggles smaller. A screenshot
        // proves it at one width. This proves it at three hundred and twenty of them, including
        // the two places the behavior changes, where each triple leaves its floor and reaches
        // its ceiling, which are six different widths.
        for (double width = 200; width <= 520; width += 1) {
            double add = at(width, ADD);
            double base = at(width, BASE);
            double toggle = at(width, TOGGLE);
            assertTrue(add > base,
                    "Add must stay bigger than the base at " + width + ": " + add + " vs " + base);
            assertTrue(base > toggle,
                    "the base must stay bigger than Fit/Plan/Units at " + width
                            + ": " + base + " vs " + toggle);
        }
    }

    @Test
    @DisplayName("on a 360 px phone the island comes out at the numbers the original computes")
    void theRealPhoneWidth() {
        // Bounded against the CSS rather than against itself: at 360 px, 4.5cqi is 16.2 and the
        // ceiling is 16, so the base sits exactly ON its ceiling; 5.6cqi is 20.16 against a
        // ceiling of 20; and 3.6cqi is 12.96, just under its ceiling of 13. Those three facts are
        // what make the island fit a real phone, and they are worth pinning as numbers because
        // any change to the percentages moves them.
        assertEquals(16, at(360, BASE), 1e-9);
        assertEquals(20, at(360, ADD), 1e-9);
        assertEquals(12.96, at(360, TOGGLE), 1e-9);
    }
}
