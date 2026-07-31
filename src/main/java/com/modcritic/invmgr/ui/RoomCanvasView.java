package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.engine.Collision;
import com.modcritic.invmgr.engine.Layers;
import com.modcritic.invmgr.engine.Stacking;
import com.modcritic.invmgr.engine.UndoHistory;
import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.UndoEntry;
import com.modcritic.invmgr.model.Units;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeType;
import javafx.scene.transform.Scale;
import javafx.util.Duration;

/**
 * The room seen from above: floor, grid, and the boxes in it.
 *
 * <p>This is the app's primary interface — the 2D view comes first and matters most, which is
 * why it is built before the 3D one.
 *
 * <p><b>How it's drawn.</b> The floor, its grid and its vignette are painted onto a single
 * JavaFX {@code Canvas} — they only change when the room's size changes. Each box, by
 * contrast, is its own {@code Rectangle} node. That mirrors the original app, where every item
 * was its own element, and it buys three things cheaply: front-to-back ordering by list order,
 * per-item opacity for the dim effect, and hit detection for dragging without any manual
 * coordinate maths.
 *
 * <p><b>Coordinates.</b> Everything inside the room is in room pixels, where 8 pixels is one
 * inch and 96 is one foot. Item positions are stored that way, so the view never converts
 * them — except when Fit mode scales the whole room, which is handled in exactly one place
 * (see {@link #setFitMode}).
 */
public final class RoomCanvasView extends ScrollPane {

    /** Told which item the pointer is over, and where in the window it is. */
    @FunctionalInterface
    public interface HoverHandler {
        void hover(Item item, double sceneX, double sceneY);
    }

    /** Floor, grid and vignette. Repainted only when the room's dimensions change. */
    private final Canvas floor = new Canvas();

    /** One {@link Rectangle} per item, plus selection outlines. */
    private final Pane itemLayer = new Pane();

    /** Floor and items together — this is the thing Fit mode scales. */
    private final Pane room = new Pane(floor, itemLayer);

    /** Positions the room within the scrollable area, and supplies its margin. */
    private final StackPane holder = new StackPane(new Group(room));

    /** Applied to {@link #room}, pivoting on its top-left corner as the original does. */
    private final Scale fitScale = new Scale(1, 1, 0, 0);

    private final Map<String, Rectangle> itemRects = new LinkedHashMap<>();
    private final Map<String, Rectangle> selectionOutlines = new HashMap<>();

    private AppState state;
    private String selectedId;
    private boolean fitMode;

    /** The interface zoom this view cancels out, so the room stays to scale. See {@link #setUiScale}. */
    private double uiScale = 1;

    /**
     * Where drags and deletions record themselves so they can be undone.
     *
     * <p>Held here rather than in {@link AppState} because undo history is never saved.
     */
    private UndoHistory undoHistory = new UndoHistory();

    /** Called with a message after an action, for the status bar. */
    private java.util.function.Consumer<String> onStatus = message -> { };

    /** What to do when an item is clicked without being dragged: open the Edit dialog. */
    private Consumer<Item> onItemActivated = item -> { };

    /** Called as the pointer moves over a visible item, so the tooltip can follow it. */
    private HoverHandler onItemHover = (item, sceneX, sceneY) -> { };

    /** Called when the pointer leaves an item, or an item is deleted from under it. */
    private Runnable onHoverEnded = () -> { };

    /** Called after a drag finishes, so the layer slider can be rebuilt. */
    private Runnable onDragCommitted = () -> { };

