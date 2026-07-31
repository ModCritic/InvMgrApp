package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Preset;
import com.modcritic.invmgr.model.Units;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

/**
 * The dialog for putting a new box in the room.
 *
 * <p>Name, three measurements, an optional ID of your own, and a row of preset shortcuts along
 * the top.
 *
 * <p>Two behaviours that look like oversights and are not:
 *
 * <ul>
 *   <li><b>Only the name is cleared when it opens.</b> The measurements stay as you last left
 *       them, which is the whole point — someone unpacking a shelf of identical boxes types the
 *       size once and then just the names.
 *   <li><b>The measurements are not converted when you switch units.</b> A field showing 12 in
 *       imperial still shows 12 after switching to metric, and now means 12 cm. This is exactly
 *       what the original does, and it follows from the first behaviour: the number is a
 *       remembered <em>entry</em>, not a stored measurement. Only the labels change.
 * </ul>
 */
public final class AddItemDialog extends ModalDialog {

    /** Told what to add. Every value is already in the units the app stores. */
    @FunctionalInterface
    public interface AddHandler {
        void add(double w_in, double l_in, double h_in, String name, String customId);
    }

    private AppState state;

    private final NameField nameField = new NameField(Tokens.FONT_CONTROL);
    private final NumberField widthField = NumberField.dialogField();
    private final NumberField lengthField = NumberField.dialogField();
    private final NumberField heightField = NumberField.dialogField();
    private final TextField idField =
            Dialogs.textInput(Tokens.DIALOG_ID_WIDTH, Tokens.FONT_ID_LABEL,
                    Item.MAX_CUSTOM_ID_LENGTH);

    private final Label widthLabel = Dialogs.fieldLabel("Width (in):");
    private final Label lengthLabel = Dialogs.fieldLabel("Length (in):");
    private final Label heightLabel = Dialogs.fieldLabel("Height (in):");

    private final PresetSlots presets;
    private final Button confirmButton = Dialogs.button("Confirm", Dialogs.ButtonKind.CONFIRM);
    private final Button cancelButton = Dialogs.button("Cancel", Dialogs.ButtonKind.PLAIN);

    private AddHandler onAdd = (w, l, h, name, id) -> { };

