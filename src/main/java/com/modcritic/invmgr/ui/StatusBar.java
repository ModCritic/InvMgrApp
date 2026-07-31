package com.modcritic.invmgr.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.text.Font;
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

    private final Label label = new Label();
    private final PauseTransition revert =
            new PauseTransition(Duration.millis(Tokens.STATUS_REVERT_MS));

    public StatusBar() {
        label.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_STATUS));
        label.setTextFill(Tokens.TEXT_STATUS);

        setPadding(new Insets(Tokens.STATUS_PADDING_V, Tokens.STATUS_PADDING_H,
                Tokens.STATUS_PADDING_V, Tokens.STATUS_PADDING_H));
        setStyle("-fx-background-color: " + Tokens.hex(Tokens.BODY_BG) + ";"
                + "-fx-border-color: " + Tokens.hex(Tokens.SEPARATOR) + " transparent transparent transparent;"
                + "-fx-border-width: 1 0 0 0;");
        getChildren().add(label);

        revert.setOnFinished(event -> label.setText(DESKTOP_INSTRUCTIONS));
        label.setText(DESKTOP_INSTRUCTIONS);
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
