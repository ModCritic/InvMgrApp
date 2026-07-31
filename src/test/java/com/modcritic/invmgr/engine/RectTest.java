package com.modcritic.invmgr.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests the one rule that everything else in the engine is built on.
 *
 * <p>Stacking, collision and dimming all ask "do these two footprints overlap?", so an error
 * here would show up as three unrelated-looking bugs.
 */
class RectTest {

    @Test
    @DisplayName("an item's footprint converts inches to pixels")
    void buildsFootprintFromItem() {
        Item item = new Item();
        item.x_px = 100;
        item.y_px = 200;
        item.w_in = 24;      // 192 px
        item.l_in = 12;      // 96 px

        Rect rect = Rect.of(item);

        assertEquals(100, rect.x());
        assertEquals(200, rect.y());
        assertEquals(292, rect.x2());
        assertEquals(296, rect.y2());
    }

    @ParameterizedTest(name = "touching {0} does not overlap")
    @CsvSource({
        "east,   96,   0",
        "west,  -96,   0",
        "south,   0,  96",
        "north,   0, -96"
    })
    @DisplayName("footprints that merely touch are not overlapping")
    void touchingIsNotOverlapping(String direction, double dx, double dy) {
        // Load-bearing, not a technicality: if flush edges counted as overlapping, boxes could
        // never be placed side by side — they would refuse to sit together, and a box could
        // not be dragged while another rested against it.
        Rect a = Rect.at(0, 0, 96, 96);
        Rect b = Rect.at(dx, dy, 96, 96);

        assertFalse(a.overlaps(b), "a should not overlap b touching to the " + direction);
        assertFalse(b.overlaps(a), "and the test must be symmetric");
    }

    @Test
    @DisplayName("a one-pixel intrusion does overlap")
    void genuineOverlapIsDetected() {
        Rect a = Rect.at(0, 0, 96, 96);
        Rect b = Rect.at(95, 0, 96, 96);

        assertTrue(a.overlaps(b));
        assertTrue(b.overlaps(a));
    }

    @Test
    @DisplayName("a footprint inside another overlaps it")
    void containmentOverlaps() {
        Rect big = Rect.at(0, 0, 500, 500);
        Rect small = Rect.at(100, 100, 50, 50);

        assertTrue(big.overlaps(small));
        assertTrue(small.overlaps(big));
    }

    @Test
    @DisplayName("footprints that share no ground don't overlap")
    void distantFootprintsDoNotOverlap() {
        Rect a = Rect.at(0, 0, 96, 96);
        Rect b = Rect.at(1000, 1000, 96, 96);

        assertFalse(a.overlaps(b));
    }

    @Test
    @DisplayName("overlapping in only one direction is not an overlap")
    void oneAxisAloneIsNotEnough() {
        // Same rows, far apart across: a common near-miss when reading the condition.
        Rect a = Rect.at(0, 0, 96, 96);
        Rect sameRowsFarEast = Rect.at(500, 0, 96, 96);
        assertFalse(a.overlaps(sameRowsFarEast));

        Rect sameColumnsFarSouth = Rect.at(0, 500, 96, 96);
        assertFalse(a.overlaps(sameColumnsFarSouth));
    }
}
