package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Preset;
import com.modcritic.invmgr.model.Units;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;

/**
 * The small dialog for defining a preset — a two-letter shorthand for a box size.
 *
 * <p>Opened by clicking an empty slot in the Add Item dialog. It sits <em>on top</em> of that
 * dialog rather than replacing it, so the half-filled Add form underneath survives.
 *
 * <p>The name is capped at two characters because the slot it goes into is 28 pixels wide. An
 * empty name becomes {@code ??} rather than being rejected — a nameless preset is still a
 * usable size shortcut, and refusing to save one would be a dead end.
 */
public final class PresetDialog extends ModalDialog {

    /** What an empty preset name becomes. */
    private static final String FALLBACK_NAME = "??";

    /** Told the finished preset and which slot it belongs in. */
    @FunctionalInterface
    public interface SaveHandler {
        void save(int slot, Preset preset);
    }

    private AppState state;

    /** Which slot is being filled in, or -1 while the dialog is closed. */
    private int slot = -1;

    private final TextField nameField =
            Dialogs.textInput(Tokens.DIALOG_TEXT_WIDTH, Tokens.FONT_CONTROL,
                    Preset.MAX_NAME_LENGTH);
    private final NumberField widthField = NumberField.dialogField();
    private final NumberField lengthField = NumberField.dialogField();
    private final NumberField heightField = NumberField.dialogField();

    private final Label widthLabel = Dialogs.fieldLabel("Width (in):");
    private final Label lengthLabel = Dialogs.fieldLabel("Length (in):");
    private final Label heightLabel = Dialogs.fieldLabel("Height (in):");

    private final Button saveButton = Dialogs.button("Save", Dialogs.ButtonKind.CONFIRM);
    private final Button cancelButton = Dialogs.button("Cancel", Dialogs.ButtonKind.PLAIN);

    private SaveHandler onSave = (index, preset) -> { };

    public PresetDialog(Overlays host, AppState state) {
        super(host);
        this.state = state;

        nameField.setPromptText("AB");

        content().getChildren().addAll(
                Dialogs.title("Save Preset"),
                Dialogs.row(Dialogs.fieldLabel("Preset name:"), nameField),
                Dialogs.row(widthLabel, widthField),
                Dialogs.row(lengthLabel, lengthField),
                Dialogs.row(heightLabel, heightField),
                Dialogs.buttonRow(cancelButton, saveButton));

        cancelButton.setOnAction(event -> cancel());
        saveButton.setOnAction(event -> confirm());
        nameField.setOnAction(event -> confirm());
        heightField.setOnAction(event -> confirm());
        nameField.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                confirm();
                event.consume();
            }
        });
    }

    public void setOnSave(SaveHandler handler) {
        this.onSave = handler == null ? (index, preset) -> { } : handler;
    }

    public void setState(AppState state) {
        this.state = state;
    }

    /** Opens the dialog for one slot, blank, with the default 12-inch cube filled in. */
    public void open(int slot) {
        this.slot = slot;
        String unit = state.metricMode ? "cm" : "in";
        widthLabel.setText("Width (" + unit + "):");
        lengthLabel.setText("Length (" + unit + "):");
        heightLabel.setText("Height (" + unit + "):");

        nameField.clear();
        double def = Units.round3(state.metricMode
                ? Units.inToCm(Item.DEFAULT_DIMENSION_IN) : Item.DEFAULT_DIMENSION_IN);
        widthField.setValue(def);
        lengthField.setValue(def);
        heightField.setValue(def);

        show();
    }

    @Override
    protected void onShown() {
        Platform.runLater(nameField::requestFocus);
    }

    @Override
    protected void cancel() {
        slot = -1;
        hide();
    }

    private void confirm() {
        if (slot < 0) {
            return;
        }
        int target = slot;
        slot = -1;
        hide();

        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isEmpty()) {
            name = FALLBACK_NAME;
        }
        double w = widthField.value().orElse(Item.DEFAULT_DIMENSION_IN);
        double l = lengthField.value().orElse(Item.DEFAULT_DIMENSION_IN);
        double h = heightField.value().orElse(Item.DEFAULT_DIMENSION_IN);

        onSave.save(target, new Preset(name,
                Units.dimensionInputToInches(w, state.metricMode),
                Units.dimensionInputToInches(l, state.metricMode),
                Units.dimensionInputToInches(h, state.metricMode)));
    }

    // ------------------------------------------------------------- for tests

    public TextField nameField() {
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

    public Button saveButton() {
        return saveButton;
    }

    public Button cancelButton() {
        return cancelButton;
    }
}
