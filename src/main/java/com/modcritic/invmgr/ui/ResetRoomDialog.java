package com.modcritic.invmgr.ui;

import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

/**
 * The windows between pressing Reset Room and the room being emptied.
 *
 * <p>The flow is the user's, decided 2026-09-24, and there are three steps to it:
 *
 * <ol>
 *   <li><b>"Make a save file first?"</b> with Cancel, No and Yes. Yes opens the app's ordinary
 *       Save. Backing out of the file picker comes back to this step.
 *   <li><b>"Save failed"</b>, only if the save did: Save to try again, and Cancel.
 *   <li><b>"Empty the room?"</b>, after No or after a save that worked: Reset and Cancel.
 * </ol>
 *
 * <p>Every Cancel leaves the room exactly as it was, and so do Escape and the phone's Back button.
 * <b>The room is emptied only by pressing Reset</b>, which is shown only once any save asked for
 * has finished; that matters on a phone, where the file picker answers some time after Yes. A tap
 * outside does nothing here, as on the dialogs from the original: the Menu closes on one, and this
 * asks something that deserves an answer.
 *
 * <p>One dialog whose contents change, not three dialogs. The Menu window closes as this opens,
 * so it replaces the Menu rather than stacking on it, and the steps replace each other the same
 * way. Every window the Menu opens works like that, so that a see-through theme later never shows
 * one window through another.
 *
 * <p>Sizes are the Menu's, 16 px type on a phone (the user, 2026-09-24), because it opens from the
 * Menu and a drop to the dialogs' 13 px would look like a glitch.
 */
public final class ResetRoomDialog extends ModalDialog {

    /**
     * How this window asks for a save file. The app answers with its ordinary Save.
     *
     * <p>An interface, so the flow can be tested with a save that succeeds, fails or is backed out
     * of on demand: the real one opens a file chooser, which no test can drive.
     */
    public interface Saver {
        /** Offers the user a save, and reports back through exactly one of the three. */
        void save(Runnable onSaved, Runnable onBackedOut, Consumer<String> onFailed);
    }

    /** Where the flow is. SAVING is the gap between Yes and the file picker's answer. */
    enum Step { ASK, SAVING, FAILED, CONFIRM }

    static final String ASK_TITLE = "Reset Room";
    static final String ASK_TEXT = "Make a save file first? After the reset, a save file is the"
            + " only way to get this room back.";
    static final String FAILED_TITLE = "Save failed";
    static final String CONFIRM_TITLE = "Reset Room";
    static final String CONFIRM_TEXT = "Empty the room? Every item goes, planned ones too, and"
            + " undo starts over. The room size, presets and switches stay as they are.";

    private final Saver saver;
    private Runnable onReset = () -> { };
    private Step step = Step.ASK;

    private final Label title = new Label();
    private final Label message = new Label();

    private final Button cancel = button("Cancel", Dialogs.ButtonKind.PLAIN);
    private final Button no = button("No", Dialogs.ButtonKind.PLAIN);
    private final Button yes = button("Yes", Dialogs.ButtonKind.CONFIRM);
    private final Button retry = button("Save", Dialogs.ButtonKind.CONFIRM);
    private final Button reset = button("Reset", Dialogs.ButtonKind.DANGER);

