package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.engine.TextFormat;
import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Units;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

/**
 * The vertical slider down the left edge that peels back the upper layers of the room.
 *
 * <p>Sliding it down hides everything sitting at or above that height, so the user can see
 * what is underneath a stack — X-raying the pile from the top.
 *
 * <p>Two details that are not obvious:
 *
 * <ul>
 *   <li><b>Two steps per foot.</b> The slider snaps to six-inch increments rather than moving
 *       continuously, because a continuous slider makes it fiddly to land on a shelf height
 *       exactly. The stored value is in feet, so the slider's own value is doubled.
 *   <li><b>The range grows past the ceiling.</b> It covers the taller of the room's height and
 *       the top of the tallest stack in it, so a tower that pokes above the ceiling can still
 *       be sliced.
 * </ul>
 */
public final class LayerSliderDrawer extends VBox {

    private final LayerTrack slider = new LayerTrack();
    private final VBox ticks = new VBox();

    /**
     * The metre labels drawn over the tick column in metric mode.
     *
     * <p>Its own layout, because each label sits at an arbitrary height rather than in a row: a
     * metre is 3.28 ft, so the labels fall between the half-foot dots, not on them. Each child
     * carries the fraction of the way up the column it belongs at, in its {@code userData}.
     */
    private final Pane meterLabels = new Pane() {
        @Override
        protected void layoutChildren() {
            double height = getHeight();
            double width = getWidth();
            for (Node child : getChildren()) {
                if (!(child instanceof Label label) || !(label.getUserData() instanceof Double up)) {
                    continue;
                }
                double labelHeight = label.prefHeight(width);
                // Measured from the BOTTOM, because the slider's zero is at the bottom, and
                // centred on that point so the text straddles the height it names rather than
                // hanging below it.
                double y = height - up * height - labelHeight / 2;
                label.resizeRelocate(0, y, width, labelHeight);
            }
        }
    };

    private AppState state;

    /** Called as the slider moves, so the room can update which items are showing. */
    private Runnable onLayerChanged = () -> { };

    /** Called with a description of the new height, for the status bar. */
    private java.util.function.Consumer<String> onStatus = message -> { };

