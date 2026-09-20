package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Preset;
import java.util.List;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TouchEvent;
import javafx.scene.input.TouchPoint;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Deleting a preset by holding a finger on it: the touch half of §5.5 <b>D-1</b>.
 *
 * <p>A preset is the one thing in the app that undo cannot bring back, which is why the hold asks
 * before it does anything, unlike a box in the room. The desktop keeps right-click and gains no
 * hold; that half is pinned here too, because the point of D-1 is the split rather than either
 * gesture on its own.
 */
class PresetTouchTest extends ApplicationTest {

    private PresetSlots slots;
    private AppState state;
    private String asked;
    private boolean answer = true;
    private Preset applied;
    private int defined = -1;

    @Override
    public void start(Stage stage) {
        state = new AppState();
        state.presets.set(0, new Preset("AB", 24, 24, 12));

        slots = new PresetSlots(state);
        slots.setConfirm(question -> {
            asked = question;
            return answer;
        });
        slots.setOnApply(preset -> applied = preset);
        slots.setOnDefine(index -> defined = index);

        stage.setScene(new Scene(new StackPane(slots), 800, 400));
        // Pinned: TestFX reuses one stage across the whole run.
        stage.setMaximized(false);
        stage.setWidth(800);
        stage.setHeight(400);
        stage.show();
    }

    @Test
    @DisplayName("holding a filled slot asks first, then empties it")
    void holdingAsksThenDeletes() {
        Button slot = slots.slotButton(0);

        touch(slot, TouchEvent.TOUCH_PRESSED, 10, 10);
        assertNotNull(ringOver(slot), "the warning ring should be showing while the finger is down");

        sleepFor(1800);

        assertEquals("Delete preset \"AB\"?", asked, "it must ask: this is not undoable");
        assertNull(state.presets.get(0), "and then empty the slot");
    }

    @Test
    @DisplayName("the question is asked somewhere a dialog can actually open")
    void theQuestionIsAskedOutsideTheFrame() {
        // The bug this exists for: the hold fires from an AnimationTimer, which JavaFX runs as
        // part of a frame, and a modal dialog cannot open there; it needs a nested event loop and
        // JavaFX refuses to start one mid-frame. On the phone the exception was swallowed the way
        // handler exceptions are, so holding a preset simply did nothing at all.
        //
        // The other tests here cannot see it, and that is the point: they replace the question
        // with a stub that answers instantly, so they drive a path the real app never takes. This
        // stub does what a real dialog does (it starts a nested event loop, through public API,
        // with no window involved), so it fails in exactly the same place a dialog would.
        Throwable[] refused = new Throwable[1];
        slots.setConfirm(question -> {
            asked = question;
            try {
                Object key = new Object();
                javafx.application.Platform.runLater(
                        () -> javafx.application.Platform.exitNestedEventLoop(key, null));
                javafx.application.Platform.enterNestedEventLoop(key);
            } catch (RuntimeException refusal) {
                refused[0] = refusal;
                return false;
            }
            return true;
        });

        Button slot = slots.slotButton(0);
        touch(slot, TouchEvent.TOUCH_PRESSED, 10, 10);
        sleepFor(1800);

        assertNull(refused[0], "the question was asked from inside a frame, where no dialog can "
                + "open: " + (refused[0] == null ? "" : refused[0].getMessage()));
        assertNotNull(asked, "and it must actually have been asked");
        assertNull(state.presets.get(0), "and answering yes must empty the slot");
    }

    @Test
    @DisplayName("saying no to the question leaves the preset alone")
    void answeringNoKeepsThePreset() {
        answer = false;
        Button slot = slots.slotButton(0);

        touch(slot, TouchEvent.TOUCH_PRESSED, 10, 10);
        sleepFor(1800);

        assertNotNull(asked, "it should still have asked");
        assertNotNull(state.presets.get(0), "but nothing should have been thrown away");
    }

    @Test
    @DisplayName("a quick tap uses the preset instead of deleting it")
    void aTapAppliesThePreset() {
        Button slot = slots.slotButton(0);

        touch(slot, TouchEvent.TOUCH_PRESSED, 10, 10);
        touch(slot, TouchEvent.TOUCH_RELEASED, 10, 10);

        assertNotNull(applied, "a tap should use the preset");
        assertEquals("AB", applied.name);
        assertNull(asked, "and must not ask about deleting anything");
        assertNull(ringOver(slot), "the ring goes as soon as the finger does");
    }

    @Test
    @DisplayName("a tap on an empty slot offers to define one, and shows no ring")
    void anEmptySlotHasNothingToWarnAbout() {
        Button empty = slots.slotButton(1);

        touch(empty, TouchEvent.TOUCH_PRESSED, 10, 10);
        assertNull(ringOver(empty), "there is nothing to delete, so nothing to warn about");

        touch(empty, TouchEvent.TOUCH_RELEASED, 10, 10);
        assertEquals(1, defined, "tapping an empty slot should offer to fill it");
    }

    @Test
    @DisplayName("sliding across a slot is someone scrolling the strip, not choosing it")
    void movingCancelsBothTheHoldAndTheTap() {
        Button slot = slots.slotButton(0);

        touch(slot, TouchEvent.TOUCH_PRESSED, 10, 10);
        touch(slot, TouchEvent.TOUCH_MOVED, 60, 12);
        sleepFor(120);
        assertNull(ringOver(slot), "the hold is off the moment the finger moves");

        touch(slot, TouchEvent.TOUCH_RELEASED, 60, 12);

        assertNull(applied, "a swipe past a preset must not use it");
        assertNull(asked, "nor start deleting it");
        assertNotNull(state.presets.get(0));
    }

