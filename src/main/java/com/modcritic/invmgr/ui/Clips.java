package com.modcritic.invmgr.ui;

import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;

/**
 * {@code overflow: hidden}, which JavaFX does not have.
 *
 * <p>A CSS box with {@code overflow: hidden} cuts its children off at its own edges. JavaFX draws
 * a child wherever the layout put it, however far outside the parent that is, and a layout that
 * cannot fit its children puts them outside quite often: a {@code StackPane} centers a child too
 * big for it, so the child hangs off both ends, and a {@code VBox} stacks them downward past its
 * own bottom edge.
 *
 * <p><b>And the overflow is not only drawn, it is tapped.</b> A node hanging outside its parent
 * keeps its share of the window as far as the hit test is concerned, so it takes taps meant for
 * whatever is really on screen there. That is how the six-button island stopped working on a
 * phone held sideways: the room's container was 64 pixels tall with a 268-pixel drawer inside it,
 * so the drawer's container reached 100 pixels up over the island and swallowed every tap.
 *
 * <p>The original marks three boxes {@code overflow: hidden} for exactly this: {@code #main} at
 * line 140, {@code #slider-drawer} at 149 and {@code #list-panel} at 233. This is that, in the
 * one place, so a fourth box that needs it says so in one line.
 *
 * <h2>The one place it is not equivalent</h2>
 *
 * <p>A clip is applied to everything a node draws, its own drop shadow included, where CSS
 * {@code overflow} cuts off the children and leaves the element's own {@code box-shadow} alone.
 * So this belongs on a container, not on a panel that casts a shadow. The drawers cast theirs
 * from inside {@code #main}, and it survives because {@code #main} is what holds the clip.
 */
public final class Clips {

    private Clips() {
    }

    /**
     * Cuts everything inside {@code region} off at the region's own edges, and keeps doing it as
     * the region is resized.
     */
    public static void toOwnBox(Region region) {
        Rectangle box = new Rectangle();
        box.widthProperty().bind(region.widthProperty());
        box.heightProperty().bind(region.heightProperty());
        region.setClip(box);
    }
}
