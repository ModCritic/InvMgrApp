package com.modcritic.invmgr.ui;

import java.util.function.Consumer;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;

/**
 * A multi-line text box that already has its skin when it reaches the window. Every multi-line
 * box in the app is one of these, and {@code SkinnedTextAreaTest} fails if a plain
 * {@code TextArea} appears anywhere else in the source.
 *
 * <p><b>The bug it exists for.</b> On the phone, the Add dialog's Name box and the Feedback box
 * drew their text black on their #1a1a1a background as soon as focus left them, and #ddd again
 * while focused. The text node really was black: JavaFX had put every color the skin owns (the
 * text, the placeholder, the selection) back to its built-in default. A skin is the part of a
 * control that builds what you see, and those colors are properties of the skin, not of the box.
 *
 * <p><b>Why only the phone.</b> JavaFX's Android build adds a stylesheet, {@code android.css},
 * that hands text boxes their Android skin through CSS ({@code -fx-skin}). That skin arrives in
 * the middle of a CSS pass, after JavaFX has already listed which properties it is going to
 * style, so the style set JavaFX saves for the box's state at that moment has none of the skin's
 * colors in it. JavaFX notices the new skin and fills them in on the next pass, but only for the
 * state the box is in by then. The Add dialog focuses Name and the Feedback page focuses its box
 * right after that first pass, so the repair lands on the focused state and the saved unfocused
 * one stays without the colors. The first time focus leaves, JavaFX reuses that saved set, finds
 * no text color in it, and resets the color to its default, which is black. A desktop never
 * gets here: it has no {@code -fx-skin} rule, and when JavaFX makes a default skin it styles it
 * again in the same pass. Read out of the shipped {@code CssStyleHelper.transitionToState} and
 * {@code Control.doProcessCSS}, and reproduced on the desktop by {@code SkinnedTextAreaTest}.
 *
 * <p><b>What this does about it.</b> The constructor stands the box in a scene of its own for
 * one CSS pass, which installs whatever skin the platform asks for, the Android one on a phone
 * and the default one on a desktop, and then takes it out again. JavaFX keeps saved style sets
 * per scene, so the incomplete one from that pass stays with the throwaway scene and goes when
 * it does. By the time the box joins the real window it has its skin, every style set JavaFX
 * saves for it there includes the skin's colors, and {@code android.css} asking for the same
 * skin class again changes nothing.
 *
 * <p><b>Gotcha:</b> build it on the JavaFX thread, like any other control here. A scene is made
 * in the constructor.
 */
public class SkinnedTextArea extends TextArea {

    public SkinnedTextArea() {
        Group stand = new Group(this);
        // A scene makes CSS apply to the stand; nothing needs to hold on to it afterwards.
        new Scene(stand);
        applyCss();
        stand.getChildren().clear();
    }

    /**
     * Runs {@code action} on the scroll pane this box scrolls its text with, now and again for any
     * skin that replaces the current one.
     *
     * <p>That scroll pane belongs to the skin, so it exists only once there is a skin, and a new
     * skin brings a new one. This is how a long message gets what every other scroll pane in the
     * app has: {@code ScrollEnds}, so it stops at its ends, and {@code ListCoast}, so it coasts
     * after a flick.
     */
    public void onScrollPane(Consumer<ScrollPane> action) {
        forScrollPaneOfSkin(action);
        skinProperty().addListener((observable, before, after) -> forScrollPaneOfSkin(action));
    }

    private void forScrollPaneOfSkin(Consumer<ScrollPane> action) {
        if (getSkin() == null) {
            return;
        }
        for (Node child : getChildrenUnmodifiable()) {
            if (child instanceof ScrollPane inside) {
                action.accept(inside);
            }
        }
    }
}
