package com.modcritic.invmgr.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.text.Font;

/**
 * The little black label that appears beside the pointer when it rests on a box.
 *
 * <p>It says the box's name and its three measurements, plus how far off the floor it sits —
 * which is the one thing a top-down view genuinely cannot show you. Two boxes drawn on top of
 * each other look identical from above whether one is stacked on the other or they are simply
 * overlapping, and {@code base:} is what tells them apart.
 *
 * <p><b>Desktop behaviour only.</b> It follows the cursor at a fixed offset and vanishes when
 * the pointer leaves the box — no clamping to the window edge, no timeout. The touch version is
 * a different thing entirely (centred over the box, clamped, and scrolling if the name is long),
 * and belongs with the rest of the touch input work.
 */
public final class ItemTooltip {

    /** How far from the cursor the tooltip's corner sits. From the original, exactly. */
    private static final double OFFSET_X = 14;
    private static final double OFFSET_Y = 10;

    private final Pane layer;
    private final Label label = new Label();

    /**
     * @param layer the floating layer it draws into — see {@link Overlays}, which puts that
     *     layer above the dialogs so a tooltip is never hidden behind one
     */
    public ItemTooltip(Pane layer) {
        this.layer = layer;

        label.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_TOOLTIP));
        label.setTextFill(Tokens.TEXT_INPUT);
        label.setPadding(new Insets(Tokens.TOOLTIP_PADDING_V, Tokens.TOOLTIP_PADDING_H,
                Tokens.TOOLTIP_PADDING_V, Tokens.TOOLTIP_PADDING_H));
        label.setWrapText(false);
        label.setStyle("-fx-background-color: " + Tokens.hex(Tokens.TOOLTIP_BG) + ";"
                + "-fx-border-color: " + Tokens.hex(Tokens.TOOLTIP_BORDER) + ";"
                + "-fx-border-width: 1;"
                + "-fx-background-radius: 0; -fx-border-radius: 0;");
    }

    /**
     * Shows the tooltip beside a point in the window.
     *
     * <p>Calling it again just moves it, which is what makes it follow the cursor smoothly
     * rather than flickering off and on.
     *
     * @param sceneX where the pointer is, in <b>scene</b> coordinates — the actual pixels of the
     *     window, which is what a {@code MouseEvent} reports
     */
    public void show(String text, double sceneX, double sceneY) {
        label.setText(text);
        if (!layer.getChildren().contains(label)) {
            layer.getChildren().add(label);
        }
        // Scene coordinates in, layer coordinates out, and the two are NOT the same thing once
        // the interface zoom is off 100%. The whole interface -- this layer included -- is drawn
        // through one Scale transform (see App and UiScale), so a layout position of 500 inside
        // the layer lands at 750 on the glass at 125%. Handing the raw scene X straight to
        // setLayoutX therefore multiplied the pointer's position by the zoom a second time, and
        // the tooltip drifted further from the cursor the further right the cursor was: at 200%
        // and 740 px across the window it appeared 768 px away, and past the right of the room
        // it left the screen entirely. Reported by the user 2026-07-30 from the M3-fixes jar.
        //
        // sceneToLocal undoes every transform between the scene and the layer, so this is right
        // for any transform anyone adds above it later, not just this one scale.
        javafx.geometry.Point2D local = layer.sceneToLocal(sceneX, sceneY);
        // The offset is added AFTER the conversion, so it is a distance in the layer's own
        // coordinates and therefore grows with the zoom -- the gap looks the same at every size,
        // exactly like every other measurement in the interface.
        label.setLayoutX(local.getX() + OFFSET_X);
        label.setLayoutY(local.getY() + OFFSET_Y);
    }

    public void hide() {
        layer.getChildren().remove(label);
    }

    public boolean isShowing() {
        return layer.getChildren().contains(label);
    }

    /** The current text, or null when nothing is showing. For tests. */
    public String text() {
        return isShowing() ? label.getText() : null;
    }

    /**
     * The label itself, so a test can measure where it actually landed on screen.
     *
     * <p>Needed because the only way to catch the zoom bug above is to compare the label's
     * <em>scene</em> bounds against the pointer's scene position — its layout position is in the
     * layer's coordinates and looks perfectly correct even when it is wrong.
     */
    public Label node() {
        return label;
    }
}
