package com.modcritic.invmgr.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * A dialog that darkens the window behind it and takes over until it is answered.
 *
 * <p><b>Not a second window.</b> JavaFX would happily open a real modal {@code Stage}, and that
 * is the usual way to do this, but the original is a web page, so its dialogs are a dark panel
 * painted over the page, and a separate operating-system window would look and behave nothing
 * like them (its own title bar, its own drop shadow, its own idea of where to appear). This
 * draws the same overlay the original does, inside the same window.
 *
 * <p>Subclasses fill in {@link #content()} and decide what Cancel means.
 */
public abstract class ModalDialog {

    private final Overlays host;

    /** The full-window darkening, with the panel centered in it. */
    private final StackPane overlay = new StackPane();

    /**
     * The panel plus anything floating over it.
     *
     * <p>Exists for exactly one thing: {@link #addFloating}, which the Edit dialog uses to put
     * its rotate button over the panel's top-right corner rather than in the column of rows. It
     * hugs the panel, so the dialog is still sized entirely by its contents.
     */
    private final StackPane panel = new StackPane();

    /** The panel itself. Subclasses add their rows to this. */
    private final VBox box = Dialogs.box();

    private boolean showing;

    /**
     * Watches the on-screen keyboard while this dialog is up, so the panel can move clear of it.
     *
     * <p>Null on a desktop and null while nothing is showing. See {@link #watchTheKeyboard}.
     */
    private javafx.animation.AnimationTimer keyboardWatcher;

    protected ModalDialog(Overlays host) {
        this.host = host;

        panel.getChildren().add(box);
        panel.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        overlay.setAlignment(Pos.CENTER);
        overlay.getChildren().add(panel);
        overlay.setStyle("-fx-background-color: rgba(0,0,0,0.65);");

        // Escape closes, from anywhere in the dialog. Handled on the overlay rather than on
        // each field, so it works even when focus is on a button or on nothing at all.
        overlay.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                cancel();
                event.consume();
            }
        });
        // The overlay swallows clicks aimed at the app behind it -- that is what makes it
        // modal. It deliberately does NOT close when the darkened area is clicked: the
        // original has no such handler, and losing a half-filled form to a stray click
        // outside the panel would be a real annoyance.
        overlay.setOnMousePressed(event -> event.consume());
    }

    /** The panel subclasses put their rows into. */
    protected final VBox content() {
        return box;
    }

    /**
     * Puts a control on top of the panel, pinned to one of its corners or edges.
     *
     * <p>Only the Edit dialog's {@code ↻} needs this. It sits 12 px in from the top-right,
     * inside the panel's own padding, which is a position no row-based layout can produce.
     */
    protected final void addFloating(Node node, Pos alignment, Insets margin) {
        StackPane.setAlignment(node, alignment);
        StackPane.setMargin(node, margin);
        panel.getChildren().add(node);
    }

    /** Puts the dialog on screen and gives it the keyboard. */
    public void show() {
        if (showing) {
            return;
        }
        host.showDialog(overlay);
        showing = true;
        watchTheKeyboard();
        onShown();
    }

    /** Takes the dialog off screen. Safe to call when it is not showing. */
    public void hide() {
        if (!showing) {
            return;
        }
        host.hideDialog(overlay);
        showing = false;
        if (keyboardWatcher != null) {
            // ⚠ Stopping this is not tidiness. A timer left running is invisible on screen and
            // costs a phone battery for the rest of the session, which is the same hole M6.2's
            // hold ring had and the reason its test counts frames.
            keyboardWatcher.stop();
            keyboardWatcher = null;
        }
        overlay.setPadding(Insets.EMPTY);
    }

    public boolean isShowing() {
        return showing;
    }

    /**
     * Called once the dialog is on screen.
     *
     * <p>Focus has to be requested here rather than while building, because a control that is
     * not yet part of a window cannot take focus; asking earlier silently does nothing.
     */
    protected void onShown() {
    }

    /** What Escape does. Subclasses override to match their own Cancel button. */
    protected void cancel() {
        hide();
    }

    /**
     * Keeps the panel above the on-screen keyboard for as long as this dialog is up.
     *
     * <p><b>This is M6.5c's B7.</b> A dialog with a text field in it raised the keyboard over its
     * own bottom half, so the field being typed into could be the one covered up.
     *
     * <p><b>⚠ The obvious fix does not work and is not worth re-trying.</b> Android can shrink a
     * window when the keyboard appears ({@code SOFT_INPUT_ADJUST_RESIZE}), and that would move
     * this dialog with no code at all. Measured on the phone 2026-09-03: it does nothing here,
     * because the app draws edge to edge and an edge-to-edge window is never resized for the
     * keyboard. Drawing edge to edge is not negotiable; it is what makes the 3D view full-bleed.
     *
     * <p><b>Why a poll rather than being told.</b> Android reports the keyboard to the Activity,
     * and the Activity has no way to call into the app; the app is compiled machine code with
     * its own runtime, and calls only go the other way. Polling costs one question per frame and
     * only while a dialog is open, which is the cheap end of the trade.
     */
    private void watchTheKeyboard() {
        if (!AndroidBridge.isAvailable()) {
            return;
        }
        keyboardWatcher = new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                double density = javafx.stage.Screen.getPrimary().getOutputScaleY();
                overlay.setPadding(new Insets(0, 0,
                        liftFor(overlay.getHeight(), panel.getHeight(),
                                AndroidBridge.keyboardHeight(density)), 0));
            }
        };
        keyboardWatcher.start();
    }

    /**
     * How far to lift the panel off the bottom of the window, in design pixels.
     *
     * <p>Pure, and separate from the timer above, because this is the half that can be wrong on
     * any machine. The panel is centered in whatever is left after this padding, so lifting by the
     * keyboard's own height puts it in the middle of the space above it.
     *
     * <p><b>The cap is the part worth reading.</b> A panel taller than the space left over cannot
     * be centered in it without hanging off the top, where the buttons would be unreachable and
     * nothing would scroll it back. Capping the lift at {@code available - panel} pins such a
     * panel to the top instead, which loses the bottom rather than the top, and the bottom is
     * where Cancel and Confirm are, so it is still wrong, just less wrong. A dialog that tall
     * does not exist today; the cap is here so that one appearing degrades predictably.
     *
     * @param overlayHeight  the whole darkened area, which is the window
     * @param panelHeight    the dialog panel itself
     * @param keyboardHeight what the keyboard is covering, or zero when it is down
     */
    static double liftFor(double overlayHeight, double panelHeight, double keyboardHeight) {
        if (keyboardHeight <= 0 || overlayHeight <= 0) {
            return 0;
        }
        double room = overlayHeight - panelHeight;
        if (room <= 0) {
            return 0;
        }
        return Math.min(keyboardHeight, room);
    }
}
