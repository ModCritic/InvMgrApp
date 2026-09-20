package com.modcritic.invmgr;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Extra logging, off unless somebody asks for it on the command line.
 *
 * <p><b>What it is for.</b> Asked for by the user 2026-09-15, looking ahead to this being a program
 * other people run: <i>"issues on the GitHub repo in the future could have serious logs to look
 * at."</i> A bug report that arrives with real numbers in it is worth several rounds of asking what
 * happened.
 *
 * <pre>
 *   java -jar InvMgr.jar                        nothing extra, as always
 *   java -Dinvmgr.verbose=3d -jar InvMgr.jar    the 3D view talks
 *   java -Dinvmgr.verbose=all -jar InvMgr.jar   everything talks
 * </pre>
 *
 * <p><b>This is not the startup report.</b> The dozen {@code InvMgr:} lines the app already prints
 * (the Java and JavaFX versions, the screen, whether 3D is available) stay exactly as they are and
 * are always on. They are short, they are the first thing anyone needs, and the point of this class
 * is the stuff that would be noise beside them.
 *
 * <h2>Three rules it is built to</h2>
 *
 * <p><b>It costs nothing when it is off.</b> {@link #log} takes a supplier rather than a string, so
 * the message is not built unless somebody is listening. A call site that concatenates its message
 * before the check is paying for logging that nobody asked for.
 *
 * <p><b>No new dependency.</b> Everything here is {@code System.out} and the standard library, in
 * the same shape and with the same {@code InvMgr:} prefix as the lines already printed. §8 and
 * OD-3 make adding a logging library a decision rather than a convenience, and nothing here needs
 * one.
 *
 * <p><b>⚠ It must not print what the user made.</b> These lines exist to be pasted into a public
 * bug report. Item names, the text in a search box and the path a save file came from are the
 * user's, and §6 says what they make stays local unless they export it deliberately. <b>Log
 * measurements, counts and shapes; never contents.</b> "42 items" is a diagnostic, "Mum's china"
 * is somebody's business.
 *
 * <h2>Adding a topic</h2>
 *
 * <p>Add a constant to {@link Topic} with the word that turns it on, and use it. The topics here
 * are exactly the ones with something to say; an empty topic is a promise nothing keeps, which is
 * the same rule §3 puts on MANUAL.md sections.
 */
public final class Verbose {

    /** The property that switches this on: a comma-separated list of topics, or {@code all}. */
    public static final String PROPERTY = "invmgr.verbose";

    /** The word that turns everything on at once. */
    public static final String ALL = "all";

    /** What can be asked to talk. */
    public enum Topic {

        /**
         * The 3D view: how long its transitions take, frame by frame, and what it decided about
         * the machine it is running on.
         *
         * <p>First one here because it is the one being asked about. M6.7 item 3 could not be
         * finished from this end: every number in it comes from a machine with no graphics card,
         * and the report was about a virtual machine running Mesa's software OpenGL. This is how
         * that machine reports its own numbers instead.
         */
        THREE_D("3d"),

        /**
         * Input to frame: how long the app takes to show the result of a tap, a drag or a key.
         *
         * <p>Added at M6.7b, whose whole suite reads these lines back. See {@link LatencyClock},
         * and note that this is the one topic that <b>changes the app while it is on</b>: it runs
         * an {@code AnimationTimer}, so the app pulses every frame whether or not anything moved.
         */
        LATENCY("latency");

        /** The word that turns this topic on. */
        public final String word;

        Topic(String word) {
            this.word = word;
        }
    }

    /**
     * The topics switched on, worked out once.
     *
     * <p>Read at class-load rather than per call, because the property cannot change while the
     * program runs and asking the system for it on every frame would be its own performance bug.
     */
    private static final Set<Topic> ON =
            parse(System.getProperty(PROPERTY), Verbose::complain);

    private Verbose() {
    }

    /** Whether anyone asked for this topic. Cheap enough to call from a loop. */
    public static boolean on(Topic topic) {
        return ON.contains(topic);
    }

    /**
     * Prints one line for a topic, if that topic is on.
     *
     * <p>The message is a supplier so that nothing is built when nobody is listening; see the
     * class documentation, and <b>do not build the string at the call site instead</b>.
     */
    public static void log(Topic topic, Supplier<String> message) {
        if (ON.contains(topic)) {
            System.out.println("InvMgr[" + topic.word + "] " + message.get());
        }
    }

    /**
     * Works out which topics a property value asks for.
     *
     * <p>Takes its input and its complaints as arguments so both halves can be tested without a
     * command line and without capturing {@code System.out}.
     *
     * <p><b>⚠ An unknown word is reported, loudly, rather than ignored.</b> Somebody who types
     * {@code -Dinvmgr.verbose=3D} or {@code =three-d} and sees nothing at all will conclude the
     * app has no logging and file the bug report without it, which is the exact failure this
     * class exists to prevent. Silence is indistinguishable from "there was nothing to say".
     *
     * @param value what the property said, or null
     * @param complain told about each word that means nothing
     */
    static Set<Topic> parse(String value, Consumer<String> complain) {
        // NULL ONLY, AND THE MISSING isBlank() IS THE MUTATION SWEEP'S DOING. It read
        // `value == null || value.isBlank()`, which looks obviously right and does nothing: every
        // blank form reaches the loop as parts that trim to empty and are skipped there, so the
        // answer was the same either way. "" and "   " each split into one empty part, "," into
        // none at all. Checked by running it rather than argued about, after the sweep reported
        // deleting the isBlank half as SURVIVED. Same shape as the setSpecularColor(null) call
        // JfxRenderer3D used to carry. The null check is real: split would throw on it.
        if (value == null) {
            return EnumSet.noneOf(Topic.class);
        }
        Set<Topic> wanted = EnumSet.noneOf(Topic.class);
        Set<String> unknown = new LinkedHashSet<>();
        for (String word : value.split(",")) {
            String trimmed = word.trim().toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty()) {
                continue;
            }
            if (ALL.equals(trimmed)) {
                wanted.addAll(EnumSet.allOf(Topic.class));
                continue;
            }
            Topic found = null;
            for (Topic topic : Topic.values()) {
                if (topic.word.equals(trimmed)) {
                    found = topic;
                    break;
                }
            }
            if (found == null) {
                unknown.add(trimmed);
            } else {
                wanted.add(found);
            }
        }
        for (String word : unknown) {
            complain.accept(word);
        }
        return wanted;
    }

    /**
     * What is switched on, for the startup report, or empty when nothing is.
     *
     * <p><b>Calling this at startup is what makes a typo report itself.</b> The topics are worked out
     * when this class is first touched, and a complaint about an unknown word is printed then. If
     * nothing touched it until the 3D view opened, somebody who mistyped the flag and never
     * pressed the 3D button would see no logging and no complaint either, which is the exact
     * failure this class exists to prevent, arriving by the back door.
     */
    public static String summary() {
        StringBuilder on = new StringBuilder();
        for (Topic topic : Topic.values()) {
            if (ON.contains(topic)) {
                on.append(on.length() == 0 ? "" : ",").append(topic.word);
            }
        }
        return on.toString();
    }

    /** The words this build understands, for a complaint that is worth reading. */
    static String knownWords() {
        StringBuilder words = new StringBuilder(ALL);
        for (Topic topic : Topic.values()) {
            words.append(", ").append(topic.word);
        }
        return words.toString();
    }

    private static void complain(String word) {
        System.out.println("InvMgr: ⚠ -D" + PROPERTY + " does not understand \"" + word
                + "\". Known: " + knownWords() + ".");
    }
}
