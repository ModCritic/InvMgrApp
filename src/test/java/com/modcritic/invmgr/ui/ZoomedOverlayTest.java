package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.model.Item;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The two things that float over the app and are positioned by hand: the item tooltip and the
 * planned-item drag ghost. Both have to stay glued to the pointer at every interface zoom level.
 *
 * <p><b>Why this file exists.</b> Everything else in the interface is positioned by JavaFX's
 * layout, so the zoom transform simply carries it along and there is nothing to get wrong. These
 * two are the exceptions — they are told a pointer position and place themselves — and a pointer
 * position arrives in <b>scene</b> coordinates while they place themselves in their layer's
 * coordinates. Those are the same number only at 100%. Getting it wrong applies the zoom twice,
 * which does not look like an off-by-a-constant: the error is proportional to how far across the
 * window you are, so the tooltip sits close to the cursor near the top-left corner and leaves the
 * screen entirely near the bottom-right. That is exactly how the user reported it on 2026-07-30,
 * and it is why every assertion below is made in scene coordinates against a range of positions
 * rather than at one convenient point.
 */
class ZoomedOverlayTest extends ApplicationTest {

    private static final int REFERENCE_WIDTH = 2560;
    private static final int REFERENCE_HEIGHT = 1440;

    /** Must match {@code ItemTooltip.OFFSET_X/Y}, which are private and from the original. */
    private static final double TOOLTIP_OFFSET_X = 14;
    private static final double TOOLTIP_OFFSET_Y = 10;

    /** How much slack an assertion in scene pixels gets. Sub-pixel rounding only. */
    private static final double TOLERANCE = 0.5;

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

    // ------------------------------------------------------------------ the tooltip

    @Test
    @DisplayName("the tooltip sits beside the cursor at every zoom level, wherever it is")
    void tooltipFollowsTheCursorAtEveryZoom() {
        WaitForAsyncUtils.waitForFxEvents();

        // Three points spread across the window. One would not do: the old bug's error was
        // proportional to the distance from the top-left, so a test near the corner would have
        // passed with the error under half a pixel and proved nothing.
        Point2D[] points = {
            new Point2D(200, 150),
            new Point2D(1200, 700),
            new Point2D(2200, 1300),
        };

        for (int step = 0; step < UiScale.STEPS_PERCENT.length; step++) {
            setZoomTo(UiScale.STEPS_PERCENT[step]);
            double factor = UiScale.STEPS_PERCENT[step] / 100.0;

            for (Point2D point : points) {
                interact(() -> app.tooltip().show("Bin  24in W x 24in L x 12in H  base:0in",
                        point.getX(), point.getY()));
                WaitForAsyncUtils.waitForFxEvents();

                Bounds onScreen = inScene(app.tooltip().node());
                String where = "at " + UiScale.STEPS_PERCENT[step] + "% and scene point " + point;

                // The gap itself is a piece of interface, so it grows with the zoom like every
                // other measurement — but it is a GAP, not a multiplier on the position.
                assertEquals(point.getX() + TOOLTIP_OFFSET_X * factor, onScreen.getMinX(),
                        TOLERANCE, "the tooltip's left edge should be beside the cursor " + where);
                assertEquals(point.getY() + TOOLTIP_OFFSET_Y * factor, onScreen.getMinY(),
                        TOLERANCE, "the tooltip's top edge should be beside the cursor " + where);
            }
        }
    }

    @Test
    @DisplayName("hovering a real box while zoomed puts the tooltip beside the real cursor")
    void hoveringAZoomedBoxPutsTheTooltipAtThePointer() {
        WaitForAsyncUtils.waitForFxEvents();
        addBox();
        setZoomTo(150);

        // The pointer is driven for real, so this covers the wiring as well as the arithmetic:
        // that RoomCanvasView reports scene coordinates and App passes them through untouched.
        Bounds box = boxBoundsInScene();
        Point2D pointer = new Point2D(box.getMinX() + box.getWidth() / 2,
                box.getMinY() + box.getHeight() / 2);
        moveTo(new Point2D(pointer.getX() + scene.getWindow().getX(),
                pointer.getY() + scene.getWindow().getY()));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(app.tooltip().isShowing(), "the pointer is over the box");
        Bounds onScreen = inScene(app.tooltip().node());
        assertEquals(pointer.getX() + TOOLTIP_OFFSET_X * 1.5, onScreen.getMinX(), TOLERANCE,
                "tooltip should be 21 px right of the cursor at 150%, not further");
        assertEquals(pointer.getY() + TOOLTIP_OFFSET_Y * 1.5, onScreen.getMinY(), TOLERANCE,
                "tooltip should be 15 px below the cursor at 150%, not further");
    }

    // ------------------------------------------------------------------ the drag ghost

