package com.modcritic.invmgr.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;

/**
 * The strip of six buttons that never goes away on a phone.
 *
 * <p>Under the top bar, above the room: {@code ■} to add a box, Undo, Fit, Plan, Units, and a
 * {@code ▼} that folds the top bar out of sight. On a desktop it does not exist at all; those five
 * live in the bar, where there is room for them.
 *
 * <p>It is what makes the top bar foldable. Collapsing the bar on a phone takes away Save, Load,
 * the room's dimensions <em>and</em> the five things you actually use while arranging a room; the
 * island is the five you keep, so the bar can go.
 *
 * <h2>The five buttons are the bar's own</h2>
 *
 * <p>The original declares ten buttons (five in the bar, five in the island) and hides whichever
 * set the screen does not want, because CSS cannot move an element from one place in the document
 * to another. It then wires ten click handlers, keeps two copies of every active-state rule, and
 * has to remember to change both. JavaFX has no such limit, so this borrows the five objects out of
 * {@link TopBar} and adds them here. One handler each, one color each, one hint each. See
 * {@code TopBar.islandButtons}.
 *
 * <p>That is also why the fills below are only <em>checked</em> against the original rather than
 * set here: {@code #island-add} and {@code #add-item-btn} are the same {@code #2e3e4e}, and so are
 * the other four and all three active states. The island changes the size of these buttons and
 * nothing else about them.
 *
 * <h2>Why the type is a listener</h2>
 *
 * <p>The original sizes every button as a percentage of the island's own width ({@code
 * clamp(12px, 4.5cqi, 16px)} and friends) so that six buttons fit a 320 px phone and do not grow
 * absurd on a 412 px one turned sideways. <b>JavaFX has no container queries and no {@code clamp}.
 * A width listener is the only way to have this at all</b>, and the arithmetic it runs lives in
 * {@link Fluid} so it can be checked at every width without a window.
 *
 * <p>What must survive is the <em>ratio</em>: Add largest, Undo and the menu in the middle, Fit,
 * Plan and Units smallest. That hierarchy is the design, and it holds at 320 px and at 412 because
 * all three are percentages of the same number.
 */
public final class TouchIsland extends HBox {

    /** The menu button when the bar is folded away: press it to bring the bar back. */
    public static final String SHOW_BAR_GLYPH = "▼";

    /** And when the bar is out. */
    public static final String HIDE_BAR_GLYPH = "▲";

    private final TopBar bar;
    private final Button menu = TopBar.button(SHOW_BAR_GLYPH, Tokens.BUTTON_ISLAND_MENU_BG);

    /**
     * Builds the island around a bar, and folds that bar away.
     *
     * <p><b>It starts folded.</b> That is the original's own behavior and its own reason: on a
     * phone the canvas is the app, and three rows of room controls across the top of a 740 px
     * screen is a fifth of it spent on things you set once.
     */
    public TouchIsland(TopBar bar) {
        this.bar = bar;

        setAlignment(Pos.CENTER);
        setPadding(new Insets(Tokens.ISLAND_PADDING_V, Tokens.ISLAND_PADDING_H,
                Tokens.ISLAND_PADDING_V, Tokens.ISLAND_PADDING_H));
        setStyle("-fx-background-color: " + Tokens.hex(Tokens.TOP_BAR_BG) + ";"
                + "-fx-border-color: " + Tokens.hex(Tokens.SEPARATOR)
                + " transparent transparent transparent;"
                + "-fx-border-width: 1 0 0 0;");

        getChildren().addAll(bar.islandButtons());
        getChildren().add(menu);

        Hints.attach(menu, "Menu");
        menu.setOnAction(event -> setBarOpen(bar.isCollapsed(), true));

        // Fluid.queryWidth, not the width the listener is handed: a cqi is one percent of the
        // container's CONTENT box, and this island has 10 px of padding each side. Handing over the
        // border box made every font here 5.9% too large (see Fluid.queryWidth for the pixels).
        widthProperty().addListener((observable, before, after) -> applyType(Fluid.queryWidth(this)));
        // Seeded, because a listener only fires on a change and the island has to be legible in
        // the first frame it is drawn in, not the second.
        applyType(Fluid.queryWidth(this));

        setBarOpen(false, false);
    }

