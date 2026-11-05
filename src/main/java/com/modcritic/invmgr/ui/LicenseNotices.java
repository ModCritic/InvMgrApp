package com.modcritic.invmgr.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * What the Licenses page shows: the paragraphs at its top, and the list of license texts below
 * them.
 *
 * <p>The app contains other people's code, and their licenses ask for things in return: a copy
 * of the license, a copyright notice, a credit sentence, or a way to get the source. The Licenses
 * page, under About in the Menu window, is where the app gives all of that. The desktop and the
 * phone contain different code, so each has its own list.
 *
 * <p><b>Nothing here is written by hand.</b> The words and the list come from an index file in
 * the app's resources, {@code licenses/desktop.txt} or {@code licenses/android.txt}, and the
 * license texts from files beside it, copied unchanged from the code they came with. The script
 * {@code tools/licenses/notices.py} writes all of them, and the license check fails a build whose
 * page is missing something a license asks for. Change the page there, not here.
 *
 * <p>The index has one line per thing on the page, its fields separated by tabs and its kind
 * first: {@code para}, {@code note}, {@code credit}, {@code source}, {@code text},
 * {@code group}, and {@code item} for each text inside the group above it.
 */
public final class LicenseNotices {

    private static final String FOLDER = "/com/modcritic/invmgr/ui/licenses/";

    /** What a line above the list is, which decides how it is drawn. */
    public enum Kind {
        /** A paragraph in the page's usual gray. */
        PARAGRAPH,
        /** A paragraph in the dimmer gray, the written offer under the source link. */
        NOTE,
        /** A sentence a license requires, word for word. Drawn like a paragraph. */
        CREDIT,
        /** The link to this version's source. The text is the link's label. */
        SOURCE
    }

    /** One line above the list. */
    public record Block(Kind kind, String text) {
    }

    /**
     * One entry in the list: a license text, or a group of them.
     *
     * <p>A text has a {@code resource}, the file it reads, and {@code usedBy}, the sentence above
     * it naming what in the app uses that license. A group (the Java class library, which brings
     * a dozen smaller licenses of its own) has an {@code intro} and its {@code items} instead.
     */
    public record Entry(String title, String resource, String usedBy, String intro, List<Entry> items) {

        public boolean isGroup() {
            return items != null;
        }
    }

    private final List<Block> blocks;
    private final List<Entry> entries;

    private LicenseNotices(List<Block> blocks, List<Entry> entries) {
        this.blocks = List.copyOf(blocks);
        this.entries = List.copyOf(entries);
    }

    /** The lines above the list, in order. */
    public List<Block> blocks() {
        return blocks;
    }

    /** The license texts and groups, in the order the page lists them. */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * This platform's page, with {@code {version}} filled in.
     *
     * <p>Fails loudly when the index is missing, like {@link AppInfo#VERSION} does: on a phone the
     * build only packs the resource folders {@code pom.xml} lists, and a Licenses page that
     * silently showed nothing would be the one failure nobody notices.
     */
    public static LicenseNotices load(boolean phone, String version) {
        String name = FOLDER + (phone ? "android.txt" : "desktop.txt");
        return parse(read(name), version);
    }

    /** Reads the index's words. Package-private so a test can feed it a page of its own. */
    static LicenseNotices parse(String index, String version) {
        List<Block> blocks = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        List<Entry> groupItems = null;
        for (String line : index.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.replace("{version}", version).split("\t", -1);
            switch (f[0]) {
                case "para" -> blocks.add(new Block(Kind.PARAGRAPH, field(f, 1)));
                case "note" -> blocks.add(new Block(Kind.NOTE, field(f, 1)));
                case "credit" -> blocks.add(new Block(Kind.CREDIT, field(f, 1)));
                case "source" -> blocks.add(new Block(Kind.SOURCE, field(f, 1)));
                case "text" -> {
                    entries.add(new Entry(field(f, 1), field(f, 2), field(f, 3), null, null));
                    groupItems = null;
                }
                case "group" -> {
                    groupItems = new ArrayList<>();
                    entries.add(new Entry(field(f, 1), null, null, field(f, 2), groupItems));
                }
                case "item" -> {
                    if (groupItems == null) {
                        throw new IllegalStateException("license index: an item with no group above it: " + line);
                    }
                    groupItems.add(new Entry(field(f, 1), field(f, 2), null, null, null));
                }
                default -> throw new IllegalStateException("license index: unknown line: " + line);
            }
        }
        return new LicenseNotices(blocks, entries);
    }

    private static String field(String[] fields, int i) {
        if (i >= fields.length || fields[i].isEmpty()) {
            throw new IllegalStateException("license index: a line is missing field " + i + ": "
                    + String.join("\t", fields));
        }
        return fields[i];
    }

    /**
     * A license text, as paragraphs to show one under another.
     *
     * <p>License files are written for an 80-column terminal: each line ends with a hard break,
     * and titles are centered with spaces. On a 300-pixel-wide page every one of those lines
     * would wrap into a full line and a short stub. So each run of lines between blank lines
     * becomes one paragraph, joined with spaces and its indentation dropped, and the page wraps
     * it to fit. The words are the file's own, in its order.
     *
     * <p>The Java class library's notices are Markdown files, so their formatting marks go too: a
     * heading's leading {@code #}s, the {@code <pre>} tags around a license, and the
     * {@code ```} lines that fence one off, which count as a break between paragraphs. So do the
     * boxes some notices draw around a title: a line made only of {@code =} or {@code -} is a
     * drawn rule and becomes a break, and the {@code ==} at each end of a boxed line is dropped.
     */
    public static List<String> paragraphs(String resource) {
        List<String> paragraphs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : read(resource).split("\\R")) {
            String words = line.strip();
            if (words.equals("```") || words.matches("[=*_-]{3,}")) {
                words = "";
            }
            words = words.replaceFirst("^#+\\s+", "").replace("<pre>", "").replace("</pre>", "")
                    .replaceFirst("^==\\s+", "").replaceFirst("\\s+==$", "").strip();
            if (words.isEmpty()) {
                if (current.length() > 0) {
                    paragraphs.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(current.length() > 0 ? " " : "").append(words);
            }
        }
        if (current.length() > 0) {
            paragraphs.add(current.toString());
        }
        return paragraphs;
    }

    private static String read(String name) {
        try (InputStream in = LicenseNotices.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException(name + " is missing from the build: on a phone, check"
                        + " that pom.xml's <resourcesList> includes the licenses folder");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + name, e);
        }
    }
}
