package com.modcritic.invmgr.ui;

import java.util.Optional;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tooltip;
import javafx.scene.text.Font;
import javafx.util.Duration;

/**
 * Two small things the browser gave the original for free, and Java does not: the little
 * explanatory label that appears when you rest on a button, and the "are you sure?" box.
 *
 * <p>The original writes {@code title="Fit to Screen"} on a button and the browser handles the
 * rest, and calls {@code confirm(...)} and the browser puts up a dialog. Neither exists in
 * JavaFX, so both are built here — in one place, so every button's hint looks the same.
 */
public final class Hints {

    /**
     * How long the pointer has to rest before a hint appears.
     *
     * <p>Browsers use around half a second and JavaFX defaults to a full one, which feels
     * sluggish next to the original. Matching the browser is the point.
     */
    private static final Duration DELAY = Duration.millis(500);

    private Hints() {
    }

    /**
     * A hint label for a button.
     *
     * <p>Painted like the app's own item tooltip — near-black on a grey hairline — rather than
     * left as JavaFX's pale default, which would be the only light-coloured thing in the
     * window.
     */
    public static Tooltip tooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_TOOLTIP));
        tooltip.setShowDelay(DELAY);
        tooltip.setStyle("-fx-background-color: " + Tokens.hex(Tokens.TOOLTIP_BG) + ";"
                + "-fx-text-fill: " + Tokens.hex(Tokens.TEXT_INPUT) + ";"
                + "-fx-border-color: " + Tokens.hex(Tokens.TOOLTIP_BORDER) + ";"
                + "-fx-border-width: 1;"
                + "-fx-background-radius: 0; -fx-border-radius: 0;");
        return tooltip;
    }

    /**
     * Asks a yes/no question and waits for the answer.
     *
     * <p>Used before deleting a preset, which cannot be undone.
     *
     * @param scene the scene to hang the question off, so it appears over the app rather than
     *     wherever the window manager fancies. May be null, in which case it still works.
     * @return true if the user said yes
     */
    public static boolean confirm(Scene scene, String question) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, question,
                ButtonType.CANCEL, ButtonType.OK);
        alert.setHeaderText(null);
        alert.setTitle("InvMgr");
        if (scene != null && scene.getWindow() != null) {
            alert.initOwner(scene.getWindow());
        }
        Optional<ButtonType> answer = alert.showAndWait();
        return answer.isPresent() && answer.get() == ButtonType.OK;
    }
}
