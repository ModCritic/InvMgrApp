package com.modcritic.invmgr.persist;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Reads a test fixture or golden file from {@code src/test/resources}.
 *
 * <p>Exists so the persistence tests read their inputs one way, and so a missing or renamed
 * fixture fails with a clear message instead of a {@code NullPointerException} thirty lines
 * later.
 */
final class Fixtures {

    private Fixtures() {
    }

    /** @param path a path under {@code src/test/resources}, e.g. {@code "fixtures/typical.json"} */
    static String read(String path) throws IOException {
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("test resource not found on the classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Same as {@link #read} for use where a checked exception would only add noise. */
    static String readUnchecked(String path) {
        try {
            return read(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
