package com.modcritic.invmgr.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Units;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The search box's rules, from SPEC-2D-ENGINE.md §8. */
class SearchTest {

    private static Item item(String name, double w, double l, double h) {
        Item item = new Item();
        item.id = "item-id-" + name;
        item.serial = 1;
        item.w_in = w;
        item.l_in = l;
        item.h_in = h;
        item.name = name;
        item.customId = "";
        item.color = "hsl(200,55%,42%)";
        return item;
    }

    private static boolean find(AppState state, Item item, String query) {
        return Search.matches(state, item, Search.tokens(query));
    }

    @Test
    void anEmptySearchShowsEverything() {
        AppState state = new AppState();
        Item bin = item("Blue Bin", 12, 12, 12);
        assertTrue(find(state, bin, ""));
        assertTrue(find(state, bin, "   "));
        assertTrue(find(state, bin, null));
    }

    @Test
    void namesMatchOnAnyPartAndIgnoreCapitals() {
        AppState state = new AppState();
        Item bin = item("Blue Storage Bin", 12, 12, 12);
        assertTrue(find(state, bin, "storage"));
        assertTrue(find(state, bin, "STORAGE"));
        assertTrue(find(state, bin, "tora"));
        assertFalse(find(state, bin, "red"));
    }

    @Test
    void everyWordHasToMatch() {
        AppState state = new AppState();
        Item bin = item("Blue Storage Bin", 12, 12, 12);
        assertTrue(find(state, bin, "blue bin"));
        assertFalse(find(state, bin, "blue crate"),
                "one non-matching word rules the item out -- the words are ANDed");
    }

    @Test
    void aMeasurementWordFiltersOnThatAxis() {
        AppState state = new AppState();
        Item bin = item("Bin", 20, 30, 40);
        assertTrue(find(state, bin, "w20"));
        assertTrue(find(state, bin, "l30"));
        assertTrue(find(state, bin, "h40"));
        assertFalse(find(state, bin, "w30"), "20 wide must not match a search for 30 wide");
    }

    @Test
    void measurementAndNameWordsCombine() {
        AppState state = new AppState();
        Item match = item("Blue Storage", 20, 12, 12);
        Item wrongWidth = item("Blue Storage", 24, 12, 12);
        Item wrongName = item("Red Crate", 20, 12, 12);

        assertTrue(find(state, match, "w20 storage"));
        assertFalse(find(state, wrongWidth, "w20 storage"));
        assertFalse(find(state, wrongName, "w20 storage"));
    }

    @Test
    void aMeasurementWordIsReadInTheUnitOnScreen() {
        AppState state = new AppState();
        // A box entered as 20 cm is stored as 7.874016 in.
        Item metricBox = item("Bin", Units.cmDimensionInputToInches(20), 12, 12);

        state.metricMode = true;
        assertTrue(find(state, metricBox, "w20"), "in metric, w20 means 20 centimetres");

        state.metricMode = false;
        assertFalse(find(state, metricBox, "w20"),
                "in imperial the same query means 20 inches, which this box is not");
        assertTrue(find(state, metricBox, "w7.874"), "and 7.874 inches is what it actually is");
    }

    @Test
    void somethingThatOnlyLooksLikeAMeasurementIsTreatedAsAName() {
        AppState state = new AppState();
        Item oddName = item("w20x crate", 5, 5, 5);
        assertTrue(find(state, oddName, "w20x"),
                "the pattern is anchored, so 'w20x' is a name fragment and not a width filter");
        assertFalse(find(state, oddName, "w20"), "'w20' is a width filter, and this box is 5 wide");
    }

    @Test
    void theDefaultNameIsSearchable() {
        AppState state = new AppState();
        Item unnamed = item("", 12, 12, 12);
        unnamed.serial = 7;
        assertTrue(find(state, unnamed, "item"));
        assertTrue(find(state, unnamed, "#7"));
    }

    @Test
    void theVisibleListIsSortedThenFiltered() {
        AppState state = new AppState();
        state.items.add(item("Zebra Bin", 12, 12, 12));
        state.items.add(item("Apple Bin", 12, 12, 12));
        state.items.add(item("Crate", 12, 12, 12));

        assertEquals(List.of("Apple Bin", "Crate", "Zebra Bin"),
                Search.visibleItems(state, "").stream().map(Item::displayName).toList());
        assertEquals(List.of("Apple Bin", "Zebra Bin"),
                Search.visibleItems(state, "bin").stream().map(Item::displayName).toList());
    }

    @Test
    void plannedItemsAreSearchedLikeAnyOther() {
        AppState state = new AppState();
        Item ghost = item("Ghost Box", 12, 12, 12);
        ghost.planned = true;
        state.items.add(ghost);
        assertEquals(1, Search.visibleItems(state, "ghost").size());
    }
}
