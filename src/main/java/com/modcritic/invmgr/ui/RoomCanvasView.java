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
import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TouchEvent;
import javafx.scene.input.TouchPoint;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Line;
import javafx.scene.shape.Path;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeType;
import javafx.scene.transform.Scale;
import javafx.util.Duration;

/**
 * The room seen from above: floor, grid, and the boxes in it.
 *
 * <p>This is the app's primary interface: the 2D view comes first and matters most, which is
 * why it is built before the 3D one.
 *
 * <p><b>How it's drawn.</b> The floor, its grid and its vignette are painted onto a single
 * JavaFX {@code Canvas}; they only change when the room's size changes. Each box, by
 * contrast, is its own {@code Rectangle} node. That mirrors the original app, where every item
 * was its own element, and it buys three things cheaply: front-to-back ordering by list order,
 * per-item opacity for the dim effect, and hit detection for dragging without any manual
 * coordinate math.
 *
 * <p><b>Coordinates.</b> Everything inside the room is in room pixels, where 8 pixels is one
 * inch and 96 is one foot. Item positions are stored that way, so the view never converts
 * them, except when Fit mode scales the whole room, which is handled in exactly one place
 * (see {@link #setFitMode}).
 */
public final class RoomCanvasView extends ScrollPane {

    /** Told which item the pointer is over, and where in the window it is. */
    @FunctionalInterface
    public interface HoverHandler {
        void hover(Item item, double sceneX, double sceneY);
    }

    /** Floor, grid and vignette. Repainted only when the room's dimensions change. */
    /**
     * The floor itself, and the one node a click on bare floor lands on.
     *
     * <p><b>Three shapes rather than one canvas, and that is B11's fix.</b> A {@code Canvas} is a
     * raster: it takes a texture the size of the room times the screen's scale, and nothing capped
     * it. A 30 ft x 40 ft room on a phone wanted 380 MB of it and left nothing for the 3D wall
     * textures, which then failed to allocate and drew black; a 180 ft room passes the 16384
     * texture limit outright and the floor goes blank on a desktop too. Shapes are vector. They
     * cost no buffer at any room size.
     *
     * <p><b>It is also what the original does.</b> Its room grid is an SVG overlay, {@code
     * #room-svg}, declared {@code pointer-events: none}. Its only canvases are the 3D surfaces,
     * whose {@code min(96, 2048 / longest side)} cap {@link com.modcritic.invmgr.threed.SurfaceMetrics}
     * already ports. Reaching for a canvas here was the port's own idea and this undoes it.
     */
    private final Rectangle floorFill = new Rectangle();

    /** The soft darkening towards the edges. Mouse-transparent, like the original's SVG. */
    private final Rectangle floorVignette = new Rectangle();

    /**
     * The grid lines. Mouse-transparent.
     *
     * <p><b>One {@code Line} per line, in a {@code Group}, and NOT one {@code Path} holding all of
     * them.</b> That was tried and it is visibly wrong: a single path strokes once, so where two
     * lines cross the result is the same 50% black as a single line, where separate lines
     * composite twice and the crossing goes darker. The original is unambiguous about which it
     * wants: it builds a {@code <g stroke="rgba(0,0,0,0.5)" stroke-width="2">} and appends one
     * {@code <line>} per grid line, so every crossing is two elements deep. Caught by
     * {@code CanvasAppearanceTest} sampling a crossing, which is the only place the two differ.
     *
     * <p>It is also the same node count the original creates, since it makes one SVG element per
     * line as well.
     */
    private final Group floorGrid = new Group();

    /** One {@link Rectangle} per item, plus selection outlines. */
    private final Pane itemLayer = new Pane();

    /** Floor and items together: this is the thing Fit mode scales. */
    private final Pane room = new Pane(floorFill, floorVignette, floorGrid, itemLayer);

    /** Positions the room within the scrollable area, and supplies its margin. */
    private final StackPane holder = new StackPane(new Group(room));

    /** Applied to {@link #room}, pivoting on its top-left corner as the original does. */
    private final Scale fitScale = new Scale(1, 1, 0, 0);

    private final Map<String, Rectangle> itemRects = new LinkedHashMap<>();
    private final Map<String, Rectangle> selectionOutlines = new HashMap<>();

    /**
     * One gesture recognizer per box, not one for the whole view.
     *
     * <p>A recognizer remembers when its last clean tap was, because a double tap is a
     * relationship between two of them, and that is the one piece of state that outlives a
     * gesture. Sharing a single recognizer across every box would read "tap box A, then tap box B
     * within 400 ms" as a double tap on B and open the wrong item's dialog. The original keeps the
     * same state per element for the same reason.
     *
     * <p>Rebuilt with the rectangles, so a delete, an undo or a load also forgets any pending tap.
     */
    private final Map<String, TouchGesture> itemGestures = new HashMap<>();

    /** Where the room was scrolled to when the current box drag began. See {@link #holdScroll}. */
    private double heldHvalue;

    private double heldVvalue;

    /** True while {@link #holdScroll} is putting a value back, so it does not answer itself. */
    private boolean restoringScroll;

    /**
     * The red square that warns a hold is about to delete a box: one node for the whole view.
     *
     * <p>Only one box can ever be held at a time, so one is enough. The original makes one per
     * element because that is how a CSS child works, but a third node per item is exactly the cost
     * {@code CLAUDE.md} §5.6 <b>OI-1</b> already warns about: {@link #rebuildItems()} recreates
     * every node it owns, and 500 items is where that starts to show.
     */
    private final Rectangle holdRing = new Rectangle();

    /** Which box the ring is currently around, or null when it is off screen. */
    private String holdRingItemId;

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

    /**
     * Called when a finger taps a box: show its details above it, the way a hover does on desktop.
     *
     * <p>The point handed over is the middle of the box's top edge, not where the finger was: a
     * fingertip covers what it is pointing at, so a tooltip under it would be unreadable.
     */
    private HoverHandler onItemTapped = (item, sceneX, sceneY) -> { };

    /**
     * Called when this view changes the selection by itself, so the item list can follow.
     *
     * <p>Needed because the list holds its own copy of which row is highlighted. Two things in
     * here change the selection without going through the list: tapping a box, and clicking bare
     * floor to clear it.
     */
    private Runnable onSelectionChanged = () -> { };

    // ------------------------------------------------------------- touch gesture

    /** Which box a finger is currently on, or null. Only one gesture runs at a time. */
    private String activeTouchId;

