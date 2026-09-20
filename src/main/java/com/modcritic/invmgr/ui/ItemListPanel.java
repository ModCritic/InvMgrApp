package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.engine.Search;
import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.util.Duration;

/**
 * The panel down the right-hand side listing everything in the room.
 *
 * <p>Three jobs. It is <b>an index</b>: every box, in name order, so something buried under a
 * pile is still findable. It is <b>a search</b>, by name, or by size with a word like
 * {@code w20}. And it is <b>where planned items live</b>, because a planned item is deliberately
 * not drawn in the room at all, so this list is the only place it exists until it is dragged
 * out and dropped in.
 *
 * <p><b>The rule that gets broken by accident.</b> Clicking a row must not rebuild the list. It
 * is tempting (redrawing everything is the simple way to move a highlight), and it destroys
 * the row you just clicked, replacing it with a new one between the two halves of a
 * double-click. The double-click then never registers, and "double-click a row to edit it"
 * silently stops working. That was a real bug in the original; the fix is
 * {@link #setSelectedId}, which recolors the rows that are already there.
 */
public final class ItemListPanel extends VBox {

    /**
     * How many extra rows to build above and below what fits on screen.
     *
     * <p>Two, so that a scroll of less than a row never shows a gap before the next refill runs.
     * Making it zero would be visibly correct in a test and would flicker on a phone; making it
     * large gives back the cost this change exists to remove.
     */
    private static final int OVERSCAN_ROWS = 2;

    /**
     * The height to assume before the panel has ever been laid out.
     *
     * <p>{@code getViewportBounds()} is empty until the first layout pass, and a caller that
     * builds the panel and reads the rows back without ever showing it would otherwise see an
     * empty list. Generous rather than exact: over-building once costs nothing and the real
     * viewport corrects it on the next pass.
     */
    private static final double FALLBACK_VIEWPORT_PX = 900;

    /** Told which item was clicked, so the room can select and scroll to it. */
    @FunctionalInterface
    public interface ItemHandler {
        void handle(Item item);
    }

    /** Asked whether a point in the window is over the room, for the drag ghost's feedback. */
    @FunctionalInterface
    public interface ScenePointTest {
        boolean test(double sceneX, double sceneY);
    }

    /** Told where a planned item was dropped, in window coordinates. */
    @FunctionalInterface
    public interface DropHandler {
        void dropped(Item item, double sceneX, double sceneY);
    }

    private AppState state;
    private String selectedId;

    private final Button exportButton = iconButton("⤓", TouchType.exportButtonSize());
    private final TextField searchField = new TextField();
    private final Button clearSearchButton = clearButton();

    /**
     * What the scroller holds: a spacer, the handful of rows actually on screen, another spacer.
     *
     * <p>A {@code VBox} rather than absolute positions, deliberately. The collapse animation that
     * closes the gap behind a lifted planned row works by shrinking the row's own height and
     * letting the box move everything below it; positioning rows by hand would mean animating all
     * of them, and the point of this change was to keep every behavior exactly as it was.
     */
    private final VBox rowBox = new VBox();
    private final ScrollPane scroller = new ScrollPane(rowBox);

    /** Stands in for the rows above the viewport, so the list is its full height. */
    private final Region topSpacer = new Region();

    /** Stands in for the rows below it. */
    private final Region bottomSpacer = new Region();

    /** Every item the list is showing, filtered and sorted, whether or not it has a node. */
    private final List<Item> visible = new ArrayList<>();

    /**
     * Where each row starts, with the total height at the end.
     *
     * <p>{@code rowTop[i]} is the top of row {@code i} and {@code rowTop[visible.size()]} is how
     * tall the whole list is, which is what the two spacers are worked out from. Sorted by
     * construction, so the rows on screen are found by binary search rather than by walking.
     */
    private double[] rowTop = {0};

    /** Rows not currently showing anything, ready to be pointed at another item. */
    private final List<Row> pool = new ArrayList<>();

    /** The rows that have a node right now, by item id. Only ever about a screenful. */
    private final Map<String, Row> liveById = new HashMap<>();

    /**
     * A row used to ask how tall a row for some item would be, without building one.
     *
     * <p>Needed because the spacers have to know the height of rows that do not exist. A name long
     * enough to wrap makes a taller row, so the heights are not all the same and cannot be assumed;
     * {@code LongItemListTest.theMeasuredHeightsMatchTheRealOnes} is what says this measurement
     * agrees with what the rows actually come out as.
     *
     * <p><b>⚠ IT IS IN THE SCENE, INVISIBLE AND UNMANAGED, AND THAT IS NOT TIDINESS.</b> A JavaFX
     * {@code Label} measures text through its skin, and a control that has never been in a Scene
     * has never been given one. Off to one side it reports a <b>width of zero for every string</b>,
     * so every row measures as one line: {@code RowHeightProbe} reads 0.0 px wide and a 20 px row
     * for a name that renders at 79. Added as a child of the panel and made invisible and
     * unmanaged, the same label reads 511.2 px wide and the row 78. Invisible costs nothing to
     * draw and unmanaged costs nothing to lay out.
     */
    private final Row ruler = new Row();

