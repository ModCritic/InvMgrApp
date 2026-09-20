package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * What happens to a number box's contents when focus lands on it.
 *
 * <p><b>Why this is four cases and not one.</b> The original does three different things, and
 * until M6.7c this app did the same thing in all three situations and so got two of them wrong.
 * The user found it on the phone: the keypad appends, so typing 24 into a box already holding
 * 12 gives 1224, which clamps to {@code MAX_DIMENSION_IN} and silently makes a 1000 inch box.
 *
 * <p>The rules, and where each comes from in {@code docs/original/InvMgr_V1.3.0.html}:
 *
 * <ul>
 *   <li><b>A tap on a phone, on a dialog box, selects everything.</b> Original 1760-1764, a
 *       focus listener on {@code new-w}, {@code new-l} and {@code new-h} guarded by
 *       {@code isTouch()}. Explicit code, not a browser default.
 *   <li><b>A tap on a phone, on a room box, selects nothing.</b> The original's listener names
 *       the three Add-dialog boxes and no others.
 *   <li><b>A click on a desktop selects nothing.</b> What a browser does.
 *   <li><b>A Tab on a desktop selects everything.</b> What a browser does natively.
 * </ul>
 *
 * <p><b>Only the first two are this app's doing.</b> A probe against a bare JavaFX
 * {@code TextField} on 2026-09-19 printed exactly the browser's answers with no help at all:
 * {@code requestFocus} selected the contents, a click selected nothing, and a click on an
 * already-focused box selected nothing. So the last two tests below pin a <em>dependency's</em>
 * behavior, not ours. They are worth keeping for that: the app now relies on JavaFX doing this,
 * where before M6.7c it overrode it, and a future toolkit that stops would break the app
 * silently. A mutation of this project's own code cannot make them fail, and that is expected
 * rather than a blind spot.
 *
 * <p>⚠ <b>Every assertion here has to be made after the skin has had its turn.</b> The text
 * field's skin selects everything on focus <em>after</em> a focus listener runs, which is why
 * the production code queues its answer with {@code Platform.runLater}. A test that reads the
 * selection without pumping the queue first reads the value from before the skin, and passes
 * for the wrong reason.
 */
class NumberFieldFocusTest extends ApplicationTest {

    private NumberField roomBox;
    private NumberField dialogBox;
    private NumberField secondDialogBox;

    @Override
    public void start(Stage stage) {
        // Built on a desktop so the dialog boxes take their desktop sizing; the platform is
        // switched per test below, and only the focus rule reads it, at focus time.
        System.setProperty(Device.OVERRIDE_PROPERTY, "desktop");
        roomBox = new NumberField(1, 100);
        roomBox.setValue(12);
        dialogBox = NumberField.dialogField();
        dialogBox.setValue(12);
        secondDialogBox = NumberField.dialogField();
        secondDialogBox.setValue(34);

        // A focus sink first in the order, because the real window has one: App gives the
        // canvas focus at startup (App.java, canvas.requestFocus()) so that no number box
        // takes the window's automatic first focus. Without it here, roomBox would take that
        // focus, the skin would select its contents, and the desktop-traversal rule below
        // would correctly leave the selection alone, which is exactly the "app opens with 12
        // highlighted" bug. That the real app avoids it is asserted in AppUiTest, against the
        // real wiring, rather than mocked up here.
        javafx.scene.control.Button focusSink = new javafx.scene.control.Button("sink");
        stage.setScene(new Scene(
                new VBox(focusSink, roomBox, dialogBox, secondDialogBox), 400, 240));
        stage.show();
    }

    @AfterEach
    void putThePlatformBack() {
        System.clearProperty(Device.OVERRIDE_PROPERTY);
    }

    private static void onAPhone() {
        System.setProperty(Device.OVERRIDE_PROPERTY, "android");
    }

    private static void onADesktop() {
        System.setProperty(Device.OVERRIDE_PROPERTY, "desktop");
    }

    /** Focus by traversal: no press, which is what a Tab looks like from in here. */
    private void tabInto(NumberField box) {
        interact(() -> box.textField().requestFocus());
        settle();
    }

    /** Focus by a press on the box itself, which is what a click or a tap looks like. */
    private void pressInto(NumberField box) {
        clickOn(box.textField());
        settle();
    }

    /**
     * Lets the skin take its turn and then the queued answer take its.
     *
     * <p>Two pumps rather than one, deliberately: the production code queues its work from
     * inside the focus listener, so the queued block does not exist yet when the first pump
     * starts. One pump reads the selection halfway through the handover.
     */
    private void settle() {
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private String selectionIn(NumberField box) {
        return box.textField().getSelectedText();
    }

    @Test
    @DisplayName("on a phone, tapping a dialog box selects it so typing replaces the number")
    void aTapOnAPhoneSelectsADialogBox() {
        onAPhone();
        pressInto(dialogBox);
        assertEquals("12", selectionIn(dialogBox),
                "a tap must select the whole number, or the keypad appends to it and the "
                        + "result clamps to MAX_DIMENSION_IN");
    }

    @Test
    @DisplayName("on a phone, the keypad's Next selects the box it lands on")
    void nextOnAPhoneSelectsTheBoxItLandsOn() {
        onAPhone();
        // Next moves focus without a press, the same way the original's Next does; its comment
        // says the one listener covers both because both fire an ordinary focus event.
        tabInto(secondDialogBox);
        assertEquals("34", selectionIn(secondDialogBox),
                "arriving from Next must select, exactly as a tap does");
    }

    @Test
    @DisplayName("on a phone, tapping a room box selects nothing, as the original leaves it")
    void aTapOnAPhoneLeavesARoomBoxAlone() {
        onAPhone();
        pressInto(roomBox);
        assertEquals("", selectionIn(roomBox),
                "the original's focus listener names the Add dialog's boxes and no others, so "
                        + "the room boxes must keep their caret");
    }

    @Test
    @DisplayName("on a desktop, clicking puts a caret in and selects nothing")
    void aClickOnADesktopSelectsNothing() {
        onADesktop();
        pressInto(dialogBox);
        assertEquals("", selectionIn(dialogBox),
                "a browser puts a caret where you click, and JavaFX agrees; if this ever fails "
                        + "the toolkit has changed and NumberField has to compensate again");
    }

    @Test
    @DisplayName("on a desktop, tabbing in selects everything, as a browser does")
    void aTabOnADesktopSelectsEverything() {
        onADesktop();
        tabInto(dialogBox);
        assertEquals("12", selectionIn(dialogBox),
                "a browser selects on tab-to-focus and JavaFX agrees; until M6.7c this app's "
                        + "own blanket deselect threw that answer away");
    }

    @Test
    @DisplayName("a box nothing has focused holds no selection")
    void anUntouchedBoxHoldsNoSelection() {
        settle();
        assertTrue(selectionIn(roomBox).isEmpty() && selectionIn(dialogBox).isEmpty(),
                "a box that has never had focus must not be showing a selection");
    }
}
