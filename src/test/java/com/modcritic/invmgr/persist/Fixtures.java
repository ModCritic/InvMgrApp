package com.modcritic.invmgr.persist;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads a test fixture or golden file from {@code src/test/resources}.
 *
 * <p>Exists so the tests read their inputs one way, and so a missing or renamed fixture fails
 * with a clear message instead of a {@code NullPointerException} thirty lines later.
 *
 * <p><b>Public because five other test classes had grown their own copy of it.</b> It lives in
 * the persistence package because that is where it was first needed, but the engine and 3D
 * differential tests read goldens the same way, and a package-private class is invisible to
 * them. M6.7c deleted the five copies and widened this instead.
 */
public final class Fixtures {

    private Fixtures() {
    }

    /** @param path a path under {@code src/test/resources}, e.g. {@code "fixtures/typical.json"} */
    public static String read(String path) throws IOException {
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("test resource not found on the classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
