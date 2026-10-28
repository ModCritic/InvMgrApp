package com.modcritic.invmgr.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TouchEvent;

/**
 * Lets a list carry on moving after a finger flicks it, with the same coast the room has.
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
 * by discarding everything recorded before it, so the release only ever measures the finger. Up and
 * down only; a list has nothing to coast sideways.
 *
 * <p>Nothing here knows about phones. A mouse wheel sends no {@code SCROLL_STARTED}, so on a
 * desktop it never starts, the same as the room's.
 */
final class ListCoast {

    private final ScrollPane scroller;
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
            glideBy(step[1]);
        }
    };

    private ListCoast(ScrollPane scroller) {
        this.scroller = scroller;
        scroller.addEventFilter(ScrollEvent.ANY, this::track);
        // A finger landing on a moving list means stop, which is what every list on a phone does.
        scroller.addEventFilter(TouchEvent.TOUCH_PRESSED, event -> stop());
        scroller.vvalueProperty().addListener((observable, before, after) ->
                sample(before.doubleValue(), after.doubleValue()));
    }

    /** Gives {@code scroller} the room's coast. Returned so a test can ask whether it is moving. */
    static ListCoast install(ScrollPane scroller) {
        return new ListCoast(scroller);
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
        momentum.moved(0, (after - before) * span, nowMs());
    }

    /** Moves the list by one frame of the coast, and stops once it is against an end. */
    private void glideBy(double pixels) {
        double span = span();
        if (span <= 0) {
            stop();
            return;
        }
        double current = scroller.getVvalue();
        double wanted = Math.max(0, Math.min(1, current + pixels / span));
        if (wanted == current) {
            // Against the top or the bottom. Carrying on would spend frames moving nothing.
            stop();
            return;
        }
        scroller.setVvalue(wanted);
    }

    /** Ends any coast at once and forgets the speed behind it. */
    private void stop() {
        clock.stop();
        momentum.stop();
    }

    /**
     * How many pixels {@code vvalue}'s 0 to 1 covers: the content's height less what is on screen.
     *
     * <p>The same conversion has to be used for sampling and for coasting, or the list would coast
     * at a speed the finger never had.
     */
    private double span() {
        Node content = scroller.getContent();
        if (content == null) {
            return 0;
        }
        return content.getLayoutBounds().getHeight() - scroller.getViewportBounds().getHeight();
    }

    private static double nowMs() {
        return System.nanoTime() / 1_000_000.0;
    }
}
