package com.modcritic.invmgr.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Preset;
import java.io.IOException;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests the save format against the original app's real behaviour.
 *
 * <p>The important tests here are the differential ones. The files in
 * {@code src/test/resources/golden/} were produced by running the HTML app's own
 * {@code loadState} validation code under node — see {@code tools/golden/original-loadstate.js}
 * — so these assertions compare the Java port against the shipped JavaScript, not against
 * anybody's reading of it. If the Java disagrees, the Java is wrong.
 */
class SaveFormatTest {

    /**
     * The same fixed colour the golden generator uses in place of a random hue, so items
     * with a missing or malformed colour are comparable.
     */
    private static final Supplier<String> FIXED_COLOR = () -> "hsl(0,55%,42%)";

    @ParameterizedTest
    @ValueSource(strings = {"typical", "legacy", "hostile", "empty-object"})
    @DisplayName("loading matches the original app's validation, value for value")
    void matchesTheOriginalValidation(String name) throws IOException {
        String input = Fixtures.read("fixtures/" + name + ".json");
        String expected = Fixtures.read("golden/" + name + ".expected.json");

        SaveFormat.LoadResult result = SaveFormat.load(input, FIXED_COLOR);
        assertTrue(result.isSuccess(), () -> "load failed: " + result.error());

        assertEquals(expected, SaveFormat.save(result.state()),
                "Java's loaded state differs from what the original app's own loadState "
                        + "produces for " + name + ".json");
    }

    @Test
    @DisplayName("a real save file survives load-then-save byte for byte")
    void typicalFileRoundTripsByteForByte() throws IOException {
        // This is the compatibility guarantee in its strongest form: not just "the HTML app
        // can read what we write", but "what we write is indistinguishable from what it
        // wrote". Byte equality also pins the two-space indentation, the key order, the
        // absence of a trailing newline, and 12 being written as 12 rather than 12.0.
        String original = Fixtures.read("fixtures/typical.json");

        SaveFormat.LoadResult result = SaveFormat.load(original, FIXED_COLOR);
        assertTrue(result.isSuccess());

        assertEquals(original, SaveFormat.save(result.state()));
    }

    @Test
    @DisplayName("saving twice in a row produces identical text")
    void saveIsStable() throws IOException {
        AppState state = SaveFormat.load(Fixtures.read("fixtures/typical.json"), FIXED_COLOR).state();
        assertEquals(SaveFormat.save(state), SaveFormat.save(state));
    }

    // ------------------------------------------------------- legacy files

    @Test
    @DisplayName("floorCollision, the old name for Layer Collision, still turns it on")
    void readsLegacyFloorCollisionKey() throws IOException {
        AppState state = SaveFormat.load(Fixtures.read("fixtures/legacy.json"), FIXED_COLOR).state();
        assertTrue(state.layerCollision,
                "a file saved before the rename must still restore Layer Collision");
    }

    @Test
    @DisplayName("only the current key name is written back, never the legacy one")
    void writesOnlyTheCurrentCollisionKey() throws IOException {
        AppState state = SaveFormat.load(Fixtures.read("fixtures/legacy.json"), FIXED_COLOR).state();
        String saved = SaveFormat.save(state);
        assertTrue(saved.contains("\"layerCollision\": true"));
        assertFalse(saved.contains("floorCollision"),
                "the legacy key is read for compatibility but must never be written");
    }

    @Test
    @DisplayName("legacy i<digits>_<digits> item IDs survive a load")
    void keepsLegacyItemIds() throws IOException {
        AppState state = SaveFormat.load(Fixtures.read("fixtures/legacy.json"), FIXED_COLOR).state();
        assertEquals(2, state.items.size());
        assertEquals("i123_456", state.items.get(0).id);
        assertEquals("i123_789", state.items.get(1).id);
    }

