package com.modcritic.invmgr.ui;

import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.util.Duration;

/**
 * The room on a phone, with both side panels sliding over it instead of sitting beside it.
 *
 * <p>On a desktop the layer slider and the item list are columns: 58 px and 180 px of the window,
 * permanently. On a 360 px phone that would be 238 of the 360: two thirds of the screen given to
 * two panels you look at occasionally. So the original takes them out of the layout and hangs them
 * off the edges, each with a small tab to pull it out by. This is that arrangement.
 *
 * <h2>What changes, and what does not</h2>
 *
 * <p>The two panels are the same objects doing the same job. Neither knows it is in a drawer; all
 * that differs is where it is put and that it is slid off the edge until asked for. The tabs, the
 * sliding, and the rule that only one may be out at a time live here and nowhere else.
 *
 * <h2>The backdrop, which is deliberately not here</h2>
 *
 * <p>The original has a {@code #drawer-backdrop} over the room while a drawer is open, and its CSS
 * says {@code background: transparent} and {@code pointer-events: none}. It is invisible and it
 * receives nothing: the element does no work at all. The original's own comment explains how it
 * ended up that way: an earlier version <em>did</em> catch clicks, and that broke panning the part
 * of the room you could still see. Tap-to-close moved onto the room itself and the backdrop was
 * left behind as scaffolding.
 *
 * <p>Copying a node that is both transparent and unpickable would be copying nothing, so what is
 * copied is the behavior. The closing lives on the room, where the original put it in the end. A
 * drag never fires a click, so panning past an open drawer works and only a real tap shuts it.
 */
public final class TouchDrawers extends StackPane {

    private final LayerSliderDrawer slider;
    private final Button sliderTab = tab(true);
    private final Button listTab = tab(false);

    private ItemListPanel list;

    /** Which drawer is out, or null when both are away. Exactly one may be open. */
    private Region open;

    public TouchDrawers(LayerSliderDrawer slider, Region room) {
        this.slider = slider;

        showClosedIcon(sliderTab);
        showClosedIcon(listTab);
        Hints.attach(sliderTab, "Layer slider");
        Hints.attach(listTab, "Item list");

        // The room first, so everything else draws over it. The tab after its drawer, so it stays
        // reachable while the drawer is out: the original's z-index 210 against 200.
        getChildren().addAll(room, slider, sliderTab);

        hang(slider, Pos.CENTER_LEFT, Tokens.DRAWER_SHADOW_OFFSET);
        StackPane.setAlignment(sliderTab, Pos.CENTER_LEFT);
        slider.setTranslateX(-Tokens.SLIDER_DRAWER_WIDTH);

        sliderTab.setOnAction(event -> toggle(slider));
        listTab.setOnAction(event -> toggle(list));

        // Tap the room to shut whatever is open. See the note above about the backdrop.
        room.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> closeAny());

