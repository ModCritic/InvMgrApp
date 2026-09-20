package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.threed.CameraPose;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Walking around the 3D room, driven by real key events in a real window.
 *
 * <p>{@code WalkTest} owns every question about <em>how far</em> and <em>which way</em>, and can
 * ask it a thousand times faster with no window at all. This class asks the questions that only a
 * running application can answer: are the handlers actually installed, does anything reach them,
 * does the loop run, and do the two timers stay out of each other's way.
 *
 * <p>It opens the 3D view directly rather than through the button, because {@code ThreeDEntryTest}
 * owns the button and sitting through a two-and-a-half second descent before every case here would
 * add most of a minute to the suite for nothing. The direct path and the descent are pinned as
 * arriving at the same place by {@code Room3dAppearanceTest}.
 */
class ThreeDControlsTest extends ApplicationTest {

    private static final int REFERENCE_WIDTH = 2560;
    private static final int REFERENCE_HEIGHT = 1440;

    private App app;
    private Scene scene;

    @Override
    public void start(Stage stage) {
        app = new App();
        app.start(stage);
        stage.setMaximized(false);
        stage.setWidth(REFERENCE_WIDTH);
        stage.setHeight(REFERENCE_HEIGHT);
        stage.setX(0);
        stage.setY(0);
        scene = stage.getScene();
    }