    /**
     * Where each item sits in {@link #visible}, by id.
     *
     * <p>⚠ A map rather than {@code visible.indexOf}, and the difference matters more here than
     * anywhere else in the class. {@link #refill} runs on every scroll event and asks for an index
     * once per live row, and the sort asked for two per comparison; on a 500 item list that is a
     * six figure count of string comparisons a frame, put straight into the one code path this
     * whole change exists to make cheap. Rebuilt whenever {@link #visible} is.
     */
    private final Map<String, Integer> indexOfId = new HashMap<>();

    /** The row width {@link #rowTop} was measured at, or -1 if it has not been measured. */
    private double measuredAtWidth = -1;

    /**
     * Whether the ruler had a working skin the last time it measured.
     *
     * <p>False until the panel is in a scene, which is not true when the constructor runs its
     * first {@code rebuild}. Without this the list would keep whatever the skinless ruler said
     * forever, and every row would be one line tall.
     */
    private boolean measuredForReal;

    /** Guards against {@link #refill} being re-entered by the layout pass it causes. */
    private boolean refilling;

    /**
     * The row most recently pressed, kept so that it is never recycled out from under a
     * double-click. See {@code Row.pinned}.
     */
    private Row lastPressed;

    private final DragGhost ghost;

    private ItemHandler onSelect = item -> { };
    private ItemHandler onEdit = item -> { };
    private Runnable onExport = () -> { };
    private ScenePointTest overRoom = (x, y) -> false;
    private DropHandler onPlannedDropped = (item, x, y) -> { };

    public ItemListPanel(AppState state, DragGhost ghost) {
        this.state = state;
        this.ghost = ghost;

        // Wider on a phone than on a desktop, which is the original's own number and only looks
        // backwards until you notice that on touch this is an overlay: closed it takes nothing
        // from the room, so it can afford to be comfortable when it is open.
        double width = Device.isTouch() ? Tokens.LIST_PANEL_WIDTH_TOUCH : Tokens.LIST_PANEL_WIDTH;
        setPrefWidth(width);
        setMinWidth(width);
        setMaxWidth(width);

        // As tall as the row and no taller, for the same reason the layer drawer is; see the note
        // there and in Clips. 130 pixels is this panel's own floor, which a landscape window with
        // the top bar open does not have to give.
        setMinHeight(0);
        setStyle("-fx-background-color: " + Tokens.hex(Tokens.LIST_PANEL_BG) + ";"
                + "-fx-border-color: transparent transparent transparent "
                + Tokens.hex(Tokens.BORDER) + ";"
                + "-fx-border-width: 0 0 0 1;");

        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setStyle("-fx-background: " + Tokens.hex(Tokens.LIST_PANEL_BG) + ";"
                + "-fx-background-color: " + Tokens.hex(Tokens.LIST_PANEL_BG) + ";");
        rowBox.setStyle("-fx-background-color: " + Tokens.hex(Tokens.LIST_PANEL_BG) + ";");
        VBox.setVgrow(scroller, Priority.ALWAYS);

        // The two things that change which rows are on screen. Both fire synchronously, so a
        // caller that sets the scroll position can read the rows back on the next line.
        scroller.vvalueProperty().addListener((observable, before, after) -> refill());
        scroller.viewportBoundsProperty().addListener((observable, before, after) -> refill());

        // Invisible and unmanaged, so it is laid out by nothing and drawn by nothing, but it is
        // in the scene and therefore has a skin. See the field's own note.
        ruler.node.setVisible(false);
        ruler.node.setManaged(false);
        getChildren().addAll(header(), searchRow(), scroller, ruler.node);

        exportButton.setOnAction(event -> onExport.run());
        // The one button in the panel that is a bare symbol, so the one that needs saying aloud.
        // The original writes it as a title attribute, in the same place, for the same reason.
        Hints.attach(exportButton, "Export Item List");
        searchField.textProperty().addListener((observable, before, after) -> rebuild());
        Hints.attach(clearSearchButton, "Clear Search");
        clearSearchButton.setOnAction(event -> {
            searchField.clear();
            searchField.requestFocus();
        });

        rebuild();
    }

    // ------------------------------------------------------------------ wiring

    public void setOnSelect(ItemHandler handler) {
        this.onSelect = handler == null ? item -> { } : handler;
    }

    public void setOnEdit(ItemHandler handler) {
        this.onEdit = handler == null ? item -> { } : handler;
    }

    public void setOnExport(Runnable handler) {
        this.onExport = handler == null ? () -> { } : handler;
    }

    /** Supplies the "is the pointer over the room?" test the drag ghost fades on. */
    public void setOverRoomTest(ScenePointTest test) {
        this.overRoom = test == null ? (x, y) -> false : test;
    }

    public void setOnPlannedDropped(DropHandler handler) {
        this.onPlannedDropped = handler == null ? (item, x, y) -> { } : handler;
    }

    public void setState(AppState state) {
        this.state = state;
        this.selectedId = null;
        rebuild();
    }

    // ------------------------------------------------------------------ header

