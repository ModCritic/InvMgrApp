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
 * it would be wrong: the export format is a user-facing file format the original app committed
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

    /** A dimension as the user should see it: centimeters in metric mode, inches otherwise. */
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
     * <p>Note the <b>two</b> spaces after the name and before {@code base:}; they are the only
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
     * header rather than on the search row, so it exports the list as a whole; a filtered
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
     * <p>Three rules, the first two from the original's
     * {@code localeCompare(..., numeric, base)}:
     *
     * <ul>
     *   <li><b>Numbers count as numbers.</b> "item #2" comes before "item #10", where plain
     *       text ordering would put "#10" first because "1" is before "2".
     *   <li><b>Case and accents are ignored.</b> "bin" and "Bin" sort together instead of all
     *       the capitals coming first.
     *   <li><b>Spaces and hyphens count.</b> "a b" comes before "ab", and a space comes before
     *       a hyphen, so "a b" comes before "a-b".
     * </ul>
     *
     * <p>Java has no single call for this, so the names are cut into tokens and compared one
     * token at a time. See {@link #compareNames} for why the third rule needs the tokenizer
     * rather than a stronger {@link Collator} strength.
     *
     * <p><b>One deliberate difference from the original, decided by the user 2026-09-11:</b>
     * leading spaces are ignored, so " z" sorts under z and not above every other name. The
     * original counts them, which puts anything typed with a leading space at the very top.
     * Everything else here matches the original answer for answer. See §5.5 D-24.
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
        List<String> leftTokens = tokens(left);
        List<String> rightTokens = tokens(right);

        int shared = Math.min(leftTokens.size(), rightTokens.size());
        for (int i = 0; i < shared; i++) {
            int verdict = compareToken(leftTokens.get(i), rightTokens.get(i));
            if (verdict != 0) {
                return verdict;
            }
        }
        // One name is a prefix of the other, so the shorter one comes first.
        return Integer.compare(leftTokens.size(), rightTokens.size());
    }

    /**
     * Compares one token against the token in the same position of the other name.
     *
     * <p>A separator beats anything else, which is the whole reason for the tokenizer: in the
     * original's ordering a space or hyphen is a real character that sorts <em>before</em>
     * letters, so "-b" comes before "a". Java's {@link Collator} cannot be asked for that.
     * At PRIMARY strength it drops separators entirely ("a b" and "ab" come back equal), and
     * every stronger strength also stops ignoring case and accents, which rule two needs.
     */
    private static int compareToken(String left, String right) {
        if (isSeparator(left) && isSeparator(right)) {
            return Integer.compare(separatorRank(left), separatorRank(right));
        }
        if (isSeparator(left)) {
            return -1;
        }
        if (isSeparator(right)) {
            return 1;
        }
        if (isDigits(left) && isDigits(right)) {
            return new java.math.BigInteger(left).compareTo(new java.math.BigInteger(right));
        }
        return BASE_LETTERS.compare(left, right);
    }

    /**
     * PRIMARY strength is Java's "base letters only": it treats a, A and á as the same letter,
     * which is what {@code sensitivity: 'base'} means in the original. It also folds the German
     * sharp s, so "straße" and "strasse" sort together exactly as the original has them.
     *
     * <p>Shared rather than built per call. {@link Collator} is not thread-safe for
     * <em>configuration</em>, but nothing reconfigures this one after class initialization, and
     * {@code compare} on a fixed collator is safe to call from anywhere.
     */
    private static final Collator BASE_LETTERS = baseLetters();

    private static Collator baseLetters() {
        Collator collator = Collator.getInstance(Locale.ROOT);
        collator.setStrength(Collator.PRIMARY);
        return collator;
    }

    private static boolean isSeparator(String token) {
        return token.length() == 1 && isSeparator(token.charAt(0));
    }

    private static boolean isSeparator(char c) {
        return c == ' ' || c == '-';
    }

    /** A space sorts before a hyphen, so "a b" comes before "a-b". Checked against the original. */
    private static int separatorRank(String token) {
        return token.charAt(0) == ' ' ? 0 : 1;
    }

    /**
     * Cuts a name into the three kinds of token the comparison treats differently: one
     * separator, a run of digits, or a run of anything else. "box 2-a" becomes
     * ["box", " ", "2", "-", "a"].
     *
     * <p><b>Leading spaces are dropped first</b>, which is the one departure from the original
     * (§5.5 D-24). Only leading ones: a trailing or interior space is a token like any other,
     * so "a " still sorts after "a" the way the original has it.
     */
    private static List<String> tokens(String text) {
        int i = 0;
        while (i < text.length() && text.charAt(i) == ' ') {
            i++;
        }
        List<String> tokens = new ArrayList<>();
        while (i < text.length()) {
            if (isSeparator(text.charAt(i))) {
                tokens.add(String.valueOf(text.charAt(i)));
                i++;
                continue;
            }
            boolean digits = Character.isDigit(text.charAt(i));
            int start = i;
            while (i < text.length()
                    && !isSeparator(text.charAt(i))
                    && Character.isDigit(text.charAt(i)) == digits) {
                i++;
            }
            tokens.add(text.substring(start, i));
        }
        return tokens;
    }

    private static boolean isDigits(String token) {
        return !token.isEmpty() && Character.isDigit(token.charAt(0));
    }
}
