package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Room;
import com.modcritic.invmgr.model.Units;
import java.util.List;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TouchEvent;
import javafx.scene.input.TouchPoint;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * What one finger on a box actually does to the room.
 *
 * <p>{@code TouchGestureTest} proves the recognizer decides correctly; this proves the room is
 * wired to it. Neither would notice if the view simply never called any of it.
 *
 * <p><b>The touches are built by hand and fired at the nodes.</b> There is no touchscreen in this
 * container and there never will be, but {@code TouchEvent} and {@code TouchPoint} are ordinary
 * public classes, so a gesture can be assembled and delivered exactly where a finger would deliver
 * it. That is what turns "none of this is provable without a real thumb" into something checkable
 * before an APK is built. What it does <b>not</b> prove is that Android's own event stream looks
 * like the one built here; that still needs the phone.
 */
class CanvasTouchTest extends ApplicationTest {

    private RoomCanvasView canvas;
    private AppState state;
    private Item box;
    private Item activated;
    private Item tapped;
    private double tappedX;
    private double tappedY;
    private int selectionChanges;

    @Override
    public void start(Stage stage) {
        state = new AppState();
        state.room = new Room(20, 16, 8);

        box = item("item-id-11111111-2222-4333-8444-555555555555", 1, 24, 24, 192, 192);
        state.items.add(box);

        canvas = new RoomCanvasView(state);
        canvas.setOnItemActivated(item -> activated = item);
        canvas.setOnItemTapped((item, sceneX, sceneY) -> {
            tapped = item;
            tappedX = sceneX;
            tappedY = sceneY;
        });
        canvas.setOnSelectionChanged(() -> selectionChanges++);

        HBox main = new HBox(canvas);
        HBox.setHgrow(canvas, Priority.ALWAYS);
        stage.setScene(new Scene(main, 1400, 900));
        // Pinned: TestFX reuses one stage for the whole run, so an earlier class can leave it
        // maximized and this one would silently get a different window, which is exactly how the
        // Fit-scale test below came to measure something else when run with the rest of the suite.
        stage.setMaximized(false);
        stage.setWidth(1400);
        stage.setHeight(900);
        stage.show();
    }

    // ------------------------------------------------------------------ dragging

    @Test
    @DisplayName("dragging a box with a finger moves it")
    void aFingerDragMovesTheBox() {
        double startX = box.x_px;
        double startY = box.y_px;

        Point2D center = centerOf(box);
        touch(TouchEvent.TOUCH_PRESSED, center.getX(), center.getY());
        touch(TouchEvent.TOUCH_MOVED, center.getX() + 200, center.getY() + 100);
        touch(TouchEvent.TOUCH_RELEASED, center.getX() + 200, center.getY() + 100);

        assertEquals(startX + 200, box.x_px, 2, "the box should follow the finger across");
        assertEquals(startY + 100, box.y_px, 2, "and down");
    }

    @Test
    @DisplayName("a finger drag is clamped by the walls, exactly as a mouse drag is")
    void aFingerDragCannotLeaveTheRoom() {
        Point2D center = centerOf(box);
        touch(TouchEvent.TOUCH_PRESSED, center.getX(), center.getY());
        touch(TouchEvent.TOUCH_MOVED, center.getX() + 5000, center.getY());
        touch(TouchEvent.TOUCH_RELEASED, center.getX() + 5000, center.getY());

        // A 20 ft room is 1920 px across and the box is 192 px wide.
        assertEquals(1728, box.x_px, 1, "the box should stop at the east wall");
    }

