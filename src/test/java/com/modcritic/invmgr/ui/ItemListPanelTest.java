package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.engine.TextFormat;
import com.modcritic.invmgr.model.Item;
import javafx.geometry.Bounds;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The item list panel, driven through the real interface: buttons clicked, text typed into the
 * search box, rows double-clicked.
 *
 * <p>Everything here goes through the app rather than calling the panel directly, because most
 * of what is being checked is the <em>wiring</em> — that adding a box puts a row in the list,
 * that clicking a row selects the box in the room. A panel tested in isolation would pass all
 * of this while being connected to nothing.
 */
class ItemListPanelTest extends ApplicationTest {

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

    /**
     * Replaces a field's whole contents.
     *
     * <p>Double-clicking selects a <em>word</em>, not everything, so typing into a field that
     * already holds two words would only replace one of them.
     */
    private void retype(javafx.scene.control.TextInputControl field, String text) {
        clickOn(field);
        interact(field::selectAll);
        write(text);
    }

    /** Adds a box through the real Add dialog, which is the only way the app can add one. */
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

    // ------------------------------------------------------------ empty states

    @Test
    @DisplayName("an empty room says so, and says something different when a search matches nothing")
    void theTwoEmptyStatesReadDifferently() {
        assertEquals(0, app.listPanel().rowCount());
        assertEquals("No items yet.", emptyMessage());

        addItem("Blue Bin", 12, 12, 12);
        clickOn(app.listPanel().searchField()).write("nothing matches this");
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(0, app.listPanel().rowCount());
        assertEquals("No items match search.", emptyMessage(),
                "an empty room and a filtered-out room need different messages");
    }

    private String emptyMessage() {
        Label message = lookup(".label").queryAllAs(Label.class).stream()
                .filter(label -> label.getText().startsWith("No items"))
                .findFirst().orElse(null);
        return message == null ? null : message.getText();
    }

    // ------------------------------------------------------------------- rows

    @Test
    @DisplayName("adding a box puts a row in the list and selects it")
    void addingShowsUpInTheList() {
        Item bin = addItem("Blue Bin", 12, 24, 18);

        assertEquals(1, app.listPanel().rowCount());
        assertNotNull(app.listPanel().rowFor(bin.id));
        assertEquals(bin.id, app.canvas().selectedId(), "a new box is selected in the room");
        assertTrue(app.listPanel().isRowSelected(bin.id), "and highlighted in the list");
        assertEquals("Added Blue Bin", app.statusBar().text());
    }

    @Test
    @DisplayName("rows are in name order, with numbers read as numbers")
    void rowsSortByName() {
        addItem("Zebra", 12, 12, 12);
        addItem("", 12, 12, 12);            // becomes "item #2"
        addItem("Apple", 12, 12, 12);

        assertEquals(3, app.listPanel().rowCount());
        // Read the rows off the panel in the order they are laid out.
        assertEquals("Apple", rowTextAt(0));
        assertEquals("item #2", rowTextAt(1));
        assertEquals("Zebra", rowTextAt(2));
    }

    /**
     * The text of the nth row as it appears on screen, top to bottom.
     *
     * <p>Read by vertical position rather than by asking the panel for its order, so that the
     * test checks what is actually drawn rather than what the panel believes.
     */
    private String rowTextAt(int index) {
        java.util.List<Label> labels = new java.util.ArrayList<>();
        for (Item item : app.state().items) {
            javafx.scene.layout.Region row = app.listPanel().rowFor(item.id);
            if (row != null) {
                labels.add((Label) ((javafx.scene.layout.HBox) row).getChildren().get(1));
            }
        }
        labels.sort((a, b) -> Double.compare(a.localToScene(a.getBoundsInLocal()).getMinY(),
                b.localToScene(b.getBoundsInLocal()).getMinY()));
        return labels.get(index).getText();
    }

