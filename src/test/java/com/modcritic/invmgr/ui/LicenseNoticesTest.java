package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Licenses page's contents, read the way the app reads them. Whether the page carries what
 * each license asks for is the license check's job ({@code tools/licenses/check.py}); this checks
 * that the app can read every file the index names, and that the words the user approved are the
 * words shown.
 */
class LicenseNoticesTest {

    private static final String OFFER = "This is a written offer, good for at least three years"
            + " after 9.9.9 was last available: if that page is ever gone, write to"
            + " contact@asterkazika.com for a copy.";

    @Test
    @DisplayName("both pages read, and every text either one names opens and has words in it")
    void everyTextOpens() {
        for (boolean phone : new boolean[] {false, true}) {
            LicenseNotices notices = LicenseNotices.load(phone, "9.9.9");
            List<LicenseNotices.Entry> texts = new ArrayList<>();
            for (LicenseNotices.Entry entry : notices.entries()) {
                if (entry.isGroup()) {
                    assertFalse(entry.items().isEmpty(), entry.title());
                    texts.addAll(entry.items());
                } else {
                    assertTrue(entry.usedBy().startsWith("Used by "), entry.title());
                    texts.add(entry);
                }
            }
            for (LicenseNotices.Entry text : texts) {
                assertFalse(LicenseNotices.paragraphs(text.resource()).isEmpty(), text.resource());
            }
        }
    }

    @Test
    @DisplayName("the written offer and the source link are the approved words, on both platforms")
    void theApprovedWords() {
        for (boolean phone : new boolean[] {false, true}) {
            List<LicenseNotices.Block> blocks = LicenseNotices.load(phone, "9.9.9").blocks();
            assertTrue(blocks.contains(new LicenseNotices.Block(LicenseNotices.Kind.NOTE, OFFER)), blocks.toString());
            assertTrue(blocks.contains(new LicenseNotices.Block(LicenseNotices.Kind.SOURCE, "Source for 9.9.9")));
            assertEquals(LicenseNotices.Kind.SOURCE, blocks.get(2).kind(), "the link comes right after the GPL paragraph");
        }
    }

    @Test
    @DisplayName("only the phone lists the Java class library, since only the phone build contains one")
    void theGroupIsThePhones() {
        assertTrue(LicenseNotices.load(false, "1").entries().stream().noneMatch(LicenseNotices.Entry::isGroup));
        LicenseNotices.Entry group = LicenseNotices.load(true, "1").entries().stream()
                .filter(LicenseNotices.Entry::isGroup).findFirst().orElseThrow();
        assertEquals("Java class library", group.title());
        assertEquals(11, group.items().size(), group.items().toString());
    }

    @Test
    @DisplayName("a license's 80-column lines become paragraphs, its indentation dropped")
    void paragraphs() {
        List<String> gpl = LicenseNotices.paragraphs("/com/modcritic/invmgr/ui/licenses/texts/gpl-2.0-classpath.txt");
        assertEquals("The GNU General Public License (GPL)", gpl.get(0));
        assertEquals("Version 2, June 1991", gpl.get(1));
        assertTrue(gpl.stream().noneMatch(p -> p.contains("\n") || p.startsWith(" ")));
        assertTrue(gpl.stream().anyMatch(p -> p.startsWith("a) Accompany it with the complete corresponding"
                + " machine-readable source code, which must be distributed")), "a wrapped clause, joined");
    }

    @Test
    @DisplayName("a Markdown notice loses its formatting marks and keeps its words")
    void markdownNotices() {
        List<String> asm = LicenseNotices.paragraphs("/com/modcritic/invmgr/ui/licenses/texts/jdk/asm.txt");
        assertEquals("ASM Bytecode Manipulation Framework v8.0.1", asm.get(0));
        assertEquals("ASM License", asm.get(1));
        assertTrue(asm.get(2).startsWith("Copyright (c) 2000-2011 France Télécom"), asm.get(2));
        assertTrue(asm.stream().noneMatch(p -> p.startsWith("#") || p.contains("<pre>") || p.contains("```")),
                asm.toString());
    }

    @Test
    @DisplayName("a notice's drawn box of = signs goes, and the words inside it stay")
    void drawnBoxes() {
        List<String> xalan = LicenseNotices.paragraphs("/com/modcritic/invmgr/ui/licenses/texts/jdk/xalan.txt");
        assertEquals("NOTICE file corresponding to the section 4d of the Apache License, Version 2.0,"
                + " in this case for the Apache Xalan distribution.", xalan.get(2));
        assertTrue(xalan.stream().noneMatch(p -> p.contains("===")), xalan.toString());
    }

    @Test
    @DisplayName("an index the app cannot read fails loudly rather than showing a short page")
    void aBrokenIndexFails() {
        assertThrows(IllegalStateException.class, () -> LicenseNotices.parse("item\tX\t/x.txt\n", "1"));
        assertThrows(IllegalStateException.class, () -> LicenseNotices.parse("pargraph\tTypo\n", "1"));
        assertThrows(IllegalStateException.class, () -> LicenseNotices.parse("text\tNo file\n", "1"));
    }
}
