package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.App;
import java.io.File;
import java.io.IOException;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Checks the window's chrome (the top bar and the status bar) against the documented tokens,
 * by sampling pixels at the reference resolution.
 *
 * <p>Same approach as {@code CanvasAppearanceTest}, and for the same reason: the top bar's
 * background is {@code #252525} while the body is {@code #1a1a1a} and the room's surround is
 * {@code #181818}. Those three are indistinguishable by eye and all are on screen at once.
 */
class ChromeAppearanceTest extends ApplicationTest {

    private static final int REFERENCE_WIDTH = 2560;
    private static final int REFERENCE_HEIGHT = 1440;

    private App app;
    private Scene scene;

    @Override
    public void start(Stage stage) {
        app = new App();
        app.start(stage);
        // The app opens maximized, which does nothing under a bare X server with no window
        // manager. Sizing the stage explicitly is what makes the screenshots comparable with
        // the 2560x1440 references.
        stage.setMaximized(false);
        stage.setWidth(REFERENCE_WIDTH);
        stage.setHeight(REFERENCE_HEIGHT);
        stage.setX(0);
        stage.setY(0);
        scene = stage.getScene();
    }

    @Test
    @DisplayName("the top bar and status bar use their own backgrounds, distinct from the body")
    void chromeBackgroundsMatchTheTokens() {
        WritableImage shot = capture("m3-chrome");

        Bounds topBar = app.topBar().localToScene(app.topBar().getBoundsInLocal());
        Bounds status = app.statusBar().localToScene(app.statusBar().getBoundsInLocal());

        // A point in the top bar clear of any control: to the right of the last button.
        assertColor("top bar background (#252525)", shot,
                topBar.getMaxX() - 40, topBar.getMinY() + topBar.getHeight() / 2,
                Tokens.TOP_BAR_BG, 2);

        // The status bar shares the body's color but sits under its own separator line.
        assertColor("status bar background (#1a1a1a)", shot,
                status.getMaxX() - 40, status.getMinY() + status.getHeight() / 2,
                Tokens.BODY_BG, 2);

        // And the three grays really are different, which is the whole reason for sampling.
        assertTrue(Tokens.TOP_BAR_BG.equals(Tokens.BODY_BG) == false
                        && Tokens.BODY_BG.equals(Tokens.CANVAS_WRAP_BG) == false,
                "top bar, body and canvas surround must stay three distinct grays");
    }

    @Test
    @DisplayName("each button carries its own documented color")
    void buttonsUseTheirOwnIdentityColors() {
        // Park the pointer somewhere harmless first. Hovering a button lifts it to #484848,
        // and the robot's pointer carries over between tests: the first run of this test
        // sampled Set Room while it happened to be hovered and read the hover color.
        //
        // Worth knowing that the hover really does win, in the original too: its rule is
        // `#top-bar button:hover`, which outranks `#set-room-btn` on specificity, so hovering
        // Set Room in the original also washes out its green. Faithful, if surprising.
        moveTo(app.statusBar());
        WaitForAsyncUtils.waitForFxEvents();

        WritableImage shot = capture("m3-buttons");

        // Sampled by node bounds rather than fixed coordinates: an earlier attempt compared
        // fixed points between this window and the reference screenshot and simply landed on
        // different controls in each, since the two have different buttons.
        assertColor("Set Room (green #3a4a3a)", shot,
                centerOfX(app.topBar().setRoomButton()), centerOfY(app.topBar().setRoomButton()),
                Tokens.BUTTON_SET_ROOM_BG, 4);
        assertColor("Save (default #383838)", shot,
                centerOfX(app.topBar().saveButton()), centerOfY(app.topBar().saveButton()),
                Tokens.BUTTON_BG, 4);
        assertColor("Load (default #383838)", shot,
                centerOfX(app.topBar().loadButton()), centerOfY(app.topBar().loadButton()),
                Tokens.BUTTON_BG, 4);
        assertColor("Fit resting (#303030)", shot,
                centerOfX(app.topBar().fitButton()), centerOfY(app.topBar().fitButton()),
                Tokens.BUTTON_TOGGLE_BG, 4);
        assertColor("Units resting (#303030)", shot,
                centerOfX(app.topBar().unitsButton()), centerOfY(app.topBar().unitsButton()),
                Tokens.BUTTON_TOGGLE_BG, 4);

        // The 3D button's plain default, and THE LITERAL IS THE POINT: see CLAUDE.md §5.5 D-9.
        //
        // Every other line in this test compares a pixel against the token that painted it, so it
        // catches JavaFX overriding a color but is blind to the token itself changing: put
        // BUTTON_3D_BG back to the original's #2e3a4e and those lines would still pass, because
        // both sides move together. That is fine for a color we are copying faithfully: the
        // original is the record, and the design system holds the number.
        //
        // This one is not copied. It is a deliberate divergence, so the only record of the agreed
        // value is here, and a test that reads it out of the constant would agree with any value
        // at all. The hex is therefore written out, and reverting the token to the original's
        // blue-gray (or back to the gold that was tried and rejected on 2026-08-05) fails this
        // line, which is exactly the job. Same reasoning as D-7's hint wording, which no
        // screenshot could catch either.
        //
        // Note this now matches Save and Load, which is the divergence rather than a coincidence.
        // The sample is located through threeDButtonNode(), by identity: do NOT "simplify" it to
        // a fixed coordinate, because three buttons in this bar are now the same color and a
        // mislanded sample would pass.
        assertColor("3D button (plain default #383838, D-9)", shot,
                centerOfX(app.topBar().threeDButtonNode()),
                centerOfY(app.topBar().threeDButtonNode()),
                Color.web("#383838"), 4);

        // The room fields are #333: a different gray from both the bar behind them and the
        // dialog inputs that arrive later. Sampled at 60% across: clear of the digits on the
        // left and of the stepper on the right. Sampling 6px from the right edge used to work
        // and now reads the stepper, which is how this test noticed the stepper had arrived.
        Bounds field = app.topBar().widthField()
                .localToScene(app.topBar().widthField().getBoundsInLocal());
        assertColor("room field (#333)", shot,
                field.getMinX() + field.getWidth() * 0.6,
                field.getMinY() + field.getHeight() / 2,
                Tokens.TOP_BAR_INPUT_BG, 4);

        // And the stepper the browser draws inside a number input: a pale block on the right,
        // the same near-white as the layer slider's empty track, since both are the browser's
        // own controls rather than anything the app's stylesheet asks for.
        assertColor("number stepper block (#e9e9ed)", shot,
                field.getMaxX() - 8, field.getMinY() + 5,
                Color.rgb(233, 233, 237), 6);
    }

    @Test
    @DisplayName("the stepper nudges the value by half a foot without applying it")
    void stepperNudgesByHalfAFoot() {
        double roomBefore = app.canvas().state().room.w;

        // Click the upper half of the stepper block.
        Bounds field = app.topBar().widthField()
                .localToScene(app.topBar().widthField().getBoundsInLocal());
        clickOn(field.getMaxX() - 8 + scene.getWindow().getX(),
                field.getMinY() + 6 + scene.getWindow().getY());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("12.5", app.topBar().widthField().getText(), "half a foot up");
        // Stepping only changes the number in the box. The room is resized by Set Room, which
        // matches the original, where the fields are read on demand rather than watched.
        assertEquals(roomBefore, app.canvas().state().room.w,
                "stepping must not resize the room on its own");
    }

    @Test
    @DisplayName("the status bar starts by telling you what the mouse does")
    void statusBarShowsInstructions() {
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(StatusBar.DESKTOP_INSTRUCTIONS, app.statusBar().text());
    }

    @Test
    @DisplayName("Layer Collision lights amber when on, and reverts when off")
    void layerCollisionToggleCarriesItsOwnColor() {
        clickOn(app.topBar().layerCollisionButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(app.canvas().state().layerCollision, "the mode should be on");
        assertEquals("Layer Collision ON: items keep their current layer.",
                app.statusBar().text());

        WritableImage on = capture("m3-layer-collision-on");
        assertColor("Layer Collision active (amber #4a3620)", on,
                centerOfX(app.topBar().layerCollisionButton()),
                centerOfY(app.topBar().layerCollisionButton()),
                Tokens.TOGGLE_LAYER_BG, 6);

        clickOn(app.topBar().layerCollisionButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(!app.canvas().state().layerCollision, "the mode should be off again");
    }

    @Test
    @DisplayName("Units switches the room labels to meters and converts what they show")
    void unitsToggleSwitchesLabelsAndValues() {
        clickOn(app.topBar().unitsButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("W(m):", app.topBar().widthLabel().getText());
        // 12 ft is 3.658 m to three decimals, which is the precision the fields accept.
        assertEquals("3.658", app.topBar().widthField().getText());
        // The stored value is untouched: metric is a display conversion and nothing more.
        assertEquals(12, app.canvas().state().room.w);

        clickOn(app.topBar().unitsButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("W(ft):", app.topBar().widthLabel().getText());
        assertEquals("12", app.topBar().widthField().getText());
    }

    @Test
    @DisplayName("typing a room size resizes the room")
    void setRoomAppliesTheFields() {
        doubleClickOn(app.topBar().widthField().textField()).write("20");
        doubleClickOn(app.topBar().lengthField().textField()).write("15");
        clickOn(app.topBar().setRoomButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(20, app.canvas().state().room.w);
        assertEquals(15, app.canvas().state().room.l);
        assertEquals("Room: 20ft x 15ft x 8ft", app.statusBar().text());

        capture("m3-room-resized");
    }

    @Test
    @DisplayName("a nonsense room size is ignored rather than applied")
    void setRoomRejectsRubbish() {
        double before = app.canvas().state().room.w;
        doubleClickOn(app.topBar().widthField().textField()).write("not a number");
        clickOn(app.topBar().setRoomButton());
        WaitForAsyncUtils.waitForFxEvents();

        // Falls back to the value already in use: a stray keystroke must not resize the room.
        assertEquals(before, app.canvas().state().room.w);
    }

    @Test
    @DisplayName("an out-of-range room size is ignored too")
    void setRoomRejectsOutOfRange() {
        double before = app.canvas().state().room.w;
        doubleClickOn(app.topBar().widthField().textField()).write("9999");
        clickOn(app.topBar().setRoomButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(before, app.canvas().state().room.w,
                "200 ft is the format's maximum, so 9999 must not be accepted");
    }

    @Test
    @DisplayName("Fit scales the room and lights blue")
    void fitToggleWorks() {
        doubleClickOn(app.topBar().widthField().textField()).write("60");
        doubleClickOn(app.topBar().lengthField().textField()).write("40");
        clickOn(app.topBar().setRoomButton());
        clickOn(app.topBar().fitButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(app.canvas().isFitMode(), "Fit should be on");
        assertTrue(app.canvas().fitScaleFactor() < 1, "a 60 ft room must be scaled down");
        assertEquals("Fit mode on.", app.statusBar().text());

        WritableImage shot = capture("m3-fit-on");
        assertColor("Fit active (blue #2a4a5a)", shot,
                centerOfX(app.topBar().fitButton()), centerOfY(app.topBar().fitButton()),
                Tokens.TOGGLE_FIT_BG, 6);
    }

    @Test
    @DisplayName("the ⤓ and ■ glyphs are painted in the middle of their buttons")
    void buttonGlyphsAreCentered() {
        // Keep the pointer away: hovering changes a button's background, and the ink search takes
        // its background reading from a corner of the button it is looking at.
        moveTo(app.statusBar());
        WaitForAsyncUtils.waitForFxEvents();

        WritableImage shot = capture("m3-glyph-centering");

        // ⤓ is the one that was wrong. Drawn in Noto Sans Math, whose line box is sized for tall
        // operators, it used to be centered as a line box and so came to rest with six pixels of
        // air above it and none at all below: the bar of the arrow sat on the button's border.
        GlyphInk.assertCentered("export ⤓", shot, app.listPanel().exportButton(), 1);

        // ■ is the control, and it is why this is a font problem and not a layout one: same
        // centering code, but the text face, and it was already right. If a future change breaks
        // centering generally, this fails alongside the other one instead of leaving the cause
        // ambiguous.
        GlyphInk.assertCentered("Add ■", shot, app.topBar().addButtonNode(), 1);
    }

    @Test
    @DisplayName("the 3D button's cube icon is painted, and painted in the middle of the button")
    void theThreeDIconIsActuallyOnTheButton() {
        moveTo(app.statusBar());
        WaitForAsyncUtils.waitForFxEvents();

        WritableImage shot = capture("m5-3d-button-icon");

        // The user reported this one as "a copied Add button with no icon", and that is exactly
        // what it was: the cube was loaded, scaled and handed to the button, and then drawn
        // entirely outside it. See Icons.load for the mechanism.
        //
        // Every scene-graph number was fine (the graphic existed, its shapes had content, its
        // fill was white), so this had to be asked of the pixels, the same way M3.4's arrow did.
        GlyphInk.assertCentered("the 3D button's cube", shot, app.topBar().threeDButtonNode(), 1);

        // And it is the right size. The reference screenshot's cube covers 13 x 15 px; the icon is
        // 1.3em in a 13 px bar, so its ink is 104/128 and 120/128 of 16.9: 13.8 by 15.9, which
        // lands on 13-14 by 15-16 once anti-aliased edges are counted or not. Sizing it from the
        // Add button's 18 px instead, which is what it was, gives 14.7 by 16.9 and measures a
        // pixel wider and a pixel taller in each direction.
        GlyphInk.assertInkSize("the 3D button's cube", shot, app.topBar().threeDButtonNode(),
                14, 16, 1);
    }

    @Test
    @DisplayName("an icon measures the size it was asked for, not the size it was drawn at")
    void anIconIsAsBigAsItWasAskedToBe() {
        // The direct form of the bug above, and the cheaper test of the two: a node that reports
        // 128x128 while drawing at 18x18 is laid out as the larger, and a button that is 30 wide
        // then centers a 128-wide box by pushing it 49 px off its own left edge.
        javafx.scene.Node icon = Icons.threeD(18);
        assertEquals(18, icon.getLayoutBounds().getWidth(), 0.5,
                "the icon reports a width the layout will believe");
        assertEquals(18, icon.getLayoutBounds().getHeight(), 0.5,
                "the icon reports a height the layout will believe");
    }

    @Test
    @DisplayName("the Add button stays 30x28, and so keeps the top bar the reference's height")
    void addButtonDoesNotInflateTheTopBar() {
        assertEquals(Tokens.ADD_BUTTON_WIDTH, app.topBar().addButtonNode().getWidth(),
                "the Add button's width is pinned, not grown from the font");
        assertEquals(Tokens.ADD_BUTTON_HEIGHT, app.topBar().addButtonNode().getHeight(),
                "the Add button's height is pinned, not grown from the font");

        // The bar is as tall as its tallest child, so this is the assertion that actually
        // protects the layout: an oversized Add button moved everything below it down the window.
        // 43 is measured off the reference screenshots, where the bar's bottom border is the row
        // at y=42. It went to 46 when the bundled fonts arrived, and nothing caught it.
        //
        // Measured on the FRAME, not the bar, since M6.4. The 43 has not moved and neither have
        // the pixels: one of those pixels is the bottom border, and the border now belongs to
        // TopWrap, because on a phone the bar folds away and a border that folded with it would
        // take the line under the island with it. So the bar is 42 inside a frame of 43.
        assertEquals(43, app.topWrap().getHeight(),
                "the top bar must stay the height the reference screenshots show");
        assertEquals(1, app.topWrap().getHeight() - app.topBar().getHeight(),
                "and the one pixel between them is the bottom border, which the frame draws");

        // And it stays 43 when the room is far bigger than the window, which is not the same
        // question. M6.4 gave the bar the ability to fold, and the first version dropped its
        // minimum height to zero permanently to allow that. A VBox short of room shrinks its
        // children towards their minimums, so a 60 x 40 ft room, which is taller than any window,
        // squashed the bar to nothing and left its buttons hanging off the top of the screen. It
        // took a measurement to find because every symptom pointed elsewhere: Fit simply stopped
        // working, because the pointer was clicking where the button used to be.
        interact(() -> {
            app.canvas().state().room.w = 60;
            app.canvas().state().room.l = 40;
            app.canvas().rebuildRoom();
            app.topWrap().getParent().applyCss();
            app.topWrap().getParent().layout();
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(43, app.topWrap().getHeight(),
                "a room too big for the window must not squash the bar");

        // Every other button in the bar measures 28 too. That is what made the Add button
        // identifiable as the single cause rather than a general drift in the bar.
        assertEquals(28, app.topBar().saveButton().getHeight(),
                "the ordinary top bar buttons are 28 and should stay there");
    }

    // ------------------------------------------------------------------ helpers

    private double centerOfX(javafx.scene.Node node) {
        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        // Two pixels in from the left edge: past the border, and clear of the text in the middle.
        return bounds.getMinX() + 3;
    }

    private double centerOfY(javafx.scene.Node node) {
        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        return bounds.getMinY() + bounds.getHeight() / 2;
    }

    private WritableImage capture(String name) {
        WaitForAsyncUtils.waitForFxEvents();
        WritableImage image = WaitForAsyncUtils.waitForAsyncFx(5000, () -> scene.snapshot(null));
        try {
            File directory = new File("target/screenshots");
            if (directory.isDirectory() || directory.mkdirs()) {
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                        new File(directory, name + ".png"));
            }
        } catch (IOException e) {
            System.err.println("could not write screenshot " + name + ": " + e.getMessage());
        }
        return image;
    }

    private void assertColor(String what, WritableImage image, double x, double y,
            Color expected, int tolerance) {
        Color actual = image.getPixelReader().getColor((int) Math.round(x), (int) Math.round(y));
        int dr = Math.abs(to255(actual.getRed()) - to255(expected.getRed()));
        int dg = Math.abs(to255(actual.getGreen()) - to255(expected.getGreen()));
        int db = Math.abs(to255(actual.getBlue()) - to255(expected.getBlue()));
        assertTrue(dr <= tolerance && dg <= tolerance && db <= tolerance,
                what + " at (" + Math.round(x) + "," + Math.round(y) + "): expected rgb("
                        + to255(expected.getRed()) + "," + to255(expected.getGreen()) + ","
                        + to255(expected.getBlue()) + ") but was rgb(" + to255(actual.getRed())
                        + "," + to255(actual.getGreen()) + "," + to255(actual.getBlue()) + ")");
    }

    private static int to255(double channel) {
        return (int) Math.round(channel * 255);
    }
}
