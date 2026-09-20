package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.threed.CameraPose;
import com.modcritic.invmgr.threed.Walk;
import java.util.List;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TouchEvent;
import javafx.scene.input.TouchPoint;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Fingers in the 3D room: the thumbstick, looking, panning, the pinch, and the tap.
 *
 * <p>{@code WalkTest}, {@code JoystickTest} and {@code WalkGestureTest} own every question about how
 * far and which way, and answer them in milliseconds with no window. <b>This class asks the
 * questions only a running application can answer</b>: are the touch filters installed, does the
 * right finger reach the right recognizer, does the stick actually drive the walking loop, and does
 * Android's second copy of every finger (the synthesized mouse event) get past the guards meant to
 * stop it.
 *
 * <p><b>The touches are built by hand and fired at real nodes</b>, the same recipe
 * {@code CanvasTouchTest} uses for the flat room. There is no touchscreen here and there never will
 * be, but {@code TouchEvent} and {@code TouchPoint} are ordinary public classes. What this still
 * does <b>not</b> prove is that a phone's own event stream looks like the one assembled here; that
 * is what the APK is for.
 */
class ThreeDTouchTest extends ApplicationTest {

    private static final int REFERENCE_WIDTH = 2560;
    private static final int REFERENCE_HEIGHT = 1440;

    /** Somewhere in the middle of the window, well away from the joystick and the return button. */
    private static final double OPEN_X = REFERENCE_WIDTH / 2.0;
    private static final double OPEN_Y = REFERENCE_HEIGHT / 2.0;

    private App app;
    private Scene scene;

    @Override
    public void start(Stage stage) {
        app = new App();
        app.start(stage);
        // Pinned: TestFX reuses one stage for the whole run, and every position below is measured
        // against this size rather than assumed from it.
        stage.setMaximized(false);
        stage.setWidth(REFERENCE_WIDTH);
        stage.setHeight(REFERENCE_HEIGHT);
        stage.setX(0);
        stage.setY(0);
        scene = stage.getScene();
    }

    // -------------------------------------------------------------------- setup

