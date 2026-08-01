package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.engine.Search;
import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
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
 * <p>Three jobs. It is <b>an index</b> — every box, in name order, so something buried under a
 * pile is still findable. It is <b>a search</b> — by name, or by size with a word like
 * {@code w20}. And it is <b>where planned items live</b>, because a planned item is deliberately
 * not drawn in the room at all, so this list is the only place it exists until it is dragged
 * out and dropped in.
 *
 * <p><b>The rule that gets broken by accident.</b> Clicking a row must not rebuild the list. It
 * is tempting — redrawing everything is the simple way to move a highlight — and it destroys
 * the row you just clicked, replacing it with a new one between the two halves of a
 * double-click. The double-click then never registers, and "double-click a row to edit it"
 * silently stops working. That was a real bug in the original; the fix is
 * {@link #setSelectedId}, which recolours the rows that are already there.
 */
public final class ItemListPanel extends VBox {

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

    /** How far the pointer moves before a press on a row counts as dragging it out. */
    private static final double DRAG_THRESHOLD_PX = 6;

    private AppState state;
    private String selectedId;

    private final Button exportButton = iconButton("⤓", Tokens.LIST_EXPORT_BUTTON_SIZE);
    private final TextField searchField = new TextField();
    private final Button clearSearchButton = clearButton();

    /** One row per visible item, in a scroller so a long list does not stretch the window. */
    private final VBox rowBox = new VBox();
    private final ScrollPane scroller = new ScrollPane(rowBox);

    private final Map<String, Row> rowsById = new HashMap<>();

    private final DragGhost ghost;

    private ItemHandler onSelect = item -> { };
    private ItemHandler onEdit = item -> { };
    private Runnable onExport = () -> { };
    private ScenePointTest overRoom = (x, y) -> false;
    private DropHandler onPlannedDropped = (item, x, y) -> { };

    public ItemListPanel(AppState state, DragGhost ghost) {
        this.state = state;
        this.ghost = ghost;

        setPrefWidth(Tokens.LIST_PANEL_WIDTH);
        setMinWidth(Tokens.LIST_PANEL_WIDTH);
        setMaxWidth(Tokens.LIST_PANEL_WIDTH);
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

        getChildren().addAll(header(), searchRow(), scroller);

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
        heading.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_LIST_HEADER));
        heading.setTextFill(Tokens.TEXT_SECTION_HEADER);

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        HBox header = new HBox(Tokens.LIST_ROW_GAP, heading, gap, exportButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(Tokens.LIST_HEADER_PADDING_V, Tokens.LIST_HEADER_PADDING_H,
                Tokens.LIST_HEADER_PADDING_V, Tokens.LIST_HEADER_PADDING_H));
        header.setStyle("-fx-border-color: transparent transparent "
                + Tokens.hex(Tokens.BORDER) + " transparent;"
                + "-fx-border-width: 0 0 1 0;");
        return header;
    }

    private HBox searchRow() {
        searchField.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_SEARCH));
        searchField.setPromptText("Search: name, W20, L12, H8...");
        searchField.setStyle("-fx-background-color: " + Tokens.hex(Tokens.SEARCH_BG) + ";"
                + "-fx-text-fill: " + Tokens.hex(Tokens.TEXT_INPUT) + ";"
                + "-fx-prompt-text-fill: " + Tokens.hex(Tokens.placeholderOver(Tokens.SEARCH_BG)) + ";"
                + "-fx-border-color: " + Tokens.hex(Tokens.BORDER) + ";"
                + "-fx-border-width: 1;"
                + "-fx-background-radius: 0; -fx-border-radius: 0;"
                + "-fx-padding: " + Tokens.DIALOG_INPUT_PADDING_V + " "
                + Tokens.DIALOG_INPUT_PADDING_H + " " + Tokens.DIALOG_INPUT_PADDING_V + " "
                + Tokens.DIALOG_INPUT_PADDING_H + ";");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.setMinWidth(0);

        HBox row = new HBox(Tokens.LIST_SEARCH_GAP, searchField, clearSearchButton);
        row.setAlignment(Pos.CENTER_LEFT);
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
     * interface's own typeface has no glyph for. The clear button's {@code ×} is <b>not</b> —
     * that one is ordinary punctuation, so {@link #clearButton()} builds its own button and stays
     * on the text face. See {@link Fonts#SYMBOL_FAMILY}.
     *
     * <p>The glyph goes in as a <em>graphic</em> rather than as the button's text, so that it is
     * centred on its own ink instead of on the maths face's very tall line box — see
     * {@link Fonts#symbolGlyph}, which explains what that fixes.
     */
    private static Button iconButton(String glyph, double size) {
        Button button = new Button();
        button.setGraphic(Fonts.symbolGlyph(glyph, Tokens.FONT_CONTROL, button));
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
        button.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_CONTROL));
        button.setPadding(new Insets(2, 7, 2, 7));
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
     * <p>Call this when the <em>contents</em> of the list change — an item added, deleted,
     * renamed, or the search narrowed. <b>Not</b> when only the selection moves; see
     * {@link #setSelectedId}.
     */
    public void rebuild() {
        rowBox.getChildren().clear();
        rowsById.clear();

        List<Item> visible = Search.visibleItems(state, searchField.getText());
        if (visible.isEmpty()) {
            // Two different empty states, because "nothing here" and "nothing matched" call
            // for two different next actions.
            rowBox.getChildren().add(emptyMessage(state.items.isEmpty()
                    ? "No items yet." : "No items match search."));
            return;
        }

        for (Item item : visible) {
            Row row = new Row(item);
            rowsById.put(item.id, row);
            rowBox.getChildren().add(row.node);
        }
    }

    private Label emptyMessage(String text) {
        Label label = new Label(text);
        label.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_LIST_ROW));
        // Italic, drawn by hand — see Fonts.oblique. Asking JavaFX for FontPosture.ITALIC here
        // would silently do nothing, because the interface's typeface has no italic face.
        label.getTransforms().add(Fonts.oblique(Tokens.FONT_LIST_ROW));
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
        for (Row row : rowsById.values()) {
            row.restyle();
        }
    }

    public String selectedId() {
        return selectedId;
    }

    /** Scrolls a row into view, for when the room selects something the list has scrolled past. */
    public void scrollTo(String id) {
        Row row = rowsById.get(id);
        if (row == null) {
            return;
        }
        double rowTop = row.node.getBoundsInParent().getMinY();
        double listHeight = rowBox.getHeight() - scroller.getViewportBounds().getHeight();
        if (listHeight > 0) {
            scroller.setVvalue(Math.max(0, Math.min(1, rowTop / listHeight)));
        }
    }

    /** For tests: the row node for an item, or null if it is filtered out. */
    public Region rowFor(String id) {
        Row row = rowsById.get(id);
        return row == null ? null : row.node;
    }

    /** For tests: how many rows are showing. */
    public int rowCount() {
        return rowsById.size();
    }

    /** For tests: whether a row is currently drawn as the selected one. */
    public boolean isRowSelected(String id) {
        Row row = rowsById.get(id);
        return row != null && row.node.getStyle().contains(Tokens.hex(Tokens.LIST_ROW_SELECTED));
    }

    // --------------------------------------------------------------- one row

    /** A single line in the list, and everything it responds to. */
    private final class Row {

        private final Item item;
        private final HBox node = new HBox(Tokens.LIST_ROW_GAP);
        private final Circle dot = new Circle(Tokens.LIST_DOT_RADIUS);
        private final Label label = new Label();

        private boolean hovered;

        /** Set while this row is collapsing or restoring, so its size is not fought over. */
        private Timeline collapse;

        Row(Item item) {
            this.item = item;

            dot.setFill(Tokens.parseHsl(item.color));
            label.setText(item.displayName() + (item.planned ? " [plan]" : ""));
            label.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_LIST_ROW));
            if (item.planned) {
                // A planned row is italic in the original. The interface's typeface has no italic
                // face, and JavaFX — unlike a browser — will not fake one, so the slant is drawn
                // here instead. See Fonts.oblique.
                label.getTransforms().add(Fonts.oblique(Tokens.FONT_LIST_ROW));
            }

            // A name too long for the panel wraps onto a second line and makes the row taller,
            // rather than being cut short with an ellipsis. That is what the original does —
            // its rows are flex containers with nothing stopping the text wrapping — and it is
            // the better behaviour anyway: names run to 200 characters, and "Blue Bin — kitchen
            // shelf, top…" tells you nothing that the truncated version did not. JavaFX
            // truncates unless asked otherwise, so it has to be asked.
            label.setWrapText(true);
            label.setMinWidth(0);
            HBox.setHgrow(label, Priority.ALWAYS);

            node.setAlignment(Pos.CENTER_LEFT);
            node.setPadding(new Insets(Tokens.LIST_ROW_PADDING_V, Tokens.LIST_ROW_PADDING_H,
                    Tokens.LIST_ROW_PADDING_V, Tokens.LIST_ROW_PADDING_H));
            node.getChildren().addAll(dot, label);
            node.setOpacity(item.planned ? Tokens.PLANNED_ROW_OPACITY : 1);
            node.setCursor(javafx.scene.Cursor.HAND);

            node.setOnMouseEntered(event -> {
                hovered = true;
                restyle();
            });
            node.setOnMouseExited(event -> {
                hovered = false;
                restyle();
            });

            if (item.planned) {
                wirePlannedDrag();
            } else {
                wireClickAndDoubleClick();
            }
            restyle();
        }

        /**
         * Repaints the row's background and text colour for its current state.
         *
         * <p>The order matters and mirrors the original's stylesheet, where the later rule wins
         * a tie: a hovered <em>planned</em> row is the most specific case and beats selection,
         * while an ordinary selected row keeps its blue even under the pointer.
         */
        void restyle() {
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
            label.setTextFill(selected ? Color.WHITE : Tokens.TEXT_PRIMARY);
        }

        private void wireClickAndDoubleClick() {
            node.setOnMouseClicked(event -> {
                if (event.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                // JavaFX reports the second click of a double-click as click 1 and then
                // click 2, so a single click still fires first -- which is exactly what the
                // original does too: select, then edit.
                if (event.getClickCount() >= 2) {
                    onEdit.handle(item);
                } else {
                    onSelect.handle(item);
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
         */
        private void wirePlannedDrag() {
            double[] start = new double[2];
            boolean[] dragging = {false};
            boolean[] decided = {false};

            node.setOnMousePressed(event -> {
                if (event.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                start[0] = event.getSceneX();
                start[1] = event.getSceneY();
                dragging[0] = false;
                decided[0] = false;
                event.consume();
            });

            node.setOnMouseDragged(event -> {
                if (!decided[0]) {
                    double dx = event.getSceneX() - start[0];
                    double dy = event.getSceneY() - start[1];
                    if (Math.abs(dx) > DRAG_THRESHOLD_PX || Math.abs(dy) > DRAG_THRESHOLD_PX) {
                        decided[0] = true;
                        dragging[0] = Math.abs(dx) > Math.abs(dy);
                        if (dragging[0]) {
                            liftGhost(start[0], start[1]);
                        }
                    }
                }
                if (dragging[0]) {
                    ghost.moveTo(event.getSceneX(), event.getSceneY(),
                            overRoom.test(event.getSceneX(), event.getSceneY()));
                }
                event.consume();
            });

            node.setOnMouseReleased(event -> {
                if (!dragging[0]) {
                    decided[0] = false;
                    return;
                }
                dragging[0] = false;
                decided[0] = false;
                ghost.drop();

                if (overRoom.test(event.getSceneX(), event.getSceneY())) {
                    // The panel rebuilds from underneath this row as a result, which is what
                    // clears away the collapsed remains of it.
                    onPlannedDropped.dropped(item, event.getSceneX(), event.getSceneY());
                } else {
                    // Dropped somewhere that is not the room: put the row back.
                    animateCollapse(false);
                }
                event.consume();
            });

            node.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() >= 2) {
                    onEdit.handle(item);
                }
            });
        }

        private void liftGhost(double pointerX, double pointerY) {
            Bounds inScene = node.localToScene(node.getBoundsInLocal());
            ghost.lift(item.displayName() + " [plan]", Tokens.parseHsl(item.color),
                    node.getWidth(), inScene.getMinX(), inScene.getMinY(), pointerX, pointerY);
            animateCollapse(true);
        }

        /**
         * Closes the gap the lifted row leaves behind, or reopens it if the drop was cancelled.
         *
         * <p>Without it the list keeps a row-shaped hole while the ghost is being carried
         * around, which reads as the item being in two places at once.
         *
         * <p>Everything animates off one value rather than three separate timelines, because
         * the row's height, padding and border all have to reach zero together — a padding
         * that finished early would make the text jump before the row closed.
         */
        private void animateCollapse(boolean closing) {
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
                node.setPadding(new Insets(Tokens.LIST_ROW_PADDING_V * open,
                        Tokens.LIST_ROW_PADDING_H, Tokens.LIST_ROW_PADDING_V * open,
                        Tokens.LIST_ROW_PADDING_H));
                node.setOpacity((item.planned ? Tokens.PLANNED_ROW_OPACITY : 1) * open);
                clip.setHeight(naturalHeight * open);
                clip.setWidth(node.getWidth());
            });

            collapse = new Timeline(new KeyFrame(Duration.millis(Tokens.ROW_COLLAPSE_MS),
                    new KeyValue(progress, closing ? 1 : 0, Interpolator.EASE_BOTH)));
            collapse.setOnFinished(event -> {
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
