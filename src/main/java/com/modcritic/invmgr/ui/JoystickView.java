package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.threed.Joystick;
import javafx.animation.TranslateTransition;
import javafx.geometry.Point2D;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/**
 * The thumbstick you walk with on a phone: a big soft disc with a smaller one that follows your
 * thumb.
 *
 * <p>Port of the original's {@code #joystick-3d} markup and stylesheet (original lines 450-464,
 * 2886-2896). <b>This class is only the picture.</b> Where the thumb is and what that means is
 * {@link Joystick}, which holds no JavaFX; which finger owns it is {@code ui.ThreeDControls}. Three
 * pieces rather than one, the same split as {@code TouchGesture} and the red ring in the flat room.
 *
 * <h2>Two circles, drawn as circles</h2>
 *
 * <p>The original is two {@code div}s with {@code border-radius: 50%}, which is a rectangle told to
 * look round. Here they are actual {@link Circle}s, and that is worth a sentence because it changes
 * one real behavior: <b>a press just outside the disc but inside its square does not grab the
 * stick.</b> That is the more faithful answer as well as the tidier one (a browser with
 * {@code border-radius} also stops hit-testing at the curve), and it means the corner of the stick's
 * bounding box is still room you can look around in.
 *
 * <h2>Why the knob eases home but does not ease out</h2>
 *
 * <p>Let go and the knob slides back to the middle over {@value #RETURN_MS} ms. Drag it and it
 * follows your thumb <em>exactly</em>, with no smoothing at all. That asymmetry is the original's
 * ({@code #joystick-knob} has a transform transition; {@code .dragging} removes it) and it is right
 * both ways round: an eased knob under a moving thumb would lag behind the finger it is supposed to
 * be attached to, and an instant snap home on release would look like the stick had broken rather
 * than let go.
 */
public final class JoystickView extends Pane {

    /** How far from the left edge of the usable area the stick sits, in pixels. */
    public static final double INSET_LEFT = 22;

    /** How far up from the bottom of the usable area the stick sits, in pixels. */
    public static final double INSET_BOTTOM = 26;

    /** How long the knob takes to slide back to the middle once you let go, in milliseconds. */
    public static final double RETURN_MS = 150;

    /** The disc you put your thumb on: {@code rgba(48,48,56,0.55)}. */
    public static final Color BASE_FILL = Color.rgb(48, 48, 56, 0.55);

    /** The hairline round it: {@code rgba(120,130,150,0.6)}. */
    public static final Color BASE_BORDER = Color.rgb(120, 130, 150, 0.6);

    /** The disc that follows your thumb: {@code rgba(90,100,120,0.85)}. */
    public static final Color KNOB_FILL = Color.rgb(90, 100, 120, 0.85);

    /** The hairline round that: {@code #99a}. */
    public static final Color KNOB_BORDER = Color.web("#99a");

    private final Circle base = new Circle(Joystick.BASE_DIAMETER_PX / 2);
    private final Circle knob = new Circle(Joystick.KNOB_DIAMETER_PX / 2);
    private final TranslateTransition returnHome =
            new TranslateTransition(Duration.millis(RETURN_MS), knob);

    public JoystickView() {
        double side = Joystick.BASE_DIAMETER_PX;
        setMinSize(side, side);
        setPrefSize(side, side);
        setMaxSize(side, side);

        // Only the discs are touchable, not the square they sit in. Without this the corners of
        // the bounding box would grab the stick from a finger that plainly missed it.
        setPickOnBounds(false);

        base.setFill(BASE_FILL);
        base.setStroke(BASE_BORDER);
        base.setStrokeWidth(1);
        base.setCenterX(side / 2);
        base.setCenterY(side / 2);

        knob.setFill(KNOB_FILL);
        knob.setStroke(KNOB_BORDER);
        knob.setStrokeWidth(1);
        knob.setCenterX(side / 2);
        knob.setCenterY(side / 2);

        // The knob is deliberately NOT mouse-transparent, and it matters in exactly one place: at
        // full deflection its edge reaches 55 px from the middle against the base's 54, so there is
        // a one-pixel ring where the knob is there and the base is not. A press on something you
        // can plainly see should take hold of it. The original behaves the same way; its knob is a
        // child of its base with no `pointer-events: none`, so a press on it bubbles to the base's
        // own listener. Everywhere else the two answers are identical, because the finger is asked
        // about with `belongsTo(the whole stick)` rather than about either circle.
        getChildren().addAll(base, knob);
    }

    /**
     * Draws the knob wherever the thumb has pulled it, in pixels from the middle of the base.
     *
     * <p>Takes the already-clamped offsets from {@link Joystick} rather than clamping again here.
     * One copy of "how far the knob may travel", and it is the copy that also decides how fast you
     * walk; a picture that disagreed with the movement would be a stick that lies about what it is
     * doing.
     */
    public void deflect(double dxPx, double dyPx) {
        // Any ease still running is from a previous release, and it would fight the thumb.
        returnHome.stop();
        knob.setTranslateX(dxPx);
        knob.setTranslateY(dyPx);
    }

    /** Lets the knob slide back to the middle. */
    public void release() {
        returnHome.stop();
        returnHome.setToX(0);
        returnHome.setToY(0);
        returnHome.play();
    }

    /**
     * Puts the knob back in the middle at once, with no animation.
     *
     * <p>For leaving the 3D view rather than for letting go of the stick: the whole thing is about
     * to be hidden, and an animation running against a hidden node is a timer nobody stops.
     */
    public void reset() {
        returnHome.stop();
        knob.setTranslateX(0);
        knob.setTranslateY(0);
    }

    /**
     * Turns a point on the screen into a displacement from the middle of the base.
     *
     * <p><b>Through {@code sceneToLocal}, not by subtracting numbers.</b> The stick is drawn inside
     * the 3D view's scaled chrome, so at a Ctrl+scroll zoom of 125% a finger that travels a hundred
     * pixels across the glass has moved eighty across the stick; eighty is the number the knob
     * is drawn with and the number the thirty-pixel radius is measured in. Doing the subtraction in
     * scene coordinates would make the stick more sensitive the further you zoomed in, which is the
     * shape of the M3.2 bug where a coordinate from one space was used in another.
     *
     * @param sceneX where the finger is, as a touch event reports it
     * @return how far right and below the middle of the base that is, in the stick's own pixels
     */
    public Point2D fromCenter(double sceneX, double sceneY) {
        Point2D local = sceneToLocal(sceneX, sceneY);
        return new Point2D(local.getX() - Joystick.BASE_DIAMETER_PX / 2,
                local.getY() - Joystick.BASE_DIAMETER_PX / 2);
    }

    /** The disc a thumb presses, so a test can find it and aim at it. */
    public Circle base() {
        return base;
    }

    /** The disc that follows the thumb, so a test can ask where it was drawn. */
    public Circle knob() {
        return knob;
    }
}