    private HBox header() {
        Label heading = new Label("Items");
        heading.setFont(Font.font(Tokens.FONT_FAMILY, TouchType.listHeaderFont()));
        heading.setTextFill(Tokens.TEXT_SECTION_HEADER);

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        HBox header = new HBox(Tokens.LIST_ROW_GAP, heading, gap, exportButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(TouchType.listHeaderPaddingV(), TouchType.listHeaderPaddingH(),
                TouchType.listHeaderPaddingV(), TouchType.listHeaderPaddingH()));
        header.setStyle("-fx-border-color: transparent transparent "
                + Tokens.hex(Tokens.BORDER) + " transparent;"
                + "-fx-border-width: 0 0 1 0;");
        return header;
    }

    private HBox searchRow() {
        searchField.setFont(Font.font(Tokens.FONT_FAMILY, TouchType.searchFont()));
        searchField.setPromptText("Search: name, W20, L12, H8...");

        searchField.setStyle("-fx-background-color: " + Tokens.hex(Tokens.SEARCH_BG) + ";"
                + "-fx-text-fill: " + Tokens.hex(Tokens.TEXT_INPUT) + ";"
                + "-fx-prompt-text-fill: " + Tokens.hex(Tokens.placeholderOver(Tokens.SEARCH_BG)) + ";"
                + "-fx-border-color: " + Tokens.hex(Tokens.BORDER) + ";"
                + "-fx-border-width: 1;"
                + "-fx-background-radius: 0; -fx-border-radius: 0;"
                + "-fx-padding: " + TouchType.searchPaddingV() + " "
                + TouchType.searchPaddingH() + " " + TouchType.searchPaddingV() + " "
                + TouchType.searchPaddingH() + ";");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.setMinWidth(0);

        HBox row = new HBox(Tokens.LIST_SEARCH_GAP, searchField, clearSearchButton);
        row.setAlignment(Pos.CENTER_LEFT);
        // NOT the touch header padding. The original overrides #list-header on touch and leaves
        // #list-search-wrap alone, so this row keeps its 6 x 8 on both platforms even though the
        // two happen to share a constant.
        row.setPadding(new Insets(Tokens.LIST_HEADER_PADDING_V, Tokens.LIST_HEADER_PADDING_H,
                Tokens.LIST_HEADER_PADDING_V, Tokens.LIST_HEADER_PADDING_H));
        row.setStyle("-fx-border-color: transparent transparent "
                + Tokens.hex(Tokens.SEPARATOR) + " transparent;"
                + "-fx-border-width: 0 0 1 0;");
        return row;
    }

    /**
     * The square export button, and the shape the clear button borrows.
     *
     * <p>Drawn in the symbol face, because its {@code ⤓} is one of the two characters the
     * interface's own typeface has no glyph for. The clear button's {@code ×} is <b>not</b>;
     * that one is ordinary punctuation, so {@link #clearButton()} builds its own button and stays
     * on the text face. See {@link Fonts#SYMBOL_FAMILY}.
     *
     * <p>The glyph goes in as a <em>graphic</em> rather than as the button's text, so that it is
     * centered on its own ink instead of on the math face's very tall line box; see
     * {@link Fonts#symbolGlyph}, which explains what that fixes.
     */
    private static Button iconButton(String glyph, double size) {
        Button button = new Button();
        button.setGraphic(Fonts.symbolGlyph(glyph, TouchType.exportButtonFont(), button));
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setMinSize(size, size);
        button.setPrefSize(size, size);
        button.setMaxSize(size, size);
        button.setPadding(Insets.EMPTY);
        styleQuietButton(button, Tokens.BUTTON_TOGGLE_BG, Tokens.TEXT_QUIET);
        button.setOnMouseEntered(event ->
                styleQuietButton(button, Tokens.BUTTON_BG_HOVER, Tokens.TEXT_PRIMARY));
        button.setOnMouseExited(event ->
                styleQuietButton(button, Tokens.BUTTON_TOGGLE_BG, Tokens.TEXT_QUIET));
        return button;
    }

    private static Button clearButton() {
        Button button = new Button("×");
        button.setFont(Font.font(Tokens.FONT_FAMILY, TouchType.searchClearFont()));
        button.setPadding(new Insets(TouchType.searchClearPaddingV(), TouchType.searchClearPaddingH(),
                TouchType.searchClearPaddingV(), TouchType.searchClearPaddingH()));
        styleQuietButton(button, Tokens.BUTTON_TOGGLE_BG, Tokens.TEXT_QUIET);
        button.setOnMouseEntered(event ->
                styleQuietButton(button, Tokens.BUTTON_BG_HOVER, Tokens.TEXT_PRIMARY));
        button.setOnMouseExited(event ->
                styleQuietButton(button, Tokens.BUTTON_TOGGLE_BG, Tokens.TEXT_QUIET));
        return button;
    }

    private static void styleQuietButton(Button button, Color background, Color text) {
        button.setStyle("-fx-background-color: " + Tokens.hex(background) + ";"
                + "-fx-text-fill: " + Tokens.hex(text) + ";"
                + "-fx-border-color: " + Tokens.hex(Tokens.CONTROL_BORDER) + ";"
                + "-fx-border-width: 1;"
                + "-fx-background-radius: 0; -fx-border-radius: 0;");
    }

    // -------------------------------------------------------------------- rows

    /** What the search box currently holds. Never saved anywhere. */
    public String searchQuery() {
        return searchField.getText();
    }

