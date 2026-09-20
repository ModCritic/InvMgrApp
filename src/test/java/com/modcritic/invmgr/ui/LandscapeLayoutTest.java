package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.App;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The phone layout on its side, where the window is short and the top bar is not.
 *
 * <p><b>Why this exists.</b> The user turned the phone sideways and found the interface cut off
 * in two directions, with the status bar pushed off the bottom once the top bar was opened.
 * Nothing had ever looked at landscape: every reference screenshot is portrait, every touch
 * test pins a tall window, and the phone is normally held upright.
 *
 * <p>A phone on its side is 740 x 360 design pixels (the same numbers as portrait, swapped),
 * so the whole interface has to fit in <b>360</b> where it is used to 740. The open top bar is
 * most of that on its own, which is what makes this the case that breaks.
 *
 * <p>Runs here rather than only on the phone because it is a layout question and a layout
 * question can be asked of a window of any shape.
 */
class LandscapeLayoutTest extends ApplicationTest {

    /** A phone on its side, in design pixels. */
    private static final double WIDTH = 740;
    private static final double HEIGHT = 360;

    /**
     * What the shared stage has to be given back as.
     *
     * <p><b>⚠ TestFX reuses ONE stage for the whole run, and this class shrinks it to a phone on
     * its side.</b> Leaving it that way is not untidiness: TestFX clicks in <em>screen</em>
     * coordinates, so every class after this one that does not pin its own window aims at
     * controls that are off the edge of a 740 x 360 screen and fails on the consequence, which
     * looks like a drag that moves nothing or a node with no scene at all rather than like a
     * window of the wrong size.
     *
     * <p>Written into this class on 2026-09-05, after a full suite went 0 failures, then 1, then
     * 43 over four runs of identical code. {@code TouchLayoutTest} has had the same guard since
     * M6.4 and its comment says the same thing; this class was written without it.
     */
    private static final int SHARED_STAGE_WIDTH = 2560;
    private static final int SHARED_STAGE_HEIGHT = 1440;

    private App app;
    private String platformBefore;
    private static Stage sharedStage;

    @AfterEach
    void putThePlatformBack() {
        if (platformBefore == null) {
            System.clearProperty(Device.OVERRIDE_PROPERTY);
        } else {
            System.setProperty(Device.OVERRIDE_PROPERTY, platformBefore);
        }
    }

    @AfterAll
    static void putTheStageBack() {
        if (sharedStage == null) {
            return;
        }
        WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            sharedStage.setWidth(SHARED_STAGE_WIDTH);
            sharedStage.setHeight(SHARED_STAGE_HEIGHT);
            sharedStage.setX(0);
            sharedStage.setY(0);
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Override
    public void start(Stage stage) {
        // ⚠ Set here and not in a @BeforeEach. TestFX calls start() from its OWN @BeforeEach,
        // and the order between two of them is not defined; in practice this one ran second,
        // so the app was built in desktop mode and had no island at all.
        platformBefore = System.getProperty(Device.OVERRIDE_PROPERTY);
        System.setProperty(Device.OVERRIDE_PROPERTY, "android");

        sharedStage = stage;
        app = new App();
        app.start(stage);
        // Pinned, because TestFX reuses one stage for the whole run and this class is the only
        // one that wants a short wide window.
        stage.setMaximized(false);
        stage.setWidth(WIDTH);
        stage.setHeight(HEIGHT);
        stage.show();
    }

    /** Where a node sits in the window. */
    private Bounds inScene(Node node) {
        return node.localToScene(node.getBoundsInLocal());
    }

    private void openTheTopBar() {
        interact(() -> app.island().setBarOpen(true, false));
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    @DisplayName("with the top bar open, the status bar is still on screen")
    void statusBarSurvivesAnOpenTopBar() {
        openTheTopBar();
        double height = app.statusBar().getScene().getHeight();
        Bounds status = inScene(app.statusBar());
        assertTrue(status.getMaxY() <= height + 1,
                "the status bar runs to y=" + status.getMaxY() + " in a window only "
                        + height + " tall, so opening the top bar pushed it off the bottom");
        assertTrue(status.getMinY() >= 0,
                "the status bar starts at y=" + status.getMinY() + ", above the top of the window");
    }

    @Test
    @DisplayName("with the top bar open, none of it is above the top of the window")
    void theTopBarIsNotClippedAtTheTop() {
        openTheTopBar();
        Bounds bar = inScene(app.topWrap());
        assertTrue(bar.getMinY() >= -1,
                "the top bar starts at y=" + bar.getMinY() + ", so its first row, the room's"
                        + " W, L and H boxes, is drawn off the top of the window");
    }

    @Test
    @DisplayName("the room keeps some height even with the top bar open")
    void theRoomIsNotSqueezedToNothing() {
        openTheTopBar();
        Bounds bar = inScene(app.topWrap());
        Bounds status = inScene(app.statusBar());
        double left = status.getMinY() - bar.getMaxY();
        assertTrue(left > 20,
                "only " + left + " pixels are left for the room between the top bar and the"
                        + " status bar, which is not a usable room");
    }

    @Test
    @DisplayName("folded, everything fits with room to spare")
    void foldedIsFine() {
        interact(() -> app.island().setBarOpen(false, false));
        WaitForAsyncUtils.waitForFxEvents();
        double height = app.statusBar().getScene().getHeight();
        Bounds status = inScene(app.statusBar());
        assertTrue(status.getMaxY() <= height + 1,
                "even folded, the status bar runs past the bottom of the window");
    }

    // ------------------------------------------------------ the island, and what covers it

    @Test
    @DisplayName("with the top bar open, the island's buttons still take a tap")
    void theIslandStillTakesATapWithTheBarOpen() {
        openTheTopBar();
        clickOn(app.island().addButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(app.addDialog().isShowing(),
                "tapping the island's Add button with the top bar open did nothing: the tap"
                        + " landed on whatever is floating over the island rather than on the"
                        + " button. The island is at " + inScene(app.island()) + " and the room's"
                        + " container reaches " + inScene(app.drawers()));
    }

    @Test
    @DisplayName("the room's container keeps everything inside its own box")
    void theRoomDoesNotSpillOverTheIsland() {
        openTheTopBar();
        TouchDrawers room = app.drawers();
        // In the PARENT's coordinates, which is the only space where the layout box and what
        // the node actually covers can be compared without an offset creeping in between them.
        Bounds spill = room.getBoundsInParent();
        double top = room.getLayoutY();
        double bottom = top + room.getHeight();
        assertTrue(spill.getMinY() >= top - 1 && spill.getMaxY() <= bottom + 1,
                "the room's container is laid out from y=" + top + " to y=" + bottom
                        + " but reaches from " + spill.getMinY() + " to " + spill.getMaxY()
                        + ". Whatever is hanging out of it is drawn over the island above and"
                        + " the status bar below, and takes their taps as well.");
    }

    @Test
    @DisplayName("neither drawer insists on being taller than the room")
    void theDrawersAreTheHeightOfTheRoom() {
        openTheTopBar();
        double room = app.drawers().getHeight();
        assertTrue(app.sliderDrawer().getHeight() <= room + 1,
                "the layer drawer is " + app.sliderDrawer().getHeight() + " tall in a room only "
                        + room + " tall, so the StackPane centered it and it hangs off both ends");
        assertTrue(app.listPanel().getHeight() <= room + 1,
                "the item list is " + app.listPanel().getHeight() + " tall in a room only "
                        + room + " tall, so the StackPane centered it and it hangs off both ends");
    }
}