    public RoomCanvasView(AppState state) {
        this.state = state;

        room.getTransforms().add(fitScale);
        itemLayer.setPickOnBounds(false);   // clicks pass through empty space to the floor

        holder.setAlignment(Pos.TOP_LEFT);
        holder.setPadding(new Insets(Tokens.CANVAS_MARGIN));

        setContent(holder);
        setPannable(false);
        // The viewport and the ScrollPane itself both need the darker background, or a paler
        // default grey shows through around the room.
        String background = "-fx-background: " + Tokens.hex(Tokens.CANVAS_WRAP_BG) + ";"
                + "-fx-background-color: " + Tokens.hex(Tokens.CANVAS_WRAP_BG) + ";";
        setStyle(background);
        getStylesheets().add(
                RoomCanvasView.class.getResource("/com/modcritic/invmgr/ui/canvas.css")
                        .toExternalForm());
        holder.setStyle("-fx-background-color: " + Tokens.hex(Tokens.CANVAS_WRAP_BG) + ";");

        // Clicking bare floor clears the selection, matching the original.
        holder.setOnMousePressed(event -> {
            if (event.getTarget() == holder || event.getTarget() == floor) {
                setSelectedId(null);
            }
        });

        rebuildRoom();
        rebuildItems();
    }

    // ------------------------------------------------------------------ state

    /** Replaces the whole room — used after loading a file. */
    public void setState(AppState state) {
        this.state = state;
        this.selectedId = null;
        rebuildRoom();
        rebuildItems();
    }

    public AppState state() {
        return state;
    }

    public String selectedId() {
        return selectedId;
    }

    /**
     * The selection highlight drawn around an item, or {@code null} if that item has no node.
     *
     * <p>Exposed so a test can check the highlight is where the item is <em>during</em> a drag,
     * not only once the drag has been committed.
     */
    public Rectangle selectionOutline(String itemId) {
        return selectionOutlines.get(itemId);
    }

    /** An item's own rectangle, or {@code null} for a planned item, which has no node. */
    public Rectangle itemRect(String itemId) {
        return itemRects.get(itemId);
    }

    /**
     * The item layer's children, in the order JavaFX will draw them — later paints on top.
     *
     * <p>Exposed so a test can assert the real draw order rather than trusting that the sort
     * comparator was wired up: the ordering rule being right and the scene graph actually being
     * reordered are two separate things, and only the second one is what the user sees.
     */
    public java.util.List<javafx.scene.Node> itemLayerChildren() {
        return java.util.List.copyOf(itemLayer.getChildren());
    }

    public void setSelectedId(String id) {
        this.selectedId = id;
        refreshItemAppearance();
    }

    public void setOnItemActivated(Consumer<Item> handler) {
        this.onItemActivated = handler == null ? item -> { } : handler;
    }

    public void setOnDragCommitted(Runnable handler) {
        this.onDragCommitted = handler == null ? () -> { } : handler;
    }

    public void setOnItemHover(HoverHandler handler) {
        this.onItemHover = handler == null ? (item, x, y) -> { } : handler;
    }

    public void setOnHoverEnded(Runnable handler) {
        this.onHoverEnded = handler == null ? () -> { } : handler;
    }

    public void setOnStatus(java.util.function.Consumer<String> handler) {
        this.onStatus = handler == null ? message -> { } : handler;
    }

    /** Shares the app's undo history, so drags and deletions here can be undone from the top bar. */
    public void setUndoHistory(UndoHistory history) {
        this.undoHistory = history == null ? new UndoHistory() : history;
    }

    public UndoHistory undoHistory() {
        return undoHistory;
    }

    /**
     * Removes an item, recording it so the deletion can be undone.
     *
     * <p>No confirmation, matching the original: deleting a box is a right-click away and undo is
     * a button away, so asking every time would be friction for no safety.
     */
    public void deleteItem(String id) {
        Item item = findItem(id);
        if (item == null) {
            return;
        }
        // Snapshot before removing: the heights being remembered are the ones that existed while
        // the item was still holding things up.
        undoHistory.push(new UndoEntry.Deleted(UndoHistory.copyOf(item),
                UndoHistory.snapshotHeights(state)));

        String name = item.displayName();
        state.items.remove(item);
        if (id.equals(selectedId)) {
            selectedId = null;
        }
        Stacking.recomputeAllBaseHeights(state);
        rebuildItems();
        onDragCommitted.run();               // the tallest point may have changed
        onStatus.accept("Deleted " + name);
    }

