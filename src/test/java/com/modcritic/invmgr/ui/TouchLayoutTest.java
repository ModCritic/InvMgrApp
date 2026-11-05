package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.model.Preset;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Button;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The phone's layout, run on a desktop.
 *
 * <p>This is what {@code Device}'s override was built for and the first test class to use it. There
 * is no touchscreen in this container, but the <em>layout</em> owes nothing to one: it is decided
 * by a single question about the machine, and that question can be answered either way from a
 * system property. What still needs real glass is a gesture, and gestures are tested elsewhere.
 *
 * <p><b>The property is set for the class and cleared after it</b>, because the whole suite shares
 * one JVM and a leaked {@code invmgr.platform=android} would silently put every later class on the
 * phone layout, which is the kind of failure that reads as a dozen unrelated bugs.
 */
class TouchLayoutTest extends ApplicationTest {

    /** A common phone: 360 x 740 design pixels, which is the user's own. */
    private static final int PHONE_WIDTH = 360;
    private static final int PHONE_HEIGHT = 740;

    /** And a narrow one, which is where a layout tuned to a pixel width comes apart. */
    private static final int NARROW_PHONE = 320;

    private static String restorePlatform;

    private App app;
    private Stage stage;

    @BeforeAll
    static void forceThePhoneLayout() {
        restorePlatform = System.getProperty(Device.OVERRIDE_PROPERTY);
        System.setProperty(Device.OVERRIDE_PROPERTY, "android");
    }

    /**
     * The window every other class in the suite expects to find: 2560 x 1440, the reference
     * resolution the tests run at.
     *
     * <p>TestFX reuses one stage for the whole run, and this is the only class that shrinks it to a
     * phone. Leaving it that way is not a small untidiness: TestFX clicks in <em>screen</em>
     * coordinates, so every class after this one that does not pin its own window would aim at
     * controls that are off the edge of a 360 px screen, and fail on the consequence: a drag that
     * moves nothing, a dialog that never opens, a right-click that deletes nothing. Which is exactly
     * what happened the first time this class ran inside the full suite.
     */
    private static final int SHARED_STAGE_WIDTH = 2560;
    private static final int SHARED_STAGE_HEIGHT = 1440;

    private static Stage sharedStage;

    @AfterAll
    static void putEverythingBack() {
        if (restorePlatform == null) {
            System.clearProperty(Device.OVERRIDE_PROPERTY);
        } else {
            System.setProperty(Device.OVERRIDE_PROPERTY, restorePlatform);
        }
        if (sharedStage != null) {
            WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
                sharedStage.setWidth(SHARED_STAGE_WIDTH);
                sharedStage.setHeight(SHARED_STAGE_HEIGHT);
                sharedStage.setX(0);
                sharedStage.setY(0);
                return null;
            });
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    @Override
    public void start(Stage primary) {
        this.stage = primary;
        sharedStage = primary;
        app = new App();
        app.start(primary);
        // Pinned: TestFX reuses one stage for the whole run, so without this the window is
        // whatever size the previous class left it, and every measurement below is about how the
        // layout behaves at a phone's width.
        primary.setMaximized(false);
        primary.setWidth(PHONE_WIDTH);
        primary.setHeight(PHONE_HEIGHT);
        primary.setX(0);
        primary.setY(0);
    }

    /**
     * Waits for the window to actually become the size {@link #start} asked for.
     *
     * <p><b>Not done inside {@code start}, which is the trap.</b> {@code start} already runs on the
     * JavaFX thread, so waiting for the JavaFX thread from in there waits for nothing, and the
     * resize arrives on a later pulse. The first version of this class measured an island 1280 px
     * wide inside a 360 px window, and the numbers read as merely wrong rather than as untimed.
     */
    @BeforeEach
    void settle() {
        layOut();
    }

