package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The privacy policy page Google Play links to, checked against the app's own.
 *
 * <p>The page lives in {@code site/} and is published from the {@code pages} branch at
 * invmgr.asterkazika.com. It says what the app says, so the two are compared here word for word:
 * the app's text is the one the user approved, and a page that drifted from it would be a policy
 * the app does not keep.
 */
class SitePolicyTest {

    private static String text(String page) throws IOException {
        String html = Files.readString(Path.of("site", page));
        // the page's words as a reader sees them: tags and comments out, runs of space as one
        return html.replaceAll("(?s)<!--.*?-->", " ").replaceAll("(?s)<[^>]*>", " ")
                .replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("the page says what the app's own privacy policy says, every paragraph")
    void thePolicyMatchesTheApp() throws IOException {
        String page = text("privacy/index.html");
        for (String paragraph : AppInfo.privacyPolicy(true, Path.of("/unused"))) {
            assertTrue(page.contains(paragraph), "the page is missing: " + paragraph);
        }
    }

    @Test
    @DisplayName("it is labeled as a privacy policy, which Google asks for")
    void itIsLabeled() throws IOException {
        String html = Files.readString(Path.of("site", "privacy", "index.html"));
        assertTrue(html.contains("<title>InvMgr Privacy Policy</title>"));
        assertTrue(html.contains("<h1>InvMgr Privacy Policy</h1>"));
    }

    @Test
    @DisplayName("the front page carries the app's description and its source")
    void theFrontPage() throws IOException {
        String page = text("index.html");
        assertTrue(page.contains(AppInfo.DESCRIPTION), page);
        assertTrue(Files.readString(Path.of("site", "index.html")).contains(AppInfo.SOURCE_URL));
    }

    @Test
    @DisplayName("neither page loads anything from anywhere: no scripts, no fonts, no images")
    void nothingIsLoaded() throws IOException {
        for (String page : new String[] {"index.html", "privacy/index.html"}) {
            String html = Files.readString(Path.of("site", page)).toLowerCase();
            for (String loader : new String[] {"<script", "src=", "<link", "@import", "url(",
                    "<img", "<iframe"}) {
                assertFalse(html.contains(loader), page + " loads something: " + loader);
            }
        }
    }

    @Test
    @DisplayName("GitHub Pages is told the domain the DNS record points at")
    void theDomain() throws IOException {
        assertEquals("invmgr.asterkazika.com", Files.readString(Path.of("site", "CNAME")).strip());
    }
}