    @Test
    @DisplayName("lifting in the dead band does nothing at all")
    void theDeadBandDoesNothing() {
        Button slot = slots.slotButton(0);

        touch(slot, TouchEvent.TOUCH_PRESSED, 10, 10);
        sleepFor(800);
        touch(slot, TouchEvent.TOUCH_RELEASED, 10, 10);

        assertNull(asked, "too early to delete");
        assertNull(applied, "too late to be a tap: lifting here is how a delete is called off");
    }

    @Test
    @DisplayName("the mouse Android invents from the tap does not use the preset a second time")
    void theSyntheticTwinIsIgnored() {
        Button slot = slots.slotButton(0);

        fireMouse(slot, MouseEvent.MOUSE_PRESSED, 10, 10);
        fireMouse(slot, MouseEvent.MOUSE_RELEASED, 10, 10);
        fireMouse(slot, MouseEvent.MOUSE_CLICKED, 10, 10);

        assertNull(applied,
                "the touch release already used the preset; this is the same tap arriving again");

        // And a real click still works, so the assertion above is about the synthesized flag
        // rather than about the events never having arrived.
        moveTo(slot);
        clickOn(MouseButton.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();
        assertNotNull(applied, "a real mouse click must still use the preset");
    }

    @Test
    @DisplayName("a mouse held on a preset for two seconds does nothing (D-1)")
    void aHeldMouseIsNotADelete() {
        Button slot = slots.slotButton(0);
        moveTo(slot);
        press(MouseButton.PRIMARY);
        sleepFor(2000);
        assertNull(ringOver(slot), "a held mouse must never show the warning ring");
        release(MouseButton.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();

        assertNull(asked, "a held mouse must never start a deletion");
        // And the press really landed: a click on a filled slot uses it. Without this the test
        // would pass just as well if the pointer had missed the button.
        assertNotNull(applied, "the click should have applied the preset");
    }

    @Test
    @DisplayName("right-click still deletes on the desktop, immediately (D-1)")
    void rightClickStillDeletes() {
        Button slot = slots.slotButton(0);
        moveTo(slot);
        clickOn(MouseButton.SECONDARY);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("Delete preset \"AB\"?", asked);
        assertNull(state.presets.get(0));
    }

    @Test
    @DisplayName("the warning ring is #e55, 3 px, and sits 4 px outside the slot")
    void theRingLooksLikeTheOriginals() {
        Button slot = slots.slotButton(0);
        touch(slot, TouchEvent.TOUCH_PRESSED, 10, 10);

        Rectangle ring = ringOver(slot);
        assertNotNull(ring);
        assertEquals(Tokens.HOLD_RING, ring.getStroke());
        assertEquals(Tokens.HOLD_RING_WIDTH, ring.getStrokeWidth(), 0.01);
        assertEquals(javafx.scene.shape.StrokeType.INSIDE, ring.getStrokeType());
        assertTrue(ring.isMouseTransparent());
        assertFalse(ring.isManaged(), "an unmanaged ring cannot push the row of slots about");

        // Four rather than the room's five, which is what the original uses for a preset.
        double inset = Tokens.HOLD_RING_INSET_PRESET;
        assertEquals(slot.getWidth() + inset * 2, ring.getWidth(), 0.01);
        assertEquals(slot.getLayoutX() - inset, ring.getLayoutX(), 0.01);

        // It fades in without changing size, exactly as the original's keyframe does.
        double atStart = ring.getOpacity();
        sleepFor(300);
        assertTrue(ring.getOpacity() > atStart, "the red should be building up");
        assertEquals(slot.getWidth() + inset * 2, ring.getWidth(), 0.01,
                "and the ring should be the size it started");
    }

    // -------------------------------------------------------------------- helpers

    private void touch(Node target, EventType<TouchEvent> type, double x, double y) {
        javafx.geometry.Point2D inScene = target.localToScene(x, y);
        TouchPoint point = new TouchPoint(1, TouchPoint.State.MOVED,
                inScene.getX(), inScene.getY(), inScene.getX(), inScene.getY(), target, null);
        interact(() -> Event.fireEvent(target, new TouchEvent(type, point, List.of(point), 1,
                false, false, false, false)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void fireMouse(Node target, EventType<MouseEvent> type, double x, double y) {
        javafx.geometry.Point2D inScene = target.localToScene(x, y);
        interact(() -> Event.fireEvent(target, new MouseEvent(type,
                inScene.getX(), inScene.getY(), inScene.getX(), inScene.getY(),
                MouseButton.PRIMARY, 1,
                false, false, false, false,
                true, false, false,
                true,                    // synthesized: Android's twin of the finger
                false, false, null)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** The ring if it is on screen over this slot, or null. Found by identity, never by index. */
    private Rectangle ringOver(Button slot) {
        for (Node child : slots.getChildren()) {
            if (child instanceof Rectangle ring) {
                return ring.getLayoutX() < slot.getLayoutX() + slot.getWidth()
                        && ring.getLayoutX() + ring.getWidth() > slot.getLayoutX() ? ring : null;
            }
        }
        return null;
    }

    private void sleepFor(long millis) {
        WaitForAsyncUtils.sleep(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
        WaitForAsyncUtils.waitForFxEvents();
    }
}