    private void layOut() {
        WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            Scene scene = stage.getScene();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    // ------------------------------------------------------------------ the island

    @Test
    @DisplayName("the island holds six buttons, in the original's order with the Menu where Units was")
    void theIslandHoldsSix() {
        TouchIsland island = app.island();
        assertNotNull(island, "a phone gets an island");
        assertEquals(6, island.getChildren().size(), "six buttons and no more");

        assertSame(island.addButton(), island.getChildren().get(0));
        assertSame(island.undoButton(), island.getChildren().get(1));
        assertSame(island.fitButton(), island.getChildren().get(2));
        assertSame(island.planButton(), island.getChildren().get(3));
        assertSame(island.menuButton(), island.getChildren().get(4));
        assertSame(island.dropdownButton(), island.getChildren().get(5));
    }

    @Test
    @DisplayName("the island's five are the bar's own buttons, not a second set")
    void theIslandBorrowsRatherThanCopies() {
        // The assertion that stops somebody "simplifying" this into the original's ten buttons.
        // The original has to declare two sets because CSS cannot move an element; JavaFX can, so
        // there is exactly one Fit button in the app and one place that knows what it does.
        assertSame(app.topBar().fitButton(), app.island().fitButton());
        assertSame(app.topBar().addButtonNode(), app.island().addButton());
        assertSame(app.topBar().undoButton(), app.island().undoButton());
        assertSame(app.topBar().planButton(), app.island().planButton());
        assertSame(app.topBar().menuButton(), app.island().menuButton());

        for (Node child : app.topBar().getChildrenUnmodifiable()) {
            assertFalse(contains(child, app.topBar().fitButton()),
                    "Fit has left the bar and lives in the island");
        }
    }

    @Test
    @DisplayName("a borrowed button still does what it did in the bar")
    void theBorrowedButtonsStillWork() {
        boolean before = app.canvas().isFitMode();
        clickOn(app.island().fitButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(!before, app.canvas().isFitMode(),
                "pressing Fit in the island toggles Fit, through the bar's own handler");
    }

    @Test
    @DisplayName("the island's type keeps its hierarchy at a real phone width")
    void theIslandTypeIsFluid() {
        double add = app.island().addButton().getFont().getSize();
        double undo = app.island().undoButton().getFont().getSize();
        double fit = app.island().fitButton().getFont().getSize();

        assertTrue(add > undo, "Add is the biggest: " + add + " vs " + undo);
        assertTrue(undo > fit, "and Fit and Plan the smallest: " + undo + " vs " + fit);

        // Bounded against the CSS, not against each other: getting the ratio right with all three
        // sizes wrong would otherwise pass.
        //
        // ⚠ These numbers are 5.9% smaller than they used to be, and the old ones were wrong. A cqi
        // is one percent of the container's CONTENT box, and this island carries 10 px of padding
        // each side, so at a 360 px screen the query width is 340. The comment that stood here did
        // its arithmetic on 360 and concluded Add sat at its 20 px ceiling; on 340 it is 19.04 and
        // reaches no ceiling at all. That single mistake is the whole of the user's "elongated
        // upward", which is why these are now computed from the rule rather than typed as literals.
        double query = PHONE_WIDTH - 2 * Tokens.ISLAND_PADDING_H;
        assertEquals(query * Tokens.ISLAND_ADD_FONT_PERCENT / 100, add, 0.01);
        assertEquals(query * Tokens.ISLAND_FONT_PERCENT / 100, undo, 0.01);
        assertEquals(query * Tokens.ISLAND_TOGGLE_FONT_PERCENT / 100, fit, 0.01);

        // And none of the three is at its ceiling here, which is the fact the old numbers hid: two
        // of them were pinned to their maximum, so a wrong query width could not show through.
        assertTrue(add < Tokens.ISLAND_ADD_FONT_MAX, "Add is below its ceiling at this width");
        assertTrue(undo < Tokens.ISLAND_FONT_MAX, "and so is Undo");
    }

    @Test
    @DisplayName("and the island's six buttons are the size the original's own screenshot shows")
    void theIslandButtonsMatchTheReference() {
        // The test that was missing, and the reason four of the ten phone bugs shipped: everything
        // here measured the island's TYPE and the ratios between its sizes, and nothing ever asked
        // how big the buttons came out. Targets are CSS pixels, pixel-scanned off the #555 borders
        // in ~/ClaudeWorkspace/mobile-touchscreen*.jpg (1080 x 2220 at scale 3, the user's own phone), and
        // two separate images of it agree to within a pixel.
        assertBox("Add", app.island().addButton(), 43.0, 40.7);
        assertBox("Undo", app.island().undoButton(), 57.0, 29.3);
        assertBox("Fit", app.island().fitButton(), 39.0, 23.7);
        assertBox("Plan", app.island().planButton(), 46.0, 23.7);
        assertBox("dropdown", app.island().dropdownButton(), 35.0, 33.3);
        // The Menu button has no reference: the original has none. It is a square exactly as tall
        // as the dropdown beside it, which the mockup the user approved showed.
        assertBox("Menu", app.island().menuButton(), 33.3, 33.3);

        // Stated separately, because every size above could drift together and keep its proportion.
        // These two are the shape the user actually reported: in the original both are slightly
        // WIDER than tall, and here both had become taller than wide.
        assertTrue(width(app.island().addButton()) > height(app.island().addButton()),
                "the Add button is wider than it is tall, as the original's is");
        assertTrue(width(app.island().dropdownButton()) >= height(app.island().dropdownButton()),
                "and the dropdown arrow is not taller than it is wide");
    }

    @Test
    @DisplayName("and the 3D button grows for a finger like the two buttons beside it")
    void theThreeDButtonIsSizedForTouch() {
        // It was missing from the bar's touch sizing loop, so it kept the desktop's 30 x 28 while
        // Save and Load on its own row grew to 16 px type: the user's "the 3D button is weirdly
        // smaller than usual". Nothing asserted anything about it at all before this.
        interact(() -> app.island().setBarOpen(true, false));
        WaitForAsyncUtils.waitForFxEvents();

        assertBox("3D", app.topBar().threeDButtonNode(), 51.0, 38.7);
        assertTrue(height(app.topBar().threeDButtonNode()) >= height(app.topBar().saveButton()),
                "the 3D button is at least as tall as Save beside it, as in the original");
    }

    @Test
    @DisplayName("a planned item dropped back on the open list drawer is canceled, not placed")
    void droppingAPlannedItemOnTheDrawerCancels() {
        String id = aPlannedItem();
        openTheListDrawer();

        // Dropped back on the drawer it came from: 40 px to the left of the row, which is still
        // inside a 200 px panel, so this is squarely "on the list" and not a near miss at its edge.
        Region row = app.listPanel().rowFor(id);
        drag(row).moveBy(-40, 0).drop();
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(app.state().items.get(0).planned,
                "dropped on the list, so the item stays planned and the row comes back");
    }

    @Test
    @DisplayName("but dropped on the room it commits, which is the half a one-sided fix would break")
    void droppingAPlannedItemOnTheRoomStillCommits() {
        // The companion, and it is the point of the pair. A predicate that simply answered "never
        // over the room" would pass the test above perfectly and break the feature outright, so the
        // other direction has to be pinned in the same breath. Bounded on both sides: the rule this
        // project keeps relearning.
        String id = aPlannedItem();
        openTheListDrawer();

        // Far to the left, clear of a drawer that is 200 px wide against the right-hand edge.
        Region row = app.listPanel().rowFor(id);
        drag(row).moveTo(new javafx.geometry.Point2D(
                app.canvas().localToScreen(app.canvas().getBoundsInLocal()).getMinX() + 40,
                row.localToScreen(row.getBoundsInLocal()).getMinY())).drop();
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(app.state().items.get(0).planned,
                "dropped on the room, so the item is committed to it");
    }

    @Test
    @DisplayName("the bar is actually part-way open part-way through the fold, not just at the end")
    void theFoldPassesThroughTheMiddle() throws Exception {
        // The test nothing has ever done, and the reason the "delayed snap" survived three builds:
        // every existing case asserts the fold through the UN-animated path, which was written as
        // "so this pins the rule without racing a 250 ms transition". It pinned the ends and never
        // looked between them, and between them was where the bug lived: the height went 0, 0, 0,
        // 116 while the property it is driven by took 168 distinct values.
        interact(() -> app.island().setBarOpen(false, false));
        WaitForAsyncUtils.waitForFxEvents();
        double folded = app.topBar().getHeight();

        interact(() -> app.island().setBarOpen(true, true));
        // ⚠ 40 ms, not 110, and the number moved for a reason worth keeping. The bar folds against a
        // max-height of 200 px while being about 136 tall, exactly as the original does, so its
        // visible movement is over well before the 250 ms animation is: full height by around
        // 100 ms. Sampling at 110 caught it at 134 of 136 and failed the "has not already finished"
        // half of the assertion. The test was right and the timing was stale.
        Thread.sleep(40);
        double midway = WaitForAsyncUtils.waitForAsyncFx(1000, () -> app.topBar().getHeight());

        WaitForAsyncUtils.sleep(400, java.util.concurrent.TimeUnit.MILLISECONDS);
        WaitForAsyncUtils.waitForFxEvents();
        double open = WaitForAsyncUtils.waitForAsyncFx(1000, () -> app.topBar().getHeight());

        assertTrue(open > folded + 20, "the bar really does open: " + folded + " -> " + open);
        // Bounded on BOTH sides, which is the whole point: "not zero" would pass on a bar that
        // snapped open on the first frame, and "not open" would pass on one that never moved.
        assertTrue(midway > folded + 5,
                "part way through the fold the bar has started opening, but was " + midway);
        assertTrue(midway < open - 5,
                "and has not already finished, but was " + midway + " against " + open);
    }

    @Test
    @DisplayName("the status bar is one height whatever it says, so nothing above it moves")
    void theStatusBarDoesNotResizeWithItsMessage() {
        // Reported by the user 2026-08-13 from the phone, and caused by the fix that made
        // the tip wrap: the instructions take two lines on a phone and a message takes one, so the
        // bar shrank on every action and grew back three seconds later. The layer-slider tab, the
        // item-list tab and, worst, the whole room under Fit all jumped each way.
        double idle = app.statusBar().getHeight();
        assertTrue(idle > 0, "the bar has a height to begin with");

        interact(() -> app.statusBar().show("Added Box"));
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("Added Box", app.statusBar().text(), "the message really is showing");
        assertEquals(idle, app.statusBar().getHeight(), 0.01,
                "and the bar is exactly as tall as it was while idle");

        // Both directions, because a bar that never changed size would also pass if it had simply
        // stopped laying out at all. Coming back to the instructions must not move it either.
        interact(() -> app.statusBar().show(StatusBar.instructions()));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(idle, app.statusBar().getHeight(), 0.01,
                "and going back to the long line does not move it either");
    }

    @Test
    @DisplayName("a sideways flick leaves the Add dialog's preset row coasting past where the finger left it")
    void aFlickLeavesThePresetRowCoasting() {
        // Forty more presets, far wider than a phone, so the row has somewhere to coast to. On the
        // desktop the dialog grows to fit them all, so only a phone-sized window can test this.
        for (int i = 0; i < 40; i++) {
            app.state().presets.add(new Preset("p" + (i % 10), 12, 12, 12));
        }
        clickOn(app.island().addButton());
        WaitForAsyncUtils.waitForFxEvents();
        ScrollPane row = enclosingScrollPane(app.addDialog().presetSlots());
        double overflow = row.getContent().getLayoutBounds().getWidth()
                - row.getViewportBounds().getWidth();
        assertTrue(overflow > 0, "the row should be wider than the dialog, by " + overflow);
        interact(() -> row.setHvalue(0));

        // The whole flick, and the reading of where it landed, in one pass on the JavaFX thread,
        // for ListCoastTest's reason: a round trip per step spaces the steps wider than the
        // coast's sampling window, and nothing would coast.
        double[] landedAt = new double[1];
        interact(() -> {
            Event.fireEvent(row, fingerScroll(ScrollEvent.SCROLL_STARTED));
            for (int i = 0; i < 8; i++) {
                row.setHvalue(Math.min(1, row.getHvalue() + 0.01));
            }
            Event.fireEvent(row, fingerScroll(ScrollEvent.SCROLL_FINISHED));
            landedAt[0] = row.getHvalue();
        });
        double landed = landedAt[0];
        assertTrue(landed < 0.5, "the flick itself should not reach the end, landed at " + landed);
        sleep(2200);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(row.getHvalue() > landed + 0.01,
                "the row should carry on past where the finger left it, went from " + landed
                        + " to " + row.getHvalue());
        assertEquals(0, row.getVvalue(), 0.0, "and it coasts sideways only");

        // And as far as the finger's speed says, which pins the conversion between hvalue and
        // pixels: with the wrong one the row still coasts, at a speed the finger never had. Steps
        // microseconds apart put the release at PanMomentum's ceiling, and from there the coast
        // runs a fixed distance until it drops under the stop speed (1236 px when measured).
        double expected = (PanMomentum.MAX_FLING_SPEED - PanMomentum.STOP_SPEED)
                / -Math.log(PanMomentum.SPEED_LEFT_AFTER_A_SECOND);
        double coasted = (row.getHvalue() - landed) * overflow;
        assertEquals(expected, coasted, expected * 0.05,
                "the coast should run the distance the flick's speed gives");
    }

    /** The scroll pane a node sits in, however deep. */
    private static ScrollPane enclosingScrollPane(Node node) {
        for (Node up = node.getParent(); up != null; up = up.getParent()) {
            if (up instanceof ScrollPane found) {
                return found;
            }
        }
        throw new AssertionError("not inside a scroll pane");
    }

    /** A scroll event as a finger's gesture sends it, marked direct. */
    private static ScrollEvent fingerScroll(EventType<ScrollEvent> type) {
        return new ScrollEvent(type, 50, 50, 50, 50, false, false, false, false,
                true, false, 0, 0, 0, 0, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, 1, null);
    }

    /** Plan mode on, one ghost added through the app's own dialog. Returns its id. */
    private String aPlannedItem() {
        clickOn(app.island().planButton());
        WaitForAsyncUtils.waitForFxEvents();
        clickOn(app.island().addButton());
        WaitForAsyncUtils.waitForFxEvents();
        clickOn(app.addDialog().confirmButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(app.state().items.get(0).planned, "the item starts out planned");
        return app.state().items.get(0).id;
    }

    private void openTheListDrawer() {
        clickOn(app.drawers().listTab());
        WaitForAsyncUtils.waitForFxEvents();
        assertSame(app.listPanel(), app.drawers().openDrawer(),
                "the list drawer is open and therefore covering the room");
    }

    private static double width(Node node) {
        return node.getBoundsInParent().getWidth();
    }

    private static double height(Node node) {
        return node.getBoundsInParent().getHeight();
    }

    /**
     * One button against the size the original renders it at, in CSS pixels.
     *
     * <p><b>The two tolerances are different on purpose, and the difference is a real residual
     * rather than a rounding allowance.</b>
     *
     * <ul>
     *   <li><b>Width, ±1.5.</b> These land exactly: Undo 57.0, Fit 39.0, Plan 46.0, Add 43.0,
     *       dropdown 35.0, every one on the nose. The slack is for the reference itself,
     *       which is measured at scale 3 and so is only good to a third of a CSS pixel.
     *   <li><b>Height, ±2.0.</b> Every height comes out <b>1.3 to 1.7 px over</b> the original's,
     *       consistently, at four different type sizes. That is not the line-box constant being
     *       wrong: a wrong constant would err in proportion to the font, and this does not; it is
     *       a fixed overshoot, which points at JavaFX snapping a control's height up rather than at
     *       the arithmetic. It is left as a known residual rather than absorbed into the constant,
     *       because tuning a measured number to cancel an unexplained one is how a fudge gets
     *       written down as a fact.
     * </ul>
     *
     * <p>Both are far tighter than the bug they exist for: before the fix the Add button was
     * 38 x 44 against 43 x 40.7, missing by five in width and flipping its proportions.
     */
    private static void assertBox(String what, Node node, double wantW, double wantH) {
        assertEquals(wantW, width(node), 1.5, what + " should be the original's width");
        assertEquals(wantH, height(node), 2.0, what + " should be the original's height");
    }

    @Test
    @DisplayName("the island sits under the bar and stays on screen when the bar folds")
    void theIslandOutlivesTheBar() {
        Bounds island = app.island().localToScene(app.island().getBoundsInLocal());

        assertTrue(island.getHeight() > 0, "the island is on screen");
        assertTrue(island.getMinY() >= 0, "and not above the top of the window");
        assertTrue(island.getMaxY() < PHONE_HEIGHT / 2.0,
                "it belongs at the top, not floating in the middle");
    }

    // ------------------------------------------------------------------ the Menu

    @Test
    @DisplayName("the island's Menu button opens a 334 px square, centered between the phone's bars")
    void theMenuWindowSitsBetweenTheBars() {
        clickOn(app.island().menuButton());
        layOut();
        MenuWindow menu = app.menuWindow();
        assertTrue(menu.isShowing(), "the island's Menu button opens it");

        // The Menu window's size on the test phone, and the one MenuWindowTest works out: half of
        // 740 less the 24 px status bar and the 48 px navigation bar.
        Bounds panel = menu.panel().localToScene(menu.panel().getLayoutBounds());
        assertEquals(334, panel.getWidth(), 0.5);
        assertEquals(334, panel.getHeight(), 0.5);

        // Centered in what the bars leave, 24 to 692, not in the whole 740. Centered in the whole
        // glass it would sit 12 px lower, and on its side it would slide up under the clock.
        assertEquals(24 + (740 - 24 - 48 - 334) / 2.0, panel.getMinY(), 0.5);
        assertEquals((360 - 334) / 2.0, panel.getMinX(), 0.5);

        WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            menu.hide();
            return null;
        });
    }

    @Test
    @DisplayName("on a phone the Menu has six entries in order, and scrolls to show the last")
    void theMenusEntriesScroll() {
        clickOn(app.island().menuButton());
        layOut();
        MenuWindow menu = app.menuWindow();
        // No touch screen on this desktop, so ScrollHint hands back the bare scroll pane; the thumb
        // itself is ScrollHintTest's and the phone's.
        javafx.scene.control.ScrollPane scroller =
                (javafx.scene.control.ScrollPane) menu.panel().getChildren().get(1);
        javafx.scene.layout.VBox entries = (javafx.scene.layout.VBox) scroller.getContent();
        assertEquals(6, entries.getChildren().size(),
                "Metric, Theme, Settings, Feedback, About, Reset Room");
        assertSame(menu.settingsEntry(), entries.getChildren().get(2));
        assertSame(menu.feedbackEntry(), entries.getChildren().get(3));
        assertSame(menu.aboutEntry(), entries.getChildren().get(4));
        assertSame(menu.resetRoomEntry(), entries.getChildren().get(5));

        // Printed for tools/android/device_suite.py, which checks the scrollbar's length and place
        // on the phone against these; see MENU_PAGE_TOP there.
        Bounds top = menu.panel().localToScene(menu.panel().getLayoutBounds());
        Bounds page = scroller.localToScene(scroller.getLayoutBounds());
        System.out.println("The Menu on a phone: entries " + entries.getLayoutBounds().getHeight()
                + " in " + scroller.getViewportBounds().getHeight() + "; the page's top is "
                + (page.getMinY() - top.getMinY()) + " below the window's top");
        // Six entries are more than a 334 px square holds under its title, so the page scrolls:
        // the user's rule for a sixth entry (2026-09-25), with ScrollHint's bar on a phone.
        assertTrue(entries.getLayoutBounds().getHeight() > scroller.getViewportBounds().getHeight(),
                "the entries should run past the square and scroll: "
                        + entries.getLayoutBounds().getHeight() + " in "
                        + scroller.getViewportBounds().getHeight());

        WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            menu.hide();
            return null;
        });
    }

    @Test
    @DisplayName("on a phone, Licenses lists the Java class library, and Back from one of its texts lands on that list")
    void theLicensesPagesOnAPhone() {
        clickOn(app.island().menuButton());
        layOut();
        MenuWindow menu = app.menuWindow();
        clickOn(menu.aboutEntry());
        clickOn(menu.licensesEntry());
        layOut();
        assertTrue(menu.pageText().contains("OpenJFX, the Java class library and the GraalVM runtime are"
                + " under the GNU GPL, version 2, with the Classpath Exception. Their complete source code"
                + " for InvMgr " + AppInfo.VERSION + " is on its release page:"), menu.pageText().toString());

        // Pressed directly: the list is below the fold on a phone.
        interact(() -> menu.licenseEntry("Java class library").fire());
        layOut();
        assertEquals("Java class library", menu.pageTitle().getText());
        interact(() -> menu.licenseEntry("ASM").fire());
        layOut();
        assertEquals("ASM", menu.pageTitle().getText());
        assertTrue(menu.pageText().stream().anyMatch(line -> line.contains("ASM")), menu.pageText().toString());

        clickOn(menu.backButton());
        layOut();
        assertEquals("Java class library", menu.pageTitle().getText(), "a text goes back to the list it was opened from");
        clickOn(menu.backButton());
        layOut();
        assertEquals("Licenses", menu.pageTitle().getText());
    }

    @Test
    @DisplayName("on a phone, About fits the square without scrolling, and the policy is the phone's")
    void theAboutPagesOnAPhone() {
        clickOn(app.island().menuButton());
        layOut();
        MenuWindow menu = app.menuWindow();
        clickOn(menu.aboutEntry());
        layOut();

        // All three entries showing at once at 16 px type, in 334 px, is the whole question: About
        // comes within a few pixels of it. The body scrolls rather than clip, so what is checked is
        // that it did not have to.
        javafx.scene.control.ScrollPane body = (javafx.scene.control.ScrollPane)
                menu.panel().getChildren().get(1);
        double content = body.getContent().getLayoutBounds().getHeight();
        double viewport = body.getViewportBounds().getHeight();
        // Printed for tools/android/device_suite.py, which taps these on the phone by their
        // distance below the window's top edge; see ABOUT_SOURCE_BELOW_TOP there.
        Bounds top = menu.panel().localToScene(menu.panel().getLayoutBounds());
        Bounds source = menu.sourceCodeEntry().localToScene(
                menu.sourceCodeEntry().getLayoutBounds());
        System.out.println("About on a phone: content " + content + " in " + viewport
                + "; Source code's middle is " + (source.getCenterY() - top.getMinY())
                + " below the window's top");
        assertTrue(content <= viewport + 0.5,
                "About needs scrolling on a 360 x 740 phone: " + content + " in " + viewport);

        clickOn(menu.privacyEntry());
        layOut();
        javafx.scene.control.ScrollPane policy = (javafx.scene.control.ScrollPane)
                menu.panel().getChildren().get(1);
        System.out.println("Privacy on a phone: content "
                + policy.getContent().getLayoutBounds().getHeight() + " in "
                + policy.getViewportBounds().getHeight());
        // The sentence about feedback made the policy longer than the page again. It now scrolls,
        // and on a phone ScrollHint draws the bar JavaFX would not, which the About window was
        // missing when it hid its last paragraph with nothing to say so. The device suite checks
        // the bar.
        assertTrue(menu.pageText().contains(
                AppInfo.privacyPolicy(true, java.nio.file.Path.of("/unused")).get(1)),
                "the phone gets the sentence about uninstalling: " + menu.pageText());

        WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            menu.hide();
            return null;
        });
    }

    @Test
    @DisplayName("on a phone, the Feedback page shows its box and both buttons without scrolling")
    void theFeedbackPageOnAPhone() {
        clickOn(app.island().menuButton());
        layOut();
        MenuWindow menu = app.menuWindow();
        clickOn(menu.feedbackEntry());
        layOut();
        javafx.scene.control.ScrollPane notice = (javafx.scene.control.ScrollPane)
                menu.panel().getChildren().get(1);
        System.out.println("Feedback's warning on a phone: content "
                + notice.getContent().getLayoutBounds().getHeight() + " in "
                + notice.getViewportBounds().getHeight());
        assertTrue(notice.getContent().getLayoutBounds().getHeight()
                        <= notice.getViewportBounds().getHeight() + 0.5,
                "the warning page needs scrolling to reach its OK");
        clickOn(menu.feedbackOkButton());
        layOut();

        javafx.scene.control.ScrollPane body = (javafx.scene.control.ScrollPane)
                menu.panel().getChildren().get(1);
        Bounds page = body.localToScene(body.getLayoutBounds());
        Bounds top = menu.panel().localToScene(menu.panel().getLayoutBounds());
        Bounds email = menu.feedbackByEmailButton().localToScene(
                menu.feedbackByEmailButton().getLayoutBounds());
        System.out.println("Feedback on a phone: content "
                + body.getContent().getLayoutBounds().getHeight() + " in "
                + body.getViewportBounds().getHeight() + "; the buttons' middle is "
                + (email.getCenterY() - top.getMinY()) + " below the window's top");
        // Sending is the page's whole job, so its buttons may not hide below the fold. The result
        // line under them may: it only appears once the message has gone to another app.
        assertTrue(email.getMaxY() <= page.getMaxY() + 0.5,
                "the Email and GitHub buttons end at " + email.getMaxY() + ", below the page's "
                        + page.getMaxY());

        WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            menu.hide();
            return null;
        });
    }

    // ------------------------------------------------------------------ the fold

    @Test
    @DisplayName("the bar starts folded away on a phone, so the room gets the glass")
    void theBarStartsFolded() {
        assertTrue(app.topBar().isCollapsed(), "the bar starts folded");
        assertEquals(0, app.topBar().getHeight(), 0.5, "and takes up no height at all");
        assertEquals(TouchIsland.SHOW_BAR_GLYPH, app.island().dropdownButton().getText(),
                "and the dropdown offers to bring it back");
    }

    @Test
    @DisplayName("the dropdown brings the bar back, and puts it away again")
    void theDropdownFolds() {
        WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            app.island().setBarOpen(true, false);
            return null;
        });
        layOut();

        assertFalse(app.topBar().isCollapsed(), "unfolded");
        assertTrue(app.topBar().getHeight() > 20,
                "and the bar has a real height: " + app.topBar().getHeight());
        assertEquals(TouchIsland.HIDE_BAR_GLYPH, app.island().dropdownButton().getText());

        WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            app.island().setBarOpen(false, false);
            return null;
        });
        layOut();

        assertTrue(app.topBar().isCollapsed(), "folded again");
        assertEquals(0, app.topBar().getHeight(), 0.5);
        assertEquals(TouchIsland.SHOW_BAR_GLYPH, app.island().dropdownButton().getText());
    }

    @Test
    @DisplayName("the fold takes the bar's padding with it, not just its height")
    void theFoldTakesThePaddingToo() {
        // Found by a surviving mutation: nothing looked at the padding, so a bar that folded its
        // height to nothing while keeping ten pixels top and bottom would have passed everything.
        //
        // It is not redundant with the height. The bar's height is driven to zero directly, so a
        // stuck padding does not change where the bar ends: it changes what the fold LOOKS like.
        // The content box is the height minus the padding, so ten fixed pixels at each end send it
        // negative almost immediately and the rows are clipped flat instead of sliding away. The
        // original animates `padding` alongside `max-height` for exactly this reason.
        //
        // Asserted through the un-animated fold rather than by sampling a frame mid-way, so this
        // pins the rule without racing a 250 ms transition.
        assertEquals(0, foldedPadding(true), 0.01, "a folded bar has no padding left");
        assertEquals(Tokens.TOP_BAR_PADDING_V_TOUCH, foldedPadding(false), 0.01,
                "and gets it all back when it comes out");
    }

    /** The bar's own top padding, folded or not. */
    private double foldedPadding(boolean collapsed) {
        return WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            app.island().setBarOpen(!collapsed, false);
            return app.topBar().getPadding().getTop();
        });
    }

    @Test
    @DisplayName("a folded bar cannot take a tap meant for what is underneath it")
    void aFoldedBarIsNotInTheWay() {
        // Invisible is not enough. JavaFX will happily deliver a press to a node of zero height
        // that is still pickable, and the thing directly under a folded bar is the room.
        assertTrue(app.topBar().isMouseTransparent(),
                "a folded bar must not be pickable");
        assertFalse(app.topBar().isVisible(), "or drawn");
    }

    // ------------------------------------------------------------------ the three rows

    @Test
    @DisplayName("the bar is three rows: W/L/H, then Set Room, then Save/Load/3D")
    void theBarHasThreeForcedRows() {
        openTheBar();

        assertEquals(3, app.topBar().getChildrenUnmodifiable().size(),
                "three rows, forced, not however many happen to fit");

        double widthRow = rowOf(app.topBar().widthField());
        double heightRow = rowOf(app.topBar().heightField());
        double setRoomRow = rowOf(app.topBar().setRoomButton());
        double saveRow = rowOf(app.topBar().saveButton());

        assertEquals(widthRow, heightRow, 1.0,
                "W and H share a row: wrapping mid-group is the thing this prevents");
        assertTrue(setRoomRow > widthRow, "Set Room is on the row below");
        assertTrue(saveRow > setRoomRow, "and Save below that");
    }

    @Test
    @DisplayName("and it is still three rows on a narrower phone")
    void theRowsSurviveANarrowPhone() {
        WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            stage.setWidth(NARROW_PHONE);
            return null;
        });
        openTheBar();

        assertEquals(3, app.topBar().getChildrenUnmodifiable().size());
        assertEquals(rowOf(app.topBar().widthField()), rowOf(app.topBar().heightField()), 1.0,
                "all three room fields still share one row at " + NARROW_PHONE + " px");

        // And they fit inside the window rather than running off the right of it, which is the
        // failure the original's fluid label sizing exists to prevent.
        Bounds height = app.topBar().heightField()
                .localToScene(app.topBar().heightField().getBoundsInLocal());
        assertTrue(height.getMaxX() <= NARROW_PHONE,
                "the last field ends at " + height.getMaxX() + ", inside " + NARROW_PHONE);

        WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            stage.setWidth(PHONE_WIDTH);
            return null;
        });
    }

    @Test
    @DisplayName("the room fields lose their steppers, which is what makes one row possible")
    void theRoomFieldsHaveNoStepper() {
        openTheBar();

        // Not a tidy-up. Four and a half characters is barely wider than a stepper block, and a
        // mobile browser draws no spinner on a number input at all, which is why the original's
        // 4.5ch is enough there. With one, the three groups need about 380 px on a 360 px screen.
        assertEquals(1, app.topBar().widthField().getChildrenUnmodifiable().size(),
                "a touch room field is the text box alone");
    }

    // ------------------------------------------------------------------ the drawers

    @Test
    @DisplayName("the room gets the whole width, because both panels are out of the layout")
    void theRoomHasTheScreenToItself() {
        // The measurement the whole drawer arrangement exists for. As columns these two take 238
        // of a 360 px screen; as overlays they take none of it until they are asked for.
        assertEquals(PHONE_WIDTH, app.canvas().getWidth(), 1.0,
                "the room is the full width of the phone");
    }

    @Test
    @DisplayName("both drawers start off their own edges, with their tabs against the frame")
    void bothDrawersStartAway() {
        assertEquals(-Tokens.SLIDER_DRAWER_WIDTH, app.sliderDrawer().getTranslateX(), 0.5,
                "the layer slider is off the left edge");
        assertEquals(Tokens.LIST_PANEL_WIDTH_TOUCH, app.listPanel().getTranslateX(), 0.5,
                "and the item list off the right");
        assertEquals(0, app.drawers().sliderTab().getTranslateX(), 0.5);
        assertEquals(0, app.drawers().listTab().getTranslateX(), 0.5);
        assertNull(app.drawers().openDrawer(), "and nothing is open");
    }

    @Test
    @DisplayName("a tab pulls its drawer out, and comes with it")
    void aTabOpensItsDrawer() {
        clickOn(app.drawers().sliderTab());
        settleTheSlide();

        assertSame(app.sliderDrawer(), app.drawers().openDrawer());
        assertEquals(0, app.sliderDrawer().getTranslateX(), 0.5, "the drawer is out");
        assertEquals(Tokens.SLIDER_DRAWER_WIDTH, app.drawers().sliderTab().getTranslateX(), 0.5,
                "and its tab has moved out with it, by the drawer's own width");

        // Open, the tab is an arrow pointing back at the edge it came from, not the drawing of
        // the thing it opens, which would then be offering to open what is already open.
        assertEquals(Tokens.DRAWER_TAB_CLOSE_LEFT, app.drawers().sliderTab().getText());
        assertNull(app.drawers().sliderTab().getGraphic(),
                "and the icon is gone, not sitting behind the arrow");
    }

    @Test
    @DisplayName("opening one drawer shuts the other")
    void onlyOneAtATime() {
        clickOn(app.drawers().sliderTab());
        settleTheSlide();
        clickOn(app.drawers().listTab());
        settleTheSlide();

        assertSame(app.listPanel(), app.drawers().openDrawer());
        assertEquals(-Tokens.SLIDER_DRAWER_WIDTH, app.sliderDrawer().getTranslateX(), 0.5,
                "the layer slider went away when the list came out");
        assertEquals(0, app.drawers().sliderTab().getTranslateX(), 0.5,
                "and took its tab back with it");
        assertNotNull(app.drawers().sliderTab().getGraphic(),
                "which is showing its icon again");
    }

    @Test
    @DisplayName("tapping the room shuts an open drawer")
    void tappingTheRoomCloses() {
        clickOn(app.drawers().sliderTab());
        settleTheSlide();
        assertNotNull(app.drawers().openDrawer());

        // On the room itself rather than on a backdrop over it: see TouchDrawers for why the
        // original ended up doing it this way too. A tap closes; a drag would not, which is what
        // lets you pan the part of the room you can still see.
        clickOn(app.canvas());
        settleTheSlide();

        assertNull(app.drawers().openDrawer(), "the drawer shut");
        assertEquals(-Tokens.SLIDER_DRAWER_WIDTH, app.sliderDrawer().getTranslateX(), 0.5);
    }

    @Test
    @DisplayName("the tabs go away in the 3D view, and come back")
    void theTabsLeaveForThreeD() {
        openTheBar();
        interact(() -> app.topBar().threeDButtonNode().fire());
        WaitForAsyncUtils.sleep(3500, java.util.concurrent.TimeUnit.MILLISECONDS);
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(app.drawers().sliderTab().isVisible(), "no tabs over the 3D room");
        assertFalse(app.drawers().listTab().isVisible());

        interact(() -> app.view3d().returnButton().fire());
        WaitForAsyncUtils.sleep(3500, java.util.concurrent.TimeUnit.MILLISECONDS);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(app.drawers().sliderTab().isVisible(), "and back afterwards");
        assertTrue(app.drawers().listTab().isVisible());
    }

    // ------------------------------------------------------------------ helpers

    /** Waits out a drawer's 0.22 s slide, with room to spare. */
    private void settleTheSlide() {
        WaitForAsyncUtils.sleep((long) Tokens.DRAWER_SLIDE_MS * 2,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void openTheBar() {
        WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            app.island().setBarOpen(true, false);
            return null;
        });
        layOut();
    }

    /** The vertical middle of a node in window coordinates: its row, in other words. */
    private static double rowOf(Node node) {
        Bounds inScene = node.localToScene(node.getBoundsInLocal());
        return (inScene.getMinY() + inScene.getMaxY()) / 2;
    }

    private static boolean contains(Node parent, Button wanted) {
        if (parent == wanted) {
            return true;
        }
        if (!(parent instanceof javafx.scene.Parent group)) {
            return false;
        }
        for (Node child : group.getChildrenUnmodifiable()) {
            if (contains(child, wanted)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("the room canvas has no scrollbars on a phone")
    void theRoomCanvasHasNoScrollbarsOnAPhone() {
        // The room's scrollbars are styled on the desktop. Since the user's decision of 2026-08-30
        // a phone gets none at all: the vertical bar is too thin to catch on a waterfall-edge
        // screen, and widening it would spend width there is not any of. Dragging and the room's
        // own coasting after a fling are what move the room instead.
        interact(() -> app.canvas().setFitMode(false));
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(ScrollPane.ScrollBarPolicy.NEVER, app.canvas().getVbarPolicy(),
                "no vertical scrollbar on a phone");
        assertEquals(ScrollPane.ScrollBarPolicy.NEVER, app.canvas().getHbarPolicy(),
                "and none horizontally either");

        // ⚠ AND IT MUST STILL SCROLL. Hiding the bar is a change to what is DRAWN and must not be
        // a change to what the room can do, or the coast has nothing to move. A policy of NEVER
        // leaves the value settable, which is what the pan and the fling both drive.
        interact(() -> app.canvas().setVvalue(0.5));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(0.5, app.canvas().getVvalue(), 0.001,
                "the room must still be scrollable with the bar hidden");
    }
}
