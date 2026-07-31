package com.modcritic.invmgr.engine;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Units;
import java.math.BigDecimal;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Turning items into text: the hover tooltip, the exported {@code .txt} file, and the order
 * names sort in.
 *
 * <p><b>The tooltip and the export line are two separate formatters and are allowed to
 * disagree.</b> They look similar enough that unifying them is an obvious-seeming tidy-up, and
 * it would be wrong — the export format is a user-facing file format the original app committed
 * to (pipe-delimited, capital {@code Base:}), while the tooltip is a compact on-screen label
 * (double spaces, lower-case {@code base:}). Changing either to match the other would break
 * something someone relies on. SPEC-2D-ENGINE.md §9 says the same thing.
 */
public final class TextFormat {

    private TextFormat() {
    }

    // ------------------------------------------------------------------ numbers

    /**
     * Prints a number the way the original app does: as short as possible, with no trailing
     * zeros and no exponent.
     *
     * <p>JavaScript prints {@code 12} for a whole number and {@code 30.48} for a fraction, with
     * no way to end up with {@code 12.0} or {@code 3.048e1}. Java's own {@code Double.toString}
     * always keeps a decimal point ({@code 12.0}) and switches to exponent notation for large
     * values, so every number shown to the user goes through here instead. Without it the two
     * apps' tooltips and exported files would not match.
     */
    public static String number(double value) {
        // BigDecimal.valueOf goes via Double.toString, which gives the shortest decimal that
        // reads back as the same double -- exactly the rule JavaScript uses. Stripping the
        // trailing zeros then removes the ".0", and toPlainString refuses to use an exponent.
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** A dimension as the user should see it: centimetres in metric mode, inches otherwise. */
    private static String dimension(boolean metric, double inches) {
        return number(Units.round3(metric ? Units.inToCm(inches) : inches));
    }

    /** The unit suffix that goes with {@link #dimension}. */
    private static String unit(boolean metric) {
        return metric ? "cm" : "in";
    }

    // ----------------------------------------------------------------- tooltip

    /**
     * The line that appears when the pointer rests on a box.
     *
     * <p>Note the <b>two</b> spaces after the name and before {@code base:} — they are the only
     * thing separating the three parts, so single spaces would run them together. The base
     * height is always shown, including when it is zero, so a glance tells you whether a box is
     * on the floor rather than leaving you to infer it from silence.
     *
     * <pre>Blue Bin  12in W x 24in L x 18in H  base:0in</pre>
     */
    public static String tooltipText(AppState state, Item item) {
        boolean metric = state.metricMode;
        String u = unit(metric);
        return item.displayName()
                + "  " + dimension(metric, item.w_in) + u + " W"
                + " x " + dimension(metric, item.l_in) + u + " L"
                + " x " + dimension(metric, item.h_in) + u + " H"
                + "  base:" + dimension(metric, item.baseHeight_in) + u;
    }

    // ------------------------------------------------------------------ export

    /**
     * One line of the exported item list.
     *
     * <p>Deliberately different from {@link #tooltipText}: pipe-separated, a capital
     * {@code Base:}, a {@code [plan]} marker for ghosts, and the user's own ID on the end when
     * they set one.
     *
     * <pre>Blue Bin | 12in W x 24in L x 18in H - Base: 0in | SKU-104</pre>
     */
    public static String exportLine(AppState state, Item item) {
        boolean metric = state.metricMode;
        String u = unit(metric);
        StringBuilder line = new StringBuilder();
        line.append(item.displayName());
        if (item.planned) {
            line.append(" [plan]");
        }
        line.append(" | ")
                .append(dimension(metric, item.w_in)).append(u).append(" W")
                .append(" x ").append(dimension(metric, item.l_in)).append(u).append(" L")
                .append(" x ").append(dimension(metric, item.h_in)).append(u).append(" H")
                .append(" - Base: ").append(dimension(metric, item.baseHeight_in)).append(u);
        if (item.customId != null && !item.customId.isEmpty()) {
            line.append(" | ").append(item.customId);
        }
        return line.toString();
    }

    /**
     * The whole exported file: every item, one per line, in name order, ending with a newline.
     *
     * <p><b>The search box is ignored on purpose.</b> The export button lives on the panel
     * header rather than on the search row, so it exports the list as a whole — a filtered
     * export would be a silent surprise the first time someone forgot the filter was there.
     */
    public static String exportAll(AppState state) {
        List<Item> sorted = new ArrayList<>(state.items);
        sorted.sort(byDisplayName());

        StringBuilder text = new StringBuilder();
        for (Item item : sorted) {
            // Trailing newline included, so the file ends the way a text file should.
            text.append(exportLine(state, item)).append('\n');
        }
        return text.toString();
    }

    // ------------------------------------------------------------------- order

    /**
     * The order names appear in, in both the item list and the exported file.
     *
     * <p>Two rules, both from the original's {@code localeCompare(..., numeric, base)}:
     *
     * <ul>
     *   <li><b>Numbers count as numbers.</b> "item #2" comes before "item #10", where plain
     *       text ordering would put "#10" first because "1" is before "2".
     *   <li><b>Case and accents are ignored.</b> "bin" and "Bin" sort together instead of all
     *       the capitals coming first.
     * </ul>
     *
     * <p>Java has no single call for this, so the strings are cut into runs of digits and runs
     * of everything else, and the two kinds are compared differently.
     */
    public static Comparator<Item> byDisplayName() {
        return (a, b) -> compareNames(a.displayName(), b.displayName());
    }

    /**
     * Compares two names by the rule above.
     *
     * <p>Package-visible rather than private so the tests can pin the rule directly on strings
     * instead of having to build items around them.
     */
    static int compareNames(String left, String right) {
        // PRIMARY strength is Java's "base letters only": it treats a, A and á as the same
        // letter, which is what sensitivity:'base' means in the original.
        Collator collator = Collator.getInstance(Locale.ROOT);
        collator.setStrength(Collator.PRIMARY);

        List<String> leftRuns = runs(left);
        List<String> rightRuns = runs(right);

        int shared = Math.min(leftRuns.size(), rightRuns.size());
        for (int i = 0; i < shared; i++) {
            String l = leftRuns.get(i);
            String r = rightRuns.get(i);
            boolean bothNumeric = isDigits(l) && isDigits(r);

            int verdict = bothNumeric
                    ? new java.math.BigInteger(l).compareTo(new java.math.BigInteger(r))
                    : collator.compare(l, r);
            if (verdict != 0) {
                return verdict;
            }
        }
        // One name is a prefix of the other, so the shorter one comes first.
        return Integer.compare(leftRuns.size(), rightRuns.size());
    }

    /** Cuts a name into alternating runs of digits and non-digits: "item #10" → ["item #", "10"]. */
    private static List<String> runs(String text) {
        List<String> runs = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            boolean digits = Character.isDigit(text.charAt(i));
            int start = i;
            while (i < text.length() && Character.isDigit(text.charAt(i)) == digits) {
                i++;
            }
            runs.add(text.substring(start, i));
        }
        return runs;
    }

    private static boolean isDigits(String run) {
        return !run.isEmpty() && Character.isDigit(run.charAt(0));
    }
}
