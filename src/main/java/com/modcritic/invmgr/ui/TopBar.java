package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.engine.TextFormat;
import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Room;
import com.modcritic.invmgr.model.Units;
import java.util.List;
import java.util.function.Consumer;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Duration;

/**
 * The bar across the top: the room's dimensions, and the buttons that change modes.
 *
 * <p><b>Each mode button has its own color</b>: Fit blue, Plan purple, Units green, Layer
 * Collision amber, so a glance tells you which modes are on without reading any labels. That is
 * a stated design principle, not decoration, and the four must stay distinct.
 *
 * <p>Buttons appear here as the features behind them are built. An inert button is worse than a
 * missing one: it looks like something that should work. The <b>3D button is deliberately
 * absent</b> until M5 gives it a 3D view to open (your call, 2026-07-27).
 */
public final class TopBar extends FlowPane {

    private AppState state;

    private final NumberField widthField = new NumberField(Room.MIN_W, Room.MAX_W);
    private final NumberField lengthField = new NumberField(Room.MIN_L, Room.MAX_L);
    private final NumberField heightField = new NumberField(Room.MIN_H, Room.MAX_H);

    private final Label widthLabel = controlLabel("W(ft):");
    private final Label lengthLabel = controlLabel("L(ft):");
    private final Label heightLabel = controlLabel("H(ft):");

    private final Button setRoom = button("Set Room", Tokens.BUTTON_SET_ROOM_BG);
    private final Button layerCollision = button("Layer Collision", Tokens.BUTTON_BG);
    private final Button save = button("Save", Tokens.BUTTON_BG);
    private final Button load = button("Load", Tokens.BUTTON_BG);
    private final Button threeD = threeDButton();
    private final Button add = addButton();
    private final Button undo = button("Undo", Tokens.BUTTON_UNDO_BG);
    private final Button fit = button("Fit", Tokens.BUTTON_TOGGLE_BG);
    private final Button plan = button("Plan", Tokens.BUTTON_TOGGLE_BG);
    private final Button units = button("Units", Tokens.BUTTON_TOGGLE_BG);

    /** Called when the room's size changes, so the canvas can redraw and the slider rebuild. */
    private Runnable onRoomChanged = () -> { };

    /** Called when a mode is toggled, with a message for the status bar. */
    private Consumer<String> onStatus = message -> { };

    private Runnable onLayerCollisionChanged = () -> { };
    private Runnable onFitChanged = () -> { };
    private Runnable onUnitsChanged = () -> { };
    private Runnable onPlanChanged = () -> { };
    private Runnable onAdd = () -> { };
    private Runnable onSave = () -> { };
    private Runnable onUndo = () -> { };
    private Runnable onLoad = () -> { };
    private Runnable onThreeD = () -> { };

    /** Whether this bar is laid out for fingers, decided once and never asked again. */
    private final boolean touch = Device.isTouch();

    public TopBar(AppState state) {
        this.state = state;

        setHgap(Tokens.TOP_BAR_GAP);
        setVgap(Tokens.TOP_BAR_GAP);
        setAlignment(touch ? Pos.CENTER : Pos.CENTER_LEFT);
        setPadding(barPadding(1));
        // The bar's color and its bottom rule belong to TopWrap, which is where the original
        // puts them too: #top-wrap carries the background and the border, #top-bar carries only
        // its own padding. That is not a tidy-up: the bar folds away to nothing on touch, and a
        // background that folded with it would take the island's backdrop down as well.

        if (touch) {
            getChildren().addAll(
                    row(field(widthLabel, widthField),
                            field(lengthLabel, lengthField),
                            field(heightLabel, heightField)),
                    row(setRoom, layerCollision),
                    row(save, load, threeD));
            // Add, Undo, Fit, Plan and Units are deliberately absent: they are the island's, and
            // the island borrows these very objects rather than building five more.
            // The content box, not the border box: this bar carries 12 px of padding each side on
            // touch, and a cqi is one percent of what is inside it. See Fluid.queryWidth.
            widthProperty().addListener(
                    (observable, before, after) -> applyTouchType(Fluid.queryWidth(this)));
            applyTouchType(Fluid.queryWidth(this));
        } else {
            getChildren().addAll(
                    field(widthLabel, widthField),
                    field(lengthLabel, lengthField),
                    field(heightLabel, heightField),
                    setRoom, layerCollision, save, load, threeD, add, undo, fit, plan, units);
        }

        // Folding is set up on every platform, not only on touch. Only a phone ever asks for it,
        // but wiring it behind the same switch that decides the layout would leave the fold
        // testable in exactly one configuration and silently inert in the other; the whole
        // point of Device's override is that a desktop can be made to answer either way.
        clipToOwnBox();
        extent.addListener((observable, before, after) -> applyExtent(after.doubleValue()));

        wireControls();
    }

    /**
     * One forced row of the touch layout.
     *
     * <p><b>This is the piece CSS does in one line and JavaFX cannot do at all.</b> The original
     * writes {@code .row-break { flex-basis: 100%; height: 0 }}, a zero-height item as wide as the
     * bar, which pushes everything after it onto a new flex line whatever the pixel widths happen
     * to be. {@code FlowPane} has no such thing, and no way to say "break here".
     *
     * <p>So the rows are made instead of the breaks: each group goes in a box that is as wide as
     * the bar's content, which leaves no room beside it and puts each on a line of its own. Same
     * result, and it survives a rotation or a font change, where anything tuned to a pixel width
     * would not.
     */
    private HBox row(Node... pieces) {
        HBox line = new HBox(pieces);
        line.setAlignment(Pos.CENTER);
        line.spacingProperty().bind(hgapProperty());
        line.prefWidthProperty().bind(
                widthProperty().subtract(Tokens.TOP_BAR_PADDING_H_TOUCH * 2));
        return line;
    }

