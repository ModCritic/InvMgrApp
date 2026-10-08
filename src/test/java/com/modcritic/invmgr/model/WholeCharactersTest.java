package com.modcritic.invmgr.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WholeCharactersTest {

    /** One emoji: two units, a high half then a low half. */
    private static final String EMOJI = "😀";

    private static final char HIGH = '\uD83D';
    private static final char LOW = '\uDE00';

    @Test
    @DisplayName("text within the limit is returned as it is")
    void shortTextIsUntouched() {
        String text = "abc" + EMOJI;
        assertSame(text, WholeCharacters.cut(text, 5));
        assertSame(text, WholeCharacters.cut(text, 50));
    }

    @Test
    @DisplayName("a limit that falls inside a character drops the whole character")
    void aSplitCharacterGoesWhole() {
        // Five units, the limit after the fourth: a plain substring keeps the high half.
        assertEquals("abc", WholeCharacters.cut("abc" + EMOJI, 4));
        assertEquals("", WholeCharacters.cut(EMOJI, 1));
        assertEquals(EMOJI, WholeCharacters.cut(EMOJI + EMOJI, 3));
    }

    @Test
    @DisplayName("a limit between whole characters cuts exactly there")
    void anOrdinaryCutIsExact() {
        assertEquals("abcd", WholeCharacters.cut("abcdef", 4));
        assertEquals("ab" + EMOJI, WholeCharacters.cut("ab" + EMOJI + "cd", 4));
        assertEquals("", WholeCharacters.cut("abc", 0));
    }

    @Test
    @DisplayName("repair replaces each lone half and keeps whole pairs and the length")
    void repairReplacesOnlyLoneHalves() {
        char r = WholeCharacters.REPLACEMENT;
        assertEquals("ab" + r, WholeCharacters.repair("ab" + HIGH), "a high half at the end");
        assertEquals(r + "ab", WholeCharacters.repair(LOW + "ab"), "a low half at the start");
        assertEquals("a" + r + "b", WholeCharacters.repair("a" + HIGH + "b"),
                "a high half followed by an ordinary character");
        assertEquals("" + r + r, WholeCharacters.repair("" + LOW + HIGH),
                "the two halves in the wrong order are two lone halves");
        assertEquals("" + r + EMOJI, WholeCharacters.repair(HIGH + EMOJI),
                "a lone high half right before a whole pair leaves the pair whole");
        assertEquals("a" + EMOJI + "b", WholeCharacters.repair("a" + EMOJI + "b"));
        assertEquals("", WholeCharacters.repair(""));
    }
}
