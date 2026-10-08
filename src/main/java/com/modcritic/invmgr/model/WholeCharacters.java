package com.modcritic.invmgr.model;

/**
 * Cuts text to a length limit, and mends text from a file, so that no character is left in half.
 *
 * <p><b>What half a character is.</b> Java stores text as 16-bit units, and most characters fit
 * in one. Emoji, and characters from some rarer scripts, take two: a pair of units that only mean
 * something together. {@code String.length()} counts units, and so does the browser's
 * {@code maxlength} the original app relies on, so every text limit in this app (200 for a name,
 * 60 for an ID, 2 for a preset name) is a count of units. A plain {@code substring} at the limit
 * can keep the first unit of a pair and drop the second.
 *
 * <p><b>Why it matters.</b> A unit left without its partner is not a character. It draws as a box,
 * and {@code Files.writeString} refuses to write it: an item name ending in one made every
 * desktop Save fail with "Input length = 1", and nothing on screen said which item was the cause.
 * Typing 199 letters and then an emoji into Name was enough to do it.
 */
public final class WholeCharacters {

    /** What a lone half becomes: U+FFFD, the standard sign for a character that could not be read. */
    public static final char REPLACEMENT = '�';

    private WholeCharacters() {
    }

    /**
     * {@code text} cut to at most {@code maxLength} units, and one shorter when the limit falls
     * between the two halves of a character, so the whole character goes rather than half of it.
     *
     * <p>For text the user is typing, where leaving out the character that did not fit is the
     * expected result. Text from a file goes through {@link #repair} instead; see there.
     */
    public static String cut(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        int end = maxLength;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return text.substring(0, end);
    }

    /**
     * {@code text} with every lone half replaced by {@link #REPLACEMENT}. Whole pairs and every
     * ordinary character are left exactly as they are, so the length does not change.
     *
     * <p>For text read from a save file. JSON can hold a lone half, written as an escape such as
     * {@code \uD800}, and cutting a file's name at the original's limit can make one. Replacing it
     * keeps the length the original shows. How the original draws a lone half depends on the
     * browser: Chromium 153 draws this same sign, and Firefox 156 a small box with the half's code
     * in it (both checked 2026-10-07).
     */
    public static String repair(String text) {
        StringBuilder repaired = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char unit = text.charAt(i);
            if (Character.isHighSurrogate(unit) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                repaired.append(unit).append(text.charAt(i + 1));   // a whole pair
                i++;
            } else {
                repaired.append(Character.isSurrogate(unit) ? REPLACEMENT : unit);
            }
        }
        return repaired.toString();
    }
}
