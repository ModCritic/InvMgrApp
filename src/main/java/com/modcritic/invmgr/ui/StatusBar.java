package com.modcritic.invmgr.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Duration;

/**
 * The quiet strip along the bottom that says what just happened.
 *
 * <p>It shows a message for a few seconds after an action, then goes back to reminding you what
 * the mouse does. That reversion is the point: the instructions are useful when you have just
 * opened the app and in the way once you know them, so they appear whenever nothing else is
 * being said.
 */
public final class StatusBar extends HBox {

    /** What shows when there is nothing to report. Taken from the original, verbatim. */
    public static final String DESKTOP_INSTRUCTIONS =
            "Click: edit. Right-click: delete. Hover: tooltip.";

    /**
     * The same reminder for a finger, which does none of those three things.
     *
     * <p><b>Now the whole sentence</b>, character for character as the original writes it at line
     * 672, including the double spaces before {@code ◄} and {@code ▼}, which are its own.
     *
     * <p>The tail names the three tabs of the touch layout, and it was held back at M6.2 because
     * they did not exist yet: <em>"the drawers become sliding overlays with tabs at M6.4, and that
     * is when the rest of this sentence arrives."</em> M6.4 built the tabs and left the sentence
     * alone, so a phone spent a milestone being told about two drawers it could not see named. Its
     * own row in CLAUDE.md promised this; nothing checked that the promise had been kept.
     *
     * <p>It is long, and it is <b>meant</b> to be: the original wraps it onto two lines and the
     * user has confirmed a two-line bar is wanted over a truncated one.
     */
    public static final String TOUCH_INSTRUCTIONS =
            "Tap: tooltip. Double-tap: edit. Hold 1.5s: delete. Drag: move. ► layer  ◄ items  ▼ menu";

    /** Whichever of the two describes the way this device is actually driven. */
    public static String instructions() {
        return Device.isTouch() ? TOUCH_INSTRUCTIONS : DESKTOP_INSTRUCTIONS;
    }

    private final Label label = new Label();

    /**
     * A twin of {@link #label}, kept in the scene but never laid out or drawn, used only to ask how
     * tall the instructions are.
     *
     * <p><b>It has to be a real {@link Label}, and it has to be in the scene, and both took a wrong
     * turn to arrive at.</b> First it was a Label built on the spot, but a {@code Control} makes its
     * skin lazily, so one that has never been through a CSS pass measures itself from modena's
     * defaults, and it reported a bar tall enough to push the window's own contents off the bottom
     * edge. Then it was a {@link Text}, which needs no skin and cannot get that wrong, and
     * under-reports a Label by about two pixels, which is enough for JavaFX to fit one line, find no
     * room for the second and ellipsize the lot. That shipped, and the user saw the truncated line
     * come straight back.
     *
     * <p>So: the same class, measured the same way, living in the same scene under the same
     * stylesheet: the only version that answers the question actually being asked. It is
     * <b>unmanaged</b> so the layout ignores it and <b>invisible</b> so nothing draws it; what it is
     * there for is to have a skin.
     */
    private final Label heightProbe = new Label();

    private final PauseTransition revert =
            new PauseTransition(Duration.millis(Tokens.STATUS_REVERT_MS));

    public StatusBar() {
        label.setFont(Font.font(Tokens.FONT_FAMILY, TouchType.statusFont()));
        label.setTextFill(Tokens.TEXT_STATUS);

        // The original is a plain <div>: text too long for one line wraps onto the next and the bar
        // grows to hold it. A JavaFX Label does the opposite by default (it stays one line high and
        // ellipsizes), which is why the phone showed "... Hold 1...." and lost the whole second half
        // of the sentence.
        label.setWrapText(true);
        setHgrow(label, Priority.ALWAYS);

        // And the bar may not be squeezed back to one line. A VBox short of room shrinks its
        // children towards their minimums, and a two-line bar is 15 px hungrier than the one-line
        // bar this used to be, so without this the room canvas simply takes the second line back.
        // Measured: pref 86, granted 71, which is one line exactly.
        setMinHeight(USE_PREF_SIZE);

        // ⚠ There is NO width listener here asking the parent to lay out again, and there must not
        // be. It looks like the obvious way to make a height-depends-on-width node re-measure, and
        // it cost an hour: the width changes *during* a layout pass, so re-dirtying the parent from
        // inside that pass sets the pulse looping and the scene never settles. The symptom is not a
        // hang; it is dialogs that quietly never open, because every pass is spent re-laying out.
        // computePrefHeight below is enough on its own.

        reserveBottom(0);
        setStyle("-fx-background-color: " + Tokens.hex(Tokens.BODY_BG) + ";"
                + "-fx-border-color: " + Tokens.hex(Tokens.SEPARATOR) + " transparent transparent transparent;"
                + "-fx-border-width: 1 0 0 0;");
        heightProbe.setWrapText(true);
        heightProbe.setManaged(false);
        heightProbe.setVisible(false);
        getChildren().addAll(label, heightProbe);

        revert.setOnFinished(event -> label.setText(instructions()));
        label.setText(instructions());
    }