    /**
     * Shows or folds the top bar, and turns the arrow round to match.
     *
     * @param animated false only for the opening state, where an animation would be a flicker
     */
    public void setBarOpen(boolean open, boolean animated) {
        bar.setCollapsed(!open, animated);
        menu.setText(open ? HIDE_BAR_GLYPH : SHOW_BAR_GLYPH);
        // And re-draw it, because on touch the arrow the eye sees is a GRAPHIC sized from its own
        // ink rather than the button's text, so setText alone changes what getText() answers and
        // nothing on the screen. See TopBar.sizeGlyphForTouch.
        if (Device.isTouch()) {
            typeGlyph(menu, Fluid.size(Fluid.queryWidth(this), Tokens.ISLAND_FONT_MIN,
                    Tokens.ISLAND_FONT_PERCENT, Tokens.ISLAND_FONT_MAX));
        }
    }

    /**
     * Re-types the six buttons and the gaps between them from the island's own width.
     *
     * <p>The menu button takes the base size rather than the small one, which looks like an
     * oversight and is not: it is the {@code #island-menu} rule in the original, which sets a
     * color and no font size and so inherits the shared {@code #top-island button} size. Undo does
     * the same. Only Add is bigger and only the three toggles are smaller.
     */
    private void applyType(double islandWidth) {
        setSpacing(Fluid.size(islandWidth, Tokens.ISLAND_GAP_MIN, Tokens.ISLAND_GAP_PERCENT,
                Tokens.ISLAND_GAP_MAX));

        double base = Fluid.size(islandWidth, Tokens.ISLAND_FONT_MIN, Tokens.ISLAND_FONT_PERCENT,
                Tokens.ISLAND_FONT_MAX);
        double addSize = Fluid.size(islandWidth, Tokens.ISLAND_ADD_FONT_MIN,
                Tokens.ISLAND_ADD_FONT_PERCENT, Tokens.ISLAND_ADD_FONT_MAX);
        double toggleSize = Fluid.size(islandWidth, Tokens.ISLAND_TOGGLE_FONT_MIN,
                Tokens.ISLAND_TOGGLE_FONT_PERCENT, Tokens.ISLAND_TOGGLE_FONT_MAX);

        // ■ and ▼ are drawn characters, not words, and the original drew them in a borrowed face,
        // so they are the two that need their box stating rather than measuring. See
        // Tokens.TOUCH_GLYPH_ADVANCE_EM.
        typeGlyph(addButton(), addSize);
        type(undoButton(), base);
        type(fitButton(), toggleSize);
        type(planButton(), toggleSize);
        type(unitsButton(), toggleSize);
        typeGlyph(menu, base);
    }

    /** One button, set in its size, padded in ems of that size as the original pads it. */
    private static void type(Button button, double fontSize) {
        TopBar.sizeForTouch(button, fontSize, Tokens.ISLAND_BUTTON_PADDING_V_EM * fontSize,
                Tokens.ISLAND_BUTTON_PADDING_H_EM * fontSize);
    }

    /** The same, for the two whose label is a glyph the bundled typeface draws at the wrong width. */
    private static void typeGlyph(Button button, double fontSize) {
        TopBar.sizeGlyphForTouch(button, fontSize, Tokens.ISLAND_BUTTON_PADDING_V_EM * fontSize,
                Tokens.ISLAND_BUTTON_PADDING_H_EM * fontSize);
    }

    // ------------------------------------------------------------ for tests

    public Button menuButton() {
        return menu;
    }

    public Button addButton() {
        return bar.addButtonNode();
    }

    public Button undoButton() {
        return bar.undoButton();
    }

    public Button fitButton() {
        return bar.fitButton();
    }

    public Button planButton() {
        return bar.planButton();
    }

    public Button unitsButton() {
        return bar.unitsButton();
    }
}