    /**
     * Undoes the last action and redraws. Returns the message to show.
     *
     * <p>Must be called on the JavaFX thread, because it rebuilds the item nodes. The app calls it
     * from the Undo button, which already is; anything else has to arrange that itself.
     */
    public String undo() {
        String message = undoHistory.undo(state);
        rebuildItems();
        onDragCommitted.run();
        return message;
    }

    /**
     * Turns Fit mode on or off.
     *
     * <p>Fit scales the room down so all of it is visible at once, instead of scrolling around
     * a room bigger than the window. <b>It never scales up past 1:1</b> — a small room stays
     * its natural size rather than being blown up.
     *
     * <p>Scaling the whole room rather than recomputing item positions is deliberate: item
     * coordinates stay in room pixels, so nothing else in the app has to know about zoom. The
     * one consequence is that pointer movement has to be divided by the scale while dragging,
     * which {@link #beginDrag} does.
     */
    public void setFitMode(boolean fitMode) {
        this.fitMode = fitMode;
        applyFit();
    }

    public boolean isFitMode() {
        return fitMode;
    }

    /**
     * How many screen pixels one room pixel occupies: 1 unless Fit mode is shrinking the room.
     *
     * <p>Everything that converts a pointer position into a room position divides by this, so it
     * has to be the <em>whole</em> chain from the scene down to the room, not just this view's own
     * transform. The interface is drawn through the Ctrl+scroll zoom, and the room's own transform
     * cancels that zoom out again, so the two multiply back together here.
     *
     * <p>Note what that means: outside Fit mode the room's transform is exactly {@code 1/uiScale},
     * so this returns <b>1</b> at every zoom level — the room is to scale, so a screen pixel is a
     * room pixel. That is why the zoom needed no changes anywhere in the drag or hit-testing code.
     */
    public double fitScaleFactor() {
        return fitScale.getX() * uiScale;
    }

    /**
     * Sets the interface zoom this view has to compensate for.
     *
     * <p>Called by {@link com.modcritic.invmgr.App} when Ctrl+scroll changes it. The view does not
     * scale itself — it un-scales, so the room comes out the same size on screen whatever the
     * chrome is doing around it.
     */
    public void setUiScale(double uiScale) {
        this.uiScale = uiScale;
        applyFit();
    }

    /** Re-reads the layer slider position and updates which items are showing. */
    public void refreshVisibility() {
        refreshItemAppearance();
    }

    /**
     * Where the room's north-west corner sits on screen.
     *
     * <p>Needed by anything that has to convert between the window and the room: the appearance
     * tests sample pixels this way, and M3's drag-an-item-out-of-the-list needs it to work out
     * where in the room a drop landed.
     */
    public javafx.geometry.Point2D roomOriginInScene() {
        return room.localToScene(0, 0);
    }

    /**
     * Converts a point in the window into a position in the room, undoing Fit mode's scaling.
     *
     * <p>The one place that conversion belongs — everywhere else works in room pixels.
     */
    public javafx.geometry.Point2D sceneToRoom(double sceneX, double sceneY) {
        javafx.geometry.Point2D origin = roomOriginInScene();
        double scale = fitScaleFactor();
        return new javafx.geometry.Point2D((sceneX - origin.getX()) / scale,
                (sceneY - origin.getY()) / scale);
    }

    /**
     * Whether a point in the window is over the room's area.
     *
     * <p><b>The whole dark area, not just the floor.</b> A planned item dropped just off the
     * room's edge should still land in the room — the alternative is a drop that silently does
     * nothing because it missed by four pixels. {@link Placement} pulls the position back
     * inside afterwards.
     */
    public boolean isOverRoomArea(double sceneX, double sceneY) {
        return localToScene(getBoundsInLocal()).contains(sceneX, sceneY);
    }

