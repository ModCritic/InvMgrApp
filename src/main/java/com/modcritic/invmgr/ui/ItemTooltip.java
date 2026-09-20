package com.modcritic.invmgr.ui;

import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.util.Duration;

/**
 * The little black label that appears beside the pointer when it rests on a box.
 *
 * <p>It says the box's name and its three measurements, plus how far off the floor it sits,
 * which is the one thing a top-down view genuinely cannot show you. Two boxes drawn on top of
 * each other look identical from above whether one is stacked on the other or they are simply
 * overlapping, and {@code base:} is what tells them apart.
 *
 * <p><b>Desktop behavior only.</b> It follows the cursor at a fixed offset and vanishes when
 * the pointer leaves the box: no clamping to the window edge, no timeout. The touch version is
 * a different thing entirely (centered over the box, clamped, and scrolling if the name is long),
 * and belongs with the rest of the touch input work.
 */
public final class ItemTooltip {

    /** How far from the cursor the tooltip's corner sits. From the original, exactly. */
    private static final double OFFSET_X = 14;
    private static final double OFFSET_Y = 10;

    /** How close a tapped tooltip may come to the edge of the window before it is pushed back. */
    private static final double EDGE_MARGIN = Tokens.TOUCH_TOOLTIP_MARGIN;

    /** Where a tapped tooltip goes when there is no room for it above the box. */
    private static final double BELOW_OFFSET = 24;

    /**
     * The hairline the box is drawn with, in pixels. JavaFX paints a border <em>inside</em> the
     * region's bounds, so it comes off the room available for the words.
     */
    private static final double BORDER = 1;

    private final Pane layer;
    private final Label label = new Label();

    /**
     * The tapped tooltip is a different node from the hovered one, and it has to be.
     *
     * <p>A hover tooltip is one label that shrink-wraps its text. A tapped one is a box of a
     * <b>decided</b> width with the text sliding about inside it, which is three nodes: the box that
     * is clipped, the track that moves, and the copies of the words on it. Trying to be both at once
     * would mean a label that sometimes ignores its own preferred width, and the desktop tooltip
     * (which has been right since M3 and is checked against the reference screenshots) would be
     * carrying the weight of a feature it never uses.
     */
    private final Pane touchBox = new Pane();

    /** What slides. Two copies of the name with a gap, or one copy when it fits and nothing moves. */
    private final HBox track = new HBox();

    private final Label firstCopy = new Label();
    private final Label secondCopy = new Label();

    /** Cuts the track off at the edges of the box. Resized with the box on every showing. */
    private final Rectangle boxClip = new Rectangle();

    /** The slide itself. One transition reused, so a second tap replaces it rather than racing it. */
    private final TranslateTransition marquee = new TranslateTransition(Duration.ZERO, track);

    /**
     * Takes the tooltip away again after a tap, because nothing else will.
     *
     * <p>One transition reused rather than a new one per tap: {@code playFromStart} restarts it, so
     * tapping a second box replaces the countdown instead of racing it.
     */
    private final PauseTransition hideTimer =
            new PauseTransition(Duration.millis(Tokens.TOUCH_TOOLTIP_HOLD_MS));

    /**
     * @param layer the floating layer it draws into (see {@link Overlays}, which puts that
     *     layer above the dialogs so a tooltip is never hidden behind one)
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

        // Removing the nodes directly rather than calling hide(): hide() stops this very timer,
        // and a PauseTransition stopping itself from inside its own finish handler is a knot not
        // worth tying.
        hideTimer.setOnFinished(event -> {
            layer.getChildren().removeAll(label, touchBox);
            marquee.stop();
        });

        for (Label copy : new Label[] { firstCopy, secondCopy }) {
            copy.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_TOOLTIP_TOUCH));
            copy.setTextFill(Tokens.TEXT_INPUT);
            copy.setWrapText(false);
        }
        track.setSpacing(Tokens.TOUCH_TOOLTIP_MARQUEE_GAP);
        touchBox.getChildren().add(track);
        touchBox.setClip(boxClip);
        touchBox.setStyle("-fx-background-color: " + Tokens.hex(Tokens.TOOLTIP_BG) + ";"
                + "-fx-border-color: " + Tokens.hex(Tokens.TOOLTIP_BORDER) + ";"
                + "-fx-border-width: " + BORDER + ";"
                + "-fx-background-radius: 0; -fx-border-radius: 0;");

        marquee.setInterpolator(Interpolator.LINEAR);
        marquee.setCycleCount(TranslateTransition.INDEFINITE);
        marquee.setDelay(Duration.millis(Tokens.TOUCH_TOOLTIP_MARQUEE_DELAY_MS));
        marquee.setFromX(0);
    }

    /**
     * Shows the tooltip beside a point in the window.
     *
     * <p>Calling it again just moves it, which is what makes it follow the cursor smoothly
     * rather than flickering off and on.
     *
     * @param sceneX where the pointer is, in <b>scene</b> coordinates, the actual pixels of the
     *     window, which is what a {@code MouseEvent} reports
     */
    public void show(String text, double sceneX, double sceneY) {
        label.setText(text);
        // A tapped tooltip may still be up: the two are different nodes and only one belongs on
        // screen at a time, or a hover would leave a scrolling box sitting behind it.
        marquee.stop();
        layer.getChildren().remove(touchBox);
        if (!layer.getChildren().contains(label)) {
            layer.getChildren().add(label);
        }
        // Scene coordinates in, layer coordinates out, and the two are NOT the same thing once
        // the interface zoom is off 100%. The whole interface (this layer included) is drawn
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
        // coordinates and therefore grows with the zoom: the gap looks the same at every size,
        // exactly like every other measurement in the interface.
        label.setLayoutX(local.getX() + OFFSET_X);
        label.setLayoutY(local.getY() + OFFSET_Y);
    }

