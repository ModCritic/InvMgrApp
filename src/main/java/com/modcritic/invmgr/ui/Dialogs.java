package com.modcritic.invmgr.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * The bits every dialog is made of: the box, its title, its rows of labelled fields, and its
 * buttons.
 *
 * <p>Three dialogs share this — Add Item, Edit Item and Save Preset — and in the original they
 * share it by all using the same handful of CSS classes. There is no CSS class here to share, so
 * the shapes live in one file instead. Without it, three dialogs would each carry their own copy
 * of "20 pixels of padding, a 14-pixel title, rows 10 pixels apart", and they would drift apart
 * the first time one of them was adjusted.
 *
 * <p>Every number below was measured off {@code reference/desktop-03-add-item-dialog.png} and
 * agrees with the original's stylesheet: the box is 340 px wide with a 281 px tall interior, the
 * number fields are 90 × 28, and the gap above the button row is 14 px rather than 24 because
 * CSS collapses the row's 10 px bottom margin into it.
 */
public final class Dialogs {

    private Dialogs() {
    }

    /** Which of the three button looks a button has. */
    public enum ButtonKind {
        /** Cancel, and the swap button: plain grey. */
        PLAIN,
        /** Confirm, OK, Save: green. */
        CONFIRM,
        /** Delete: red, with its own darker red border. */
        DANGER
    }

    // -------------------------------------------------------------------- box

    /** The dialog's panel: dark grey, square, with a one-pixel border. */
    public static VBox box() {
        VBox box = new VBox();
        box.setPadding(new Insets(Tokens.DIALOG_PADDING));
        box.setMinWidth(Tokens.DIALOG_MIN_WIDTH);
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        box.setStyle("-fx-background-color: " + Tokens.hex(Tokens.DIALOG_BG) + ";"
                + "-fx-border-color: " + Tokens.hex(Tokens.CONTROL_BORDER) + ";"
                + "-fx-border-width: 1;"
                + "-fx-background-radius: 0; -fx-border-radius: 0;");
        return box;
    }