    /**
     * Scrolls until an item is visible, if it is not already.
     *
     * <p>Called after adding a box or selecting one from the list. In a room bigger than the
     * window, "it was added" is not much use if the box landed off-screen.
     */
    public void scrollItemIntoView(String id) {
        Rectangle rect = itemRects.get(id);
        if (rect == null) {
            return;
        }
        double scale = fitScaleFactor();
        scrollAxis(rect.getX() * scale, rect.getWidth() * scale,
                getViewportBounds().getWidth(), Units.feetToPx(state.room.w) * scale,
                this::getHvalue, this::setHvalue);
        scrollAxis(rect.getY() * scale, rect.getHeight() * scale,
                getViewportBounds().getHeight(), Units.feetToPx(state.room.l) * scale,
                this::getVvalue, this::setVvalue);
    }

    /**
     * Nudges one scrollbar just far enough to bring a span into view, and no further.
     *
     * <p>"Just far enough" is what {@code block: 'nearest'} means in the original: something
     * already on screen does not move at all, so selecting boxes one after another does not
     * make the room jump about.
     */
    private void scrollAxis(double itemStart, double itemSize, double viewport, double content,
            java.util.function.DoubleSupplier get, java.util.function.DoubleConsumer set) {
        double scrollable = content + Tokens.CANVAS_MARGIN * 2 - viewport;
        if (scrollable <= 0) {
            return;                       // it all fits; there is nothing to scroll
        }
        double visibleStart = get.getAsDouble() * scrollable;
        double itemStartInContent = itemStart + Tokens.CANVAS_MARGIN;

        double target;
        if (itemStartInContent < visibleStart) {
            target = itemStartInContent;
        } else if (itemStartInContent + itemSize > visibleStart + viewport) {
            target = itemStartInContent + itemSize - viewport;
        } else {
            return;                       // already visible
        }
        set.accept(Math.max(0, Math.min(1, target / scrollable)));
    }

    // ----------------------------------------------------------------- drawing

    /** Repaints the floor, its grid and its vignette. Call when the room's size changes. */
    public void rebuildRoom() {
        double widthPx = Units.feetToPx(state.room.w);
        double lengthPx = Units.feetToPx(state.room.l);

        floor.setWidth(widthPx);
        floor.setHeight(lengthPx);
        room.setPrefSize(widthPx, lengthPx);
        room.setMinSize(widthPx, lengthPx);
        room.setMaxSize(widthPx, lengthPx);
        itemLayer.resize(widthPx, lengthPx);

        GraphicsContext g = floor.getGraphicsContext2D();
        g.clearRect(0, 0, widthPx, lengthPx);

        g.setFill(Tokens.ROOM_FILL);
        g.fillRect(0, 0, widthPx, lengthPx);

        // A soft darkening towards the edges. Subtle on purpose — it gives the floor some
        // depth without becoming decoration.
        g.setFill(new RadialGradient(0, 0, 0.5, 0.5, 0.7, true, CycleMethod.NO_CYCLE,
                List.of(new Stop(0, Tokens.ROOM_FILL.deriveColor(0, 1, 1, 0)),
                        new Stop(1, javafx.scene.paint.Color.rgb(17, 17, 17, 0.7)))));
        g.fillRect(0, 0, widthPx, lengthPx);

        // One line per foot — or per metre in metric mode, which is the one place the unit
        // setting changes something drawn rather than merely labelled. Note the lines start one
        // step in and stop before the far edge: nothing is drawn on the room's own boundary.
        g.setStroke(Tokens.GRID_LINE);
        g.setLineWidth(Tokens.GRID_LINE_WIDTH);
        double step = state.metricMode ? Units.PX_PER_METER : Units.PX_PER_FOOT;
        for (double x = step; x < widthPx; x += step) {
            g.strokeLine(x, 0, x, lengthPx);
        }
        for (double y = step; y < lengthPx; y += step) {
            g.strokeLine(0, y, widthPx, y);
        }

        applyFit();
    }

