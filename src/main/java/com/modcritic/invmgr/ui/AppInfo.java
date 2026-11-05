package com.modcritic.invmgr.ui;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * What the About pages say: the version, the description, the source link and the privacy
 * policy. The Licenses page's words are in {@link LicenseNotices}.
 *
 * <p>Gathered here, away from the window that shows them, for two reasons. The words are the
 * user's and Google's business as much as the interface's, so they belong somewhere they can be
 * read in one go. And a test can check them without opening a window.
 *
 * <p>In {@code ui} because the About pages are its only reader, and so that {@code ui} still needs
 * nothing from the packages above it: the one thing here that depends on the computer, the
 * autosave folder the desktop privacy policy names, is handed in by {@code App}.
 *
 * <p><b>The version is read from the build, never written here.</b> {@code pom.xml}'s own
 * {@code <version>} is the only place it exists; Maven copies it into {@code app.properties} as it
 * builds, and Substrate copies the same value into the Android manifest, which is what the Play
 * Store shows. So About and Play cannot disagree.
 */
public final class AppInfo {

    private static final String PROPERTIES = "/com/modcritic/invmgr/app.properties";

    /** The app's version, as the build that made it says. */
    public static final String VERSION = readVersion();

    /** The user's description of the app, 2026-09-24. */
    public static final String DESCRIPTION = "A top-down inventory manager for keeping track of"
            + " boxes in a 2D simulation of your space.";

    /** The repository. Feedback's GitHub button opens a new issue there. */
    public static final String SOURCE_URL = "https://github.com/ModCritic/InvMgrApp";

    /**
     * This version's Release page, which About's Source code entry and the Licenses page's source
     * link both open.
     *
     * <p>The Release, not the repository's front page, because the repository moves on and a
     * Release stays put: it holds the source of exactly this version and the source of the
     * GPL-licensed code inside it, which the GPL asks to be available. Every Release is tagged
     * {@code v} and the version, as {@code v1.0.1} was.
     */
    public static final String RELEASE_URL = SOURCE_URL + "/releases/tag/v" + VERSION;

    /** Where Feedback's Email button addresses the message: the user's choice, 2026-09-25. */
    public static final String FEEDBACK_EMAIL = "contact@asterkazika.com";

    /**
     * The most a feedback message may hold: room for a bug report, and short enough that one written
     * in plain English, spaces and all, fits in a GitHub address (see {@link #ISSUE_URL_MAX}). It is
     * a character count, so it cannot promise that for every message. A character outside plain
     * ASCII grows up to nine times when encoded, and {@link #fitsInIssueUrl} is the real check.
     */
    public static final int FEEDBACK_MAX_LENGTH = 2000;

    /**
     * The longest GitHub new-issue address the GitHub button will hand over, in characters.
     *
     * <p>Measured with curl on 2026-09-25, signed out, as a sender without a session would be:
     * GitHub redirects to its login page up to an address of about 6,920 characters, answers
     * {@code 500} beyond that, and drops the connection from about 8,100. The edge was the same
     * total length for plain letters (6,851 of them), for {@code é} (1,143) and for {@code €}
     * (761), so it is the address that counts, not the character count. 6,000 leaves 13% for
     * GitHub to move it. Nothing here re-checks that GitHub still draws the line in the same
     * place.
     */
    static final int ISSUE_URL_MAX = 6000;

    private AppInfo() {
    }

    /** The subject line of a feedback email, which says which version it came from. */
    public static String feedbackSubject() {
        return "InvMgr " + VERSION + " feedback";
    }

    /**
     * A {@code mailto:} address with the message in it, for a desktop's mail program.
     *
     * <p>Encoded as RFC 6068 asks: every character outside the plain unreserved set
     * percent-encoded, a space as {@code %20} rather than the {@code +} a web form would use, and
     * each line break as {@code %0D%0A}.
     */
    public static String feedbackMailto(String message) {
        return "mailto:" + FEEDBACK_EMAIL + "?subject=" + encode(feedbackSubject())
                + "&body=" + encode(message.replace("\r\n", "\n").replace("\n", "\r\n"));
    }

