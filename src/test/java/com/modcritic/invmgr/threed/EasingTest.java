package com.modcritic.invmgr.threed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three curves the 3D transitions are shaped by.
 *
 * <p>Each is checked three ways, because they fail differently. Sampled values catch a curve being
 * swapped for another; the endpoints catch a curve that never quite arrives; and the shape
 * assertions (is it above or below a straight line) are what actually distinguish "eases in"
 * from "eases out", which is the property {@link Transitions} depends on and the one a sample at a
 * single point can agree with by accident.
 */
class EasingTest {

    private static final double TOLERANCE = 1e-12;

    @Test
    @DisplayName("each curve matches the original's arithmetic at sampled points")
    void theCurvesAreTheOnesTheOriginalUses() {
        // -(cos(pi*u) - 1) / 2
        assertEquals(0.5, Easing.inOutSine(0.5), TOLERANCE, "sine is symmetric about the middle");
        assertEquals(0.1464466094067262, Easing.inOutSine(0.25), 1e-15);
        assertEquals(0.8535533905932737, Easing.inOutSine(0.75), 1e-15);

        // u^3
        assertEquals(0.125, Easing.inCubic(0.5), TOLERANCE);
        assertEquals(0.015625, Easing.inCubic(0.25), TOLERANCE);
        assertEquals(0.421875, Easing.inCubic(0.75), TOLERANCE);

        // 1 - (1-u)^3
        assertEquals(0.875, Easing.outCubic(0.5), TOLERANCE);
        assertEquals(0.578125, Easing.outCubic(0.25), TOLERANCE);
        assertEquals(0.984375, Easing.outCubic(0.75), TOLERANCE);
    }

    @Test
    @DisplayName("every curve starts at exactly 0 and ends at exactly 1")
    void theCurvesArriveWhereTheyAreSupposedTo() {
        // Not a formality. A curve that ends at 0.9999 leaves the camera permanently short of the
        // pose it was animating to, and the error is far too small to see and far too large to
        // ignore once the render loop takes over from that pose.
        for (DoubleUnaryOperator curve : all()) {
            assertEquals(0, curve.applyAsDouble(0), TOLERANCE, "must start still");
            assertEquals(1, curve.applyAsDouble(1), TOLERANCE, "must arrive exactly");
        }
    }

    @Test
    @DisplayName("every curve moves forwards the whole way, never backwards")
    void theCurvesAreMonotonic() {
        // A curve that dips would make the camera reverse mid-animation.
        for (DoubleUnaryOperator curve : all()) {
            double previous = curve.applyAsDouble(0);
            for (int step = 1; step <= 1000; step++) {
                double next = curve.applyAsDouble(step / 1000.0);
                assertTrue(next >= previous,
                        "a curve must never go backwards: fell from " + previous + " to " + next
                                + " at " + (step / 1000.0));
                previous = next;
            }
        }
    }

    @Test
    @DisplayName("in-cubic lags a straight line and out-cubic leads it, the whole way through")
    void theCurvesBendTheWayTheirNamesSay() {
        // This is the assertion that a swapped pair cannot survive, and it is deliberately about
        // shape rather than about numbers: IN_CUBIC and OUT_CUBIC are reflections of each other,
        // so a single sampled value from one is a perfectly ordinary-looking value for the other.
        for (int step = 1; step < 1000; step++) {
            double u = step / 1000.0;
            assertTrue(Easing.inCubic(u) < u,
                    "eases IN means it is behind a constant speed at " + u);
            assertTrue(Easing.outCubic(u) > u,
                    "eases OUT means it is ahead of a constant speed at " + u);
        }

        // And the sine curve is behind in the first half, ahead in the second: slow, fast, slow.
        assertTrue(Easing.inOutSine(0.25) < 0.25, "sine starts gently");
        assertTrue(Easing.inOutSine(0.75) > 0.75, "sine finishes gently");
    }

    private static DoubleUnaryOperator[] all() {
        return new DoubleUnaryOperator[] {
            Easing.IN_OUT_SINE, Easing.IN_CUBIC, Easing.OUT_CUBIC,
        };
    }
}
