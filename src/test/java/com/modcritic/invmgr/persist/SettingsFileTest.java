package com.modcritic.invmgr.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The settings file beside the autosave: what it reads, what it writes, what it says. */
class SettingsFileTest {

    @TempDir
    Path dir;

    private final List<String> log = new ArrayList<>();

    private SettingsFile file() {
        return new SettingsFile(dir, log::add);
    }

    @Test
    @DisplayName("what is saved comes back")
    void roundTrip() throws IOException {
        SettingsFile.Settings on = new SettingsFile.Settings();
        on.hideSystemBars = true;
        file().save(on);
        assertTrue(file().load().hideSystemBars);

        on.hideSystemBars = false;
        file().save(on);
        assertFalse(file().load().hideSystemBars, "and a second save replaces the first");
    }

    @Test
    @DisplayName("it sits beside the autosave, in the folder the app already uses")
    void itLivesBesideTheAutosave() throws IOException {
        file().save(new SettingsFile.Settings());
        assertEquals(dir.resolve(SettingsFile.FILE_NAME), file().file());
        assertTrue(Files.exists(dir.resolve("settings.txt")));
    }

    @Test
    @DisplayName("no file yet means the defaults, and the log says so")
    void noFileMeansDefaults() {
        assertFalse(file().load().hideSystemBars);
        assertEquals(1, log.size());
        assertTrue(log.get(0).startsWith("no settings file yet"), log.get(0));
    }

    @Test
    @DisplayName("a file that cannot be read means the defaults, and the log says why")
    void anUnreadableFileMeansDefaults() throws IOException {
        // A folder where the file should be: it exists, and reading it as text fails.
        Files.createDirectories(dir.resolve(SettingsFile.FILE_NAME));
        assertFalse(file().load().hideSystemBars);
        assertEquals(1, log.size());
        assertTrue(log.get(0).startsWith("could not read"), log.get(0));
    }

    @Test
    @DisplayName("a value that makes no sense keeps the default, and is reported")
    void aBadValueKeepsTheDefault() throws IOException {
        Files.writeString(dir.resolve(SettingsFile.FILE_NAME), "hideSystemBars=maybe\n");
        assertFalse(file().load().hideSystemBars);
        assertEquals(1, log.size());
        assertTrue(log.get(0).contains("should be true or false, not \"maybe\""), log.get(0));
    }

    @Test
    @DisplayName("a newer version's setting, a comment and a blank line are all passed over quietly")
    void whatItDoesNotKnowIsSkipped() throws IOException {
        Files.writeString(dir.resolve(SettingsFile.FILE_NAME),
                "# a comment\n\nsomeFutureSetting=42\nhideSystemBars = TRUE\n");
        assertTrue(file().load().hideSystemBars, "spaces and capitals are forgiven");
        assertTrue(log.isEmpty(), "and nothing there is a problem: " + log);
    }

    @Test
    @DisplayName("a line that is not name=value is reported and skipped")
    void aStrayLineIsReported() throws IOException {
        Files.writeString(dir.resolve(SettingsFile.FILE_NAME), "hideSystemBars\n");
        assertFalse(file().load().hideSystemBars);
        assertTrue(log.get(0).contains("not name=value"), log.get(0));
    }

    @Test
    @DisplayName("saving leaves no temporary file behind")
    void noTemporaryFileIsLeft() throws IOException {
        file().save(new SettingsFile.Settings());
        try (Stream<Path> left = Files.list(dir)) {
            assertEquals(List.of(dir.resolve(SettingsFile.FILE_NAME)), left.toList());
        }
    }
}
