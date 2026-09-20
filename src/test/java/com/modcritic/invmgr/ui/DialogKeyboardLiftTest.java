package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How far a dialog moves up to stay out from under the on-screen keyboard (bug B7).
 *
 * <p>The panel is centered in whatever is left below the lift, so lifting by the keyboard's own
 * height centers it in the space above the keyboard. Only the arithmetic is here: whether the
 * keyboard height itself is right is a question for the phone.
 */
class DialogKeyboardLiftTest {

    /** The test phone in design pixels: a 740-tall window, keyboard about 380 of it. */
    private static final double WINDOW = 740;
    private static final double KEYBOARD = 380;

    @Test
    @DisplayName("with the keyboard down nothing moves")
    void keyboardDownDoesNotMove() {
        assertEquals(0, ModalDialog.liftFor(WINDOW, 300, 0), 1e-9);
    }

    @Test
    @DisplayName("a panel that fits above the keyboard lifts by the keyboard's full height")
    void liftsByTheKeyboard() {
        // 740 - 380 = 360 of room, and a 300 panel fits in it.
        assertEquals(KEYBOARD, ModalDialog.liftFor(WINDOW, 300, KEYBOARD), 1e-9);
    }

    @Test
    @DisplayName("a panel too tall for the space left is pinned to the top instead")
    void capsTheLift() {
        // A 600 panel leaves 140. Lifting the full 380 would center it 240 above the window
        // and put Cancel and Confirm off the top, where nothing can scroll them back.
        assertEquals(140, ModalDialog.liftFor(WINDOW, 600, KEYBOARD), 1e-9);
    }

    @Test
    @DisplayName("a panel taller than the window is left where it is")
    void panelTallerThanTheWindow() {
        assertEquals(0, ModalDialog.liftFor(WINDOW, 800, KEYBOARD), 1e-9);
        assertEquals(0, ModalDialog.liftFor(WINDOW, WINDOW, KEYBOARD), 1e-9);
    }

    @Test
    @DisplayName("a window with no height yet does not move anything")
    void noWindowYet() {
        // The timer runs on every frame, including ones before the overlay has been laid out.
        assertEquals(0, ModalDialog.liftFor(0, 300, KEYBOARD), 1e-9);
    }
}
