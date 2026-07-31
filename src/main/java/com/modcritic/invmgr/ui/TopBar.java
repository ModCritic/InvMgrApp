package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.engine.TextFormat;
import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Room;
import com.modcritic.invmgr.model.Units;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * The bar across the top: the room's dimensions, and the buttons that change modes.
 *
 * <p><b>Each mode button has its own colour</b> — Fit blue, Plan purple, Units green, Layer
 * Collision amber — so a glance tells you which modes are on without reading any labels. That is
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

    public TopBar(AppState state) {
        this.state = state;

        setHgap(Tokens.TOP_BAR_GAP);
        setVgap(Tokens.TOP_BAR_GAP);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(Tokens.TOP_BAR_PADDING_V, Tokens.TOP_BAR_PADDING_H,
                Tokens.TOP_BAR_PADDING_V, Tokens.TOP_BAR_PADDING_H));
        setStyle("-fx-background-color: " + Tokens.hex(Tokens.TOP_BAR_BG) + ";"
                + "-fx-border-color: transparent transparent " + Tokens.hex(Tokens.BORDER) + " transparent;"
                + "-fx-border-width: 0 0 1 0;");

        getChildren().addAll(
                field(widthLabel, widthField),
                field(lengthLabel, lengthField),
                field(heightLabel, heightField),
                setRoom, layerCollision, save, load, add, undo, fit, plan, units);

        setRoom.setOnAction(event -> applyRoomFromFields());
        layerCollision.setOnAction(event -> toggleLayerCollision());
        add.setOnAction(event -> onAdd.run());
        undo.setOnAction(event -> onUndo.run());
        fit.setOnAction(event -> toggleFit());
        plan.setOnAction(event -> togglePlan());
        units.setOnAction(event -> toggleUnits());
        save.setOnAction(event -> onSave.run());
        load.setOnAction(event -> onLoad.run());

        // The hints the original writes as HTML title attributes. Only the buttons whose name
        // does not already say what they do carry one.
        layerCollision.setTooltip(Hints.tooltip(
                "Items keep their current layer instead of stacking or falling"));
        add.setTooltip(Hints.tooltip("Add Item"));
        fit.setTooltip(Hints.tooltip("Fit to Screen"));
        plan.setTooltip(Hints.tooltip("Planning Mode"));
        units.setTooltip(Hints.tooltip("Toggle Metric Units"));

        // Enter in any of the three fields applies the room, which is what anyone typing a
        // number expects — reaching for the button every time would be tedious.
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

    private static Runnable or(Runnable handler) {
        return handler == null ? () -> { } : handler;
    }

    // ------------------------------------------------------------- behaviour

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
     * <p>Easy to forget, and wrong in a way that is hard to spot: the fields show metres in
     * metric, so a stepper still nudging by half — half a <em>metre</em>, more than a foot and a
     * half — would jump the room in unusable steps. The ceiling moves too, since 200 feet is
     * 61 metres, not 200 of them.
     */
    private void applyFieldRanges(boolean metric) {
        double step = metric ? METRIC_STEP : IMPERIAL_STEP;
        widthField.setStep(step);
        lengthField.setStep(step);
        heightField.setStep(step);

        // Rounded, exactly as the original rounds its max attribute — 61 m rather than 60.96.
        double maxPlan = metric ? Math.round(Units.ftToM(Room.MAX_W)) : Room.MAX_W;
        double maxHeight = metric ? Math.round(Units.ftToM(Room.MAX_H)) : Room.MAX_H;
        widthField.setRange(Room.MIN_W, maxPlan);
        lengthField.setRange(Room.MIN_L, maxPlan);
        heightField.setRange(Room.MIN_H, maxHeight);
    }

    /** The room steppers nudge by half a foot, or a tenth of a metre. From the original. */
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
                ? "Layer Collision ON — items keep their current layer."
                : "Layer Collision OFF — items settled to their correct layer.");
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
                ? "Planning Mode ON — new items are planned (not placed)."
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
     * <p>In metric the room is <b>displayed</b> in metres while still being <b>stored</b> in
     * feet — the conversion happens here, at the very edge of the interface, and nowhere else.
     */
    private String displayValue(double feet) {
        return TextFormat.number(state.metricMode ? Units.round3(Units.ftToM(feet)) : feet);
    }

    /** Reads a typed value, converting back from metres if that is what the user is seeing. */
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
     * <p>Bigger type and tighter padding than every other button, from the original's own rule
     * for it — a single glyph reads as a button rather than as a stray character only if it
     * fills the space.
     */
    private static Button addButton() {
        Button button = new Button("■");
        button.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_ADD_BUTTON));
        applyButtonStyle(button, Tokens.BUTTON_ADD_BG, Tokens.CONTROL_BORDER,
                Tokens.TEXT_PRIMARY, Tokens.ADD_BUTTON_PADDING_V, Tokens.ADD_BUTTON_PADDING_H);
        button.setOnMouseEntered(event -> applyButtonStyle(button, Tokens.BUTTON_BG_HOVER,
                Tokens.CONTROL_BORDER, Tokens.TEXT_PRIMARY, Tokens.ADD_BUTTON_PADDING_V,
                Tokens.ADD_BUTTON_PADDING_H));
        button.setOnMouseExited(event -> applyButtonStyle(button, Tokens.BUTTON_ADD_BG,
                Tokens.CONTROL_BORDER, Tokens.TEXT_PRIMARY, Tokens.ADD_BUTTON_PADDING_V,
                Tokens.ADD_BUTTON_PADDING_H));
        return button;
    }

    private static Button button(String text, Color background) {
        Button button = new Button(text);
        button.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_CONTROL));
        applyButtonStyle(button, background, Tokens.CONTROL_BORDER, Tokens.TEXT_PRIMARY);

        // Hover only lifts a button that is not currently showing an active mode colour;
        // otherwise hovering an active toggle would wash out the colour that says it is on.
        button.setOnMouseEntered(event -> {
            if (button.getUserData() == null) {
                applyButtonStyle(button, Tokens.BUTTON_BG_HOVER, Tokens.CONTROL_BORDER,
                        Tokens.TEXT_PRIMARY);
            }
        });
        button.setOnMouseExited(event -> {
            if (button.getUserData() == null) {
                applyButtonStyle(button, background, Tokens.CONTROL_BORDER, Tokens.TEXT_PRIMARY);
            }
        });
        return button;
    }

    /**
     * Switches a toggle between its resting colour and its own active identity.
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

    private static void applyButtonStyle(Button button, Color background, Color border,
            Color text) {
        applyButtonStyle(button, background, border, text, Tokens.BUTTON_PADDING_V,
                Tokens.BUTTON_PADDING_H);
    }

    private static void applyButtonStyle(Button button, Color background, Color border,
            Color text, double paddingV, double paddingH) {
        button.setStyle("-fx-background-color: " + Tokens.hex(background) + ";"
                + "-fx-text-fill: " + Tokens.hex(text) + ";"
                + "-fx-border-color: " + Tokens.hex(border) + ";"
                + "-fx-border-width: 1;"
                // Square corners are the rule here — these are the app's own buttons, not
                // browser-native controls.
                + "-fx-background-radius: 0; -fx-border-radius: 0;"
                + "-fx-padding: " + paddingV + " " + paddingH + " "
                + paddingV + " " + paddingH + ";");
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
