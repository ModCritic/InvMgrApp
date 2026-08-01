package com.modcritic.invmgr.ui;

import java.util.Optional;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Control;
import javafx.scene.control.Tooltip;
import javafx.scene.text.Font;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

/**
 * Two small things the browser gave the original for free, and Java does not: the little
 * explanatory label that appears when you rest on a button, and the "are you sure?" box.
 *
 * <p>The original writes {@code title="Fit to Screen"} on a button and the browser handles the
 * rest, and calls {@code confirm(...)} and the browser puts up a dialog. Neither exists in
 * JavaFX, so both are built here — in one place, so every button's hint looks the same.
 */
public final class Hints {

    /**
     * How long the pointer has to rest before a hint appears.
     *
     * <p>Browsers use around half a second and JavaFX defaults to a full one, which feels
     * sluggish next to the original. Matching the browser is the point.
     */
    private static final Duration DELAY = Duration.millis(500);

    /**
     * How a hint is painted — <b>every measurement in it relative to the font size</b>.
     *
     * <p>The colours are the item tooltip's: near-black on a grey hairline, rather than JavaFX's
     * pale default, which would be the only light-coloured thing in the window.
     *
     * <p><b>Why the sizes are in {@code em} and not in pixels.</b> A hint has to grow with the
     * Ctrl+scroll interface zoom, and unlike everything else in the app it cannot simply be
     * carried along by the zoom's transform — see {@link #tooltip}. So the zoom has to be applied
     * to the hint by hand, and there is exactly one property that can carry it: the font.
     * Measured, on a real popup: <b>a tooltip's stylesheet is processed once, on its first
     * showing, and never again.</b> Later {@code setStyle} calls are stored and ignored, and an
     * explicit {@code applyCss()} does not dislodge them either. Its <em>font</em>, by contrast,
     * is a plain JavaFX property that the skin binds to its label, so it takes effect whenever it
     * is set — even while the hint is on screen.
     *
     * <p>An {@code em} is a multiple of the font size, and JavaFX re-resolves em-valued CSS when
     * the font moves. So writing the padding and the border in em makes the one property that
     * still works drive all three: set the font, and the box around it follows. Verified across
     * the whole zoom ladder — at 50% the padding measures 2.5/5 with a 0.5 px border, at 200%
     * 10/20 with a 2 px border, and at 100% exactly the {@link Tokens#TOOLTIP_PADDING_V} and
     * {@link Tokens#TOOLTIP_PADDING_H} the item tooltip uses.
     *
     * <p>Do not "tidy" these into pixels. Pixel values apply once and then stay put, which is the
     * bug this replaced: the hint's text grew and its padding did not.
     */
    private static final String STYLE =
            "-fx-background-color: " + Tokens.hex(Tokens.TOOLTIP_BG) + ";"
            + "-fx-text-fill: " + Tokens.hex(Tokens.TEXT_INPUT) + ";"
            + "-fx-border-color: " + Tokens.hex(Tokens.TOOLTIP_BORDER) + ";"
            + "-fx-border-width: " + em(1) + ";"
            + "-fx-padding: " + em(Tokens.TOOLTIP_PADDING_V) + " " + em(Tokens.TOOLTIP_PADDING_H)
            + ";"
            + "-fx-background-radius: 0; -fx-border-radius: 0;";

    private Hints() {
    }

    /** A measurement in pixels, written as the multiple of the hint's own font size that it is. */
    private static String em(double pixels) {
        return (pixels / Tokens.FONT_TOOLTIP) + "em";
    }

