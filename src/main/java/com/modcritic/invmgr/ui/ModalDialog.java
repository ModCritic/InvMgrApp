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
 * is the usual way to do this — but the original is a web page, so its dialogs are a dark panel
 * painted over the page, and a separate operating-system window would look and behave nothing
 * like them (its own title bar, its own drop shadow, its own idea of where to appear). This
 * draws the same overlay the original does, inside the same window.
 *
 * <p>Subclasses fill in {@link #content()} and decide what Cancel means.
 */
public abstract class ModalDialog {

    private final Overlays host;

    /** The full-window darkening, with the panel centred in it. */
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
        onShown();
    }

    /** Takes the dialog off screen. Safe to call when it is not showing. */
    public void hide() {
        if (!showing) {
            return;
        }
        host.hideDialog(overlay);
        showing = false;
    }

    public boolean isShowing() {
        return showing;
    }

    /**
     * Called once the dialog is on screen.
     *
     * <p>Focus has to be requested here rather than while building, because a control that is
     * not yet part of a window cannot take focus — asking earlier silently does nothing.
     */
    protected void onShown() {
    }

    /** What Escape does. Subclasses override to match their own Cancel button. */
    protected void cancel() {
        hide();
    }

    /** The overlay node, for tests that need to find the dialog in the scene. */
    public StackPane overlayNode() {
        return overlay;
    }
}