    public AddItemDialog(Overlays host, AppState state) {
        super(host);
        this.state = state;
        this.presets = new PresetSlots(state);

        widthField.setValue(Item.DEFAULT_DIMENSION_IN);
        lengthField.setValue(Item.DEFAULT_DIMENSION_IN);
        heightField.setValue(Item.DEFAULT_DIMENSION_IN);

        content().getChildren().addAll(
                titleRow(),
                Dialogs.topAlignedRow(Dialogs.fieldLabel("Name:"), nameField),
                Dialogs.row(widthLabel, widthField),
                Dialogs.row(lengthLabel, lengthField),
                Dialogs.row(heightLabel, heightField),
                Dialogs.buttonRow(Dialogs.idField(idField), cancelButton, confirmButton));

        cancelButton.setOnAction(event -> cancel());
        confirmButton.setOnAction(event -> confirm());

        presets.setOnApply(this::applyPreset);

        // Enter moves on rather than submitting, because the name is the first thing you type
        // and almost never the last.
        //
        // Tab moves on too. A JavaFX TextArea treats Tab as text and indents with it, but the
        // original's Name box is an HTML <textarea>, and in a browser Tab has always moved to the
        // next control there — so leaving JavaFX's behaviour in place is the divergence, not
        // changing it. Shift+Tab is consumed as well: the Name box is the first field, so there
        // is nothing before it to move to, and inserting whitespace into a name is never what
        // was meant.
        nameField.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER
                    || (event.getCode() == KeyCode.TAB && !event.isShiftDown())) {
                widthField.textField().requestFocus();
                event.consume();
            } else if (event.getCode() == KeyCode.TAB) {
                event.consume();
            }
        });
        heightField.setOnAction(event -> confirm());
    }

    // ------------------------------------------------------------------ wiring

    public void setOnAdd(AddHandler handler) {
        this.onAdd = handler == null ? (w, l, h, name, id) -> { } : handler;
    }

    /** Passed straight through to the preset row: which slot wants defining. */
    public void setOnDefinePreset(java.util.function.IntConsumer handler) {
        presets.setOnDefine(handler);
    }

    public void setOnStatus(java.util.function.Consumer<String> handler) {
        presets.setOnStatus(handler);
    }

    public void setState(AppState state) {
        this.state = state;
        presets.setState(state);
        refreshUnitLabels();
    }

    /** Redraws the preset slots — call after one is saved or deleted elsewhere. */
    public void refreshPresets() {
        presets.rebuild();
    }

    /**
     * Re-labels the three measurement rows for the current unit.
     *
     * <p>Only the labels. See the class comment for why the numbers deliberately stay put.
     */
    public void refreshUnitLabels() {
        String unit = state.metricMode ? "cm" : "in";
        widthLabel.setText("Width (" + unit + "):");
        lengthLabel.setText("Length (" + unit + "):");
        heightLabel.setText("Height (" + unit + "):");
    }

    // ---------------------------------------------------------------- building

    /**
     * The heading, which is also the preset row.
     *
     * <p>It scrolls sideways, because presets can be added without limit and the dialog must
     * not grow wider than the window to accommodate forty of them.
     */
    private VBox titleRow() {
        // Both colours are set as an INLINE STYLE, not with setTextFill, and it matters.
        //
        // These two labels sit inside the title ScrollPane, which needs an inline
        // "-fx-background: transparent" to stop JavaFX painting a pale box behind the preset
        // row. JavaFX's default stylesheet then says a label's colour is
        // "-fx-text-background-color", which is a ladder() computed FROM that background — so
        // the value it produces carries the INLINE origin of the style it was derived from.
        // Inline beats a value set from code, so setTextFill() here was silently discarded and
        // both labels rendered pure white: "| Presets:" measured #E6E6E6 against the #777 it
        // should be. Setting the colour inline as well puts it at the same precedence, and it
        // wins because it names the property directly.
        Label heading = new Label("Add Item");
        heading.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_DIALOG_TITLE));
        heading.setStyle("-fx-text-fill: " + Tokens.hex(Tokens.TEXT_INPUT) + ";");

        Label presetsLabel = new Label("| Presets:");
        presetsLabel.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_DIALOG_TITLE));
        presetsLabel.setStyle("-fx-text-fill: " + Tokens.hex(Tokens.TEXT_PRESET_LABEL) + ";");

        HBox strip = new HBox(Tokens.DIALOG_TITLE_GAP, heading, presetsLabel, presets);
        strip.setAlignment(Pos.CENTER_LEFT);

        ScrollPane scroller = new ScrollPane(strip);
        scroller.setFitToHeight(true);
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroller.setMinViewportHeight(Tokens.PRESET_SIZE);
        scroller.setStyle("-fx-background: transparent; -fx-background-color: transparent;"
                + "-fx-padding: 0;");
        strip.setStyle("-fx-background-color: transparent;");

        // The underline and the space beneath it belong to the row as a whole, so they go on a
        // wrapper rather than on the scroller -- a border on the scroller would scroll with it.
        VBox row = new VBox(scroller);
        row.setPadding(new Insets(0, 0, Tokens.DIALOG_TITLE_ROW_PADDING_BOTTOM, 0));
        Dialogs.headingUnderline(row);
        VBox.setMargin(row, new Insets(0, 0, Tokens.DIALOG_TITLE_MARGIN_BOTTOM, 0));
        return row;
    }

    // --------------------------------------------------------------- behaviour

    /** Opens the dialog, clearing the name and nothing else. */
    @Override
    public void show() {
        nameField.clear();
        nameField.resizeToFit();
        refreshUnitLabels();
        presets.rebuild();
        super.show();
    }

    @Override
    protected void onShown() {
        // Deferred: a control cannot take focus until it is actually part of a shown window,
        // and asking any earlier silently does nothing.
        Platform.runLater(nameField::requestFocus);
    }

    private void applyPreset(Preset preset) {
        widthField.setValue(displayed(preset.w_in));
        lengthField.setValue(displayed(preset.l_in));
        heightField.setValue(displayed(preset.h_in));
    }

    /** A stored inches value as the dimension boxes should show it. */
    private double displayed(double inches) {
        return Units.round3(state.metricMode ? Units.inToCm(inches) : inches);
    }

    private void confirm() {
        // An unreadable box falls back to 12, the same default the original uses -- refusing
        // to add anything because one field was empty would be worse than adding a 12-inch cube.
        double w = widthField.value().orElse(Item.DEFAULT_DIMENSION_IN);
        double l = lengthField.value().orElse(Item.DEFAULT_DIMENSION_IN);
        double h = heightField.value().orElse(Item.DEFAULT_DIMENSION_IN);

        hide();
        onAdd.add(Units.dimensionInputToInches(w, state.metricMode),
                Units.dimensionInputToInches(l, state.metricMode),
                Units.dimensionInputToInches(h, state.metricMode),
                nameField.getText(), idField.getText());
    }

    // ------------------------------------------------------------- for tests

    public NameField nameField() {
        return nameField;
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

    public TextField idField() {
        return idField;
    }

    public Button confirmButton() {
        return confirmButton;
    }

    public Button cancelButton() {
        return cancelButton;
    }

    public PresetSlots presetSlots() {
        return presets;
    }

    public Label widthLabel() {
        return widthLabel;
    }
}
