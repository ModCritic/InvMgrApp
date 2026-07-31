package com.modcritic.invmgr.ui;

import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

/**
 * The window's stack of layers: the app itself, then anything that floats above it.
 *
 * <p><b>Why the app needs this at all.</b> Dialogs, tooltips and the drag ghost have to draw
 * over everything, including the top bar and the item list, and they have to be able to overlap
 * each other in a fixed order. In the original that is what CSS {@code z-index} does; JavaFX has
 * no such property, and simply draws children in the order they are listed. So the order is
 * managed here, in one place, rather than being an accident of who was added last.
 *
 * <p>The order, from the design system's z-index ladder:
 *
 * <pre>
 *   drag ghost      &gt;  tooltip  &gt;  dialogs  &gt;  the app
 * </pre>
 *
 * <p>The ghost sitting above the tooltip is deliberate — you can be dragging a planned item
 * while a tooltip is still on screen, and the thing under your finger should be on top.
 */
public final class Overlays extends StackPane {

    /** Where {@link ItemTooltip} puts itself. Above dialogs, below the ghost. */
    private final Pane tooltipLayer = new Pane();

    /** Where the planned-item drag ghost puts itself. The very top. */
    private final Pane ghostLayer = new Pane();

    /** How many dialogs are currently open. Kept so Escape and the app can ask. */
    private int openDialogs;

    public Overlays(Node content) {
        // Both float layers position their contents by hand, so they must not eat clicks
        // meant for the app underneath -- pickOnBounds alone is not enough, because a Pane
        // with a child would still swallow a press that lands on that child.
        tooltipLayer.setMouseTransparent(true);
        tooltipLayer.setPickOnBounds(false);
        ghostLayer.setMouseTransparent(true);
        ghostLayer.setPickOnBounds(false);

        getChildren().addAll(content, tooltipLayer, ghostLayer);
    }

    /**
     * Puts a dialog's overlay on screen, above the app but below the tooltip.
     *
     * <p>Inserting rather than appending is the whole point: appending would put the dialog on
     * top of the tooltip layer, and a tooltip would then be invisible behind a dialog.
     */
    public void showDialog(Node overlay) {
        if (getChildren().contains(overlay)) {
            return;
        }
        getChildren().add(getChildren().indexOf(tooltipLayer), overlay);
        openDialogs++;
    }

    public void hideDialog(Node overlay) {
        if (getChildren().remove(overlay)) {
            openDialogs--;
        }
    }

    /**
     * Whether anything modal is open.
     *
     * <p>Used to keep the room from reacting to the keyboard while a dialog has it.
     */
    public boolean isDialogOpen() {
        return openDialogs > 0;
    }

    public Pane tooltipLayer() {
        return tooltipLayer;
    }

    public Pane ghostLayer() {
        return ghostLayer;
    }
}
