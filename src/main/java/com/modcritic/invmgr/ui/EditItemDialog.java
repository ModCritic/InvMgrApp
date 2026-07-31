package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Units;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.text.Font;

/**
 * The dialog for changing a box that is already in the room — reached by clicking it, or by
 * double-clicking its row in the list.
 *
 * <p>Same four fields as Add Item, plus three things Add has no use for: a Delete button, and
 * the {@code ↻} button in the corner that turns the box a quarter turn.
 *
 * <p><b>The rotate button acts immediately.</b> It does not wait for OK, and pressing Cancel
 * afterwards does not put the box back. That is the original's behaviour and it is defensible:
 * rotating is its own action with its own undo entry, so Undo reverses it — Cancel is about the
 * text and numbers in the dialog, which the rotation never touched.
 */
public final class EditItemDialog extends ModalDialog {

    /** Told what the fields now say. Values are already in stored units. */
    @FunctionalInterface
    public interface EditHandler {
        void apply(Item item, String name, String customId, double w_in, double l_in,
                double h_in);
    }

    private AppState state;

    /** The box being edited, or null while the dialog is closed. */
    private Item editing;

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

    private final Button okButton = Dialogs.button("OK", Dialogs.ButtonKind.CONFIRM);
    private final Button cancelButton = Dialogs.button("Cancel", Dialogs.ButtonKind.PLAIN);
    private final Button deleteButton = Dialogs.button("Delete", Dialogs.ButtonKind.DANGER);
    private final Button swapButton = Dialogs.button("↻", Dialogs.ButtonKind.PLAIN);

    private EditHandler onApply = (item, name, id, w, l, h) -> { };
    private Consumer<Item> onDelete = item -> { };
    private Consumer<Item> onSwap = item -> { };

    public EditItemDialog(Overlays host, AppState state) {
        super(host);
        this.state = state;

        content().getChildren().addAll(
                Dialogs.title("Edit Item"),
                Dialogs.topAlignedRow(Dialogs.fieldLabel("Name:"), nameField),
                Dialogs.row(widthLabel, widthField),
                Dialogs.row(lengthLabel, lengthField),
                Dialogs.row(heightLabel, heightField),
                Dialogs.buttonRow(deleteButton, Dialogs.idField(idField), cancelButton,
                        okButton));

        // The rotate button sits over the dialog's own corner rather than in a row, which is
        // why it is floated rather than added to the column.
        swapButton.setPadding(new Insets(2, 7, 2, 7));
        swapButton.setTooltip(Hints.tooltip("Swap Width and Length"));
        addFloating(swapButton, Pos.TOP_RIGHT,
                new Insets(Tokens.DIALOG_SWAP_INSET, Tokens.DIALOG_SWAP_INSET, 0, 0));

        cancelButton.setOnAction(event -> cancel());
        okButton.setOnAction(event -> confirm());
        deleteButton.setOnAction(event -> delete());
        swapButton.setOnAction(event -> swap());

        // Unlike Add, Enter here submits: by the time you are editing, the name is usually the
        // only thing you came to change.
        //
        // Tab moves to the next field instead. See AddItemDialog for why that is the faithful
        // behaviour rather than a change: the original's Name box is an HTML <textarea>, where a
        // browser has always moved focus on Tab, and only JavaFX indents with it.
        nameField.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                confirm();
                event.consume();
            } else if (event.getCode() == KeyCode.TAB) {
                if (!event.isShiftDown()) {
                    widthField.textField().requestFocus();
                }
                event.consume();
            }
        });
        heightField.setOnAction(event -> confirm());
    }

    // ------------------------------------------------------------------ wiring

    public void setOnApply(EditHandler handler) {
        this.onApply = handler == null ? (item, name, id, w, l, h) -> { } : handler;
    }

    public void setOnDelete(Consumer<Item> handler) {
        this.onDelete = handler == null ? item -> { } : handler;
    }

    public void setOnSwap(Consumer<Item> handler) {
        this.onSwap = handler == null ? item -> { } : handler;
    }

    public void setState(AppState state) {
        this.state = state;
    }

    // --------------------------------------------------------------- behaviour

    /** Opens the dialog on a particular box, filling every field from it. */
    public void open(Item item) {
        this.editing = item;
        String unit = state.metricMode ? "cm" : "in";
        widthLabel.setText("Width (" + unit + "):");
        lengthLabel.setText("Length (" + unit + "):");
        heightLabel.setText("Height (" + unit + "):");

        nameField.setText(item.name == null ? "" : item.name);
        nameField.resizeToFit();
        widthField.setValue(displayed(item.w_in));
        lengthField.setValue(displayed(item.l_in));
        heightField.setValue(displayed(item.h_in));
        idField.setText(item.customId == null ? "" : item.customId);

        show();
    }

    @Override
    protected void onShown() {
        Platform.runLater(nameField::requestFocus);
    }

    @Override
    protected void cancel() {
        editing = null;
        hide();
    }

    private double displayed(double inches) {
        return Units.round3(state.metricMode ? Units.inToCm(inches) : inches);
    }

    private void confirm() {
        Item item = editing;
        editing = null;
        hide();
        if (item == null) {
            return;
        }
        // An unreadable box keeps the box's current size rather than falling back to 12, which
        // is the difference from Add: here there is an existing value worth preserving.
        double w = widthField.value().orElse(displayed(item.w_in));
        double l = lengthField.value().orElse(displayed(item.l_in));
        double h = heightField.value().orElse(displayed(item.h_in));

        onApply.apply(item, nameField.getText(), idField.getText(),
                Units.dimensionInputToInches(w, state.metricMode),
                Units.dimensionInputToInches(l, state.metricMode),
                Units.dimensionInputToInches(h, state.metricMode));
    }

    private void delete() {
        Item item = editing;
        editing = null;
        hide();
        if (item != null) {
            onDelete.accept(item);
        }
    }

    /**
     * Turns the box a quarter turn and updates the two fields it changed.
     *
     * <p>Height is left alone, in the box and in the dialog — this turns the box on the floor,
     * it does not tip it over.
     */
    private void swap() {
        if (editing == null) {
            return;
        }
        onSwap.accept(editing);
        widthField.setValue(displayed(editing.w_in));
        lengthField.setValue(displayed(editing.l_in));
    }

    // ------------------------------------------------------------- for tests

    public Item editingItem() {
        return editing;
    }

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

    public Button okButton() {
        return okButton;
    }

    public Button cancelButton() {
        return cancelButton;
    }

    public Button deleteButton() {
        return deleteButton;
    }

    public Button swapButton() {
        return swapButton;
    }
}
