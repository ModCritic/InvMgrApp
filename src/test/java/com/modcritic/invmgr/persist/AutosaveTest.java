package com.modcritic.invmgr.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The autosave file itself: writing it without a window where a crash leaves rubble, reading it
 * back, and keeping the last few sessions aside.
 */
class AutosaveTest {

    @TempDir
    Path dir;

    /** A clock that starts at a known moment and can be stepped forward. */
    private static Clock at(String isoInstant) {
        return Clock.fixed(Instant.parse(isoInstant), ZoneOffset.UTC);
    }

    private static AppState roomWithOneBox(String name) {
        AppState state = new AppState();
        Item item = new Item();
        item.id = "i1_1";
        item.serial = 1;
        item.dragOrder = 1;
        item.w_in = 24;
        item.l_in = 18;
        item.h_in = 12;
        item.x_px = 40;
        item.y_px = 60;
        item.color = "hsl(200,70%,50%)";
        item.name = name;
        item.customId = "";
        state.items.add(item);
        state.itemCounter = 1;
        state.dragOrderCounter = 1;
        return state;
    }

    // ------------------------------------------------------------------ round trip

    @Test
    void whatIsWrittenComesBack() throws IOException {
        Autosave autosave = new Autosave(dir);
        AppState state = roomWithOneBox("Winter coats");

        autosave.write(SaveFormat.save(state));
        Autosave.Restored restored = autosave.restore();

        assertTrue(restored.hasState());
        assertNull(restored.problem());
        assertEquals(1, restored.state().items.size());
        assertEquals("Winter coats", restored.state().items.get(0).name);
        // Byte equality, not just semantic: the autosave is the same format as an explicit save,
        // so a user can open it in a text editor or hand it to the original HTML app.
        assertEquals(SaveFormat.save(state), Files.readString(autosave.file()));
    }

    @Test
    void aMissingAutosaveIsNotAProblem() {
        Autosave.Restored restored = new Autosave(dir).restore();

        assertFalse(restored.hasState());
        assertNull(restored.problem(), "a first run reported a problem it does not have");
    }

    @Test
    void aDamagedAutosaveIsReportedRatherThanThrown() throws IOException {
        Autosave autosave = new Autosave(dir);
        Files.writeString(autosave.file(), "{\"room\": {\"w\": 12,");   // truncated mid-write

        Autosave.Restored restored = autosave.restore();

        assertFalse(restored.hasState());
        assertNotNull(restored.problem(), "a corrupt autosave loaded as if it were fine");
        // And it is still there. Deleting it would destroy the only copy of whatever the user
        // was working on, which a person with a text editor might well rescue by hand.
        assertTrue(Files.exists(autosave.file()));
    }

    @Test
    void writingCreatesTheDirectory() throws IOException {
        Path nested = dir.resolve("does").resolve("not").resolve("exist");
        Autosave autosave = new Autosave(nested);

        autosave.write(SaveFormat.save(new AppState()));

        assertTrue(Files.isRegularFile(autosave.file()));
    }

    // ------------------------------------------------------------------ atomicity

    @Test
    void aWriteLeavesNoTemporaryFilesBehind() throws IOException {
        Autosave autosave = new Autosave(dir);

        for (int i = 0; i < 5; i++) {
            autosave.write(SaveFormat.save(roomWithOneBox("box " + i)));
        }

        assertEquals(List.of(Autosave.FILE_NAME), namesIn(dir),
                "the directory should hold the autosave and nothing else");
    }

