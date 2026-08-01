package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.model.Item;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The three things that float over the app and do not simply ride along with the interface zoom:
 * the item tooltip, the planned-item drag ghost, and the little hints on the buttons. The first
 * two have to stay glued to the pointer at every zoom level; the third has to grow.
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
 *
 * <p>The hints are here for a related but separate reason: a JavaFX tooltip is a <b>window</b>,
 * not a control in this one, so the zoom's transform cannot reach it. It stayed its 100% size at
 * every zoom while the button under it grew — reported by the user 2026-08-01.
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

    // ------------------------------------------------------------------ the button hints

    /**
     * How far the hint's measured width may be off the size it should have grown to.
     *
     * <p>Two pixels, because character advances are rounded to whole pixels and a 12 px font
     * scaled by 1.75 does not land on one. Worst observed across the ladder is 1.5 px.
     */
    private static final double WIDTH_TOLERANCE = 2.0;

    /**
     * The same for the height, which needs a little more room.
     *
     * <p>A line box is not simply the font size — it is ascent plus descent, each rounded — so it
     * grows in whole-pixel jumps that a fractional zoom step cannot land on. Measured worst case
     * is 175%, where the hint comes up 53 px tall against the 50.75 that exact proportion asks
     * for; three pixels covers it with a little to spare.
     *
     * <p>Still far tighter than anything this is watching for. A hint that ignores the zoom
     * entirely is out by the whole difference — 29 px against 58 at the top of the ladder — and
     * one whose padding is pinned in pixels while only its text grows comes up 47 px there, out
     * by eleven.
     */
    private static final double HEIGHT_TOLERANCE = 3.0;

    @Test
    @DisplayName("a button hint grows with the interface zoom, box and all")
    void hintsGrowWithTheInterfaceZoom() {
        WaitForAsyncUtils.waitForFxEvents();

        Button units = app.topBar().unitsButton();
        Tooltip hint = units.getTooltip();

        // Measured at 100% rather than written down, because the size depends on the text and on
        // the typeface, and hard-coding it would turn a font change into a failure here.
        setZoomTo(100);
        showHint(hint, units);
        double width100 = hint.getWidth();
        double height100 = hint.getHeight();
        hideHint(hint);

        for (int percent : UiScale.STEPS_PERCENT) {
            setZoomTo(percent);
            double factor = percent / 100.0;
            showHint(hint, units);
            String at = " at " + percent + "%";

            // The font is the one property that reaches a shown popup, so it is the knob the
            // whole thing turns on -- see Hints.STYLE for why the padding cannot be.
            assertEquals(Tokens.FONT_TOOLTIP * factor, hint.getFont().getSize(), 0.001,
                    "the hint's text should be drawn at the interface size" + at);

            // And the box around the text, which is the half that silently did not follow when
            // the padding was written in pixels instead of ems.
            assertEquals(width100 * factor, hint.getWidth(), WIDTH_TOLERANCE,
                    "the hint's box should grow with its text" + at);
            assertEquals(height100 * factor, hint.getHeight(), HEIGHT_TOLERANCE,
                    "the hint's box should grow with its text" + at);

            // The hairline round the outside, which the two measurements above cannot see: it
            // contributes one pixel per side, and one pixel is inside their tolerance. Left
            // untested, a border pinned at 1 px survived every other assertion here.
            // JavaFX rounds a border width to a tenth of a pixel on the way in — 0.75 comes back
            // as 0.8 — so half a tenth is the most it can ever be out. A shade over that, because
            // 0.8 - 0.75 in binary floating point is 0.05000000000000004 and an exact 0.05 fails.
            // Still nowhere near enough slack to hide the failure this is here for: a border left
            // at a flat 1 px is out by half at the bottom of the ladder.
            assertEquals(factor, borderWidthOf(hint), 0.06,
                    "the hint's border should thicken with everything else" + at);
            hideHint(hint);
        }
    }

    @Test
    @DisplayName("a hint on a button outside the top bar scales too")
    void theExportHintScalesAsWell() {
        WaitForAsyncUtils.waitForFxEvents();

        // The scale is read off the button the hint belongs to, so a button in a different part
        // of the window is a different path through the scene graph to the same transform. If
        // that ever stops being one shared zoom, this is what notices.
        Button export = app.listPanel().exportButton();
        Tooltip hint = export.getTooltip();

        setZoomTo(150);
        showHint(hint, export);
        assertEquals(Tokens.FONT_TOOLTIP * 1.5, hint.getFont().getSize(), 0.001,
                "the export hint should be drawn at 150% like everything else at 150%");
        hideHint(hint);
    }

    @Test
    @DisplayName("a hint the POINTER brings up scales, not just one shown by hand")
    void aHoveredHintScales() {
        // This is the test that was missing, and its absence shipped the bug twice.
        //
        // The two above put the hint up by calling show(owner, x, y) -- deliberately, because
        // hovering costs a 500 ms rest on each of seven rungs. But that call is also the ONLY one
        // that records which node a popup belongs to, and JavaFX's own hover timer does not use
        // it: all four of its show calls take a Window instead. So the first fix read the owner
        // back off the tooltip, passed every assertion above, and did nothing whatsoever in the
        // app, where the owner is always null. Reported by the user against the M3.2.5 jar.
        //
        // Hence one test that pays the 500 ms and drives a real pointer. It only needs one rung:
        // what it is holding shut is the wiring, and the arithmetic is covered seven times over.
        WaitForAsyncUtils.waitForFxEvents();
        Button units = app.topBar().unitsButton();
        Tooltip hint = units.getTooltip();

        setZoomTo(200);
        // Away first, so the pointer genuinely enters the button and starts the hover timer --
        // TestFX leaves it wherever the last test put it, which may already be here.
        moveTo(app.listPanel().searchField());
        moveTo(units);
        try {
            WaitForAsyncUtils.waitFor(3, java.util.concurrent.TimeUnit.SECONDS, hint::isShowing);
        } catch (java.util.concurrent.TimeoutException e) {
            // Fall through: the assertions below say what went wrong in English.
        }

        assertTrue(hint.isShowing(), "resting on the Units button should bring its hint up");
        assertEquals(Tokens.FONT_TOOLTIP * 2, hint.getFont().getSize(), 0.001,
                "a hint the pointer brought up at 200% should be drawn at twice its size");
        interact(hint::hide);
    }

    /** Puts a hint on screen the way resting on its button would, and waits for it to settle. */
    private void showHint(Tooltip hint, Node owner) {
        // Shown directly rather than by hovering: the point here is the size it comes up at, and
        // driving the pointer would add a 500 ms rest and a race to every one of the seven rungs.
        // aHoveredHintScales pays that cost once, and is what proves this shortcut is honest.
        interact(() -> hint.show(owner, 400, 400));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void hideHint(Tooltip hint) {
        interact(hint::hide);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * The width of the hairline round a showing hint.
     *
     * <p><b>This reaches inside JavaFX's own tooltip skin</b>, which is the only way to see the
     * number: a {@code Tooltip} exposes its font and its overall size and nothing between them.
     * The skin's node is the {@code Label} that draws the hint, and its border is the one being
     * asked about. If a future JavaFX changes that shape this fails with a {@code ClassCastException}
     * rather than quietly passing, which is the right way round for a test holding a hole shut.
     */
    private double borderWidthOf(Tooltip hint) {
        javafx.scene.control.Labeled painted = (javafx.scene.control.Labeled) hint.getSkin().getNode();
        return painted.getBorder().getStrokes().get(0).getWidths().getTop();
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
        // The card is leaning while it is being carried, and a rotated node's scene bounds are its
        // bounding BOX -- wider than the card, and starting left of its corner. So this asks for
        // the rectangle the card was laid out at instead, and maps that up to the scene itself.
        //
        // It used to zero the rotation first and then measure. That no longer works and could not
        // be made to: the lean is now advanced by a running AnimationTimer (see DragGhost), which
        // would set it straight back. Reading the layout is better anyway -- it measures without
        // disturbing what it is measuring. The lean itself is TiltPendulumTest's business.
        Node card = app.dragGhost().node();
        Bounds laidOut = new javafx.geometry.BoundingBox(card.getLayoutX(), card.getLayoutY(),
                card.getBoundsInLocal().getWidth(), card.getBoundsInLocal().getHeight());
        return card.getParent().localToScene(laidOut);
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