    /**
     * Runs for as long as the platform may still be sending inertia after a box drag.
     *
     * <p>A {@link PauseTransition} used purely as a timer, and its duration is not a guess: Glass's
     * {@code ScrollGestureRecognizer} declares {@code SCROLL_INERTIA_MILLIS = 1500}, so that is how
     * long its fling can keep arriving after the finger has gone. A little over, to be sure of
     * outlasting it. See {@link #ignoreScrollWhileDragging}.
     */
    private final PauseTransition inertiaFromBoxDrag =
            new PauseTransition(Duration.millis(Tokens.BOX_DRAG_INERTIA_GUARD_MS));

    // ------------------------------------------------------- the room's own coasting (B9)

    /** How fast the room was moving when the finger left it, and where it goes next. */
    private final PanMomentum panMomentum = new PanMomentum();

    /** Reused by {@link #coastClock} so a fling does not allocate on every frame. */
    private final double[] coastStep = new double[2];

    /**
     * True between {@code SCROLL_STARTED} and {@code SCROLL_FINISHED}: a finger is panning the room.
     *
     * <p>It gates the sampling, and it is what keeps the coast from feeding on itself. Setting
     * {@code hvalue} during a fling fires the same listener the sampling hangs off, so without this
     * the room would sample its own coasting, measure that as speed, and never stop.
     */
    private boolean panningRoom;

    /**
     * Drives the coast after the finger has gone.
     *
     * <p>An {@link AnimationTimer} for the same reason the hold uses one: the decay has to be
     * sampled at whatever rate the phone is actually drawing at, and {@link PanMomentum} integrates
     * across the frame it is given rather than assuming one.
     */
    private final AnimationTimer coastClock = new AnimationTimer() {
        @Override
        public void handle(long nanos) {
            if (!panMomentum.advance(nanos / 1_000_000.0, coastStep)) {
                stop();
                return;
            }
            glideBy(coastStep[0], coastStep[1]);
        }
    };

    /** Where the finger went down and where the box was then, both in room pixels. */
    private double touchStartX;
    private double touchStartY;
    private double touchStartItemX;
    private double touchStartItemY;

    /**
     * How much the room was scaled down when the finger landed, captured once for the gesture.
     *
     * <p>Fit mode cannot change while a finger is down, and reading it per frame would mean the
     * box jumped if it ever did.
     */
    private double touchScale = 1;

    /** Recorded before the box moves, and only pushed if the gesture turns out to be a drag. */
    private UndoEntry pendingTouchUndo;

    /**
     * The per-frame clock behind the hold: it fades the ring in and fires the delete.
     *
     * <p>An {@link AnimationTimer} rather than a one-shot 1500 ms timer, and that is forced rather
     * than preferred. {@link TouchGesture#holdFired} is written to be asked every frame and to
     * answer yes exactly once, and {@link TouchGesture#isHolding} exists only to be polled; a
     * single delayed timer would make both of them dead code. It also keeps the ring and the
     * delete on one clock, so the red cannot finish early or late.
     */
    /**
     * How many frames the hold clock has run, ever. For tests, and it earns its keep.
     *
     * <p>An {@code AnimationTimer} will not say whether it is running, and a separate flag would
     * only report what {@code endHold} <em>meant</em> to do. Counting the frames it actually
     * delivers is the one way to catch the timer being left running after the finger has gone,
     * which is invisible on screen, because the ring has already been taken off, and costs battery
     * on a phone forever after.
     */
    private long holdFrames;

    private final AnimationTimer holdClock = new AnimationTimer() {
        @Override
        public void handle(long nanos) {
            holdFrames++;
            double nowMs = nanos / 1_000_000.0;
            TouchGesture gesture = activeTouchId == null ? null : itemGestures.get(activeTouchId);
            if (gesture == null) {
                endHold();
                return;
            }
            if (gesture.holdFired(nowMs)) {
                String doomed = activeTouchId;
                // The ring has to come off before deleteItem runs: that rebuilds itemLayer's
                // children from scratch, and a ring still in the old list would reappear as an
                // orphan the next time the paint order was applied.
                endHold();
                onHoverEnded.run();
                deleteItem(doomed);
                return;
            }
            if (!gesture.isHolding()) {
                endHold();                       // the finger moved: this is a drag now
                return;
            }
            holdRing.setOpacity(Tokens.HOLD_RING_MIN_OPACITY
                    + (1 - Tokens.HOLD_RING_MIN_OPACITY) * gesture.holdProgress(nowMs));
        }
    };