    public TextField searchField() {
        return searchField;
    }

    public Button exportButton() {
        return exportButton;
    }

    public Button clearSearchButton() {
        return clearSearchButton;
    }

    /**
     * Rebuilds every row from scratch.
     *
     * <p>Call this when the <em>contents</em> of the list change: an item added, deleted,
     * renamed, or the search narrowed. <b>Not</b> when only the selection moves; see
     * {@link #setSelectedId}.
     */
    public void rebuild() {
        for (Row row : new ArrayList<>(liveById.values())) {
            recycle(row);
        }
        liveById.clear();
        rowBox.getChildren().clear();

        visible.clear();
        visible.addAll(Search.visibleItems(state, searchField.getText()));
        indexOfId.clear();
        for (int i = 0; i < visible.size(); i++) {
            indexOfId.put(visible.get(i).id, i);
        }
        if (visible.isEmpty()) {
            // Two different empty states, because "nothing here" and "nothing matched" call
            // for two different next actions.
            rowTop = new double[] {0};
            rowBox.getChildren().add(emptyMessage(state.items.isEmpty()
                    ? "No items yet." : "No items match search."));
            return;
        }

        measureRows();
        rowBox.getChildren().addAll(topSpacer, bottomSpacer);
        refill();
    }

    /**
     * Works out where every row starts, without building any of them.
     *
     * <p>This is the price of not keeping a node per item: the two spacers have to be the exact
     * height of the rows they stand in for, so the height of a row that does not exist has to be
     * known. {@link #ruler} is asked, once per item per rebuild.
     *
     * <p><b>Rows are not all the same height.</b> A name too long for the panel wraps onto a second
     * line and makes its row taller, which is what the original does and what {@code Row} goes out
     * of its way to keep. So this cannot be a row count times a constant.
     */
    private void measureRows() {
        double width = rowWidth();
        rowTop = new double[visible.size() + 1];
        for (int i = 0; i < visible.size(); i++) {
            rowTop[i + 1] = rowTop[i] + ruler.heightFor(visible.get(i), width);
        }
        measuredAtWidth = width;
        measuredForReal = ruler.canMeasure();
        // Leave the ruler showing nothing, or it would hold the last item alive and answer
        // rowFor-style questions about a row that is not on screen.
        ruler.unbind();
    }

    /** How wide a row is: the viewport, since the scroller is set to fit its content to it. */
    private double rowWidth() {
        double width = scroller.getViewportBounds().getWidth();
        return width > 0 ? width : getPrefWidth();
    }

    /**
     * Points row nodes at the items currently on screen, and takes back the ones that scrolled off.
     *
     * <p><b>This is the whole of the change.</b> Instead of a node per item, there is a node per
     * row you can see, plus a couple either side so that scrolling never shows a gap. A 200 item
     * list builds about twenty rows rather than two hundred, which on the phone is the difference
     * between 32 ms a frame and 16.
     *
     * <p>⚠ <b>A row in the middle of a gesture is never taken back</b>, whatever has scrolled where.
     * See {@link Row#pinned}: that rule is what keeps this change from breaking the one thing
     * {@code ItemListPanel}'s class documentation warns about.
     */
    private void refill() {
        if (refilling || visible.isEmpty()) {
            return;
        }
        refilling = true;
        try {
            // The panel has been laid out since the last measurement, or has changed width, so
            // the heights the spacers rest on are out of date. The first pass through here is
            // always one of these: the constructor measures before the panel has a scene, when
            // the ruler cannot measure text at all.
            if (!measuredForReal || rowWidth() != measuredAtWidth) {
                measureRows();
            }
            double viewport = scroller.getViewportBounds().getHeight();
            if (viewport <= 0) {
                // Before the first layout there is no viewport to measure. Build a screenful
                // anyway, or a caller that never lays the panel out would see an empty list.
                viewport = Math.max(getHeight(), FALLBACK_VIEWPORT_PX);
            }
            double total = rowTop[visible.size()];
            double top = scroller.getVvalue() * Math.max(0, total - viewport);

            int first = Math.max(0, indexAt(top) - OVERSCAN_ROWS);
            int last = Math.min(visible.size() - 1, indexAt(top + viewport) + OVERSCAN_ROWS);

            // Take back anything outside the window, then hand out what is inside it. In that
            // order, so the rows scrolling off are back in the pool before the new ones ask.
            for (Row row : new ArrayList<>(liveById.values())) {
                int at = indexOf(row.item.id);
                if (at < first || at > last) {
                    recycle(row);
                }
            }
            for (int i = first; i <= last; i++) {
                Item item = visible.get(i);
                if (!liveById.containsKey(item.id)) {
                    Row row = pool.isEmpty() ? new Row() : pool.remove(pool.size() - 1);
                    row.bind(item);
                    liveById.put(item.id, row);
                }
            }

            // Rebuild the child list in row order: spacer, rows top to bottom, spacer.
            List<Row> showing = new ArrayList<>(liveById.values());
            showing.sort((a, b) -> Integer.compare(indexOf(a.item.id), indexOf(b.item.id)));
            List<javafx.scene.Node> children = new ArrayList<>();
            children.add(topSpacer);
            int firstShown = visible.size();
            int lastShown = -1;
            for (Row row : showing) {
                children.add(row.node);
                int at = indexOf(row.item.id);
                firstShown = Math.min(firstShown, at);
                lastShown = Math.max(lastShown, at);
            }
            children.add(bottomSpacer);
            rowBox.getChildren().setAll(children);

            double above = lastShown < 0 ? 0 : rowTop[firstShown];
            double below = lastShown < 0 ? total : total - rowTop[lastShown + 1];
            setSpacer(topSpacer, above);
            setSpacer(bottomSpacer, below);
        } finally {
            refilling = false;
        }
    }