    @Test
    void theTemporaryFileIsWrittenBesideTheTargetSoTheRenameStaysOnOneFilesystem()
            throws IOException {
        // The rename is what makes the write atomic, and a rename is only atomic within a single
        // filesystem. Across one it silently becomes a copy, which has exactly the half-written
        // window the whole design exists to close. Catching the temporary file mid-write is the
        // only way to show where it was put.
        Autosave autosave = new Autosave(dir);
        List<Path> seenDuringWrite = new ArrayList<>();

        Thread writer = new Thread(() -> {
            try {
                autosave.write("x".repeat(8_000_000));      // big enough to still be open below
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        writer.start();
        while (writer.isAlive() && seenDuringWrite.isEmpty()) {
            for (String name : namesIn(dir)) {
                if (name.endsWith(".tmp")) {
                    seenDuringWrite.add(dir.resolve(name));
                }
            }
        }
        try {
            writer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertFalse(seenDuringWrite.isEmpty(),
                "never saw a temporary file, so this test proves nothing about where it goes");
        for (Path temp : seenDuringWrite) {
            assertEquals(dir, temp.getParent(),
                    "the temporary file was written to " + temp.getParent() + ", not beside the "
                            + "autosave in " + dir + " — the rename would cross a filesystem");
        }
    }

    @Test
    void aFailedWriteLeavesThePreviousAutosaveIntact() throws IOException {
        Autosave autosave = new Autosave(dir);
        String good = SaveFormat.save(roomWithOneBox("still here"));
        autosave.write(good);

        // Make the directory unwritable, so creating the temporary file fails. Running as root
        // ignores permissions entirely, so skip rather than pass on a meaningless assertion.
        boolean enforced = dir.toFile().setWritable(false);
        try {
            org.junit.jupiter.api.Assumptions.assumeTrue(enforced && !isWritable(dir),
                    "cannot make a directory unwritable here (running as root?)");
            assertThrows(IOException.class,
                    () -> autosave.write(SaveFormat.save(roomWithOneBox("never written"))));
        } finally {
            dir.toFile().setWritable(true);
        }

        assertEquals(good, Files.readString(autosave.file()),
                "a failed write damaged the autosave that was already there");
    }

    private static boolean isWritable(Path directory) {
        try {
            Path probe = Files.createTempFile(directory, "probe", ".tmp");
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ backups

    @Test
    void aSessionBackupCopiesWhatWasThereBefore() throws IOException {
        Autosave first = new Autosave(dir, at("2026-08-01T09:00:00Z"));
        first.write(SaveFormat.save(roomWithOneBox("yesterday")));

        Autosave second = new Autosave(dir, at("2026-08-02T09:00:00Z"));
        Path backup = second.takeSessionBackup();

        assertNotNull(backup);
        assertEquals("autosave-20260802-090000.json", backup.getFileName().toString());
        assertTrue(Files.readString(backup).contains("yesterday"));
    }

    @Test
    void thereIsNothingToBackUpOnAFirstRun() throws IOException {
        assertNull(new Autosave(dir).takeSessionBackup());
        assertTrue(new Autosave(dir).existingBackups().isEmpty());
    }

    @Test
    void aBackupIsTakenEvenWhenTheAutosaveCannotBeRead() throws IOException {
        // The single most important backup there is. A file that fails to parse is about to be
        // overwritten by this session's first write, and it is the only copy of that work.
        Autosave autosave = new Autosave(dir, at("2026-08-01T12:00:00Z"));
        Files.writeString(autosave.file(), "{ this is not json");

        Path backup = autosave.takeSessionBackup();

        assertNotNull(backup, "a corrupt autosave was thrown away instead of backed up");
        assertEquals("{ this is not json", Files.readString(backup));
    }

    @Test
    void onlyTheLastFiveBackupsAreKept() throws IOException {
        Instant day = Instant.parse("2026-08-01T08:00:00Z");
        List<String> written = new ArrayList<>();

        for (int session = 0; session < 8; session++) {
            Instant when = day.plus(session, ChronoUnit.DAYS);
            Autosave autosave = new Autosave(dir, Clock.fixed(when, ZoneOffset.UTC));
            autosave.write(SaveFormat.save(roomWithOneBox("session " + session)));
            Path backup = autosave.takeSessionBackup();
            written.add(backup.getFileName().toString());
        }

        List<String> kept = new ArrayList<>();
        for (Path backup : new Autosave(dir).existingBackups()) {
            kept.add(backup.getFileName().toString());
        }
        kept.sort(String::compareTo);

        assertEquals(Autosave.BACKUP_COUNT, kept.size(),
                "kept " + kept.size() + " backups, not " + Autosave.BACKUP_COUNT);
        // The five newest, and specifically not the five oldest.
        assertEquals(written.subList(written.size() - Autosave.BACKUP_COUNT, written.size()), kept);
    }

    @Test
    void twoSessionsInTheSameSecondDoNotOverwriteEachOther() throws IOException {
        Clock sameMoment = at("2026-08-01T10:00:00Z");

        Autosave first = new Autosave(dir, sameMoment);
        first.write(SaveFormat.save(roomWithOneBox("one")));
        first.takeSessionBackup();

        Autosave second = new Autosave(dir, sameMoment);
        second.write(SaveFormat.save(roomWithOneBox("two")));
        Path secondBackup = second.takeSessionBackup();

        assertEquals(2, second.existingBackups().size(),
                "the second backup overwrote the first");
        assertTrue(Files.readString(secondBackup).contains("two"));
    }

    @Test
    void rotationOnlyEverDeletesItsOwnBackups() throws IOException {
        // The backup folder lives inside the user's own data directory. Deleting a file we did
        // not write, however unlikely one is to be there, is not ours to do.
        Autosave autosave = new Autosave(dir, at("2026-08-01T08:00:00Z"));
        autosave.write(SaveFormat.save(new AppState()));
        Files.createDirectories(autosave.backupDir());
        Path strangerA = autosave.backupDir().resolve("aaa-notes.txt");
        Path strangerB = autosave.backupDir().resolve("autosave-backup.json");
        Files.writeString(strangerA, "keep me");
        Files.writeString(strangerB, "keep me too");

        for (int session = 0; session < 10; session++) {
            new Autosave(dir, Clock.fixed(
                    Instant.parse("2026-08-01T08:00:00Z").plus(session, ChronoUnit.DAYS),
                    ZoneOffset.UTC)).takeSessionBackup();
        }

        assertTrue(Files.exists(strangerA), "rotation deleted a file it did not write");
        assertTrue(Files.exists(strangerB), "rotation deleted a file it did not write");
        assertEquals(Autosave.BACKUP_COUNT, new Autosave(dir).existingBackups().size());
    }

    @Test
    void theBackupCountIsTheOneThatWasAskedFor() {
        assertEquals(5, Autosave.BACKUP_COUNT,
                "the spec and the user both said five backups");
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> namesIn(Path directory) throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry)) {
                    names.add(entry.getFileName().toString());
                }
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    @Test
    void theFileIsWrittenAsUtf8() throws IOException {
        Autosave autosave = new Autosave(dir);
        AppState state = roomWithOneBox("Bin \"A\" — café ☃");

        autosave.write(SaveFormat.save(state));

        // Read back as bytes and decode explicitly, so a platform default of anything else shows
        // up here rather than as mangled names on a Windows machine.
        String text = new String(Files.readAllBytes(autosave.file()), StandardCharsets.UTF_8);
        assertTrue(text.contains("café ☃"), "non-ASCII names did not survive the write");
        assertEquals("Bin \"A\" — café ☃", autosave.restore().state().items.get(0).name);
    }
}
