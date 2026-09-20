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
 * SPEC-2D-ENGINE.md §9, which were read out of the original app.
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
    void tooltipShowsCentimetersInMetric() {
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
        // Not a curiosity; it is the reason they are separate functions, and a change that
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
    void spacesAndHyphensCount() {
        // Every expectation here is the original's own answer, read out of
        // localeCompare(undefined, {numeric: true, sensitivity: 'base'}) in node on
        // 2026-09-11, not reasoned about. Before this rule existed the Collator dropped
        // separators at PRIMARY strength and the first four came back 0.
        assertTrue(TextFormat.compareNames("a b", "ab") < 0, "a space is a real character");
        assertTrue(TextFormat.compareNames("a-b", "ab") < 0, "so is a hyphen");
        assertTrue(TextFormat.compareNames("a b", "a-b") < 0, "and a space comes before one");
        assertTrue(TextFormat.compareNames("a  b", "a b") < 0, "two spaces before one");
        assertTrue(TextFormat.compareNames("-b", "a") < 0,
                "a separator sorts before letters, so a leading hyphen wins outright");
        assertTrue(TextFormat.compareNames("a ", "a") > 0, "a trailing space still counts");
    }

    @Test
    void onlyLeadingSpacesAreIgnored() {
        // The one deliberate departure from the original, decided by the user 2026-09-11,
        // and D-24. The original answers -1 to all three: a name typed with a leading space
        // sorts above every other name in the list.
        assertTrue(TextFormat.compareNames(" z", "a") > 0,
                "' z' sorts under z, not above everything");
        assertEquals(0, TextFormat.compareNames(" a", "a"));
        assertEquals(0, TextFormat.compareNames("  x", "x"));
        // And dropping them must not reach any further into the name than the front.
        assertTrue(TextFormat.compareNames(" a b", "ab") < 0,
                "the interior space survives the leading one being dropped");
    }

    @Test
    void punctuationKeepsTheOriginalsOwnPositions() {
        // The cheap fix for the separator rule was to fold case and compare code points, and
        // it reproduced every answer above. It would have got these two backwards: '~' and
        // '{' sit above 'a' in code points and below it in the collator, which is where the
        // original puts them. Comparing through the Collator is what keeps them right.
        assertTrue(TextFormat.compareNames("~x", "ax") < 0);
        assertTrue(TextFormat.compareNames("{x", "ax") < 0);
        // The same fix would also have lost this, which the original folds.
        assertEquals(0, TextFormat.compareNames("stra\u00dfe", "strasse"));
    }

    @Test
    void namesThatDifferOnlyByALeadingSpaceKeepTheirOrder() {
        // They compare equal by design, so determinism comes from the sort being stable
        // rather than from the comparator. Both call sites use List.sort, which is.
        List<Item> items = new ArrayList<>(List.of(
                item(" bin", 1, 1, 1, 0),
                item("bin", 1, 1, 1, 0),
                item("apple", 1, 1, 1, 0)));
        items.sort(TextFormat.byDisplayName());
        assertEquals(List.of("apple", " bin", "bin"),
                items.stream().map(Item::displayName).toList());
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
