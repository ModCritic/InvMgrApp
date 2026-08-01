package com.modcritic.invmgr.ui;

import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.transform.Rotate;

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
 *   <li><b>It swings as you carry it.</b> The card hangs from the point you grabbed it, with
 *       weight: it leans as you start moving, hangs straight while you carry it along, and swings
 *       past straight and settles when you stop. It is the only piece of playfulness in the app,
 *       and it is what makes a drag feel like carrying something rather than sliding a rectangle.
 *       The arithmetic is {@link TiltPendulum}'s; this class only feeds it and applies the result.
 *   <li><b>It fades slightly over the room.</b> Dropping to 0.8 opacity is the signal that
 *       letting go here will actually place the item — releasing anywhere else cancels.
 * </ul>
 *
 * <p>This is also the one place in the app with a rounded corner and a drop shadow, both
 * deliberate: the design system allows shadows only on things that are moving, and the ghost is
 * the definition of moving.
 */
public final class DragGhost {

    /** How see-through it goes once it is over the room. */
    private static final double OVER_ROOM_OPACITY = 0.8;

    private final Pane layer;
    private final HBox card = new HBox(Tokens.LIST_ROW_GAP);
    private final Circle dot = new Circle(Tokens.LIST_DOT_RADIUS);
    private final Label name = new Label();

    /**
     * The swing, as a rotation about the point the card was grabbed by.
     *
     * <p>A {@code Rotate} transform rather than {@code setRotate}, because that turns a node about
     * the centre of its own box and there is no way to move it. A card held by its middle and a
     * card held near one end swing quite differently, and which one this is depends on where the
     * pointer came down on the row — so the pivot has to move with the grab. Set in {@link #lift}.
     */
    private final Rotate swing = new Rotate();

    /** What decides the angle. See {@link TiltPendulum} for why it is a spring and not a formula. */
    private final TiltPendulum pendulum = new TiltPendulum();

    /**
     * Where the pointer was at the last mouse event, in scene coordinates.
     *
     * <p>Written by {@link #moveTo} and read by {@link #ticker}, which is the whole reason it is a
     * field. <b>The card's position follows the mouse event and its lean follows the clock</b>, and
     * those have to be separate: the card must be under the cursor the instant the cursor moves, or
     * dragging feels laggy, while the lean must advance on every frame whether the hand moved or
     * not, or it freezes at whatever the last event said — which is exactly what the old
     * event-driven version did when you stopped moving.
     */
    private double pointerSceneX;

    /**
     * Advances the swing once per frame while a card is being carried.
     *
     * <p>Running on the clock rather than on mouse events is also what makes the movement mean the
     * same thing on every machine. The old rule measured the gap between two consecutive mouse
     * events and had no idea how long that gap was, so the same hand movement leaned twice as far
     * on a computer delivering half as many events. Here the elapsed time is divided out.
     */
    private final AnimationTimer ticker = new AnimationTimer() {
        private long lastNanos;

        @Override
        public void handle(long nowNanos) {
            if (lastNanos != 0) {
                swing.setAngle(pendulum.step(pointerSceneX, (nowNanos - lastNanos) / 1e9));
            }
            lastNanos = nowNanos;
        }

        @Override
        public void start() {
            // Cleared here rather than in stop(), because the first frame after a start has no
            // previous one to measure against and would otherwise be handed the length of the
            // pause since the last drag.
            lastNanos = 0;
            super.start();
        }
    };

    /**
     * Where in the card the pointer grabbed it, so it does not jump on the first frame.
     *
     * <p>In the <b>layer's</b> coordinates, like everything else this class positions — see
     * {@link #toLayer}.
     */
    private double grabOffsetX;
    private double grabOffsetY;

    public DragGhost(Pane layer) {
        this.layer = layer;
        card.getTransforms().add(swing);

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

        // The card hangs from where it was grabbed, so that is where it turns about. The offset is
        // measured from the row's corner, and the card is laid out with its corner on that point,
        // so the same two numbers are the pivot in the card's own coordinates.
        swing.setPivotX(grabOffsetX);
        swing.setPivotY(grabOffsetY);
        swing.setAngle(0);
        pendulum.reset(pointerX);
        pointerSceneX = pointerX;

        card.setOpacity(1);
        card.setLayoutX(row.getX());
        card.setLayoutY(row.getY());
        if (!layer.getChildren().contains(card)) {
            layer.getChildren().add(card);
        }
        ticker.start();
    }

    /**
     * Moves the ghost to follow the pointer.
     *
     * <p>Position only — the lean is the ticker's business, and all this does towards it is record
     * where the pointer got to. See {@link #pointerSceneX} for why the two are split.
     *
     * @param pointerX where the pointer is, in scene coordinates
     */
    public void moveTo(double pointerX, double pointerY, boolean overRoom) {
        pointerSceneX = pointerX;

        javafx.geometry.Point2D pointer = toLayer(pointerX, pointerY);
        card.setLayoutX(pointer.getX() - grabOffsetX);
        card.setLayoutY(pointer.getY() - grabOffsetY);
        card.setOpacity(overRoom ? OVER_ROOM_OPACITY : 1);
    }

    public void drop() {
        // Stopped first: an AnimationTimer left running would go on stepping the pendulum against
        // a pointer position nothing updates any more, for the rest of the session.
        ticker.stop();
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

    /**
     * How far the card is currently leaning, in degrees, positive clockwise.
     *
     * <p>Not {@code node().getRotate()}, which is zero and always will be — the lean is a
     * {@link Rotate} in the card's transform list so that it can turn about the grab point rather
     * than about its own middle.
     */
    public double tiltDegrees() {
        return swing.getAngle();
    }
}