    /**
     * GitHub's new-issue page for the repository, with the title and the message filled in.
     *
     * <p>The {@code title} and {@code body} query parameters are GitHub's own ("Creating an issue
     * from a URL query", docs.github.com, read 2026-09-25). The sender still has to be signed in
     * to GitHub and press Submit there; this only fills the form.
     */
    public static String feedbackIssueUrl(String message) {
        return SOURCE_URL + "/issues/new?title=" + encode(issueTitle(message))
                + "&body=" + encode(message);
    }

    /** Whether this message's GitHub address is short enough for GitHub to open it. */
    public static boolean fitsInIssueUrl(String message) {
        return feedbackIssueUrl(message).length() <= ISSUE_URL_MAX;
    }

    /**
     * The issue's title: the message's first line, cut to 60 characters, or "Feedback" if there is
     * none. Package-private for the test.
     */
    static String issueTitle(String message) {
        for (String line : message.split("\\R")) {
            String first = line.strip();
            if (!first.isEmpty()) {
                return first.length() <= 60 ? first : first.substring(0, 57) + "...";
            }
        }
        return "Feedback";
    }

    /** Percent-encoding that is right in both a mailto address and a web address. */
    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * The privacy policy, a paragraph to a string, in the user's approved wording (2026-09-24).
     *
     * <p><b>One sentence differs by platform</b>, and on purpose. On a phone, uninstalling the
     * app deletes everything it stored, because Android deletes an app's private folder with it.
     * On a desktop that is not true: the autosave stays in the folder {@code persist/AppDataDir}
     * picks after the app is deleted, so the desktop names that folder instead. The user chose
     * that wording on 2026-09-24.
     *
     * <p>Google's User Data policy asks for a policy that is labeled as one, names the developer,
     * gives a contact point, and says how data is kept and deleted. The third paragraph is the
     * first two of those; the second is the last.
     *
     * @param phone      whether this is the phone build
     * @param dataFolder where the autosave lives on this computer; ignored on a phone
     */
    public static List<String> privacyPolicy(boolean phone, Path dataFolder) {
        String stays = phone
                ? "Uninstalling the app deletes everything it stored."
                : "Deleting the app leaves its saved room in " + dataFolder
                        + "; delete the folder to remove it.";
        return List.of(
                "InvMgr collects nothing. It makes no network connections, shows no ads, and has"
                        + " no analytics or tracking. It asks for no permissions.",
                "Your rooms are kept on this device. They leave it only when you save or export a"
                        + " file and move it off this device yourself. " + stays,
                FEEDBACK_POLICY,
                "Developer: Asteroid. Contact: contact@asterkazika.com.");
    }

    /**
     * The Feedback entry's sentence for the privacy policy. ⚠ A DRAFT until the user approves it:
     * the policy is theirs, and the other paragraphs are their approved wording. It is true
     * because Feedback hands the message to another app and never sends anything itself.
     */
    static final String FEEDBACK_POLICY = "Feedback you choose to send leaves through your own mail"
            + " app or your own GitHub account, not through InvMgr.";

    /**
     * Reads the version out of the file the build filled in.
     *
     * <p>Fails loudly, like the fonts and the icons do. A file that is missing, or that still
     * says {@code ${project.version}} because the filtering did not run, is a broken build, and
     * an About page reporting a wrong version would be worse than one that stops the build's
     * tests.
     */
    private static String readVersion() {
        try (InputStream in = AppInfo.class.getResourceAsStream(PROPERTIES)) {
            if (in == null) {
                throw new IllegalStateException("app.properties is missing from the build: check"
                        + " the <resources> block in pom.xml and, on a phone, <resourcesList>");
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version", "").strip();
            if (version.isEmpty() || version.contains("${")) {
                throw new IllegalStateException("app.properties was not filtered, it says \""
                        + version + "\": check the filtered <resource> in pom.xml");
            }
            return version;
        } catch (IOException e) {
            throw new IllegalStateException("could not read app.properties", e);
        }
    }
}