        // ⚠ AND NOTHING IN HERE MAY REACH OUTSIDE THIS BOX, which is #main's own
        // `overflow: hidden` at line 140 of the original and is load-bearing on a phone held
        // sideways. Landscape leaves this container 64 design pixels tall; a drawer that
        // insists on more than that is centered by the StackPane and hangs off both ends,
        // reaching up over the island and down over the status bar. Nothing of it can be SEEN
        // there, because a closed drawer is parked off the side of the screen, but it is
        // TAPPED there all the same: a Region takes a tap anywhere inside its bounds. The
        // island's six buttons stopped responding entirely with the top bar open, and looked
        // perfectly normal doing it.
        //
        // The drawers ask for less as well, in their own constructors, so that the usual case is
        // a panel the height of the room rather than a panel cut off at both ends. This is the
        // backstop for everything else in here, the tabs and the room included.
        Clips.toOwnBox(this);
    }

    /**
     * Puts the item list in, once it exists.
     *
     * <p>Two steps rather than one because the list panel cannot be built until the drag ghost is,
     * and the ghost's layer cannot be built until this is. The desktop layout has the same knot and
     * unties it the same way.
     */
    public void addItemList(ItemListPanel panel) {
        this.list = panel;
        getChildren().addAll(panel, listTab);

        hang(panel, Pos.CENTER_RIGHT, -Tokens.DRAWER_SHADOW_OFFSET);
        StackPane.setAlignment(listTab, Pos.CENTER_RIGHT);
        panel.setTranslateX(Tokens.LIST_PANEL_WIDTH_TOUCH);
    }

    /** Opens a drawer, or shuts it if it is already out. Opening one closes the other. */
    public void toggle(Region drawer) {
        if (drawer == null) {
            return;
        }
        boolean wasOpen = open == drawer;
        closeAny();
        if (wasOpen) {
            return;
        }

        Button tab = tabOf(drawer);
        slide(drawer, 0);
        slide(tab, drawer == slider
                ? Tokens.SLIDER_DRAWER_WIDTH : -Tokens.LIST_PANEL_WIDTH_TOUCH);
        // An arrow pointing back at the edge it came from, replacing the icon entirely, which is
        // what the fixed 27 x 36 box is for, so a glyph and a drawing occupy the same space.
        tab.setGraphic(null);
        tab.setText(drawer == slider
                ? Tokens.DRAWER_TAB_CLOSE_LEFT : Tokens.DRAWER_TAB_CLOSE_RIGHT);
        open = drawer;
    }

    /** Shuts whatever is open, and puts its tab's icon back. */
    public void closeAny() {
        if (open == null) {
            return;
        }
        slide(open, open == slider
                ? -Tokens.SLIDER_DRAWER_WIDTH : Tokens.LIST_PANEL_WIDTH_TOUCH);
        Button tab = tabOf(open);
        slide(tab, 0);
        showClosedIcon(tab);
        open = null;
    }

    /**
     * Hides both tabs, for the 3D view.
     *
     * <p>The drawers themselves go with the top and status bars, through the same code that takes
     * all the 2D chrome out of the layout. The tabs are not in that list because they are not
     * chrome a desktop has.
     */
    public void setTabsVisible(boolean visible) {
        for (Button tab : new Button[] {sliderTab, listTab}) {
            tab.setVisible(visible);
            tab.setManaged(visible);
        }
    }

    /** Which drawer is currently out, or null. */
    public Region openDrawer() {
        return open;
    }

    public Button sliderTab() {
        return sliderTab;
    }

    public Button listTab() {
        return listTab;
    }

    // ------------------------------------------------------------- building

    /** Hangs a drawer off one edge, full height, casting its shadow away from that edge. */
    private static void hang(Region drawer, Pos side, double shadowOffset) {
        StackPane.setAlignment(drawer, side);
        drawer.setMaxHeight(Double.MAX_VALUE);
        drawer.setStyle(drawer.getStyle()
                + "-fx-effect: dropshadow(gaussian, " + Tokens.hex(Tokens.DRAWER_SHADOW) + ", "
                + Tokens.DRAWER_SHADOW_BLUR + ", 0, " + shadowOffset + ", 0);");
    }

    /**
     * One drawer tab: a fixed 27 × 36 box on the edge of the screen.
     *
     * <p>Fixed, and the original's comment says why: the box matches what its old content and
     * padding came to exactly, so the icon inside could be enlarged without the button growing,
     * and so the arrow it shows when open is drawn at the same size as the icon it replaces,
     * instead of at whatever size that character happens to be.
     *
     * <p>The two rounded corners face the room. The border on the drawer's own side is left off, so
     * that with the drawer out the two read as one piece rather than as a button stuck to a panel.
     */
    private static Button tab(boolean onTheLeft) {
        Button button = new Button();
        button.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_DRAWER_TAB));
        button.setPadding(new Insets(Tokens.DRAWER_TAB_PADDING));
        button.setMinSize(Tokens.DRAWER_TAB_WIDTH, Tokens.DRAWER_TAB_HEIGHT);
        button.setPrefSize(Tokens.DRAWER_TAB_WIDTH, Tokens.DRAWER_TAB_HEIGHT);
        button.setMaxSize(Tokens.DRAWER_TAB_WIDTH, Tokens.DRAWER_TAB_HEIGHT);

        String radius = onTheLeft
                ? "0 " + Tokens.DRAWER_TAB_RADIUS + " " + Tokens.DRAWER_TAB_RADIUS + " 0"
                : Tokens.DRAWER_TAB_RADIUS + " 0 0 " + Tokens.DRAWER_TAB_RADIUS;
        button.setStyle("-fx-background-color: " + Tokens.hex(Tokens.DRAWER_TAB_BG) + ";"
                + "-fx-text-fill: " + Tokens.hex(Tokens.DRAWER_TAB_TEXT) + ";"
                + "-fx-border-color: " + Tokens.hex(Tokens.CONTROL_BORDER) + ";"
                + "-fx-border-width: " + (onTheLeft ? "1 1 1 0" : "1 0 1 1") + ";"
                + "-fx-background-radius: " + radius + ";"
                + "-fx-border-radius: " + radius + ";");
        return button;
    }

    /** Puts a tab back to the drawing of the drawer it opens. */
    private void showClosedIcon(Button tab) {
        tab.setText("");
        tab.setGraphic(tab == sliderTab
                ? Icons.layerSlider(Tokens.DRAWER_TAB_ICON)
                : Icons.itemList(Tokens.DRAWER_TAB_ICON));
    }

    // ------------------------------------------------------------ behavior

    private Button tabOf(Region drawer) {
        return drawer == slider ? sliderTab : listTab;
    }

    private static void slide(Node node, double toX) {
        TranslateTransition move =
                new TranslateTransition(Duration.millis(Tokens.DRAWER_SLIDE_MS), node);
        move.setToX(toX);
        move.setInterpolator(Interpolator.EASE_BOTH);
        move.play();
    }
}
