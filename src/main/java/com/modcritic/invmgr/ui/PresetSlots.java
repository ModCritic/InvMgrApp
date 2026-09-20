package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Preset;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeType;
import javafx.scene.text.Font;

/**
 * The little row of saved box sizes along the top of the Add Item dialog.
 *
 * <p>A preset is a shortcut, nothing more: two letters standing for a width, length and height
 * you use often. Clicking one fills in the three dimension boxes below. An empty slot
 * (shown as {@code ...}) opens a small dialog to define one, and the green {@code +} adds
 * another empty slot on the end.
 *
 * <p>They are saved <b>in the room file</b> rather than in a settings store, which is
 * deliberate in the original: the sizes that matter are the ones in the room you are working
 * on, and a file handed to someone else brings its shorthand with it.
 *
 * <p><b>Deleting is right-click on a desktop and a 1.5-second hold on a phone.</b> The original
 * accepts both on desktop, so a preset can be deleted two ways there; this port drops the desktop
 * hold (your call, 2026-07-27) so there is one deletion gesture per input type. Room items already
 * work exactly this way. See {@code CLAUDE.md} §5.5, divergence D-1. The consequence is that the
 * red warning ring that builds up during a hold is a <b>touch-only</b> thing: on a desktop nothing
 * here ever builds up to a delete, so there is nothing to warn about.
 *
 * <p>Either way the deletion is confirmed first, unlike a box in the room; a box can be brought
 * back with undo and a preset cannot.
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
     * two reasons. It lets the deletion rule be tested without a real dialog box to click; a
     * modal window that blocks waiting for an answer is not something an automated test can get
     * past. And M6's touch build asks the same question at the end of a 1.5-second hold, which
     * will want its own way of asking.
     */
    private Predicate<String> confirm = question -> Hints.confirm(getScene(), question);

    /**
     * The red square that closes in while a finger is held on a slot. One for the whole row, since
     * only one slot can be held at a time.
     */
    private final Rectangle ring = new Rectangle();

    public PresetSlots(AppState state) {
        this.state = state;
        setSpacing(Tokens.PRESET_GAP);
        setAlignment(Pos.CENTER_LEFT);

        ring.setFill(Color.TRANSPARENT);
        ring.setStroke(Tokens.HOLD_RING);
        ring.setStrokeWidth(Tokens.HOLD_RING_WIDTH);
        ring.setStrokeType(StrokeType.INSIDE);
        ring.setMouseTransparent(true);
        ring.setManaged(false);

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

    /** Redraws every slot. Cheap: there are three of them by default and rarely many more. */
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
        shape(button, TouchType.presetSize(), TouchType.presetFont());
        paint(button, filled ? Tokens.PRESET_FILLED_BG : Tokens.BUTTON_BG,
                filled ? Color.WHITE : Tokens.TEXT_PRIMARY);
        button.setOnMouseEntered(event -> paint(button, Tokens.BUTTON_BG_HOVER,
                filled ? Color.WHITE : Tokens.TEXT_PRIMARY));
        button.setOnMouseExited(event -> paint(button,
                filled ? Tokens.PRESET_FILLED_BG : Tokens.BUTTON_BG,
                filled ? Color.WHITE : Tokens.TEXT_PRIMARY));

        // No hint on a phone, which is what the original does too: its hints are `title`
        // attributes, and a browser never shows one to a finger. It would also be actively wrong
        // here (the text names right-clicking, which a phone cannot do) and a JavaFX hint pops up
        // on hover, which on Android means part-way through the hold it would be fighting.
        if (!Device.isTouch()) {
            Hints.attach(button, filled
                    ? preset.name + ": click to use, right-click to delete"
                    : "Empty preset: click to set dimensions");
        }

        button.setOnAction(event -> apply(index, preset, filled));

        if (filled) {
            button.setOnMousePressed(event -> {
                if (event.getButton() == MouseButton.SECONDARY) {
                    confirmDelete(index, preset);
                    event.consume();
                }
            });
        }

        wireTouch(button, index, preset, filled);
        return button;
    }

    /** What a click or a tap on a slot does: use the preset, or offer to define one. */
    private void apply(int index, Preset preset, boolean filled) {
        if (filled) {
            onApply.accept(preset);
            onStatus.accept("Preset \"" + preset.name + "\" applied.");
        } else {
            onDefine.accept(index);
        }
    }

    /**
     * What a finger on a slot means: a tap uses the preset, and a second and a half deletes it.
     * The touch half of §5.5 <b>D-1</b>.
     *
     * <p>Wired on every platform rather than behind a check, because a mouse never produces a
     * touch event; on a desktop none of this can fire, and nothing here adds a desktop hold.
     * What it does buy is that the whole gesture can be driven from a test in a container with no
     * touchscreen in it.
     *
     * <p>Movement past six pixels calls the hold off, because a finger crossing a slot is someone
     * swiping the row of presets sideways, not choosing this one.
     *
     * <p><b>Both halves are needed together.</b> The tap has to be handled here rather than left to
     * the button's own {@code setOnAction}, because on Android the finger also arrives as a
     * synthesized mouse press and release, and those would fire the action a second time: using
     * the preset twice, or opening the define dialog twice. So the synthesized events are consumed
     * and the touch release does the work. That costs nothing that was not already lost: a JavaFX
     * {@code Button} consumes mouse presses itself, so the scrolling strip these sit in never saw
     * them anyway.
     */
    private void wireTouch(Button button, int index, Preset preset, boolean filled) {
        button.addEventFilter(javafx.scene.input.MouseEvent.ANY, event -> {
            if (event.isSynthesized()) {
                event.consume();
            }
        });
        TouchGesture gesture = new TouchGesture();
        boolean[] spent = {false};

        AnimationTimer clock = new AnimationTimer() {
            @Override
            public void handle(long nanos) {
                double nowMs = nanos / 1_000_000.0;
                if (gesture.holdFired(nowMs)) {
                    stop();
                    hideRing();
                    spent[0] = true;
                    // The existing confirm-and-remove, not a second copy of it. Deleting a preset
                    // is not undoable, so the question is the only protection there is.
                    //
                    // ⚠ ON THE NEXT PULSE, NOT THIS ONE. We are inside an AnimationTimer, which
                    // JavaFX runs as part of a frame, and a modal dialog has to start a nested
                    // event loop to wait for its answer, which JavaFX refuses mid-frame:
                    // "showAndWait is not allowed during animation or layout processing". Asking
                    // straight from here threw that on the phone, the exception was swallowed the
                    // way handler exceptions are, and holding a preset simply did nothing. Nothing
                    // about it is Android-specific; it is where the call was made from.
                    javafx.application.Platform.runLater(() -> confirmDelete(index, preset));
                    return;
                }
                if (!gesture.isHolding()) {
                    stop();
                    hideRing();
                    return;
                }
                ring.setOpacity(Tokens.HOLD_RING_MIN_OPACITY
                        + (1 - Tokens.HOLD_RING_MIN_OPACITY) * gesture.holdProgress(nowMs));
            }
        };

        button.setOnTouchPressed(event -> {
            if (event.getTouchCount() != 1) {
                return;
            }
            spent[0] = false;
            gesture.began(event.getTouchPoint().getSceneX(), event.getTouchPoint().getSceneY(),
                    nowMs());
            // Only a filled slot can be deleted, so only a filled slot warns about it. An empty
            // one still runs the gesture, so that holding it does not then count as a tap.
            if (filled) {
                showRing(button);
            }
            clock.start();
        });

        button.setOnTouchMoved(event -> gesture.moved(event.getTouchPoint().getSceneX(),
                event.getTouchPoint().getSceneY()));

        button.setOnTouchReleased(event -> {
            TouchGesture.Outcome outcome = gesture.ended(nowMs());
            clock.stop();
            hideRing();
            if (spent[0]) {
                // The hold already deleted this slot. The synthesized click that follows the
                // finger lifting would otherwise apply a preset that no longer exists.
                event.consume();
                return;
            }
            if (outcome == TouchGesture.Outcome.TAP || outcome == TouchGesture.Outcome.DOUBLE_TAP) {
                apply(index, preset, filled);
                event.consume();
            }
            // A drag was someone scrolling the row, and the dead band is the escape hatch from a
            // delete. Neither does anything, and neither is consumed.
        });
    }

    private static double nowMs() {
        return System.nanoTime() / 1_000_000.0;
    }

    /**
     * Puts the warning ring over a slot.
     *
     * <p>It is a child of this row rather than of the button, because a JavaFX {@code Button} has
     * no children to put it in. Unmanaged, so being 4 px bigger than the slot on every side does
     * not push the row about.
     */
    private void showRing(Button button) {
        ring.setWidth(button.getWidth() + Tokens.HOLD_RING_INSET_PRESET * 2);
        ring.setHeight(button.getHeight() + Tokens.HOLD_RING_INSET_PRESET * 2);
        ring.setOpacity(Tokens.HOLD_RING_MIN_OPACITY);
        if (!getChildren().contains(ring)) {
            getChildren().add(ring);
        }
        ring.relocate(button.getLayoutX() - Tokens.HOLD_RING_INSET_PRESET,
                button.getLayoutY() - Tokens.HOLD_RING_INSET_PRESET);
    }

    private void hideRing() {
        getChildren().remove(ring);
    }

    private Button addSlotButton() {
        Button button = new Button("+");
        shape(button, TouchType.presetSize(), TouchType.presetAddFont());
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
     * <p>Unlike deleting a box from the room, this is <b>not</b> undoable (the undo stack is
     * about the room's contents), so the confirmation is the only protection there is.
     */
    private void confirmDelete(int index, Preset preset) {
        if (confirm.test("Delete preset \"" + preset.name + "\"?")) {
            state.presets.set(index, null);
            rebuild();
            onStatus.accept("Preset \"" + preset.name + "\" deleted.");
        }
    }

    /**
     * For tests: the button standing for one slot.
     *
     * <p>Found by counting buttons rather than by child position, because the warning ring is also
     * a child of this row while a finger is held on a slot.
     */
    public Button slotButton(int index) {
        return buttons().get(index);
    }

    /** For tests: the green {@code +} on the end. */
    public Button addSlotButtonNode() {
        java.util.List<Button> buttons = buttons();
        return buttons.get(buttons.size() - 1);
    }

    private java.util.List<Button> buttons() {
        java.util.List<Button> found = new java.util.ArrayList<>();
        for (javafx.scene.Node child : getChildren()) {
            if (child instanceof Button button) {
                found.add(button);
            }
        }
        return found;
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
