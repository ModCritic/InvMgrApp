package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Preset;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * The little row of saved box sizes along the top of the Add Item dialog.
 *
 * <p>A preset is a shortcut, nothing more: two letters standing for a width, length and height
 * you use often. Clicking one fills in the three dimension boxes below. An empty slot —
 * shown as {@code ...} — opens a small dialog to define one, and the green {@code +} adds
 * another empty slot on the end.
 *
 * <p>They are saved <b>in the room file</b> rather than in a settings store, which is
 * deliberate in the original: the sizes that matter are the ones in the room you are working
 * on, and a file handed to someone else brings its shorthand with it.
 *
 * <p><b>Deleting is right-click only here.</b> The original also accepts a 1.5-second hold on
 * desktop, so a preset can be deleted two ways; this port drops the hold (your call,
 * 2026-07-27) so there is one deletion gesture per input type — hold is a touch gesture,
 * right-click is a desktop one. Room items already work exactly this way. See
 * {@code CLAUDE.md} §5.5, divergence D-1. The consequence is that the red warning ring that
 * builds up during a hold is a <b>touch-only</b> thing and does not appear here at all.
 */
public final class PresetSlots extends HBox {

    private AppState state;

    private Consumer<Preset> onApply = preset -> { };
    private IntConsumer onDefine = index -> { };
    private Consumer<String> onStatus = message -> { };

    /**
     * How the "are you sure?" question gets asked.
     *
     * <p>Held as a replaceable function rather than calling {@link Hints#confirm} directly, for
     * two reasons. It lets the deletion rule be tested without a real dialog box to click — a
     * modal window that blocks waiting for an answer is not something an automated test can get
     * past. And M6's touch build asks the same question at the end of a 1.5-second hold, which
     * will want its own way of asking.
     */
    private Predicate<String> confirm = question -> Hints.confirm(getScene(), question);

    public PresetSlots(AppState state) {
        this.state = state;
        setSpacing(Tokens.PRESET_GAP);
        setAlignment(Pos.CENTER_LEFT);
        rebuild();
    }

    /** Called with the preset whose dimensions should be copied into the Add fields. */
    public void setOnApply(Consumer<Preset> handler) {
        this.onApply = handler == null ? preset -> { } : handler;
    }

    /** Called with the slot number that should be filled in. */
    public void setOnDefine(IntConsumer handler) {
        this.onDefine = handler == null ? index -> { } : handler;
    }

    public void setOnStatus(Consumer<String> handler) {
        this.onStatus = handler == null ? message -> { } : handler;
    }

    /** Replaces how deletion is confirmed. See the field it sets. */
    public void setConfirm(Predicate<String> confirm) {
        this.confirm = confirm == null ? question -> false : confirm;
    }

    public void setState(AppState state) {
        this.state = state;
        rebuild();
    }

    /** Redraws every slot. Cheap — there are three of them by default and rarely many more. */
    public void rebuild() {
        getChildren().clear();
        for (int index = 0; index < state.presets.size(); index++) {
            getChildren().add(slot(index));
        }
        getChildren().add(addSlotButton());
    }

    private Button slot(int index) {
        Preset preset = state.presets.get(index);
        boolean filled = preset != null;

        Button button = new Button(filled ? preset.name : "...");
        shape(button, Tokens.PRESET_SIZE, Tokens.FONT_PRESET);
        paint(button, filled ? Tokens.PRESET_FILLED_BG : Tokens.BUTTON_BG,
                filled ? Color.WHITE : Tokens.TEXT_PRIMARY);
        button.setOnMouseEntered(event -> paint(button, Tokens.BUTTON_BG_HOVER,
                filled ? Color.WHITE : Tokens.TEXT_PRIMARY));
        button.setOnMouseExited(event -> paint(button,
                filled ? Tokens.PRESET_FILLED_BG : Tokens.BUTTON_BG,
                filled ? Color.WHITE : Tokens.TEXT_PRIMARY));

        Hints.attach(button, filled
                ? preset.name + ": click to use, right-click to delete"
                : "Empty preset — click to set dimensions");

        button.setOnAction(event -> {
            if (filled) {
                onApply.accept(preset);
                onStatus.accept("Preset \"" + preset.name + "\" applied.");
            } else {
                onDefine.accept(index);
            }
        });

        if (filled) {
            button.setOnMousePressed(event -> {
                if (event.getButton() == MouseButton.SECONDARY) {
                    confirmDelete(index, preset);
                    event.consume();
                }
            });
        }
        return button;
    }

    private Button addSlotButton() {
        Button button = new Button("+");
        shape(button, Tokens.PRESET_SIZE, Tokens.FONT_PRESET_ADD);
        paint(button, Tokens.BUTTON_CONFIRM_BG, Tokens.TEXT_PRIMARY);
        button.setOnMouseEntered(event ->
                paint(button, Tokens.BUTTON_CONFIRM_BG_HOVER, Tokens.TEXT_PRIMARY));
        button.setOnMouseExited(event ->
                paint(button, Tokens.BUTTON_CONFIRM_BG, Tokens.TEXT_PRIMARY));
        Hints.attach(button, "Add another preset slot");
        button.setOnAction(event -> {
            state.presets.add(null);
            rebuild();
        });
        return button;
    }

    /**
     * Asks before throwing a preset away.
     *
     * <p>Unlike deleting a box from the room, this is <b>not</b> undoable — the undo stack is
     * about the room's contents — so the confirmation is the only protection there is.
     */
    private void confirmDelete(int index, Preset preset) {
        if (confirm.test("Delete preset \"" + preset.name + "\"?")) {
            state.presets.set(index, null);
            rebuild();
            onStatus.accept("Preset \"" + preset.name + "\" deleted.");
        }
    }

    /** For tests: the button standing for one slot. */
    public Button slotButton(int index) {
        return (Button) getChildren().get(index);
    }

    /** For tests: the green {@code +} on the end. */
    public Button addSlotButtonNode() {
        return (Button) getChildren().get(getChildren().size() - 1);
    }

    private static void shape(Button button, double size, double fontSize) {
        button.setFont(Font.font(Tokens.FONT_FAMILY, fontSize));
        button.setMinSize(size, size);
        button.setPrefSize(size, size);
        button.setMaxSize(size, size);
        button.setPadding(Insets.EMPTY);
    }

    private static void paint(Button button, Color background, Color text) {
        button.setStyle("-fx-background-color: " + Tokens.hex(background) + ";"
                + "-fx-text-fill: " + Tokens.hex(text) + ";"
                + "-fx-border-color: " + Tokens.hex(Tokens.CONTROL_BORDER) + ";"
                + "-fx-border-width: 1;"
                + "-fx-background-radius: 0; -fx-border-radius: 0;");
    }
}
