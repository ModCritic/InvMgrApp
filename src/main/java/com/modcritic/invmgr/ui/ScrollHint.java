package com.modcritic.invmgr.ui;

import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * A scrollbar a phone actually draws.
 *
 * <p><b>JavaFX will not show one on a touch screen, whatever it is asked.</b> Read out of JavaFX
 * 21's {@code ScrollPaneSkin} bytecode on 2026-09-25: when touch is supported, its
 * {@code determineVerticalSBVisible} shows the bar only while a finger is scrolling
 * ({@code tempVisibility}), and never reads the scroll pane's bar policy at all, so
 * {@code ScrollBarPolicy.ALWAYS} changes nothing. The phone showed the result in the About
 * window: a privacy policy longer than its page, with no sign there was more.
 *
 * <p>The user decided on 2026-09-25 that a list that scrolls in the Menu shows a scrollbar. So on
 * a touch screen this draws one of its own: a thin thumb just past the page's right-hand edge, in
 * the window's margin, as long as the visible share of the content and as far down as the scroll
 * has gone. Outside the page so it never covers what it scrolls: {@link Tokens#SCROLL_HINT_GAP}.
 * It is only a picture, and takes no touches; a finger scrolls the content as before. It shows
 * only when there is more than fits. JavaFX's own bar, which a touch screen shows only while a
 * finger is scrolling, is hidden, so there is one bar rather than two.
 *
 * <p><b>Only on a touch screen</b>, decided the way JavaFX decides it:
 * {@code Platform.isSupported(ConditionalFeature.INPUT_TOUCH)}, which is exactly what the skin's
 * {@code IS_TOUCH_SUPPORTED} is (its static initializer, read the same day). A desktop draws its
 * own scrollbar, and a second one beside it would be wrong. That includes the desktop pretending
 * to be a phone for the layout tests, which has no touch screen; the tests ask for the thumb
 * directly instead.
 */
public final class ScrollHint {

    private ScrollHint() {
    }

    /** The scroll pane, with a thumb over its edge if this is a touch screen. */
    public static Node around(ScrollPane scroller) {
        return around(scroller, Platform.isSupported(ConditionalFeature.INPUT_TOUCH));
    }

    /**
     * The same, told whether to draw the thumb. Package-private for the tests, which run on a
     * desktop and still need to see it.
     */
    static Node around(ScrollPane scroller, boolean draw) {
        if (!draw) {
            return scroller;
        }
        Region thumb = new Region();
        thumb.setId(THUMB_ID);
        thumb.setStyle("-fx-background-color: " + Tokens.hex(Tokens.SCROLL_HINT) + ";"
                + "-fx-background-radius: " + Tokens.SCROLL_HINT_WIDTH / 2 + ";");
        thumb.setManaged(false);

        Pane overlay = new Pane(thumb);
        overlay.setMouseTransparent(true);
        overlay.setPickOnBounds(false);

        // Ours replaces JavaFX's own, which on a touch screen still flashes up while a finger is
        // scrolling and fades a second later. Side by side the two looked wrong (the user,
        // 2026-09-26). The skin never touches its bars' opacity, so zero stays zero; and the
        // invisible bar takes no touches either, so a finger near the edge reaches the page.
        ScrollEnds.forEachBar(scroller, bar -> {
            bar.setOpacity(0);
            bar.setMouseTransparent(true);
        });

        StackPane stack = new StackPane(scroller, overlay);
        Runnable place = () -> place(thumb, scroller);
        scroller.vvalueProperty().addListener((observable, before, after) -> place.run());
        scroller.viewportBoundsProperty().addListener((observable, before, after) -> place.run());
        scroller.getContent().layoutBoundsProperty().addListener(
                (observable, before, after) -> place.run());
        stack.widthProperty().addListener((observable, before, after) -> place.run());
        return stack;
    }

    /** The thumb's id, which is how the tests find it. */
    static final String THUMB_ID = "scroll-hint";

    /**
     * Sizes and places the thumb: as long as the visible share of the content, as far down the
     * track as the scroll has gone, and hidden when everything fits. Across, it goes past the
     * scroll pane's right-hand edge, which the overlay allows because a {@code Pane} does not clip
     * what it holds.
     */
    private static void place(Region thumb, ScrollPane scroller) {
        double viewport = scroller.getViewportBounds().getHeight();
        double content = scroller.getContent().getLayoutBounds().getHeight();
        boolean more = viewport > 0 && content > viewport + 0.5;
        thumb.setVisible(more);
        if (!more) {
            return;
        }
        double length = Math.max(Tokens.SCROLL_HINT_MIN_LENGTH, viewport * viewport / content);
        double top = (viewport - length) * scroller.getVvalue();
        double width = Tokens.SCROLL_HINT_WIDTH;
        thumb.resizeRelocate(scroller.getWidth() + Tokens.SCROLL_HINT_GAP, top, width, length);
    }
}