    /**
     * Shows the tooltip above a box that has just been tapped, and takes it away again by itself.
     *
     * <p>Three things differ from the desktop version, and each of them is because a finger is not
     * a pointer:
     *
     * <ul>
     *   <li><b>It sits above the box, centered on it</b>, rather than beside the touch. A fingertip
     *       covers what it is touching, so a label under it could not be read.
     *   <li><b>It times itself out.</b> A pointer leaves a box and the tooltip goes; a finger just
     *       lifts, and nothing would ever tell it to.
     *   <li><b>It is clamped inside the window.</b> On a phone the room reaches the glass, so a box
     *       against a wall would otherwise push its label off the edge.
     *   <li><b>A name too long for the room it has scrolls</b>, rather than being cut off. See
     *       {@link #budgetFor} for how much room that is, and why it is not simply the screen.
     * </ul>
     *
     * @param anchorSceneX the middle of the box, in scene coordinates
     * @param anchorSceneY ten pixels above the box's top edge, in scene coordinates
     */
    public void showForTouch(String text, double anchorSceneX, double anchorSceneY) {
        marquee.stop();
        track.setTranslateX(0);
        firstCopy.setText(text);
        secondCopy.setText(text);
        track.getChildren().setAll(firstCopy);

        layer.getChildren().remove(label);
        if (!layer.getChildren().contains(touchBox)) {
            layer.getChildren().add(touchBox);
        }

        // Everything from here on is in the LAYER's coordinates, not the scene's. The whole
        // interface is drawn through one Scale transform, so a scene position used as a layout
        // position gets multiplied by the zoom a second time: the bug M3.2 fixed for the hover
        // tooltip, and it would come straight back here.
        javafx.geometry.Point2D anchor = layer.sceneToLocal(anchorSceneX, anchorSceneY);

        // The words have to be laid out before their own size can be read, or every number below
        // uses whatever the previous tooltip measured. Same trap as M3.4's glyphs: every number
        // agrees with itself and the picture is still wrong.
        layer.applyCss();
        layer.layout();
        double wordsWidth = firstCopy.prefWidth(-1);
        double wordsHeight = firstCopy.prefHeight(wordsWidth);

        double chrome = 2 * (BORDER + Tokens.TOOLTIP_PADDING_H_TOUCH);
        double natural = wordsWidth + chrome;
        double budget = budgetFor(anchor.getX());
        double boxWidth = Math.min(natural, budget);
        double boxHeight = wordsHeight + 2 * (BORDER + Tokens.TOOLTIP_PADDING_V_TOUCH);

        double holdMs = Tokens.TOUCH_TOOLTIP_HOLD_MS;
        if (natural > budget) {
            holdMs = startTheMarquee(wordsWidth);
        }

        touchBox.setMinSize(boxWidth, boxHeight);
        touchBox.setPrefSize(boxWidth, boxHeight);
        touchBox.setMaxSize(boxWidth, boxHeight);
        boxClip.setWidth(boxWidth);
        boxClip.setHeight(boxHeight);
        track.setLayoutX(BORDER + Tokens.TOOLTIP_PADDING_H_TOUCH);
        track.setLayoutY(BORDER + Tokens.TOOLTIP_PADDING_V_TOUCH);

        double x = anchor.getX() - boxWidth / 2;
        double y = anchor.getY() - boxHeight;
        if (y < EDGE_MARGIN) {
            // No room above: a box against the top wall, or under the phone's status bar. Put it
            // below the finger instead, which is the one place guaranteed to be clear.
            y = anchor.getY() + BELOW_OFFSET;
        }

        touchBox.setLayoutX(clamp(x, boxWidth, layer.getWidth()));
        touchBox.setLayoutY(clamp(y, boxHeight, layer.getHeight()));

        hideTimer.setDuration(Duration.millis(holdMs));
        hideTimer.playFromStart();
    }

