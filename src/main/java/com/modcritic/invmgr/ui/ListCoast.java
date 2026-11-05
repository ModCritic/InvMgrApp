package com.modcritic.invmgr.ui;

import javafx.animation.AnimationTimer;
import javafx.beans.property.DoubleProperty;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TouchEvent;

/**
 * Lets a list, a sideways strip or a text box carry on moving after a finger flicks it, with the
 * same coast the room has.
 *
 * <p><b>Why a list needs this at all.</b> JavaFX on a phone has a fling of its own, made by the
 * {@code ScrollGestureRecognizer} that only the phone builds. {@code Launcher} switches it off for
 * the whole app, because it decides whether to fling by how long the gesture lasted rather than by
 * how fast the finger was going when it left, and the room replaced it with {@link PanMomentum}.
 * The switch cannot be aimed at one node, so every list lost its momentum along with the room's
 * fling: a flick stopped dead the moment the finger lifted. This gives a {@link ScrollPane} the
 * room's coast instead, fed exactly the way {@code RoomCanvasView} feeds it, so a list and the room
 * coast alike.
 *
 * <p><b>How it is fed.</b> The finger's own scroll gesture brackets the pan: {@code SCROLL_STARTED}
 * when it begins and {@code SCROLL_FINISHED} when the finger lifts. Every change to the scroll
 * pane's {@code vvalue} is turned into pixels and recorded, so what is measured is how far the list
 * really moved, whatever moved it. Changes made outside a gesture (the coast itself, the list
 * scrolling to a selected item) need no filtering out: {@code SCROLL_STARTED} begins each gesture
 * by discarding everything recorded before it, so the release only ever measures the finger.
 *
 * <p><b>One direction per scroll pane.</b> {@link #install} coasts up and down on {@code vvalue};
 * {@link #installSideways} coasts left and right on {@code hvalue}, for a strip like the Add
 * dialog's row of presets. The phone's scroll gesture starts the same way for both: its
 * {@code ScrollGestureRecognizer} begins once the finger has moved 10 px along either axis (read
 * out of the phone's {@code javafx.graphics.jar}, {@code notifyEndTouchEvent}, 2026-11-04).
 *
 * <p><b>Inside a text box.</b> A multi-line box scrolls its text with a scroll pane of its own,
 * which {@code SkinnedTextArea.onScrollPane} hands over for {@link #install}. A finger dragging one
 * of the phone's selection handles there starts no coast: the box's skin has a filter that consumes
 * a finger's scroll events while a handle is held (the shipped {@code TextAreaSkin}, read
 * 2026-11-04), so they never reach that scroll pane.
 *
 * <p>Nothing here knows about phones. A mouse wheel sends no {@code SCROLL_STARTED}, so on a
 * desktop it never starts, the same as the room's.
 */
final class ListCoast {

    private final ScrollPane scroller;

    /** True for a strip that scrolls left and right, false for a list that scrolls up and down. */
    private final boolean sideways;

    private final PanMomentum momentum = new PanMomentum();

    /** Reused by {@link #clock} so a coast does not allocate on every frame. */
    private final double[] step = new double[2];

    /** Drives the coast after the finger has gone, at whatever rate the screen is drawing. */
    private final AnimationTimer clock = new AnimationTimer() {
        @Override
        public void handle(long nanos) {
            if (!momentum.advance(nanos / 1_000_000.0, step)) {
                stop();
                return;
            }
            glideBy(sideways ? step[0] : step[1]);
        }
    };

    private ListCoast(ScrollPane scroller, boolean sideways) {
        this.scroller = scroller;
        this.sideways = sideways;
        scroller.addEventFilter(ScrollEvent.ANY, this::track);
        // A finger landing on a moving list means stop, which is what every list on a phone does.
        scroller.addEventFilter(TouchEvent.TOUCH_PRESSED, event -> stop());
        position().addListener((observable, before, after) ->
                sample(before.doubleValue(), after.doubleValue()));
    }

    /** Gives {@code scroller} the room's coast. Returned so a test can ask whether it is moving. */
    static ListCoast install(ScrollPane scroller) {
        return new ListCoast(scroller, false);
    }

    /** The same coast for a scroll pane that scrolls left and right instead. */
    static ListCoast installSideways(ScrollPane scroller) {
        return new ListCoast(scroller, true);
    }

    /** Whether the list is coasting right now. */
    boolean isCoasting() {
        return momentum.isCoasting();
    }

    private void track(ScrollEvent event) {
        // JavaFX's own fling, if any of it ever arrives. The switch in Launcher should mean it is
        // never made; this is the same second guard the room keeps, in case the phone's build fixed
        // the switch's value before the app could set it. Consumed, or the list would coast twice.
        if (event.isInertia()) {
            event.consume();
            return;
        }
        if (event.getEventType() == ScrollEvent.SCROLL_STARTED) {
            stop();
            momentum.began();
        } else if (event.getEventType() == ScrollEvent.SCROLL_FINISHED) {
            if (momentum.released(nowMs())) {
                clock.start();
            }
        }
    }

    private void sample(double before, double after) {
        double span = span();
        if (span <= 0) {
            return;                       // it all fits; there is nothing to scroll and nothing to time
        }
        double pixels = (after - before) * span;
        if (sideways) {
            momentum.moved(pixels, 0, nowMs());
        } else {
            momentum.moved(0, pixels, nowMs());
        }
    }

    /** Moves the list by one frame of the coast, and stops once it is against an end. */
    private void glideBy(double pixels) {
        double span = span();
        if (span <= 0) {
            stop();
            return;
        }
        double current = position().get();
        double wanted = Math.max(0, Math.min(1, current + pixels / span));
        if (wanted == current) {
            // Against an end. Carrying on would spend frames moving nothing.
            stop();
            return;
        }
        position().set(wanted);
    }

    /** Ends any coast at once and forgets the speed behind it. */
    private void stop() {
        clock.stop();
        momentum.stop();
    }

    /** Where the scroll pane is along the direction it coasts, from 0 at one end to 1 at the other. */
    private DoubleProperty position() {
        return sideways ? scroller.hvalueProperty() : scroller.vvalueProperty();
    }

    /**
     * How many pixels {@link #position}'s 0 to 1 covers: the content's height less what is on
     * screen, or its width for a strip.
     *
     * <p>The same conversion has to be used for sampling and for coasting, or the list would coast
     * at a speed the finger never had.
     */
    private double span() {
        Node content = scroller.getContent();
        if (content == null) {
            return 0;
        }
        if (sideways) {
            return content.getLayoutBounds().getWidth() - scroller.getViewportBounds().getWidth();
        }
        return content.getLayoutBounds().getHeight() - scroller.getViewportBounds().getHeight();
    }

    private static double nowMs() {
        return System.nanoTime() / 1_000_000.0;
    }
}
