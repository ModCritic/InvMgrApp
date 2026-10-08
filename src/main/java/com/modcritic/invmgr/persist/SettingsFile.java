package com.modcritic.invmgr.persist;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

/**
 * The app's settings, kept in a plain text file beside the autosave.
 *
 * <p>Decided by the user on 2026-09-24: a text file, one setting to a line, in the folder
 * {@link AppDataDir} already picks, so it sits next to {@code autosave.json} on every platform.
 * It is not part of the save format. A room file carries a room; this carries how the app itself
 * behaves on this device, which is why loading a room never changes it.
 *
 * <pre>
 * # InvMgr settings. One per line, name=value. Lines this version does not know are ignored.
 * hideSystemBars=true
 * </pre>
 *
 * <p><b>A missing or unreadable file means the defaults, and the log says which.</b> The first
 * launch has no file, which is normal and said once in the log. A file that cannot be read, or a
 * line whose value makes no sense, gets the default for what it could not read, and a line in the
 * log naming the problem rather than a silent guess.
 *
 * <p><b>Written the way the autosave is</b>: into a temporary file in the same folder, forced to
 * the disk, then moved over the old one in one step, so a crash halfway leaves the old file whole
 * rather than half a new one. {@link Autosave#write} carries the long version of why each step
 * is there. The few lines are repeated here rather than shared because the mutation sweep aims at
 * Autosave's own, and pulling them into a helper would leave its pattern matching nothing.
 */
public final class SettingsFile {

    public static final String FILE_NAME = "settings.txt";

    /** Hide the phone's status and navigation bars until a swipe brings them back. Phone only. */
    static final String HIDE_SYSTEM_BARS = "hideSystemBars";

    private final Path dir;
    private final Path file;
    private final Consumer<String> log;

    /** A settings file in this folder, reporting what it notices to standard output. */
    public SettingsFile(Path dir) {
        this(dir, message -> System.out.println("InvMgr: " + message));
    }

    /** The same, reporting to whatever is given: the tests read the log this way. */
    public SettingsFile(Path dir, Consumer<String> log) {
        this.dir = dir;
        this.file = dir.resolve(FILE_NAME);
        this.log = log;
    }

    public Path file() {
        return file;
    }

    /** What the app's settings are. Every field's starting value is its default. */
    public static final class Settings {
        public boolean hideSystemBars;
    }

    /** Reads the file, or gives the defaults and says why. Never throws. */
    public Settings load() {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            log.accept("no settings file yet at " + file + ", using the defaults");
            return new Settings();
        } catch (IOException | RuntimeException e) {
            log.accept("could not read " + file + " (" + e.getMessage() + "), using the defaults");
            return new Settings();
        }
        return parse(text, problem -> log.accept(file + ": " + problem));
    }

    /**
     * Reads settings out of the file's text.
     *
     * <p>Blank lines and lines starting {@code #} are skipped. A name this version does not know is
     * skipped without comment, so a file written by a newer version still loads. A known name with
     * a value that makes no sense keeps its default, and is reported.
     */
    static Settings parse(String text, Consumer<String> problems) {
        Settings settings = new Settings();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                problems.accept("skipped \"" + line + "\", which is not name=value");
                continue;
            }
            String name = line.substring(0, equals).strip();
            String value = line.substring(equals + 1).strip();
            if (name.equals(HIDE_SYSTEM_BARS)) {
                Boolean on = yesOrNo(value);
                if (on == null) {
                    problems.accept(HIDE_SYSTEM_BARS + " should be true or false, not \"" + value
                            + "\"; using the default");
                } else {
                    settings.hideSystemBars = on;
                }
            }
        }
        return settings;
    }

    private static Boolean yesOrNo(String value) {
        if (value.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (value.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** The file's text for these settings. */
    static String format(Settings settings) {
        return "# InvMgr settings. One per line, name=value. Lines this version does not know are"
                + " ignored.\n"
                + HIDE_SYSTEM_BARS + "=" + settings.hideSystemBars + "\n";
    }

    /**
     * Writes the settings, replacing the file in one step.
     *
     * @throws IOException if they could not be written; the previous file is untouched
     */
    public void save(Settings settings) throws IOException {
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, "settings", ".tmp");
        try {
            byte[] bytes = format(settings).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            try {
                Files.move(temp, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } finally {
            if (temp != null) {
                Files.deleteIfExists(temp);
            }
        }
    }
}
