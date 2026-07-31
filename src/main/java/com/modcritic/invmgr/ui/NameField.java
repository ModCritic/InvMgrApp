package com.modcritic.invmgr.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

/**
 * The Name box on the Add Item and Edit Item dialogs — the one that grows as you type.
 *
 * <p><b>What it does.</b> It starts narrow, widens to fit whatever you type, and once it hits
 * its width limit it starts wrapping and grows downward instead. It never scrolls sideways, so
 * the beginning of a long name never disappears off the left while you are typing the end of
 * it.
 *
 * <p><b>Why it is hand-written.</b> The original gets this free from a single CSS line
 * ({@code field-sizing: content}) that browsers implement. JavaFX has nothing equivalent — a
 * {@code TextArea} is a fixed rectangle with a scrollbar — so the text is measured after every
 * keystroke and the box resized to match. That is the whole of this class.
 *
 * <p>The limits come from the original's stylesheet: 160 px to start, no wider than 560 px or
 * 80% of the window (whichever is smaller), and no taller than 40% of the window.
 */
public final class NameField extends TextArea {

    /** Its width with nothing in it, and the narrowest it ever gets. */
    private static final double MIN_WIDTH = 160;

    /** The widest it will grow before it starts wrapping instead. */
    private static final double MAX_WIDTH = 560;

    /** ...or this fraction of the window, if that is narrower. */
    private static final double MAX_WIDTH_WINDOW_FRACTION = 0.8;

    /** The tallest it will grow before it starts scrolling instead. */
    private static final double MAX_HEIGHT_WINDOW_FRACTION = 0.4;

    /**
     * How tall one line of text is, in pixels.
     *
     * <p>The original's stylesheet says {@code line-height: 1.4}, which at 13 px is 18.2 — but
     * the reference screenshot measures the whole empty field at exactly <b>26</b>, which is
     * 18 plus 4 px of padding above and below. Deriving 18.2 from the stylesheet and rounding
     * it up to 19 was the first attempt and rendered the field three pixels too tall. The
     * measurement wins over the arithmetic.
     */
    private static final double LINE_HEIGHT = 18;

    /**
     * A little slack on the right, so the caret after the last character is inside the box.
     *
     * <p>Without it, typing to the exact edge puts the caret on the border and the box looks
     * one character too small.
     */
    private static final double CARET_SLACK = 2;

    /**
     * What {@link #horizontalChrome()} uses before the control has been laid out once.
     *
     * <p>Measured from a running dialog: a 306 px field gave its text 278 px. Only ever used for
     * the first pass, after which the real value is read off the control itself.
     */
    private static final double FALLBACK_HORIZONTAL_CHROME = 28;

    private final Font font;

    public NameField(double fontSize) {
        font = Font.font(Tokens.FONT_FAMILY, fontSize);

        setFont(font);
        setWrapText(true);
        getStyleClass().add("name-field");
        setPadding(new Insets(Tokens.DIALOG_INPUT_PADDING_V, Tokens.DIALOG_INPUT_PADDING_H,
                Tokens.DIALOG_INPUT_PADDING_V, Tokens.DIALOG_INPUT_PADDING_H));
        setPromptText("Leave blank for default");
        setStyle("-fx-background-color: " + Tokens.hex(Tokens.DIALOG_INPUT_BG) + ";"
                + "-fx-text-fill: " + Tokens.hex(Tokens.TEXT_INPUT) + ";"
                + "-fx-prompt-text-fill: " + Tokens.hex(Tokens.placeholderOver(Tokens.DIALOG_INPUT_BG)) + ";"
                + "-fx-border-color: " + Tokens.hex(Tokens.CONTROL_BORDER) + ";"
                + "-fx-border-width: 1;"
                + "-fx-background-radius: 0; -fx-border-radius: 0;");

        Dialogs.limitLength(this, com.modcritic.invmgr.model.Item.MAX_NAME_LENGTH);
        textProperty().addListener((observable, before, after) -> resizeToFit());
        // The window can change size while the dialog is open, which changes both caps.
        sceneProperty().addListener((observable, before, after) -> resizeToFit());
        resizeToFit();
    }

    /**
     * Measures the current text and resizes the box around it.
     *
     * <p>Called on every keystroke. Cheap enough to do that way — it lays out one string of at
     * most 200 characters, which is nothing next to the redraw that follows it.
     */
    public void resizeToFit() {
        double horizontalChrome = horizontalChrome() + CARET_SLACK;
        // Padding only: the border is drawn inside the height JavaFX is given, so counting it
        // again here would make every field two pixels too tall.
        double verticalChrome = Tokens.DIALOG_INPUT_PADDING_V * 2;

        double widest = widestLine();
        double width = Math.max(MIN_WIDTH, Math.min(widthCap(), widest + horizontalChrome));
        setPrefWidth(width);
        setMinWidth(width);
        setMaxWidth(width);

        // How many characters fit on a line at this width decides how many lines the text
        // needs -- which is why the height has to be worked out after the width, not with it.
        double usableWidth = width - horizontalChrome;
        double height = Math.min(heightCap(),
                lineCount(usableWidth) * LINE_HEIGHT + verticalChrome);
        setPrefHeight(height);
        setMinHeight(height);
        setMaxHeight(height);
    }

