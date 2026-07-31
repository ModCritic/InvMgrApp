package com.modcritic.invmgr.persist;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Preset;
import com.modcritic.invmgr.model.Room;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Turns a {@link AppState} into a {@code room_inventory.json} file and back.
 *
 * <p><b>The contract this class exists to keep:</b> the Java app must open every file the
 * HTML app has ever written, and must write files the HTML app can still open. That is why
 * this code looks unusually literal — every range, default and fallback below is copied from
 * the original's {@code loadState}, and several of them are surprising.
 *
 * <p><b>The loader never throws and never rejects a file for being ugly.</b> A value that is
 * missing, the wrong type, or out of range is replaced with a default; a single bad item is
 * dropped; only two conditions refuse the whole file (see {@link LoadResult}). A
 * hand-edited or truncated file should degrade into something sensible rather than lose the
 * user's room.
 *
 * <p><b>Out-of-range numbers are replaced, not pinned.</b> A width of 99999 inches does not
 * become the 1000-inch maximum — it becomes the 12-inch default. That reads like a bug and
 * is not: it is what the original does, and matching it is the whole point.
 */
public final class SaveFormat {

    /**
     * The two accepted item ID shapes: the current {@code item-id-<uuid>} and the legacy
     * {@code i<digits>_<digits>} from older files. An item whose ID matches neither is
     * <b>dropped silently</b>, which is why this pattern must not be tightened — a
     * stricter rule here would look like data loss to the user.
     */
    private static final Pattern SAFE_ID = Pattern.compile("^(i\\d+_\\d+|item-id-[0-9a-f-]{36})$");

    /** The only colour shape the format accepts. Note: no spaces after the commas. */
    private static final Pattern SAFE_COLOR = Pattern.compile("^hsl\\(\\d+,\\d+%,\\d+%\\)$");

    private static final Random RANDOM = new Random();

    private SaveFormat() {
    }

    /**
     * The outcome of a load: either a state, or a message to show the user.
     *
     * <p>Modelled as a value rather than an exception because the original app treats a bad
     * file as a status-bar message, not a crash, and because there are exactly two refusal
     * cases — an unparseable or non-object file, and one with more than 500 items.
     */
    public static final class LoadResult {
        private final AppState state;
        private final String error;

        private LoadResult(AppState state, String error) {
            this.state = state;
            this.error = error;
        }

        public boolean isSuccess() {
            return state != null;
        }

        /** The loaded state, or {@code null} if the load failed. */
        public AppState state() {
            return state;
        }

        /**
         * The message to show, already prefixed with {@code "Load error: "} exactly as the
         * original does, or {@code null} on success.
         */
        public String error() {
            return error;
        }
    }

    /** A fresh random colour, matching the original's {@code randColor()}. */
    public static String randomColor() {
        return "hsl(" + RANDOM.nextInt(360) + ",55%,42%)";
    }

    // ------------------------------------------------------------------- load

    /** Loads with real random colours for items whose colour is missing or malformed. */
    public static LoadResult load(String jsonText) {
        return load(jsonText, SaveFormat::randomColor);
    }