    private static void setSpacer(Region spacer, double height) {
        double pinned = Math.max(0, height);
        spacer.setMinHeight(pinned);
        spacer.setPrefHeight(pinned);
        spacer.setMaxHeight(pinned);
    }

    /** Which row contains this height down the list. Binary search over {@link #rowTop}. */
    private int indexAt(double y) {
        int found = java.util.Arrays.binarySearch(rowTop, 0, visible.size(), y);
        if (found >= 0) {
            return found;
        }
        return Math.max(0, Math.min(visible.size() - 1, -found - 2));
    }

    /** Takes a row's node back, unless it is busy. */
    private void recycle(Row row) {
        if (row.pinned()) {
            return;
        }
        liveById.remove(row.item.id);
        row.unbind();
        pool.add(row);
    }

    private Label emptyMessage(String text) {
        Label label = new Label(text);
        label.setFont(Font.font(Tokens.FONT_FAMILY, TouchType.listRowFont()));
        // Italic, drawn by hand; see Fonts.oblique. Asking JavaFX for FontPosture.ITALIC here
        // would silently do nothing, because the interface's typeface has no italic face.
        label.getTransforms().add(Fonts.oblique(TouchType.listRowFont()));
        label.setTextFill(Tokens.TEXT_STATUS);
        label.setPadding(new Insets(Tokens.LIST_EMPTY_PADDING_V, Tokens.LIST_HEADER_PADDING_H,
                Tokens.LIST_EMPTY_PADDING_V, Tokens.LIST_HEADER_PADDING_H));
        return label;
    }

    /**
     * Moves the highlight without touching a single row node.
     *
     * <p>See the class comment: rebuilding here would kill double-click.
     */
    public void setSelectedId(String id) {
        this.selectedId = id;
        // Only the rows that exist. A row built later reads selectedId as it is bound, so one
        // scrolled into view afterwards still comes up highlighted.
        for (Row row : liveById.values()) {
            row.restyle();
        }
    }

    public String selectedId() {
        return selectedId;
    }

    /** Scrolls a row into view, for when the room selects something the list has scrolled past. */
    public void scrollTo(String id) {
        int at = indexOf(id);
        if (at < 0) {
            return;
        }
        // Worked out from the measured tops rather than from the node's position, which is what
        // lets this reach a row that has never been built. That is not a convenience: the room
        // selects items the list has scrolled past, and most of them have no node.
        double viewport = scroller.getViewportBounds().getHeight();
        double listHeight = rowTop[visible.size()] - viewport;
        if (listHeight > 0) {
            scroller.setVvalue(Math.max(0, Math.min(1, rowTop[at] / listHeight)));
        }
        // The listener above has already refilled if the value moved. If it did not move, the
        // row is on screen already, and this costs one early return.
        refill();
    }

    /**
     * For tests: the height this list reserved for an item's row, whether or not it has a node.
     *
     * <p>Here because the two spacers are built from these numbers and nothing else can see them.
     * A measurement that disagreed with what the rows actually come out as would make the list the
     * wrong length and the scrollbar a lie, and it would do it silently.
     */
    public double measuredHeightOf(String id) {
        int at = indexOf(id);
        return at < 0 ? 0 : rowTop[at + 1] - rowTop[at];
    }

    /** Where an item sits in the list, or -1 if the search has filtered it out. */
    private int indexOf(String id) {
        return indexOfId.getOrDefault(id, -1);
    }

    /**
     * For tests: the row node for an item, or null if it has none right now.
     *
     * <p>⚠ <b>Null now means two different things</b>, and it did not before: the search has
     * filtered this item out, <em>or</em> the row is scrolled off screen and has no node. Call
     * {@link #scrollTo} first if what you want is the node.
     */
    public Region rowFor(String id) {
        Row row = liveById.get(id);
        return row == null ? null : row.node;
    }

    /**
     * For tests: how many rows the list is showing.
     *
     * <p>Rows, not nodes. There are about twenty nodes whatever the list holds, and the number
     * anybody wants from this is how many items got through the search.
     */
    public int rowCount() {
        return visible.size();
    }

    /**
     * For tests: whether a row is currently drawn as the selected one.
     *
     * <p>False for an item with no node, for the same reason {@link #rowFor} returns null.
     */
    public boolean isRowSelected(String id) {
        Row row = liveById.get(id);
        return row != null && row.node.getStyle().contains(Tokens.hex(Tokens.LIST_ROW_SELECTED));
    }

    // --------------------------------------------------------------- one row

    /** A single line in the list, and everything it responds to. */
    private final class Row {