    /**
     * A dialog heading with the hairline under it.
     *
     * <p>The Add Item dialog does not use this — its heading is a whole row with the preset
     * slots in it — but it uses the same underline and spacing, which is why
     * {@link #headingUnderline} is separate.
     */
    public static Label title(String text) {
        Label label = new Label(text);
        label.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_DIALOG_TITLE));
        label.setTextFill(Tokens.TEXT_INPUT);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setPadding(new Insets(0, 0, Tokens.DIALOG_TITLE_PADDING_BOTTOM, 0));
        headingUnderline(label);
        VBox.setMargin(label, new Insets(0, 0, Tokens.DIALOG_TITLE_MARGIN_BOTTOM, 0));
        return label;
    }

    /** Adds the one-pixel rule that separates a dialog's heading from its fields. */
    public static void headingUnderline(Region heading) {
        heading.setStyle(heading.getStyle()
                + "-fx-border-color: transparent transparent "
                + Tokens.hex(Tokens.BORDER) + " transparent;"
                + "-fx-border-width: 0 0 1 0;");
    }

    // ------------------------------------------------------------------- rows

    /** The grey caption to the left of a field. */
    public static Label fieldLabel(String text) {
        Label label = new Label(text);
        label.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_CONTROL));
        label.setTextFill(Tokens.TEXT_DIALOG_LABEL);
        return label;
    }

    /**
     * One labelled row: caption on the left, field hard against the right edge.
     *
     * <p>The gap between them stretches, so every field in the dialog lines up down the right
     * whatever the captions say — which is what {@code justify-content: space-between} does in
     * the original.
     */
    public static HBox row(Label label, Node field) {
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        gap.setMinWidth(Tokens.DIALOG_ROW_GAP);

        HBox row = new HBox(label, gap, field);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(row, new Insets(0, 0, Tokens.DIALOG_ROW_MARGIN_BOTTOM, 0));
        return row;
    }

    /**
     * The same, for the name row, whose field grows downward as it fills up.
     *
     * <p>The caption sits at the <b>top</b> rather than the middle, so it stays beside the first
     * line instead of drifting down the side of a tall field.
     */
    public static HBox topAlignedRow(Label label, Node field) {
        HBox row = row(label, field);
        row.setAlignment(Pos.TOP_LEFT);
        label.setPadding(new Insets(Tokens.DIALOG_NAME_LABEL_PADDING_TOP, 0, 0, 0));
        return row;
    }

    // ----------------------------------------------------------------- fields

    /** A plain text box in a dialog: near-black, one-pixel border, capped in length. */
    public static TextField textInput(double width, double fontSize, int maxLength) {
        TextField field = new TextField();
        field.setFont(Font.font(Tokens.FONT_FAMILY, fontSize));
        field.setPrefWidth(width);
        field.setMinWidth(width);
        field.setMaxWidth(width);
        field.setStyle("-fx-background-color: " + Tokens.hex(Tokens.DIALOG_INPUT_BG) + ";"
                + "-fx-text-fill: " + Tokens.hex(Tokens.TEXT_INPUT) + ";"
                + "-fx-prompt-text-fill: " + Tokens.hex(Tokens.placeholderOver(Tokens.DIALOG_INPUT_BG)) + ";"
                + "-fx-border-color: " + Tokens.hex(Tokens.CONTROL_BORDER) + ";"
                + "-fx-border-width: 1;"
                + "-fx-background-radius: 0; -fx-border-radius: 0;"
                + "-fx-padding: " + Tokens.DIALOG_INPUT_PADDING_V + " "
                + Tokens.DIALOG_INPUT_PADDING_H + " " + Tokens.DIALOG_INPUT_PADDING_V + " "
                + Tokens.DIALOG_INPUT_PADDING_H + ";");
        limitLength(field, maxLength);
        return field;
    }

    /**
     * Stops a field accepting more than it is allowed to hold.
     *
     * <p>The original relies on the browser's {@code maxlength}; JavaFX has no equivalent, so
     * the text is trimmed as it is typed. Doing it here rather than when the dialog is confirmed
     * matters: a name silently losing its last forty characters on OK would be a nasty surprise,
     * whereas a field that simply stops taking input is obvious.
     */
    public static void limitLength(TextInputControl field, int maxLength) {
        field.textProperty().addListener((observable, before, after) -> {
            if (after != null && after.length() > maxLength) {
                field.setText(after.substring(0, maxLength));
            }
        });
    }

    /**
     * The small {@code ID:} box that lives <b>in the button row</b> rather than with the other
     * fields.
     *
     * <p>That placement looks like a mistake and is not — it is where the original puts it, left
     * of Cancel, and the reference screenshot shows it there.
     */
    public static HBox idField(TextField field) {
        Label label = new Label("ID:");
        label.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_ID_LABEL));
        label.setTextFill(Tokens.TEXT_ID_LABEL);

        HBox group = new HBox(Tokens.DIALOG_ID_GAP, label, field);
        group.setAlignment(Pos.CENTER_LEFT);
        return group;
    }

    // ---------------------------------------------------------------- buttons

    /** The right-aligned strip of buttons along the bottom of a dialog. */
    public static HBox buttonRow(Node... children) {
        HBox row = new HBox(Tokens.DIALOG_BUTTON_GAP, children);
        row.setAlignment(Pos.CENTER_RIGHT);

        // 4, not 14 — the one place in this port where a CSS rule has to be *undone* rather
        // than copied. In CSS the last field row's 10 px bottom margin and this row's 14 px
        // top margin **collapse** into a single 14 px gap. JavaFX has no margin collapsing and
        // would stack them into 24, which is exactly what the first attempt rendered. The
        // reference screenshot measures 14.
        VBox.setMargin(row, new Insets(
                Tokens.DIALOG_BUTTON_ROW_MARGIN_TOP - Tokens.DIALOG_ROW_MARGIN_BOTTOM, 0, 0, 0));
        return row;
    }

    /**
     * A dialog button.
     *
     * <p>Hovering lightens a plain button and deepens a coloured one, exactly as the original's
     * three hover rules do — the green and red keep their identity instead of all washing out to
     * the same grey.
     */
    public static Button button(String text, ButtonKind kind) {
        Button button = button(text, kind,
                Tokens.DIALOG_BUTTON_PADDING_V, Tokens.DIALOG_BUTTON_PADDING_H);
        button.setMinHeight(Tokens.DIALOG_BUTTON_HEIGHT);
        button.setPrefHeight(Tokens.DIALOG_BUTTON_HEIGHT);
        button.setMaxHeight(Tokens.DIALOG_BUTTON_HEIGHT);
        return button;
    }

    /**
     * The same, with the padding chosen by the caller.
     *
     * <p>Only {@link #symbolButton} needs this, and it needs it because the padding is written
     * into the style string rather than set on the button. Anything that calls {@code setPadding}
     * afterwards is quietly ignored — a JavaFX inline style outranks a value set from code, and
     * these buttons rewrite their style on every hover, so it is overwritten again the first time
     * the pointer crosses them. That trap cost a real line of dead code in {@code EditItemDialog}.
     */
    private static Button button(String text, ButtonKind kind, double paddingV, double paddingH) {
        Color resting = switch (kind) {
            case CONFIRM -> Tokens.BUTTON_CONFIRM_BG;
            case DANGER -> Tokens.BUTTON_DANGER_BG;
            case PLAIN -> Tokens.BUTTON_BG;
        };
        Color hovered = switch (kind) {
            case CONFIRM -> Tokens.BUTTON_CONFIRM_BG_HOVER;
            case DANGER -> Tokens.BUTTON_DANGER_BG_HOVER;
            case PLAIN -> Tokens.BUTTON_BG_HOVER;
        };
        Color border = kind == ButtonKind.DANGER
                ? Tokens.BUTTON_DANGER_BORDER : Tokens.CONTROL_BORDER;

        Button button = new Button(text);
        button.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_CONTROL));
        styleDialogButton(button, resting, border, paddingV, paddingH);
        button.setOnMouseEntered(event ->
                styleDialogButton(button, hovered, border, paddingV, paddingH));
        button.setOnMouseExited(event ->
                styleDialogButton(button, resting, border, paddingV, paddingH));
        return button;
    }

    /**
     * A square dialog button whose label is a character the interface's typeface cannot draw.
     *
     * <p>Only the Edit dialog's {@code ↻} needs this. It exists as a wrapper rather than as a
     * few calls at the call site because {@code swapButton} is set up where it is declared, which
     * can hold an expression but not a second statement — and because the font decision then stays
     * here, beside the one it is an exception to.
     *
     * <p>Two things differ from an ordinary {@link #button}, and both follow from the glyph:
     *
     * <ul>
     *   <li>The character goes in as a <b>graphic</b>, not as the button's text, so that it is
     *       centred on its own ink rather than on the maths face's very tall line box.
     *       {@link Fonts#symbolGlyph} explains what that fixes and why nothing here is a measured
     *       nudge.</li>
     *   <li>The button is given an explicit <b>square</b> size. Left to itself it came out
     *       46 × 30, because the width followed the glyph's advance — and a maths face's advances
     *       are set by its widest operators, not by a small arrow. An icon button that is half as
     *       wide again as it is tall reads as a mistake next to the app's otherwise squared-off
     *       chrome.</li>
     * </ul>
     *
     * <p>The padding goes to zero as a consequence of the second point, and it matters more than
     * it looks. A dialog button's 14 px of side padding inside a 30 px square leaves a content box
     * <em>zero</em> pixels wide; the glyph then happens to land in the middle only because zero is
     * symmetric. Handing the whole square to the graphic makes the centring mean something.
     *
     * @see Fonts#SYMBOL_FAMILY
     */
    public static Button symbolButton(String text, ButtonKind kind, double size) {
        Button button = button(text, kind, 0, 0);
        button.setText(null);
        button.setGraphic(Fonts.symbolGlyph(text, Tokens.FONT_CONTROL, button));
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setMinSize(size, size);
        button.setPrefSize(size, size);
        button.setMaxSize(size, size);
        return button;
    }

    private static void styleDialogButton(Button button, Color background, Color border,
            double paddingV, double paddingH) {
        button.setStyle("-fx-background-color: " + Tokens.hex(background) + ";"
                + "-fx-text-fill: " + Tokens.hex(Tokens.TEXT_PRIMARY) + ";"
                + "-fx-border-color: " + Tokens.hex(border) + ";"
                + "-fx-border-width: 1;"
                + "-fx-background-radius: 0; -fx-border-radius: 0;"
                + "-fx-padding: " + paddingV + " " + paddingH + " "
                + paddingV + " " + paddingH + ";");
    }
}
