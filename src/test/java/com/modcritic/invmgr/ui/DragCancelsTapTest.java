package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * A swipe over a button scrolls, and does not press it.
 *
 * <p>This machine has no touch screen, so the finger is made by hand: mouse events carrying the
 * {@code synthesized} flag, fired straight at the button. On Android a finger arrives twice, as
 * a touch and as a synthesized mouse copy, and the flag is how the phone marks the copy. The
 * phone case in the device suite checks the real thing.
 */
class DragCancelsTapTest extends ApplicationTest {

    private Button button;
    private int pressed;

    @Override
    public void start(Stage stage) {
        button = new Button("About");
        button.setOnAction(event -> pressed++);
        ScrollPane scroller = new ScrollPane(new VBox(button));
        DragCancelsTap.install(scroller);
        stage.setScene(new Scene(scroller, 200, 200));
        stage.show();
    }

    /** One mouse event at (x, y) inside the button, from a finger or from a mouse. */
    private void event(EventType<MouseEvent> type, double x, double y, boolean finger) {
        Bounds box = button.localToScene(button.getLayoutBounds());
        MouseEvent event = new MouseEvent(type, box.getMinX() + x, box.getMinY() + y, 0, 0,
                MouseButton.PRIMARY, 1, false, false, false, false,
                type != MouseEvent.MOUSE_RELEASED, false, false, finger, false, false, null);
        interact(() -> Event.fireEvent(button, event));
    }

    @Test
    @DisplayName("a finger that moves past the slop does not press the button it started on")
    void aSwipeDoesNotPress() {
        event(MouseEvent.MOUSE_PRESSED, 10, 5, true);
        event(MouseEvent.MOUSE_DRAGGED, 10, 5 + Tokens.TAP_SLOP + 5, true);
        event(MouseEvent.MOUSE_RELEASED, 10, 5 + Tokens.TAP_SLOP + 5, true);
        assertEquals(0, pressed);
    }

    @Test
    @DisplayName("armed again before the finger lifts, as coming back over it does, it still does not")
    void aSwipeThatComesBackDoesNotPress() {
        event(MouseEvent.MOUSE_PRESSED, 10, 5, true);
        event(MouseEvent.MOUSE_DRAGGED, 10, 5 + Tokens.TAP_SLOP + 5, true);
        // What JavaFX's ButtonBehavior does on MOUSE_ENTERED while the button is still pressed.
        interact(button::arm);
        event(MouseEvent.MOUSE_RELEASED, 10, 5, true);
        assertEquals(0, pressed);
    }

    @Test
    @DisplayName("a tap that wobbles less than the slop still presses it")
    void aWobblyTapPresses() {
        event(MouseEvent.MOUSE_PRESSED, 10, 5, true);
        event(MouseEvent.MOUSE_DRAGGED, 10, 5 + Tokens.TAP_SLOP - 2, true);
        event(MouseEvent.MOUSE_RELEASED, 10, 5 + Tokens.TAP_SLOP - 2, true);
        assertEquals(1, pressed);
    }

    @Test
    @DisplayName("after a swipe, the next tap presses as usual")
    void aTapAfterASwipePresses() {
        event(MouseEvent.MOUSE_PRESSED, 10, 5, true);
        event(MouseEvent.MOUSE_DRAGGED, 10, 5 + Tokens.TAP_SLOP + 5, true);
        event(MouseEvent.MOUSE_RELEASED, 10, 5 + Tokens.TAP_SLOP + 5, true);
        event(MouseEvent.MOUSE_PRESSED, 10, 5, true);
        event(MouseEvent.MOUSE_RELEASED, 10, 5, true);
        assertEquals(1, pressed, "the swipe's movement must not carry over to the tap");
    }

    @Test
    @DisplayName("a desktop mouse is left to JavaFX: no finger, no change")
    void aMouseIsLeftAlone() {
        event(MouseEvent.MOUSE_PRESSED, 10, 5, false);
        event(MouseEvent.MOUSE_DRAGGED, 10, 5 + Tokens.TAP_SLOP + 5, false);
        event(MouseEvent.MOUSE_RELEASED, 10, 5 + Tokens.TAP_SLOP + 5, false);
        assertEquals(1, pressed, "fired straight at the button, with no MOUSE_EXITED to disarm it");
    }
}
