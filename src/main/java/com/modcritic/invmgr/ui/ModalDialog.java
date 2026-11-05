package com.modcritic.invmgr.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
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

    /** Where the last press on this dialog landed, in the scene. */
    private double pressX;
    private double pressY;

    /**
     * Whether the press under way can still end as a tap outside the panel: it landed on the
     * darkened area itself and has not moved further than {@link Tokens#TAP_SLOP} since.
     */
    private boolean tapOutside;

    /**
     * The strip around the window this dialog keeps clear of, usually nothing.
     *
     * <p>Only the Menu window sets it, to the phone's own bars, so that it centers in the part of
     * the glass the app actually has. The dialogs from the original center in the whole window,
     * as they always have. See {@link #setSafeArea}.
     */
    private Insets safeArea = Insets.EMPTY;

    /**
     * Watches the on-screen keyboard while this dialog is up, so the panel can move clear of it.
     *
     * <p>Null on a desktop and null while nothing is showing. See {@link #watchTheKeyboard}.
     */
    private javafx.animation.AnimationTimer keyboardWatcher;

    /** The mark every dialog's overlay carries, so a dialog can tell it was opened from another. */
    private static final String OVERLAY_MARK = "invmgr.dialogOverlay";

    /**
     * Where the focus goes back to when this dialog closes, or null for the window's root.
     *
     * <p><b>Inside another dialog</b> (Save Preset opened from a preset slot on Add Item): back
     * there, whatever it was. Without it Escape stopped closing Add Item, because Escape goes to
     * whatever has the focus and closing Save Preset had taken the focus out of the window.
     *
     * <p><b>Anywhere else</b>, back to what had it (the room, or the button that opened the
     * dialog) unless that was a text box: on a phone a text box with the focus can bring the
     * keyboard up when nobody asked for it.
     */
    private javafx.scene.Node returnFocusTo;

    protected ModalDialog(Overlays host) {
        this.host = host;

        panel.getChildren().add(box);
        panel.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        overlay.setAlignment(Pos.CENTER);
        overlay.getChildren().add(panel);
        overlay.setStyle("-fx-background-color: rgba(0,0,0,0.65);");
        overlay.getProperties().put(OVERLAY_MARK, Boolean.TRUE);
        // The phone's Next key moves among this dialog's number boxes only, and never to the
        // room's W, L and H boxes in the top bar behind it.
        AndroidBridge.keepsNextInside(overlay);

        // Escape closes, from anywhere in the dialog. Handled on the overlay rather than on
        // each field, so it works even when focus is on a button or on nothing at all.
        overlay.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                cancel();
                event.consume();
            }
        });
        // The overlay swallows clicks aimed at the app behind it -- that is what makes it
        // modal. The dialogs from the original deliberately do NOT close when the darkened area is
        // clicked: the original has no such handler, and losing a half-filled form to a stray
        // click outside the panel would be a real annoyance. A dialog with no form in it can ask
        // to; see closesOnOutsideTap.
        overlay.setOnMousePressed(event -> event.consume());
        // Only a press that lands outside the panel and stays put is a tap outside. Found on the
        // phone on 2026-10-31: a finger that scrolled a Menu page and lifted past the panel's edge
        // went back a page. JavaFX sends a release that ends on a different node from its press a
        // CLICKED aimed at the nearest node holding both, and for a press inside the panel and a
        // release outside it that node is the overlay. Watched in filters, so the panel's own
        // nodes still see every event.
        overlay.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            pressX = event.getSceneX();
            pressY = event.getSceneY();
            tapOutside = event.getTarget() == overlay;
        });
        overlay.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (Math.hypot(event.getSceneX() - pressX, event.getSceneY() - pressY) > Tokens.TAP_SLOP) {
                tapOutside = false;
            }
        });
        // On CLICKED, not PRESSED, so the whole press and release land on the overlay before it
        // goes away. On a desktop the two measured the same (MenuWindowAppTest's click-outside
        // case passes with either), so this is a precaution rather than a fix: on a phone the
        // release is a synthesized mouse event (a finger arrives twice on Android, as a touch and
        // as a mouse copy), and nothing here has shown where one goes when the node it was
        // pressed on has gone. The target test is what keeps a click on the panel itself, or on
        // a button in it, from counting as outside.
        overlay.setOnMouseClicked(event -> {
            if (event.getTarget() == overlay && tapOutside && closesOnOutsideTap()) {
                cancel();
            }
            event.consume();
        });
    }

    /**
     * Whether a tap on the darkened area outside the panel closes this dialog, as Cancel would.
     *
     * <p>No by default, for the reason given in the constructor. The Menu window says yes, because
     * it holds nothing that a stray tap could lose.
     */
    protected boolean closesOnOutsideTap() {
        return false;
    }

    /**
     * Keeps the panel clear of a strip around the window, and centers it in what is left.
     *
     * <p>Added for the Menu window, which on a phone must center between the status bar and the
     * navigation bar rather than in the whole glass: on its side the phone has only 336 px below
     * the status bar and the window is 334, so centering in the full 360 would push its top edge
     * up under the clock. Safe to call while showing; rotation does exactly that.
     */
    protected final void setSafeArea(Insets area) {
        safeArea = area == null ? Insets.EMPTY : area;
        overlay.setPadding(safeArea);
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
        javafx.scene.Node owner = host.getScene() == null ? null : host.getScene().getFocusOwner();
        returnFocusTo = insideADialog(owner)
                || (owner != null && !(owner instanceof javafx.scene.control.TextInputControl))
                ? owner : null;
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
        // The focus is placed BEFORE the panel goes. Taken out with the focus still in it,
        // JavaFX hands the focus to the next control it finds, which is the room's W box, and a
        // text box selects its text when the focus arrives that way: closing any dialog left the
        // room's width highlighted. So it goes where returnFocusTo says, and otherwise to the
        // window's own root, which shows nothing and still passes keys on to the app.
        if (returnFocusTo != null && returnFocusTo.getScene() != null) {
            returnFocusTo.requestFocus();
        } else {
            host.requestFocus();
        }
        returnFocusTo = null;
        host.hideDialog(overlay);
        showing = false;
        if (keyboardWatcher != null) {
            // ⚠ Stopping this is not tidiness. A timer left running is invisible on screen and
            // costs a phone battery for the rest of the session, which is the same hole the
            // canvas's hold ring animation once had and the reason its test counts frames.
            keyboardWatcher.stop();
            keyboardWatcher = null;
        }
        overlay.setPadding(safeArea);
    }

    /** Whether a node sits in a dialog's overlay. */
    private static boolean insideADialog(javafx.scene.Node node) {
        for (javafx.scene.Node at = node; at != null; at = at.getParent()) {
            if (Boolean.TRUE.equals(at.getProperties().get(OVERLAY_MARK))) {
                return true;
            }
        }
        return false;
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
     * <p><b>The bug this fixes:</b> a dialog with a text field in it raised the keyboard over its
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
                double lift = liftFor(overlay.getHeight() - safeArea.getTop(), panel.getHeight(),
                        AndroidBridge.keyboardHeight(density));
                // The larger of the two, not their sum: the keyboard rises from the bottom edge of
                // the glass, over the navigation bar, so it already covers the strip the safe
                // area keeps clear there.
                overlay.setPadding(new Insets(safeArea.getTop(), safeArea.getRight(),
                        Math.max(safeArea.getBottom(), lift), safeArea.getLeft()));
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
