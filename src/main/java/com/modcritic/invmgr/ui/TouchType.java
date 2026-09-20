package com.modcritic.invmgr.ui;


/**
 * Every size that changes when the app is driven by a finger, decided in one place.
 *
 * <p>The original writes all of this as a single CSS block ({@code @media (pointer: coarse)}), and
 * that being one block is what makes it checkable: you can read it against the desktop rules line by
 * line and see nothing has been missed. There is no {@code @media} in JavaFX, so the block would
 * naturally scatter into a dozen {@code Device.isTouch()} tests spread over six files, and the
 * question "what actually changes on a phone?" would stop having an answer you could read.
 *
 * <p>So it does not scatter. Every one of those rules is a method here, in the order the original
 * writes them, and the two numbers behind each sit next to each other in {@link Tokens}.
 *
 * <p><b>What is deliberately not here.</b> The touch island, the folding bar, the drawers and the
 * tooltip marquee are not size overrides; they are different arrangements of different pieces, and
 * they live with the pieces. This class is only the table of numbers that grow for a fingertip.
 *
 * <h2>⚠ Fourteen of these rules are dead in the original, and the browser never applies them</h2>
 *
 * <p>Found 2026-08-13 by measuring the reference screenshots instead of reading the stylesheet,
 * after the phone reported dialog buttons truncated to {@code C...} and a status-bar tip cut off
 * with an ellipsis. <b>Read this before "correcting" any number below to match the original's touch
 * block; for most of them the touch block is not what the original does.</b>
 *
 * <p><b>The mechanism.</b> A media query adds no specificity, so between two rules of equal
 * specificity the later one in the file wins. The original has <em>five</em>
 * {@code @media (pointer: coarse)} blocks. Four sit <em>after</em> the rules they override and work
 * as intended: the drawers at lines 151 and 236, the tooltip at 226, the presets at 364. The big
 * block at lines 97-135 is the exception: it sits near the top, so it only beats what is declared
 * above it.
 *
 * <ul>
 *   <li><b>Live</b>: the top bar and island, whose base rules are all earlier:
 *       {@code #top-bar} (base 30), {@code #top-bar label} and {@code input} (36-37),
 *       {@code #top-bar button} (42), {@code .row-break} (50), {@code #add-item-btn} (55),
 *       {@code #top-island} (62).
 *   <li><b>Dead</b>: dialogs, the item list, the search box and the status bar, whose base rules
 *       are all later: {@code .modal-box} (base 333), {@code .modal-box h2} (336),
 *       {@code .modal-row label} (371), {@code .modal-row input} (372),
 *       {@code .modal-row textarea} (383), {@code .modal-btns button} (392),
 *       {@code .id-field-label} (401), {@code .list-entry} (285), {@code #list-header} (256),
 *       {@code #list-panel h3} (260), {@code #list-export-btn} (261), {@code #item-search} (272),
 *       {@code #item-search-clear} (277), {@code #status-bar} (409).
 * </ul>
 *
 * <p>The original's author knew about the hazard elsewhere: the two drawer blocks say
 * {@code display: flex !important} on their toggles precisely because the base rule follows them.
 * In this block they did not, and fourteen rules went quietly nowhere.
 *
 * <p><b>What this app does about it: the user's decision, 2026-08-13.</b> Only the two the phone
 * actually complained about are put back to what the original renders: the <b>dialog buttons</b>
 * ({@link #dialogButtonFont()}) and the <b>status bar</b> ({@link #statusFont()}). <b>The other
 * twelve keep the larger touch sizes on purpose</b>; they are plainly what the original's author
 * intended, they are easier to hit with a thumb, and reverting them would shrink an item list and a
 * dialog nobody complained about. That makes them a deliberate departure rather than an error, and
 * it is <b>CLAUDE.md §5.5 D-27</b>; MANUAL.md's "The fourteen rules that were never in force" is
 * the long version.
 *
 * <p>⚠ This line claimed a §5.5 entry from M6.4a onward and <b>there was none</b> until the user
 * wrote D-27 on 2026-09-19. The behavior had shipped; only the entry was missing, which is the
 * failure CLAUDE.md §3 describes: a pointer nobody follows stays wrong indefinitely.
 *
 * <p><b>The lesson worth keeping:</b> transcribing a stylesheet proves what it <em>says</em>, never
 * what it <em>does</em>. Only a measurement tells the two apart, and this one hid behind a whole
 * milestone of green tests.
 */
public final class TouchType {

    private TouchType() {
    }

    /** The whole of this class, in one line: the phone's number, or the desktop's. */
    private static double pick(double desktop, double touch) {
        return Device.isTouch() ? touch : desktop;
    }