    /** Opens the room with the phone's layout on. The descent is {@code ThreeDEntryTest}'s. */
    private void openTouchThreeD() {
        interact(() -> {
            Item box = new Item();
            box.id = "box-1";
            box.serial = 1;
            box.dragOrder = 1;
            box.x_px = 5 * 96;
            box.y_px = 3 * 96;
            box.w_in = 24;
            box.l_in = 24;
            box.h_in = 24;
            box.color = "hsl(207,55%,42%)";
            app.state().items.add(box);
            app.view3d().setTouchControls(true);
            app.view3d().open(app.state(), scene);
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Lets real time pass while JavaFX keeps animating; walking needs a clock. */
    private void settle(long millis) {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            WaitForAsyncUtils.sleep(20, java.util.concurrent.TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    private JoystickView stick() {
        return app.view3d().joystick();
    }

    /** The middle of the joystick base, in scene coordinates. */
    private Point2D stickCenter() {
        return WaitForAsyncUtils.waitForAsyncFx(5000, () -> stick().base()
                .localToScene(stick().base().getCenterX(), stick().base().getCenterY()));
    }

    private TouchPoint point(int id, Node target, double sceneX, double sceneY,
            TouchPoint.State state) {
        return new TouchPoint(id, state, sceneX, sceneY, sceneX, sceneY, target, null);
    }

    /** One finger, fired at a node so the scene's filters see it on the way down. */
    private void touch(EventType<TouchEvent> type, int id, Node target,
            double sceneX, double sceneY) {
        TouchPoint.State state = type == TouchEvent.TOUCH_RELEASED
                ? TouchPoint.State.RELEASED : TouchPoint.State.MOVED;
        TouchPoint one = point(id, target, sceneX, sceneY, state);
        interact(() -> Event.fireEvent(target,
                new TouchEvent(type, one, List.of(one), 1, false, false, false, false)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Two fingers at once, delivered the way JavaFX delivers them: one event per finger, each
     * carrying the whole set. The second event of a pair is exactly what would double a movement if
     * the recognizer worked from deltas rather than from positions, so both are fired every time.
     */
    private void touchPair(EventType<TouchEvent> type, Node target,
            double x0, double y0, double x1, double y1) {
        TouchPoint.State state = type == TouchEvent.TOUCH_RELEASED
                ? TouchPoint.State.RELEASED : TouchPoint.State.MOVED;
        TouchPoint first = point(1, target, x0, y0, state);
        TouchPoint second = point(2, target, x1, y1, state);
        List<TouchPoint> both = List.of(first, second);
        interact(() -> {
            Event.fireEvent(target,
                    new TouchEvent(type, first, both, 1, false, false, false, false));
            Event.fireEvent(target,
                    new TouchEvent(type, second, both, 1, false, false, false, false));
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** A mouse event carrying Android's "this was really a finger" flag. */
    private MouseEvent mouse(EventType<MouseEvent> type, double sceneX, double sceneY,
            boolean synthesized) {
        return new MouseEvent(type, sceneX, sceneY, sceneX, sceneY, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, synthesized, false, false, null);
    }

    private void fireMouse(EventType<MouseEvent> type, double sceneX, double sceneY,
            boolean synthesized) {
        interact(() -> Event.fireEvent(scene.getRoot(), mouse(type, sceneX, sceneY, synthesized)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    // ---------------------------------------------------------------- the stick

    @Test
    @DisplayName("the joystick is on a phone and nowhere else")
    void theStickOnlyExistsOnTouch() {
        openTouchThreeD();
        assertNotNull(stick().getScene(), "the stick must be in the window on a phone");
        assertTrue(app.view3d().isTouchControls());

        WaitForAsyncUtils.waitForAsyncFx(5000, () -> app.view3d().setTouchControls(false));
        WaitForAsyncUtils.waitForFxEvents();

        // Removed from the scene rather than merely hidden. A hidden node is not pickable, so
        // hiding would be enough to be safe, but a desktop with a joystick in its scene graph is
        // a desktop that grew a control the original does not give it.
        assertEquals(null, stick().getScene(), "a desktop must have no joystick in it at all");
    }

    @Test
    @DisplayName("the stick sits above the phone's navigation bar, not under it")
    void theStickClearsTheSystemBars() {
        openTouchThreeD();
        SystemInsets phone = new SystemInsets(24, 0, 48, 0);
        WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            app.view3d().setSafeArea(phone);
            app.view3d().applyCss();
            app.view3d().layout();
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();

        Bounds where = WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> stick().localToScene(stick().getBoundsInLocal()));

        assertEquals(JoystickView.INSET_LEFT, where.getMinX(), 1,
                "22 px from the left edge of the glass");
        // 26 above the usable area, and the usable area stops 48 px short of the bottom. Written as
        // the sum rather than as 74, so the two numbers it is made of are both visible.
        assertEquals(REFERENCE_HEIGHT - (JoystickView.INSET_BOTTOM + phone.bottom()),
                where.getMaxY(), 1, "26 px above the navigation bar, not 26 px above the glass");
    }

    @Test
    @DisplayName("a thumb on the base walks the camera, and the knob follows it")
    void aThumbOnTheStickWalks() {
        openTouchThreeD();
        Point2D center = stickCenter();

        // Full deflection straight up the screen: 40 px is past the 30 px radius, so it clamps.
        touch(TouchEvent.TOUCH_PRESSED, 1, stick().base(), center.getX(), center.getY() - 40);

        assertTrue(app.view3d().controls().joystick().isActive(), "the stick must be held");
        assertEquals(-30, stick().knob().getTranslateY(), 0.001,
                "the knob must be drawn at the clamped 30 px, not at the thumb's 40");
        assertEquals(1, app.view3d().controls().joystick().forward(), 0.001);

        // ⚠ The clock and the camera are read in the SAME call on the JavaFX thread, and both ends
        // are. The obvious version (note the camera before the press, start the clock after the
        // assertions above) measured a distance that included every frame those assertions took to
        // run and divided it by the settle alone. It reported 8.8 ft/s against a real 6 and failed,
        // which is a test measuring its own overhead. Nothing between the two samples can walk the
        // camera, because a frame cannot run while this runnable holds the thread.
        double[] start = WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> new double[] { app.view3d().camera().z, app.view3d().camera().x,
                        System.currentTimeMillis() });
        settle(500);
        double[] end = WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> new double[] { app.view3d().camera().z, app.view3d().camera().x,
                        System.currentTimeMillis() });

        double traveled = start[0] - end[0];
        double seconds = (end[2] - start[2]) / 1000.0;

        assertTrue(traveled > 0.5, "half a second of full forward must actually walk north, got "
                + traveled + " ft");
        assertEquals(start[1], end[1], 0.001, "and must not drift east or west");

        // One-sided on purpose. A frame that arrives late is capped at 50 ms by
        // Walk.MAX_FRAME_SECONDS, so a busy machine UNDER-counts the distance and can only push
        // this number down; it can never push it up. So the upper bound is the trustworthy half and
        // a tight lower bound would be the flaky one. The exact 6 ft/s is pinned without any clock
        // at all in JoystickTest.
        double impliedSpeed = traveled / seconds;
        assertTrue(impliedSpeed <= Walk.TOUCH_SPEED_FT_PER_S + 0.5,
                "the stick must walk at the touch speed, not the keyboard's 9 ft/s: implied "
                        + impliedSpeed + " ft/s");

        touch(TouchEvent.TOUCH_RELEASED, 1, stick().base(), center.getX(), center.getY() - 40);
        assertFalse(app.view3d().controls().joystick().isActive(), "letting go must let go");

        double stopped = app.view3d().camera().z;
        settle(200);
        assertEquals(stopped, app.view3d().camera().z, 0.001,
                "and the camera must stop, not coast");
    }

    @Test
    @DisplayName("the thumb slides right off the stick and it keeps working")
    void theStickKeepsItsFingerOffTheBase() {
        openTouchThreeD();
        Point2D center = stickCenter();
        touch(TouchEvent.TOUCH_PRESSED, 1, stick().base(), center.getX(), center.getY() - 5);

        // 300 px away is nowhere near the 108 px base. Tracked by identifier, not by hit-testing,
        // and the target stays the base because JavaFX delivers a touch to the node it was pressed
        // on for the whole of its life.
        touch(TouchEvent.TOUCH_MOVED, 1, stick().base(), center.getX(), center.getY() - 300);

        assertTrue(app.view3d().controls().joystick().isActive());
        assertEquals(1, app.view3d().controls().joystick().forward(), 0.001,
                "still asking for full speed forward");
    }

    // --------------------------------------------------------- looking and panning

    @Test
    @DisplayName("one finger anywhere else looks around, and leaves the stick alone")
    void oneFingerLooks() {
        openTouchThreeD();
        double before = WaitForAsyncUtils.waitForAsyncFx(5000, () -> app.view3d().camera().yaw);

        touch(TouchEvent.TOUCH_PRESSED, 1, app.view3d(), OPEN_X, OPEN_Y);
        touch(TouchEvent.TOUCH_MOVED, 1, app.view3d(), OPEN_X + 100, OPEN_Y);

        assertEquals(before + 100 * com.modcritic.invmgr.threed.WalkGesture.LOOK_SENS_RAD_PER_PX,
                app.view3d().camera().yaw, 1e-9, "100 px right is 0.6 rad of yaw");
        assertFalse(app.view3d().controls().joystick().isActive(),
                "a finger in the middle of the room is not on the stick");
        assertEquals(0, stick().knob().getTranslateX(), 0.001, "and must not move the knob");
    }

    @Test
    @DisplayName("a thumb on the stick and a finger looking work at the same time")
    void theStickAndTheLookAreIndependent() {
        openTouchThreeD();
        Point2D center = stickCenter();

        touch(TouchEvent.TOUCH_PRESSED, 1, stick().base(), center.getX(), center.getY() - 30);
        double yawBefore = app.view3d().camera().yaw;

        // A second finger, elsewhere. It must start a LOOK rather than being counted as the
        // stick's, which is what otherTouches3d exists for.
        TouchPoint thumb = point(1, stick().base(), center.getX(), center.getY() - 30,
                TouchPoint.State.STATIONARY);
        TouchPoint finger = point(2, app.view3d(), OPEN_X, OPEN_Y, TouchPoint.State.PRESSED);
        List<TouchPoint> both = List.of(thumb, finger);
        interact(() -> Event.fireEvent(app.view3d(), new TouchEvent(TouchEvent.TOUCH_PRESSED,
                finger, both, 1, false, false, false, false)));
        WaitForAsyncUtils.waitForFxEvents();

        TouchPoint moved = point(2, app.view3d(), OPEN_X + 50, OPEN_Y, TouchPoint.State.MOVED);
        List<TouchPoint> after = List.of(thumb, moved);
        interact(() -> Event.fireEvent(app.view3d(), new TouchEvent(TouchEvent.TOUCH_MOVED,
                moved, after, 1, false, false, false, false)));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(app.view3d().controls().joystick().isActive(),
                "the thumb must still be holding the stick");
        assertEquals(1, app.view3d().controls().joystick().forward(), 0.001,
                "and still asking for full speed");
        assertEquals(yawBefore + 50 * com.modcritic.invmgr.threed.WalkGesture.LOOK_SENS_RAD_PER_PX,
                app.view3d().camera().yaw, 1e-9,
                "the second finger must be a one-finger look, not half of a two-finger pan");
    }

    @Test
    @DisplayName("two fingers slid across the glass pan the camera, once and not twice")
    void twoFingersSlidingPanTheCamera() {
        openTouchThreeD();
        CameraPose before = WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> app.view3d().camera().copy());

        touchPair(TouchEvent.TOUCH_PRESSED, app.view3d(),
                OPEN_X - 50, OPEN_Y, OPEN_X + 50, OPEN_Y);
        // Both fingers 30 px right, and no further apart than they started.
        touchPair(TouchEvent.TOUCH_MOVED, app.view3d(),
                OPEN_X - 20, OPEN_Y, OPEN_X + 80, OPEN_Y);

        assertEquals(before.x + 30 * com.modcritic.invmgr.threed.WalkGesture.PAN_SENS_FT_PER_PX,
                app.view3d().camera().x, 1e-9, "the midpoint moved 30 px right: 0.9 ft east");
        assertEquals(before.z, app.view3d().camera().z, 1e-9,
                "a slide at yaw 0 must not also carry you north");
        assertEquals(before.yaw, app.view3d().camera().yaw, 1e-9,
                "two fingers must not turn the camera");

        // The half of this that only a window can check: JavaFX fires one event per finger, so the
        // movement above arrived twice. A recognizer working from deltas would have applied it
        // twice and doubled every number here.
    }

    @Test
    @DisplayName("spreading two fingers moves the camera forward without dragging it sideways")
    void spreadingTwoFingersMovesForward() {
        openTouchThreeD();
        CameraPose before = WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> app.view3d().camera().copy());

        touchPair(TouchEvent.TOUCH_PRESSED, app.view3d(),
                OPEN_X - 50, OPEN_Y, OPEN_X + 50, OPEN_Y);
        // A one-handed pinch as it is actually performed on a phone: one finger rests and the other
        // travels 100 px. That drifts the midpoint 50 px, which used to pan the camera 1.5 ft east
        // at the same moment as it moved 1.5 ft forward: the forty-five degrees the user reported
        // 2026-08-08. Through the wiring, not only through the recognizer, because the finger that
        // rests still arrives as its own event.
        touchPair(TouchEvent.TOUCH_MOVED, app.view3d(),
                OPEN_X - 50, OPEN_Y, OPEN_X + 150, OPEN_Y);

        assertEquals(before.z - 100 * com.modcritic.invmgr.threed.WalkGesture.PINCH_SENS_FT_PER_PX,
                app.view3d().camera().z, 1e-9, "100 px of separation is 1.5 ft forward");
        assertEquals(before.x, app.view3d().camera().x, 1e-9,
                "and the 50 px of midpoint drift must move the camera nowhere sideways");
        assertEquals(before.yaw, app.view3d().camera().yaw, 1e-9, "nor turn it");
    }

    // -------------------------------------------------------------- tapping a box

    @Test
    @DisplayName("tapping a box names it, above the fingertip and on a clock")
    void tappingABoxShowsTheTouchTooltip() {
        openTouchThreeD();
        interact(() -> {
            // openTouchThreeD's box occupies x 5..7, y 0..2, z 3..5 in feet; this stands square in
            // front of it, the same aim ThreeDControlsTest uses for the hover.
            app.view3d().camera().x = 6;
            app.view3d().camera().y = 1;
            app.view3d().camera().z = 12;
            app.view3d().camera().yaw = 0;
            app.view3d().camera().pitch = 0;
            app.view3d().render();
        });
        WaitForAsyncUtils.waitForFxEvents();

        touch(TouchEvent.TOUCH_PRESSED, 1, app.view3d(), OPEN_X, OPEN_Y);
        touch(TouchEvent.TOUCH_RELEASED, 1, app.view3d(), OPEN_X, OPEN_Y);

        assertTrue(app.tooltip3d().isShowing(), "a tap on a box must name it");
        Item box = app.state().items.get(0);
        assertEquals(com.modcritic.invmgr.engine.TextFormat.tooltipText(app.state(), box),
                app.tooltip3d().text(), "and say exactly what the flat room's tooltip would");

        // Above the finger, not beside it. The label's bottom edge is on the point that was tapped,
        // because a fingertip covers what it is touching. Beside-the-pointer is the hover's rule
        // and using it here would put the words under the thumb that asked for them.
        Bounds where = WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> app.tooltip3d().node().localToScene(
                        app.tooltip3d().node().getBoundsInLocal()));
        assertTrue(where.getMaxY() <= OPEN_Y + 1,
                "the tooltip must sit above the tap, got maxY " + where.getMaxY());
        assertTrue(app.tooltip3d().node().getScene() != null, "and actually be in the window");
    }

    @Test
    @DisplayName("a finger that looked around does not also name a box")
    void aLookIsNotATap() {
        openTouchThreeD();
        interact(() -> {
            app.view3d().camera().x = 6;
            app.view3d().camera().y = 1;
            app.view3d().camera().z = 12;
            app.view3d().camera().yaw = 0;
            app.view3d().camera().pitch = 0;
            app.view3d().render();
        });
        WaitForAsyncUtils.waitForFxEvents();

        touch(TouchEvent.TOUCH_PRESSED, 1, app.view3d(), OPEN_X, OPEN_Y);
        touch(TouchEvent.TOUCH_MOVED, 1, app.view3d(), OPEN_X + 40, OPEN_Y);
        touch(TouchEvent.TOUCH_RELEASED, 1, app.view3d(), OPEN_X + 40, OPEN_Y);

        assertFalse(app.tooltip3d().isShowing(),
                "forty pixels is a look, and a look must not leave a label behind it");
    }

    // ------------------------------------------------- the mouse Android invents

    @Test
    @DisplayName("the mouse Android makes from a finger does not turn the camera a second time")
    void aSynthesizedDragIsIgnored() {
        openTouchThreeD();
        double before = WaitForAsyncUtils.waitForAsyncFx(5000, () -> app.view3d().camera().yaw);

        fireMouse(MouseEvent.MOUSE_PRESSED, OPEN_X, OPEN_Y, true);
        fireMouse(MouseEvent.MOUSE_DRAGGED, OPEN_X + 200, OPEN_Y, true);

        assertEquals(before, app.view3d().camera().yaw, 1e-12,
                "a finger's mouse copy must not look as well as the touch path");
        assertFalse(app.view3d().controls().isCapturing());

        // A real mouse still works, which is the other half: the guard has to reject the copy and
        // not the thing it is a copy of.
        fireMouse(MouseEvent.MOUSE_PRESSED, OPEN_X, OPEN_Y, false);
        fireMouse(MouseEvent.MOUSE_DRAGGED, OPEN_X + 200, OPEN_Y, false);
        assertTrue(Math.abs(app.view3d().camera().yaw - before) > 0.1,
                "a real mouse drag must still look around");
    }

    @Test
    @DisplayName("lifting a finger does not try to capture a pointer the phone has not got")
    void aSynthesizedClickDoesNotGrabThePointer() {
        openTouchThreeD();

        fireMouse(MouseEvent.MOUSE_PRESSED, OPEN_X, OPEN_Y, true);
        fireMouse(MouseEvent.MOUSE_RELEASED, OPEN_X, OPEN_Y, true);

        // Without the guard this is a click, and a click toggles the pointer capture: the cursor is
        // hidden and a Robot starts warping it to the middle of a window nobody is pointing at.
        assertFalse(app.view3d().controls().isCapturing(),
                "a tap must never turn on mouse capture");
    }

    @Test
    @DisplayName("a mouse press followed by a finger's drag does not turn the camera")
    void theGuardOnTheDragIsNotJustTheOneOnThePress() {
        openTouchThreeD();
        double before = WaitForAsyncUtils.waitForAsyncFx(5000, () -> app.view3d().camera().yaw);

        // ⚠ Written this way on purpose. The obvious version (synthesized press THEN synthesized
        // drag) cannot see the drag's own guard at all: the press was already refused, so nothing
        // is being dragged and the drag returns on that instead. The mutation sweep duly reported
        // removing the drag's guard as survived. Mixing a real press with a finger's drag is the
        // case the guard actually exists for, and on a touchscreen laptop it is a real case.
        fireMouse(MouseEvent.MOUSE_PRESSED, OPEN_X, OPEN_Y, false);
        fireMouse(MouseEvent.MOUSE_DRAGGED, OPEN_X + 200, OPEN_Y, true);

        assertEquals(before, app.view3d().camera().yaw, 1e-12,
                "a finger's drag must be refused even when a real button is down");
    }

    @Test
    @DisplayName("a mouse press followed by a finger's release does not grab the pointer")
    void theGuardOnTheReleaseIsNotJustTheOneOnThePress() {
        openTouchThreeD();

        fireMouse(MouseEvent.MOUSE_PRESSED, OPEN_X, OPEN_Y, false);
        fireMouse(MouseEvent.MOUSE_RELEASED, OPEN_X, OPEN_Y, true);

        assertFalse(app.view3d().controls().isCapturing(),
                "a finger lifting must not toggle the pointer capture a mouse press armed");
    }

    @Test
    @DisplayName("a finger that has lifted is out of the set at once, not one event later")
    void aLiftingFingerLeavesTheSetImmediately() {
        openTouchThreeD();
        CameraPose before = WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> app.view3d().camera().copy());

        touchPair(TouchEvent.TOUCH_PRESSED, app.view3d(),
                OPEN_X - 100, OPEN_Y, OPEN_X + 100, OPEN_Y);

        // The right-hand finger lifts. JavaFX still lists it, marked RELEASED; a browser's
        // e.touches would not. Keeping it would leave the gesture panning from a pair that is no
        // longer on the glass.
        TouchPoint staying = point(1, app.view3d(), OPEN_X - 100, OPEN_Y, TouchPoint.State.MOVED);
        TouchPoint leaving = point(2, app.view3d(), OPEN_X + 100, OPEN_Y,
                TouchPoint.State.RELEASED);
        List<TouchPoint> both = List.of(staying, leaving);
        interact(() -> Event.fireEvent(app.view3d(), new TouchEvent(TouchEvent.TOUCH_RELEASED,
                leaving, both, 1, false, false, false, false)));
        WaitForAsyncUtils.waitForFxEvents();

        double xAfterLift = app.view3d().camera().x;

        // Now the finger still down moves 60 px right. That is one finger, so it must LOOK.
        touch(TouchEvent.TOUCH_MOVED, 1, app.view3d(), OPEN_X - 40, OPEN_Y);

        assertEquals(xAfterLift, app.view3d().camera().x, 1e-9,
                "one finger left must look, not go on panning");
        assertEquals(before.yaw + 60 * com.modcritic.invmgr.threed.WalkGesture.LOOK_SENS_RAD_PER_PX,
                app.view3d().camera().yaw, 1e-9, "and look by its own 60 px");
    }

    @Test
    @DisplayName("a second thumb cannot take a stick another one is already holding")
    void theStickBelongsToTheFirstThumb() {
        openTouchThreeD();
        Point2D center = stickCenter();

        touch(TouchEvent.TOUCH_PRESSED, 1, stick().base(), center.getX(), center.getY() - 30);
        assertEquals(-30, stick().knob().getTranslateY(), 0.001);

        // A second finger lands on the base, on the opposite side. The stick must not follow it.
        TouchPoint first = point(1, stick().base(), center.getX(), center.getY() - 30,
                TouchPoint.State.STATIONARY);
        TouchPoint second = point(2, stick().base(), center.getX(), center.getY() + 30,
                TouchPoint.State.PRESSED);
        // ⚠ The NEW finger is listed first, and that is what makes this case able to fail. The
        // stick is claimed by scanning the whole batch, so with the incumbent listed first the scan
        // would reach it before the newcomer and re-apply the position it already had, masking a
        // missing "a held stick is not up for grabs" guard completely. The sweep reported exactly
        // that: this case caught the mutation until the batch scan arrived, and then stopped.
        List<TouchPoint> both = List.of(second, first);
        interact(() -> Event.fireEvent(stick().base(), new TouchEvent(TouchEvent.TOUCH_PRESSED,
                second, both, 1, false, false, false, false)));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(app.view3d().controls().joystick().owns(1),
                "the stick still belongs to the thumb that grabbed it");
        assertEquals(-30, stick().knob().getTranslateY(), 0.001,
                "and the knob must not jump to the other side of the base");
        assertEquals(1, app.view3d().controls().joystick().forward(), 0.001,
                "nor reverse the direction of travel");
    }

    @Test
    @DisplayName("a thumb and a finger landing together do not leave the finger dead")
    void twoFingersInOneBatchDoNotConfuseEachOther() {
        openTouchThreeD();
        Point2D center = stickCenter();

        // Both land in the same batch, and the ROOM finger's event is delivered first, which is
        // the order that gets it wrong if the stick is only claimed by its own event. The
        // recognizer would count the thumb as a second finger, enter a two-finger pan, and then
        // never leave it: from the next event on there is only one finger it owns, and a pan wants
        // two, so the finger on the room does nothing at all until it is lifted.
        TouchPoint onTheRoom = point(1, app.view3d(), OPEN_X, OPEN_Y, TouchPoint.State.PRESSED);
        TouchPoint onTheStick = point(2, stick().base(), center.getX(), center.getY() - 30,
                TouchPoint.State.PRESSED);
        List<TouchPoint> both = List.of(onTheRoom, onTheStick);
        interact(() -> {
            Event.fireEvent(app.view3d(), new TouchEvent(TouchEvent.TOUCH_PRESSED, onTheRoom,
                    both, 1, false, false, false, false));
            Event.fireEvent(stick().base(), new TouchEvent(TouchEvent.TOUCH_PRESSED, onTheStick,
                    both, 1, false, false, false, false));
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(app.view3d().controls().joystick().owns(2), "the thumb took the stick");
        assertEquals(com.modcritic.invmgr.threed.WalkGesture.Mode.LOOK,
                app.view3d().controls().gesture().mode(),
                "and the other finger is a one-finger look, not half of a pan");

        double before = app.view3d().camera().yaw;
        TouchPoint stationary = point(2, stick().base(), center.getX(), center.getY() - 30,
                TouchPoint.State.STATIONARY);
        TouchPoint moved = point(1, app.view3d(), OPEN_X + 50, OPEN_Y, TouchPoint.State.MOVED);
        List<TouchPoint> after = List.of(moved, stationary);
        interact(() -> Event.fireEvent(app.view3d(), new TouchEvent(TouchEvent.TOUCH_MOVED, moved,
                after, 2, false, false, false, false)));
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(before + 50 * com.modcritic.invmgr.threed.WalkGesture.LOOK_SENS_RAD_PER_PX,
                app.view3d().camera().yaw, 1e-9,
                "and it must actually look when it moves, not sit dead until it lifts");
    }

    @Test
    @DisplayName("three fingers follow the same two of them however the platform lists them")
    void theFingersAreFollowedByIdentifierNotByOrder() {
        openTouchThreeD();

        TouchPoint a = point(1, app.view3d(), OPEN_X - 100, OPEN_Y, TouchPoint.State.PRESSED);
        TouchPoint b = point(2, app.view3d(), OPEN_X, OPEN_Y, TouchPoint.State.PRESSED);
        TouchPoint c = point(3, app.view3d(), OPEN_X + 400, OPEN_Y, TouchPoint.State.PRESSED);
        interact(() -> Event.fireEvent(app.view3d(), new TouchEvent(TouchEvent.TOUCH_PRESSED,
                a, List.of(a, b, c), 1, false, false, false, false)));
        WaitForAsyncUtils.waitForFxEvents();

        CameraPose settled = WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> app.view3d().camera().copy());

        // The same three fingers, none of them moved, reported in the opposite order. Nothing
        // promises the platform keeps its list stable, and with three down the recognizer follows
        // the first two, so an order that flips would swap which pair is being tracked and slide
        // the camera by the distance between the two midpoints.
        TouchPoint a2 = point(1, app.view3d(), OPEN_X - 100, OPEN_Y, TouchPoint.State.MOVED);
        TouchPoint b2 = point(2, app.view3d(), OPEN_X, OPEN_Y, TouchPoint.State.MOVED);
        TouchPoint c2 = point(3, app.view3d(), OPEN_X + 400, OPEN_Y, TouchPoint.State.MOVED);
        interact(() -> Event.fireEvent(app.view3d(), new TouchEvent(TouchEvent.TOUCH_MOVED,
                c2, List.of(c2, b2, a2), 1, false, false, false, false)));
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(settled.x, app.view3d().camera().x, 1e-9,
                "fingers that did not move must not move the camera, whatever order they arrive in");
        assertEquals(settled.z, app.view3d().camera().z, 1e-9);
    }

    @Test
    @DisplayName("grabbing the stick again mid-ease puts the knob where the thumb is")
    void aRegrabBeatsTheEaseHome() {
        openTouchThreeD();
        Point2D center = stickCenter();

        touch(TouchEvent.TOUCH_PRESSED, 1, stick().base(), center.getX(), center.getY() - 30);

        // ⚠ The lift and the second grab are fired in ONE call on the JavaFX thread, and they have
        // to be. Sent as two ordinary touches they are separated by two waits for the event queue,
        // which is comfortably longer than the knob's 150 ms slide home, so the ease had already
        // finished before the second grab arrived, and the sweep reported removing its stop() as
        // survived. A frame cannot run inside this runnable, so here the ease is genuinely still
        // playing when the thumb comes back.
        TouchPoint up = point(1, stick().base(), center.getX(), center.getY() - 30,
                TouchPoint.State.RELEASED);
        TouchPoint down = point(2, stick().base(), center.getX(), center.getY() - 30,
                TouchPoint.State.PRESSED);
        interact(() -> {
            Event.fireEvent(stick().base(), new TouchEvent(TouchEvent.TOUCH_RELEASED, up,
                    List.of(up), 1, false, false, false, false));
            Event.fireEvent(stick().base(), new TouchEvent(TouchEvent.TOUCH_PRESSED, down,
                    List.of(down), 2, false, false, false, false));
        });
        WaitForAsyncUtils.waitForFxEvents();

        settle(90);

        assertEquals(-30, stick().knob().getTranslateY(), 0.5,
                "the ease from the last release must be stopped, not left dragging the knob home "
                        + "under a thumb that is holding it out");
        assertEquals(1, app.view3d().controls().joystick().forward(), 0.001);
    }

    // ------------------------------------------------------------ the way out

    @Test
    @DisplayName("leaving the room lets go of the stick")
    void closingReleasesEverything() {
        openTouchThreeD();
        Point2D center = stickCenter();
        touch(TouchEvent.TOUCH_PRESSED, 1, stick().base(), center.getX(), center.getY() - 30);
        assertTrue(app.view3d().controls().joystick().isActive());

        WaitForAsyncUtils.waitForAsyncFx(5000, () -> app.view3d().close());
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(app.view3d().controls().joystick().isActive(),
                "a thumb cannot still be on a stick that is not on screen");
        assertEquals(0, stick().knob().getTranslateY(), 0.001, "and the knob is back in the middle");
    }

    @Test
    @DisplayName("the stick goes away the moment the way out begins, like the return button")
    void theAscentTakesTheStickWithIt() {
        openTouchThreeD();
        assertTrue(stick().isVisible());

        // Started rather than watched all the way through: the ascent is 1920 ms and the question
        // here is only whether the stick is taken off screen at the top of it. Whether the flight
        // lands correctly is ThreeDEntryTest's and ThreeDSkipTest's.
        WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            app.view3d().closeWithAscent(app.state(), scene, () -> { });
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(stick().isVisible(),
                "a thumb on the stick during the ascent would be walking a camera a flight is "
                        + "already flying");
        assertFalse(app.view3d().returnButton().isVisible(), "as the return button already does");
    }

    // ------------------------------------------------------------- the hint

    @Test
    @DisplayName("the controls hint names gestures on a phone, and fits on one")
    void theHintSaysWhatAThumbCanDo() {
        openTouchThreeD();

        String text = WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> app.view3d().controlsHintNode().getText());
        assertEquals(View3D.CONTROLS_HINT_TOUCH, text);
        assertFalse(text.contains("WASD"), "a phone has no W, A, S or D on it");
        assertTrue(text.contains("pinch"), "and the pinch is the one gesture nothing else mentions");

        // The reported half: the desktop wording measures 582 px against a 360 px phone, so it was
        // hanging off both edges. Measured on the real label rather than counted in characters,
        // because the padding and the border are part of what has to fit.
        double width = WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            javafx.scene.control.Label hint = app.view3d().controlsHintNode();
            hint.applyCss();
            return hint.prefWidth(-1);
        });
        assertTrue(width < 360, "the hint must fit a phone's 360 px, measured " + width);

        WaitForAsyncUtils.waitForAsyncFx(5000, () -> app.view3d().setTouchControls(false));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(View3D.CONTROLS_HINT, app.view3d().controlsHintNode().getText(),
                "and a desktop keeps the one that names its keys");
    }

    @Test
    @DisplayName("the hint does not sit on top of the joystick")
    void theHintClearsTheStick() {
        openTouchThreeD();

        // ⚠ Found by looking at a rendered phone-sized picture, not by reasoning, and it is the
        // reason there is a rule here at all. The hint is 316 px wide on a 360 px screen and
        // centered at the bottom, so its left end landed squarely on the stick, for the first four
        // seconds after landing, which is exactly when somebody is working out what the stick does.
        // No reference screenshot could have shown it: the original has no controls hint.
        Bounds[] both = WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            app.view3d().setSafeArea(new SystemInsets(24, 0, 48, 0));
            app.view3d().applyCss();
            app.view3d().layout();
            javafx.scene.control.Label hint = app.view3d().controlsHintNode();
            return new Bounds[] {
                hint.localToScene(hint.getBoundsInLocal()),
                stick().localToScene(stick().getBoundsInLocal()) };
        });

        assertFalse(both[0].intersects(both[1]),
                "the hint " + both[0] + " must not overlap the stick " + both[1]);
        assertTrue(both[0].getMinY() >= 0 && both[0].getMaxX() <= REFERENCE_WIDTH,
                "and it must still be on the screen");
        assertTrue(both[0].getMaxY() < both[1].getMinY(),
                "specifically it goes above the stick, not beside it: there is no room beside it");
    }
}