    /**
     * Loads a save file.
     *
     * @param jsonText the file's contents
     * @param colorSupplier what to use when an item's colour is absent or malformed. Exists
     *     so tests can supply a fixed colour — the real behaviour is a random hue, which
     *     nothing could assert against.
     * @return a {@link LoadResult}; this method does not throw for bad input
     */
    public static LoadResult load(String jsonText, Supplier<String> colorSupplier) {
        Object parsed;
        try {
            parsed = Json.parse(jsonText);
        } catch (Json.JsonException e) {
            // The original surfaces the parser's own message: 'Load error: ' + err.message
            return new LoadResult(null, "Load error: " + e.getMessage());
        }

        if (!(parsed instanceof Map)) {
            return new LoadResult(null, "Load error: invalid file.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) parsed;

        AppState loaded = new AppState();

        Object rawRoom = Json.get(state, "room");
        Map<String, Object> roomMap = rawRoom instanceof Map
                ? castMap(rawRoom)
                : java.util.Collections.emptyMap();
        loaded.room = new Room(
                safeNum(Json.get(roomMap, "w"), Room.MIN_W, Room.MAX_W, Room.DEFAULT_W),
                safeNum(Json.get(roomMap, "l"), Room.MIN_L, Room.MAX_L, Room.DEFAULT_L),
                safeNum(Json.get(roomMap, "h"), Room.MIN_H, Room.MAX_H, Room.DEFAULT_H));

        loaded.itemCounter = safeNum(Json.get(state, "itemCounter"), 0, AppState.MAX_COUNTER, 0);
        loaded.dragOrderCounter =
                safeNum(Json.get(state, "dragOrderCounter"), 0, AppState.MAX_COUNTER, 0);
        // Note the default: a file with no usable layerFeet opens with the slider level
        // with its own ceiling, not with some fixed number.
        loaded.layerFeet = safeNum(Json.get(state, "layerFeet"), 0, AppState.MAX_LAYER_FEET,
                loaded.room.h);

        Object rawItems = Json.get(state, "items");
        List<Object> itemList = rawItems instanceof List ? castList(rawItems) : List.of();
        if (itemList.size() > AppState.MAX_ITEMS) {
            // Refused wholesale rather than truncated — losing half a room silently would
            // be worse than refusing to open it.
            return new LoadResult(null,
                    "Load error: too many items (max " + AppState.MAX_ITEMS + ").");
        }

        List<Item> items = new ArrayList<>();
        for (Object raw : itemList) {
            if (!(raw instanceof Map)) {
                continue;                      // skips both nulls and non-objects
            }
            Map<String, Object> itemMap = castMap(raw);
            String id = safeId(Json.get(itemMap, "id"));
            if (id == null) {
                continue;                      // bad ID: the whole item is dropped
            }

            Item item = new Item();
            item.id = id;
            // Defaults reference the count of items accepted SO FAR, so they depend on how
            // many earlier items were dropped. Read them before adding this one.
            item.serial = safeNum(Json.get(itemMap, "serial"), 1, AppState.MAX_COUNTER,
                    items.size() + 1);
            // dragOrder falls back to the item's RAW serial — not the validated one above —
            // so that files predating the dragOrder field still stack in a sane order.
            // If that is unusable too, the default is the position, with no +1.
            Object rawDragOrder = Json.get(itemMap, "dragOrder");
            Object dragOrderSource = rawDragOrder == Json.MISSING
                    ? Json.get(itemMap, "serial")
                    : rawDragOrder;
            item.dragOrder = safeNum(dragOrderSource, 0, AppState.MAX_COUNTER, items.size());
            item.w_in = safeNum(Json.get(itemMap, "w_in"), Item.MIN_DIMENSION_IN,
                    Item.MAX_DIMENSION_IN, Item.DEFAULT_DIMENSION_IN);
            item.l_in = safeNum(Json.get(itemMap, "l_in"), Item.MIN_DIMENSION_IN,
                    Item.MAX_DIMENSION_IN, Item.DEFAULT_DIMENSION_IN);
            item.h_in = safeNum(Json.get(itemMap, "h_in"), Item.MIN_DIMENSION_IN,
                    Item.MAX_DIMENSION_IN, Item.DEFAULT_DIMENSION_IN);
            item.x_px = safeNum(Json.get(itemMap, "x_px"), 0, Item.MAX_POSITION_PX,
                    Item.DEFAULT_POSITION_PX);
            item.y_px = safeNum(Json.get(itemMap, "y_px"), 0, Item.MAX_POSITION_PX,
                    Item.DEFAULT_POSITION_PX);
            item.color = safeColor(Json.get(itemMap, "color"), colorSupplier);
            item.name = safeText(Json.get(itemMap, "name"), Item.MAX_NAME_LENGTH);
            item.customId = safeText(Json.get(itemMap, "customId"), Item.MAX_CUSTOM_ID_LENGTH);
            item.baseHeight_in = safeNum(Json.get(itemMap, "baseHeight_in"), 0,
                    Item.MAX_BASE_HEIGHT_IN, 0);
            // Strictly true, not "truthy": the string "yes" is not a yes.
            item.planned = Boolean.TRUE.equals(Json.get(itemMap, "planned"));
            items.add(item);
        }
        loaded.items = items;

        // A non-boolean is ignored rather than treated as false. That matters when loading
        // into a running app: the original leaves the *current* mode alone in that case, so
        // whoever wires this up at M3 must apply these two only when the key was a boolean,
        // not blindly copy the defaults below.
        Object planMode = Json.get(state, "planMode");
        loaded.planMode = planMode instanceof Boolean ? (Boolean) planMode : false;
        Object metricMode = Json.get(state, "metricMode");
        loaded.metricMode = metricMode instanceof Boolean ? (Boolean) metricMode : false;

        // "floorCollision" is what Layer Collision used to be called. Read both, write
        // only the new name. Dropping the old key would silently lose the setting for
        // anyone with a file from before the rename.
        loaded.layerCollision = Boolean.TRUE.equals(Json.get(state, "layerCollision"))
                || Boolean.TRUE.equals(Json.get(state, "floorCollision"));

        loaded.presets = loadPresets(Json.get(state, "presets"));

        return new LoadResult(loaded, null);
    }

    private static List<Preset> loadPresets(Object rawPresets) {
        List<Preset> presets = new ArrayList<>();
        if (rawPresets instanceof List) {
            List<Object> slots = castList(rawPresets);
            int limit = Math.min(slots.size(), Preset.MAX_SLOTS);
            for (int i = 0; i < limit; i++) {
                Object slot = slots.get(i);
                if (!(slot instanceof Map)) {
                    presets.add(null);         // empty slot, or unusable and treated as one
                    continue;
                }
                Map<String, Object> slotMap = castMap(slot);
                Object name = Json.get(slotMap, "name");
                if (!(name instanceof String)) {
                    presets.add(null);
                    continue;
                }
                presets.add(new Preset(
                        truncate((String) name, Preset.MAX_NAME_LENGTH),
                        safeNum(Json.get(slotMap, "w_in"), Item.MIN_DIMENSION_IN,
                                Item.MAX_DIMENSION_IN, Item.DEFAULT_DIMENSION_IN),
                        safeNum(Json.get(slotMap, "l_in"), Item.MIN_DIMENSION_IN,
                                Item.MAX_DIMENSION_IN, Item.DEFAULT_DIMENSION_IN),
                        safeNum(Json.get(slotMap, "h_in"), Item.MIN_DIMENSION_IN,
                                Item.MAX_DIMENSION_IN, Item.DEFAULT_DIMENSION_IN)));
            }
        }
        if (presets.isEmpty()) {
            // Never leave the user with no slots at all.
            return new ArrayList<>(Arrays.asList(new Preset[Preset.DEFAULT_SLOT_COUNT]));
        }
        return presets;
    }

    // -------------------------------------------------------- validation rules

    /**
     * The original's {@code safeNum}: convert using JavaScript's rules, then <b>reject</b>
     * anything non-finite or outside the range, falling back to {@code fallback}.
     *
     * <p>Rejection, not clamping. See this class's header.
     */
    static double safeNum(Object value, double min, double max, double fallback) {
        double n = Json.jsNumber(value);
        if (Double.isNaN(n) || Double.isInfinite(n) || n < min || n > max) {
            return fallback;
        }
        return n;
    }

    /** Returns the ID if it is a string of an accepted shape, otherwise {@code null}. */
    static String safeId(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String text = (String) value;
        return SAFE_ID.matcher(text).matches() ? text : null;
    }

    private static String safeColor(Object value, Supplier<String> fallback) {
        if (value instanceof String && SAFE_COLOR.matcher((String) value).matches()) {
            return (String) value;
        }
        return fallback.get();
    }

    /** A non-string becomes empty; a long string is cut to length. Never {@code null}. */
    private static String safeText(Object value, int maxLength) {
        if (!(value instanceof String)) {
            return "";
        }
        return truncate((String) value, maxLength);
    }

    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    // ------------------------------------------------------------------- save

    /**
     * Serializes state to the exact text the HTML app writes: the same nine keys in the same
     * order, two-space indentation, and <b>no trailing newline</b> (the original builds the
     * file from {@code JSON.stringify} output verbatim).
     *
     * <p>Item keys are written in the order {@link Item} declares them, which is the order
     * the original uses for items the user just created. Note that the original writes a
     * <em>different</em> key order for items it loaded from a file — an inconsistency in the
     * original, not here. Since the HTML app reads keys by name, order affects only whether
     * files compare byte-identical, never whether they load.
     */
    public static String save(AppState state) {
        StringBuilder out = new StringBuilder(1024);
        out.append("{\n");

        out.append("  \"room\": {\n");
        out.append("    \"w\": ").append(Json.writeNumber(state.room.w)).append(",\n");
        out.append("    \"l\": ").append(Json.writeNumber(state.room.l)).append(",\n");
        out.append("    \"h\": ").append(Json.writeNumber(state.room.h)).append("\n");
        out.append("  },\n");

        if (state.items.isEmpty()) {
            out.append("  \"items\": [],\n");
        } else {
            out.append("  \"items\": [\n");
            for (int i = 0; i < state.items.size(); i++) {
                appendItem(out, state.items.get(i));
                out.append(i < state.items.size() - 1 ? ",\n" : "\n");
            }
            out.append("  ],\n");
        }

        out.append("  \"itemCounter\": ").append(Json.writeNumber(state.itemCounter)).append(",\n");
        out.append("  \"dragOrderCounter\": ").append(Json.writeNumber(state.dragOrderCounter))
                .append(",\n");
        out.append("  \"layerFeet\": ").append(Json.writeNumber(state.layerFeet)).append(",\n");
        out.append("  \"planMode\": ").append(state.planMode).append(",\n");
        out.append("  \"metricMode\": ").append(state.metricMode).append(",\n");
        // Only the current name is ever written, never the legacy floorCollision.
        out.append("  \"layerCollision\": ").append(state.layerCollision).append(",\n");

        if (state.presets.isEmpty()) {
            out.append("  \"presets\": []\n");
        } else {
            out.append("  \"presets\": [\n");
            for (int i = 0; i < state.presets.size(); i++) {
                Preset preset = state.presets.get(i);
                if (preset == null) {
                    out.append("    null");
                } else {
                    out.append("    {\n");
                    out.append("      \"name\": ").append(Json.writeString(preset.name))
                            .append(",\n");
                    out.append("      \"w_in\": ").append(Json.writeNumber(preset.w_in))
                            .append(",\n");
                    out.append("      \"l_in\": ").append(Json.writeNumber(preset.l_in))
                            .append(",\n");
                    out.append("      \"h_in\": ").append(Json.writeNumber(preset.h_in))
                            .append("\n");
                    out.append("    }");
                }
                out.append(i < state.presets.size() - 1 ? ",\n" : "\n");
            }
            out.append("  ]\n");
        }

        out.append("}");
        return out.toString();
    }

    private static void appendItem(StringBuilder out, Item item) {
        out.append("    {\n");
        out.append("      \"id\": ").append(Json.writeString(item.id)).append(",\n");
        out.append("      \"serial\": ").append(Json.writeNumber(item.serial)).append(",\n");
        out.append("      \"dragOrder\": ").append(Json.writeNumber(item.dragOrder)).append(",\n");
        out.append("      \"w_in\": ").append(Json.writeNumber(item.w_in)).append(",\n");
        out.append("      \"l_in\": ").append(Json.writeNumber(item.l_in)).append(",\n");
        out.append("      \"h_in\": ").append(Json.writeNumber(item.h_in)).append(",\n");
        out.append("      \"x_px\": ").append(Json.writeNumber(item.x_px)).append(",\n");
        out.append("      \"y_px\": ").append(Json.writeNumber(item.y_px)).append(",\n");
        out.append("      \"color\": ").append(Json.writeString(item.color)).append(",\n");
        out.append("      \"name\": ").append(Json.writeString(item.name)).append(",\n");
        out.append("      \"customId\": ").append(Json.writeString(item.customId)).append(",\n");
        out.append("      \"baseHeight_in\": ").append(Json.writeNumber(item.baseHeight_in))
                .append(",\n");
        out.append("      \"planned\": ").append(item.planned).append("\n");
        out.append("    }");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return (List<Object>) value;
    }
}
