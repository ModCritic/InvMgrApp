package com.modcritic.invmgr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Units;
import com.modcritic.invmgr.ui.RoomCanvasView;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Tests that the application window opens and shows the room.
 *
 * <p>This runs the real app: TestFX starts JavaFX, hands {@link App} a real window, and then
 * lets the test inspect it the way a user would see it.
 *
 * <p>It needs a display to draw into. In the container that means Xvfb, a fake X server that
 * renders to memory instead of a monitor; {@code DISPLAY} must point at it.
 */
class AppUiTest extends ApplicationTest {

    private Stage stage;
    private App app;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        this.app = new App();
        app.start(stage);
    }

    @Test
    @DisplayName("the window opens, titled InvMgr")
    void windowOpens() {
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("InvMgr", stage.getTitle());
        assertTrue(stage.isShowing(), "stage should be visible after start()");
    }

    @Test
    @DisplayName("it opens on an empty default room, the same one the original starts with")
    void showsTheDefaultRoom() {
        WaitForAsyncUtils.waitForFxEvents();
        RoomCanvasView canvas = app.canvas();
        assertNotNull(canvas, "the canvas should exist");

        AppState state = canvas.state();
        assertEquals(12, state.room.w);
        assertEquals(10, state.room.l);
        assertEquals(8, state.room.h);
        assertTrue(state.items.isEmpty());
    }

    @Test
    @DisplayName("the room is drawn at 96 pixels to the foot")
    void roomIsDrawnAtTheRightScale() {
        WaitForAsyncUtils.waitForFxEvents();
        // A 12 x 10 ft room is 1152 x 960 px. If this scale ever drifts, every saved file's
        // item coordinates would land in the wrong place.
        assertEquals(1152, Units.feetToPx(app.canvas().state().room.w));
        assertEquals(960, Units.feetToPx(app.canvas().state().room.l));
    }

    @Test
    @DisplayName("the layer slider starts level with the ceiling, snapped to half feet")
    void layerSliderStartsAtTheCeiling() {
        WaitForAsyncUtils.waitForFxEvents();
        // Two steps per foot, so an 8 ft room gives a range of 0-16 with the handle at the top.
        assertEquals(0, app.sliderDrawer().slider().getMin());
        assertEquals(16, app.sliderDrawer().slider().getMax());
        assertEquals(16, app.sliderDrawer().slider().getValue());
        assertEquals(8, app.canvas().state().layerFeet);
    }

    @Test
    @DisplayName("the readout still reports a JavaFX 21 runtime")
    void reportsJavaFxVersion() {
        WaitForAsyncUtils.waitForFxEvents();
        // The version check moved out of the window and into the console when the canvas took
        // the window over, so it is asserted directly rather than read off a label.
        assertTrue(System.getProperty("javafx.runtime.version").startsWith("21"),
                "expected a JavaFX 21 runtime, got: "
                        + System.getProperty("javafx.runtime.version"));
    }

    @Test
    @DisplayName("the 3D capability check still answers")
    void scene3dCheckAnswers() {
        WaitForAsyncUtils.waitForFxEvents();
        // Only that it answers without throwing: whether 3D is available depends on the
        // machine, and asserting either way would make this test fail somewhere valid.
        boolean supported = App.isScene3dSupported();
        assertTrue(supported || !supported);
        // Nothing on the canvas should depend on it — the 2D view must work regardless.
        assertNotNull(app.canvas());
    }

    @Test
    @DisplayName("no stray Label is left over from the M0 diagnostics screen")
    void noLeftoverDiagnostics() {
        WaitForAsyncUtils.waitForFxEvents();
        // The M0 window was a toolchain readout. If any of it survived into the real UI, it
        // would be visible in the corner of the room.
        Label leftover = lookup("#diag-javafx").tryQuery().map(node -> (Label) node).orElse(null);
        assertEquals(null, leftover, "the M0 readout labels should be gone");
    }

    @Test
    @DisplayName("Ctrl+scroll resizes the interface, and the room stays exactly to scale")
    void ctrlScrollScalesTheChromeButNotTheRoom() {
        WaitForAsyncUtils.waitForFxEvents();

        com.modcritic.invmgr.model.Item box = new com.modcritic.invmgr.model.Item();
        box.id = "item-id-11111111-2222-4333-8444-555555555555";
        box.w_in = 24;
        box.l_in = 24;
        box.h_in = 12;
        box.x_px = 96;
        box.y_px = 96;
        box.color = "hsl(122,55%,42%)";
        box.name = "";
        box.customId = "";
        interact(() -> {
            app.canvas().state().items.add(box);
            app.canvas().rebuildItems();
        });
        WaitForAsyncUtils.waitForFxEvents();

        // Measured in SCENE coordinates, which is what the user actually sees: the zoom is a
        // transform above both of these, so scene bounds are the only place the two can be
        // compared. A box's own width in room pixels never changes, so asserting that would prove
        // nothing at all.
        // The top bar is measured by HEIGHT, not width. It stretches across the window at every
        // zoom level -- correctly, that is what a top bar does -- so its width is 1280 either way
        // and comparing it would prove nothing. Its height is content-driven, so that is the
        // dimension the zoom actually moves.
        double roomBoxBefore = boxWidthOnScreen(box.id);
        double topBarBefore = app.topBar().localToScene(app.topBar().getBoundsInLocal()).getHeight();
        assertEquals(100, app.uiScalePercent(), "should start at 100%");

        interact(() -> scaleUp());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(125, app.uiScalePercent(), "one notch up the ladder");
        // THE constraint. The room is drawn 8 px to the inch so a box on screen is a real size;
        // if the zoom stretched it the app would be lying about physical dimensions.
        assertEquals(roomBoxBefore, boxWidthOnScreen(box.id), 0.5,
                "the room must be exactly the same size on screen after zooming the interface");
        // ...and the zoom has to actually be doing something, or the line above passes trivially.
        double topBarAfter = app.topBar().localToScene(app.topBar().getBoundsInLocal()).getHeight();
        assertTrue(topBarAfter > topBarBefore * 1.2,
                "the top bar should have grown by about a quarter, got "
                        + topBarBefore + " -> " + topBarAfter);
    }

    @Test
    @DisplayName("the zoom stops at the ends of the ladder and Ctrl+middle-click resets it")
    void uiScaleClampsAndResets() {
        WaitForAsyncUtils.waitForFxEvents();

        interact(() -> {
            for (int i = 0; i < 20; i++) {
                scaleUp();
            }
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(200, app.uiScalePercent(),
                "scrolling past the top must stop, not wrap round to the smallest");

        // Ctrl+middle-click anywhere over the app.
        interact(() -> app.canvas().getScene().getRoot().fireEvent(middleClickWithControl()));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(100, app.uiScalePercent(), "Ctrl+middle-click returns to the default");
    }

    /** One Ctrl+scroll notch upward, delivered the way a real mouse would. */
    private void scaleUp() {
        app.canvas().getScene().getRoot().fireEvent(new javafx.scene.input.ScrollEvent(
                javafx.scene.input.ScrollEvent.SCROLL,
                0, 0, 0, 0, false, true, false, false, false, false,
                0, 40, 0, 40,
                javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0,
                0, null));
    }

    private javafx.scene.input.MouseEvent middleClickWithControl() {
        return new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                0, 0, 0, 0, javafx.scene.input.MouseButton.MIDDLE, 1,
                false, true, false, false, false, true, false, false, false, false, null);
    }

    /** How wide an item's rectangle is on screen, with every transform above it applied. */
    private double boxWidthOnScreen(String itemId) {
        javafx.scene.shape.Rectangle rect = app.canvas().itemRect(itemId);
        assertNotNull(rect, "the box should have a node on the canvas");
        return rect.localToScene(rect.getBoundsInLocal()).getWidth();
    }

    @Test
    @DisplayName("metric mode labels the slider in metres and drops the foot labels")
    void metricSliderIsLabelledInMetres() {
        WaitForAsyncUtils.waitForFxEvents();

        // Imperial first, so the switch is what is being tested rather than the starting state.
        java.util.List<String> imperial = sliderLabels();
        assertTrue(imperial.contains("0ft"), "imperial should label whole feet, got " + imperial);
        assertTrue(imperial.stream().noneMatch(text -> text.endsWith("m")),
                "no metre labels before switching, got " + imperial);

        clickOn(app.topBar().unitsButton());
        WaitForAsyncUtils.waitForFxEvents();

        // The original keeps the dot grid on half-FEET — that is what the slider snaps to, and it
        // does not change with the unit — and overlays whole-metre labels at their true heights.
        // So the right result is: no foot labels at all, and a metre label per whole metre that
        // fits in the room. The default room is 8 ft, which is 2.43 m, hence 0m 1m 2m and no 3m.
        java.util.List<String> metric = sliderLabels();
        assertTrue(metric.stream().noneMatch(text -> text.endsWith("ft")),
                "metric should drop every foot label, got " + metric);
        assertTrue(metric.contains("0m") && metric.contains("1m") && metric.contains("2m"),
                "metric should label each whole metre, got " + metric);
        assertFalse(metric.contains("3m"), "3 m does not fit in an 8 ft room, got " + metric);
        assertTrue(metric.contains("·"), "the half-foot dot grid must survive, got " + metric);
    }

    /** Every label currently in the layer-slider drawer, by text. */
    private java.util.List<String> sliderLabels() {
        return app.sliderDrawer().lookupAll(".label").stream()
                .map(node -> ((Label) node).getText())
                .filter(text -> text != null && !text.isEmpty())
                .toList();
    }

    @Test
    @DisplayName("after a load replaces the state, the slider drives the NEW state, not the old one")
    void sliderWritesIntoAReplacedState() {
        WaitForAsyncUtils.waitForFxEvents();

        // Loading a file replaces the whole AppState and hands the new one to every view. The
        // slider's value listener is registered once, in the constructor, so if it captured the
        // constructor's argument instead of the field it would stay welded to the state that
        // was replaced: the handle would still move, but nothing would cross-section any more.
        // That was a real bug, and it only ever showed up *after* a load.
        AppState replaced = app.canvas().state();
        AppState loaded = new AppState();

        interact(() -> app.sliderDrawer().setState(loaded));
        WaitForAsyncUtils.waitForFxEvents();

        double replacedBefore = replaced.layerFeet;
        // Any value the rebuild did not already leave it on, so the listener actually fires.
        double target = app.sliderDrawer().slider().getValue() == 0 ? 2 : 0;

        interact(() -> app.sliderDrawer().slider().setValue(target));
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(target / 2, loaded.layerFeet,
                "the slider must write layerFeet into the state it was last given");
        assertEquals(replacedBefore, replaced.layerFeet,
                "and must not still be writing into the state that was thrown away");
    }

    @Test
    @DisplayName("the maximise repair fires only when the window really did shrink")
    void maximizeRepairRuleOnlyFiresOnARealShrink() {
        // The situation this guards against needs a window manager to reproduce -- KWin
        // un-maximising the window when a file dialog opens, while JavaFX's own `maximized`
        // property stays true (JDK-8325549). There is no window manager in this container at all,
        // so the SITUATION cannot be tested here; the RULE can, and it is the part with a
        // judgement call in it. See App.showChooser.
        double screen = 2560;

        assertTrue(App.needsMaximizeRepair(true, 1280, screen),
                "half-width while still claiming to be maximised is the reported symptom");
        assertTrue(App.needsMaximizeRepair(true, 1000, screen),
                "\"sometimes a little smaller\" than half, also reported, must count too");

        // The three ways it must stay out of the way. Firing wrongly would make the window
        // flicker on every Save on platforms that never had the bug.
        assertFalse(App.needsMaximizeRepair(false, 1280, screen),
                "a window that is legitimately not maximised must be left alone");
        assertFalse(App.needsMaximizeRepair(true, 2560, screen),
                "a genuinely maximised window needs no repair");
        assertFalse(App.needsMaximizeRepair(true, 2400, screen),
                "a panel or a rounding error must not be mistaken for the bug");

        // A screen can measure zero while the stage sits between monitors. The comparison covers
        // that on its own -- no positive width is below zero -- so this pins the behaviour rather
        // than a guard: an explicit `screenWidth > 0` check was written, then deleted once a
        // mutation showed removing it changed no answer.
        assertFalse(App.needsMaximizeRepair(true, 1280, 0),
                "an unmeasurable screen must not be treated as a shrunken window");
    }
}