        /**
         * The item this row is currently showing, or null while it sits in the pool.
         *
         * <p><b>⚠ Not final any more, and that is the whole of the risk in this class.</b> A row
         * node used to mean one item for its whole life. Now it is pointed at whichever item has
         * scrolled into its place, so <b>every handler below reads this field when the event
         * arrives</b> rather than closing over an item at wiring time. A handler that captured its
         * item would go on acting for a row that is no longer on screen, which is the same failure
         * as the rebuild-on-selection bug in this class's documentation, arriving by a new door.
         */
        private Item item;

        private final HBox node = new HBox(Tokens.LIST_ROW_GAP);
        private final Circle dot = new Circle(Tokens.LIST_DOT_RADIUS);
        private final Label label = new Label();

        private boolean hovered;

        /** Set while this row is collapsing or restoring, so its size is not fought over. */
        private Timeline collapse;

        // ---- the gesture, and why it is a field rather than a local ----------------------
        //
        // These four used to live in arrays captured by the handlers, which was fine when a node
        // meant one item forever. They are fields now so that unbind() can refuse to run while
        // one is in progress; see pinned.
        private final double[] pressedAt = new double[2];
        private boolean dragging;
        private boolean decided;

        /** True while the mouse is down on this row, so a stray release still finds its start. */
        private boolean pressed;

        /**
         * Whether this row is busy and must not be pointed at another item.
         *
         * <p><b>This is what makes recycling safe.</b> Three things make a row busy:
         *
         * <ul>
         *   <li><b>A gesture is running on it.</b> The planned drag spans a press, many drags and
         *       a release, and a drag scrolls the list, so without this the node under the finger
         *       could become a different item half way through and drop the wrong one.
         *   <li><b>It is the row last pressed.</b> A double-click is two separate click events on
         *       the <em>same node</em>, and anything that scrolled between them would otherwise be
         *       free to recycle it: the second click would land on a fresh node reporting a click
         *       count of one, and double-click-to-edit would silently stop working. That is the
         *       exact bug this class's documentation is about.
         *   <li><b>Its collapse animation is running</b>, which is mid-flight height and clip
         *       state that belongs to one particular row.
         * </ul>
         */
        private boolean pinned() {
            return pressed || dragging || collapse != null || this == lastPressed;
        }

        Row() {
            label.setFont(Font.font(Tokens.FONT_FAMILY, TouchType.listRowFont()));

            // A name too long for the panel wraps onto a second line and makes the row taller,
            // rather than being cut short with an ellipsis. That is what the original does (its
            // rows are flex containers with nothing stopping the text wrapping), and it is
            // the better behavior anyway: names run to 200 characters, and "Blue Bin, kitchen
            // shelf, top…" tells you nothing that the truncated version did not. JavaFX
            // truncates unless asked otherwise, so it has to be asked.
            label.setWrapText(true);
            label.setMinWidth(0);
            HBox.setHgrow(label, Priority.ALWAYS);

            node.setAlignment(Pos.CENTER_LEFT);
            node.setPadding(new Insets(TouchType.listRowPaddingV(), TouchType.listRowPaddingH(),
                    TouchType.listRowPaddingV(), TouchType.listRowPaddingH()));
            node.getChildren().addAll(dot, label);
            node.setCursor(javafx.scene.Cursor.HAND);

            node.setOnMouseEntered(event -> {
                hovered = true;
                restyle();
            });
            node.setOnMouseExited(event -> {
                hovered = false;
                restyle();
            });

            // Both sets of handlers, on every row, every time. They used to be wired one way or
            // the other depending on whether the item was planned, which a recycled node cannot
            // do: the same node shows a planned item now and an ordinary one after a scroll. Each
            // one checks what it is showing when it runs instead. The ordinary case still installs
            // no behavior at all on press, drag and release, because those return immediately and
            // consume nothing, which is what TouchScrollingTest is about.
            wirePlannedDrag();
            wireClickAndDoubleClick();
        }

        /** Points this row at an item and makes it look like that item's row. */
        void bind(Item showing) {
            this.item = showing;
            this.hovered = false;
            this.dragging = false;
            this.decided = false;
            this.pressed = false;

            dot.setFill(Tokens.parseHsl(showing.color));
            label.setText(showing.displayName() + (showing.planned ? " [plan]" : ""));
            // A planned row is italic in the original. The interface's typeface has no italic
            // face, and JavaFX (unlike a browser) will not fake one, so the slant is drawn here
            // instead. See Fonts.oblique. Cleared first: this node may have been showing an
            // ordinary item a moment ago, or a planned one.
            label.getTransforms().clear();
            if (showing.planned) {
                label.getTransforms().add(Fonts.oblique(TouchType.listRowFont()));
            }
            node.setOpacity(showing.planned ? Tokens.PLANNED_ROW_OPACITY : 1);

            // Whatever the collapse animation left behind. A row that was lifted out and then put
            // back is handed to the pool with its heights pinned and a clip on it.
            node.setMinHeight(Region.USE_COMPUTED_SIZE);
            node.setPrefHeight(Region.USE_COMPUTED_SIZE);
            node.setMaxHeight(Region.USE_COMPUTED_SIZE);
            node.setClip(null);

            restyle();
        }

