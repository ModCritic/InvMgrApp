package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.engine.TextFormat;
import com.modcritic.invmgr.model.Item;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Reset Room through the real app: from the Menu's entry to an empty room.
 *
 * <p>What the flow's steps do is {@code ResetRoomDialogTest}'s job. This checks that the Menu hands
 * over to it, that Reset really empties the room the app is showing, and that everything that is
 * meant to stay (room size, presets, layer slider, switches) does stay. The Yes branch needs a file
 * chooser and is covered on the phone instead.
 */
class ResetRoomAppTest extends ApplicationTest {

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

    private void openResetFromTheMenu() {
        clickOn(app.topBar().menuButton());
        WaitForAsyncUtils.waitForFxEvents();
        clickOn(app.menuWindow().resetRoomEntry());
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    @DisplayName("Reset Room replaces the Menu window instead of opening on top of it")
    void itReplacesTheMenu() {
        openResetFromTheMenu();
        assertFalse(app.menuWindow().isShowing(), "the Menu closed");
        assertTrue(app.resetDialog().isShowing(), "and the Reset Room window is up in its place");
    }

    @Test
    @DisplayName("Escape closes the Reset Room window the moment it opens, room untouched")
    void escapeClosesIt() {
        // Straight after opening, with nothing in the window clicked. The Menu held focus, and it
        // has just closed, so unless the Reset Room window takes focus itself, Escape goes to
        // something behind it and never reaches the window. Same for the phone's Back button.
        addItem("Crate", 24, 24, 24);
        openResetFromTheMenu();
        type(javafx.scene.input.KeyCode.ESCAPE);
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(app.resetDialog().isShowing());
        assertEquals(1, app.state().items.size());
    }

    @Test
    @DisplayName("the Reset Room entry is red, like the app's other buttons that cannot be undone")
    void theEntryIsRed() {
        clickOn(app.topBar().menuButton());
        WaitForAsyncUtils.waitForFxEvents();
        javafx.geometry.Bounds entry = app.menuWindow().resetRoomEntry()
                .localToScene(app.menuWindow().resetRoomEntry().getLayoutBounds());
        WritableImage shot = WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> app.canvas().getScene().snapshot(null));
        Color pixel = shot.getPixelReader().getColor((int) entry.getMinX() + 4,
                (int) entry.getMinY() + 4);
        double off = Math.max(Math.abs(pixel.getRed() - Tokens.BUTTON_DANGER_BG.getRed()),
                Math.max(Math.abs(pixel.getGreen() - Tokens.BUTTON_DANGER_BG.getGreen()),
                        Math.abs(pixel.getBlue() - Tokens.BUTTON_DANGER_BG.getBlue())));
        assertTrue(off * 255 <= 4, "expected " + Tokens.hex(Tokens.BUTTON_DANGER_BG)
                + " but the screen shows " + Tokens.hex(pixel));
    }

    @Test
    @DisplayName("No, then Reset: every item goes, planned ones too, and the numbering starts over")
    void resetEmptiesTheRoom() {
        addItem("Crate", 24, 24, 24);
        clickOn(app.topBar().planButton());
        addItem("Plan me", 12, 12, 12);
        clickOn(app.topBar().planButton());
        assertEquals(2, app.state().items.size());

        openResetFromTheMenu();
        clickOn(app.resetDialog().noButton());
        clickOn(app.resetDialog().resetButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(app.state().items.isEmpty(), "the room is empty");
        assertEquals(0, app.listPanel().rowCount(), "and so is the item list, planned row and all");
        assertEquals("Room reset.", app.statusBar().text());

        // Undo has nothing to put back: the history went with the items.
        clickOn(app.topBar().undoButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(app.state().items.isEmpty(), "Undo cannot bring them back");

        Item next = addItem("", 12, 12, 12);
        assertEquals("item #1", next.displayName(), "the next unnamed box is numbered from one");
    }

    @Test
    @DisplayName("the room's size, the presets and the switches all stay as they were")
    void resetKeepsTheRest() {
        retype(app.topBar().widthField().textField(), "20");
        clickOn(app.topBar().setRoomButton());
        clickOn(app.topBar().layerCollisionButton());
        clickOn(app.topBar().planButton());
        clickOn(app.topBar().menuButton());
        clickOn(app.menuWindow().metricEntry());
        clickOn(app.menuWindow().closeButton());
        // The layer slider and a preset, set directly: dragging the slider and filling the preset
        // dialog would test those, not this. Both are moved off their defaults, or "stays where it
        // was" would pass for a reset that put them back to the defaults.
        interact(() -> {
            app.state().layerFeet = 4.5;
            app.sliderDrawer().rebuild();
            app.state().presets.set(0, new com.modcritic.invmgr.model.Preset("Bx", 10, 11, 12));
            app.addDialog().refreshPresets();
        });
        WaitForAsyncUtils.waitForFxEvents();
        double layer = app.state().layerFeet;
        double width = app.state().room.w;

        openResetFromTheMenu();
        clickOn(app.resetDialog().noButton());
        clickOn(app.resetDialog().resetButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(width, app.state().room.w, "the room keeps its size");
        assertEquals(4.5, layer, "the slider really was moved before the reset");
        assertEquals(layer, app.state().layerFeet, "the layer slider stays where it was");
        assertEquals("Bx", app.state().presets.get(0).name, "the preset stays");
        assertTrue(app.state().layerCollision, "Layer Collision stays on");
        assertTrue(app.state().planMode, "Plan stays on");
        assertTrue(app.state().metricMode, "Metric stays on");
    }

    @Test
    @DisplayName("Cancel on the last window leaves every item where it was")
    void cancelKeepsTheRoom() {
        addItem("Crate", 24, 24, 24);
        openResetFromTheMenu();
        clickOn(app.resetDialog().noButton());
        clickOn(app.resetDialog().cancelButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1, app.state().items.size());
        assertEquals(1, app.listPanel().rowCount());
    }

    // -------------------------------------------------------------- helpers

    private Item addItem(String name, double w, double l, double h) {
        clickOn(app.topBar().addButtonNode());
        WaitForAsyncUtils.waitForFxEvents();
        if (!name.isEmpty()) {
            clickOn(app.addDialog().nameField()).write(name);
        }
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
}