    @Test
    @DisplayName("a planned box appears only in the list, marked [plan]")
    void aPlannedBoxLivesOnlyInTheList() {
        clickOn(app.topBar().planButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(app.state().planMode);

        Item ghost = addItem("Ghost", 12, 12, 12);

        assertTrue(ghost.planned);
        assertNotNull(app.listPanel().rowFor(ghost.id), "the row is the only place it exists");
        assertNull(app.canvas().selectedId(), "a ghost is not selected on creation");
        assertEquals("Added Ghost (planned)", app.statusBar().text());
    }

    // ---------------------------------------------------------------- selection

    @Test
    @DisplayName("clicking a row selects the box, and double-clicking opens its dialog")
    void clickSelectsAndDoubleClickEdits() {
        Item first = addItem("Apple", 12, 12, 12);
        Item second = addItem("Zebra", 12, 12, 12);
        assertTrue(app.listPanel().isRowSelected(second.id));

        clickOn(app.listPanel().rowFor(first.id));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(first.id, app.canvas().selectedId());
        assertTrue(app.listPanel().isRowSelected(first.id));
        assertFalse(app.listPanel().isRowSelected(second.id), "only one row at a time");

        // The rule this is really guarding: the row node must survive a click, or the second
        // half of a double-click lands on a node that no longer exists and the edit never
        // opens. Rebuilding the list on selection is what used to break this.
        doubleClickOn(app.listPanel().rowFor(first.id));
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(app.editDialog().isShowing(), "double-click should open the Edit dialog");
        assertEquals(first.id, app.editDialog().editingItem().id);
    }

    @Test
    @DisplayName("selecting a row keeps the very same row node alive")
    void selectionDoesNotRebuildTheList() {
        Item bin = addItem("Blue Bin", 12, 12, 12);
        javafx.scene.layout.Region before = app.listPanel().rowFor(bin.id);

        clickOn(before);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(before == app.listPanel().rowFor(bin.id),
                "the highlight must move in place; replacing the node kills double-click");
    }

    // ------------------------------------------------------------------ search

    @Test
    @DisplayName("the search box filters by name and by size, and the cross clears it")
    void searchFiltersAndClears() {
        addItem("Blue Storage", 20, 12, 12);
        addItem("Red Crate", 24, 12, 12);

        clickOn(app.listPanel().searchField()).write("storage");
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, app.listPanel().rowCount());

        doubleClickOn(app.listPanel().searchField()).write("w24");
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, app.listPanel().rowCount(), "w24 should find the 24-wide crate");

        clickOn(app.listPanel().clearSearchButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(2, app.listPanel().rowCount());
        assertEquals("", app.listPanel().searchQuery());
    }

    @Test
    @DisplayName("switching to metric re-reads an active size search in centimetres")
    void aSizeSearchFollowsTheUnits() {
        addItem("Bin", 20, 12, 12);          // 20 inches wide

        clickOn(app.listPanel().searchField()).write("w20");
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, app.listPanel().rowCount(), "20 inches, searched in inches");

        clickOn(app.topBar().unitsButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(0, app.listPanel().rowCount(),
                "the same query now means 20 cm, and the box is 50.8 cm wide");
    }

    // ------------------------------------------------------------------ export

    @Test
    @DisplayName("exporting an empty room says so instead of writing a file")
    void exportOfNothingIsRefusedPolitely() {
        clickOn(app.listPanel().exportButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("No items to export.", app.statusBar().text());
    }

    @Test
    @DisplayName("the exported text ignores the search filter")
    void exportIgnoresTheSearch() {
        addItem("Apple", 12, 12, 12);
        addItem("Zebra", 12, 12, 12);

        clickOn(app.listPanel().searchField()).write("apple");
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, app.listPanel().rowCount(), "the list is filtered");

        // The export button lives on the panel header, not the search row, and it exports the
        // whole list on purpose. Checked against the formatter rather than by driving a file
        // chooser, which cannot be automated.
        String exported = TextFormat.exportAll(app.state());
        assertEquals(2, exported.split("\n").length, "but the export is not");
        assertTrue(exported.contains("Zebra"));
    }
}