    @Test
    @DisplayName("an item with no dragOrder falls back to its serial")
    void dragOrderFallsBackToSerial() throws IOException {
        // Files predating the dragOrder field must still stack in a sane order rather than
        // all collapsing to zero.
        AppState state = SaveFormat.load(Fixtures.read("fixtures/legacy.json"), FIXED_COLOR).state();
        assertEquals(7, state.items.get(0).serial);
        assertEquals(7, state.items.get(0).dragOrder);
        assertEquals(8, state.items.get(1).serial);
        assertEquals(8, state.items.get(1).dragOrder);
    }

    // ------------------------------------------------------ hostile input

    @Test
    @DisplayName("an item whose ID fails the pattern is dropped, and the rest still load")
    void dropsItemsWithBadIds() throws IOException {
        AppState state = SaveFormat.load(Fixtures.read("fixtures/hostile.json"), FIXED_COLOR).state();
        // The fixture has five entries: a bad ID, a null, a bare string, and two good items.
        assertEquals(2, state.items.size());
    }

    @Test
    @DisplayName("out-of-range numbers are replaced with defaults, not pinned to the bound")
    void outOfRangeValuesBecomeDefaults() throws IOException {
        AppState state = SaveFormat.load(Fixtures.read("fixtures/hostile.json"), FIXED_COLOR).state();

        // 99999 inches wide does NOT become the 1000-inch maximum.
        assertEquals(Item.DEFAULT_DIMENSION_IN, state.items.get(0).w_in);
        // 0.5 inches is below the minimum, so it defaults rather than clamping up to 1.
        assertEquals(Item.DEFAULT_DIMENSION_IN, state.items.get(0).l_in);
        // A null height converts to 0, which fails the >= 1 check, so it defaults.
        assertEquals(Item.DEFAULT_DIMENSION_IN, state.items.get(0).h_in);
        // Negative and 1e9 positions both default to 10.
        assertEquals(Item.DEFAULT_POSITION_PX, state.items.get(0).x_px);
        assertEquals(Item.DEFAULT_POSITION_PX, state.items.get(0).y_px);
        // The room's 500 ft width exceeds 200, so the whole room falls back to 12 x 10 x 8.
        assertEquals(12, state.room.w);
        assertEquals(10, state.room.l);
        assertEquals(8, state.room.h);
    }

    @Test
    @DisplayName("a malformed colour is replaced, text is truncated, planned is strict")
    void sanitisesTextColourAndFlags() throws IOException {
        AppState state = SaveFormat.load(Fixtures.read("fixtures/hostile.json"), FIXED_COLOR).state();
        Item first = state.items.get(0);

        assertEquals("hsl(0,55%,42%)", first.color, "a non-hsl colour must be replaced");
        assertEquals("", first.name, "a non-string name becomes empty, never null");
        assertEquals(Item.MAX_CUSTOM_ID_LENGTH, first.customId.length(), "customId is capped at 60");
        assertFalse(first.planned, "planned is strictly true/false — the string \"yes\" is not true");

        // "true" as a string is likewise not true.
        assertFalse(state.layerCollision);
    }

    @Test
    @DisplayName("a presets value that isn't an array resets to three empty slots")
    void resetsPresetsWhenUnusable() throws IOException {
        AppState state = SaveFormat.load(Fixtures.read("fixtures/hostile.json"), FIXED_COLOR).state();
        assertEquals(Preset.DEFAULT_SLOT_COUNT, state.presets.size());
        assertTrue(state.presets.stream().allMatch(java.util.Objects::isNull));
    }

    @Test
    @DisplayName("more than 50 preset slots are truncated")
    void truncatesPresetSlots() {
        StringBuilder json = new StringBuilder("{\"presets\":[");
        for (int i = 0; i < 60; i++) {
            json.append(i > 0 ? "," : "").append("{\"name\":\"P").append(i % 10).append("\"}");
        }
        json.append("]}");

        AppState state = SaveFormat.load(json.toString(), FIXED_COLOR).state();
        assertEquals(Preset.MAX_SLOTS, state.presets.size());
    }