    public RoomCanvasView(AppState state) {
        this.state = state;

        room.getTransforms().add(fitScale);
        itemLayer.setPickOnBounds(false);   // clicks pass through empty space to the floor

        // The grid and the vignette take no clicks, so a press on bare floor always lands on
        // floorFill and the deselect below can test one node. This is the original's own rule:
        // #room-svg is declared `pointer-events: none` for exactly this reason. A canvas needed
        // none of it, because a canvas was one node; three nodes need it stated.
        floorVignette.setMouseTransparent(true);
        floorGrid.setMouseTransparent(true);

        holder.setAlignment(Pos.TOP_LEFT);
        holder.setPadding(new Insets(Tokens.CANVAS_MARGIN));

        setContent(holder);
        setPannable(false);
        pinScrollableArea();

        // ⚠ A FILTER ON THE SCROLL PANE ITSELF, and it is the only thing that stops the room being
        // dragged along with a box. Read the whole of this before touching it, because both obvious
        // simplifications of it are wrong.
        //
        // `setPannable(false)` does NOT disable panning on a touch platform: ScrollPaneSkin installs
        // a separate press/drag pan on its inner viewRect inside `if (IS_TOUCH_SUPPORTED)`, and that
        // branch never asks whether the pane is pannable. Android drives it with the mouse events it
        // manufactures from a finger, and §5.8 item 5 records that this is exactly how the room gets
        // panned at all, so it must keep working.
        //
        // The per-rectangle filter that consumes those synthesized events cannot help here. It sits
        // ON the box, and the skin's handlers sit on an ANCESTOR of the box; consuming at the target
        // stops the bubble, which is why that filter looked sufficient for four milestones and why
        // three separate theories about this bug were wrong. Whatever the remaining path is (and
        // the phone says the scroll happens while `activeTouchId` is set, tracking the finger one to
        // one), it reaches the skin without passing through the box.
        //
        // So the block is placed where it cannot be gone around: a filter on this pane runs during
        // the capture phase, above the skin and above the boxes. Narrowed three ways so it takes
        // nothing else with it: only while a box drag is actually in progress, only synthesized
        // events, and never touch events, which are what drive the drag itself.
        addEventFilter(MouseEvent.ANY, event -> {
            if (activeTouchId != null && event.isSynthesized()) {
                event.consume();
            }
        });
        addEventFilter(ScrollEvent.ANY, this::ignoreScrollWhileDragging);

        // B9. Registered AFTER the guard above so that ordering reads the way the two behave:
        // the guard decides whether this scroll belongs to a box drag, and this one only ever
        // looks at scrolls that are the room's.
        addEventFilter(ScrollEvent.ANY, this::trackPanForMomentum);

        // A new finger cancels the inertia guard, and without this the cure would be worse than the
        // complaint: the guard runs 1.6 s, so for a second and a half after moving a box you could
        // not pan the room deliberately. A pan starts with a touch, and a touch means the platform's
        // old fling is no longer what is being asked for.
        //
        // The room's own coast stops on the same event and for the same reason: a finger landing on
        // a moving room means "stop", which is what every scrolling list on the phone does.
        addEventFilter(TouchEvent.TOUCH_PRESSED, event -> {
            inertiaFromBoxDrag.stop();
            stopCoasting();
        });

        // AND THE SCROLL IS HELD WHERE IT WAS, which is a belt to that filter's braces and is here
        // because the filter alone has not been shown to work. Four attempts at the CAUSE have now
        // failed: filter ordering, the synthesized flag, the app's own gesture, and the content
        // growing, and the fourth was a real bug that simply was not this one. Even with
        // `com.sun.javafx.touch=true` the skin's pan cannot be provoked in this container, so a
        // filter aimed at it is a fix nobody here can verify.
        //
        // This constrains the OUTCOME instead, and the outcome is the thing the user reported: while
        // a box is under a finger, the room does not move. Whatever moves these two values (the
        // skin, a gesture nobody has thought of, a future version of JavaFX), they go straight back.
        //
        // Deliberately narrow. It is armed ONLY between a touch landing on a box and that gesture
        // ending, so panning the room by its background is untouched, and so is every scroll the app
        // makes on purpose: scrollItemIntoView runs when an item is added or selected, never during
        // a drag. The guard stops the listener reacting to its own correction.
        //
        // B9 hangs its sampling off the same two listeners, and for the same reason they exist at
        // all: what the room is asked to do is the skin's business, and what the room actually DID
        // is observable right here. Measuring the movement rather than the request means the coast
        // continues whatever really happened, at whatever scale Fit had it at.
        hvalueProperty().addListener((observable, before, after) -> {
            samplePan(true, before.doubleValue(), after.doubleValue());
            holdScroll(true, after.doubleValue());
        });
        vvalueProperty().addListener((observable, before, after) -> {
            samplePan(false, before.doubleValue(), after.doubleValue());
            holdScroll(false, after.doubleValue());
        });

        // The viewport and the ScrollPane itself both need the darker background, or a paler
        // default gray shows through around the room.
        String background = "-fx-background: " + Tokens.hex(Tokens.CANVAS_WRAP_BG) + ";"
                + "-fx-background-color: " + Tokens.hex(Tokens.CANVAS_WRAP_BG) + ";";
        setStyle(background);
        getStylesheets().add(
                RoomCanvasView.class.getResource("/com/modcritic/invmgr/ui/canvas.css")
                        .toExternalForm());
        holder.setStyle("-fx-background-color: " + Tokens.hex(Tokens.CANVAS_WRAP_BG) + ";");

        // Clicking bare floor clears the selection, matching the original.
        //
        // A CLICK, not a press, which is what the original binds too. The difference only shows up
        // under a finger: on a phone this same view is scrolled by dragging it, and a press fires
        // at the start of that drag, so clearing on press would wipe the selection every time
        // someone scrolled the room. A click is not delivered after a drag at all.
        holder.setOnMouseClicked(event -> {
            if (event.getTarget() == holder || event.getTarget() == floorFill) {
                setSelectedId(null);
                // The list keeps its own highlight, so it has to be told. Without this the row
                // stayed blue with nothing selected in the room: the original clears both.
                onSelectionChanged.run();
            }
        });

        holdRing.setFill(javafx.scene.paint.Color.TRANSPARENT);
        holdRing.setStroke(Tokens.HOLD_RING);
        holdRing.setStrokeWidth(Tokens.HOLD_RING_WIDTH);
        // INSIDE, matching positionSelectionOutline right next door, and matching the CSS: the
        // original's ring is a box inset -5px with a 3px border drawn inside it, so the red runs
        // from 5 px outside the item inward to 2 px outside it.
        holdRing.setStrokeType(StrokeType.INSIDE);
        holdRing.setMouseTransparent(true);

        rebuildRoom();
        rebuildItems();
    }

    // ------------------------------------------------------------------ state

    /** Replaces the whole room: used after loading a file. */
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
     * The hold-to-delete warning ring, so a test can measure it.
     *
     * <p>It is on screen only while a finger is resting on a box; the rest of the time it has no
     * parent. A test asks whether it is showing by looking at that, never by counting children:
     * a ring found by position would agree with itself even if it were around the wrong box.
     */
    public Rectangle holdRingNode() {
        return holdRing;
    }

    /** For tests: how many frames the hold clock has delivered. See the field it returns. */
    public long holdClockFrames() {
        return holdFrames;
    }

    /**
     * The item layer's children, in the order JavaFX will draw them: later paints on top.
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

    /** What to do when a finger taps a box: show its details above it. Touch only. */
    public void setOnItemTapped(HoverHandler handler) {
        this.onItemTapped = handler == null ? (item, x, y) -> { } : handler;
    }