    /**
     * How wide the tapped tooltip is allowed to be, given where the box it names is.
     *
     * <p><b>Twice the smaller of the two gaps either side, never the whole window.</b> The tooltip
     * is centered on the box, so the width it can use without shifting off-center is the narrower
     * side doubled, and centering is what makes it obvious which box the words belong to. Measuring
     * the whole window instead is the mistake the original records having made: the tooltip then
     * leaned toward whichever side had more room, and looked off-center by an amount that changed
     * with where the box happened to sit.
     *
     * <p>The floor of {@link Tokens#TOUCH_TOOLTIP_MIN_WIDTH} is for a box hard against a wall, where
     * that arithmetic works out at almost nothing.
     *
     * @param anchorX the middle of the box, in the layer's own coordinates
     */
    private double budgetFor(double anchorX) {
        double toTheLeft = anchorX - EDGE_MARGIN;
        double toTheRight = layer.getWidth() - EDGE_MARGIN - anchorX;
        return Math.max(Tokens.TOUCH_TOOLTIP_MIN_WIDTH, 2 * Math.min(toTheLeft, toTheRight));
    }

    /**
     * Puts a second copy of the name on the track and starts it sliding.
     *
     * <p><b>Two copies, not one.</b> A single copy sliding off the left would leave the box empty
     * until it came round again; a second copy a fixed gap behind it means there is always something
     * to read, and the loop point is invisible. The slide is exactly one copy plus one gap, which
     * puts the second copy precisely where the first started: the original's {@code translateX
     * (-50%)} over a track holding two of them.
     *
     * @param wordsWidth how wide one copy of the name is
     * @return how long the tooltip should now stay up, in milliseconds
     */
    private double startTheMarquee(double wordsWidth) {
        track.getChildren().setAll(firstCopy, secondCopy);
        double onePass = wordsWidth + Tokens.TOUCH_TOOLTIP_MARQUEE_GAP;

        // The distance sets the duration, so without a floor a name only slightly too long would
        // whip past too fast to read.
        double seconds = Math.max(Tokens.TOUCH_TOOLTIP_MARQUEE_MIN_PASS_S,
                onePass / Tokens.TOUCH_TOOLTIP_MARQUEE_PX_PER_SEC);
        marquee.setDuration(Duration.seconds(seconds));
        marquee.setToX(-onePass);
        marquee.playFromStart();

        // Long enough for one whole pass, plus the pause before it starts and a moment after it
        // finishes. Taking a scrolling name away halfway through is worse than not scrolling it.
        return Math.max(Tokens.TOUCH_TOOLTIP_HOLD_MS,
                Tokens.TOUCH_TOOLTIP_MARQUEE_DELAY_MS + seconds * 1000
                        + Tokens.TOUCH_TOOLTIP_MARQUEE_TAIL_MS);
    }

    /** Keeps one edge of the box a margin inside the layer, preferring the near edge if both fail. */
    private static double clamp(double position, double size, double extent) {
        double furthest = extent - size - EDGE_MARGIN;
        return Math.max(EDGE_MARGIN, Math.min(position, Math.max(EDGE_MARGIN, furthest)));
    }

    public void hide() {
        // Stopped as well as removed. Without this, tapping a box and then resting a pointer on
        // another one leaves the tap's timer running, and it hides the hover tooltip 2.5 s later
        // for no reason anyone could see.
        hideTimer.stop();
        marquee.stop();
        layer.getChildren().removeAll(label, touchBox);
    }

    public boolean isShowing() {
        return layer.getChildren().contains(label) || layer.getChildren().contains(touchBox);
    }

    /** The current text, or null when nothing is showing. For tests. */
    public String text() {
        if (layer.getChildren().contains(label)) {
            return label.getText();
        }
        return layer.getChildren().contains(touchBox) ? firstCopy.getText() : null;
    }

    /** Whether the name is too long for the room it has and is sliding past. For tests. */
    public boolean isScrolling() {
        return track.getChildren().size() > 1;
    }

    /** How far the name has slid, in the box's own pixels. For tests. */
    public double scrolledBy() {
        return -track.getTranslateX();
    }

    /** How long one whole pass of the name takes, in seconds. For tests. */
    public double passSeconds() {
        return marquee.getDuration().toSeconds();
    }

    /**
     * The label itself, so a test can measure where it actually landed on screen.
     *
     * <p>Needed because the only way to catch the zoom bug above is to compare the label's
     * <em>scene</em> bounds against the pointer's scene position; its layout position is in the
     * layer's coordinates and looks perfectly correct even when it is wrong.
     *
     * <p>Whichever of the two is on screen: the label for a hover, the box for a tap. A caller that
     * measured only the label would silently measure a node that is not in the window at all after
     * a tap, and report bounds of zero as though the tooltip were at the top-left corner.
     */
    public javafx.scene.layout.Region node() {
        return layer.getChildren().contains(touchBox) ? touchBox : label;
    }
}