    /** Recreates the item nodes from scratch. Call when items are added or removed. */
    public void rebuildItems() {
        itemLayer.getChildren().clear();
        itemRects.clear();
        selectionOutlines.clear();

        for (Item item : state.items) {
            // Planned items are ghosts — they are never drawn in the room at all, so they get
            // no node rather than a hidden one.
            if (item.planned) {
                continue;
            }
            Rectangle outline = new Rectangle();
            outline.setFill(javafx.scene.paint.Color.TRANSPARENT);
            outline.setStroke(Tokens.SELECTION_OUTLINE);
            outline.setStrokeWidth(Tokens.SELECTION_OUTLINE_WIDTH);
            outline.setStrokeType(StrokeType.INSIDE);
            outline.setMouseTransparent(true);
            outline.setVisible(false);

            Rectangle rect = new Rectangle();
            rect.setStroke(Tokens.ITEM_BORDER);
            rect.setStrokeWidth(Tokens.ITEM_BORDER_WIDTH);
            // Inside, so the border eats into the footprint instead of enlarging it — the
            // original's box-sizing:border-box does the same, and an outside stroke would
            // make every box 4 px too big.
            rect.setStrokeType(StrokeType.INSIDE);
            rect.setCursor(Cursor.OPEN_HAND);
            rect.setOnMousePressed(event -> beginDrag(item, rect, event));

            // The tooltip follows the pointer, so it is driven by movement rather than by
            // entering the box -- that is also what makes it update as the cursor crosses from
            // one box to another without leaving the room.
            rect.setOnMouseMoved(event -> {
                // An item hidden by the layer slider must not describe itself; the box under
                // the pointer is whatever is still showing.
                if (Layers.isVisible(state, item)) {
                    onItemHover.hover(item, event.getSceneX(), event.getSceneY());
                }
            });
            rect.setOnMouseExited(event -> onHoverEnded.run());

            // Right-click deletes, immediately. Hold-to-delete is a touch gesture and is never
            // wired on desktop — see CLAUDE.md §5.5.
            rect.setOnContextMenuRequested(event -> {
                // Hide first: the box is about to stop existing, and a tooltip describing it
                // would be left hanging over empty floor.
                onHoverEnded.run();
                deleteItem(item.id);
                event.consume();
            });

            itemRects.put(item.id, rect);
            selectionOutlines.put(item.id, outline);
            itemLayer.getChildren().addAll(outline, rect);
        }
        refreshItemAppearance();
    }

    /**
     * Updates every item's position, colour, front-to-back order, dimming and visibility
     * without recreating any nodes.
     */
    public void refreshItemAppearance() {
        Item selected = findItem(selectedId);

        for (Item item : state.items) {
            Rectangle rect = itemRects.get(item.id);
            if (rect == null) {
                continue;                      // planned: no node
            }
            double widthPx = Units.inchesToPx(item.w_in);
            double lengthPx = Units.inchesToPx(item.l_in);

            rect.setX(item.x_px);
            rect.setY(item.y_px);
            rect.setWidth(widthPx);
            rect.setHeight(lengthPx);
            rect.setFill(Tokens.parseHsl(item.color));

            boolean visible = Layers.isVisible(state, item);
            rect.setVisible(visible);

            Rectangle outline = selectionOutlines.get(item.id);
            boolean isSelected = item.id.equals(selectedId);
            outline.setVisible(visible && isSelected);
            if (isSelected) {
                positionSelectionOutline(item, outline);
            }

            fadeTo(rect, Layers.shouldDim(item, selected) ? Tokens.DIM_OPACITY : 1.0);
        }

        applyPaintOrder();
    }

    /**
     * Puts the selection highlight around an item where the item currently is.
     *
     * <p>The highlight is a <em>separate</em> {@code Rectangle} sibling of the item's own
     * rectangle, not a border on it, so nothing makes the two move together — each one has to be
     * positioned explicitly. That is why this is called from the drag handler as well as from
     * {@link #refreshItemAppearance()}: when only the commit-time refresh positioned it, the
     * highlight stayed at the item's old position for the whole drag and snapped across at the
     * end.
     *
     * <p>The outline sits clear of the item's edge rather than on it, so a selected box's own
     * colour is not covered up.
     */
    private void positionSelectionOutline(Item item, Rectangle outline) {
        double inset = Tokens.SELECTION_OUTLINE_OFFSET + Tokens.SELECTION_OUTLINE_WIDTH;
        outline.setX(item.x_px - inset);
        outline.setY(item.y_px - inset);
        outline.setWidth(Units.inchesToPx(item.w_in) + inset * 2);
        outline.setHeight(Units.inchesToPx(item.l_in) + inset * 2);
    }

