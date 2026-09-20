package com.modcritic.invmgr.threed;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Units;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the room's items into boxes the 3D view can build, converting out of the model's mixed
 * inches-and-pixels into plain feet along the way.
 *
 * <p>This is the boundary between the app's data and the 3D world, and it is deliberately its own
 * class rather than a loop inside the renderer. Two reasons. It is where the <b>planned items
 * never render</b> invariant lives, and an invariant is easier to keep when it has one home and a
 * test pointed at it than when it is an {@code if} in the middle of mesh-building code. And it is
 * pure arithmetic with no JavaFX in it, so the conversion can be tested without a graphics
 * pipeline, which matters, because a wrong factor here produces a room full of boxes that are
 * all eight times too big, and that is much easier to catch with numbers than by eye.
 */
public final class Geometry3D {

    private Geometry3D() {
    }

    /**
     * Every item that should appear in the 3D view, as boxes in feet.
     *
     * <p><b>Planned items are skipped</b>, exactly as the original skips them
     * ({@code if (it.planned) continue;}, original line 2685). A planned item is a note about
     * something not in the room yet: it is not an obstacle for stacking, it gets no automatic base
     * height, and it does not draw on the 2D canvas. Drawing one in 3D would put a solid box in a
     * room where the 2D view shows empty floor; the two views would disagree about what is in the
     * room, which is worse than either answer on its own.
     */
    public static List<Box3D> boxesFor(AppState state) {
        List<Box3D> boxes = new ArrayList<>();
        for (Item item : state.items) {
            if (item.planned) {
                continue;
            }
            boxes.add(boxFor(item));
        }
        return boxes;
    }

    /**
     * One item's box.
     *
     * <p>Positions arrive in pixels and dimensions in inches (the model's two units), and they are
     * not the same conversion. There are 96 pixels to the foot and 12 inches to the foot, which
     * agree because a pixel is an eighth of an inch, but writing both out is what makes a future
     * reader able to check it rather than trust it.
     */
    static Box3D boxFor(Item item) {
        double x0 = item.x_px / Units.PX_PER_FOOT;
        double z0 = item.y_px / Units.PX_PER_FOOT;
        double x1 = (item.x_px + Units.inchesToPx(item.w_in)) / Units.PX_PER_FOOT;
        double z1 = (item.y_px + Units.inchesToPx(item.l_in)) / Units.PX_PER_FOOT;
        double y0 = Units.inchesToPx(item.baseHeight_in) / Units.PX_PER_FOOT;
        double y1 = Units.inchesToPx(item.baseHeight_in + item.h_in) / Units.PX_PER_FOOT;
        return new Box3D(item.id, item.color, x0, x1, y0, y1, z0, z1);
    }
}
