package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Consumer;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Every branch of Reset Room's flow, with a save the test answers by hand.
 *
 * <p>The real save opens a file chooser, which no test can drive. So the dialog is built on its
 * own here and given a {@link FakeSaver} that remembers what it was asked and waits to be told
 * the outcome, the way Android's picker answers some time after Yes. The app-level wiring, and the
 * real save on a phone, are checked elsewhere: {@code ResetRoomAppTest} and the device suite.
 */
class ResetRoomDialogTest extends ApplicationTest {

    /** A save that does nothing until the test says how it went. */
    static final class FakeSaver implements ResetRoomDialog.Saver {
        int asked;
        Runnable saved;
        Runnable backedOut;
        Consumer<String> failed;

        @Override
        public void save(Runnable onSaved, Runnable onBackedOut, Consumer<String> onFailed) {
            asked++;
            saved = onSaved;
            backedOut = onBackedOut;
            failed = onFailed;
        }
    }

    private final FakeSaver saver = new FakeSaver();
    private ResetRoomDialog dialog;
    private int resets;

    @Override
    public void start(Stage stage) {
        Overlays host = new Overlays(new Pane());
        dialog = new ResetRoomDialog(host, saver);
        dialog.setOnReset(() -> resets++);
        stage.setScene(new Scene(host, 900, 700));
        stage.setX(0);
        stage.setY(0);
        stage.show();
    }

