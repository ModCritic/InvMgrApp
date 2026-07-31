package com.modcritic.invmgr.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Units}.
 *
 * <p>These are the first tests in the project, so they double as proof that the test
 * setup actually runs something — a test suite that passes because it found no tests is
 * the failure mode being ruled out here.
 */
class UnitsTest {

    /** Tolerance for comparing decimal numbers that went through division. */
    private static final double EPSILON = 1e-9;

    @Test
    @DisplayName("pixel scale: 12 inches is exactly one foot on screen")
    void pixelScale() {
        assertEquals(96.0, Units.inchesToPx(12), EPSILON);
        assertEquals(96.0, Units.feetToPx(1), EPSILON);
        assertEquals(Units.feetToPx(1), Units.inchesToPx(12), EPSILON);
        assertEquals(314.9606299212598, Units.PX_PER_METER, 1e-10);
    }

    @Test
    @DisplayName("imperial/metric conversions are exact both ways")
    void metricConversions() {
        assertEquals(30.48, Units.inToCm(12), EPSILON);
        assertEquals(12.0, Units.cmToIn(30.48), EPSILON);
        assertEquals(0.3048, Units.ftToM(1), EPSILON);
        assertEquals(1.0, Units.mToFt(0.3048), EPSILON);
    }

    @Test
    void rounding() {
        assertEquals(1.235, Units.round3(1.23456), EPSILON);
        assertEquals(1.234568, Units.round6(1.2345678), EPSILON);
    }

    @Test
    @DisplayName("a whole-inch value entered in cm round-trips back unchanged")
    void metricRoundTrip() {
        double stored = Units.cmDimensionInputToInches(30.48);
        assertEquals(12.0, stored, EPSILON);
        assertEquals(30.48, Units.inToCm(stored), EPSILON);
    }

    @Test
    @DisplayName("rounding in cm first gives a different answer than rounding once at the end")
    void roundingOrderMatters() {
        // This is the case SPEC-2D-ENGINE.md §2 warns about. If someone ever
        // "simplifies" cmDimensionInputToInches into a single rounding step, this test
        // is what catches it.
        double specOrder = Units.cmDimensionInputToInches(12.3456);
        double collapsed = Units.round6(Units.cmToIn(12.3456));

        assertEquals(4.86063, specOrder, EPSILON);
        assertEquals(4.860472, collapsed, EPSILON);
        assertNotEquals(specOrder, collapsed);
    }

    @Test
    @DisplayName("dimensions are clamped to the legal 1–1000 inch range")
    void clamping() {
        assertEquals(1.0, Units.cmDimensionInputToInches(1.0), EPSILON);      // 0.39 in → floor
        assertEquals(1000.0, Units.cmDimensionInputToInches(5000.0), EPSILON); // 1968 in → ceiling
        assertEquals(1.0, Units.clampDimension(0.0), EPSILON);
        assertEquals(1000.0, Units.clampDimension(99_999.0), EPSILON);
        assertEquals(24.5, Units.clampDimension(24.5), EPSILON);
    }
}