    @Test
    @DisplayName("a preset name longer than 2 characters is cut down")
    void truncatesPresetNames() {
        AppState state = SaveFormat.load(
                "{\"presets\":[{\"name\":\"Small\",\"w_in\":24,\"l_in\":24,\"h_in\":24}]}",
                FIXED_COLOR).state();
        assertEquals("Sm", state.presets.get(0).name);
        assertEquals(24, state.presets.get(0).w_in);
    }

    // ---------------------------------------------------------- refusals

    @Test
    @DisplayName("exactly 500 items load; 501 refuses the whole file")
    void itemLimitIsFiveHundred() {
        SaveFormat.LoadResult atLimit = SaveFormat.load(fileWithItems(500), FIXED_COLOR);
        assertTrue(atLimit.isSuccess(), () -> "500 items should load: " + atLimit.error());
        assertEquals(500, atLimit.state().items.size());

        SaveFormat.LoadResult overLimit = SaveFormat.load(fileWithItems(501), FIXED_COLOR);
        assertFalse(overLimit.isSuccess());
        assertEquals("Load error: too many items (max 500).", overLimit.error());
        assertNull(overLimit.state(),
                "an over-limit file is refused outright, not silently truncated");
    }

    @Test
    @DisplayName("malformed JSON is reported, not thrown")
    void malformedJsonIsReported() {
        // The original catches the parse failure and shows it in the status bar. Anything
        // that escapes as an exception here would crash the app on a truncated file.
        SaveFormat.LoadResult result = SaveFormat.load("{\"room\": {\"w\": 12,", FIXED_COLOR);
        assertFalse(result.isSuccess());
        assertNotNull(result.error());
        assertTrue(result.error().startsWith("Load error: "),
                "the message must carry the same prefix the original uses, got: "
                        + result.error());
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"just a string\"", "42", "true", "null"})
    @DisplayName("valid JSON that isn't an object is refused as an invalid file")
    void nonObjectFilesAreRefused(String json) {
        SaveFormat.LoadResult result = SaveFormat.load(json, FIXED_COLOR);
        assertFalse(result.isSuccess());
        assertEquals("Load error: invalid file.", result.error());
    }

    // ------------------------------------------------------------ defaults

    @Test
    @DisplayName("an empty object loads as the default room with the slider at the ceiling")
    void emptyObjectGivesDefaults() {
        AppState state = SaveFormat.load("{}", FIXED_COLOR).state();
        assertEquals(12, state.room.w);
        assertEquals(10, state.room.l);
        assertEquals(8, state.room.h);
        assertEquals(0, state.itemCounter);
        // layerFeet defaults to the room's own height, not to a fixed number.
        assertEquals(state.room.h, state.layerFeet);
        assertTrue(state.items.isEmpty());
        assertEquals(Preset.DEFAULT_SLOT_COUNT, state.presets.size());
    }

    @Test
    @DisplayName("layerFeet defaults to whatever the loaded room's height is")
    void layerFeetFollowsTheLoadedRoom() {
        AppState state = SaveFormat.load("{\"room\":{\"h\":14},\"layerFeet\":\"nonsense\"}",
                FIXED_COLOR).state();
        assertEquals(14, state.room.h);
        assertEquals(14, state.layerFeet);
    }

    @Test
    @DisplayName("a fresh state saves to valid JSON that loads back unchanged")
    void freshStateRoundTrips() {
        String saved = SaveFormat.save(new AppState());
        SaveFormat.LoadResult reloaded = SaveFormat.load(saved, FIXED_COLOR);
        assertTrue(reloaded.isSuccess(), () -> "our own output must load: " + reloaded.error());
        assertEquals(saved, SaveFormat.save(reloaded.state()));
        assertTrue(saved.contains("\"items\": []"), "an empty item list writes as []");
    }

    private static String fileWithItems(int count) {
        StringBuilder json = new StringBuilder("{\"items\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"i").append(i).append("_1\",\"serial\":").append(i + 1)
                    .append(",\"color\":\"hsl(1,55%,42%)\"}");
        }
        json.append("]}");
        return json.toString();
    }
}