    private void open() {
        interact(dialog::open);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Answers the pending save on the JavaFX thread, as Android's picker does. */
    private void answer(Runnable outcome) {
        interact(outcome);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** The buttons the dialog is showing right now, left to right. */
    private List<String> buttons() {
        FlowPane row = (FlowPane) dialog.panel().getChildren().get(2);
        return row.getChildren().stream().map(node -> ((Button) node).getText()).toList();
    }

    private void assertStep(ResetRoomDialog.Step step, String title, List<String> buttons) {
        assertEquals(step, dialog.step());
        assertEquals(title, dialog.titleLabel().getText());
        assertEquals(buttons, buttons());
    }

    // ------------------------------------------------------------------ the paths

    @Test
    @DisplayName("it opens asking about a save, with Cancel, No and Yes")
    void itOpensOnTheQuestion() {
        open();
        assertTrue(dialog.isShowing());
        assertStep(ResetRoomDialog.Step.ASK, "Reset Room", List.of("Cancel", "No", "Yes"));
        assertEquals(ResetRoomDialog.ASK_TEXT, dialog.messageLabel().getText());
    }

    @Test
    @DisplayName("No goes straight to the last window, and Reset empties the room")
    void noThenReset() {
        open();
        clickOn(dialog.noButton());
        assertStep(ResetRoomDialog.Step.CONFIRM, "Reset Room", List.of("Cancel", "Reset"));
        assertEquals(0, saver.asked, "No offers no save");

        clickOn(dialog.resetButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, resets);
        assertFalse(dialog.isShowing());
    }

    @Test
    @DisplayName("Yes saves, and only once the save has worked does Reset appear")
    void yesThenSavedThenReset() {
        open();
        clickOn(dialog.yesButton());
        assertEquals(1, saver.asked);
        assertEquals(ResetRoomDialog.Step.SAVING, dialog.step(),
                "nothing moves on until the save answers");
        assertEquals(0, resets);

        answer(saver.saved);
        assertStep(ResetRoomDialog.Step.CONFIRM, "Reset Room", List.of("Cancel", "Reset"));

        clickOn(dialog.resetButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, resets);
    }

    @Test
    @DisplayName("backing out of the file picker comes back to the first window")
    void backingOutReturnsToTheQuestion() {
        open();
        clickOn(dialog.yesButton());
        answer(saver.backedOut);

        assertStep(ResetRoomDialog.Step.ASK, "Reset Room", List.of("Cancel", "No", "Yes"));
        assertEquals(0, resets);
    }

    @Test
    @DisplayName("a failed save says why and offers Save again, which saves again")
    void aFailedSaveOffersToTryAgain() {
        open();
        clickOn(dialog.yesButton());
        answer(() -> saver.failed.accept("No space left on device"));

        assertStep(ResetRoomDialog.Step.FAILED, "Save failed", List.of("Cancel", "Save"));
        assertEquals("The file could not be written: No space left on device. The room has not"
                + " been reset.", dialog.messageLabel().getText());
        assertEquals(0, resets);

        clickOn(dialog.retryButton());
        assertEquals(2, saver.asked, "Save asks for the save again");
        answer(saver.saved);
        assertStep(ResetRoomDialog.Step.CONFIRM, "Reset Room", List.of("Cancel", "Reset"));
    }

    // ------------------------------------------------------------- leaving it be

    @Test
    @DisplayName("Cancel at every step leaves the room alone")
    void cancelEverywhere() {
        open();
        clickOn(dialog.cancelButton());
        assertFalse(dialog.isShowing(), "Cancel on the question");

        open();
        clickOn(dialog.noButton());
        clickOn(dialog.cancelButton());
        assertFalse(dialog.isShowing(), "Cancel on the last window");

        open();
        clickOn(dialog.yesButton());
        answer(() -> saver.failed.accept("disk full"));
        clickOn(dialog.cancelButton());
        assertFalse(dialog.isShowing(), "Cancel after a failed save");

        assertEquals(0, resets, "and not one of them emptied the room");
    }

    @Test
    @DisplayName("Escape is Cancel, which makes the phone's Back button Cancel too")
    void escapeCancels() {
        // ⚠ This cannot tell whether onShown put focus in the window: in a scene holding nothing
        // else, JavaFX gives the dialog's first button focus by itself. ResetRoomAppTest checks
        // that inside the real app, where focus starts on the room behind.
        open();
        type(KeyCode.ESCAPE);
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(dialog.isShowing(), "Escape reaches the window only if focus is inside it");
        assertEquals(0, resets);
    }

    @Test
    @DisplayName("a tap outside does nothing: this window wants an answer")
    void anOutsideTapIsIgnored() {
        open();
        Bounds panel = dialog.panel().localToScreen(dialog.panel().getLayoutBounds());
        Point2D outside = new Point2D(panel.getMinX() - 40, panel.getMinY() - 40);
        clickOn(outside);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(dialog.isShowing());
        assertEquals(ResetRoomDialog.Step.ASK, dialog.step());
    }

    @Test
    @DisplayName("while a save is out, the other buttons do nothing")
    void buttonsWaitForTheSave() {
        open();
        clickOn(dialog.yesButton());
        clickOn(dialog.noButton());
        assertEquals(ResetRoomDialog.Step.SAVING, dialog.step(), "No did not jump ahead of the save");
        clickOn(dialog.yesButton());
        assertEquals(1, saver.asked, "and a second Yes did not open a second picker");
    }

    @Test
    @DisplayName("a save that answers after the window was closed empties nothing")
    void aLateAnswerEmptiesNothing() {
        open();
        clickOn(dialog.yesButton());
        type(KeyCode.ESCAPE);
        WaitForAsyncUtils.waitForFxEvents();
        answer(saver.saved);

        assertFalse(dialog.isShowing(), "the window stays closed");
        assertEquals(0, resets, "only Reset empties the room, and nobody pressed it");

        open();
        assertStep(ResetRoomDialog.Step.ASK, "Reset Room", List.of("Cancel", "No", "Yes"));
    }

    @Test
    @DisplayName("the failure message keeps one period, and copes with no reason at all")
    void theFailureMessage() {
        assertEquals("The file could not be written: Permission denied. The room has not been"
                + " reset.", ResetRoomDialog.failedText("Permission denied."));
        assertEquals("The file could not be written: no reason was given. The room has not been"
                + " reset.", ResetRoomDialog.failedText(null));
    }

    @Test
    @DisplayName("the Reset button is the red one")
    void resetIsRed() {
        open();
        clickOn(dialog.noButton());
        Node reset = dialog.resetButton();
        assertTrue(reset.getStyle().contains(Tokens.hex(Tokens.BUTTON_DANGER_BG)),
                "Reset wears the danger red, like Delete: " + reset.getStyle());
    }
}