    /**
     * How tall the bar needs to be, asked of the label at the width the label will actually get.
     *
     * <p><b>⚠ Without this, {@code setWrapText(true)} does nothing at all, and it looks like the
     * wrapping is simply broken.</b> The bar sits in a {@code VBox}, and a VBox asks each child for
     * {@code prefHeight(-1)}, its height at <em>unknown</em> width. A {@code Label} answers that
     * from its own preferred width, which is the whole sentence on one line; so it reports one line
     * high, the VBox gives it one line of room, and the label then ellipsizes to fit the box it was
     * just handed. The text wraps in principle and never in practice.
     *
     * <p>Overriding here is what breaks the circle: the bar knows its own width, so it can ask the
     * label the question that has an answer. Measured before and after on a 360 px screen: 16 px
     * tall for text that needs 574 px of line, then 28 for the two lines it really takes.
     *
     * <p>The original needs none of this because a {@code <div>} simply grows, which is the general
     * shape of the difference between the two: CSS lays out by flowing content, JavaFX by asking
     * nodes how big they would like to be.
     */
    @Override
    protected double computePrefHeight(double width) {
        // getInsets(), not getPadding(). The bar draws a 1 px rule along its top, and a border is
        // part of a Region's insets while being no part of its padding. Asking for padding alone
        // left the bar exactly one pixel short of the two lines it had just asked for; one
        // pixel is enough: JavaFX fits the first line, finds no room for the second, and ellipsizes
        // the lot. The picture showed a truncated single line while every measurement said 340 x 31
        // and "two lines", which is a maddening place to be and was a rounding error all along.
        Insets insets = getInsets();
        double forLabel = (width < 0 ? getWidth() : width) - insets.getLeft() - insets.getRight();
        if (forLabel <= 0) {
            return super.computePrefHeight(width);
        }
        return heightFor(instructions(), forLabel) + insets.getTop() + insets.getBottom();
    }

    /**
     * How tall the label would be holding a given string at a given width.
     *
     * <p><b>Always asked about the INSTRUCTIONS, never about what is on screen right now</b>; that
     * is the point of it. The instructions are the longest thing this bar ever says, so sizing
     * to them keeps the bar one height for the whole run.
     *
     * <p>Reported by the user 2026-08-13 against `InvMgr-M6.4a.apk`: a message like "Added Box" is
     * one line where the instructions are two, so the bar shrank every time something happened and
     * grew back three seconds later; the room, the layer-slider tab and the item-list tab all
     * jumped up and down with it, with Fit re-fitting the whole room each way. Their words: the rest
     * of the interface "should not depend on the status bar's size".
     *
     * <p>It is a **divergence** (§5.5): the original is a plain {@code <div>} that really does grow
     * and shrink with its text. On a desktop nobody notices, because the desktop line is one line
     * either way; on a phone the instructions need two and every message needs one, so the original
     * would jump too; it simply has not been looked at on a phone. Matching the original exactly
     * here would mean reproducing something the user has explicitly called annoying.
     */
    private double heightFor(String text, double width) {
        heightProbe.setText(text);
        heightProbe.setFont(label.getFont());
        return heightProbe.prefHeight(width);
    }

    /**
     * Grows the bar downward to sit clear of the phone's navigation buttons.
     *
     * <p>The mirror of {@code TopBar.reserveTop}, and it needs no color reasoning: this bar is
     * already painted the window's own background, so extending it downward changes nothing about
     * how the bottom edge looks. That is why the user reported the bottom of the first Android
     * build as already integrated and only the top as wrong; the seam at the top existed because
     * the top bar is a <em>different</em> color from the window behind it.
     *
     * <p>Called with zero from the constructor, so there is one place that sets this padding.
     *
     * @param extra design pixels to reserve below the bar's ordinary padding
     */
    public void reserveBottom(double extra) {
        setPadding(new Insets(TouchType.statusPaddingV(), TouchType.statusPaddingH(),
                TouchType.statusPaddingV() + extra, TouchType.statusPaddingH()));
    }

    /**
     * Shows a message, which fades back to the instructions after a few seconds.
     *
     * <p>Calling this again restarts the timer rather than stacking messages, so a burst of
     * actions leaves the last one on screen for its full time.
     */
    public void show(String message) {
        label.setText(message);
        revert.playFromStart();
    }

    /** The current text. Exposed for tests. */
    public String text() {
        return label.getText();
    }
}
