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
import javafx.scene.text.Text;

/**
 * A number entry box with the little up/down stepper on its right.
 *
 * <p><b>Why this exists rather than a plain text field.</b> The original app uses an HTML
 * {@code <input type="number">}, and the browser draws a stepper inside it: a pale block with
 * two small chevrons. The app's stylesheet never mentions it, so it is invisible in the CSS and
 * easy to miss, but it is in every desktop reference screenshot and it is what the user sees.
 * This is the same class of detail as the layer slider's rounded ends.
 *
 * <p>It comes in two sizes, both measured from the references rather than guessed:
 *
 * <ul>
 *   <li><b>The room fields</b> in the top bar: 62 px wide on a {@code #333} background.
 *   <li><b>The dimension fields</b> in the dialogs: 90 px wide on {@code #1a1a1a}, and they
 *       truncate typing to three decimal places as it happens.
 * </ul>
 *
 * <p>The stepper block itself is identical in both: 18 × 18, {@code #e9e9ed} (the same
 * near-white as the layer slider's empty track, because both are the browser's own controls
 * showing through), with gray chevrons, vertically centered and sitting <b>inside the field's
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

    /**
     * Whether a tap should select this box's contents, which only the dialog boxes do.
     *
     * <p>The room boxes in the top bar are deliberately left out, the same way the original
     * leaves them out: its focus listener names only the Add dialog's three.
     */
    private boolean selectsOnTouchFocus;


    /** A room-size box for the top bar. */
    public NumberField(double min, double max) {
        this(min, max, Tokens.ROOM_FIELD_WIDTH, Tokens.TOP_BAR_INPUT_BG,
                Tokens.INPUT_PADDING_V, Tokens.INPUT_PADDING_H);
        // Pinned rather than computed, for the same reason as dialogField() below, and measured
        // off the reference at exactly 26. Left to JavaFX the height follows the font: once the
        // app carried its own typeface instead of borrowing the computer's, these came out at 31
        // and dragged the vertically-centered stepper block down out of place with them.
        setPrefHeight(Tokens.ROOM_FIELD_HEIGHT);
        setMinHeight(Tokens.ROOM_FIELD_HEIGHT);
        setMaxHeight(Tokens.ROOM_FIELD_HEIGHT);
    }

    /** A box-dimension field for the Add, Edit and Preset dialogs. */
    public static NumberField dialogField() {
        NumberField dialogField = new NumberField(
                com.modcritic.invmgr.model.Item.MIN_DIMENSION_IN,
                com.modcritic.invmgr.model.Item.MAX_DIMENSION_IN,
                TouchType.dialogNumberWidth(), Tokens.DIALOG_INPUT_BG,
                TouchType.dialogInputPaddingV(), TouchType.dialogInputPaddingH());
        // Pinned rather than computed. A browser gives a number input a minimum height of its
        // own that has nothing to do with the font or the padding, and the reference measures
        // these at exactly 28; left to JavaFX they come out at 26, and the whole dialog is
        // then six pixels short.
        dialogField.setPrefHeight(TouchType.dialogNumberHeight());
        dialogField.setMinHeight(TouchType.dialogNumberHeight());
        dialogField.setMaxHeight(TouchType.dialogNumberHeight());
        dialogField.limitToThreeDecimals();
        // §5.5 D-28: a tap selects the box's contents so typing replaces the number. The
        // original does this for the Add dialog's three boxes only; the user chose all nine
        // on 2026-09-19, because Edit is where a size is most often retyped.
        dialogField.selectsOnTouchFocus = true;
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

        // The text field carries no styling of its own; the box around it supplies the
        // background and border, so the two cannot end up drawing competing edges.
        field.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_CONTROL));
        field.setStyle("-fx-background-color: transparent;"
                + "-fx-text-fill: " + Tokens.hex(Tokens.TEXT_INPUT) + ";"
                + "-fx-border-width: 0;"
                + "-fx-padding: " + paddingV + " 0 " + paddingV + " " + paddingH + ";");
        HBox.setHgrow(field, Priority.ALWAYS);
        field.setMinWidth(0);

        // A tap on a phone selects the box's contents, so typing replaces the number instead
        // of appending to it. The original's own code, not a browser default: original
        // 1760-1764 puts a focus listener on new-w, new-l and new-h guarded by isTouch(), and
        // its comment says the one listener covers tapping straight into a box and arriving on
        // one from the keypad's Next alike, because both fire an ordinary focus event.
        //
        // ⚠ THE OTHER TWO CASES NEED NO CODE, WHICH IS NOT WHAT THIS FILE USED TO BELIEVE.
        // A probe against a bare JavaFX TextField, run on 2026-09-19, says it already behaves
        // exactly as a browser does: requestFocus selects the whole contents, a click selects
        // nothing, and a click on an already-focused box selects nothing. So desktop Tab and
        // desktop click are both right with no help from here.
        //
        // Until M6.7c this cleared the selection on EVERY focus. The comment justifying that
        // said the skin "selects everything on focus", which is half true: it selects on
        // TRAVERSAL focus. So the clearing was not protecting the click case, which never
        // needed it; it was removing desktop's tab-to-select and the phone's tap-to-replace.
        // The user found the second half from the phone: the keypad appends, so typing 24 into
        // a box holding 12 gives 1224, which clamps to MAX_DIMENSION_IN and silently makes a
        // 1000 inch box. Going wider than the original's three Add-dialog boxes to all nine is
        // §5.5 D-28.
        //
        // ⚠ The app must still not OPEN with a box selected, or one stray keystroke replaces
        // the room's width. Nothing here does that any more: App gives the canvas focus at
        // startup so no box takes the window's automatic first focus, and
        // AppUiTest.nothingIsSelectedAtStartup is the only thing asserting it.
        //
        // Queued deliberately: the skin selects after this listener runs, so selecting here
        // directly would be undone by it.
        field.focusedProperty().addListener((observable, lostFocus, hasFocus) -> {
            if (hasFocus && selectsOnTouchFocus && Device.isTouch()) {
                javafx.application.Platform.runLater(field::selectAll);
            }
        });

        // B6: marks this box as holding a number, so a phone raises the keypad when it takes
        // focus (what the original gets for free from <input type="number">). The watching is
        // done once for the whole window; see AndroidBridge.followTheFocusedField.
        AndroidBridge.marksANumberField(field);

        stepper = buildStepper();
        getChildren().addAll(field, stepper);
    }

    /** The browser's spinner block, kept so the touch layout can take it away again. */
    private final VBox stepper;

    /**
     * Re-sizes a room field for a finger, and <b>takes the stepper off it</b>.
     *
     * <p>The original's touch rule is {@code width: 4.5ch; font-size: clamp(9px, 3.6cqi, 15px);
     * padding: 0.3em}, and its own comment says the numbers were computed rather than guessed, so
     * that all three of W, L and H fit on one row at real phone widths instead of wrapping halfway
     * through a group.
     *
     * <p><b>They only fit if there is no stepper</b>, and that is not a liberty: it is what the
     * original actually renders. Four and a half characters is barely wider than the spinner block
     * itself; a mobile browser draws no spinner on a number input at all, which is why 4.5ch is
     * enough there. Doing the arithmetic at 360 px settles it: with the stepper the three groups
     * need about 380 px and wrap, and without it about 327 and do not.
     *
     * <p>This app draws the spinner by hand precisely <em>because</em> a desktop browser draws one.
     * The same reasoning, applied to a phone, removes it.
     *
     * @param fontSize   the fluid type this field is to be set in
     * @param characters how many characters wide the box should be (the {@code ch} of the CSS)
     */
    public void useTouchType(double fontSize, double characters) {
        Font font = Font.font(Tokens.FONT_FAMILY, fontSize);
        field.setFont(font);

        double padding = TOUCH_PADDING_EM * fontSize;
        field.setStyle("-fx-background-color: transparent;"
                + "-fx-text-fill: " + Tokens.hex(Tokens.TEXT_INPUT) + ";"
                + "-fx-border-width: 0;"
                + "-fx-padding: " + padding + " " + padding + " " + padding + " " + padding + ";");

        getChildren().remove(stepper);

        // Measured, not assumed. A `ch` is the advance width of a zero in the font actually in
        // use, and the app carries its own typeface, so reading it off the font is the only way
        // this number stays right if that typeface is ever changed.
        double width = characters * characterWidth(font) + 2 * padding + 2 * BORDER;
        setPrefWidth(width);
        setMinWidth(width);
        setMaxWidth(width);
    }

    /** The original's touch padding on a room field, {@code 0.3em} of its own type. */
    private static final double TOUCH_PADDING_EM = 0.3;

    /** The 1 px border the box draws around itself, counted because CSS counts it. */
    private static final double BORDER = 1;

    /** The width of one character of a monospaced font: CSS's {@code ch}. */
    private static double characterWidth(Font font) {
        Text probe = new Text("0");
        probe.setFont(font);
        return probe.getLayoutBounds().getWidth();
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

        // Vertically centered, and held clear of the right border by the field's own padding,
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
     * feet, or 1 to 61 meters, which is the same room measured two ways.
     */
    public void setRange(double min, double max) {
        this.min = min;
        this.max = max;
    }

    /** Changes the stepper's increment: half a foot imperial, a tenth of a meter in metric. */
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
