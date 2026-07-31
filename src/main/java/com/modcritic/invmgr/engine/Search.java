package com.modcritic.invmgr.engine;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Units;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The item list's search box: which boxes it shows, and in what order.
 *
 * <p>Typing splits on spaces and <b>every</b> word has to match — so {@code blue bin} finds
 * things called "blue storage bin" but not things called just "blue". Two kinds of word:
 *
 * <ul>
 *   <li><b>A measurement</b> — a {@code w}, {@code l} or {@code h} stuck to a number, like
 *       {@code w20}. Matches boxes 20 wide, give or take a hundredth.
 *   <li><b>Anything else</b> — matched against the name, ignoring capitals.
 * </ul>
 *
 * <p>So {@code w20 storage} finds 20-wide boxes with "storage" in the name.
 *
 * <p><b>The number is read in whatever unit is on screen.</b> In metric, {@code w20} means 20
 * centimetres and is converted before comparing; in imperial it means 20 inches. Searching in
 * the units you can see is the only behaviour that makes sense, but it does mean the same query
 * finds different boxes depending on the Units button.
 *
 * <p>What you type here is <b>never saved</b> — not to the file, not to the undo history. It is
 * a view of the list, not part of the room.
 */
public final class Search {

    /**
     * A measurement word: one of w/l/h, then a number. Anchored at both ends, so "w20" is a
     * measurement but "w20x" is just a name fragment.
     */
    private static final Pattern DIMENSION_WORD = Pattern.compile("^([wlh])(\\d+(?:\\.\\d+)?)$");

    /**
     * How close a measurement has to be to count as a match, in inches.
     *
     * <p>Not zero, because a box entered as 20 cm is stored as 7.874016 inches and converting
     * back does not land exactly on 20. Comparing exactly would mean a metric search almost
     * never matched anything.
     */
    private static final double EPSILON_IN = 0.01;

    private Search() {
    }

    /** Splits what was typed into words, lower-cased, with the spaces thrown away. */
    public static List<String> tokens(String query) {
        if (query == null) {
            return List.of();
        }
        String trimmed = query.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return List.of();
        }
        return List.of(trimmed.split("\\s+"));
    }

    /** Whether one item survives the search. An empty search matches everything. */
    public static boolean matches(AppState state, Item item, List<String> tokens) {
        if (tokens.isEmpty()) {
            return true;
        }
        String name = item.displayName().toLowerCase(Locale.ROOT);

        for (String token : tokens) {
            Matcher measurement = DIMENSION_WORD.matcher(token);
            if (measurement.matches()) {
                if (!matchesMeasurement(state, item, measurement)) {
                    return false;
                }
            } else if (!name.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesMeasurement(AppState state, Item item, Matcher measurement) {
        char axis = measurement.group(1).charAt(0);
        double typed = Double.parseDouble(measurement.group(2));
        double wanted = state.metricMode ? Units.cmToIn(typed) : typed;

        double actual;
        switch (axis) {
            case 'w' -> actual = item.w_in;
            case 'l' -> actual = item.l_in;
            default -> actual = item.h_in;
        }
        return Math.abs(actual - wanted) <= EPSILON_IN;
    }

    /**
     * The items the list should show: sorted by name, then filtered.
     *
     * <p>Sorting before filtering rather than after is deliberate — it means a row keeps the
     * same neighbours whether or not a search is active, so clearing the box does not reshuffle
     * what is left.
     */
    public static List<Item> visibleItems(AppState state, String query) {
        List<Item> sorted = new ArrayList<>(state.items);
        sorted.sort(TextFormat.byDisplayName());

        List<String> tokens = tokens(query);
        List<Item> visible = new ArrayList<>(sorted.size());
        for (Item item : sorted) {
            if (matches(state, item, tokens)) {
                visible.add(item);
            }
        }
        return visible;
    }
}
