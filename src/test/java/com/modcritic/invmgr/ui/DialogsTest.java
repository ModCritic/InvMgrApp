package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.engine.TextFormat;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Units;
import java.io.File;
import java.io.IOException;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/** The Add, Edit and Save Preset dialogs, and the hover tooltip. */
class DialogsTest extends ApplicationTest {

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

    // --------------------------------------------------------------- Add Item

    @Test
    @DisplayName("the Add dialog opens over the app and puts a box in the room")
    void addDialogAddsABox() {
        clickOn(app.topBar().addButtonNode());
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(app.addDialog().isShowing());

        capture("m3-add-dialog");

        clickOn(app.addDialog().nameField()).write("Blue Bin");
        doubleClickOn(app.addDialog().widthField().textField()).write("24");
        clickOn(app.addDialog().confirmButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(app.addDialog().isShowing());
        assertEquals(1, app.state().items.size());
        Item added = app.state().items.get(0);
        assertEquals("Blue Bin", added.name);
        assertEquals(24, added.w_in);
        assertEquals(12, added.l_in, "the untouched fields keep their default");
    }

    @Test
    @DisplayName("Cancel and Escape both close the dialog without adding anything")
    void cancelAddsNothing() {
        clickOn(app.topBar().addButtonNode());
        clickOn(app.addDialog().nameField()).write("Never Added");
        clickOn(app.addDialog().cancelButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(app.addDialog().isShowing());
        assertTrue(app.state().items.isEmpty());

        clickOn(app.topBar().addButtonNode());
        WaitForAsyncUtils.waitForFxEvents();
        press(KeyCode.ESCAPE).release(KeyCode.ESCAPE);
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(app.addDialog().isShowing(), "Escape should close it too");
        assertTrue(app.state().items.isEmpty());
    }

    @Test
    @DisplayName("reopening Add clears the name but keeps the measurements")
    void reopeningKeepsTheMeasurements() {
        addItem("First", 24, 36, 18);

        clickOn(app.topBar().addButtonNode());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("", app.addDialog().nameField().getText(),
                "the name is per-box and always starts blank");
        // The whole point: unpacking a shelf of identical boxes should mean typing the size
        // once and then only the names.
        assertEquals("24", app.addDialog().widthField().getText());
        assertEquals("36", app.addDialog().lengthField().getText());
        assertEquals("18", app.addDialog().heightField().getText());
        clickOn(app.addDialog().cancelButton());
    }

    @Test
    @DisplayName("in metric the labels say cm and what you type is converted to inches")
    void metricEntryIsConvertedOnTheWayIn() {
        clickOn(app.topBar().unitsButton());
        WaitForAsyncUtils.waitForFxEvents();

        clickOn(app.topBar().addButtonNode());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("Width (cm):", app.addDialog().widthLabel().getText());

        doubleClickOn(app.addDialog().widthField().textField()).write("20");
        clickOn(app.addDialog().confirmButton());
        WaitForAsyncUtils.waitForFxEvents();

        // Stored in inches, always. 20 cm is 7.874016 in at the format's six decimals.
        assertEquals(Units.cmDimensionInputToInches(20), app.state().items.get(0).w_in);
    }

    @Test
    @DisplayName("the ID typed in the button row is kept on the box")
    void theIdFieldIsRead() {
        clickOn(app.topBar().addButtonNode());
        clickOn(app.addDialog().idField()).write("SKU-104");
        clickOn(app.addDialog().confirmButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("SKU-104", app.state().items.get(0).customId);
    }

    // -------------------------------------------------------------- Edit Item

    @Test
    @DisplayName("clicking a box on the canvas opens its dialog, filled in")
    void clickingABoxOpensTheEditDialog() {
        Item bin = addItem("Blue Bin", 24, 18, 12);

        clickOn(pointOn(bin));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(app.editDialog().isShowing());
        assertEquals(bin.id, app.editDialog().editingItem().id);
        assertEquals("Blue Bin", app.editDialog().nameField().getText());
        assertEquals("24", app.editDialog().widthField().getText());
        capture("m3-edit-dialog");
    }

    @Test
    @DisplayName("OK applies the changes and Cancel discards them")
    void editAppliesOrDiscards() {
        Item bin = addItem("Blue Bin", 24, 18, 12);

        clickOn(pointOn(bin));
        retype(app.editDialog().nameField(), "Discarded");
        clickOn(app.editDialog().cancelButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("Blue Bin", bin.name, "Cancel must change nothing");

        clickOn(pointOn(bin));
        retype(app.editDialog().nameField(), "Red Crate");
        retype(app.editDialog().heightField().textField(), "30");
        clickOn(app.editDialog().okButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("Red Crate", bin.name);
        assertEquals(30, bin.h_in);
        assertFalse(app.editDialog().isShowing());
    }

    @Test
    @DisplayName("the rotate button swaps width and length, and updates the two fields")
    void rotateSwapsWidthAndLength() {
        Item bin = addItem("Long Box", 12, 36, 18);

        clickOn(pointOn(bin));
        WaitForAsyncUtils.waitForFxEvents();
        clickOn(app.editDialog().swapButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(36, bin.w_in);
        assertEquals(12, bin.l_in);
        assertEquals(18, bin.h_in, "height is untouched — the box turns, it does not tip");
        assertEquals("36", app.editDialog().widthField().getText(), "the dialog keeps up");
        assertEquals("12", app.editDialog().lengthField().getText());

        // The rotation is applied immediately and is its own undoable action, so Cancel does
        // not put it back. Undo does.
        clickOn(app.editDialog().cancelButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(36, bin.w_in);
        clickOn(app.topBar().undoButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(12, bin.w_in, "Undo reverses the rotation");
    }

    @Test
    @DisplayName("Delete removes the box, and Undo brings it back")
    void deleteFromTheDialog() {
        Item bin = addItem("Blue Bin", 24, 18, 12);

        clickOn(pointOn(bin));
        WaitForAsyncUtils.waitForFxEvents();
        clickOn(app.editDialog().deleteButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(app.state().items.isEmpty());
        assertEquals("Deleted Blue Bin", app.statusBar().text());
        assertNull(app.listPanel().rowFor(bin.id));

        clickOn(app.topBar().undoButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, app.state().items.size());
        assertEquals(1, app.listPanel().rowCount(), "and the row comes back with it");
    }

    // ----------------------------------------------------------------- presets

    @Test
    @DisplayName("an empty slot opens the preset dialog, and saving fills the slot")
    void savingAPreset() {
        clickOn(app.topBar().addButtonNode());
        WaitForAsyncUtils.waitForFxEvents();

        clickOn(app.addDialog().presetSlots().slotButton(0));
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(app.presetDialog().isShowing());
        capture("m3-preset-dialog");

        clickOn(app.presetDialog().nameField()).write("SM");
        doubleClickOn(app.presetDialog().widthField().textField()).write("6");
        doubleClickOn(app.presetDialog().lengthField().textField()).write("8");
        doubleClickOn(app.presetDialog().heightField().textField()).write("10");
        clickOn(app.presetDialog().saveButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertNotNull(app.state().presets.get(0));
        assertEquals("SM", app.state().presets.get(0).name);
        assertEquals(6, app.state().presets.get(0).w_in);
        assertEquals("Preset \"SM\" saved.", app.statusBar().text());

        // And clicking the now-filled slot fills in the Add dialog's measurements.
        clickOn(app.addDialog().presetSlots().slotButton(0));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("6", app.addDialog().widthField().getText());
        assertEquals("8", app.addDialog().lengthField().getText());
        assertEquals("10", app.addDialog().heightField().getText());
        assertEquals("Preset \"SM\" applied.", app.statusBar().text());
    }

    @Test
    @DisplayName("a preset name is capped at two characters, and a blank one becomes ??")
    void presetNamesAreShort() {
        clickOn(app.topBar().addButtonNode());
        clickOn(app.addDialog().presetSlots().slotButton(0));
        clickOn(app.presetDialog().nameField()).write("TOOLONG");
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("TO", app.presetDialog().nameField().getText(),
                "the slot is 28 px wide; it holds 'SM', not 'Small'");

        clickOn(app.presetDialog().saveButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("TO", app.state().presets.get(0).name);

        clickOn(app.addDialog().presetSlots().slotButton(1));
        clickOn(app.presetDialog().saveButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("??", app.state().presets.get(1).name,
                "a nameless preset is still a usable size, so it is not rejected");
    }

    @Test
    @DisplayName("the green + adds another slot")
    void plusAddsASlot() {
        clickOn(app.topBar().addButtonNode());
        WaitForAsyncUtils.waitForFxEvents();
        int before = app.state().presets.size();

        clickOn(app.addDialog().presetSlots().addSlotButtonNode());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(before + 1, app.state().presets.size());
    }

    @Test
    @DisplayName("right-click deletes a preset, but only after asking")
    void rightClickDeletesAPresetAfterConfirming() {
        clickOn(app.topBar().addButtonNode());
        clickOn(app.addDialog().presetSlots().slotButton(0));
        clickOn(app.presetDialog().nameField()).write("SM");
        clickOn(app.presetDialog().saveButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertNotNull(app.state().presets.get(0));

        // Answer "no" first. The question itself is a real modal window in the app; the test
        // replaces how it is asked, because nothing automated can get past one that blocks.
        String[] asked = {null};
        interact(() -> app.addDialog().presetSlots().setConfirm(question -> {
            asked[0] = question;
            return false;
        }));
        rightClickOn(app.addDialog().presetSlots().slotButton(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("Delete preset \"SM\"?", asked[0]);
        assertNotNull(app.state().presets.get(0), "answering no must keep the preset");

        interact(() -> app.addDialog().presetSlots().setConfirm(question -> true));
        rightClickOn(app.addDialog().presetSlots().slotButton(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertNull(app.state().presets.get(0), "answering yes empties the slot");
        assertEquals("Preset \"SM\" deleted.", app.statusBar().text());
    }

    // ----------------------------------------------------------------- tooltip

    @Test
    @DisplayName("resting on a box shows its measurements, and leaving hides them")
    void hoverShowsTheTooltip() {
        Item bin = addItem("Blue Bin", 24, 18, 12);

        moveTo(pointOn(bin));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(app.tooltip().isShowing(), "the pointer is over the box");
        assertEquals("Blue Bin  24in W x 18in L x 12in H  base:0in", app.tooltip().text());
        capture("m3-tooltip");

        moveTo(app.statusBar());
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(app.tooltip().isShowing(), "leaving the box hides it");
    }

    @Test
    @DisplayName("the Add dialog's title is #ddd and its Presets caption is grey #777")
    void titleRowKeepsItsColours() {
        clickOn(app.topBar().addButtonNode());
        WaitForAsyncUtils.waitForFxEvents();

        // Asserted on the resolved textFill rather than by sampling pixels because the bug being
        // guarded is one of CSS PRECEDENCE, not layout. The title row's ScrollPane sets an inline
        // background, JavaFX derives a label's default colour from its background via ladder(),
        // and the derived value therefore carries INLINE origin — which outranks a colour set
        // from code with setTextFill(). Both labels silently rendered pure white.
        assertEquals(Tokens.TEXT_PRESET_LABEL, titleLabel("Presets").getTextFill(),
                "the Presets caption must stay grey, not fall back to the derived white");
        assertEquals(Tokens.TEXT_INPUT, titleLabel("Add Item").getTextFill(),
                "and the heading must keep its own colour for the same reason");
    }

    @Test
    @DisplayName("a long name stays visible — the box grows to fit it instead of hiding it")
    void aLongNameIsNeverHidden() {
        clickOn(app.topBar().addButtonNode());
        WaitForAsyncUtils.waitForFxEvents();
        clickOn(app.addDialog().nameField()).write("Winter clothes and spare blankets box");
        WaitForAsyncUtils.waitForFxEvents();

        // The invariant that matters is not "how wide is the box" but "can you SEE what you
        // typed". A TextArea keeps its text in an inner viewport and quietly clips whatever does
        // not fit; with the caret on the last line, a box left one line too short showed only the
        // final word of the name. So this asserts the viewport is tall enough for the text that
        // was actually laid out, which is exactly the thing that was false.
        NameField field = app.addDialog().nameField();

        // Compared as LINE COUNTS rather than raw pixels: the viewport sits a couple of pixels
        // under the text's nominal line height even when everything is correct, so a pixel
        // comparison fails on a perfectly good field. What actually breaks is the box being
        // sized for fewer lines than the text wrapped onto.
        javafx.scene.text.Text oneLine = new javafx.scene.text.Text("X");
        oneLine.setFont(field.getFont());
        double lineHeight = oneLine.getLayoutBounds().getHeight();

        double tallestText = field.lookupAll(".text").stream()
                .mapToDouble(node -> node.getLayoutBounds().getHeight())
                .max()
                .orElse(0);
        assertTrue(tallestText > 0, "the name should have been laid out");

        long linesOfText = Math.round(tallestText / lineHeight);
        long linesTheBoxAllows =
                Math.round((field.getHeight() - Tokens.DIALOG_INPUT_PADDING_V * 2) / 18.0);

        assertTrue(linesTheBoxAllows >= linesOfText,
                "the box must be tall enough to show every line of the name — it allows "
                        + linesTheBoxAllows + " line(s) but the name wrapped onto " + linesOfText);
    }

    @Test
    @DisplayName("Tab out of the Name box moves to Width instead of indenting the name")
    void tabLeavesTheNameBox() {
        clickOn(app.topBar().addButtonNode());
        WaitForAsyncUtils.waitForFxEvents();
        clickOn(app.addDialog().nameField()).write("Blue Bin");

        press(KeyCode.TAB).release(KeyCode.TAB);
        WaitForAsyncUtils.waitForFxEvents();

        // A JavaFX TextArea would have put a tab character in the name. A browser textarea, which
        // is what the original uses, moves to the next control — so this is the faithful result.
        assertEquals("Blue Bin", app.addDialog().nameField().getText(),
                "Tab must not become part of the name");
        assertTrue(app.addDialog().widthField().textField().isFocused(),
                "Tab should have moved focus to the Width field");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Replaces a field's whole contents.
     *
     * <p>Double-clicking selects a <em>word</em>, not everything — so typing into a name that
     * already reads "Blue Bin" would only replace one of the two. Selecting outright is the
     * only reliable way to overwrite a field.
     */
    private void retype(javafx.scene.control.TextInputControl field, String text) {
        clickOn(field);
        interact(field::selectAll);
        write(text);
    }

    /** A point in screen coordinates somewhere inside a box on the canvas. */
    private javafx.geometry.Point2D pointOn(Item item) {
        javafx.geometry.Point2D origin = app.canvas().roomOriginInScene();
        double scale = app.canvas().fitScaleFactor();
        double x = origin.getX() + (item.x_px + Units.inchesToPx(item.w_in) / 2) * scale;
        double y = origin.getY() + (item.y_px + Units.inchesToPx(item.l_in) / 2) * scale;
        return new javafx.geometry.Point2D(x + scene.getWindow().getX(),
                y + scene.getWindow().getY());
    }

    /** The Add dialog's title label whose text contains the given fragment. */
    private javafx.scene.control.Label titleLabel(String fragment) {
        return lookup(".label").queryAll().stream()
                .map(node -> (javafx.scene.control.Label) node)
                .filter(label -> label.getText() != null && label.getText().contains(fragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no label containing " + fragment));
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
}
