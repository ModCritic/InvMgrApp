package com.modcritic.invmgr.ui;

import java.util.List;
import java.util.function.Consumer;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;

/**
 * Makes a scroll pane stop at its ends instead of stretching past them and springing back
 * (asked for by the user on 2026-09-26).
 *
 * <p>On a touch screen JavaFX lets a finger drag the content past its first or last row, then
 * animates it back when the finger lifts. Read out of the shipped JavaFX 21's
 * {@code ScrollPaneSkin} on 2026-09-26: both the finger's drag and the phone's scroll gesture set
 * the skin's own scroll bar to a value past its ends, where the desktop branch of the same code
 * clamps it; the content's position follows that value, with the clamp to the edge skipped on a
 * touch screen; and {@code startContentsToViewport} is the spring back, started only when the
 * value lies outside the bar's range. The scroll pane's own {@code vvalue} is always clamped, so
 * watching it would change nothing. Keeping the bar's value inside its range removes the stretch
 * and the spring together.
 *
 * <p>On a desktop the skin already clamps, so there this changes nothing.
 */
final class ScrollEnds {

    private ScrollEnds() {
    }

    /** Keeps both of {@code scroller}'s bars inside their range, from its skin's first moment on. */
    static void install(ScrollPane scroller) {
        forEachBar(scroller, bar -> bar.valueProperty().addListener((observable, before, after) -> {
            double value = after.doubleValue();
            if (value < bar.getMin()) {
                bar.setValue(bar.getMin());
            } else if (value > bar.getMax()) {
                bar.setValue(bar.getMax());
            }
        }));
    }

    /**
     * Runs {@code action} on each scroll bar the scroll pane's skin made, as soon as there is a
     * skin. The bars are the skin's own children, so they exist only once it does. Only the
     * pane's direct children count: a text box inside the page has scroll bars of its own, and
     * they are not this pane's.
     */
    static void forEachBar(ScrollPane scroller, Consumer<ScrollBar> action) {
        if (scroller.getSkin() != null) {
            bars(scroller).forEach(action);
            return;
        }
        scroller.skinProperty().addListener((observable, before, after) -> {
            if (after != null && before == null) {
                bars(scroller).forEach(action);
            }
        });
    }

    private static List<ScrollBar> bars(ScrollPane scroller) {
        return scroller.getChildrenUnmodifiable().stream()
                .filter(ScrollBar.class::isInstance).map(ScrollBar.class::cast).toList();
    }
}