    /**
     * Gives a button a hint, and hands it back so a test can look at it.
     *
     * <p><b>It resizes with the interface zoom, and it has to do that the hard way.</b> Every
     * other control in the app is inside one {@code Scale} transform (see {@code App} and
     * {@link UiScale}), so Ctrl+scroll carries it along and no measurement anywhere has to know
     * the zoom exists. A JavaFX {@link Tooltip} is not a control in the window at all — it is a
     * <em>separate popup window</em> with its own scene, which is precisely why it can hang over
     * the edge of the app. Being its own window, it sits outside the transform, so it stayed at
     * its 100% size at every zoom level while the button it belonged to grew. Reported by the
     * user on 2026-08-01, against the M3.2.4 jar.
     *
     * <p>The zoom is therefore read off the button the hint belongs to:
     * {@code getLocalToSceneTransform()} is the accumulated effect of every transform between that
     * button and the scene, which is the zoom and nothing else. That keeps the whole thing local —
     * no wiring through {@code App}, no shared mutable "current scale" for something to forget to
     * update — and it stays correct if a transform is ever added somewhere above. It is read at
     * <b>showing</b> time rather than at construction, because a hint is built while the window is
     * being assembled and the zoom can change any number of times afterwards.
     *
     * <p><b>Why the button is passed in rather than asked for.</b> This method used to be
     * {@code tooltip(String)} and got the button from {@code Tooltip.getOwnerNode()} at showing
     * time. <b>That is null in the running app and always will be</b>, so the zoom read as 1 and
     * the hint never grew — the same bug, reported again on 2026-08-01 against the M3.2.5 jar that
     * was supposed to have fixed it. A popup records an owner <em>node</em> only when it is shown
     * with {@code show(Node, x, y)}; shown with {@code show(Window, x, y)} it records an owner
     * window and leaves the node null. Disassembling JavaFX 21's own {@code TooltipBehavior} — the
     * thing that counts your half-second rest and puts the hint up — shows all four of its show
     * calls take the {@code Window} form. There is no public way to ask a tooltip which control it
     * was installed on, so the only reliable answer is to keep hold of it here.
     *
     * <p>It also installs the hint rather than returning it to be installed, which is not tidiness:
     * with two separate calls, a hint could be built against one button and set on another, and it
     * would then quietly report the wrong zoom.
     */
    public static Tooltip attach(Control owner, String text) {
        Tooltip hint = new Tooltip(text);
        hint.setShowDelay(DELAY);
        hint.setStyle(STYLE);
        hint.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_TOOLTIP));
        // addEventHandler rather than setOnShowing, so this cannot be silently replaced by a
        // caller that wants to know when its own hint appears.
        hint.addEventHandler(WindowEvent.WINDOW_SHOWING, event -> hint.setFont(
                Font.font(Tokens.FONT_FAMILY, Tokens.FONT_TOOLTIP * interfaceScaleOf(owner))));
        owner.setTooltip(hint);
        return hint;
    }

    /**
     * How much larger than its stated size a node is currently being drawn.
     *
     * <p>{@code getMxx} is the horizontal scale of the accumulated transform. The zoom scales both
     * axes by the same factor, so one of them is the answer; reading both and averaging them would
     * only hide it if that ever stopped being true.
     *
     * @param owner the button the hint belongs to
     * @return the scale, or 1 if there is nothing to read it from
     */
    private static double interfaceScaleOf(Node owner) {
        if (owner.getScene() == null) {
            // A hint cannot appear over a button that is not in a window, so this is unreachable
            // from the app itself; it is here because getLocalToSceneTransform on a detached node
            // reports the transforms up to whatever it happens to be hanging off, which would be a
            // silently wrong number rather than an obviously wrong one.
            return 1;
        }
        double scale = owner.getLocalToSceneTransform().getMxx();
        // A zero or negative scale would mean a zero or negative font size. Nothing in the app
        // produces one, and a hint at its normal size is a far better failure than an exception
        // thrown from inside a popup's show.
        return scale > 0 ? scale : 1;
    }

    /**
     * Asks a yes/no question and waits for the answer.
     *
     * <p>Used before deleting a preset, which cannot be undone.
     *
     * @param scene the scene to hang the question off, so it appears over the app rather than
     *     wherever the window manager fancies. May be null, in which case it still works.
     * @return true if the user said yes
     */
    public static boolean confirm(Scene scene, String question) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, question,
                ButtonType.CANCEL, ButtonType.OK);
        alert.setHeaderText(null);
        alert.setTitle("InvMgr");
        if (scene != null && scene.getWindow() != null) {
            alert.initOwner(scene.getWindow());
        }
        Optional<ButtonType> answer = alert.showAndWait();
        return answer.isPresent() && answer.get() == ButtonType.OK;
    }
}