    /**
     * How narrow the bar may get, which {@code FlowPane} would otherwise answer catastrophically.
     *
     * <p>A {@code FlowPane}'s minimum width is <b>its widest child's preferred width</b>. Every
     * touch row is deliberately as wide as the bar, so left alone that is a loop: the bar's minimum
     * is its own current width, and it can therefore never get narrower than the widest it has ever
     * been. The app opens at 1280 and a phone is 360, so the loop latches on the first frame.
     *
     * <p>What that looked like is worth recording, because none of it named the cause. The window
     * was 360 px. The layer holding the interface was 360 px. Inside it the entire interface
     * (top bar, island, status bar) was <b>1280</b>, running off the right of the screen; and the
     * island, sizing its type from its own width as it is supposed to, dressed itself for a screen
     * a meter wide. The visible symptom was a font one pixel too big.
     *
     * <p>So the answer here is the honest one: the widest row's own <em>minimum</em>. That is what
     * the bar genuinely cannot go below, and it owes nothing to how wide the bar happens to be.
     */
    @Override
    protected double computeMinWidth(double height) {
        if (!touch) {
            return super.computeMinWidth(height);
        }
        double widest = 0;
        for (Node child : getManagedChildren()) {
            widest = Math.max(widest, child.minWidth(-1));
        }
        return getInsets().getLeft() + widest + getInsets().getRight();
    }

    /**
     * Re-sizes everything in the bar from the bar's own width.
     *
     * <p>{@link Fluid} explains why this is a listener rather than a stylesheet. What matters here
     * is <em>what</em> is fluid and what is not: the labels and the number fields are, because the
     * three of them have to share one row on a screen that might be 320 px across; the buttons are
     * not, because 16 px on 8 × 14 padding is the size a finger needs and shrinking it to fit would
     * defeat the point of the touch layout.
     */
    private void applyTouchType(double barWidth) {
        setHgap(Fluid.size(barWidth, Tokens.TOP_BAR_GAP_MIN, Tokens.TOP_BAR_GAP_PERCENT,
                Tokens.TOP_BAR_GAP_MAX));
        setVgap(getHgap());

        double controlFont = Fluid.size(barWidth, Tokens.FONT_CONTROL_TOUCH_MIN,
                Tokens.FONT_CONTROL_TOUCH_PERCENT, Tokens.FONT_CONTROL_TOUCH_MAX);
        for (Label label : new Label[] {widthLabel, lengthLabel, heightLabel}) {
            label.setFont(Font.font(Tokens.FONT_FAMILY, controlFont));
        }
        for (NumberField input : new NumberField[] {widthField, lengthField, heightField}) {
            input.useTouchType(controlFont, Tokens.ROOM_FIELD_CHARS_TOUCH);
        }
        for (Button button : new Button[] {setRoom, layerCollision, save, load}) {
            sizeForTouch(button, Tokens.FONT_BUTTON_TOUCH, Tokens.BUTTON_PADDING_V_TOUCH,
                    Tokens.BUTTON_PADDING_H_TOUCH);
        }

        // The 3D button was missing from that list, and it sits on the same row as Save and Load,
        // so it kept the desktop's 30 x 28 while they grew, which is exactly what the user saw and
        // reported as "the 3D button is weirdly smaller than usual".
        //
        // It is not in the loop because it is not a word: its label is the user's own cube drawing,
        // so its box is the icon plus padding rather than a line of text plus padding, and pinning a
        // text line box around a graphic would be wrong twice over. The numbers need no invention:
        // the original's 51.0 x 38.7 is exactly a 1.3 em icon inside this bar's own touch padding.
        threeD.setMinSize(Button.USE_COMPUTED_SIZE, Button.USE_COMPUTED_SIZE);
        threeD.setPrefSize(Button.USE_COMPUTED_SIZE, Button.USE_COMPUTED_SIZE);
        threeD.setMaxSize(Button.USE_COMPUTED_SIZE, Button.USE_COMPUTED_SIZE);
        threeD.setGraphic(Icons.threeD(Tokens.ICON_3D_SIZE_TOUCH));
        threeD.setPadding(new Insets(Tokens.BUTTON_PADDING_V_TOUCH, Tokens.BUTTON_PADDING_H_TOUCH,
                Tokens.BUTTON_PADDING_V_TOUCH, Tokens.BUTTON_PADDING_H_TOUCH));
    }

    /**
     * Keeps the bar's contents inside the bar while it is folding away.
     *
     * <p>The original gets this free from {@code #top-bar { overflow: hidden }}. JavaFX draws a
     * child wherever it is regardless of the parent's size, so without this the buttons would hang
     * below a bar of height twelve and sit on top of the island.
     */
    private void clipToOwnBox() {
        Clips.toOwnBox(this);
    }

