package com.modcritic.invmgr.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;

/**
 * The little card that follows the pointer while a planned item is being dragged out of the
 * list and into the room.
 *
 * <p>It shows exactly what the row showed — the colour dot and the name with {@code [plan]} on
 * the end — so it reads as the row itself having been picked up rather than as a new thing
 * appearing.
 *
 * <p>Two details are doing real work:
 *
 * <ul>
 *   <li><b>It tilts with horizontal speed.</b> Up to 16° either way, proportional to how fast
 *       the pointer is moving sideways. It is the only piece of playfulness in the app, and it
 *       is what makes a drag feel like carrying something rather than sliding a rectangle.
 *   <li><b>It fades slightly over the room.</b> Dropping to 0.8 opacity is the signal that
 *       letting go here will actually place the item — releasing anywhere else cancels.
 * </ul>
 *
 * <p>This is also the one place in the app with a rounded corner and a drop shadow, both
 * deliberate: the design system allows shadows only on things that are moving, and the ghost is
 * the definition of moving.
 */
public final class DragGhost {

    /** Degrees of tilt per pixel of sideways movement between two frames. */
    private static final double TILT_PER_PX = 1.4;

    /** The most it will ever lean, in degrees. */
    private static final double MAX_TILT_DEGREES = 16;

    /** How see-through it goes once it is over the room. */
    private static final double OVER_ROOM_OPACITY = 0.8;

    private final Pane layer;
    private final HBox card = new HBox(Tokens.LIST_ROW_GAP);
    private final Circle dot = new Circle(Tokens.LIST_DOT_RADIUS);
    private final Label name = new Label();

    /**
     * Where in the card the pointer grabbed it, so it does not jump on the first frame.
     *
     * <p>In the <b>layer's</b> coordinates, like everything else this class positions — see
     * {@link #toLayer}.
     */
    private double grabOffsetX;
    private double grabOffsetY;

    /**
     * The previous frame's pointer X, which is what the tilt is computed from.
     *
     * <p>The one thing here kept in <b>scene</b> coordinates. The tilt is a reaction to how fast
     * the pointer swept across the glass, and a hand movement is a physical thing that does not
     * change when the interface is zoomed — so the lean stays identical at every zoom level,
     * where a layer-space delta would have halved it at 200%.
     */
    private double lastSceneX;

    public DragGhost(Pane layer) {
        this.layer = layer;

        name.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_LIST_ROW));
        name.setTextFill(Tokens.TEXT_INPUT);

        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(Tokens.LIST_ROW_PADDING_V, Tokens.LIST_ROW_PADDING_H,
                Tokens.LIST_ROW_PADDING_V, Tokens.LIST_ROW_PADDING_H));
        card.getChildren().addAll(dot, name);
        card.setStyle("-fx-background-color: " + Tokens.hex(Tokens.DIALOG_BG) + ";"
                + "-fx-border-color: " + Tokens.hex(Tokens.CONTROL_BORDER) + ";"
                + "-fx-border-width: 1;"
                // The one 8px radius in the app, and the one shadow on a moving element.
                + "-fx-background-radius: " + Tokens.GHOST_RADIUS + ";"
                + "-fx-border-radius: " + Tokens.GHOST_RADIUS + ";"
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.55), 16, 0, 0, 6);");
    }

    /**
     * A point in the window, in the coordinates this layer positions its children with.
     *
     * <p>The two are only the same at 100%. The whole interface, this layer included, is drawn
     * through the interface-zoom Scale transform (see {@code App} and {@link UiScale}), so a
     * layout position of 500 in here lands at 750 on the glass at 125%. Passing a raw scene
     * coordinate to {@code setLayoutX} therefore applied the zoom to the pointer's position a
     * second time, and the ghost ran away from the cursor — worse the further across the window
     * it went. Reported by the user 2026-07-30, alongside the same bug in {@link ItemTooltip}.
     */
    private javafx.geometry.Point2D toLayer(double sceneX, double sceneY) {
        return layer.sceneToLocal(sceneX, sceneY);
    }

    /**
     * Lifts the ghost off a row.
     *
     * @param width the row's width in the list's own coordinates, so the card is the same size
     *     as what was picked up — the list is inside the same zoom transform as this layer, so
     *     the number carries across unchanged
     * @param rowSceneX where the row's top-left corner is in the window, in scene coordinates
     * @param pointerX where the pointer is, in scene coordinates
     */
    public void lift(String text, Color colour, double width, double rowSceneX,
            double rowSceneY, double pointerX, double pointerY) {
        name.setText(text);
        dot.setFill(colour);
        card.setPrefWidth(width);
        card.setMinWidth(width);

        javafx.geometry.Point2D row = toLayer(rowSceneX, rowSceneY);
        javafx.geometry.Point2D pointer = toLayer(pointerX, pointerY);
        grabOffsetX = pointer.getX() - row.getX();
        grabOffsetY = pointer.getY() - row.getY();
        lastSceneX = pointerX;

        card.setRotate(0);
        card.setOpacity(1);
        card.setLayoutX(row.getX());
        card.setLayoutY(row.getY());
        if (!layer.getChildren().contains(card)) {
            layer.getChildren().add(card);
        }
    }

    /**
     * Moves the ghost to follow the pointer, leaning into the movement.
     *
     * @param pointerX where the pointer is, in scene coordinates
     */
    public void moveTo(double pointerX, double pointerY, boolean overRoom) {
        double tilt = Math.max(-MAX_TILT_DEGREES,
                Math.min(MAX_TILT_DEGREES, (pointerX - lastSceneX) * TILT_PER_PX));
        lastSceneX = pointerX;

        javafx.geometry.Point2D pointer = toLayer(pointerX, pointerY);
        card.setRotate(tilt);
        card.setLayoutX(pointer.getX() - grabOffsetX);
        card.setLayoutY(pointer.getY() - grabOffsetY);
        card.setOpacity(overRoom ? OVER_ROOM_OPACITY : 1);
    }

    public void drop() {
        layer.getChildren().remove(card);
    }

    public boolean isShowing() {
        return layer.getChildren().contains(card);
    }

    /**
     * The card itself, so a test can measure where it actually landed on screen.
     *
     * <p>Its layout position is in the layer's coordinates and looks correct even when the card
     * is visibly nowhere near the cursor; only its scene bounds show the zoom bug.
     */
    public HBox node() {
        return card;
    }
}
