package com.modcritic.invmgr.persist;

import com.modcritic.invmgr.model.AppState;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The app's own copy of the current room, kept without the user asking.
 *
 * <p>This is deliberately <b>not</b> the user's file. Their explicit Save still writes wherever
 * they chose and this never touches it; the two are separate on purpose, so that a crash cannot
 * damage a document the user believes they control. What lives here is the app's working state,
 * restored silently the next time it opens.
 *
 * <p>The file is written the only way a file can be replaced without a window where a crash
 * leaves rubble: the new text goes to a temporary file <em>in the same directory</em>, is forced
 * to the physical disk, and is then renamed over the target. A rename within one filesystem is
 * atomic — a reader sees either the whole old file or the whole new one, never a mixture. This
 * is why the temporary file cannot go in the system temp directory: a rename across filesystems
 * is a copy, and a copy has exactly the half-written window being avoided.
 *
 * <p>One backup is kept per app session rather than per write. Writes happen every few seconds
 * and five of those would cover the last quarter-minute, which only protects against a bad
 * autosave; five sessions can span weeks, which protects against a bad afternoon.
 */
public final class Autosave {

    /** How many timestamped backups to keep. Oldest is deleted first. */
    public static final int BACKUP_COUNT = 5;

    public static final String FILE_NAME = "autosave.json";
    public static final String BACKUP_DIR_NAME = "backups";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * Matches only the backups this class writes.
     *
     * <p>Rotation deletes by this pattern and nothing else. The backup folder is inside the
     * user's own data directory and deleting a file we did not create there would be
     * indefensible, however unlikely it is that one appears.
     */
    private static final Pattern BACKUP_NAME =
            Pattern.compile("^autosave-\\d{8}-\\d{6}(-\\d+)?\\.json$");

    private final Path dir;
    private final Path file;
    private final Path backupDir;
    private final Clock clock;

    public Autosave(Path dir) {
        this(dir, Clock.systemDefaultZone());
    }

    /** @param clock injected so the backup tests can name files at chosen times */
    public Autosave(Path dir, Clock clock) {
        this.dir = dir;
        this.file = dir.resolve(FILE_NAME);
        this.backupDir = dir.resolve(BACKUP_DIR_NAME);
        this.clock = clock;
    }

    public Path file() {
        return file;
    }

    public Path backupDir() {
        return backupDir;
    }

    /** Whether there is anything to restore. Says nothing about whether it can be read. */
    public boolean exists() {
        return Files.isRegularFile(file);
    }

    // ------------------------------------------------------------------ reading

    /** What {@link #restore} found. */
    public static final class Restored {

        private final AppState state;
        private final String problem;

        private Restored(AppState state, String problem) {
            this.state = state;
            this.problem = problem;
        }

        /** Nothing was saved. The normal case on a first run, and not a problem. */
        static Restored nothing() {
            return new Restored(null, null);
        }

        static Restored of(AppState state) {
            return new Restored(state, null);
        }

        static Restored broken(String problem) {
            return new Restored(null, problem);
        }

        public boolean hasState() {
            return state != null;
        }

        public AppState state() {
            return state;
        }

        /** A sentence naming what went wrong, or {@code null} when nothing did. */
        public String problem() {
            return problem;
        }
    }

    /**
     * Reads back the last autosave.
     *
     * <p>Never throws. A missing file is ordinary, and a damaged one must not stop the app
     * opening — the user would be left with no way in at all. A damaged file is also left
     * exactly where it is rather than deleted, because {@link #takeSessionBackup} has already
     * copied it aside by the time anything overwrites it.
     */
    public Restored restore() {
        if (!exists()) {
            return Restored.nothing();
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Restored.broken("Could not read the autosave: " + e.getMessage());
        }
        SaveFormat.LoadResult result = SaveFormat.load(text);
        if (!result.isSuccess()) {
            return Restored.broken("Autosave " + result.error());
        }
        return Restored.of(result.state());
    }

    // ------------------------------------------------------------------ writing

    /**
     * Replaces the autosave with {@code json}, atomically.
     *
     * @throws IOException if the text could not be written; the previous autosave is untouched
     */
    public void write(String json) throws IOException {
        Files.createDirectories(dir);

        // In the same directory as the target, so the rename below stays within one filesystem.
        Path temp = Files.createTempFile(dir, "autosave", ".tmp");
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                // Without this the bytes may still be in the operating system's cache when the
                // rename lands, and a power cut then leaves a correctly-named empty file — the
                // exact corruption the rename was supposed to rule out.
                channel.force(true);
            }
            moveIntoPlace(temp);
            temp = null;                            // moved; nothing left to clean up
        } finally {
            if (temp != null) {
                Files.deleteIfExists(temp);
            }
        }
    }

    private void moveIntoPlace(Path temp) throws IOException {
        try {
            Files.move(temp, file,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Some network and FUSE filesystems refuse the atomic flag. Falling back keeps the
            // app working there; it loses the crash guarantee, which is better than losing the
            // ability to save at all.
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ backups

    /**
     * Copies the existing autosave aside, then trims the folder to {@link #BACKUP_COUNT}.
     *
     * <p>Called once at startup, <b>before</b> anything is written and <b>without</b> checking
     * that the file parses. Both matter: a file that fails to load is the one most worth keeping,
     * and it is about to be overwritten by the first autosave of the new session.
     *
     * @return the backup written, or {@code null} if there was no autosave to copy
     */
    public Path takeSessionBackup() throws IOException {
        if (!exists()) {
            return null;
        }
        Files.createDirectories(backupDir);
        Path target = freeBackupPath(LocalDateTime.now(clock).format(STAMP));
        Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
        rotate();
        return target;
    }

    /** A name not already taken — two sessions can start inside the same second. */
    private Path freeBackupPath(String stamp) {
        Path candidate = backupDir.resolve("autosave-" + stamp + ".json");
        for (int n = 2; Files.exists(candidate); n++) {
            candidate = backupDir.resolve("autosave-" + stamp + "-" + n + ".json");
        }
        return candidate;
    }

    /** Deletes the oldest backups until {@link #BACKUP_COUNT} remain. */
    private void rotate() throws IOException {
        List<Path> backups = existingBackups();
        // The stamp is year-first and fixed-width, so sorting the names sorts them by time.
        backups.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (int i = 0; i < backups.size() - BACKUP_COUNT; i++) {
            Files.deleteIfExists(backups.get(i));
        }
    }

    /** Every file in the backup folder that this class wrote, in no particular order. */
    public List<Path> existingBackups() throws IOException {
        List<Path> found = new ArrayList<>();
        if (!Files.isDirectory(backupDir)) {
            return found;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(backupDir)) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry)
                        && BACKUP_NAME.matcher(entry.getFileName().toString()).matches()) {
                    found.add(entry);
                }
            }
        }
        return found;
    }
}
