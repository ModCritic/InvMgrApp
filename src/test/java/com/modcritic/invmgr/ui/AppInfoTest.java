package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the About pages say, checked without a window. */
class AppInfoTest {

    @Test
    @DisplayName("the version About shows is the one pom.xml declares")
    void theVersionComesFromThePom() throws IOException {
        // pom.xml is the one place the version is written. Read it the way Maven does: the
        // project's own <version>, the first one after the project's own <artifactId>.
        String pom = Files.readString(Path.of("pom.xml"));
        Matcher project = Pattern.compile(
                "<artifactId>invmgr</artifactId>\\s*(?:<!--(?s:.*?)-->\\s*)?<version>([^<]+)</version>")
                .matcher(pom);
        assertTrue(project.find(), "could not find the project's own version in pom.xml");
        assertEquals(project.group(1).strip(), AppInfo.VERSION);
        assertEquals("1.0.0", AppInfo.VERSION, "the user's first version, 2026-09-24");
    }

    @Test
    @DisplayName("on a phone the policy says uninstalling deletes everything")
    void thePhonePolicy() {
        List<String> policy = AppInfo.privacyPolicy(true, Path.of("/unused"));
        assertEquals(4, policy.size());
        assertTrue(policy.get(1).endsWith("Uninstalling the app deletes everything it stored."));
        assertFalse(String.join(" ", policy).contains("/unused"),
                "the phone names no folder: Android's is private and deleted with the app");
    }

    @Test
    @DisplayName("on a desktop the policy names the folder the autosave stays in")
    void theDesktopPolicy() {
        Path folder = Path.of("/home/someone/.local/share/InvMgr");
        List<String> policy = AppInfo.privacyPolicy(false, folder);
        assertTrue(policy.get(1).endsWith("Deleting the app leaves its saved room in " + folder
                + "; delete the folder to remove it."), policy.get(1));
        assertFalse(String.join(" ", policy).contains("Uninstalling"),
                "the phone's sentence is not true on a desktop");
    }

    @Test
    @DisplayName("the policy names the developer and a contact, as Google asks")
    void theDeveloperAndContact() {
        for (boolean phone : new boolean[] {true, false}) {
            List<String> policy = AppInfo.privacyPolicy(phone, Path.of("/x"));
            assertEquals("Developer: Asteroid. Contact: contact@asterkazika.com.", policy.get(3));
            assertEquals(AppInfo.FEEDBACK_POLICY, policy.get(2), "M8: where feedback goes");
            assertTrue(policy.get(0).startsWith("InvMgr collects nothing."));
        }
    }

    @Test
    @DisplayName("a feedback email is addressed, and its words encoded the way mailto wants")
    void theFeedbackEmail() {
        String mailto = AppInfo.feedbackMailto("Hi there\nsecond line & more?");
        assertTrue(mailto.startsWith("mailto:contact@asterkazika.com?subject="), mailto);
        assertTrue(mailto.contains("subject=InvMgr%20" + AppInfo.VERSION + "%20feedback"), mailto);
        // RFC 6068: a space is %20, never the + a web form uses, and a line break is %0D%0A.
        assertTrue(mailto.endsWith("&body=Hi%20there%0D%0Asecond%20line%20%26%20more%3F"), mailto);
    }

    @Test
    @DisplayName("a feedback issue fills GitHub's own title and body parameters")
    void theFeedbackIssue() {
        String url = AppInfo.feedbackIssueUrl("The slider sticks\nwhen the room is metric");
        assertEquals("https://github.com/ModCritic/InvMgrApp/issues/new?title=The%20slider%20sticks"
                + "&body=The%20slider%20sticks%0Awhen%20the%20room%20is%20metric", url);
    }

    @Test
    @DisplayName("the GitHub button's limit is the address, which a full box can pass or not")
    void theIssueAddressLimit() {
        String plain = "x".repeat(AppInfo.FEEDBACK_MAX_LENGTH);
        assertTrue(AppInfo.fitsInIssueUrl(plain), "a full box of plain letters fits");
        String euros = "\u20ac".repeat(AppInfo.FEEDBACK_MAX_LENGTH);
        assertFalse(AppInfo.fitsInIssueUrl(euros), "a full box of euro signs is over 18,000 characters");
        // Either side of the line, one letter apart. A line of more than 60 letters is titled by
        // 60 characters, so the address is the fixed part, the 60, and the letters.
        int fixed = AppInfo.feedbackIssueUrl("").length() - "Feedback".length();
        String exact = "x".repeat(AppInfo.ISSUE_URL_MAX - fixed - 60);
        assertEquals(AppInfo.ISSUE_URL_MAX, AppInfo.feedbackIssueUrl(exact).length(), "setup");
        assertTrue(AppInfo.fitsInIssueUrl(exact));
        assertFalse(AppInfo.fitsInIssueUrl(exact + "x"));
    }

    @Test
    @DisplayName("an issue is titled by the message's first line, cut to 60 characters")
    void theIssueTitle() {
        assertEquals("Short", AppInfo.issueTitle("\n  Short  \nrest"));
        assertEquals("Feedback", AppInfo.issueTitle("  \n "));
        String longLine = "x".repeat(80);
        assertEquals("x".repeat(57) + "...", AppInfo.issueTitle(longLine));
    }

    @Test
    @DisplayName("the credit is the FreeType Project's own wording")
    void theCredit() {
        assertEquals("This software is based in part on the work of the FreeType Team.",
                AppInfo.CREDITS.get(0));
        assertTrue(AppInfo.CREDITS.get(1).contains("© 2025 The FreeType Project"));
    }
}