    public ResetRoomDialog(Overlays host, Saver saver) {
        super(host);
        this.saver = saver;

        VBox box = content();
        double width = Tokens.MENU_DIALOG_WIDTH;
        box.setMinWidth(width);
        box.setPrefWidth(width);
        box.setMaxWidth(width);
        box.setPadding(new Insets(TouchType.menuPadding()));

        title.setFont(Font.font(Tokens.FONT_FAMILY, TouchType.menuTitleFont()));
        title.setStyle("-fx-text-fill: " + Tokens.hex(Tokens.TEXT_INPUT) + ";");
        title.setMaxWidth(Double.MAX_VALUE);
        title.setPadding(new Insets(0, 0, Tokens.DIALOG_TITLE_PADDING_BOTTOM, 0));
        Dialogs.headingUnderline(title);
        VBox.setMargin(title, new Insets(0, 0, Tokens.DIALOG_TITLE_MARGIN_BOTTOM, 0));

        message.setFont(Font.font(Tokens.FONT_FAMILY, TouchType.menuProseFont()));
        message.setStyle("-fx-text-fill: " + Tokens.hex(Tokens.TEXT_DIALOG_LABEL) + ";");
        message.setWrapText(true);
        message.setMaxWidth(width - 2 * TouchType.menuPadding());
        // The dialogs' row margin, which Dialogs.buttonRow counts on: it sets its own top margin
        // 10 short of the 14 the gap should be, because in the original the row above supplies
        // those 10. Without this the last line of text sat 4 px above the buttons on the phone.
        VBox.setMargin(message, new Insets(0, 0, Tokens.DIALOG_ROW_MARGIN_BOTTOM, 0));

        cancel.setOnAction(event -> cancel());
        no.setOnAction(event -> whenAnswering(this::confirm));
        yes.setOnAction(event -> whenAnswering(this::saveFirst));
        retry.setOnAction(event -> whenAnswering(this::saveFirst));
        reset.setOnAction(event -> whenAnswering(() -> {
            hide();
            onReset.run();
        }));
    }

    /** What pressing Reset does. The app points it at emptying the room. */
    public void setOnReset(Runnable handler) {
        this.onReset = handler == null ? () -> { } : handler;
    }

    /** Starts the flow from its first question. */
    public void open() {
        ask();
        show();
    }

    @Override
    protected void onShown() {
        // For the same reason as the Menu window: Escape and the phone's Back only reach a dialog
        // when focus is inside it.
        content().requestFocus();
    }

    // ------------------------------------------------------------------ the steps

    private void ask() {
        showStep(Step.ASK, ASK_TITLE, ASK_TEXT, cancel, no, yes);
    }

    private void failed(String why) {
        showStep(Step.FAILED, FAILED_TITLE, failedText(why), cancel, retry);
    }

    private void confirm() {
        showStep(Step.CONFIRM, CONFIRM_TITLE, CONFIRM_TEXT, cancel, reset);
    }

    /**
     * Yes, or Save after a failure: hands over to the app's Save and waits for its answer.
     *
     * <p>An answer can only move the steps on; it never empties the room, which only Reset does.
     * So one that arrives after the window was closed changes a window nobody can see, and
     * {@link #open} starts from the first question again anyway.
     */
    private void saveFirst() {
        step = Step.SAVING;
        saver.save(this::confirm, this::ask, this::failed);
    }

    /**
     * The failure message, with the reason the save gave.
     *
     * <p>Package-private for the test. A reason that already ends in a period keeps just the one,
     * which is how Java's own messages are sometimes worded.
     */
    static String failedText(String why) {
        String reason = why == null || why.isBlank() ? "no reason was given" : why.strip();
        if (reason.endsWith(".")) {
            reason = reason.substring(0, reason.length() - 1);
        }
        return "The file could not be written: " + reason + ". The room has not been reset.";
    }

    private void showStep(Step next, String heading, String text, Button... buttons) {
        step = next;
        title.setText(heading);
        message.setText(text);
        content().getChildren().setAll(title, message, Dialogs.buttonRow(buttons));
    }

    /** Runs a button's job, unless a save is still out being answered. */
    private void whenAnswering(Runnable job) {
        if (step != Step.SAVING) {
            job.run();
        }
    }

    private static Button button(String text, Dialogs.ButtonKind kind) {
        return Dialogs.button(text, kind, TouchType.menuFont(), TouchType.menuButtonHeight());
    }

    // ------------------------------------------------------------ for tests

    Step step() {
        return step;
    }

    public VBox panel() {
        return content();
    }

    public Label titleLabel() {
        return title;
    }

    public Label messageLabel() {
        return message;
    }

    public Button cancelButton() {
        return cancel;
    }

    public Button noButton() {
        return no;
    }

    public Button yesButton() {
        return yes;
    }

    public Button retryButton() {
        return retry;
    }

    public Button resetButton() {
        return reset;
    }
}
