package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.engine.TextFormat;
import com.modcritic.invmgr.model.Item;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The Menu window, driven through the real app on a desktop-sized window.
 *
 * <p>Everything a person does with it: open it from the bar, switch Metric from it and see
 * the switch reported, and every way of closing it, including the one that has to be checked from
 * the other side, that a click outside it closes it <em>without</em> also landing on the room.
 */
class MenuWindowAppTest extends ApplicationTest {

    private static final int REFERENCE_WIDTH = 2560;
    private static final int REFERENCE_HEIGHT = 1440;

    private App app;

    @Override
    public void start(Stage stage) {
        app = new App();
        app.start(stage);
        stage.setMaximized(false);
        stage.setWidth(REFERENCE_WIDTH);
        stage.setHeight(REFERENCE_HEIGHT);
        stage.setX(0);
        stage.setY(0);
    }

    private MenuWindow menu() {
        return app.menuWindow();
    }

    private void open() {
        clickOn(app.topBar().menuButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(menu().isShowing(), "the Menu button opens the window");
    }

    @Test
    @DisplayName("the Menu button opens a 334 px square")
    void itOpensAtItsSize() {
        open();
        Bounds panel = menu().panel().getLayoutBounds();
        assertEquals(Tokens.MENU_WINDOW_SIDE, panel.getWidth(), 0.5);
        assertEquals(Tokens.MENU_WINDOW_SIDE, panel.getHeight(), 0.5);
    }

    @Test
    @DisplayName("Metric switches the room to meters, and the entry turns green and says on")
    void metricSwitchesAndSaysSo() {
        open();
        clickOn(menu().metricEntry());
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(app.state().metricMode, "metric is on");
        assertEquals("W(m):", app.topBar().widthLabel().getText(), "and the room fields say so");
        assertEquals("on", noteOf(menu().metricEntry()).getText());
        assertTrue(menu().isShowing(), "switching stays in the window, so it can be switched back");

        // The colors are read off the screen, not out of the style string: a style that JavaFX
        // overrides reads back exactly as it was written. Sampled 4 px in from the corner, inside
        // the border and clear of the words.
        assertPixel("the Metric entry is green", corner(menu().metricEntry()),
                Tokens.TOGGLE_METRIC_BG);
        assertEquals(Tokens.TOGGLE_METRIC_TEXT, textFill(noteOf(menu().metricEntry())),
                "and so are its words");
        assertInk("the words on screen are the green text color", noteOf(menu().metricEntry()),
                Tokens.TOGGLE_METRIC_BG, Tokens.TOGGLE_METRIC_TEXT);

        clickOn(menu().metricEntry());
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(app.state().metricMode, "and off again");
        assertEquals("off", noteOf(menu().metricEntry()).getText());
        assertPixel("back to the resting gray", corner(menu().metricEntry()),
                Tokens.BUTTON_TOGGLE_BG);
    }

    @Test
    @DisplayName("Theme is gray, says coming soon, and pressing it does nothing")
    void themeIsInert() {
        open();
        clickOn(menu().themeEntry());
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(menu().isShowing(), "the window stays open");
        assertFalse(app.state().metricMode, "and nothing else changed");
        Label note = (Label) menu().themeEntry().getChildren().get(2);
        assertEquals("coming soon", note.getText());
        assertEquals(Tokens.MENU_INERT_TEXT, textFill(note));
    }

    @Test
    @DisplayName("the × closes it")
    void theCloseButtonClosesIt() {
        open();
        clickOn(menu().closeButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(menu().isShowing());
    }

    @Test
    @DisplayName("Escape closes it, and so therefore does the phone's Back button")
    void escapeClosesIt() {
        open();
        type(KeyCode.ESCAPE);
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(menu().isShowing(),
                "Escape reaches the window only if focus is inside it; see MenuWindow.onShown");
    }

    @Test
    @DisplayName("a click on the window itself, away from its buttons, leaves it open")
    void aClickInsideLeavesItOpen() {
        open();
        Bounds panel = menu().panel().localToScreen(menu().panel().getLayoutBounds());
        // The bottom of the square, below the two entries: part of the window, and nothing on it.
        clickOn(new Point2D(panel.getCenterX(), panel.getMaxY() - 10));
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(menu().isShowing());
    }

    @Test
    @DisplayName("a click outside closes it, and does not also land on the box underneath")
    void aClickOutsideClosesItAndNothingElse() {
        Item box = addItem("Crate", 24, 24, 24);
        open();

        Node boxNode = app.canvas().itemRect(box.id);
        Bounds boxOnScreen = boxNode.localToScreen(boxNode.getLayoutBounds());
        Bounds panel = menu().panel().localToScreen(menu().panel().getLayoutBounds());
        Point2D target = new Point2D(boxOnScreen.getCenterX(), boxOnScreen.getCenterY());
        assertFalse(panel.contains(target), "the box must be outside the window for this to mean anything");

        clickOn(target);
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(menu().isShowing(), "the click outside closed the Menu");
        // A desktop click on a box opens it for editing. If the click had gone through the
        // darkened room to the box as well, that dialog would be up now.
        assertFalse(app.editDialog().isShowing(), "and nothing behind it heard the click");
    }

    @Test
    @DisplayName("a drag that starts on a page and lets go outside the window is not a tap outside")
    void aDragThatEndsOutsideIsNotATap() {
        open();
        clickOn(menu().aboutEntry());
        clickOn(menu().licensesEntry());
        WaitForAsyncUtils.waitForFxEvents();
        Bounds panel = menu().panel().localToScreen(menu().panel().getLayoutBounds());

        // On the page's words, then down past the window's bottom edge, the way a finger scrolls.
        drag(new Point2D(panel.getCenterX(), panel.getMinY() + 120))
                .dropTo(new Point2D(panel.getCenterX(), panel.getMaxY() + 60));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(menu().isShowing());
        assertEquals("Licenses", menu().pageTitle().getText(), "letting go outside after a drag is no tap");
    }

    @Test
    @DisplayName("a page carries on after a flick, with the item list's coast")
    void aPageCoasts() {
        open();
        clickOn(menu().aboutEntry());
        clickOn(menu().licensesEntry());
        WaitForAsyncUtils.waitForFxEvents();
        javafx.scene.control.ScrollPane page = scrollPaneIn(menu().panel());
        interact(() -> page.setVvalue(0.1));

        // The whole flick in one pass on the JavaFX thread, as ListCoastTest does and for its reason:
        // a round trip per step would space the steps wider than the coast's sampling window. Where
        // it landed is read in the same pass: read after it, the coast may already have run to the
        // end of this short page, which it did once in a full run (1.0 against 1.0).
        double[] landedAt = new double[1];
        interact(() -> {
            javafx.event.Event.fireEvent(page, fingerScroll(javafx.scene.input.ScrollEvent.SCROLL_STARTED));
            for (int i = 0; i < 8; i++) {
                page.setVvalue(Math.min(1, page.getVvalue() + 0.01));
            }
            javafx.event.Event.fireEvent(page, fingerScroll(javafx.scene.input.ScrollEvent.SCROLL_FINISHED));
            landedAt[0] = page.getVvalue();
        });
        double landed = landedAt[0];
        assertTrue(landed < 0.5, "the flick itself should not reach the end, landed at " + landed);
        sleep(2200);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(page.getVvalue() > landed + 0.01,
                "the page should carry on past where the finger left it, went from " + landed
                        + " to " + page.getVvalue());
    }

    @Test
    @DisplayName("a press outside that drags before letting go is not a tap either")
    void aDragOutsideIsNotATap() {
        open();
        clickOn(menu().aboutEntry());
        WaitForAsyncUtils.waitForFxEvents();
        Bounds panel = menu().panel().localToScreen(menu().panel().getLayoutBounds());

        drag(new Point2D(panel.getMinX() - 60, panel.getMinY() + 40))
                .dropTo(new Point2D(panel.getMinX() - 60, panel.getMinY() + 140));
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("About", menu().pageTitle().getText());
        clickOn(new Point2D(panel.getMinX() - 60, panel.getMinY() + 40));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("Menu", menu().pageTitle().getText(), "while a tap there still goes back one page");
    }

    @Test
    @DisplayName("a desktop's Menu has no Settings: its one option only means anything on a phone")
    void noSettingsOnADesktop() {
        open();
        assertEquals(null, menu().settingsEntry());
        assertFalse(menu().pageText().contains("Settings"), menu().pageText().toString());
    }

    // ------------------------------------------------------------ Feedback

    /** The Menu, then Feedback, then OK on its warning: the message page. */
    private void openFeedback(java.util.List<String> emailed, java.util.List<String> issued) {
        interact(() -> {
            menu().setOnFeedbackByEmail(emailed::add);
            menu().setOnFeedbackByIssue(issued::add);
        });
        open();
        clickOn(menu().feedbackEntry());
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    @DisplayName("a long message in the Feedback box coasts after a flick, as the page around it does")
    void theFeedbackBoxCoasts() {
        openFeedback(new java.util.ArrayList<>(), new java.util.ArrayList<>());
        clickOn(menu().feedbackOkButton());
        WaitForAsyncUtils.waitForFxEvents();
        // Sixty lines in a box four lines tall, so the box's own scroll pane has somewhere to go.
        interact(() -> menu().feedbackText().setText("line\n".repeat(60)));
        WaitForAsyncUtils.waitForFxEvents();
        javafx.scene.control.ScrollPane box = scrollPaneIn(menu().feedbackText());
        assertNotNull(box, "the box's skin should hold a scroll pane");
        interact(() -> box.setVvalue(0.1));

        // In one pass on the JavaFX thread, as aPageCoasts does and for its reason.
        double[] landedAt = new double[1];
        interact(() -> {
            javafx.event.Event.fireEvent(box, fingerScroll(javafx.scene.input.ScrollEvent.SCROLL_STARTED));
            for (int i = 0; i < 8; i++) {
                box.setVvalue(Math.min(1, box.getVvalue() + 0.01));
            }
            javafx.event.Event.fireEvent(box, fingerScroll(javafx.scene.input.ScrollEvent.SCROLL_FINISHED));
            landedAt[0] = box.getVvalue();
        });
        double landed = landedAt[0];
        assertTrue(landed < 0.5, "the flick itself should not reach the end, landed at " + landed);
        sleep(2200);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(box.getVvalue() > landed + 0.01,
                "the message should carry on past where the finger left it, went from " + landed
                        + " to " + box.getVvalue());
    }

    @Test
    @DisplayName("a long message in the Feedback box stops at its ends rather than stretching past them")
    void theFeedbackBoxStopsAtItsEnds() {
        openFeedback(new java.util.ArrayList<>(), new java.util.ArrayList<>());
        clickOn(menu().feedbackOkButton());
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> menu().feedbackText().setText("line\n".repeat(60)));
        WaitForAsyncUtils.waitForFxEvents();
        javafx.scene.control.ScrollPane box = scrollPaneIn(menu().feedbackText());
        java.util.List<javafx.scene.control.ScrollBar> bars = new java.util.ArrayList<>();
        ScrollEnds.forEachBar(box, bars::add);
        javafx.scene.control.ScrollBar bar = bars.stream()
                .filter(found -> found.getOrientation() == javafx.geometry.Orientation.VERTICAL)
                .findFirst().orElseThrow();

        // What the phone's skin does to its bar under a finger dragging past the end, started from
        // the end itself, for ScrollEndsTest's reason: pushed from anywhere else, the desktop skin
        // clamps it by itself and the test passes without ScrollEnds.
        interact(() -> bar.setValue(bar.getMax()));
        interact(() -> bar.setValue(bar.getMax() + 0.4));
        assertEquals(bar.getMax(), bar.getValue(), 1e-9, "the message should stop at its last line");
    }

    @Test
    @DisplayName("Feedback warns first that sending needs the internet, and OK opens the message box")
    void feedbackWarnsFirst() {
        openFeedback(new java.util.ArrayList<>(), new java.util.ArrayList<>());
        assertEquals("Feedback", menu().pageTitle().getText());
        assertTrue(menu().pageText().contains(MenuWindow.FEEDBACK_NOTICE), menu().pageText().toString());
        assertTrue(menu().pageText().contains(MenuWindow.FEEDBACK_ROUTES), "and where each route goes");

        clickOn(menu().feedbackOkButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(menu().feedbackText().isFocused(), "straight to the message box");
    }

    @Test
    @DisplayName("Email and GitHub each hand the message on, and say where it went")
    void feedbackIsHandedOn() {
        java.util.List<String> emailed = new java.util.ArrayList<>();
        java.util.List<String> issued = new java.util.ArrayList<>();
        openFeedback(emailed, issued);
        clickOn(menu().feedbackOkButton());
        write("The slider sticks");

        clickOn(menu().feedbackByEmailButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(java.util.List.of("The slider sticks"), emailed);
        assertTrue(menu().feedbackResult().getText().startsWith("Opened in your mail app"));

        clickOn(menu().feedbackByIssueButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(java.util.List.of("The slider sticks"), issued, "the message is still there for it");
        assertTrue(menu().feedbackResult().getText().startsWith("Opened in your browser"));
    }

    @Test
    @DisplayName("an empty message goes nowhere, and says so")
    void anEmptyMessageStays() {
        java.util.List<String> emailed = new java.util.ArrayList<>();
        openFeedback(emailed, new java.util.ArrayList<>());
        clickOn(menu().feedbackOkButton());
        clickOn(menu().feedbackByEmailButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(emailed.isEmpty());
        assertEquals("Write something first.", menu().feedbackResult().getText());
    }

    @Test
    @DisplayName("a message stops at the length a GitHub address can carry")
    void aLongMessageIsCut() {
        openFeedback(new java.util.ArrayList<>(), new java.util.ArrayList<>());
        clickOn(menu().feedbackOkButton());
        interact(() -> menu().feedbackText().setText("x".repeat(AppInfo.FEEDBACK_MAX_LENGTH + 100)));
        assertEquals(AppInfo.FEEDBACK_MAX_LENGTH, menu().feedbackText().getLength());
    }

    @Test
    @DisplayName("a message too long for a GitHub address stays, and Email still takes it")
    void tooLongForGitHub() {
        java.util.List<String> emailed = new java.util.ArrayList<>();
        java.util.List<String> issued = new java.util.ArrayList<>();
        openFeedback(emailed, issued);
        clickOn(menu().feedbackOkButton());
        String euros = "\u20ac".repeat(AppInfo.FEEDBACK_MAX_LENGTH);
        interact(() -> menu().feedbackText().setText(euros));

        clickOn(menu().feedbackByIssueButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(issued.isEmpty(), "GitHub would refuse it, so it is not handed over");
        assertEquals(MenuWindow.TOO_LONG_FOR_GITHUB, menu().feedbackResult().getText());

        clickOn(menu().feedbackByEmailButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(java.util.List.of(euros), emailed);
    }

    @Test
    @DisplayName("a tap outside does not leave the message page, and a closed window keeps the draft")
    void aDraftIsNotLost() {
        openFeedback(new java.util.ArrayList<>(), new java.util.ArrayList<>());
        clickOn(menu().feedbackOkButton());
        write("half a thought");
        Bounds panel = menu().panel().localToScreen(menu().panel().getLayoutBounds());
        clickOn(new Point2D(panel.getMinX() - 60, panel.getMinY() - 60));
        WaitForAsyncUtils.waitForFxEvents();
        // Still on the message page, not only still open: anywhere else a tap outside is Back, which
        // from here would leave the window open on the Menu with the message page gone.
        assertTrue(menu().isShowing(), "the page with a half-written message ignores a stray tap");
        assertNotNull(menu().feedbackText().getScene(), "and it is still the message page");

        clickOn(menu().closeButton());
        WaitForAsyncUtils.waitForFxEvents();
        open();
        clickOn(menu().feedbackEntry());
        clickOn(menu().feedbackOkButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("half a thought", menu().feedbackText().getText(),
                "and closing it some other way keeps the draft for next time");
    }

    @Test
    @DisplayName("a finger swiping the Menu's entries scrolls them, and opens none (M8)")
    void aSwipeOpensNoEntry() {
        open();
        Node about = menu().aboutEntry();
        Bounds box = about.localToScene(about.getLayoutBounds());
        double x = box.getCenterX();
        double y = box.getCenterY();
        // A finger's mouse copy, made by hand; see DragCancelsTapTest for why.
        for (javafx.event.EventType<javafx.scene.input.MouseEvent> type : java.util.List.of(
                javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                javafx.scene.input.MouseEvent.MOUSE_DRAGGED,
                javafx.scene.input.MouseEvent.MOUSE_RELEASED)) {
            double at = type == javafx.scene.input.MouseEvent.MOUSE_PRESSED ? y : y - 60;
            javafx.scene.input.MouseEvent event = new javafx.scene.input.MouseEvent(type, x, at,
                    0, 0, javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false,
                    type != javafx.scene.input.MouseEvent.MOUSE_RELEASED, false, false, true,
                    false, false, null);
            interact(() -> javafx.event.Event.fireEvent(about, event));
        }
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("Menu", menu().pageTitle().getText(), "the swipe opened the entry it began on");
    }

    @Test
    @DisplayName("the Menu's pages, the Add dialog's preset strip and the item list stop at their ends")
    void theScrollersStopAtTheirEnds() {
        open();
        javafx.scene.control.ScrollPane page =
                (javafx.scene.control.ScrollPane) menu().panel().getChildren().get(1);
        interact(() -> menu().hide());
        interact(() -> app.addDialog().show());
        WaitForAsyncUtils.waitForFxEvents();
        // The strip's scroll pane is the first one above the preset slots.
        javafx.scene.Parent above = app.addDialog().presetSlots().getParent();
        while (!(above instanceof javafx.scene.control.ScrollPane)) {
            above = above.getParent();
        }
        javafx.scene.control.ScrollPane strip = (javafx.scene.control.ScrollPane) above;
        javafx.scene.control.ScrollPane list = (javafx.scene.control.ScrollPane)
                app.listPanel().lookup(".scroll-pane");
        for (javafx.scene.control.ScrollPane scroller : java.util.List.of(page, strip, list)) {
            ScrollEnds.forEachBar(scroller, bar -> {
                // From the end, not from the top: see ScrollEndsTest's class comment.
                interact(() -> bar.setValue(bar.getMax()));
                interact(() -> bar.setValue(bar.getMax() + 0.5));
                assertEquals(bar.getMax(), bar.getValue(), 1e-9,
                        "a bar pushed past its end, as the phone's skin does, comes back");
            });
        }
        interact(() -> app.addDialog().hide());
    }

    // ------------------------------------------------------------ About

    @Test
    @DisplayName("About opens in the same square, with the version the build says")
    void aboutReplacesTheMenu() {
        open();
        clickOn(menu().aboutEntry());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("About", menu().pageTitle().getText());
        Bounds panel = menu().panel().getLayoutBounds();
        assertEquals(Tokens.MENU_WINDOW_SIDE, panel.getWidth(), 0.5, "the same square");
        assertEquals(Tokens.MENU_WINDOW_SIDE, panel.getHeight(), 0.5);
        java.util.List<String> text = menu().pageText();
        assertTrue(text.contains("InvMgr " + AppInfo.VERSION), "the version: " + text);
        assertTrue(text.contains(AppInfo.DESCRIPTION), "the description: " + text);
        assertFalse(text.contains("Metric"), "the Menu's entries are gone, not underneath");
    }

    @Test
    @DisplayName("‹ goes back one page, and Escape does too until the Menu, where it closes")
    void backGoesBackOnePage() {
        open();
        clickOn(menu().aboutEntry());
        clickOn(menu().privacyEntry());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("Privacy policy", menu().pageTitle().getText());

        clickOn(menu().backButton());
        assertEquals("About", menu().pageTitle().getText(), "‹ from Privacy policy is About");

        type(KeyCode.ESCAPE);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(menu().isShowing(), "Escape on About goes back rather than closing");
        assertEquals("Menu", menu().pageTitle().getText());

        type(KeyCode.ESCAPE);
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(menu().isShowing(), "and on the Menu it closes");
    }

    @Test
    @DisplayName("closed from a page, it opens on the Menu again")
    void itAlwaysOpensOnTheMenu() {
        open();
        clickOn(menu().aboutEntry());
        clickOn(menu().closeButton());
        WaitForAsyncUtils.waitForFxEvents();
        open();
        assertEquals("Menu", menu().pageTitle().getText());
    }

    @Test
    @DisplayName("the desktop's privacy policy names the folder the autosave stays in")
    void thePrivacyPolicyPage() {
        open();
        clickOn(menu().aboutEntry());
        clickOn(menu().privacyEntry());
        WaitForAsyncUtils.waitForFxEvents();

        java.util.List<String> expected = AppInfo.privacyPolicy(false,
                com.modcritic.invmgr.persist.AppDataDir.current());
        java.util.List<String> text = menu().pageText();
        for (String paragraph : expected) {
            assertTrue(text.contains(paragraph), "missing: " + paragraph + "\nshown: " + text);
        }
    }

    @Test
    @DisplayName("Licenses shows the desktop's licenses, the source link and the written offer")
    void theLicensesPage() {
        open();
        clickOn(menu().aboutEntry());
        clickOn(menu().licensesEntry());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("Licenses", menu().pageTitle().getText());
        java.util.List<String> text = menu().pageText();
        for (String expected : java.util.List.of(
                "InvMgr's own code is 0BSD. It also contains other people's work, under the"
                        + " licenses listed below.",
                "OpenJFX is under the GNU GPL, version 2, with the Classpath Exception. Its complete"
                        + " source code for InvMgr " + AppInfo.VERSION + " is on its release page:",
                "This is a written offer, good for at least three years after " + AppInfo.VERSION
                        + " was last available: if that page is ever gone, write to"
                        + " contact@asterkazika.com for a copy.",
                "This software is based in part on the work of the FreeType Team.",
                "This software is based in part on the work of the Independent JPEG Group.")) {
            assertTrue(text.contains(expected), "missing: " + expected + "\nshown: " + text);
        }
        for (String license : java.util.List.of("GPL 2 + Classpath", "Open Font License", "MIT (Mesa)")) {
            assertNotNull(menu().licenseEntry(license), license);
        }
        assertEquals(null, menu().licenseEntry("Java class library"),
                "the desktop jar carries no Java class library of its own");
    }

    @Test
    @DisplayName("a license opens its full text, and Back returns to Licenses, then About")
    void aLicenseText() {
        open();
        clickOn(menu().aboutEntry());
        clickOn(menu().licensesEntry());
        // Below the fold, where a click would land on whatever is drawn at its coordinates, so the
        // button is pressed directly. The page's scrolling is tested on its own elsewhere.
        interact(() -> menu().licenseEntry("GPL 2 + Classpath").fire());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("GPL 2 + Classpath", menu().pageTitle().getText());
        java.util.List<String> text = menu().pageText();
        assertTrue(text.contains("Used by OpenJFX, the toolkit that draws the whole interface."), text.toString());
        assertTrue(text.contains("The GNU General Public License (GPL)"), "the license's own first line");
        assertTrue(text.stream().anyMatch(line -> line.startsWith("\"CLASSPATH\" EXCEPTION TO THE GPL")),
                "and its exception, a paragraph to itself");

        clickOn(menu().backButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("Licenses", menu().pageTitle().getText());
        push(KeyCode.ESCAPE);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("About", menu().pageTitle().getText());
    }

    @Test
    @DisplayName("the Licenses page's source link opens this version's Release page")
    void theReleaseSourceLink() {
        java.util.List<String> opened = new java.util.ArrayList<>();
        interact(() -> menu().setOnOpenLink(opened::add));
        open();
        clickOn(menu().aboutEntry());
        clickOn(menu().licensesEntry());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("Source for " + AppInfo.VERSION, labelOf(menu().releaseSourceEntry()));
        interact(() -> menu().releaseSourceEntry().fire());
        assertEquals(java.util.List.of(AppInfo.RELEASE_URL), opened);
    }

    @Test
    @DisplayName("Source code hands this version's Release page to whatever opens links")
    void sourceCodeOpensTheRelease() {
        // A stand-in for the app's own opener, which on this machine would start a real browser.
        java.util.List<String> opened = new java.util.ArrayList<>();
        interact(() -> menu().setOnOpenLink(opened::add));
        open();
        clickOn(menu().aboutEntry());
        clickOn(menu().sourceCodeEntry());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(java.util.List.of("https://github.com/ModCritic/InvMgrApp/releases/tag/v"
                + AppInfo.VERSION), opened);
    }

    @Test
    @DisplayName("the link's arrow is drawn in the math face, the only bundled face that has one")
    void theArrowHasAFace() {
        open();
        clickOn(menu().aboutEntry());
        WaitForAsyncUtils.waitForFxEvents();
        Label arrow = noteOf(menu().sourceCodeEntry());
        assertEquals("↗", arrow.getText());
        assertEquals(Fonts.SYMBOL_FAMILY, arrow.getFont().getFamily());
    }

    // -------------------------------------------------------------- helpers

    /** Adds a box through the real Add dialog, the only way the app adds one. */
    private Item addItem(String name, double w, double l, double h) {
        clickOn(app.topBar().addButtonNode());
        WaitForAsyncUtils.waitForFxEvents();
        clickOn(app.addDialog().nameField()).write(name);
        retype(app.addDialog().widthField().textField(), TextFormat.number(w));
        retype(app.addDialog().lengthField().textField(), TextFormat.number(l));
        retype(app.addDialog().heightField().textField(), TextFormat.number(h));
        clickOn(app.addDialog().confirmButton());
        WaitForAsyncUtils.waitForFxEvents();
        return app.state().items.get(app.state().items.size() - 1);
    }

    private void retype(javafx.scene.control.TextInputControl field, String text) {
        clickOn(field);
        interact(field::selectAll);
        write(text);
    }

    /** The right-hand label of an entry: "on" or "off" on Metric. */
    private static Label noteOf(javafx.scene.control.Button entry) {
        HBox inside = (HBox) entry.getGraphic();
        return (Label) inside.getChildren().get(2);
    }

    /** The first scroll pane inside {@code node}: a Menu page's body. */
    private static javafx.scene.control.ScrollPane scrollPaneIn(Node node) {
        if (node instanceof javafx.scene.control.ScrollPane found) {
            return found;
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                javafx.scene.control.ScrollPane found = scrollPaneIn(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** A scroll event as a finger's gesture sends it, marked direct. */
    private static javafx.scene.input.ScrollEvent fingerScroll(
            javafx.event.EventType<javafx.scene.input.ScrollEvent> type) {
        return new javafx.scene.input.ScrollEvent(type, 50, 50, 50, 50, false, false, false, false,
                true, false, 0, 0, 0, 0, javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0, 1, null);
    }

    /** The left-hand label of an entry: its name. */
    private static String labelOf(javafx.scene.control.Button entry) {
        HBox inside = (HBox) entry.getGraphic();
        return ((Label) inside.getChildren().get(0)).getText();
    }

    /** What color a label's words actually come out, after JavaFX has had its say. */
    private Color textFill(Label label) {
        return WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            label.applyCss();
            return (Color) label.getTextFill();
        });
    }

    /**
     * Checks the color a label's words actually came out on screen.
     *
     * <p>{@code getTextFill} answers what the property holds, and a property can hold one color while
     * the screen shows another. So this reads the pixels: of every pixel inside the label, it takes
     * the one furthest from the background, which is the middle of a stroke, and compares that to
     * the color the words should be. Anti-aliasing only ever pulls a pixel toward the background, so
     * the furthest one is the truest.
     */
    private void assertInk(String what, Label label, Color background, Color expected) {
        WaitForAsyncUtils.waitForFxEvents();
        WritableImage shot = WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> app.canvas().getScene().snapshot(null));
        Bounds box = label.localToScene(label.getLayoutBounds());
        Color furthest = background;
        double best = -1;
        for (int y = (int) box.getMinY(); y < (int) box.getMaxY(); y++) {
            for (int x = (int) box.getMinX(); x < (int) box.getMaxX(); x++) {
                Color c = shot.getPixelReader().getColor(x, y);
                double d = distance(c, background);
                if (d > best) {
                    best = d;
                    furthest = c;
                }
            }
        }
        assertTrue(distance(furthest, expected) * 255 <= 24, what + ": expected ink "
                + Tokens.hex(expected) + " but the strongest pixel is " + Tokens.hex(furthest));
    }

    private static double distance(Color a, Color b) {
        return Math.max(Math.abs(a.getRed() - b.getRed()),
                Math.max(Math.abs(a.getGreen() - b.getGreen()), Math.abs(a.getBlue() - b.getBlue())));
    }

    /** A point 4 px in from a node's top-left corner, in scene coordinates. */
    private static Point2D corner(Node node) {
        Bounds bounds = node.localToScene(node.getLayoutBounds());
        return new Point2D(bounds.getMinX() + 4, bounds.getMinY() + 4);
    }

    private void assertPixel(String what, Point2D at, Color expected) {
        WaitForAsyncUtils.waitForFxEvents();
        WritableImage shot = WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> app.canvas().getScene().snapshot(null));
        Color actual = shot.getPixelReader().getColor((int) at.getX(), (int) at.getY());
        double off = Math.max(Math.abs(actual.getRed() - expected.getRed()),
                Math.max(Math.abs(actual.getGreen() - expected.getGreen()),
                        Math.abs(actual.getBlue() - expected.getBlue())));
        assertTrue(off * 255 <= 4, what + ": expected " + Tokens.hex(expected) + " but the screen shows "
                + Tokens.hex(actual));
    }
}
