package com.modcritic.invmgr.ui;

import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.TextField;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;

/**
 * A number entry box with the little up/down stepper on its right.
 *
 * <p><b>Why this exists rather than a plain text field.</b> The original app uses an HTML
 * {@code <input type="number">}, and the browser draws a stepper inside it — a pale block with
 * two small chevrons. The app's stylesheet never mentions it, so it is invisible in the CSS and
 * easy to miss, but it is in every desktop reference screenshot and it is what the user sees.
 * This is the same class of detail as the layer slider's rounded ends.
 *
 * <p>It comes in two sizes, both measured from the references rather than guessed:
 *
 * <ul>
 *   <li><b>The room fields</b> in the top bar — 62 px wide on a {@code #333} background.
 *   <li><b>The dimension fields</b> in the dialogs — 90 px wide on {@code #1a1a1a}, and they
 *       truncate typing to three decimal places as it happens.
 * </ul>
 *
 * <p>The stepper block itself is identical in both: 18 × 18, {@code #e9e9ed} — the same
 * near-white as the layer slider's empty track, because both are the browser's own controls
 * showing through — with grey chevrons, vertically centred and sitting <b>inside the field's
 * right-hand padding</b> rather than flush against the border. That last detail is what makes
 * the two match the reference to the pixel.
 *
 * <p><b>Stepping does not apply anything.</b> It only changes the number in the box; the room is
 * resized when Set Room is pressed and a box when the dialog is confirmed. That matches the
 * original, where the fields are read on demand rather than watched.
 */
public final class NumberField extends HBox {

    /** The browser's stepper block. Same near-white as the slider's unfilled track. */
    private static final Color STEPPER_BG = Color.rgb(233, 233, 237);

    /** The chevrons inside it. */
    private static final Color STEPPER_ARROW = Color.web("#888");

    private static final double STEPPER_SIZE = 18;
    private static final double ARROW_WIDTH = 8;
    private static final double ARROW_HEIGHT = 4;

    /** Every number input in the original steps by a half, of whatever unit it shows. */
    public static final double DEFAULT_STEP = 0.5;

    /**
     * Typing more than three decimals is truncated as it happens.
     *
     * <p>Keeps what is on screen and what gets stored the same thing: the app rounds to three
     * decimals when it reads a field, so a fourth digit would silently disappear on confirm.
     */
    private static final Pattern OVER_THREE_DECIMALS = Pattern.compile("^(\\d*\\.\\d{3})\\d+");

    private final TextField field = new TextField();
    private final double horizontalPadding;

    private double min;
    private double max;
    private double step = DEFAULT_STEP;

    /** A room-size box for the top bar. */
    public NumberField(double min, double max) {
        this(min, max, Tokens.ROOM_FIELD_WIDTH, Tokens.TOP_BAR_INPUT_BG,
                Tokens.INPUT_PADDING_V, Tokens.INPUT_PADDING_H);
        // Pinned rather than computed, for the same reason as dialogField() below, and measured
        // off the reference at exactly 26. Left to JavaFX the height follows the font: once the
        // app carried its own typeface instead of borrowing the computer's, these came out at 31
        // and dragged the vertically-centred stepper block down out of place with them.
        setPrefHeight(Tokens.ROOM_FIELD_HEIGHT);
        setMinHeight(Tokens.ROOM_FIELD_HEIGHT);
        setMaxHeight(Tokens.ROOM_FIELD_HEIGHT);
    }

    /** A box-dimension field for the Add, Edit and Preset dialogs. */
    public static NumberField dialogField() {
        NumberField dialogField = new NumberField(
                com.modcritic.invmgr.model.Item.MIN_DIMENSION_IN,
                com.modcritic.invmgr.model.Item.MAX_DIMENSION_IN,
                Tokens.DIALOG_NUMBER_WIDTH, Tokens.DIALOG_INPUT_BG,
                Tokens.DIALOG_INPUT_PADDING_V, Tokens.DIALOG_INPUT_PADDING_H);
        // Pinned rather than computed. A browser gives a number input a minimum height of its
        // own that has nothing to do with the font or the padding, and the reference measures
        // these at exactly 28; left to JavaFX they come out at 26, and the whole dialog is
        // then six pixels short.
        dialogField.setPrefHeight(Tokens.DIALOG_NUMBER_HEIGHT);
        dialogField.setMinHeight(Tokens.DIALOG_NUMBER_HEIGHT);
        dialogField.setMaxHeight(Tokens.DIALOG_NUMBER_HEIGHT);
        dialogField.limitToThreeDecimals();
        return dialogField;
    }

