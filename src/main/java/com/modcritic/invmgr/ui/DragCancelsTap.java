package com.modcritic.invmgr.ui;

import javafx.event.EventTarget;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.input.MouseEvent;

/**
 * Keeps a finger that scrolls a list of buttons from also pressing the one it started on.
 *
 * <p><b>Found on the phone on 2026-09-25</b>, the day the Menu got its sixth entry and started to
 * scroll: a swipe that began on About scrolled the list to its end <em>and</em> opened About, and
 * one that began in the 8 px gap below Feedback opened Feedback. So every attempt to scroll the
 * Menu opened something.
 *
 * <p>Why, read out of the shipped JavaFX's {@code ButtonBehavior}: a press arms the button, a
 * {@code MOUSE_ENTERED} while pressed arms it again, and the release fires it if it is still
 * armed. On the phone the button stayed armed through the whole swipe. Meanwhile the swipe also
 * scrolls, because the phone turns a moving finger into scroll events that reach the scroll pane
 * whatever the finger started on (the app's Android launcher, invmgr_launcher.c, switches JavaFX's
 * scroll gestures on; the desktop never sends them).
 *
 * <p>So this watches the finger's copy of the mouse events, and nothing else: they are the ones
 * flagged {@link MouseEvent#isSynthesized()} (on Android a finger arrives twice, as a touch and as
 * a synthesized mouse copy), so a desktop mouse is untouched. Once the finger has moved more than
 * {@link Tokens#TAP_SLOP} from where it went down, the button it started on is disarmed just
 * before the release, which is the one moment the button checks. No event is consumed, so the
 * scroll pane's own handling is unchanged.
 */
final class DragCancelsTap {

    private DragCancelsTap() {
    }

    /** Where the finger went down, and what it went down on. */
    private static final class Gesture {
        double x;
        double y;
        ButtonBase button;
        boolean moved;
    }

    /** Watches every finger that goes down inside {@code area}. */
    static void install(Node area) {
        Gesture gesture = new Gesture();
        area.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            gesture.button = event.isSynthesized() ? buttonAt(event.getTarget(), area) : null;
            gesture.x = event.getSceneX();
            gesture.y = event.getSceneY();
            gesture.moved = false;
        });
        area.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (gesture.button != null && Math.hypot(event.getSceneX() - gesture.x,
                    event.getSceneY() - gesture.y) > Tokens.TAP_SLOP) {
                gesture.moved = true;
            }
        });
        // Disarmed at the release rather than while dragging: the button arms itself again on
        // every MOUSE_ENTERED while pressed, and the release is the only moment it checks.
        area.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (gesture.button != null && gesture.moved) {
                gesture.button.disarm();
            }
            gesture.button = null;
        });
    }

    /** The button the event landed on or inside, stopping at {@code area}; null if none. */
    private static ButtonBase buttonAt(EventTarget target, Node area) {
        Node node = target instanceof Node ? (Node) target : null;
        while (node != null && node != area) {
            if (node instanceof ButtonBase) {
                return (ButtonBase) node;
            }
            node = node.getParent();
        }
        return null;
    }
}