    /**
     * Everything between the box's outer edge and the text inside it, in pixels.
     *
     * <p><b>Measured from the live control, not calculated.</b> A {@code TextArea} is four nested
     * controls — the area, a scroll pane, a viewport, and a "content" region holding the text —
     * and the scroll pane keeps insets of its own that no token knows about. Adding up the
     * padding and border the way this used to gives <b>16 px</b>; the real figure is <b>28</b>.
     *
     * <p>Those missing 12 px are what caused the reported bug. The box was sized as though its
     * text had 290 px to live in when it actually had 278, so text that just fit by the
     * arithmetic really wrapped onto a second line — and because the height had been worked out
     * from the same wrong number, the box was left one line tall with two lines in it. The caret
     * is on the last line, so the field showed only the final word.
     *
     * <p>Asking the skin removes the guesswork, and it stays right if the skin ever changes. The
     * constant is only a starting value for the very first layout, before there is a skin to ask.
     */
    private double horizontalChrome() {
        Node content = lookup(".content");
        if (content instanceof Region region && getWidth() > 0 && region.getWidth() > 0) {
            return getWidth() - region.getWidth();
        }
        return FALLBACK_HORIZONTAL_CHROME;
    }

    /** The widest single line of the current text, in pixels. */
    private double widestLine() {
        String text = getText();
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double widest = 0;
        for (String line : text.split("\n", -1)) {
            widest = Math.max(widest, measure(line));
        }
        return widest;
    }

    /**
     * How many lines the text takes once it has wrapped to the given width.
     *
     * <p><b>This asks JavaFX rather than working it out.</b> The obvious arithmetic —
     * {@code ceil(textWidth / usableWidth)} — treats text as a ribbon that can be cut anywhere,
     * but real wrapping happens at <em>word</em> boundaries, so a line that is 1 px too long for
     * its box loses a whole word to the next line. The estimate therefore says "one line" for
     * text that actually needs two.
     *
     * <p>That was a real bug, reported from the M3 jar. When the count came out too low the box
     * was made one line tall while holding two lines of text, and since the caret is on the last
     * one, the field showed only the final word — "Winter clothes and spare blankets box"
     * displayed as "box". It looked like the text was scrolling sideways; it was not, it was
     * hidden below the bottom edge.
     *
     * <p>Laying out a {@link Text} node with the same font and the same wrapping width uses the
     * same layout engine the {@code TextArea} itself uses, so the answer matches what will
     * actually be drawn — including word wrapping, and including runs of text with no spaces in
     * them, which wrap mid-word in both.
     */
    private int lineCount(double usableWidth) {
        String text = getText();
        if (text == null || text.isEmpty() || usableWidth <= 0) {
            return 1;
        }
        Text probe = new Text(text);
        probe.setFont(font);
        probe.setWrappingWidth(usableWidth);
        double wrappedHeight = probe.getLayoutBounds().getHeight();
        return Math.max(1, (int) Math.round(wrappedHeight / singleLineHeight()));
    }

    /**
     * The height of exactly one line, as this font lays it out.
     *
     * <p>Used only as the divisor for the wrapped height above, so it must come from the same
     * measurement JavaFX made — not from {@link #LINE_HEIGHT}, which is the <em>drawn</em> line
     * height measured off the reference screenshot and is deliberately a slightly different
     * number.
     */
    private double singleLineHeight() {
        Text probe = new Text("X");
        probe.setFont(font);
        return probe.getLayoutBounds().getHeight();
    }

    private double measure(String line) {
        Text probe = new Text(line);
        probe.setFont(font);
        return probe.getLayoutBounds().getWidth();
    }

    /**
     * The widest the box may get: the fixed cap, or a share of the window if that is narrower.
     *
     * <p>The window matters because the dialog has to fit inside it — on a small window a
     * 560-pixel field would push the dialog wider than the screen.
     */
    private double widthCap() {
        if (getScene() == null) {
            return MAX_WIDTH;
        }
        return Math.max(MIN_WIDTH,
                Math.min(MAX_WIDTH, getScene().getWidth() * MAX_WIDTH_WINDOW_FRACTION));
    }

    /**
     * The tallest the box may get before it scrolls instead of growing.
     *
     * <p>There is no fixed cap here, only the window one — a name long enough to matter is
     * already being wrapped, and how much room there is to show it depends entirely on the
     * window. While the dialog is still being built there is no window to ask, so nothing caps
     * it and the first measured layout settles it.
     */
    private double heightCap() {
        if (getScene() == null) {
            return Double.MAX_VALUE;
        }
        return getScene().getHeight() * MAX_HEIGHT_WINDOW_FRACTION;
    }
}