    /**
     * @param initialState the state to start with. Deliberately NOT named {@code state}: a
     *     parameter of that name would shadow the field, and the value listener below writes
     *     through it. A lambda captures the parameter by value, so the listener would stay
     *     bolted to the first AppState forever and {@link #setState} — which only rebinds the
     *     field — could not reach it. That was a real bug: after loading a save the handle
     *     still moved but the cross-section froze, because every drag wrote {@code layerFeet}
     *     into the discarded pre-load state. The rename is what makes it impossible.
     */
    public LayerSliderDrawer(AppState initialState) {
        this.state = initialState;

        setPrefWidth(Tokens.SLIDER_DRAWER_WIDTH);
        setMinWidth(Tokens.SLIDER_DRAWER_WIDTH);
        setMaxWidth(Tokens.SLIDER_DRAWER_WIDTH);
        setAlignment(Pos.TOP_CENTER);
        setPadding(new Insets(6, 0, 6, 0));
        setStyle("-fx-background-color: " + Tokens.hex(Tokens.DRAWER_BG) + ";"
                + "-fx-border-color: transparent " + Tokens.hex(Tokens.BORDER) + " transparent transparent;"
                + "-fx-border-width: 0 1 0 0;");

        Label title = new Label("Layer");
        title.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_DRAWER_LABEL));
        title.setTextFill(Tokens.DRAWER_LABEL_TEXT);
        VBox.setMargin(title, new Insets(0, 0, 4, 0));

        slider.valueProperty().addListener((observable, old, value) -> {
            state.layerFeet = value.doubleValue() / 2;
            onLayerChanged.run();
            onStatus.accept(heightDescription());
        });

        // Ticks fill the height beside the slider, spread evenly, counting UP from the bottom —
        // hence the reversed order and the space-between spacing.
        ticks.setAlignment(Pos.TOP_RIGHT);
        ticks.setFillWidth(true);
        ticks.setMaxHeight(Double.MAX_VALUE);

        // The metre labels sit ON TOP of the dot column rather than beside it, so the two share
        // one cell and one height. Mouse-transparent so the overlay cannot swallow a click meant
        // for the slider — the original marks it pointer-events:none for the same reason.
        meterLabels.setMouseTransparent(true);
        StackPane tickColumn = new StackPane(ticks, meterLabels);
        tickColumn.setAlignment(Pos.TOP_RIGHT);
        tickColumn.setMaxHeight(Double.MAX_VALUE);
        HBox.setHgrow(tickColumn, Priority.ALWAYS);

        HBox sliderRow = new HBox(tickColumn, slider);
        // TOP alignment plus unbounded child heights is what makes both columns fill the
        // drawer; CENTER would lay them out at their preferred height and float them.
        sliderRow.setAlignment(Pos.TOP_CENTER);
        sliderRow.setFillHeight(true);
        sliderRow.setPadding(new Insets(0, 2, 0, 2));
        VBox.setVgrow(sliderRow, Priority.ALWAYS);

        getChildren().addAll(title, sliderRow);
        rebuild();
    }

    public void setState(AppState state) {
        this.state = state;
        rebuild();
    }

    public void setOnLayerChanged(Runnable handler) {
        this.onLayerChanged = handler == null ? () -> { } : handler;
    }

    public void setOnStatus(java.util.function.Consumer<String> handler) {
        this.onStatus = handler == null ? message -> { } : handler;
    }

    /**
     * Where the slider is, said twice — once in the big unit and once in the small one.
     *
     * <p>Both, because the slider moves in half-foot steps while item heights are in inches:
     * "Layer: 3ft" does not obviously mean "hide anything whose base is at or above 36 in",
     * and "(36in)" does.
     *
     * <pre>Layer: 3ft (36in)        Layer: 0.914m (91.44cm)</pre>
     */
    public String heightDescription() {
        if (state.metricMode) {
            return "Layer: " + TextFormat.number(Units.round3(Units.ftToM(state.layerFeet))) + "m"
                    + " (" + TextFormat.number(Units.round3(Units.inToCm(state.layerFeet * 12)))
                    + "cm)";
        }
        return "Layer: " + TextFormat.number(state.layerFeet) + "ft"
                + " (" + TextFormat.number(state.layerFeet * 12) + "in)";
    }

    /**
     * Recalculates the slider's range and its tick labels.
     *
     * <p>Call after anything that could change the tallest point in the room: loading a file,
     * adding or deleting an item, or finishing a drag that restacked something.
     */
    public void rebuild() {
        double maxHeightInches = state.room.h * 12;
        for (Item item : state.items) {
            maxHeightInches = Math.max(maxHeightInches, item.baseHeight_in + item.h_in);
        }
        int steps = (int) Math.ceil(maxHeightInches / 12) * 2;

        slider.setRange(0, steps);
        // Keep the handle where it was if it still fits, and write the snapped value back so
        // the stored feet and the visible handle can never disagree.
        double value = Math.min(Math.round(state.layerFeet * 2), steps);
        slider.setValue(value);
        state.layerFeet = value / 2;

        ticks.getChildren().clear();
        // Counting DOWN the column, because the slider's zero is at the bottom: the top label
        // is the highest layer.
        //
        // The labels keep their natural height and the gaps between them stretch, so the first
        // and last sit flush against the top and bottom of the drawer and the rest are evenly
        // spread — which is what lines each label up with its position on the slider beside it.
        // (Stretching the labels themselves instead would centre each one in a band and push
        // every label away from the tick it belongs to.)
        for (int step = steps; step >= 0; step--) {
            double feet = step / 2.0;
            boolean wholeFoot = feet % 1 == 0;

            // In metric every step becomes a bare dot: the snap grid stays on half-FEET, because
            // metres do not land on a half-foot boundary, so labelling the steps in metres would
            // put the numbers in the wrong places. The metre labels are overlaid separately
            // below, at their true heights. The dot's size and colour still key off wholeFoot —
            // the original's CSS class does the same and ignores the unit entirely.
            boolean labelInFeet = wholeFoot && !state.metricMode;
            Label label = new Label(labelInFeet ? (long) feet + "ft" : "·");
            label.setFont(Font.font(Tokens.FONT_FAMILY,
                    wholeFoot ? Tokens.FONT_TICK : Tokens.FONT_TICK_HALF));
            label.setTextFill(wholeFoot ? Tokens.TICK_TEXT : Tokens.TICK_HALF_TEXT);
            label.setPadding(new Insets(0, 2, 0, 0));
            label.setMaxWidth(Double.MAX_VALUE);
            label.setAlignment(Pos.CENTER_RIGHT);
            ticks.getChildren().add(label);

            if (step > 0) {
                Region gap = new Region();
                VBox.setVgrow(gap, Priority.ALWAYS);
                ticks.getChildren().add(gap);
            }
        }

        rebuildMeterLabels(steps / 2.0);
    }

    /**
     * The {@code 1m 2m 3m} labels shown over the tick column in metric mode.
     *
     * <p>They are a separate, freely-positioned layer rather than more entries in the tick column
     * because a metre is not a whole number of feet. The column's dots are evenly spaced on
     * half-feet — that is the grid the slider actually snaps to, and it does not change with the
     * unit — so a metre label has to sit at its own true proportional height, between the dots
     * rather than on one. Adding them to the column would drag the dots out of position.
     *
     * @param maxFeet the height of the room in feet, i.e. what the top of the track represents
     */
    private void rebuildMeterLabels(double maxFeet) {
        meterLabels.getChildren().clear();
        if (!state.metricMode || maxFeet <= 0) {
            return;
        }

        int maxMetres = (int) Math.floor(Units.ftToM(maxFeet));
        for (int metre = 0; metre <= maxMetres; metre++) {
            Label label = new Label(metre + "m");
            label.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_TICK));
            label.setTextFill(Tokens.METER_TICK_TEXT);
            label.setPadding(new Insets(0, 2, 0, 0));
            label.setAlignment(Pos.CENTER_RIGHT);
            // How far up the column this metre falls, as a fraction. Read back in layoutChildren.
            label.setUserData(Units.mToFt(metre) / maxFeet);
            meterLabels.getChildren().add(label);
        }
        meterLabels.requestLayout();
    }

    /** Exposed for tests, which need to move the slider without a pointer. */
    public LayerTrack slider() {
        return slider;
    }
}