    /**
     * The bar's own padding, faded out along with everything else as it folds away.
     *
     * <p>The original animates {@code padding} beside {@code max-height}, and it has to: a bar
     * whose height was going to zero while it still held 7 px of padding top and bottom would stop
     * fourteen pixels short and leave a stripe.
     *
     * <p>Note that the phone's status-bar inset is <b>not</b> in here. That space belongs to
     * {@code TopWrap}, precisely because this padding disappears when the bar folds and the space
     * behind the clock must not.
     *
     * @param extent 1 when the bar is fully out, 0 when it is folded away
     */
    private Insets barPadding(double extent) {
        double v = extent * (touch ? Tokens.TOP_BAR_PADDING_V_TOUCH : Tokens.TOP_BAR_PADDING_V);
        double h = touch ? Tokens.TOP_BAR_PADDING_H_TOUCH : Tokens.TOP_BAR_PADDING_H;
        return new Insets(v, h, v, h);
    }

    /**
     * How much of the bar is currently showing: 1 fully out, 0 folded away to nothing.
     *
     * <p>A single number driving height, padding and opacity together, because those three are one
     * gesture and letting them drift apart is how you get a bar that is invisible but still taking
     * up space.
     */
    private final DoubleProperty extent = new SimpleDoubleProperty(1);

    /** The height the bar wants, measured the moment before it starts folding. */
    private double naturalHeight;

    private Timeline fold;