    /**
     * Reorders the nodes so the right box is in front.
     *
     * <p>JavaFX draws children in list order, so the ordering rule from {@code Layers} is
     * applied by sorting the child list rather than by setting any per-node depth.
     */
    private void applyPaintOrder() {
        List<Item> drawable = new ArrayList<>();
        for (Item item : state.items) {
            if (itemRects.containsKey(item.id)) {
                drawable.add(item);
            }
        }
        drawable.sort(Layers::comparePaint);

        List<javafx.scene.Node> ordered = new ArrayList<>();
        for (Item item : drawable) {
            // Each item's outline goes immediately behind it, so a selected box's outline is
            // never drawn over the box in front of it.
            ordered.add(selectionOutlines.get(item.id));
            ordered.add(itemRects.get(item.id));
        }
        itemLayer.getChildren().setAll(ordered);
    }

    private void fadeTo(Rectangle rect, double target) {
        if (Math.abs(rect.getOpacity() - target) < 0.001) {
            return;
        }
        FadeTransition fade = new FadeTransition(Duration.millis(Tokens.DIM_FADE_MS), rect);
        fade.setToValue(target);
        fade.play();
    }

    // ----------------------------------------------------------------- dragging

    /**
     * Starts a drag on an item.
     *
     * <p>A press only becomes a drag once the pointer has moved past a threshold; below that
     * it is a click. Without that distinction, the tiny movement between pressing and
     * releasing a mouse button would count as a drag and nudge the box every time it was
     * clicked.
     */
    private void beginDrag(Item item, Rectangle rect, javafx.scene.input.MouseEvent press) {
        if (!press.isPrimaryButtonDown()) {
            return;
        }
        press.consume();

        double startPointerX = press.getSceneX();
        double startPointerY = press.getSceneY();
        double startItemX = item.x_px;
        double startItemY = item.y_px;
        boolean[] didDrag = {false};

        // Captured now, before anything moves, but only recorded if this turns into a real drag.
        // Taking it here is what makes undo restore the arrangement that existed beforehand.
        UndoEntry pendingUndo = new UndoEntry.Moved(item.id, item.x_px, item.y_px, item.dragOrder,
                UndoHistory.snapshotHeights(state));

        rect.setCursor(Cursor.CLOSED_HAND);

        rect.setOnMouseDragged(drag -> {
            // Pointer movement is in screen pixels but item positions are in room pixels, so
            // while Fit mode has the room scaled down the two differ. Dividing by the scale is
            // what keeps the box under the cursor instead of lagging behind it.
            double scale = fitScaleFactor();
            double dx = (drag.getSceneX() - startPointerX) / scale;
            double dy = (drag.getSceneY() - startPointerY) / scale;

            if (Math.abs(dx) > Tokens.DRAG_THRESHOLD_MOUSE_PX
                    || Math.abs(dy) > Tokens.DRAG_THRESHOLD_MOUSE_PX) {
                didDrag[0] = true;
            }

            Collision.Point allowed =
                    Collision.clampItem(state, item, startItemX + dx, startItemY + dy);
            item.x_px = allowed.x();
            item.y_px = allowed.y();
            rect.setX(allowed.x());
            rect.setY(allowed.y());

            // The highlight is its own node, so it has to be dragged along by hand or it stays
            // at the position the drag started from until the release-time refresh moves it.
            // The highlight is its own node, so it has to be dragged along by hand or it stays
            // at the position the drag started from until the release-time refresh moves it.
            if (item.id.equals(selectedId)) {
                positionSelectionOutline(item, selectionOutlines.get(item.id));
            }
            drag.consume();
        });

        rect.setOnMouseReleased(release -> {
            rect.setCursor(Cursor.OPEN_HAND);
            rect.setOnMouseDragged(null);
            rect.setOnMouseReleased(null);

            if (didDrag[0]) {
                // Only a real drag is worth an undo entry. A click that moved two pixels and
                // snapped back is not something anyone wants to undo.
                undoHistory.push(pendingUndo);

                // The item just moved, so it becomes the most recently touched one — which is
                // what puts it on top of anything it now overlaps.
                //
                // Taking the highest existing value into account is a deliberate, minimal
                // departure from the original, and it fixes a real bug in files we promised to
                // support. The original just incremented its counter. That counter is saved in
                // the file, but a file written before the dragOrder field existed has no counter
                // at all, so it loads as 0 while the items themselves get drag orders taken from
                // their serial numbers — 7, 8, and so on. Incrementing 0 to 1 then leaves the
                // box the user just dragged *below* everything else, and stacking stays wrong
                // until they have dragged as many times as there are items.
                //
                // When the counter is consistent — every file the current app writes — the
                // maximum is the counter itself and this behaves identically.
                state.dragOrderCounter = Math.max(state.dragOrderCounter, highestDragOrder()) + 1;
                item.dragOrder = state.dragOrderCounter;
                Stacking.recomputeAllBaseHeights(state);
                refreshItemAppearance();
                onDragCommitted.run();
            } else {
                onItemActivated.accept(item);
            }
            release.consume();
        });
    }