    /** Told when this view changed the selection itself, so the item list can follow. */
    public void setOnSelectionChanged(Runnable handler) {
        this.onSelectionChanged = handler == null ? () -> { } : handler;
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
     * a room bigger than the window. <b>It never scales up past 1:1</b>: a small room stays
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
     * so this returns <b>1</b> at every zoom level: the room is to scale, so a screen pixel is a
     * room pixel. That is why the zoom needed no changes anywhere in the drag or hit-testing code.
     */
    public double fitScaleFactor() {
        return fitScale.getX() * uiScale;
    }

    /**
     * Sets the interface zoom this view has to compensate for.
     *
     * <p>Called by {@link com.modcritic.invmgr.App} when Ctrl+scroll changes it. The view does not
     * scale itself; it un-scales, so the room comes out the same size on screen whatever the
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
     * <p>The one place that conversion belongs: everywhere else works in room pixels.
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
     * room's edge should still land in the room: the alternative is a drop that silently does
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
        double scrollable = scrollableSpan(content, viewport);
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

        room.setPrefSize(widthPx, lengthPx);
        room.setMinSize(widthPx, lengthPx);
        room.setMaxSize(widthPx, lengthPx);
        itemLayer.resize(widthPx, lengthPx);

        floorFill.setWidth(widthPx);
        floorFill.setHeight(lengthPx);
        floorFill.setFill(Tokens.ROOM_FILL);

        // A soft darkening towards the edges. Subtle on purpose: it gives the floor some
        // depth without becoming decoration. Proportional stops, so it re-centers on any room.
        floorVignette.setWidth(widthPx);
        floorVignette.setHeight(lengthPx);
        floorVignette.setFill(new RadialGradient(0, 0, 0.5, 0.5, 0.7, true, CycleMethod.NO_CYCLE,
                List.of(new Stop(0, Tokens.ROOM_FILL.deriveColor(0, 1, 1, 0)),
                        new Stop(1, javafx.scene.paint.Color.rgb(17, 17, 17, 0.7)))));

        // One line per foot, or per meter in metric mode, which is the one place the unit
        // setting changes something drawn rather than merely labeled. Note the lines start one
        // step in and stop before the far edge: nothing is drawn on the room's own boundary.
        //
        // All of them in ONE Path, not one node each. A 200 ft room in metric is about 1,300
        // lines, and that many nodes would cost more in layout than the grid is worth.
        floorGrid.getChildren().clear();
        double step = state.metricMode ? Units.PX_PER_METER : Units.PX_PER_FOOT;
        for (double x = step; x < widthPx; x += step) {
            floorGrid.getChildren().add(gridLine(x, 0, x, lengthPx));
        }
        for (double y = step; y < lengthPx; y += step) {
            floorGrid.getChildren().add(gridLine(0, y, widthPx, y));
        }

        applyFit();
    }

    /** Recreates the item nodes from scratch. Call when items are added or removed. */
    public void rebuildItems() {
        // JavaFX has no touch-canceled event, so this stands in for one. The ways a gesture can
        // be taken away here are a second finger (handled in touchBegan) and the boxes being
        // replaced underneath it: a delete, an undo, or a file being loaded.
        //
        // ⚠ Deleting this line leaves every test in this project passing, and would still be wrong
        // on a phone. A test fires its touches at a node it looked up a moment earlier, so after a
        // rebuild it naturally addresses the NEW rectangle. A real finger does not: JavaFX records
        // which node a touch point was pressed on and delivers the rest of that gesture there
        // whatever happens to the scene, so the lift would arrive at the OLD, detached rectangle,
        // whose handler still holds the old recognizer, with the finger still down on it, and the
        // old pending undo entry beside it. That is a stale move pushed onto the undo stack, and
        // it cannot be reproduced from here because Event.fireEvent has no grab map to bypass.
        cancelTouch();

        itemLayer.getChildren().clear();
        itemRects.clear();
        selectionOutlines.clear();
        itemGestures.clear();

        for (Item item : state.items) {
            // Planned items are ghosts: they are never drawn in the room at all, so they get
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
            // Inside, so the border eats into the footprint instead of enlarging it: the
            // original's box-sizing:border-box does the same, and an outside stroke would
            // make every box 4 px too big.
            rect.setStrokeType(StrokeType.INSIDE);
            rect.setCursor(Cursor.OPEN_HAND);
            rect.setOnMousePressed(event -> beginDrag(item, rect, event));

            // Android manufactures a mouse press, drag and click from every finger, and those
            // would drive beginDrag above at the same time as the touch handlers below drive
            // TouchGesture, so every gesture would happen twice. A FILTER rather than a handler,
            // because a filter runs before the node's own handlers and beginDrag is one of them.
            //
            // Scoped to this rectangle and nothing above it, deliberately. The room is scrolled
            // by a finger through exactly these synthesized events (JavaFX's own ScrollPaneSkin
            // pans from mouse drags once the platform reports touch support), so a filter on the
            // view, the holder or the scene would leave the room unpannable. Nothing is lost by
            // consuming them here: beginDrag already consumed the press on a box, so the scroller
            // never saw one anyway.
            rect.addEventFilter(MouseEvent.ANY, event -> {
                if (event.isSynthesized()) {
                    event.consume();
                }
            });

            TouchGesture gesture = new TouchGesture();
            itemGestures.put(item.id, gesture);
            rect.setOnTouchPressed(event -> touchBegan(item, gesture, event));
            rect.setOnTouchMoved(event -> touchMoved(item, rect, gesture, event));
            rect.setOnTouchReleased(event -> touchEnded(item, rect, gesture, event));

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
            // wired on desktop: see CLAUDE.md §5.5 D-1.
            //
            // Not wired at all on touch, and that is the other half of the same rule. A
            // ContextMenuEvent is not a MouseEvent, so the synthesized-mouse filter above cannot
            // catch one; if a long press on Android ever raised it, the box would be deleted
            // twice: once by the ring finishing and once by this.
            if (!Device.isTouch()) {
                rect.setOnContextMenuRequested(event -> {
                    // Hide first: the box is about to stop existing, and a tooltip describing it
                    // would be left hanging over empty floor.
                    onHoverEnded.run();
                    deleteItem(item.id);
                    event.consume();
                });
            }

            itemRects.put(item.id, rect);
            selectionOutlines.put(item.id, outline);
            itemLayer.getChildren().addAll(outline, rect);
        }
        refreshItemAppearance();
    }

    /**
     * Updates every item's position, color, front-to-back order, dimming and visibility
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
     * rectangle, not a border on it, so nothing makes the two move together: each one has to be
     * positioned explicitly. That is why this is called from the drag handler as well as from
     * {@link #refreshItemAppearance()}: when only the commit-time refresh positioned it, the
     * highlight stayed at the item's old position for the whole drag and snapped across at the
     * end.
     *
     * <p>The outline sits clear of the item's edge rather than on it, so a selected box's own
     * color is not covered up.
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
            if (item.id.equals(holdRingItemId)) {
                // Directly in front of the box it belongs to, which is the original's
                // `z-index: 1` on a child of the item. Living in itemLayer rather than in an
                // overlay also means the ring is in room pixels, so its 5 px sits 5 px clear at
                // every Fit scale, exactly as the original's does inside the scaled canvas.
                ordered.add(holdRing);
            }
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


    private void beginDrag(Item item, Rectangle rect, MouseEvent press) {
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

            dragTo(item, rect, startItemX + dx, startItemY + dy);
            drag.consume();
        });

        rect.setOnMouseReleased(release -> {
            rect.setCursor(Cursor.OPEN_HAND);
            rect.setOnMouseDragged(null);
            rect.setOnMouseReleased(null);

            if (didDrag[0]) {
                commitDrag(item, pendingUndo);
            } else {
                onItemActivated.accept(item);
            }
            release.consume();
        });
    }

    /**
     * Moves a box to where the pointer or finger has taken it, as far as the room allows.
     *
     * <p>Shared by the mouse and the touch path so there is one copy of what dragging means.
     *
     * @param roomX where the box would go with nothing in the way, in room pixels
     */
    /**
     * Fixes how much there is to scroll, so that moving a box can never change it.
     *
     * <p><b>This is B2: "dragging an item to move it also moves the canvas".</b> Three theories
     * about it were wrong before a stack trace and a scroll log between them named the real one, and
     * it is not an event being mishandled at all.
     *
     * <p>The selected box's white ring is drawn <em>outside</em> its box: {@code
     * positionSelectionOutline} sets it at {@code x - inset}, and {@code dragTo} re-positions it on
     * every frame of a drag. A {@code Pane} does not clip, and the {@code Group} wrapping the room
     * takes its bounds from whatever its children cover, so the moment a selected box reaches an
     * edge, the ring crosses it, the scrollable content grows, and a {@code ScrollPane}'s
     * {@code hvalue} and {@code vvalue} are <em>fractions</em> of that content. The fractions shift
     * under a viewport that has not moved, and the room slides. Both axes at once, every frame,
     * which is exactly what the log showed.
     *
     * <p><b>The original cannot have this bug, and the reason is a detail of CSS worth keeping.</b>
     * Its selection ring is an {@code outline}, and an outline is defined never to affect layout or
     * scrollable overflow: that is precisely what separates it from a {@code border}. JavaFX has no
     * such thing: a ring is a real node and real nodes have bounds. So the property has to be
     * recreated deliberately.
     *
     * <p>Done by clipping rather than by moving the ring, and the clip is <b>bigger than the room</b>
     * by the ring's own reach, so the scrollable area is constant, and nothing that was visible
     * before is hidden now. Clipping tight to the room would also have fixed the scrolling, and
     * would have sliced the ring off a box parked against a wall.
     *
     * <p>Phone-only in practice, which is why four milestones missed it: a desktop window is bigger
     * than the room, so there is no scrolling to be had and the content growing costs nothing.
     */
    private void pinScrollableArea() {
        // Clipped tight to the room, at exactly the bounds it has always had.
        //
        // Two other shapes were tried and both were worse. A clip GENEROUS enough to spare the ring
        // (the room plus its 4 px reach) does not fix anything: a clip is a ceiling on what the
        // bounds can reach, and underneath it they still varied: 1920 with the ring inside the
        // room, 1924 with it over the left wall. Adding an invisible rectangle to hold the bounds
        // open at the larger size did make them constant, and moved the room's own origin to −4,
        // which the whole interface is measured against: ZoomedOverlayTest caught the tooltip
        // landing half a pixel out at 150% zoom.
        //
        // So the scrollable area is the room, full stop, and the cost is paid where it is cheapest:
        // the last few pixels of the white ring are clipped on a box parked flush against a wall.
        // The original does show that sliver, and this is the one part of its behavior not
        // reproduced here: an `outline` that neither clips nor affects layout has no JavaFX
        // equivalent, and of the two properties the layout one is what the user actually noticed.
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(room.widthProperty());
        clip.heightProperty().bind(room.heightProperty());
        room.setClip(clip);
    }

    /**
     * Swallows the scroll <b>gesture</b> while a box is being dragged, and for as long afterwards as
     * the platform may still be flinging. <b>This is the actual cause of "dragging an item also
     * moves the canvas", after five wrong answers.</b>
     *
     * <p><b>Where it comes from.</b> The app's own Android launcher hard-codes
     * {@code -Dcom.sun.javafx.gestures.scroll=true} ({@code native/invmgr_launcher.c}), and that
     * property defaults to <em>false</em> everywhere else. So on the phone (and only on the phone)
     * Glass builds a {@code ScrollGestureRecognizer}, which turns one finger moving more than 10 px
     * into {@code ScrollEvent.SCROLL}. {@code Scene} aims those at the node under the finger, which
     * is the box, and they then bubble up through this pane to {@code ScrollPaneSkin}'s own
     * {@code SCROLL} handler, which scrolls the room.
     *
     * <p><b>Why nothing else worked, and each failure now makes sense.</b> The recognizer sits in
     * Glass, <em>below</em> the scene graph: {@code GlassViewEventHandler} hands the touch to the
     * scene and then calls {@code notifyEndTouchEvent} unconditionally, with no check for whether
     * anyone consumed it, so consuming {@code TouchEvent} on the box is invisible to it.
     * {@code ScrollEvent} has no {@code isSynthesized()} at all, so every fix built on that flag was
     * testing a property this path does not carry. And {@code isPannable()} is never consulted by
     * the {@code SCROLL} handler, so {@code setPannable(false)} was never going to matter.
     *
     * <p><b>And why it could not be reproduced here.</b> No launcher, no property, no recognizer, no
     * {@code ScrollEvent}. A test in this container cannot make this bug happen, which is exactly
     * why an earlier attempt passed identically with the fix present and absent.
     *
     * <p><b>The tail.</b> {@code ScrollGestureRecognizer.notifyEndTouchEvent} starts an inertia
     * timeline ({@code SCROLL_INERTIA_MILLIS = 1500}) that keeps sending scrolls after the finger
     * has lifted. Disarming when the app's own gesture ended is precisely what turned the pan into
     * the momentum the user then reported, so the guard runs on past it.
     *
     * <p>Narrow on purpose: it is armed only by a touch that landed <em>on a box</em>, so panning
     * the room by its background (which the phone does through this same gesture) is untouched.
     */
    private void ignoreScrollWhileDragging(ScrollEvent event) {
        if (swallowsScrollGesture()) {
            event.consume();
        }
    }

    /**
     * Whether a scroll gesture currently belongs to a box drag rather than to the room.
     *
     * <p>Its own method, and visible to the tests, because the rule can be asked here and cannot be
     * observed any other way in this container: no Android launcher means no scroll recognizer, so
     * the events this exists to swallow are never generated, and {@code ScrollPaneSkin}'s own
     * handler consults {@code IS_TOUCH_SUPPORTED} internally and moves nothing on a desktop. Reading
     * a fired event's consumed flag back does not work either: JavaFX re-targets a gesture, so the
     * object handed to {@code fireEvent} is not the one the handlers see.
     */
    /**
     * The floor rectangle, which is also what a click on bare floor lands on.
     *
     * <p>Visible to the tests because B11's rule can only be asked of the node: the floor must
     * cost no pixel buffer that grows with the room. A {@code Rectangle} answers that by being a
     * {@code Rectangle}, and a test that finds a {@code Canvas} here again has found the bug back.
     */
    Rectangle floorNode() {
        return floorFill;
    }

    /** The grid, for the tests that count its lines. */
    Group gridNode() {
        return floorGrid;
    }

    /** One grid line, carrying the stroke the original puts on the group around all of them. */
    private static Line gridLine(double x1, double y1, double x2, double y2) {
        Line line = new Line(x1, y1, x2, y2);
        line.setStroke(Tokens.GRID_LINE);
        line.setStrokeWidth(Tokens.GRID_LINE_WIDTH);
        return line;
    }

    boolean swallowsScrollGesture() {
        return activeTouchId != null
                || inertiaFromBoxDrag.getStatus() == Animation.Status.RUNNING;
    }

    /**
     * B9. Throws away the platform's fling and measures the gesture so the app can do its own.
     *
     * <p><b>Why the platform's is not simply tuned.</b> Glass's {@code ScrollGestureRecognizer}
     * decides whether to fling by asking how long the whole gesture lasted, flinging only under
     * 300 ms, and never asks how fast the finger was going when it left. So the same parting flick
     * coasts at the end of a quick swipe and stops dead at the end of a considered pan. The two
     * constants that shape it, {@code MAX_INITIAL_VELOCITY} 1000 and {@code SCROLL_INERTIA_MILLIS}
     * 1500, are plain literals in its {@code <clinit>}; the {@code doPrivileged} block at the end of
     * that method reads exactly two system properties,
     * {@code com.sun.javafx.gestures.scroll.threshold} and
     * {@code com.sun.javafx.gestures.scroll.inertia}, and neither of those two is among them. The
     * trigger therefore cannot be corrected from outside, only the whole fling turned off.
     *
     * <p><b>Turned off here rather than by that property, and both are done.</b>
     * {@code isInertia()} is public API on {@code GestureEvent}, and the recognizer genuinely sets
     * it: its live-gesture send passes false and its inertia timeline passes true. Consuming on
     * that flag works whatever a native image does to a static initializer at build time, which is
     * the risk the property alone carries and one that cannot be tested from this container. The
     * property is set as well, in {@link com.modcritic.invmgr.Launcher}, because it stops the events
     * being made at all and costs one line.
     *
     * <p>Unlike the bug this sits next to, <b>this one is testable here</b>: a
     * {@code ScrollEvent} carrying the inertia flag is publicly constructible, so a test can fire
     * one and watch it be swallowed without any of it existing on the platform.
     */
    private void trackPanForMomentum(ScrollEvent event) {
        if (event.isInertia()) {
            event.consume();
            return;
        }
        if (swallowsScrollGesture()) {
            // The scroll belongs to a box drag. ignoreScrollWhileDragging has already consumed it,
            // and a drag must not leave the room coasting after the box is put down.
            return;
        }

        // Compared inline rather than through a local of type EventType, deliberately: naming that
        // type would add two javafx.event imports, and every javafx class this project names has to
        // earn an entry in the pom's reflectionList (§5.8 item 1). Two classes registered for a
        // local variable would be two nobody could later explain.
        if (event.getEventType() == ScrollEvent.SCROLL_STARTED) {
            stopCoasting();
            panMomentum.began();
            panningRoom = true;
        } else if (event.getEventType() == ScrollEvent.SCROLL_FINISHED) {
            panningRoom = false;
            if (panMomentum.released(nowMs())) {
                coastClock.start();
            }
        }
    }

    /**
     * Records how far the room actually moved, for {@link PanMomentum} to take a speed from.
     *
     * <p>One axis per call, because the two properties change independently and a diagonal pan
     * reports them as two events at almost the same moment. {@code PanMomentum} sums each axis on
     * its own, so splitting them costs nothing.
     */
    private void samplePan(boolean horizontal, double before, double after) {
        if (!panningRoom) {
            return;
        }
        double span = panSpan(horizontal);
        if (span <= 0) {
            return;                       // it all fits; there is nothing to pan and nothing to time
        }
        double movedPx = (after - before) * span;
        if (horizontal) {
            panMomentum.moved(movedPx, 0, nowMs());
        } else {
            panMomentum.moved(0, movedPx, nowMs());
        }
    }

    /** Moves the room by one frame of the coast, and stops when there is nowhere left to go. */
    private void glideBy(double dx, double dy) {
        boolean movedHorizontally = glideAxis(dx, true);
        boolean movedVertically = glideAxis(dy, false);
        if (!movedHorizontally && !movedVertically) {
            // Both axes are against their stops. Carrying on would burn frames moving nothing, and
            // the room would sit there "coasting" until the decay ran out.
            stopCoasting();
        }
    }

    /** Applies one axis of a coast frame. Returns whether the room actually moved. */
    private boolean glideAxis(double pixels, boolean horizontal) {
        double span = panSpan(horizontal);
        if (span <= 0) {
            return false;
        }
        double current = horizontal ? getHvalue() : getVvalue();
        double wanted = Math.max(0, Math.min(1, current + pixels / span));
        if (wanted == current) {
            return false;
        }
        if (horizontal) {
            setHvalue(wanted);
        } else {
            setVvalue(wanted);
        }
        return true;
    }

    /** Ends any coast at once and forgets the speed behind it. */
    private void stopCoasting() {
        coastClock.stop();
        panMomentum.stop();
    }

    /** How many content pixels the room can be panned along one axis, at the current Fit scale. */
    private double panSpan(boolean horizontal) {
        double content = (horizontal ? Units.feetToPx(state.room.w) : Units.feetToPx(state.room.l))
                * fitScaleFactor();
        double viewport = horizontal
                ? getViewportBounds().getWidth()
                : getViewportBounds().getHeight();
        return scrollableSpan(content, viewport);
    }

    /**
     * The pixels a scrollbar's 0 to 1 covers: the content plus its margins, less what is on screen.
     *
     * <p>Its own method so that {@link #scrollAxis} and the coast share one copy of it. They have
     * to agree: one converts a fraction to pixels to decide where to scroll to, the other converts
     * pixels to a fraction to keep a fling moving, and a difference between them would show up as
     * a coast that drifts at a speed the finger never had.
     */
    private static double scrollableSpan(double content, double viewport) {
        return content + Tokens.CANVAS_MARGIN * 2 - viewport;
    }

    /**
     * Puts a scroll value back where the drag found it. See the listeners in the constructor.
     *
     * @param horizontal which of the two moved
     * @param moved      where it has just been moved to
     */
    private void holdScroll(boolean horizontal, double moved) {
        if (activeTouchId == null || restoringScroll) {
            return;
        }
        double held = horizontal ? heldHvalue : heldVvalue;
        if (moved == held) {
            return;
        }
        restoringScroll = true;
        try {
            if (horizontal) {
                setHvalue(held);
            } else {
                setVvalue(held);
            }
        } finally {
            restoringScroll = false;
        }
    }

    private void dragTo(Item item, Rectangle rect, double roomX, double roomY) {
        Collision.Point allowed = Collision.clampItem(state, item, roomX, roomY);
        item.x_px = allowed.x();
        item.y_px = allowed.y();
        rect.setX(allowed.x());
        rect.setY(allowed.y());

        // The highlight is its own node, so it has to be dragged along by hand or it stays
        // at the position the drag started from until the release-time refresh moves it.
        if (item.id.equals(selectedId)) {
            positionSelectionOutline(item, selectionOutlines.get(item.id));
        }
        if (item.id.equals(holdRingItemId)) {
            positionHoldRing(item);
        }
    }

    /**
     * Records a finished move and lets the rest of the app know about it.
     *
     * <p>Shared by the mouse and the touch path. It exists as one method precisely so the touch
     * gestures could not quietly grow their own version that forgot one of these five steps:
     * every one of them matters and none of them is obvious from the outside.
     */
    private void commitDrag(Item item, UndoEntry pendingUndo) {
        // Only a real drag is worth an undo entry. A click that moved two pixels and
        // snapped back is not something anyone wants to undo.
        undoHistory.push(pendingUndo);

        // The item just moved, so it becomes the most recently touched one, which is
        // what puts it on top of anything it now overlaps.
        //
        // Taking the highest existing value into account is a deliberate, minimal
        // departure from the original, and it fixes a real bug in files we promised to
        // support. The original just incremented its counter. That counter is saved in
        // the file, but a file written before the dragOrder field existed has no counter
        // at all, so it loads as 0 while the items themselves get drag orders taken from
        // their serial numbers: 7, 8, and so on. Incrementing 0 to 1 then leaves the
        // box the user just dragged *below* everything else, and stacking stays wrong
        // until they have dragged as many times as there are items.
        //
        // When the counter is consistent (every file the current app writes), the
        // maximum is the counter itself and this behaves identically.
        state.dragOrderCounter = Math.max(state.dragOrderCounter, highestDragOrder()) + 1;
        item.dragOrder = state.dragOrderCounter;
        Stacking.recomputeAllBaseHeights(state);
        refreshItemAppearance();
        onDragCommitted.run();
    }

    // ------------------------------------------------------------------- touch

    /** The clock the gestures and the ring share. Milliseconds, matching {@link TouchGesture}. */
    private static double nowMs() {
        return System.nanoTime() / 1_000_000.0;
    }

    /**
     * A finger landed on a box.
     *
     * <p>Nothing is decided here. All four meanings (drag, tap, double tap and delete) begin
     * identically, and which one it was is not known until the finger lifts or the ring finishes.
     * So this records everything any of them might need and starts the clock.
     */
    private void touchBegan(Item item, TouchGesture gesture, TouchEvent event) {
        if (event.getTouchCount() != 1) {
            // A second finger means this was never a gesture on this box. The original bails the
            // same way; two-finger gestures belong to the view, not to an item.
            cancelTouch();
            event.consume();
            return;
        }

        touchScale = fitScaleFactor();
        TouchPoint point = event.getTouchPoint();
        touchStartX = point.getSceneX() / touchScale;
        touchStartY = point.getSceneY() / touchScale;
        touchStartItemX = item.x_px;
        touchStartItemY = item.y_px;
        activeTouchId = item.id;
        heldHvalue = getHvalue();
        heldVvalue = getVvalue();

        // Before anything moves, exactly as the mouse path does: that is what makes undo restore
        // the arrangement that existed beforehand rather than the one halfway through the drag.
        pendingTouchUndo = new UndoEntry.Moved(item.id, item.x_px, item.y_px, item.dragOrder,
                UndoHistory.snapshotHeights(state));

        gesture.began(touchStartX, touchStartY, nowMs());
        startHold(item);
        event.consume();
    }

    /** The finger moved: past six room pixels this is a drag, and the delete is called off. */
    private void touchMoved(Item item, Rectangle rect, TouchGesture gesture, TouchEvent event) {
        if (event.getTouchCount() != 1 || !item.id.equals(activeTouchId)) {
            // Consumed on the way out, not merely ignored, and this is the one path in the three
            // touch handlers that used to return without doing so. A touch that began on a box is
            // never the room's to scroll with, whatever this app decides to do with it, and letting
            // one bubble hands it to ScrollPaneSkin, which keeps the movement and flings on release.
            // Its siblings touchBegan and touchEnded both consume on every path already.
            event.consume();
            return;
        }
        TouchPoint point = event.getTouchPoint();

        // Divided by the scale BEFORE the threshold is tested, not after. The six pixels are six
        // *room* pixels, so with Fit shrinking the room a finger has to travel further across the
        // glass to mean the same thing, which is what keeps the box under the fingertip. Testing
        // first and dividing afterwards would make the threshold change with the zoom.
        double roomX = point.getSceneX() / touchScale;
        double roomY = point.getSceneY() / touchScale;

        if (gesture.moved(roomX, roomY)) {
            dragTo(item, rect, touchStartItemX + (roomX - touchStartX),
                    touchStartItemY + (roomY - touchStartY));
        }
        event.consume();
    }

    /**
     * The finger lifted. Now the gesture can say what it was.
     *
     * <p><b>Why there is no "is this still the live gesture?" check here, when there nearly was.</b>
     * Only one move is held pending at a time, so a box lifting after something else had taken over
     * would commit a move recorded against the <em>other</em> box, and once that entry was
     * cleared, push a null one and throw. That happened, and a test caught it. But the cause was
     * not a missing check: it was {@link #endHold()} clearing {@code activeTouchId}, which made
     * {@code cancelTouch} unable to find the gesture it was supposed to cancel, because the finger
     * moving ends the <em>hold</em> long before it ends the gesture. With those two separated, a
     * gesture that loses its claim is always canceled, so by the time it lifts it reports
     * {@code NOTHING} of its own accord. A guard on top of that was one no test could reach.
     */
    private void touchEnded(Item item, Rectangle rect, TouchGesture gesture, TouchEvent event) {
        TouchGesture.Outcome outcome = gesture.ended(nowMs());
        endHold();

        switch (outcome) {
            case DRAG -> {
                commitDrag(item, pendingTouchUndo);
                // ⚠ ARMED ONLY BY A REAL DRAG. The gesture is over for this app and NOT over for
                // the platform: Glass's recognizer keeps sending inertia for up to 1.5 s after the
                // finger lifts, which is what turned the pan into momentum once the drag-time guard
                // let go. A TAP must not arm it: a tap moves nothing and has no inertia to
                // suppress, and arming there would cost a second and a half of not being able to
                // scroll the room after merely touching a box.
                inertiaFromBoxDrag.playFromStart();
            }
            case TAP -> tapItem(item, rect);
            case DOUBLE_TAP -> {
                onHoverEnded.run();
                onItemActivated.accept(item);
            }
            // The dead band, or a gesture already spent on a delete. Deliberately nothing: lifting
            // between 600 ms and 1500 ms is how a delete you did not mean to start is called off,
            // and it would be no use if it did something instead.
            case NOTHING -> { }
        }
        clearTouch();
        event.consume();
    }

    /**
     * A quick tap: say what the box is and select it.
     *
     * <p>Not the edit dialog: that is the double tap. A finger has no hover, so the tap is the
     * only way to ask "what is this box?", and answering with a dialog would make that impossible.
     */
    private void tapItem(Item item, Rectangle rect) {
        setSelectedId(item.id);
        onSelectionChanged.run();

        // The middle of the box's top edge, ten pixels above it: the original's anchor exactly.
        // A fingertip covers the thing it is touching, so the label goes above rather than under.
        Bounds inScene = rect.localToScene(rect.getBoundsInLocal());
        onItemTapped.hover(item, (inScene.getMinX() + inScene.getMaxX()) / 2,
                inScene.getMinY() - 10);
    }

    /** Puts the warning ring around a box and starts the fade. */
    private void startHold(Item item) {
        holdRingItemId = item.id;
        holdRing.setOpacity(Tokens.HOLD_RING_MIN_OPACITY);
        positionHoldRing(item);
        applyPaintOrder();
        holdClock.start();
    }

    /**
     * Takes the ring off and stops the clock. Every way a hold can end comes through here.
     *
     * <p>{@code AnimationTimer.stop()} is safe to call when it is not running, so this can be
     * called from more places than strictly need it, which is the point, because a ring left on
     * screen after the finger has gone is a bug that only shows up on hardware.
     */
    private void endHold() {
        holdClock.stop();
        if (holdRingItemId != null) {
            holdRingItemId = null;
            itemLayer.getChildren().remove(holdRing);
        }
        // Deliberately does NOT clear activeTouchId. Ending the hold and ending the gesture are
        // different things: the very first thing a drag does is call this, because the moment the
        // finger moves there is no delete pending any more, and the drag then carries on for as
        // long as the finger does. Clearing the owner here made every finger drag abandon itself.
    }

    /** Forgets whose gesture is live, and the move it was going to record. */
    private void clearTouch() {
        activeTouchId = null;
        pendingTouchUndo = null;
    }

    /** Abandons a gesture in progress: the boxes are being replaced, or a second finger arrived. */
    private void cancelTouch() {
        if (activeTouchId != null) {
            TouchGesture gesture = itemGestures.get(activeTouchId);
            if (gesture != null) {
                gesture.canceled();
            }
        }
        endHold();
        clearTouch();
    }

    /**
     * Sizes the warning ring around a box.
     *
     * <p>Deliberately the same shape of arithmetic as {@link #positionSelectionOutline}: a
     * rectangle inflated by the offset, with the stroke drawn <em>inside</em> it. The original's
     * ring is a child inset by −5 px carrying a 3 px border under {@code box-sizing: border-box},
     * which puts the red between 5 px and 2 px outside the box; that is what this produces.
     */
    private void positionHoldRing(Item item) {
        double inset = Tokens.HOLD_RING_INSET_ITEM;
        holdRing.setX(item.x_px - inset);
        holdRing.setY(item.y_px - inset);
        holdRing.setWidth(Units.inchesToPx(item.w_in) + inset * 2);
        holdRing.setHeight(Units.inchesToPx(item.l_in) + inset * 2);
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
            // already a departure from to-scale (that is what it is for), so there the room
            // should keep filling the viewport whatever the zoom.
            fitScale.setX(1 / uiScale);
            fitScale.setY(1 / uiScale);
            holder.setPadding(new Insets(Tokens.CANVAS_MARGIN));

            // §5.5 D-3, amended: a phone gets no scrollbars on the room at all. The vertical one
            // is too thin to catch on a waterfall-edge screen and widening it would spend width a
            // phone has not got, and since B9 there is nothing left for it to do that dragging and
            // the coast do not already do. Desktop keeps AS_NEEDED, where a pointer can hit a
            // 12 px bar and there is width to spare.
            //
            // Not the same call as Fit mode's below. That one hides them because nothing CAN be
            // off-screen, so a bar would be a lie; this one hides a bar that would tell the truth
            // and be useless. Two reasons, so two places, and neither should be folded into the
            // other.
            ScrollBarPolicy bars = Device.isTouch() ? ScrollBarPolicy.NEVER : ScrollBarPolicy.AS_NEEDED;
            setHbarPolicy(bars);
            setVbarPolicy(bars);
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