    /** Lets real time pass while the JavaFX thread keeps animating. */
    private void settle(long millis) {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            WaitForAsyncUtils.sleep(20, java.util.concurrent.TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void openThreeD() {
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
            app.view3d().open(app.state(), scene);
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Holds a key for a while, then lets go, which is how walking actually happens. */
    private void hold(KeyCode key, long millis) {
        press(key);
        settle(millis);
        release(key);
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    @DisplayName("holding W walks the camera north through the real key handlers")
    void theKeyboardMovesTheCamera() {
        openThreeD();
        CameraPose before = app.view3d().camera().copy();

        hold(KeyCode.W, 300);

        // Direction, not distance: how far depends on how many frames this container managed to
        // draw, which is not something to assert. Which way is not negotiable.
        assertTrue(app.view3d().camera().z < before.z - 0.2,
                "holding W must walk north (z decreasing); z went "
                        + before.z + " -> " + app.view3d().camera().z);
        assertEquals(before.x, app.view3d().camera().x, 0.001, "and must not drift sideways");
        assertEquals(before.y, app.view3d().camera().y, 0.001, "or change height");
    }

    @Test
    @DisplayName("letting go stops the camera dead")
    void releasingAKeyStopsTheWalk() {
        openThreeD();

        hold(KeyCode.W, 200);
        double stopped = app.view3d().camera().z;

        settle(300);

        assertEquals(stopped, app.view3d().camera().z, 0.001,
                "the camera kept moving after the key came up");
    }

    @Test
    @DisplayName("D strafes east, and it is east rather than west")
    void strafingGoesTheWayItShould() {
        openThreeD();
        double before = app.view3d().camera().x;

        hold(KeyCode.D, 300);

        assertTrue(app.view3d().camera().x > before + 0.2,
                "holding D must strafe east (x increasing)");
    }

    @Test
    @DisplayName("Space rises and C descends, and so does Ctrl")
    void theVerticalKeysWorkIncludingCtrl() {
        openThreeD();
        double eye = app.view3d().camera().y;

        hold(KeyCode.SPACE, 250);
        double risen = app.view3d().camera().y;
        assertTrue(risen > eye + 0.2, "Space must rise");

        hold(KeyCode.C, 250);
        double afterC = app.view3d().camera().y;
        assertTrue(afterC < risen - 0.2, "C must descend");

        // CLAUDE.md §5.5 D-13. The original uses C precisely because Ctrl+W closes a browser tab,
        // which is not a constraint here, so Ctrl works too, and C is kept.
        hold(KeyCode.SPACE, 250);
        double risenAgain = app.view3d().camera().y;
        hold(KeyCode.CONTROL, 250);
        assertTrue(app.view3d().camera().y < risenAgain - 0.2, "Ctrl must descend as well as C");
    }

    @Test
    @DisplayName("Space does not press the return button out from under you")
    void spaceRisesRatherThanLeaving() {
        openThreeD();
        interact(() -> app.view3d().returnButton().requestFocus());
        WaitForAsyncUtils.waitForFxEvents();

        double eye = app.view3d().camera().y;
        hold(KeyCode.SPACE, 250);

        // A focused JavaFX Button arms on SPACE pressed and FIRES ON SPACE RELEASED. The return
        // button takes focus the moment the camera lands, so without consuming both halves of the
        // event, holding Space to rise would leave the 3D view instead. Consuming only the press
        // is not enough and looks exactly like it is.
        assertTrue(app.view3d().isOpen(), "Space must not have fired the return button");
        assertTrue(app.view3d().camera().y > eye + 0.2, "and must have risen");
    }

    @Test
    @DisplayName("you can walk out through a wall and look back in")
    void theCameraIsNotFencedIn() {
        openThreeD();

        // Turn to face west and keep going. The room is 12 ft wide and the camera starts in the
        // middle, so a couple of seconds is well past the wall.
        interact(() -> app.view3d().camera().yaw = -Math.PI / 2);
        hold(KeyCode.W, 1200);

        assertTrue(app.view3d().camera().x < 0,
                "the camera should be west of the west wall, not stopped at it; x = "
                        + app.view3d().camera().x);
        assertTrue(app.view3d().camera().x >= -CameraPose.ROOM_SLACK_FT,
                "but not past the sixty feet of slack");
    }

    @Test
    @DisplayName("keys do nothing once the view is closed")
    void theHandlersGoAwayWithTheRoom() {
        openThreeD();
        WaitForAsyncUtils.waitForAsyncFx(5000, () -> app.view3d().close());
        WaitForAsyncUtils.waitForFxEvents();

        CameraPose after = app.view3d().camera().copy();
        hold(KeyCode.W, 300);

        assertEquals(after.z, app.view3d().camera().z, 0.001,
                "a filter was left listening on the scene after the room was torn down");
        assertTrue(app.view3d().controls().input().isIdle(),
                "and the key state should have been forgotten");
    }

    @Test
    @DisplayName("typing into a dialog never moves the camera")
    void theTwoDInterfaceKeepsItsKeys() {
        // The structural claim is that there is no overlap at all: the filters do not exist
        // unless the 3D view is up, and no dialog can be opened while it is. This drives the
        // sequence anyway, because "it cannot happen" is exactly the kind of reasoning that turns
        // out to be wrong after some later change.
        openThreeD();
        WaitForAsyncUtils.waitForAsyncFx(5000, () -> app.view3d().close());
        WaitForAsyncUtils.waitForFxEvents();

        CameraPose before = app.view3d().camera().copy();

        interact(() -> app.topBar().addButtonNode().fire());
        WaitForAsyncUtils.waitForFxEvents();
        write("Wardrobe");
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(before.x, app.view3d().camera().x, 0.001);
        assertEquals(before.z, app.view3d().camera().z, 0.001);
        assertEquals(before.y, app.view3d().camera().y, 0.001);

        type(KeyCode.ESCAPE);
        WaitForAsyncUtils.waitForFxEvents();
    }

    // ------------------------------------------------------------------ looking

    /**
     * Drags the pointer across the middle of the window and returns how far it went.
     *
     * <p>Deliberately in several steps: one jump would be a single event, and the code under test
     * accumulates per-event deltas, so a one-event drag would not exercise the accumulation at
     * all.
     */
    private void dragBy(double dx, double dy) {
        double startX = REFERENCE_WIDTH / 2.0;
        double startY = REFERENCE_HEIGHT / 2.0;
        moveTo(new javafx.geometry.Point2D(startX, startY));
        press(javafx.scene.input.MouseButton.PRIMARY);
        for (int step = 1; step <= 10; step++) {
            moveTo(new javafx.geometry.Point2D(startX + dx * step / 10.0,
                    startY + dy * step / 10.0));
        }
        release(javafx.scene.input.MouseButton.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    @DisplayName("dragging right turns the camera right, by the drag path's own sensitivity")
    void draggingLooksAround() {
        openThreeD();
        double before = app.view3d().camera().yaw;

        dragBy(200, 0);

        double turned = app.view3d().camera().yaw - before;
        assertTrue(turned > 0, "dragging right must increase yaw (turn toward east)");

        // 200 px at 0.0022 x 1.4. Not exact to the last decimal: the robot moves the pointer in
        // whole pixels and the window manager can coalesce moves, so a couple of pixels either
        // way is honest. Bounded on BOTH sides, though, or "it turned at all" would pass on a
        // build using the captured sensitivity, or ten times it.
        double expected = 200 * Walk_MOUSE_SENS * Walk_DRAG_MULT;
        assertTrue(turned > expected * 0.9 && turned < expected * 1.1,
                "expected about " + expected + " rad, got " + turned);
    }

    private static final double Walk_MOUSE_SENS =
            com.modcritic.invmgr.threed.Walk.MOUSE_SENS_RAD_PER_PX;
    private static final double Walk_DRAG_MULT =
            com.modcritic.invmgr.threed.Walk.DRAG_LOOK_MULT;

    @Test
    @DisplayName("dragging down looks down, and dragging up looks up")
    void theDragLookIsInvertedTheRightWay() {
        openThreeD();

        dragBy(0, 200);
        assertTrue(app.view3d().camera().pitch < -0.1,
                "dragging down must look down; pitch was " + app.view3d().camera().pitch);

        double lowered = app.view3d().camera().pitch;
        dragBy(0, -200);
        assertTrue(app.view3d().camera().pitch > lowered, "dragging up must look back up");
    }

    @Test
    @DisplayName("looking up and down stops short of vertical instead of tumbling over")
    void thePitchStopsShortOfStraightUp() {
        openThreeD();

        for (int i = 0; i < 8; i++) {
            dragBy(0, -400);
        }
        assertEquals(CameraPose.PITCH_LIMIT_RAD, app.view3d().camera().pitch, 0.001,
                "the pitch should be sitting exactly on its limit");
    }

    @Test
    @DisplayName("pressing the return button does not also drag the view around")
    void theReturnButtonIsNotALookHandle() {
        openThreeD();
        double yaw = app.view3d().camera().yaw;
        double pitch = app.view3d().camera().pitch;

        moveTo(app.view3d().returnButton());
        press(javafx.scene.input.MouseButton.PRIMARY);
        moveTo(new javafx.geometry.Point2D(REFERENCE_WIDTH / 2.0, REFERENCE_HEIGHT / 2.0));
        release(javafx.scene.input.MouseButton.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(yaw, app.view3d().camera().yaw, 1e-9,
                "a press that started on the return button must not turn the camera");
        assertEquals(pitch, app.view3d().camera().pitch, 1e-9);
    }

    // ---------------------------------------------------------------- hovering

    /** Points the camera straight at the test box from a known distance. */
    private void lookAtTheBox() {
        interact(() -> {
            // openThreeD's box occupies x 5..7, y 0..2, z 3..5 in feet.
            app.view3d().camera().x = 6;
            app.view3d().camera().y = 1;
            app.view3d().camera().z = 12;
            app.view3d().camera().yaw = 0;
            app.view3d().camera().pitch = 0;
            app.view3d().render();
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    @DisplayName("resting on a box in the room names it, on screen and with the right words")
    void hoveringABoxShowsItsTooltip() {
        openThreeD();
        lookAtTheBox();

        moveTo(new javafx.geometry.Point2D(REFERENCE_WIDTH / 2.0, REFERENCE_HEIGHT / 2.0));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(app.tooltip3d().isShowing(), "the 3D tooltip should be up");

        com.modcritic.invmgr.model.Item box = app.state().items.get(0);
        assertEquals(com.modcritic.invmgr.engine.TextFormat.tooltipText(app.state(), box),
                app.tooltip3d().text(), "and should say exactly what the 2D one would");

        // AND IT MUST ACTUALLY BE VISIBLE, which the two lines above cannot tell you. The 2D
        // tooltip is drawn on a layer the 3D view hides for as long as it is up, so a version of
        // this that used the wrong instance would match the text perfectly and show nothing at
        // all. Walking the ancestors is what catches that.
        javafx.scene.Node label = app.tooltip3d().node();
        assertTrue(label.getScene() != null, "the label is not in a scene at all");
        for (javafx.scene.Node node = label; node != null; node = node.getParent()) {
            assertTrue(node.isVisible(),
                    "an ancestor of the 3D tooltip is hidden: " + node.getClass().getSimpleName());
            assertTrue(node.getOpacity() > 0,
                    "an ancestor of the 3D tooltip is transparent: "
                            + node.getClass().getSimpleName());
        }
    }

    @Test
    @DisplayName("the tooltip sits beside the pointer, and follows it")
    void theTooltipFollowsThePointer() {
        openThreeD();
        lookAtTheBox();

        double x = REFERENCE_WIDTH / 2.0;
        double y = REFERENCE_HEIGHT / 2.0;
        moveTo(new javafx.geometry.Point2D(x, y));
        WaitForAsyncUtils.waitForFxEvents();

        javafx.geometry.Bounds first = app.tooltip3d().node()
                .localToScene(app.tooltip3d().node().getLayoutBounds());
        assertEquals(x + 14, first.getMinX(), 1.5, "the tooltip's 14 px offset from the cursor");
        assertEquals(y + 10, first.getMinY(), 1.5, "and its 10 px one");

        // Move a little, still on the box, and it should have come along rather than stayed put.
        moveTo(new javafx.geometry.Point2D(x + 20, y + 12));
        WaitForAsyncUtils.waitForFxEvents();

        javafx.geometry.Bounds second = app.tooltip3d().node()
                .localToScene(app.tooltip3d().node().getLayoutBounds());
        assertEquals(x + 20 + 14, second.getMinX(), 1.5);
        assertEquals(y + 12 + 10, second.getMinY(), 1.5);
    }

    @Test
    @DisplayName("the 3D tooltip and the hint grow with the interface zoom, and the picture does not")
    void theOverlayChromeFollowsTheZoom() {
        // Reported by the user 2026-08-06 against the M5.3 jar: the 3D tooltip stayed its 100%
        // size at every zoom. View3D sits OUTSIDE the one transform that scales the whole
        // interface (it has to, or the drawing surface would not be exactly as many pixels as
        // the window and the view would come out stretched) and the tooltip and hint had
        // silently inherited that exemption. Nothing caught it because the only test of the
        // tooltip's position ran at 100%, where a scale of 1 is indistinguishable from no scale.
        openThreeD();
        lookAtTheBox();

        moveTo(new javafx.geometry.Point2D(REFERENCE_WIDTH / 2.0, REFERENCE_HEIGHT / 2.0));
        WaitForAsyncUtils.waitForFxEvents();

        double tooltipAt100 = app.tooltip3d().node().getBoundsInParent().getWidth();
        double hintAt100 = app.view3d().controlsHintNode().getBoundsInParent().getWidth();
        double surfaceAt100 = app.view3d().subScene().getWidth();
        assertTrue(tooltipAt100 > 0 && hintAt100 > 0, "both should have a size to compare");

        setZoomTo(200);
        moveTo(new javafx.geometry.Point2D(REFERENCE_WIDTH / 2.0 + 4, REFERENCE_HEIGHT / 2.0));
        WaitForAsyncUtils.waitForFxEvents();

        // Measured in the PARENT's coordinates, which is the scaled group, so this is the size it
        // actually occupies on the glass rather than the size it thinks it is.
        assertEquals(2.0, scaledWidth(app.tooltip3d().node()) / tooltipAt100, 0.02,
                "the 3D tooltip should be twice the size at 200%");
        assertEquals(2.0, scaledWidth(app.view3d().controlsHintNode()) / hintAt100, 0.02,
                "and so should the controls hint");

        // And the half that must NOT change. A drawing surface scaled with the interface would be
        // a different shape from the window and the whole view would come out stretched.
        assertEquals(surfaceAt100, app.view3d().subScene().getWidth(), 0.001,
                "the 3D picture itself must stay one pixel to one pixel");

        // The chrome must be laid out at the window's size DIVIDED by the zoom, so that scaling it
        // back up fills the glass exactly. Laying it out at the full size and then doubling it
        // draws a window's worth of interface at twice a window; the sizes above still come out
        // 2x, so only this can see it, and what it sees is the return button off the right edge.
        javafx.geometry.Bounds button = app.view3d().returnButton()
                .localToScene(app.view3d().returnButton().getLayoutBounds());
        assertTrue(button.getMaxX() <= app.view3d().getWidth() + 1,
                "the return button has been pushed off the right edge at 200%: it ends at "
                        + button.getMaxX() + " in a " + app.view3d().getWidth() + " px window");
        assertTrue(button.getMinX() > app.view3d().getWidth() * 0.5,
                "and it should still be over on the right-hand side");

        setZoomTo(100);
    }

    /** How wide a node is on the glass, after every transform between it and the scene. */
    private static double scaledWidth(javafx.scene.Node node) {
        return node.localToScene(node.getLayoutBounds()).getWidth();
    }

    /** Drives the zoom the way the app does: a real Ctrl+scroll, as {@code ZoomedOverlayTest}. */
    private void setZoomTo(int percent) {
        interact(() -> {
            for (int i = 0; i < UiScale.STEPS_PERCENT.length; i++) {
                notch(false);
            }
            for (int i = 0; UiScale.STEPS_PERCENT[i] < percent; i++) {
                notch(true);
            }
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(percent, app.uiScalePercent(), "failed to reach the requested zoom");
    }

    private void notch(boolean up) {
        scene.getRoot().fireEvent(new javafx.scene.input.ScrollEvent(
                javafx.scene.input.ScrollEvent.SCROLL,
                0, 0, 0, 0, false, true, false, false, false, false,
                0, up ? 40 : -40, 0, up ? 40 : -40,
                javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
    }

    @Test
    @DisplayName("pointing at empty room takes the tooltip away again")
    void movingOffABoxHidesTheTooltip() {
        openThreeD();
        lookAtTheBox();

        moveTo(new javafx.geometry.Point2D(REFERENCE_WIDTH / 2.0, REFERENCE_HEIGHT / 2.0));
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(app.tooltip3d().isShowing());

        moveTo(new javafx.geometry.Point2D(REFERENCE_WIDTH * 0.04, REFERENCE_HEIGHT / 2.0));
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(app.tooltip3d().isShowing(),
                "a tooltip left up over empty room is naming something you are not pointing at");
    }

    @Test
    @DisplayName("starting to look around takes the tooltip away")
    void draggingHidesTheTooltip() {
        openThreeD();
        lookAtTheBox();

        moveTo(new javafx.geometry.Point2D(REFERENCE_WIDTH / 2.0, REFERENCE_HEIGHT / 2.0));
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(app.tooltip3d().isShowing());

        dragBy(150, 0);

        // Once you are turning your head you are not pointing at anything, and a tooltip left up
        // would be naming whatever was under the cursor when the drag started, which after a few
        // degrees is nowhere near what is there now.
        assertFalse(app.tooltip3d().isShowing(), "the tooltip should go when a look-drag starts");
    }

    @Test
    @DisplayName("leaving the room takes the tooltip with it")
    void closingHidesTheTooltip() {
        openThreeD();
        lookAtTheBox();

        moveTo(new javafx.geometry.Point2D(REFERENCE_WIDTH / 2.0, REFERENCE_HEIGHT / 2.0));
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(app.tooltip3d().isShowing());

        WaitForAsyncUtils.waitForAsyncFx(5000, () -> app.view3d().close());
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(app.tooltip3d().isShowing());
    }

    @Test
    @DisplayName("forgetting the held keys stops the camera dead, which is what a lost window needs")
    void clearingTheKeysStopsTheWalk() {
        openThreeD();

        // The stuck key is simulated by setting the state, NOT by holding a real one, and the
        // difference is not squeamishness: the first attempt held W with the robot and failed,
        // because X11 auto-repeat re-delivers KEY_PRESSED about thirty times a second and each
        // repeat put FORWARD straight back after the clear. That does not happen in the case this
        // guards: when the window loses focus the repeats go to whatever window took it, and this
        // one hears nothing more. So holding a real key here would be testing the opposite
        // situation and failing for the right reason about the wrong thing.
        interact(() -> app.view3d().controls().input()
                .set(com.modcritic.invmgr.threed.WalkInput.Control.FORWARD, true));
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(app.view3d().controls().input().isIdle(), "forward should be recorded as held");

        settle(150);
        assertTrue(app.view3d().camera().z < 5.0, "and should be walking");

        interact(() -> app.view3d().controls().input().clear());
        WaitForAsyncUtils.waitForFxEvents();
        double resting = app.view3d().camera().z;
        settle(250);

        assertTrue(app.view3d().controls().input().isIdle());
        assertEquals(resting, app.view3d().camera().z, 0.001,
                "with the keys forgotten the camera must stand still");

        // What is NOT pinned here: that this is wired to the window's focus at all. Xvfb's idea
        // of focus is not a window manager's, so that half is verified by running the app and is
        // listed with the other things no test here can reach.
    }
}