    @Test
    @DisplayName("the drag ghost stays under the pointer at every zoom level")
    void ghostStaysUnderThePointerAtEveryZoom() {
        WaitForAsyncUtils.waitForFxEvents();

        for (int step = 0; step < UiScale.STEPS_PERCENT.length; step++) {
            setZoomTo(UiScale.STEPS_PERCENT[step]);
            String at = "at " + UiScale.STEPS_PERCENT[step] + "%";

            // Lift a card off a row near the right-hand side, where the list actually is, and
            // grab it 40 px in from its own corner.
            Point2D row = new Point2D(2000, 300);
            Point2D grab = new Point2D(row.getX() + 40, row.getY() + 12);
            interact(() -> app.dragGhost().lift("Ghost [plan]", Color.GREEN, 200,
                    row.getX(), row.getY(), grab.getX(), grab.getY()));
            WaitForAsyncUtils.waitForFxEvents();

            Bounds lifted = ghostBoundsInScene();
            assertEquals(row.getX(), lifted.getMinX(), TOLERANCE,
                    "the card should appear exactly over the row it came off " + at);
            assertEquals(row.getY(), lifted.getMinY(), TOLERANCE,
                    "the card should appear exactly over the row it came off " + at);

            // Now carry it a long way across the window — the distance is the point, because the
            // old bug's error grew with it.
            Point2D moved = new Point2D(600, 1100);
            interact(() -> app.dragGhost().moveTo(moved.getX(), moved.getY(), true));
            WaitForAsyncUtils.waitForFxEvents();

            Bounds carried = ghostBoundsInScene();
            // The card moves exactly as far as the hand did, so the point the user grabbed stays
            // under the cursor. Stated as a delta rather than an absolute position because that
            // is the property that actually matters, and it holds whatever the zoom is.
            assertEquals(row.getX() + (moved.getX() - grab.getX()), carried.getMinX(), TOLERANCE,
                    "the card should travel exactly as far as the pointer " + at);
            assertEquals(row.getY() + (moved.getY() - grab.getY()), carried.getMinY(), TOLERANCE,
                    "the card should travel exactly as far as the pointer " + at);

            interact(() -> app.dragGhost().drop());
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Walks the zoom ladder to a given percentage, the way a real Ctrl+scroll would. */
    private void setZoomTo(int percent) {
        interact(() -> {
            // Down to the floor first, so this works from wherever the last test left it.
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
        scene.getRoot().fireEvent(new ScrollEvent(ScrollEvent.SCROLL,
                0, 0, 0, 0, false, true, false, false, false, false,
                0, up ? 40 : -40, 0, up ? 40 : -40,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
    }

    private Item addBox() {
        Item box = new Item();
        box.id = "item-id-11111111-2222-4333-8444-555555555555";
        box.w_in = 24;
        box.l_in = 24;
        box.h_in = 12;
        box.x_px = 96;
        box.y_px = 96;
        box.color = "hsl(122,55%,42%)";
        box.name = "Bin";
        box.customId = "";
        interact(() -> {
            app.state().items.add(box);
            app.canvas().rebuildItems();
        });
        WaitForAsyncUtils.waitForFxEvents();
        return box;
    }

    private Bounds boxBoundsInScene() {
        return inScene(app.canvas().itemRect("item-id-11111111-2222-4333-8444-555555555555"));
    }

    private Bounds ghostBoundsInScene() {
        // The card is rotated while it is being carried, and a rotated node's scene bounds are
        // its bounding BOX, which is wider than the card and starts left of its corner. The tilt
        // is zeroed here so the bounds are the card's own edges and the numbers mean what they
        // say; the tilt itself is PlannedDragTest's business.
        interact(() -> app.dragGhost().node().setRotate(0));
        WaitForAsyncUtils.waitForFxEvents();
        return inScene(app.dragGhost().node());
    }

    /**
     * A node's own rectangle, in scene coordinates.
     *
     * <p>{@code getLayoutBounds}, not {@code getBoundsInLocal}: the latter includes the effect,
     * and the ghost card has a 16 px drop shadow that pushes its bounds 16 px out on every side.
     * Using it made this file's first run fail by exactly 8 px at 50% zoom — the shadow, scaled —
     * which looks precisely like the bug being tested and is not.
     */
    private Bounds inScene(javafx.scene.Node node) {
        return node.localToScene(node.getLayoutBounds());
    }

    /** Keeps the interface zoom from leaking into whatever runs next in the same JVM. */
    @org.junit.jupiter.api.AfterEach
    void resetZoom() {
        interact(() -> scene.getRoot().fireEvent(new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                0, 0, 0, 0, MouseButton.MIDDLE, 1,
                false, true, false, false, false, true, false, false, false, false, null)));
        WaitForAsyncUtils.waitForFxEvents();
    }
}
