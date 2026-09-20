package com.modcritic.invmgr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which topics {@code -Dinvmgr.verbose} switches on, and what it says about a word it does not know.
 *
 * <p>Parsing is a plain function taking its input and its complaints as arguments, the same shape as
 * {@code Device.isAndroid} and {@code Launcher.shouldForceGpu}, so both halves can be checked
 * without a command line and without capturing what the app prints.
 */
class VerboseTest {

    @Test
    @DisplayName("nothing is on unless somebody asks")
    void silentByDefault() {
        // The whole point: a program that logs at people who did not ask has trained them to
        // ignore it by the time something goes wrong.
        assertTrue(Verbose.parse(null, complainer()).isEmpty());
        assertTrue(Verbose.parse("", complainer()).isEmpty());
        assertTrue(Verbose.parse("   ", complainer()).isEmpty());
    }

    @Test
    @DisplayName("a topic's own word turns it on")
    void oneTopic() {
        assertEquals(Set.of(Verbose.Topic.THREE_D), Verbose.parse("3d", complainer()));
    }

    @Test
    @DisplayName("case and spaces do not matter, because a command line is typed by hand")
    void forgivingAboutShape() {
        assertEquals(Set.of(Verbose.Topic.THREE_D), Verbose.parse("3D", complainer()));
        assertEquals(Set.of(Verbose.Topic.THREE_D), Verbose.parse("  3d  ", complainer()));
        assertEquals(Set.of(Verbose.Topic.THREE_D), Verbose.parse("3d,,", complainer()));
    }

    @Test
    @DisplayName("all turns on every topic there is, including ones added later")
    void allMeansAll() {
        // Asserted against the enum rather than against a list written here, so a topic added
        // tomorrow is covered by `all` without anyone remembering to come back.
        assertEquals(Set.of(Verbose.Topic.values()), Verbose.parse("all", complainer()));
    }

    @Test
    @DisplayName("⚠ a word it does not know is reported, not ignored")
    void unknownWordsAreReported() {
        // THE FAILURE THIS PREVENTS IS THE WHOLE REASON THE CLASS EXISTS. Somebody typing
        // -Dinvmgr.verbose=three-d and seeing nothing at all concludes the app has no logging and
        // files the report without it. Silence is indistinguishable from "there was nothing to
        // say", so an unknown word has to say so.
        List<String> complaints = new ArrayList<>();
        Set<Verbose.Topic> on = Verbose.parse("three-d", complaints::add);

        assertTrue(on.isEmpty(), "a word it does not know must not turn anything on");
        assertEquals(List.of("three-d"), complaints);
    }

    @Test
    @DisplayName("a good word still works when a bad one is beside it")
    void oneBadWordDoesNotSpoilTheRest() {
        List<String> complaints = new ArrayList<>();
        Set<Verbose.Topic> on = Verbose.parse("3d,nonsense", complaints::add);

        assertEquals(Set.of(Verbose.Topic.THREE_D), on,
                "the topic that was spelled correctly should still be on");
        assertEquals(List.of("nonsense"), complaints);
    }

    @Test
    @DisplayName("the complaint names the words that would have worked")
    void theComplaintIsUseful() {
        // A complaint that says only "bad topic" leaves the reader guessing, which is how the
        // report arrives without the log a second time.
        String known = Verbose.knownWords();
        assertTrue(known.contains(Verbose.ALL), "got " + known);
        for (Verbose.Topic topic : Verbose.Topic.values()) {
            assertTrue(known.contains(topic.word), topic + " missing from " + known);
        }
    }

    @Test
    @DisplayName("every topic's word is lower case and unique")
    void thewordsAreUsable() {
        // Parsing lower-cases what it is given, so a topic declared with a capital in it could
        // never be switched on and nothing would report that. Two topics sharing a word is the
        // same failure with a different cause.
        Set<String> seen = new java.util.HashSet<>();
        for (Verbose.Topic topic : Verbose.Topic.values()) {
            assertEquals(topic.word.toLowerCase(java.util.Locale.ROOT), topic.word,
                    topic + " has a word that could never be matched");
            assertFalse(topic.word.isBlank(), topic + " has no word at all");
            assertTrue(seen.add(topic.word), topic + " shares its word with another topic");
        }
        assertFalse(seen.contains(Verbose.ALL), "a topic may not be called " + Verbose.ALL);
    }

    private static java.util.function.Consumer<String> complainer() {
        return word -> {
            throw new AssertionError("did not expect a complaint about \"" + word + "\"");
        };
    }
}
