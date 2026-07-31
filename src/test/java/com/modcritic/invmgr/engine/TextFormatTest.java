package com.modcritic.invmgr.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two text formats and the name ordering, pinned against the strings in
 * SPEC-2D-ENGINE.md §9 — which were read out of the original app.
 */
class TextFormatTest {

    private static Item item(String name, double w, double l, double h, double base) {
        Item item = new Item();
        item.id = "item-id-" + name;
        item.serial = 1;
        item.w_in = w;
        item.l_in = l;
        item.h_in = h;
        item.baseHeight_in = base;
        item.name = name;
        item.customId = "";
        item.color = "hsl(200,55%,42%)";
        return item;
    }

    // ------------------------------------------------------------------ numbers

    @Test
    void printsNumbersTheWayTheOriginalDoes() {
        assertEquals("12", TextFormat.number(12.0), "a whole number keeps no .0");
        assertEquals("30.48", TextFormat.number(30.48));
        assertEquals("0", TextFormat.number(0.0));
        assertEquals("0.5", TextFormat.number(0.5));
        // Double.toString would give 1000.0 and, for bigger values, an exponent.
        assertEquals("1000", TextFormat.number(1000.0));
    }

    // ----------------------------------------------------------------- tooltip

    @Test
    void tooltipMatchesTheSpecExample() {
        AppState state = new AppState();
        assertEquals("Blue Bin  12in W x 24in L x 18in H  base:0in",
                TextFormat.tooltipText(state, item("Blue Bin", 12, 24, 18, 0)));
    }

    @Test
    void tooltipShowsCentimetresInMetric() {
        AppState state = new AppState();
        state.metricMode = true;
        assertEquals("Blue Bin  30.48cm W x 60.96cm L x 45.72cm H  base:0cm",
                TextFormat.tooltipText(state, item("Blue Bin", 12, 24, 18, 0)));
    }

    @Test
    void tooltipAlwaysShowsBaseHeightEvenWhenZero() {
        AppState state = new AppState();
        assertTrue(TextFormat.tooltipText(state, item("A", 1, 1, 1, 0)).endsWith("base:0in"));
    }

    @Test
    void tooltipUsesTheDefaultNameWhenThereIsNoName() {
        AppState state = new AppState();
        Item unnamed = item("", 12, 12, 12, 0);
        unnamed.serial = 4;
        assertTrue(TextFormat.tooltipText(state, unnamed).startsWith("item #4  "));
    }

    // ------------------------------------------------------------------ export

    @Test
    void exportLineMatchesTheSpecExample() {
        AppState state = new AppState();
        Item bin = item("Blue Bin", 12, 24, 18, 0);
        bin.customId = "SKU-104";
        assertEquals("Blue Bin | 12in W x 24in L x 18in H - Base: 0in | SKU-104",
                TextFormat.exportLine(state, bin));
    }

    @Test
    void exportLineMarksPlannedItemsAndOmitsAnEmptyId() {
        AppState state = new AppState();
        state.metricMode = true;
        Item ghost = item("Ghost Box", 12, 24, 18, 0);
        ghost.planned = true;
        assertEquals("Ghost Box [plan] | 30.48cm W x 60.96cm L x 45.72cm H - Base: 0cm",
                TextFormat.exportLine(state, ghost));
    }

    @Test
    void theTwoFormattersDisagree() {
        // Not a curiosity -- it is the reason they are separate functions, and a change that
        // "tidied" one into the other would silently alter a user-facing file format.
        AppState state = new AppState();
        Item bin = item("Bin", 12, 12, 12, 0);
        assertTrue(!TextFormat.tooltipText(state, bin).equals(TextFormat.exportLine(state, bin)));
    }

    @Test
    void exportEndsWithANewlineAndIgnoresNothing() {
        AppState state = new AppState();
        state.items.add(item("Zebra", 12, 12, 12, 0));
        state.items.add(item("Apple", 12, 12, 12, 0));

        String text = TextFormat.exportAll(state);
        assertTrue(text.endsWith("\n"), "a text file should end with a newline");
        assertEquals(2, text.split("\n").length);
        assertTrue(text.indexOf("Apple") < text.indexOf("Zebra"), "sorted by name");
    }

    @Test
    void exportOfAnEmptyRoomIsEmpty() {
        assertEquals("", TextFormat.exportAll(new AppState()));
    }

    // ------------------------------------------------------------------- order

    @Test
    void numbersInNamesSortAsNumbers() {
        // The whole reason the comparator is hand-written: plain text ordering puts "#10"
        // before "#2", because it compares "1" against "2" one character at a time.
        assertTrue(TextFormat.compareNames("item #2", "item #10") < 0);
        assertTrue(TextFormat.compareNames("item #10", "item #9") > 0);
        assertTrue(TextFormat.compareNames("box 2 a", "box 2 b") < 0);
    }

    @Test
    void capitalsDoNotSortSeparately() {
        assertEquals(0, TextFormat.compareNames("bin", "BIN"));
        assertTrue(TextFormat.compareNames("apple", "Banana") < 0,
                "'apple' before 'Banana' -- capitals must not all come first");
    }

    @Test
    void aShorterNameComesFirstWhenItIsAPrefix() {
        assertTrue(TextFormat.compareNames("bin", "bin 2") < 0);
    }

    @Test
    void theListOrderUsesTheSameRule() {
        List<Item> items = new ArrayList<>(List.of(
                item("item #10", 1, 1, 1, 0),
                item("item #2", 1, 1, 1, 0),
                item("Apple", 1, 1, 1, 0)));
        items.sort(TextFormat.byDisplayName());
        assertEquals(List.of("Apple", "item #2", "item #10"),
                items.stream().map(Item::displayName).toList());
    }
}
