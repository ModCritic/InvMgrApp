package com.modcritic.invmgr.threed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The conversion out of the model's mixed inches-and-pixels into the 3D world's feet, and the one
 * invariant that lives on this boundary: planned items never render.
 */
class Geometry3DTest {

    private static final double TOLERANCE = 1e-12;

    private static Item item(double xPx, double yPx, double wIn, double lIn, double hIn,
            double baseIn) {
        Item it = new Item();
        it.id = "i" + xPx + "_" + yPx;
        it.x_px = xPx;
        it.y_px = yPx;
        it.w_in = wIn;
        it.l_in = lIn;
        it.h_in = hIn;
        it.baseHeight_in = baseIn;
        it.color = "hsl(207,55%,42%)";
        return it;
    }

    @Test
    @DisplayName("a one-foot cube at the origin is one foot on every side")
    void theUnitConversionIsRight() {
        // 12 inches is one foot and 96 pixels is one foot; a box built from both has to come out
        // square. A stray factor of 8 (pixels per inch) would make this 8 ft on two sides and 1 ft
        // on the third, which is exactly the kind of error that is obvious in numbers and merely
        // "looks a bit off" on screen.
        Box3D box = Geometry3D.boxFor(item(0, 0, 12, 12, 12, 0));
        assertEquals(0, box.x0, TOLERANCE);
        assertEquals(1, box.x1, TOLERANCE);
        assertEquals(0, box.z0, TOLERANCE);
        assertEquals(1, box.z1, TOLERANCE);
        assertEquals(0, box.y0, TOLERANCE);
        assertEquals(1, box.y1, TOLERANCE);
        assertEquals(1, box.width(), TOLERANCE);
        assertEquals(1, box.length(), TOLERANCE);
        assertEquals(1, box.height(), TOLERANCE);
    }

    @Test
    @DisplayName("position comes from pixels and size from inches, and they agree")
    void positionAndSizeUseTheirOwnUnits() {
        // 480 px is 5 ft across, 288 px is 3 ft down, and a 24 x 6 in box is 2 x 0.5 ft.
        Box3D box = Geometry3D.boxFor(item(480, 288, 24, 6, 18, 0));
        assertEquals(5, box.x0, TOLERANCE);
        assertEquals(7, box.x1, TOLERANCE);
        assertEquals(3, box.z0, TOLERANCE);
        assertEquals(3.5, box.z1, TOLERANCE);
        assertEquals(1.5, box.height(), TOLERANCE, "18 inches is a foot and a half");
    }

    @Test
    @DisplayName("a stacked item sits on top of what is under it, not on the floor")
    void baseHeightLiftsTheBox() {
        // baseHeight_in is how high off the true floor the item sits (the whole point of the
        // stacking engine). An item at 18 in with a 12 in height occupies 1.5 to 2.5 ft.
        Box3D box = Geometry3D.boxFor(item(0, 0, 12, 12, 12, 18));
        assertEquals(1.5, box.y0, TOLERANCE);
        assertEquals(2.5, box.y1, TOLERANCE);
        assertTrue(box.y0 > 0, "a stacked item must not be drawn sitting on the floor");
    }

    @Test
    @DisplayName("planned items never become boxes")
    void plannedItemsAreSkipped() {
        // The §6 invariant, in 3D. A planned item is a note about something not in the room yet:
        // it is not a stacking obstacle, it gets no automatic base height, and it does not draw
        // on the 2D canvas. Drawing one here would make the two views disagree about what is
        // actually in the room.
        AppState state = new AppState();
        Item real = item(0, 0, 12, 12, 12, 0);
        real.id = "real";
        Item planned = item(96, 96, 12, 12, 12, 0);
        planned.id = "planned";
        planned.planned = true;
        state.items.add(real);
        state.items.add(planned);

        List<Box3D> boxes = Geometry3D.boxesFor(state);
        assertEquals(1, boxes.size(), "the planned item must not produce a box");
        assertEquals("real", boxes.get(0).itemId);
    }

    @Test
    @DisplayName("an empty room produces no boxes, and every real item produces exactly one")
    void theCountMatches() {
        AppState state = new AppState();
        assertTrue(Geometry3D.boxesFor(state).isEmpty());

        for (int i = 0; i < 7; i++) {
            Item it = item(i * 96, 0, 12, 12, 12, 0);
            it.id = "i" + i;
            it.planned = i % 3 == 0;
            state.items.add(it);
        }
        // 0, 3 and 6 are planned, so four remain.
        assertEquals(4, Geometry3D.boxesFor(state).size());
    }

    @Test
    @DisplayName("the color string is carried through untouched, not parsed here")
    void theColorIsPassedAlong() {
        // Geometry3D does geometry. Shading is Shades' job, and keeping the raw string means the
        // one place that reads a color is the one place that can get the color space wrong.
        Box3D box = Geometry3D.boxFor(item(0, 0, 12, 12, 12, 0));
        assertEquals("hsl(207,55%,42%)", box.color);
    }
}