        /** Lets go of the item, so nothing can act on a row that is no longer on screen. */
        void unbind() {
            item = null;
            hovered = false;
        }

        /**
         * How tall a row for this item would be, without building one.
         *
         * <p>Binds the ruler to the item and asks the node, so the number comes from the same
         * layout the real rows go through rather than from a formula that would have to be kept
         * in step with them by hand.
         */
        double heightFor(Item measuring, double width) {
            bind(measuring);
            node.applyCss();
            // Snapped the way the layout above will snap it. Without this a wrapping row measures
            // 78 px for a spacer and renders at 79, and the difference accumulates down the list.
            return snapSizeY(node.prefHeight(width));
        }

        /** Whether this row can measure text yet, which needs a skin, which needs a Scene. */
        boolean canMeasure() {
            return label.getScene() != null && label.prefWidth(-1) > 0;
        }

        /**
         * Repaints the row's background and text color for its current state.
         *
         * <p>The order matters and mirrors the original's stylesheet, where the later rule wins
         * a tie: a hovered <em>planned</em> row is the most specific case and beats selection,
         * while an ordinary selected row keeps its blue even under the pointer.
         */
        void restyle() {
            if (item == null) {
                return;
            }
            boolean selected = item.id.equals(selectedId);

            String background;
            if (item.planned && hovered) {
                background = Tokens.hex(Tokens.LIST_ROW_HOVER_PLANNED);
            } else if (selected) {
                background = Tokens.hex(Tokens.LIST_ROW_SELECTED);
            } else if (hovered) {
                background = Tokens.hex(Tokens.LIST_ROW_HOVER);
            } else {
                background = "transparent";
            }

            node.setStyle("-fx-background-color: " + background + ";"
                    + "-fx-border-color: transparent transparent "
                    + Tokens.hex(Tokens.LIST_ROW_BORDER) + " transparent;"
                    + "-fx-border-width: 0 0 1 0;");

            // ⚠ AN INLINE STYLE, NOT setTextFill, AND IT IS A BUG FIX RATHER THAN A PREFERENCE.
            //
            // Every row in this list rendered PURE WHITE from the day it was written, where the
            // original gives white to the selected row alone and #ccc to the rest. Three things
            // agree on that: SPEC-DESIGN-SYSTEM.md ("#ccc primary text" and "#fff selected list
            // row text"), the original's own stylesheet (`.list-entry.selected { color: #fff }`
            // and nothing else), and the brightest pixel anywhere in the list panel of the
            // original's reference screenshot, which is 204.
            //
            // The cause is the one AddItemDialog.titleRow already found and wrote down. The line
            // above sets -fx-background-color inline; JavaFX's default stylesheet then says a
            // label's color is -fx-text-background-color, a ladder() computed FROM that
            // background, so the value it produces carries the INLINE origin of the style it came
            // from. Inline beats a value set from code, so setTextFill was discarded.
            //
            // ⚠ It was discarded only on the FIRST css pass after a node joins the scene, which is
            // why this survived: RowFillTimingProbe reads #ccc straight after rebuild, white one
            // round of events later, and #ccc again after any later restyle. Every row was styled
            // in its constructor, before it was added, so every row lost the color. A row
            // restyled afterwards, by a hover or a selection, quietly got it back.
            label.setStyle("-fx-text-fill: "
                    + (selected ? Tokens.hex(Color.WHITE) : Tokens.hex(Tokens.TEXT_PRIMARY)) + ";");
        }