    // --------------------------------------------------------------------- fit

    private void applyFit() {
        double widthPx = Units.feetToPx(state.room.w);
        double lengthPx = Units.feetToPx(state.room.l);

        if (!fitMode) {
            // 1/uiScale, not 1: the whole interface is drawn through a scale transform for the
            // Ctrl+scroll zoom, and this cancels it back out so the room alone stays to scale at
            // 8 px to the inch. Without it, zooming the controls would stretch the room too and
            // a box on screen would stop being a real size.
            //
            // Fit mode below deliberately does NOT do this. Fitting the room to the window is
            // already a departure from to-scale — that is what it is for — so there the room
            // should keep filling the viewport whatever the zoom.
            fitScale.setX(1 / uiScale);
            fitScale.setY(1 / uiScale);
            holder.setPadding(new Insets(Tokens.CANVAS_MARGIN));
            setHbarPolicy(ScrollBarPolicy.AS_NEEDED);
            setVbarPolicy(ScrollBarPolicy.AS_NEEDED);
            return;
        }

        double availableWidth = getViewportBounds().getWidth();
        double availableHeight = getViewportBounds().getHeight();
        if (availableWidth <= 0 || availableHeight <= 0) {
            // Laid out but not measured yet; the layout pass will call back.
            return;
        }

        double scaleX = (availableWidth - Tokens.FIT_PADDING * 2) / widthPx;
        double scaleY = (availableHeight - Tokens.FIT_PADDING * 2) / lengthPx;
        double scale = Math.min(Math.min(scaleX, scaleY), 1);   // never magnify past 1:1

        fitScale.setX(scale);
        fitScale.setY(scale);

        double left = Math.max(Tokens.FIT_PADDING, (availableWidth - widthPx * scale) / 2);
        double top = Math.max(Tokens.FIT_PADDING, (availableHeight - lengthPx * scale) / 2);
        holder.setPadding(new Insets(top, 0, 0, left));

        // Nothing can be off-screen in Fit mode, so scrollbars would be misleading.
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollBarPolicy.NEVER);
        setHvalue(0);
        setVvalue(0);
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        if (fitMode) {
            applyFit();
        }
    }

    /** The highest drag order among the items currently in the room, or 0 if there are none. */
    private double highestDragOrder() {
        double highest = 0;
        for (Item item : state.items) {
            highest = Math.max(highest, item.dragOrder);
        }
        return highest;
    }

    private Item findItem(String id) {
        if (id == null) {
            return null;
        }
        for (Item item : state.items) {
            if (item.id.equals(id)) {
                return item;
            }
        }
        return null;
    }
}
