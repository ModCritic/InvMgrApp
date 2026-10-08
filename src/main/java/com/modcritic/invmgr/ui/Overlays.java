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
 * <p>The ghost sitting above the tooltip is deliberate: you can be dragging a planned item
 * while a tooltip is still on screen, and the thing under your finger should be on top.
 *
 * <p><b>A tooltip never stays over a dialog, though</b> (the user's decision, 2026-10-01): opening
 * a dialog calls {@link #setOnDialogShown}'s action, and App hides the tooltip with it. On a phone
 * a tapped box's tooltip stays up for seconds, and a long name scrolls, so it was still sliding
 * across the Menu after the Menu opened. The tooltip keeps its place above dialogs in the order
 * all the same, because the order also decides what a tooltip shown later would cover, and none
 * can be shown while a dialog is up: the dialog's overlay takes every touch and the mouse.
 */
public final class Overlays extends StackPane {

    /** Where {@link ItemTooltip} puts itself. Above dialogs, below the ghost. */
    private final Pane tooltipLayer = new Pane();

    /** Where the planned-item drag ghost puts itself. The very top. */
    private final Pane ghostLayer = new Pane();

    /** Run each time a dialog goes on screen. */
    private Runnable onDialogShown = () -> { };

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
        onDialogShown.run();
    }

    /** What to do whenever a dialog opens. App hides the tooltip with it. */
    public void setOnDialogShown(Runnable action) {
        onDialogShown = action == null ? () -> { } : action;
    }

    public void hideDialog(Node overlay) {
        getChildren().remove(overlay);
    }

    public Pane tooltipLayer() {
        return tooltipLayer;
    }

    public Pane ghostLayer() {
        return ghostLayer;
    }
}