        private void wireClickAndDoubleClick() {
            node.setOnMouseClicked(event -> {
                Item acting = item;
                if (acting == null || event.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                // JavaFX reports the second click of a double-click as click 1 and then
                // click 2, so a single click still fires first -- which is exactly what the
                // original does too: select, then edit.
                if (event.getClickCount() >= 2) {
                    onEdit.handle(acting);
                } else if (!acting.planned) {
                    // A planned row has never selected on a single click: it is not in the room,
                    // so there is nothing to select. Only the double-click reaches its dialog.
                    onSelect.handle(acting);
                }
            });
        }

        // ------------------------------------------------- dragging a ghost out

        /**
         * Lets a planned item be dragged out of the list and dropped into the room.
         *
         * <p>The press only becomes a drag once the pointer has moved 6 px <b>and</b> moved
         * more sideways than up or down. That second half is what keeps the list scrollable:
         * a mostly-vertical drag is someone trying to scroll, not trying to lift a row out.
         * Whichever way it went, the decision <b>sticks</b> for the rest of the gesture: a
         * hand that starts sideways and curls upward is still lifting the row out.
         *
         * <h2>Why almost nothing here is consumed</h2>
         *
         * <p>On a phone this row sits inside a {@link ScrollPane} that is scrolled by
         * <em>mouse</em> events: Android manufactures a mouse press and drag from every finger,
         * and JavaFX's own {@code ScrollPaneSkin} pans its content from those. Consuming an
         * event here stops it reaching that skin, because a consumed event never travels on to
         * its ancestors. So this row consumes exactly one thing (a drag it has decided to
         * handle itself) and lets everything else past. Consuming the press, or consuming the
         * "scroll" half of the decision, leaves the list unscrollable by finger while looking
         * perfectly correct with a mouse, which is why it survived this long.
         */
        private void wirePlannedDrag() {
            node.setOnMousePressed(event -> {
                if (item == null || event.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                // Every row remembers its press, planned or not, because that is what stops the
                // node being recycled between the two halves of a double-click.
                lastPressed = this;
                pressed = true;
                pressedAt[0] = event.getSceneX();
                pressedAt[1] = event.getSceneY();
                dragging = false;
                decided = false;
                // Deliberately NOT consumed: the ScrollPane above has to see this press to know
                // where a finger-pan started from.
            });

            node.setOnMouseDragged(event -> {
                Item carrying = item;
                if (carrying == null || !carrying.planned) {
                    return;
                }
                if (!decided) {
                    double dx = event.getSceneX() - pressedAt[0];
                    double dy = event.getSceneY() - pressedAt[1];
                    if (Math.abs(dx) > Tokens.DRAG_THRESHOLD_ROW_PX
                            || Math.abs(dy) > Tokens.DRAG_THRESHOLD_ROW_PX) {
                        decided = true;
                        dragging = Math.abs(dx) > Math.abs(dy);
                        if (dragging) {
                            liftGhost(pressedAt[0], pressedAt[1]);
                        }
                    }
                }
                if (dragging) {
                    ghost.moveTo(event.getSceneX(), event.getSceneY(),
                            overRoom.test(event.getSceneX(), event.getSceneY()));
                    // Only the drag half. The scroll half must reach the ScrollPane, which is
                    // the whole point of the direction lock.
                    event.consume();
                }
            });

            node.setOnMouseReleased(event -> {
                pressed = false;
                Item carrying = item;
                if (carrying == null || !carrying.planned || !dragging) {
                    decided = false;
                    // Not consumed, for the same reason as the press: this release may be the
                    // end of a finger-pan that the ScrollPane is still following.
                    return;
                }
                dragging = false;
                decided = false;
                ghost.drop();

                if (overRoom.test(event.getSceneX(), event.getSceneY())) {
                    // The panel rebuilds from underneath this row as a result, which is what
                    // clears away the collapsed remains of it.
                    onPlannedDropped.dropped(carrying, event.getSceneX(), event.getSceneY());
                } else {
                    // Dropped somewhere that is not the room: put the row back.
                    animateCollapse(false);
                }
                event.consume();
            });
        }

        private void liftGhost(double pointerX, double pointerY) {
            if (item == null) {
                return;
            }
            Bounds inScene = node.localToScene(node.getBoundsInLocal());
            ghost.lift(item.displayName() + " [plan]", Tokens.parseHsl(item.color),
                    node.getWidth(), inScene.getMinX(), inScene.getMinY(), pointerX, pointerY);
            animateCollapse(true);
        }

        /**
         * Closes the gap the lifted row leaves behind, or reopens it if the drop was canceled.
         *
         * <p>Without it the list keeps a row-shaped hole while the ghost is being carried
         * around, which reads as the item being in two places at once.
         *
         * <p>Everything animates off one value rather than three separate timelines, because
         * the row's height, padding and border all have to reach zero together: a padding
         * that finished early would make the text jump before the row closed.
         */
        private void animateCollapse(boolean closing) {
            if (item == null) {
                return;
            }
            if (collapse != null) {
                collapse.stop();
            }
            double naturalHeight = node.getHeight() > 0
                    ? node.getHeight() : node.prefHeight(node.getWidth());

            // Clipped, so the contents disappear as the row shrinks instead of spilling over
            // the row below it.
            Rectangle clip = new Rectangle(node.getWidth(), naturalHeight);
            node.setClip(clip);

            SimpleDoubleProperty progress = new SimpleDoubleProperty(closing ? 0 : 1);
            progress.addListener((observable, before, after) -> {
                double open = 1 - after.doubleValue();
                node.setMinHeight(naturalHeight * open);
                node.setPrefHeight(naturalHeight * open);
                node.setMaxHeight(naturalHeight * open);
                node.setPadding(new Insets(TouchType.listRowPaddingV() * open,
                        TouchType.listRowPaddingH(), TouchType.listRowPaddingV() * open,
                        TouchType.listRowPaddingH()));
                node.setOpacity((item != null && item.planned
                        ? Tokens.PLANNED_ROW_OPACITY : 1) * open);
                clip.setHeight(naturalHeight * open);
                clip.setWidth(node.getWidth());
            });

            collapse = new Timeline(new KeyFrame(Duration.millis(Tokens.ROW_COLLAPSE_MS),
                    new KeyValue(progress, closing ? 1 : 0, Interpolator.EASE_BOTH)));
            collapse.setOnFinished(event -> {
                // Cleared first: while this is set the row counts as busy and cannot be recycled,
                // and a row that never let go of it would be pinned for the rest of the session.
                collapse = null;
                if (!closing) {
                    // Hand the row's height back to the layout, or it would stay pinned at
                    // whatever the last animation frame happened to compute.
                    node.setMinHeight(Region.USE_COMPUTED_SIZE);
                    node.setPrefHeight(Region.USE_COMPUTED_SIZE);
                    node.setMaxHeight(Region.USE_COMPUTED_SIZE);
                    node.setClip(null);
                }
            });
            collapse.play();
        }
    }
}
