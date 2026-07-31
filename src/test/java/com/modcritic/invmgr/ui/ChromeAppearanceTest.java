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
 * Checks the window's chrome — the top bar and the status bar — against the documented tokens,
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

        // The status bar shares the body's colour but sits under its own separator line.
        assertColor("status bar background (#1a1a1a)", shot,
                status.getMaxX() - 40, status.getMinY() + status.getHeight() / 2,
                Tokens.BODY_BG, 2);

        // And the three greys really are different, which is the whole reason for sampling.
        assertTrue(Tokens.TOP_BAR_BG.equals(Tokens.BODY_BG) == false
                        && Tokens.BODY_BG.equals(Tokens.CANVAS_WRAP_BG) == false,
                "top bar, body and canvas surround must stay three distinct greys");
    }

    @Test
    @DisplayName("each button carries its own documented colour")
    void buttonsUseTheirOwnIdentityColours() {
        // Park the pointer somewhere harmless first. Hovering a button lifts it to #484848,
        // and the robot's pointer carries over between tests — the first run of this test
        // sampled Set Room while it happened to be hovered and read the hover colour.
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
                centreOfX(app.topBar().setRoomButton()), centreOfY(app.topBar().setRoomButton()),
                Tokens.BUTTON_SET_ROOM_BG, 4);
        assertColor("Save (default #383838)", shot,
                centreOfX(app.topBar().saveButton()), centreOfY(app.topBar().saveButton()),
                Tokens.BUTTON_BG, 4);
        assertColor("Load (default #383838)", shot,
                centreOfX(app.topBar().loadButton()), centreOfY(app.topBar().loadButton()),
                Tokens.BUTTON_BG, 4);
        assertColor("Fit resting (#303030)", shot,
                centreOfX(app.topBar().fitButton()), centreOfY(app.topBar().fitButton()),
                Tokens.BUTTON_TOGGLE_BG, 4);
        assertColor("Units resting (#303030)", shot,
                centreOfX(app.topBar().unitsButton()), centreOfY(app.topBar().unitsButton()),
                Tokens.BUTTON_TOGGLE_BG, 4);

        // The room fields are #333 — a different grey from both the bar behind them and the
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
    void layerCollisionToggleCarriesItsOwnColour() {
        clickOn(app.topBar().layerCollisionButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(app.canvas().state().layerCollision, "the mode should be on");
        assertEquals("Layer Collision ON — items keep their current layer.",
                app.statusBar().text());

        WritableImage on = capture("m3-layer-collision-on");
        assertColor("Layer Collision active (amber #4a3620)", on,
                centreOfX(app.topBar().layerCollisionButton()),
                centreOfY(app.topBar().layerCollisionButton()),
                Tokens.TOGGLE_LAYER_BG, 6);

        clickOn(app.topBar().layerCollisionButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(!app.canvas().state().layerCollision, "the mode should be off again");
    }

    @Test
    @DisplayName("Units switches the room labels to metres and converts what they show")
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

        // Falls back to the value already in use — a stray keystroke must not resize the room.
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
                centreOfX(app.topBar().fitButton()), centreOfY(app.topBar().fitButton()),
                Tokens.TOGGLE_FIT_BG, 6);
    }

    // ------------------------------------------------------------------ helpers

    private double centreOfX(javafx.scene.Node node) {
        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        // Two pixels in from the left edge: past the border, and clear of the text in the middle.
        return bounds.getMinX() + 3;
    }

    private double centreOfY(javafx.scene.Node node) {
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