    @Test
    @DisplayName("while a box is under a finger, nothing may scroll the room")
    void aFingerDragDoesNotScrollTheRoom() {
        // The rule the user reported three builds running: dragging a box also moved the room.
        //
        // This deliberately does NOT try to reproduce the cause. An earlier version fired the events
        // a finger makes (touches plus the synthesized mouse copies Android sends with them) and
        // was worth nothing: the skin's press/drag pan cannot be provoked in this container even
        // with `com.sun.javafx.touch=true`, so the test passed identically with the fix in place and
        // with it removed. A test that cannot fail is not evidence.
        //
        // So it asserts the rule instead of the mechanism, by moving the scroll directly. That is
        // the strongest form of the question: anything the platform can do to these two numbers,
        // this does too.
        interact(() -> canvas.setSelectedId(box.id));
        WaitForAsyncUtils.waitForFxEvents();
        double h = canvas.getHvalue();
        double v = canvas.getVvalue();

        Point2D center = centerOf(box);
        touch(TouchEvent.TOUCH_PRESSED, center.getX(), center.getY());
        touch(TouchEvent.TOUCH_MOVED, center.getX() + 120, center.getY() + 120);

        interact(() -> {
            canvas.setHvalue(Math.min(1, h + 0.4));
            canvas.setVvalue(Math.min(1, v + 0.4));
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(h, canvas.getHvalue(), 0.001, "a box drag must not scroll the room sideways");
        assertEquals(v, canvas.getVvalue(), 0.001, "nor up and down");

        touch(TouchEvent.TOUCH_RELEASED, center.getX() + 120, center.getY() + 120);

        // And the hold is released with the gesture, or the room could never be scrolled again.
        interact(() -> canvas.setVvalue(Math.min(1, v + 0.3)));
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(canvas.getVvalue() > v, "once the finger is up the room scrolls normally again");
    }

    @Test
    @DisplayName("the scroll GESTURE is swallowed during a box drag, and through its inertia tail")
    void theScrollGestureIsIgnoredWhileDraggingABox() {
        // ⚠ THE ACTUAL CAUSE, after five wrong answers, and the one thing that CAN be reproduced
        // here even though the bug cannot. The app's own Android launcher sets
        // -Dcom.sun.javafx.gestures.scroll=true, which exists nowhere else, so only the phone builds
        // a ScrollGestureRecognizer: one finger moving 10 px there becomes a ScrollEvent aimed at
        // the box and bubbling up to ScrollPaneSkin, which scrolls the room. No recognizer here
        // means no such event here; but a ScrollEvent is an ordinary public class, so the app's
        // response to one is checkable exactly as the touch gestures are.
        // The RULE is asserted directly rather than through a dispatch, and both alternatives were
        // tried and rejected. Reading the fired event's consumed flag back proves nothing, because
        // JavaFX re-targets a gesture and the object handed to fireEvent is not the one the handlers
        // see. And watching whether the room moved does not work HERE either: ScrollPaneSkin's
        // SCROLL handler consults IS_TOUCH_SUPPORTED inside its body, so on this desktop a scroll
        // moves nothing and the test would pass for the wrong reason.
        //
        // So this asks the app's own rule the question, with no platform in the way.
        Point2D center = centerOf(box);
        assertFalse(canvas.swallowsScrollGesture(), "idle, a scroll belongs to the room");

        touch(TouchEvent.TOUCH_PRESSED, center.getX(), center.getY());
        touch(TouchEvent.TOUCH_MOVED, center.getX() + 80, center.getY() + 80);
        assertTrue(canvas.swallowsScrollGesture(), "but not while a box is being dragged");

        touch(TouchEvent.TOUCH_RELEASED, center.getX() + 80, center.getY() + 80);

        // And still swallowed immediately after the finger lifts. This is the half fix five got
        // wrong: the app's gesture is over, the platform's is not, and the recognizer's inertia
        // keeps arriving for up to 1500 ms. Disarming on release is what turned the pan into
        // momentum on the phone.
        assertTrue(canvas.swallowsScrollGesture(), "nor its inertia, which outlives the gesture");

        // A fresh touch hands the room back, or moving a box would cost a second and a half of
        // being unable to pan on purpose.
        touch(TouchEvent.TOUCH_PRESSED, center.getX(), center.getY());
        touch(TouchEvent.TOUCH_RELEASED, center.getX(), center.getY());
        assertFalse(canvas.swallowsScrollGesture(), "a new touch cancels the guard");
    }

    @Test
    @DisplayName("dragging a selected box to a wall does not change how much there is to scroll")
    void draggingABoxDoesNotResizeTheScrollableArea() {
        // B2, reported three times before it was understood: "dragging an item to move it also
        // moves the canvas". Not an event going astray; the selected box's white ring is drawn
        // OUTSIDE its box and dragTo re-positions it every frame, so a box against a wall pushes the
        // ring past the room, grows the unclipped content, and moves the scroll fractions under a
        // viewport that has not moved. The original cannot do this: its ring is a CSS `outline`, and
        // an outline is defined never to affect layout or scrollable overflow.
        //
        // Asserted on the content's SIZE rather than on hvalue, because the size is the cause and
        // the fraction only the symptom, and because on a desktop-sized window the fraction has
        // room to hide in, which is why this only ever showed itself on a phone.
        interact(() -> canvas.setSelectedId(box.id));
        WaitForAsyncUtils.waitForFxEvents();

        double width = canvas.getContent().getLayoutBounds().getWidth();
        double height = canvas.getContent().getLayoutBounds().getHeight();

        Point2D center = centerOf(box);
        touch(TouchEvent.TOUCH_PRESSED, center.getX(), center.getY());
        touch(TouchEvent.TOUCH_MOVED, center.getX() + 5000, center.getY() + 5000);

        assertEquals(width, canvas.getContent().getLayoutBounds().getWidth(), 0.01,
                "the scrollable width must not change just because a box moved");
        assertEquals(height, canvas.getContent().getLayoutBounds().getHeight(), 0.01,
                "nor the scrollable height");

        touch(TouchEvent.TOUCH_RELEASED, center.getX() + 5000, center.getY() + 5000);

        assertEquals(width, canvas.getContent().getLayoutBounds().getWidth(), 0.01,
                "and not once the drag has finished either");
    }

    @Test
    @DisplayName("a finished finger drag records undo and puts the box on top")
    void aFingerDragCommitsLikeAMouseDrag() {
        Item other = item("item-id-33333333-4444-4555-8666-777777777777", 5, 24, 24, 600, 192);
        other.h_in = 18;
        interact(() -> {
            state.items.add(other);
            canvas.rebuildItems();
        });
        WaitForAsyncUtils.waitForFxEvents();

        double startX = box.x_px;
        dragWithFinger(box, 408, 0);

        assertTrue(box.dragOrder > other.dragOrder,
                "the box just moved should be the most recently touched");
        assertEquals(18, box.baseHeight_in, "and should rest on the 18 inch box under it");

        String[] message = new String[1];
        interact(() -> message[0] = canvas.undo());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(startX, box.x_px, "undo should put it back exactly");
        assertTrue(message[0].startsWith("Undid drag of"), "got: " + message[0]);
    }

    @Test
    @DisplayName("a stale drag-order counter does not strand a finger-dragged box below (D-2)")
    void aFingerDragBeatsAStaleCounter() {
        Item high = item("item-id-44444444-5555-4666-8777-888888888888", 8, 24, 24, 600, 192);
        high.h_in = 18;
        interact(() -> {
            state.dragOrderCounter = 0;
            box.dragOrder = 7;
            state.items.add(high);
            canvas.rebuildItems();
        });
        WaitForAsyncUtils.waitForFxEvents();

        dragWithFinger(box, 408, 0);

        // The touch path calls the same commit as the mouse path, so D-2 has to hold here too. If
        // it ever grew its own copy, this is where that would show.
        assertTrue(box.dragOrder > high.dragOrder,
                "expected above drag order 8, got " + box.dragOrder);
    }

    @Test
    @DisplayName("the selection outline follows the box during a finger drag")
    void theOutlineKeepsUpWithTheFinger() {
        interact(() -> canvas.setSelectedId(box.id));
        WaitForAsyncUtils.waitForFxEvents();

        Point2D center = centerOf(box);
        touch(TouchEvent.TOUCH_PRESSED, center.getX(), center.getY());
        touch(TouchEvent.TOUCH_MOVED, center.getX() + 300, center.getY());
        WaitForAsyncUtils.waitForFxEvents();

        // Mid-drag, before the release-time refresh would have tidied it up. The outline is its
        // own node and nothing moves it automatically.
        Rectangle outline = outlineFor(box);
        double inset = Tokens.SELECTION_OUTLINE_OFFSET + Tokens.SELECTION_OUTLINE_WIDTH;
        assertEquals(box.x_px - inset, outline.getX(), 0.5,
                "the highlight should be around the box, not where the box started");

        touch(TouchEvent.TOUCH_RELEASED, center.getX() + 300, center.getY());
    }

    @Test
    @DisplayName("the six pixel threshold is measured in room pixels, not screen pixels")
    void theThresholdIsInRoomPixels() {
        // Fit shrinks the room to fit the window, so one screen pixel is MORE than one room pixel.
        // Five pixels of finger movement is therefore under the threshold read off the glass and
        // over it once converted, which is the whole question. The original divides by the fit
        // scale before comparing, so this is a drag; testing the raw screen delta first and
        // dividing afterwards would leave it a tap.
        //
        // A deliberately huge room, set here rather than in start(): the window is not the same
        // size in every run, and at 20 ft Fit barely shrinks anything, which left the margin
        // between "under six on the glass" and "over six in the room" too thin to test. The guards
        // below refuse to run a meaningless version of this rather than passing vacuously, which
        // is how that was noticed at all.
        interact(() -> {
            state.room = new com.modcritic.invmgr.model.Room(60, 40, 8);
            canvas.rebuildRoom();
            canvas.setFitMode(true);
        });
        WaitForAsyncUtils.waitForFxEvents();

        double scale = canvas.fitScaleFactor();
        assertTrue(scale < 0.8, "this test is meaningless unless Fit really shrank the room, got "
                + scale);
        assertTrue(5 / scale > Tokens.DRAG_THRESHOLD_TOUCH_PX,
                "and unless five screen pixels really is over six room pixels, got " + (5 / scale));

        double startX = box.x_px;
        Point2D center = centerOf(box);
        touch(TouchEvent.TOUCH_PRESSED, center.getX(), center.getY());
        touch(TouchEvent.TOUCH_MOVED, center.getX() + 5, center.getY());
        touch(TouchEvent.TOUCH_RELEASED, center.getX() + 5, center.getY());

        assertEquals(startX + 5 / scale, box.x_px, 1,
                "the box should have moved by five SCREEN pixels' worth of room");
        assertNull(tapped, "and it was a drag, not a tap");
    }

    // ------------------------------------------------- tap, double tap, dead band

    @Test
    @DisplayName("a quick tap names the box and selects it, and does NOT open the dialog")
    void aTapNamesAndSelects() {
        Point2D center = centerOf(box);
        touch(TouchEvent.TOUCH_PRESSED, center.getX(), center.getY());
        touch(TouchEvent.TOUCH_RELEASED, center.getX(), center.getY());

        assertEquals(box, tapped, "a tap should ask for the box's details");
        assertEquals(box.id, canvas.selectedId(), "and select it");
        assertTrue(selectionChanges > 0, "and tell the list, or its highlight goes stale");
        assertNull(activated, "a single tap must not open the edit dialog: that is the double");

        // Above the box's top edge and centered on it: a fingertip covers what it is touching.
        Rectangle rect = rectFor(box);
        javafx.geometry.Bounds inScene = rect.localToScene(rect.getBoundsInLocal());
        assertEquals((inScene.getMinX() + inScene.getMaxX()) / 2, tappedX, 0.5);
        assertEquals(inScene.getMinY() - 10, tappedY, 0.5);
    }

    @Test
    @DisplayName("two quick taps open the edit dialog")
    void aDoubleTapEdits() {
        Point2D center = centerOf(box);
        touch(TouchEvent.TOUCH_PRESSED, center.getX(), center.getY());
        touch(TouchEvent.TOUCH_RELEASED, center.getX(), center.getY());
        touch(TouchEvent.TOUCH_PRESSED, center.getX(), center.getY());
        touch(TouchEvent.TOUCH_RELEASED, center.getX(), center.getY());

        assertEquals(box, activated, "the second tap should open the dialog");
    }

    @Test
    @DisplayName("a second finger abandons the gesture rather than confusing it")
    void aSecondFingerCancels() {
        double startX = box.x_px;
        Point2D center = centerOf(box);

        Rectangle rect = rectFor(box);
        TouchPoint first = point(rect, center.getX(), center.getY());
        TouchPoint second = point(rect, center.getX() + 40, center.getY());
        interact(() -> Event.fireEvent(rect, new TouchEvent(TouchEvent.TOUCH_PRESSED, first,
                List.of(first, second), 1, false, false, false, false)));
        WaitForAsyncUtils.waitForFxEvents();

        touch(TouchEvent.TOUCH_MOVED, center.getX() + 300, center.getY());
        touch(TouchEvent.TOUCH_RELEASED, center.getX() + 300, center.getY());

        assertEquals(startX, box.x_px, "two fingers on a box are not a drag of it");
        assertNull(tapped, "nor a tap");
        assertNull(activated, "nor an edit");
    }

    @Test
    @DisplayName("a second finger on a DIFFERENT box abandons the first one's drag cleanly")
    void aSecondFingerOnAnotherBoxDoesNotCrossTheWires() {
        Item other = item("item-id-55555555-6666-4777-8888-999999999999", 4, 24, 24, 900, 600);
        interact(() -> {
            state.items.add(other);
            canvas.rebuildItems();
        });
        WaitForAsyncUtils.waitForFxEvents();

        // Start dragging the first box.
        Point2D first = centerOf(box);
        touch(TouchEvent.TOUCH_PRESSED, first.getX(), first.getY());
        touch(TouchEvent.TOUCH_MOVED, first.getX() + 200, first.getY());
        double moved = box.x_px;
        assertTrue(moved > 192, "the first box should be moving");

        // A second finger lands on the other box. Only one gesture runs at a time, so the first is
        // abandoned, and this is the case where an undo entry could cross over between the two
        // boxes, since only one is held pending at a time. The first box's lift must therefore
        // commit nothing rather than commit the wrong box's starting position.
        Rectangle otherRect = rectFor(other);
        Point2D at = centerOf(other);
        TouchPoint a = point(otherRect, at.getX(), at.getY());
        TouchPoint b = point(otherRect, at.getX() + 30, at.getY());
        interact(() -> Event.fireEvent(otherRect, new TouchEvent(TouchEvent.TOUCH_PRESSED, a,
                List.of(a, b), 1, false, false, false, false)));
        WaitForAsyncUtils.waitForFxEvents();

        touch(TouchEvent.TOUCH_RELEASED, first.getX() + 200, first.getY());

        String[] message = new String[1];
        interact(() -> message[0] = canvas.undo());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("Nothing to undo.", message[0],
                "the abandoned drag must not have pushed an undo entry, got: " + message[0]);
    }

    // ------------------------------------------------------- hold, ring, deleting

    @Test
    @DisplayName("the warning ring appears while a finger rests on a box, and its red builds up")
    void theRingBuildsUp() {
        Point2D center = centerOf(box);
        touch(TouchEvent.TOUCH_PRESSED, center.getX(), center.getY());
        WaitForAsyncUtils.waitForFxEvents();

        Rectangle ring = ringNode();
        assertNotNull(ring, "a ring should be showing while the finger is down");

        double inset = Tokens.HOLD_RING_INSET_ITEM;
        assertEquals(box.x_px - inset, ring.getX(), 0.01, "5 px clear of the box");
        assertEquals(Units.inchesToPx(box.w_in) + inset * 2, ring.getWidth(), 0.01);
        assertEquals(Tokens.HOLD_RING, ring.getStroke(), "the original's #e55");
        assertEquals(Tokens.HOLD_RING_WIDTH, ring.getStrokeWidth(), 0.01);
        assertEquals(javafx.scene.shape.StrokeType.INSIDE, ring.getStrokeType(),
                "the stroke runs inward from 5 px out, matching the CSS box model");
        assertTrue(ring.isMouseTransparent(), "the ring must not eat the touch it is reporting on");

        // It starts faint and gets stronger. It does NOT change size: the original's keyframe is
        // only named holdGrow, and what it animates is opacity.
        sleepFor(300);
        double after = ring.getOpacity();
        assertTrue(after > Tokens.HOLD_RING_MIN_OPACITY && after < 1,
                "the red should be part way up, got " + after);
        assertEquals(Units.inchesToPx(box.w_in) + inset * 2, ring.getWidth(), 0.01,
                "and the ring should be exactly the size it started");

        touch(TouchEvent.TOUCH_RELEASED, center.getX(), center.getY());
        WaitForAsyncUtils.waitForFxEvents();
        assertNull(ringNode(), "the ring goes as soon as the finger does");
    }

    @Test
    @DisplayName("moving the finger calls the delete off and takes the ring away")
    void movingCancelsTheHold() {
        Point2D center = centerOf(box);
        touch(TouchEvent.TOUCH_PRESSED, center.getX(), center.getY());
        WaitForAsyncUtils.waitForFxEvents();
        assertNotNull(ringNode());

        touch(TouchEvent.TOUCH_MOVED, center.getX() + 300, center.getY());
        WaitForAsyncUtils.waitForFxEvents();
        // One frame for the clock to notice.
        sleepFor(120);

        assertNull(ringNode(), "a drag is not a pending delete");
        touch(TouchEvent.TOUCH_RELEASED, center.getX() + 300, center.getY());
        assertEquals(1, state.items.size(), "and nothing was deleted");
    }

    @Test
    @DisplayName("holding still deletes the box, and undo brings it back")
    void holdingDeletes() {
        double x = box.x_px;
        Point2D center = centerOf(box);
        touch(TouchEvent.TOUCH_PRESSED, center.getX(), center.getY());

        // The one test here that pays the real second and a half. Every boundary around it is
        // already covered for nothing in TouchGestureTest; what this proves is that the clock is
        // running at all and wired to the delete.
        sleepFor(1800);

        assertTrue(state.items.isEmpty(), "the hold should have deleted the box");
        assertNull(ringNode(), "and taken its ring with it");

        // No release is fired: the box's node stopped existing when the delete rebuilt the room,
        // so on a phone the finger lifts over nothing. That is exactly why rebuildItems cancels
        // the gesture rather than leaving it half-finished.
        assertNull(activated, "the delete must not also have opened a dialog");

        interact(() -> canvas.undo());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, state.items.size(), "undo should bring it back");
        assertEquals(x, state.items.get(0).x_px);
    }

    @Test
    @DisplayName("the hold clock stops when the finger goes, rather than running for ever")
    void theClockStopsWithTheGesture() {
        Point2D center = centerOf(box);
        touch(TouchEvent.TOUCH_PRESSED, center.getX(), center.getY());
        sleepFor(200);
        assertTrue(canvas.holdClockFrames() > 0, "the clock should be running while held");

        touch(TouchEvent.TOUCH_RELEASED, center.getX(), center.getY());
        long atRelease = canvas.holdClockFrames();
        sleepFor(300);

        // Nothing on screen can show this: the ring has already been taken off, so a timer left
        // running is invisible and simply burns a phone's battery for the rest of the session.
        assertEquals(atRelease, canvas.holdClockFrames(),
                "the clock kept ticking after the finger lifted");
    }

    @Test
    @DisplayName("replacing the boxes under a live gesture leaves nothing behind")
    void rebuildingLeavesNoRingAndNoRunningClock() {
        Point2D center = centerOf(box);
        touch(TouchEvent.TOUCH_PRESSED, center.getX(), center.getY());
        WaitForAsyncUtils.waitForFxEvents();
        assertNotNull(ringNode(), "the ring is up");

        // What an undo, a delete or a file being loaded does while a finger is still down. JavaFX
        // has no touch-canceled event, so `rebuildItems` calling `cancelTouch` is the stand-in.
        interact(() -> canvas.rebuildItems());
        WaitForAsyncUtils.waitForFxEvents();

        assertNull(ringNode(), "the ring must not survive the children list being thrown away");
        long after = canvas.holdClockFrames();
        sleepFor(300);
        assertEquals(after, canvas.holdClockFrames(), "and the clock must not still be polling");

        touch(TouchEvent.TOUCH_RELEASED, center.getX(), center.getY());
        assertNull(tapped, "the abandoned gesture must not report a tap on the way out");
        assertNull(activated, "nor open a dialog");

        // HONESTY, because a green test is not evidence on its own: deleting `cancelTouch()` from
        // rebuildItems leaves every assertion above still passing. The rebuild clears itemLayer
        // (taking the ring with it) and replaces every recognizer with a fresh one, so the next
        // frame finds nothing holding and shuts the clock down by itself. What the explicit call
        // buys is one frame (without it `applyPaintOrder` can put the ring back around the box
        // before the clock notices) plus not holding a stale undo entry. Neither is observable
        // from out here, and this is recorded as a known-surviving mutation rather than papered
        // over with an assertion that only looks strict.
    }

    @Test
    @DisplayName("lifting in the dead band does nothing, which is how a delete is called off")
    void theDeadBandDoesNothing() {
        Point2D center = centerOf(box);
        touch(TouchEvent.TOUCH_PRESSED, center.getX(), center.getY());
        sleepFor(800);
        touch(TouchEvent.TOUCH_RELEASED, center.getX(), center.getY());

        assertEquals(1, state.items.size(), "too early to delete");
        assertNull(tapped, "too late to be a tap");
        assertNull(activated, "and certainly not an edit");
    }

    @Test
    @DisplayName("a mouse held on a box for two seconds does nothing at all (D-1)")
    void aHeldMouseIsNotADelete() {
        Point2D center = centerOnScreen(box);
        moveTo(center.getX(), center.getY());
        press(MouseButton.PRIMARY);
        sleepFor(2000);
        assertNull(ringNode(), "a held mouse must never show the warning ring");
        release(MouseButton.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();

        // Hold is a touch gesture and only a touch gesture. A desktop deletes by right-clicking,
        // and nothing should ever add a hold to it.
        assertEquals(1, state.items.size(), "a held mouse button must never delete");

        // And the press really did land on the box. Without this the test would pass just as
        // happily if the pointer had missed the window altogether, which is exactly what it did
        // the first time it was written.
        assertEquals(box, activated, "a press and release without moving opens the dialog");
    }

    // --------------------------------------------- Android's synthetic mouse twin

    @Test
    @DisplayName("the mouse events Android invents from a finger do not move the box a second time")
    void synthesizedMouseIsIgnoredOnABox() {
        double startX = box.x_px;
        Rectangle rect = rectFor(box);
        Point2D center = centerOf(box);

        fireMouse(rect, MouseEvent.MOUSE_PRESSED, center.getX(), center.getY(), true);
        fireMouse(rect, MouseEvent.MOUSE_DRAGGED, center.getX() + 300, center.getY(), true);
        fireMouse(rect, MouseEvent.MOUSE_RELEASED, center.getX() + 300, center.getY(), true);

        assertEquals(startX, box.x_px,
                "a synthesized mouse drag is the touch handlers' gesture arriving twice");
        assertNull(activated, "and its release must not open the dialog either");
    }

    @Test
    @DisplayName("a real mouse still drags the same box, so the filter is not simply off")
    void realMouseStillWorks() {
        double startX = box.x_px;
        dragBy(box, 200, 0);
        assertEquals(startX + 200, box.x_px, 2,
                "suppressing the synthetic twin must not suppress a real mouse");
    }

    @Test
    @DisplayName("the room itself is left pannable: nothing above a box filters the finger's mouse")
    void theRoomKeepsItsFingerScrolling() {
        // On Android the room is scrolled by dragging it, and JavaFX does that from the very mouse
        // events the box above suppresses. A filter on the view, the holder or the scene would be
        // invisible on a desktop and would leave the room frozen on a phone, so what is asserted
        // here is that such a filter does not exist: an event aimed at bare floor survives.
        Point2D origin = canvas.roomOriginInScene();
        MouseEvent press = mouse(MouseEvent.MOUSE_PRESSED,
                origin.getX() + 1200, origin.getY() + 700, true);
        interact(() -> Event.fireEvent(canvas, press));
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(press.isConsumed(),
                "a synthesized press on the room must reach the scroller that pans it");
    }

    // -------------------------------------------------------------------- helpers

    /** Fires one touch at the box's rectangle, in scene coordinates. */
    private void touch(EventType<TouchEvent> type, double sceneX, double sceneY) {
        Rectangle rect = rectFor(box);
        TouchPoint point = point(rect, sceneX, sceneY);
        interact(() -> Event.fireEvent(rect, new TouchEvent(type, point, List.of(point), 1,
                false, false, false, false)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private TouchPoint point(Node target, double sceneX, double sceneY) {
        TouchPoint.State state = TouchPoint.State.MOVED;
        return new TouchPoint(1, state, sceneX, sceneY, sceneX, sceneY, target, null);
    }

    private void dragWithFinger(Item item, double dx, double dy) {
        Point2D center = centerOf(item);
        touch(TouchEvent.TOUCH_PRESSED, center.getX(), center.getY());
        touch(TouchEvent.TOUCH_MOVED, center.getX() + dx, center.getY() + dy);
        touch(TouchEvent.TOUCH_RELEASED, center.getX() + dx, center.getY() + dy);
    }

    private void fireMouse(Node target, EventType<MouseEvent> type,
            double sceneX, double sceneY, boolean synthesized) {
        interact(() -> Event.fireEvent(target, mouse(type, sceneX, sceneY, synthesized)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** A mouse event with the synthesized flag set: what Android sends alongside every touch. */
    private MouseEvent mouse(EventType<MouseEvent> type,
            double sceneX, double sceneY, boolean synthesized) {
        return new MouseEvent(type, sceneX, sceneY, sceneX, sceneY, MouseButton.PRIMARY, 1,
                false, false, false, false,
                true,                 // primaryButtonDown
                false, false,
                synthesized,
                false, false, null);
    }

    private void dragBy(Item item, double dx, double dy) {
        Point2D center = centerOnScreen(item);
        moveTo(center.getX(), center.getY());
        press(MouseButton.PRIMARY);
        moveBy(dx / 2, dy / 2);
        moveBy(dx / 2, dy / 2);
        release(MouseButton.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private Rectangle rectFor(Item item) {
        return canvas.itemRect(item.id);
    }

    private Rectangle outlineFor(Item item) {
        return canvas.selectionOutline(item.id);
    }

    /** The hold ring if it is on screen, or null. Found by identity, never by position. */
    private Rectangle ringNode() {
        return canvas.holdRingNode().getParent() == null ? null : canvas.holdRingNode();
    }

    /**
     * The middle of a box in <b>scene</b> coordinates: what a hand-built event carries.
     *
     * <p>Not the same thing as {@link #centerOnScreen}, and mixing the two is a trap: an event
     * fired with screen coordinates lands somewhere plausible but wrong, and a robot sent to scene
     * coordinates misses the window entirely. A test that misses can still pass, if all it asserts
     * is that nothing happened.
     */
    private Point2D centerOf(Item item) {
        Point2D origin = canvas.roomOriginInScene();
        double scale = canvas.fitScaleFactor();
        return new Point2D(
                origin.getX() + (item.x_px + Units.inchesToPx(item.w_in) / 2) * scale,
                origin.getY() + (item.y_px + Units.inchesToPx(item.l_in) / 2) * scale);
    }

    /** The middle of a box in <b>screen</b> coordinates: what the TestFX robot works in. */
    private Point2D centerOnScreen(Item item) {
        Point2D inScene = centerOf(item);
        Scene scene = canvas.getScene();
        return new Point2D(
                scene.getWindow().getX() + scene.getX() + inScene.getX(),
                scene.getWindow().getY() + scene.getY() + inScene.getY());
    }

    private void sleepFor(long millis) {
        WaitForAsyncUtils.sleep(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static Item item(String id, int serial, double w, double l, double x, double y) {
        Item made = new Item();
        made.id = id;
        made.name = "";
        made.serial = serial;
        made.w_in = w;
        made.l_in = l;
        made.h_in = 12;
        made.x_px = x;
        made.y_px = y;
        made.color = "hsl(200 60% 50%)";
        made.dragOrder = serial;
        return made;
    }
}