    /**
     * Folds the bar away, or brings it back.
     *
     * <p>On a phone the bar starts folded, so the room gets the glass and the six-button island is
     * all that is between the clock and the canvas. The island's {@code ▼} is what calls this.
     *
     * @param collapsed whether the bar should be out of the way
     * @param animated  false when setting the opening state, where an animation would be a flicker
     */
    public void setCollapsed(boolean collapsed, boolean animated) {
        if (fold != null) {
            fold.stop();
        }
        double target = collapsed ? 0 : 1;
        naturalHeight = measureNaturalHeight();
        if (!animated) {
            setOpacity(target);
            extent.set(target);
            applyExtent(target);
            return;
        }
        fold = new Timeline(
                new KeyFrame(Duration.millis(Tokens.TOP_BAR_COLLAPSE_FADE_MS),
                        new KeyValue(opacityProperty(), target, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(Tokens.TOP_BAR_COLLAPSE_MS),
                        new KeyValue(extent, target, Interpolator.EASE_BOTH)));

        fold.play();
    }


    /** Whether the bar is currently folded away. */
    public boolean isCollapsed() {
        return extent.get() <= 0;
    }

    /**
     * Reads back the height the bar would take if nothing were pinning it.
     *
     * <p>Has to unpin it to ask, because {@code prefHeight} is exactly the thing being animated:
     * asking while it is pinned to 40 would answer 40 and the bar would never grow past whatever it
     * happened to be when the last fold stopped.
     */
    private double measureNaturalHeight() {
        double pinned = getPrefHeight();
        Insets held = getPadding();
        // ⚠ THE PADDING HAS TO GO BACK ON BEFORE MEASURING, and leaving it off is what made the
        // fold stagger. This runs from setCollapsed, which is usually called while the bar is
        // ALREADY folded, and a folded bar's padding has been animated to zero, because the
        // original transitions padding alongside max-height. So the height came back 116 where the
        // open bar is 136, missing exactly the 10 px at each end.
        //
        // What that looked like: the bar rose smoothly to 116, sat there while the rest of the
        // animation ran, then jumped the last 20 px when `showing` reached 1 and the branch above
        // released it to its computed size. Reported as "hangs a little longer on the
        // second-to-last frame of movement", which is precisely what it was, and not a timing
        // problem at all.
        setPadding(barPadding(1));
        setPrefHeight(USE_COMPUTED_SIZE);
        applyCss();
        double natural = prefHeight(getWidth() > 0 ? getWidth() : USE_COMPUTED_SIZE);
        setPrefHeight(pinned);
        setPadding(held);
        return natural;
    }

    /**
     * How tall the bar is part-way through a fold, <b>as a {@code max-height}, which is what makes
     * the movement finish early and is why the original looks quicker.</b>
     *
     * <p>On 2026-08-14 the user compared the fold with the original and asked for it to be about
     * twice as fast. They are right that it is slower, and the reason is not the duration: both
     * are 250 ms. The original animates {@code max-height: 200px → 0}, and its bar is not 200 px
     * tall; on a phone it is around 136. A maximum above the content's own height changes nothing
     * anyone can see, so <b>the first third of the original's transition is invisible</b> and the
     * visible movement is over in about {@code 250 × 136/200 ≈ 170 ms}.
     *
     * <p>Reproducing that exactly is better than shortening the animation, and not only for
     * fidelity: the easing comes out right too. Cut the duration instead and the bar would run a
     * whole ease-in-out curve in the time the original spends on the middle of one, which reads as
     * a different movement rather than a faster one.
     *
     * <p>So the property still runs its full 250 ms and this clamps what the bar does with it.
     * Padding is deliberately NOT clamped: the original transitions that over the whole 0.25 s,
     * and it is a few pixels closing after the bar has arrived.
     */
    private double foldedHeight(double showing) {
        return Math.min(naturalHeight, showing * Tokens.TOP_BAR_MAX_HEIGHT);
    }

    private void applyExtent(double showing) {
        setPadding(barPadding(showing));
        // Not just invisible: a folded bar must not take a tap meant for the canvas underneath it,
        // and JavaFX will happily deliver one to a node of zero height that is still pickable.
        setMouseTransparent(showing <= 0);
        setVisible(showing > 0);
        if (showing >= 1) {
            // Released rather than pinned at the measured number, so the bar re-wraps by itself
            // when the window turns sideways.
            setPrefHeight(USE_COMPUTED_SIZE);
            setMinHeight(USE_COMPUTED_SIZE);
        } else {
            setPrefHeight(foldedHeight(showing));
            // ⚠ AND THE MINIMUM FOLLOWS THE PREFERRED, which is the whole of the "delayed snap" the
            // user reported three builds running. A minimum of ZERO is not merely permission to
            // shrink: in a column with no room to spare it is permission to be REFUSED, and the
            // room below is exactly such a column on a phone. So the bar asked for 4 px, then 9,
            // then 15, and was granted none of them; it stayed flat until `showing` reached 1, at
            // which point the branch above raises the minimum to the content's own height and the
            // VBox finally has to give way. Hence: nothing, nothing, nothing, snap.
            //
            // The animation was never the problem and the diagnostics said so twice (13 to 19
            // frames in 250 ms on the phone, 168 frames here) while the height took exactly two
            // values, 0 and 116. An animated property that nothing grants is not an animation.
            //
            // Tying the floor to the same number keeps every reason the old comment gave: it is
            // still below the content's own minimum, so a FlowPane cannot stop the fold at one row
            // of buttons, and it still reaches 0 when the fold does.
            //
            // ⚠ ONLY while folding. Setting this once in the constructor and leaving it there cost
            // an afternoon: a VBox short of room shrinks its children towards their minimums, and
            // a room bigger than the window is exactly that. A minimum of zero made the top bar
            // legal to squash to nothing, and it was: 2560 px wide and 0 px tall, with its
            // buttons hanging above the top of the screen at y = −14, on a perfectly ordinary
            // desktop that had never folded anything. Found by measuring, not by reading.
            setMinHeight(foldedHeight(showing));
        }
    }

    /**
     * Hooks every control in the bar up to what it does, and attaches the hover hints.
     *
     * <p>Split out of the constructor at M6.1b only so that {@link #reserveTop} could sit between
     * the two without a hundred lines of wiring in the way. Nothing about it changed.
     */
    private void wireControls() {
        setRoom.setOnAction(event -> applyRoomFromFields());
        layerCollision.setOnAction(event -> toggleLayerCollision());
        add.setOnAction(event -> onAdd.run());
        undo.setOnAction(event -> onUndo.run());
        fit.setOnAction(event -> toggleFit());
        plan.setOnAction(event -> togglePlan());
        units.setOnAction(event -> toggleUnits());
        save.setOnAction(event -> onSave.run());
        load.setOnAction(event -> onLoad.run());
        threeD.setOnAction(event -> onThreeD.run());

        // The hints the original writes as HTML title attributes. Only the buttons whose name
        // does not already say what they do carry one. Four of these five are the original's
        // wording exactly; Layer Collision's is the user's own (see §5.5 D-7).
        Hints.attach(layerCollision,
                "Disable gravity, items collide based on height-range, not stack");
        Hints.attach(threeD, "3D View");
        Hints.attach(add, "Add Item");
        Hints.attach(fit, "Fit to Screen");
        Hints.attach(plan, "Planning Mode");
        Hints.attach(units, "Toggle Metric Units");

        // Enter in any of the three fields applies the room, which is what anyone typing a
        // number expects; reaching for the button every time would be tedious.
        widthField.setOnAction(event -> applyRoomFromFields());
        lengthField.setOnAction(event -> applyRoomFromFields());
        heightField.setOnAction(event -> applyRoomFromFields());

        refresh();
    }

    // -------------------------------------------------------------- wiring

    public void setOnRoomChanged(Runnable handler) {
        this.onRoomChanged = or(handler);
    }

    public void setOnStatus(Consumer<String> handler) {
        this.onStatus = handler == null ? message -> { } : handler;
    }

    public void setOnLayerCollisionChanged(Runnable handler) {
        this.onLayerCollisionChanged = or(handler);
    }

    public void setOnFitChanged(Runnable handler) {
        this.onFitChanged = or(handler);
    }

    public void setOnUnitsChanged(Runnable handler) {
        this.onUnitsChanged = or(handler);
    }

    public void setOnPlanChanged(Runnable handler) {
        this.onPlanChanged = or(handler);
    }

    public void setOnAdd(Runnable handler) {
        this.onAdd = or(handler);
    }

    public void setOnUndo(Runnable handler) {
        this.onUndo = or(handler);
    }

    public void setOnSave(Runnable handler) {
        this.onSave = or(handler);
    }

    public void setOnLoad(Runnable handler) {
        this.onLoad = or(handler);
    }

    public void setOnThreeD(Runnable handler) {
        this.onThreeD = or(handler);
    }

    /**
     * Grays the 3D button out and says why, when this machine cannot draw 3D at all.
     *
     * <p>Some machines have no usable 3D: an old graphics driver, a remote desktop session, a
     * virtual machine. On those, JavaFX quietly falls back to drawing in software, where a 3D scene
     * produces <b>nothing at all and reports no error</b>: a black rectangle and no clue why.
     *
     * <p>So the button says so instead of pretending. The flat view is the app's main interface and
     * keeps working exactly as before; losing the whole program because a graphics driver is old
     * would be a far worse answer than losing one button.
     *
     * <p><b>This is a state the original never had.</b> It was a web page and simply assumed the
     * browser could manage; there was nothing to check and nowhere to report it. See CLAUDE.md
     * §5.5 for the divergence entry.
     */
    public void setThreeDUnavailable(String reason) {
        threeD.setDisable(true);
        Hints.attach(threeD, reason);
    }

    private static Runnable or(Runnable handler) {
        return handler == null ? () -> { } : handler;
    }

    // ------------------------------------------------------------- behavior

    /** Points the bar at a freshly loaded room. */
    public void setState(AppState newState) {
        this.state = newState;
        refresh();
    }

    /** Re-reads everything from the state: field values, unit labels, and which modes are on. */
    public void refresh() {
        boolean metric = state.metricMode;
        widthLabel.setText(metric ? "W(m):" : "W(ft):");
        lengthLabel.setText(metric ? "L(m):" : "L(ft):");
        heightLabel.setText(metric ? "H(m):" : "H(ft):");

        applyFieldRanges(metric);

        widthField.setText(displayValue(state.room.w));
        lengthField.setText(displayValue(state.room.l));
        heightField.setText(displayValue(state.room.h));

        style(layerCollision, state.layerCollision, Tokens.BUTTON_BG, Tokens.TOGGLE_LAYER_BG,
                Tokens.TOGGLE_LAYER_BORDER, Tokens.TOGGLE_LAYER_TEXT);
        style(plan, state.planMode, Tokens.BUTTON_TOGGLE_BG, Tokens.TOGGLE_PLAN_BG,
                Tokens.TOGGLE_PLAN_BORDER, Tokens.TOGGLE_PLAN_TEXT);
        style(units, state.metricMode, Tokens.BUTTON_TOGGLE_BG, Tokens.TOGGLE_UNITS_BG,
                Tokens.TOGGLE_UNITS_BORDER, Tokens.TOGGLE_UNITS_TEXT);
    }

    /**
     * Puts the steppers into the unit currently on screen.
     *
     * <p>Easy to forget, and wrong in a way that is hard to spot: the fields show meters in
     * metric, so a stepper still nudging by half (half a <em>meter</em>, more than a foot and a
     * half) would jump the room in unusable steps. The ceiling moves too, since 200 feet is
     * 61 meters, not 200 of them.
     */
    private void applyFieldRanges(boolean metric) {
        double step = metric ? METRIC_STEP : IMPERIAL_STEP;
        widthField.setStep(step);
        lengthField.setStep(step);
        heightField.setStep(step);

        // Rounded, exactly as the original rounds its max attribute: 61 m rather than 60.96.
        double maxPlan = metric ? Math.round(Units.ftToM(Room.MAX_W)) : Room.MAX_W;
        double maxHeight = metric ? Math.round(Units.ftToM(Room.MAX_H)) : Room.MAX_H;
        widthField.setRange(Room.MIN_W, maxPlan);
        lengthField.setRange(Room.MIN_L, maxPlan);
        heightField.setRange(Room.MIN_H, maxHeight);
    }

    /** The room steppers nudge by half a foot, or a tenth of a meter. From the original. */
    private static final double IMPERIAL_STEP = 0.5;
    private static final double METRIC_STEP = 0.1;

    /** Reflects Fit mode, which lives on the canvas rather than in the saved state. */
    public void setFitActive(boolean active) {
        style(fit, active, Tokens.BUTTON_TOGGLE_BG, Tokens.TOGGLE_FIT_BG,
                Tokens.TOGGLE_FIT_BORDER, Tokens.TOGGLE_FIT_TEXT);
    }

    /**
     * Reads the three fields and resizes the room.
     *
     * <p>Anything unreadable falls back to the value already in use rather than to a default, so
     * a stray keystroke in one field cannot silently resize the room around it. Values are held
     * inside the legal range the save format allows.
     */
    private void applyRoomFromFields() {
        double w = parseDimension(widthField.getText(), state.room.w, Room.MIN_W, Room.MAX_W);
        double l = parseDimension(lengthField.getText(), state.room.l, Room.MIN_L, Room.MAX_L);
        double h = parseDimension(heightField.getText(), state.room.h, Room.MIN_H, Room.MAX_H);

        state.room.w = w;
        state.room.l = l;
        state.room.h = h;

        refresh();
        onRoomChanged.run();
        String unit = state.metricMode ? "m" : "ft";
        onStatus.accept("Room: " + displayValue(w) + unit + " x " + displayValue(l) + unit
                + " x " + displayValue(h) + unit);
    }

    private void toggleLayerCollision() {
        state.layerCollision = !state.layerCollision;
        refresh();
        onLayerCollisionChanged.run();
        onStatus.accept(state.layerCollision
                ? "Layer Collision ON: items keep their current layer."
                : "Layer Collision OFF: items settled to their correct layer.");
    }

    private void toggleFit() {
        onFitChanged.run();
    }

    /**
     * Turns Planning Mode on or off.
     *
     * <p>While it is on, adding a box creates a <b>ghost</b> instead: it appears in the item
     * list but not in the room, takes up no space and blocks nothing, until it is dragged out
     * of the list and dropped somewhere. It is for working out whether a thing will fit before
     * committing to where it goes.
     */
    private void togglePlan() {
        state.planMode = !state.planMode;
        refresh();
        onPlanChanged.run();
        onStatus.accept(state.planMode
                ? "Planning Mode ON: new items are planned (not placed)."
                : "Planning Mode OFF.");
    }

    private void toggleUnits() {
        state.metricMode = !state.metricMode;
        refresh();
        onUnitsChanged.run();
        onStatus.accept(state.metricMode
                ? "Units: Metric (m / cm)." : "Units: Imperial (ft / in).");
    }

    /**
     * Turns a stored feet value into what the field shows.
     *
     * <p>In metric the room is <b>displayed</b> in meters while still being <b>stored</b> in
     * feet; the conversion happens here, at the very edge of the interface, and nowhere else.
     */
    private String displayValue(double feet) {
        return TextFormat.number(state.metricMode ? Units.round3(Units.ftToM(feet)) : feet);
    }

    /** Reads a typed value, converting back from meters if that is what the user is seeing. */
    private double parseDimension(String text, double fallback, double min, double max) {
        try {
            double typed = Double.parseDouble(text.trim());
            double feet = state.metricMode ? Units.mToFt(typed) : typed;
            if (Double.isNaN(feet) || feet < min || feet > max) {
                return fallback;
            }
            return feet;
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }

    // -------------------------------------------------------------- building

    private HBox field(Label label, NumberField input) {
        HBox row = new HBox(4, label, input);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Label controlLabel(String text) {
        Label label = new Label(text);
        label.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_CONTROL));
        label.setTextFill(Tokens.TEXT_PRIMARY);
        return label;
    }

    /**
     * The square ■ that opens the Add Item dialog.
     *
     * <p>Bigger type than every other button, from the original's own rule for it: a single glyph
     * reads as a button rather than as a stray character only if it fills the space.
     *
     * <p>Its size is <b>pinned</b> rather than grown from that type, and the padding is
     * consequently zero. See {@link Tokens#ADD_BUTTON_WIDTH} for what went wrong when it was not.
     * Zero padding is not a look; with the box fixed, padding cannot change where a centered label
     * lands, and leaving it at the old 2 × 10 would have squeezed the content box narrower than
     * the glyph and invited an ellipsis.
     */
    private static Button addButton() {
        Button button = new Button("■");
        size(button, Tokens.FONT_ADD_BUTTON, 0, 0);
        pin(button, Tokens.ADD_BUTTON_WIDTH, Tokens.ADD_BUTTON_HEIGHT);
        applyButtonStyle(button, Tokens.BUTTON_ADD_BG, Tokens.CONTROL_BORDER,
                Tokens.TEXT_PRIMARY);
        onHover(button, () -> applyButtonStyle(button, Tokens.BUTTON_BG_HOVER,
                Tokens.CONTROL_BORDER, Tokens.TEXT_PRIMARY),
                () -> applyButtonStyle(button, Tokens.BUTTON_ADD_BG,
                        Tokens.CONTROL_BORDER, Tokens.TEXT_PRIMARY));
        return button;
    }

    /** Fixes a button's box at one size, so its type cannot move the bar's height. */
    private static void pin(Button button, double width, double height) {
        button.setMinSize(width, height);
        button.setPrefSize(width, height);
        button.setMaxSize(width, height);
    }

    /**
     * Lets a button size itself from its own type again.
     *
     * <p>The one caller is the island, which takes the pinned Add button and gives it a size that
     * changes with the screen. A box pinned at 30 × 28 around type that ranges from 15 px to 20
     * would clip the glyph at the top of that range and leave it swimming at the bottom.
     */
    private static void unpin(Button button) {
        button.setMinSize(Button.USE_COMPUTED_SIZE, Button.USE_COMPUTED_SIZE);
        button.setPrefSize(Button.USE_COMPUTED_SIZE, Button.USE_COMPUTED_SIZE);
        button.setMaxSize(Button.USE_COMPUTED_SIZE, Button.USE_COMPUTED_SIZE);
    }

    /**
     * The button that opens the 3D view.
     *
     * <p>Its label is the user's own isometric-cube drawing rather than text, so it is loaded from
     * {@code assets/icons/btn-3d.svg} rather than written out here; the icons are artwork and the
     * ground rules say to use the files.
     *
     * <p>Sized to match the Add button beside it, so the top bar's height is decided by the text
     * buttons and not by whichever icon happens to be tallest. That mattered at M3.4, where the Add
     * button being three pixels too big pushed the whole bar (and everything under it) down the
     * window.
     */
    private static Button threeDButton() {
        Button button = new Button();
        button.setGraphic(Icons.threeD(Tokens.ICON_3D_SIZE));
        button.setPadding(Insets.EMPTY);
        pin(button, Tokens.ADD_BUTTON_WIDTH, Tokens.ADD_BUTTON_HEIGHT);
        applyButtonStyle(button, Tokens.BUTTON_3D_BG, Tokens.CONTROL_BORDER,
                Tokens.TEXT_PRIMARY);
        onHover(button, () -> applyButtonStyle(button, Tokens.BUTTON_BG_HOVER,
                Tokens.CONTROL_BORDER, Tokens.TEXT_PRIMARY),
                () -> applyButtonStyle(button, Tokens.BUTTON_3D_BG,
                        Tokens.CONTROL_BORDER, Tokens.TEXT_PRIMARY));
        return button;
    }

    /**
     * A plain bar button: the app's own square-cornered style, with the hover that leaves an
     * active toggle's color alone.
     *
     * <p>Package-private so the island's {@code ▼} is built by the same line as the ten beside it
     * rather than restating the border, the radius and the two hover handlers a second time.
     */
    /**
     * Wires a button's hover styling, <b>and does nothing at all on a phone, because a finger does
     * not hover.</b>
     *
     * <p>Reported by the user 2026-08-14: folding the bar <em>down</em> left the Layer Collision
     * button lit as though it were selected, until the movement finished. Nothing was touching it.
     *
     * <p>Android manufactures a mouse from a finger, and that mouse keeps its last position, the
     * spot on the island where {@code ▼} was tapped. So the bar slides down, a button passes
     * <em>underneath</em> a pointer that is not really there, {@code MOUSE_ENTERED} fires, and the
     * hover color sticks. Only downward, because on the way up the buttons move away from that
     * point and the matching exit tidies up after them.
     *
     * <p>The cure is not to chase the exit event but to stop pretending there is a pointer. This app
     * has already made that decision once, in the status bar's own instructions: the desktop line
     * ends "Hover: tooltip" and the touch line does not, because a thumb cannot hover. Same fact,
     * same answer.
     */
    private static void onHover(Button button, Runnable entered, Runnable exited) {
        if (Device.isTouch()) {
            return;
        }
        button.setOnMouseEntered(event -> {
            if (button.getUserData() == null) {
                entered.run();
            }
        });
        button.setOnMouseExited(event -> {
            if (button.getUserData() == null) {
                exited.run();
            }
        });
    }

    static Button button(String text, Color background) {
        Button button = new Button(text);
        size(button, Tokens.FONT_CONTROL, Tokens.BUTTON_PADDING_V, Tokens.BUTTON_PADDING_H);
        applyButtonStyle(button, background, Tokens.CONTROL_BORDER, Tokens.TEXT_PRIMARY);

        // Hover only lifts a button that is not currently showing an active mode color;
        // otherwise hovering an active toggle would wash out the color that says it is on.
        onHover(button, () -> applyButtonStyle(button, Tokens.BUTTON_BG_HOVER,
                Tokens.CONTROL_BORDER, Tokens.TEXT_PRIMARY),
                () -> applyButtonStyle(button, background, Tokens.CONTROL_BORDER,
                        Tokens.TEXT_PRIMARY));
        return button;
    }

    /**
     * Switches a toggle between its resting color and its own active identity.
     *
     * <p>The active state is remembered on the button itself so the hover handlers above can
     * leave it alone.
     */
    private static void style(Button button, boolean active, Color restingBackground,
            Color activeBackground, Color activeBorder, Color activeText) {
        button.setUserData(active ? Boolean.TRUE : null);
        if (active) {
            applyButtonStyle(button, activeBackground, activeBorder, activeText);
        } else {
            applyButtonStyle(button, restingBackground, Tokens.CONTROL_BORDER,
                    Tokens.TEXT_PRIMARY);
        }
    }

    /**
     * Paints a button, and <b>says nothing about its padding</b>.
     *
     * <p>Padding used to be written into this same style string, and it could not stay there. The
     * touch island re-uses these very buttons at a different size, and every restyle (a hover, a
     * toggle going active, a refresh after a load) would have quietly stamped the desktop's
     * {@code 4 × 10} back over the island's. Color and size are two different questions asked at
     * two different times, so they are now set by two different calls.
     *
     * <p>Padding is set with {@code Region.setPadding} instead, which survives this because JavaFX
     * leaves a property alone once it has been set in code; a stylesheet rule cannot overwrite it,
     * only an inline style can, and there is no longer an inline padding to do it.
     */
    private static void applyButtonStyle(Button button, Color background, Color border,
            Color text) {
        button.setStyle("-fx-background-color: " + Tokens.hex(background) + ";"
                + "-fx-text-fill: " + Tokens.hex(text) + ";"
                + "-fx-border-color: " + Tokens.hex(border) + ";"
                + "-fx-border-width: 1;"
                // Square corners are the rule here; these are the app's own buttons, not
                // browser-native controls.
                + "-fx-background-radius: 0; -fx-border-radius: 0;");
    }

    /**
     * Sets one of this bar's buttons to a size, wherever it currently lives.
     *
     * <p>Package-private because {@code TouchIsland} is the other caller: it borrows five of these
     * buttons and re-types them as the screen width changes, and doing that through this one method
     * is what keeps there being exactly one way a button of this app's gets its size.
     */
    static void size(Button button, double fontSize, double paddingV, double paddingH) {
        button.setFont(Font.font(Tokens.FONT_FAMILY, fontSize));
        button.setPadding(new Insets(paddingV, paddingH, paddingV, paddingH));
    }

    /**
     * The same, plus the height the <em>original</em> gave a touch button rather than the one this
     * app's typeface asks for.
     *
     * <p>Touch only, and deliberately a separate method: the desktop has been signed off since
     * M5.3a and its buttons are sized against desktop reference screenshots taken of this app, so
     * nothing here may reach them. See {@link Tokens#TOUCH_TEXT_LINE_EM} for why the two differ and
     * for the measurements.
     *
     * <p><b>The minimum has to come down with the preferred height, or nothing happens at all.</b>
     * A {@code Button}'s computed minimum height is its own content, and a Region never lays out
     * below its minimum, so asking for a shorter preferred height and stopping there is silently
     * ignored, which is exactly what the first attempt at this did. {@code USE_PREF_SIZE} ties the
     * two together.
     *
     * <p>That is safe here in a way it was <em>not</em> for the bar itself: this pins a button to a
     * definite height, where the trouble recorded in {@link #applyExtent} came from granting a
     * minimum of <em>zero</em> and letting a short-of-space parent squash the whole bar away.
     */
    static void sizeForTouch(Button button, double fontSize, double paddingV, double paddingH) {
        size(button, fontSize, paddingV, paddingH);
        pinHeight(button, boxFor(Tokens.TOUCH_TEXT_LINE_EM, fontSize, paddingV));

        // The WIDTH has to be pinned once the label is a graphic, and this is the one thing drawing
        // the ink costs. A box sized by its content now follows the INK, and ink stops at the last
        // mark; it leaves out the side bearing that a browser (and JavaFX's own text layout) count
        // as part of the advance. Two to three pixels a button, every one of them narrower than the
        // reference that had just been made to match.
        Text advance = new Text(button.getText());
        advance.setFont(button.getFont());
        button.setPrefWidth(advance.getLayoutBounds().getWidth()
                + 2 * paddingH + 2 * Tokens.TOUCH_BUTTON_BORDER);
        button.setMinWidth(Button.USE_PREF_SIZE);

        drawLabelAsInk(button, Fonts.inkLabel(button.getText(), button.getFont(), button));
    }

    /**
     * Shows a button's label as a drawn node whose bounds are its ink, so the button centers what
     * can be seen rather than what the font declares.
     *
     * <p>{@code GRAPHIC_ONLY} rather than clearing the text: {@code getText()} has to keep
     * answering, because the tests, {@code Hints} and the drawer tabs all read it.
     *
     * <p>Re-applied every time a button is re-typed, which is what the island does on every width
     * change, so the node is replaced rather than mutated, and there is never a stale graphic
     * sized for a screen width that has been and gone.
     */
    private static void drawLabelAsInk(Button button, Text ink) {
        button.setGraphic(ink);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
    }

    /**
     * And the same again for a button whose label is a drawn character: the island's {@code ■} and
     * {@code ▼}, which the original rendered in a borrowed fallback face that is both taller and
     * much wider than a monospace one.
     *
     * <p>This is the only place the app sets a button's width from a font metric rather than letting
     * the text decide, and it is here because the text <em>cannot</em> decide: the face that drew
     * these two in the original is not the face that draws them here, and no padding tweak makes a
     * 0.6 em glyph occupy 0.95 em.
     */
    static void sizeGlyphForTouch(Button button, double fontSize, double paddingV, double paddingH) {
        size(button, fontSize, paddingV, paddingH);

        // ⚠ AND the glyph itself is drawn LARGER than the type around it. Widening the box was only
        // half of this: the box came out right and the mark inside it stayed small, which the user
        // reported as "box icon in Add Item button is too small". The original's borrowed face
        // covers about three quarters of an em with `■` where this monospace face covers under a
        // half, so at the same nominal size the mark is barely three fifths as big: 9 CSS px
        // against the original's 14.3.
        //
        // Set from the INK rather than from the nominal size, because the nominal size is exactly
        // what does not carry across between the two faces. Fonts.sizeForInkHeight measures it.
        //
        // Drawn as a graphic for the same reason as every other touch button, and here it is not
        // merely tidier, it is required. Enlarging the FONT was tried first and made things worse:
        // a 29.5 px glyph inside a 42 px box has a line box taller than the box itself, so the mark
        // ended up seven pixels below center instead of one. A graphic has no line box to overflow.
        double wantedInk = Tokens.TOUCH_GLYPH_INK_EM * fontSize;
        double glyphSize = Fonts.sizeForInkHeight(button.getText(), wantedInk);
        drawLabelAsInk(button,
                Fonts.inkLabel(button.getText(), Fonts.mono(glyphSize), button));

        pinHeight(button, boxFor(Tokens.TOUCH_GLYPH_LINE_EM, fontSize, paddingV));
        // The width needs no such care: the glyph is narrower than the original's, so the preferred
        // width being asked for is larger than the computed minimum and simply wins.
        button.setPrefWidth(boxFor(Tokens.TOUCH_GLYPH_ADVANCE_EM, fontSize, paddingH));
    }

    /** One edge-to-edge measurement: the content, its padding on both sides, and its border. */
    private static double boxFor(double contentEm, double fontSize, double padding) {
        return contentEm * fontSize + 2 * padding + 2 * Tokens.TOUCH_BUTTON_BORDER;
    }

    /** Asks for a height, and releases the minimum that would otherwise refuse it. */
    private static void pinHeight(Button button, double height) {
        button.setPrefHeight(height);
        button.setMinHeight(Button.USE_PREF_SIZE);
    }

    /**
     * Hands the island the five buttons that move out of the bar on a touch screen.
     *
     * <p><b>The same objects, not copies.</b> The original has no choice: CSS cannot move an
     * element, so it declares a second row of five buttons in the markup and hides whichever set
     * does not apply, then wires ten click handlers where five would do. JavaFX can simply put the
     * button somewhere else, so it does, and the consequence is that everything already true of
     * these five stays true: one handler each, one active-state color each, one hint each, and no
     * possibility of the two copies drifting apart.
     *
     * <p>Add comes back unpinned, because in the island it is sized by its own fluid type rather
     * than held at the desktop's 30 × 28.
     */
    List<Button> islandButtons() {
        unpin(add);
        return List.of(add, undo, fit, plan, units);
    }

    // ------------------------------------------------------------ for tests

    public Button setRoomButton() {
        return setRoom;
    }

    public Button layerCollisionButton() {
        return layerCollision;
    }

    public Button fitButton() {
        return fit;
    }

    public Button planButton() {
        return plan;
    }

    public Button addButtonNode() {
        return add;
    }

    public Button unitsButton() {
        return units;
    }

    public Button undoButton() {
        return undo;
    }

    public Button saveButton() {
        return save;
    }

    public Button loadButton() {
        return load;
    }

    public Button threeDButtonNode() {
        return threeD;
    }

    public NumberField widthField() {
        return widthField;
    }

    public NumberField lengthField() {
        return lengthField;
    }

    public NumberField heightField() {
        return heightField;
    }

    public Label widthLabel() {
        return widthLabel;
    }
}