    private NumberField(double min, double max, double width, Color background,
            double paddingV, double paddingH) {
        this.min = min;
        this.max = max;
        this.horizontalPadding = paddingH;

        setPrefWidth(width);
        setMinWidth(width);
        setMaxWidth(width);
        setAlignment(Pos.CENTER);
        setStyle("-fx-background-color: " + Tokens.hex(background) + ";"
                + "-fx-border-color: " + Tokens.hex(Tokens.CONTROL_BORDER) + ";"
                + "-fx-border-width: 1;"
                + "-fx-background-radius: 0; -fx-border-radius: 0;");

        // The text field carries no styling of its own — the box around it supplies the
        // background and border, so the two cannot end up drawing competing edges.
        field.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_CONTROL));
        field.setStyle("-fx-background-color: transparent;"
                + "-fx-text-fill: " + Tokens.hex(Tokens.TEXT_INPUT) + ";"
                + "-fx-border-width: 0;"
                + "-fx-padding: " + paddingV + " 0 " + paddingV + " " + paddingH + ";");
        HBox.setHgrow(field, Priority.ALWAYS);
        field.setMinWidth(0);

        // JavaFX selects a text field's whole contents when focus arrives by traversal, and the
        // first field in the window gets focus the moment it opens. That left the app starting
        // with "12" highlighted, which the original does not — and worse, it means one stray
        // keystroke replaces the room's width, so the next Set Room resizes the room to whatever
        // was typed. Clicking or double-clicking still selects normally; this only undoes the
        // automatic select-all.
        // Deferred deliberately: the text field's own skin also selects everything on focus, and
        // it does so after this listener runs, so clearing the selection here directly gets
        // immediately undone. Queueing the clear puts it after the skin's turn.
        field.focusedProperty().addListener((observable, lostFocus, hasFocus) -> {
            if (hasFocus) {
                javafx.application.Platform.runLater(() -> {
                    field.deselect();
                    field.positionCaret(field.getText().length());
                });
            }
        });

        getChildren().addAll(field, buildStepper());
    }

    /** Trims a fourth decimal place away as it is typed. */
    private void limitToThreeDecimals() {
        field.textProperty().addListener((observable, before, after) -> {
            if (after == null) {
                return;
            }
            Matcher tooPrecise = OVER_THREE_DECIMALS.matcher(after);
            if (tooPrecise.find()) {
                field.setText(tooPrecise.group(1));
            }
        });
    }

    private VBox buildStepper() {
        StackPane up = arrowButton(true);
        StackPane down = arrowButton(false);

        VBox stepper = new VBox(up, down);
        stepper.setAlignment(Pos.CENTER);
        stepper.setPrefSize(STEPPER_SIZE, STEPPER_SIZE);
        stepper.setMinSize(STEPPER_SIZE, STEPPER_SIZE);
        stepper.setMaxSize(STEPPER_SIZE, STEPPER_SIZE);
        stepper.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(up, Priority.ALWAYS);
        VBox.setVgrow(down, Priority.ALWAYS);

        // Vertically centred, and held clear of the right border by the field's own padding —
        // exactly where the browser puts it. Measured at 5 px in the top bar and 6 px in the
        // dialogs, which is each field's padding, not two different design decisions.
        setAlignment(Pos.CENTER_LEFT);
        HBox.setMargin(stepper, new Insets(0, horizontalPadding, 0, 0));
        HBox.setHgrow(stepper, Priority.NEVER);
        return stepper;
    }

    /** One half of the stepper: a pale block with a chevron, which nudges the value when clicked. */
    private StackPane arrowButton(boolean up) {
        Polyline chevron = new Polyline();
        if (up) {
            chevron.getPoints().addAll(0.0, ARROW_HEIGHT, ARROW_WIDTH / 2, 0.0,
                    ARROW_WIDTH, ARROW_HEIGHT);
        } else {
            chevron.getPoints().addAll(0.0, 0.0, ARROW_WIDTH / 2, ARROW_HEIGHT,
                    ARROW_WIDTH, 0.0);
        }
        chevron.setStroke(STEPPER_ARROW);
        chevron.setStrokeWidth(1.2);
        chevron.setStrokeLineCap(StrokeLineCap.ROUND);
        chevron.setMouseTransparent(true);

        StackPane block = new StackPane(chevron);
        block.setBackground(new Background(new BackgroundFill(STEPPER_BG, null, null)));
        block.setCursor(Cursor.DEFAULT);
        block.setOnMousePressed(event -> {
            nudge(up ? step : -step);
            event.consume();
        });
        return block;
    }

    /** Moves the value by one step, held inside the range, and rewrites the box. */
    private void nudge(double delta) {
        double current = value().orElse(min);
        setValue(Math.max(min, Math.min(max, current + delta)));
    }

    /**
     * Changes what the stepper will step to.
     *
     * <p>The room fields need this because their range is in whatever unit is showing: 1 to 200
     * feet, or 1 to 61 metres, which is the same room measured two ways.
     */
    public void setRange(double min, double max) {
        this.min = min;
        this.max = max;
    }

    /** Changes the stepper's increment — half a foot imperial, a tenth of a metre in metric. */
    public void setStep(double step) {
        this.step = step;
    }

    /** The number currently typed in, or empty if it isn't a number. */
    public OptionalDouble value() {
        try {
            return OptionalDouble.of(Double.parseDouble(field.getText().trim()));
        } catch (NumberFormatException | NullPointerException e) {
            return OptionalDouble.empty();
        }
    }

    /** Writes a value in, dropping a trailing ".0" so a whole number reads as "12". */
    public void setValue(double value) {
        field.setText(com.modcritic.invmgr.engine.TextFormat.number(value));
    }

    public String getText() {
        return field.getText();
    }

    public void setText(String text) {
        field.setText(text);
    }

    /** The editable part, for wiring key handlers and for tests to type into. */
    public TextField textField() {
        return field;
    }

    public void setOnAction(javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        field.setOnAction(handler);
    }
}