    // ------------------------------------------------------------- dialogs
    //
    // ⚠ THE WHOLE DIALOG TAKES THE DESKTOP'S NUMBERS ON A PHONE, and that is deliberate.
    //
    // Every rule in this group is one of the fourteen dead ones described in the class
    // documentation above: the original asks for a bigger dialog inside `@media (pointer: coarse)`
    // and the cascade throws the request away, so the phone has always rendered these at 13 px on
    // the desktop's padding. Implementing the intended sizes made the Add Item dialog wider than a
    // 360 px screen: its third preset, its + button and the Name placeholder all clipped off the
    // right-hand edge.
    //
    // The user compared the two side by side on 2026-08-13 and asked for the dialogs to match the
    // original. The top bar's own W/L/H fields are NOT in this group and are deliberately left
    // large: `#top-bar label` and `#top-bar input` are declared before the touch block, so those
    // overrides are live, the original really does grow them, and the user likes them that way.

    public static double dialogMinWidth() {
        return Tokens.DIALOG_MIN_WIDTH;
    }

    public static double dialogPadding() {
        return Tokens.DIALOG_PADDING;
    }

    public static double dialogTitleFont() {
        return Tokens.FONT_DIALOG_TITLE;
    }

    /** Dialog labels, inputs and the name box: the original's 13 px, on a phone as on a desktop. */
    public static double dialogFont() {
        return Tokens.FONT_CONTROL;
    }

    public static double dialogInputPaddingV() {
        return Tokens.DIALOG_INPUT_PADDING_V;
    }

    public static double dialogInputPaddingH() {
        return Tokens.DIALOG_INPUT_PADDING_H;
    }

    public static double dialogNumberWidth() {
        return Tokens.DIALOG_NUMBER_WIDTH;
    }

    public static double dialogTextWidth() {
        return Tokens.DIALOG_TEXT_WIDTH;
    }

    /**
     * <b>A dialog button keeps the desktop's 13 px on a phone, unlike the labels and inputs beside
     * it.</b> The original's {@code .modal-btns button} touch rule is one of the dead fourteen (see
     * the class documentation), and the reference screenshot settles it: Cancel measures 76.7 CSS
     * px, which is six characters of 13 px type inside 14 px of padding and a border (76.8), and
     * nothing like the 99.6 the touch rule would give.
     *
     * <p>Kept as its own method rather than folded back into {@link #dialogFont()} (which now
     * returns the same number) because the original writes the two rules separately and one of them
     * could be corrected without the other. Oversized buttons are what pushed Cancel, Confirm and
     * Delete past the edge of a 360 px dialog and left JavaFX ellipsizing them to {@code C...}.
     */
    public static double dialogButtonFont() {
        return Tokens.FONT_CONTROL;
    }

    /** Desktop's 5 px, for the reason given on {@link #dialogButtonFont()}. */
    public static double dialogButtonPaddingV() {
        return Tokens.DIALOG_BUTTON_PADDING_V;
    }

    /** Desktop's 14 px, for the reason given on {@link #dialogButtonFont()}. */
    public static double dialogButtonPaddingH() {
        return Tokens.DIALOG_BUTTON_PADDING_H;
    }

    /**
     * A dialog button is pinned to a fixed height, and on a phone that height is worked out rather
     * than taken from a token.
     *
     * <p>The pin exists because a browser gives a button a minimum height that owes nothing to the
     * font, and the desktop reference measures it at 30. A phone renders the same 13 px type, so it
     * cannot simply be left computed: JavaFX's line box for this face is about 1.6 em against the
     * browser's 1.18, which lands 31 px where the phone reference measures 27.3. Same arithmetic
     * as {@code TopBar.sizeForTouch}, and the same reason.
     */
    public static double dialogButtonHeight() {
        if (!Device.isTouch()) {
            return Tokens.DIALOG_BUTTON_HEIGHT;
        }
        return Tokens.TOUCH_TEXT_LINE_EM * dialogButtonFont()
                + 2 * dialogButtonPaddingV() + 2 * Tokens.TOUCH_BUTTON_BORDER;
    }

    /**
     * The number box keeps its desktop pin on a phone as well.
     *
     * <p>This used to come off, and the reasoning was sound while it lasted: at 16 px type the box's
     * natural height already exceeded the 28 px pin, so holding it there would have clipped the
     * text. That premise is gone (the dialog renders 13 px on a phone too), so the pin is exactly
     * as right here as it is on a desktop.
     */
    public static double dialogNumberHeight() {
        return Tokens.DIALOG_NUMBER_HEIGHT;
    }

    public static double idInputWidth() {
        return Tokens.DIALOG_ID_WIDTH;
    }

    /** The little "ID:" caption beside the Add dialog's identifier box. */
    public static double idLabelFont() {
        return Tokens.FONT_ID_LABEL;
    }

    /** And the box itself, which on a desktop matches its caption, and now on a phone too. */
    public static double idInputFont() {
        return Tokens.FONT_ID_LABEL;
    }

    // ----------------------------------------------------------- item list

    public static double listRowFont() {
        return pick(Tokens.FONT_LIST_ROW, Tokens.FONT_LIST_ROW_TOUCH);
    }

    public static double listRowPaddingV() {
        return pick(Tokens.LIST_ROW_PADDING_V, Tokens.LIST_ROW_PADDING_V_TOUCH);
    }

    public static double listRowPaddingH() {
        return pick(Tokens.LIST_ROW_PADDING_H, Tokens.LIST_ROW_PADDING_H_TOUCH);
    }

    public static double listHeaderPaddingV() {
        return pick(Tokens.LIST_HEADER_PADDING_V, Tokens.LIST_HEADER_PADDING_V_TOUCH);
    }

    public static double listHeaderPaddingH() {
        return pick(Tokens.LIST_HEADER_PADDING_H, Tokens.LIST_HEADER_PADDING_H_TOUCH);
    }

    public static double listHeaderFont() {
        return pick(Tokens.FONT_LIST_HEADER, Tokens.FONT_LIST_HEADER_TOUCH);
    }

    public static double exportButtonSize() {
        return pick(Tokens.LIST_EXPORT_BUTTON_SIZE, Tokens.LIST_EXPORT_BUTTON_SIZE_TOUCH);
    }

    public static double exportButtonFont() {
        return pick(Tokens.FONT_CONTROL, Tokens.FONT_LIST_EXPORT_TOUCH);
    }

    public static double searchFont() {
        return pick(Tokens.FONT_SEARCH, Tokens.FONT_SEARCH_TOUCH);
    }

    public static double searchPaddingV() {
        return pick(Tokens.DIALOG_INPUT_PADDING_V, Tokens.SEARCH_PADDING_V_TOUCH);
    }

    public static double searchPaddingH() {
        return pick(Tokens.DIALOG_INPUT_PADDING_H, Tokens.SEARCH_PADDING_H_TOUCH);
    }

    public static double searchClearFont() {
        return pick(Tokens.FONT_CONTROL, Tokens.FONT_CONTROL_TOUCH);
    }

    public static double searchClearPaddingV() {
        return pick(2, Tokens.SEARCH_CLEAR_PADDING_V_TOUCH);
    }

    public static double searchClearPaddingH() {
        return pick(7, Tokens.SEARCH_CLEAR_PADDING_H_TOUCH);
    }

    // ---------------------------------------------------------- status bar

    /**
     * <b>The status bar takes the desktop's numbers on a phone too, and that is not an oversight.</b>
     *
     * <p>The original's touch block asks for {@code font-size: 13px; padding: 5px 12px} at line 134,
     * and the browser never applies it: the base {@code #status-bar} rule is at line 409, later in
     * the file at equal specificity, so the cascade gives it to the base. The phone really does
     * render this bar at 11 px on 3 × 10, measured off the reference screenshot, whose character
     * advance is 6.59 CSS px, which is 11 × 0.6 and not 13 × 0.6.
     *
     * <p>See {@link #DEAD_TOUCH_RULES} for the full list and for why only this one and the dialog
     * buttons were put back: those two were reported from the phone, where the oversized type is
     * what pushed the tip past the edge of the screen.
     */
    public static double statusFont() {
        return Tokens.FONT_STATUS;
    }

    /** Desktop's 3 px, for the reason given on {@link #statusFont()}. */
    public static double statusPaddingV() {
        return Tokens.STATUS_PADDING_V;
    }

    /** Desktop's 10 px, for the reason given on {@link #statusFont()}. */
    public static double statusPaddingH() {
        return Tokens.STATUS_PADDING_H;
    }

    // ------------------------------------------------------------- presets

    public static double presetSize() {
        return pick(Tokens.PRESET_SIZE, Tokens.PRESET_SIZE_TOUCH);
    }

    public static double presetFont() {
        return pick(Tokens.FONT_PRESET, Tokens.FONT_PRESET_TOUCH);
    }

    public static double presetAddFont() {
        return pick(Tokens.FONT_PRESET_ADD, Tokens.FONT_PRESET_ADD_TOUCH);
    }
}
