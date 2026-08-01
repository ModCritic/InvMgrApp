package com.modcritic.invmgr.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Where each platform is told to keep the autosave.
 *
 * <p>Only one of these three answers can ever be tried on the machine running the tests, which
 * is why {@link AppDataDir#resolve} takes the operating system and the environment as arguments.
 * Getting the Windows path wrong is otherwise something a Linux container cannot notice and a
 * Windows user finds out the hard way.
 */
class AppDataDirTest {

    private static final Path HOME = Path.of("/home/someone");

    /** An environment with the named variables set and nothing else. */
    private static UnaryOperator<String> env(Map<String, String> values) {
        return values::get;
    }

    private static UnaryOperator<String> emptyEnv() {
        return name -> null;
    }

    @Test
    void windowsUsesTheRoamingApplicationDataFolder() {
        // Asserted as the whole path, and against the home directory as well.
        //
        // This was first written as "the result contains AppData/Roaming", which the fallback
        // below ALSO satisfies — so deleting the %APPDATA% lookup entirely left the test green.
        // The mutation sweep found it. A test that both the working and the broken code pass is
        // not a weak test, it is not a test.
        Path appData = Path.of("D:\\Profiles\\Someone\\AppData\\Roaming");
        Path resolved = AppDataDir.resolve("Windows 11",
                env(Map.of("APPDATA", appData.toString())), HOME);

        assertEquals(appData.resolve("InvMgr"), resolved);
        assertFalse(resolved.startsWith(HOME),
                "%APPDATA% was ignored and the home directory used instead");
    }

    @Test
    void windowsFallsBackToTheDefaultLocationWhenAppdataIsUnset() {
        Path resolved = AppDataDir.resolve("Windows 10", emptyEnv(), HOME);

        assertEquals(HOME.resolve("AppData").resolve("Roaming").resolve("InvMgr"), resolved);
    }

    @Test
    void windowsIgnoresABlankAppdata() {
        // An empty variable is set-but-useless, and resolving "" gives the working directory --
        // the one place the autosave must never land.
        Path resolved = AppDataDir.resolve("Windows 10", env(Map.of("APPDATA", "   ")), HOME);

        assertEquals(HOME.resolve("AppData").resolve("Roaming").resolve("InvMgr"), resolved);
    }

    @Test
    void macUsesApplicationSupport() {
        Path resolved = AppDataDir.resolve("Mac OS X", emptyEnv(), HOME);

        assertEquals(HOME.resolve("Library").resolve("Application Support").resolve("InvMgr"),
                resolved);
    }

    @Test
    void macIgnoresXdgEvenWhenItIsSet() {
        // A developer's shell can carry XDG_DATA_HOME onto a Mac; the platform convention wins.
        Path resolved = AppDataDir.resolve("Mac OS X",
                env(Map.of("XDG_DATA_HOME", "/tmp/xdg")), HOME);

        assertEquals(HOME.resolve("Library").resolve("Application Support").resolve("InvMgr"),
                resolved);
    }

    @Test
    void linuxHonoursXdgDataHome() {
        Path resolved = AppDataDir.resolve("Linux",
                env(Map.of("XDG_DATA_HOME", "/var/data/someone")), HOME);

        assertEquals(Path.of("/var/data/someone/InvMgr"), resolved);
    }

    @Test
    void linuxFallsBackToTheStandardShareFolder() {
        Path resolved = AppDataDir.resolve("Linux", emptyEnv(), HOME);

        assertEquals(HOME.resolve(".local").resolve("share").resolve("InvMgr"), resolved);
    }

    @Test
    void linuxRejectsARelativeXdgDataHome() {
        // The XDG specification says a relative value must be ignored. Honouring it would put
        // the autosave under whatever directory the app happened to be launched from.
        Path resolved = AppDataDir.resolve("Linux",
                env(Map.of("XDG_DATA_HOME", "relative/path")), HOME);

        assertEquals(HOME.resolve(".local").resolve("share").resolve("InvMgr"), resolved);
    }

    @Test
    void anUnknownPlatformIsTreatedAsLinux() {
        // Android arrives here, and so would a BSD. The XDG layout under the home directory is
        // writable on all of them.
        Path resolved = AppDataDir.resolve("FreeBSD", emptyEnv(), HOME);

        assertEquals(HOME.resolve(".local").resolve("share").resolve("InvMgr"), resolved);
    }

    @Test
    void neverTheWorkingDirectory() {
        // The one rule that holds on every platform: an absolute path, never a relative one.
        for (String os : new String[] {"Windows 11", "Mac OS X", "Linux", "FreeBSD"}) {
            assertTrue(AppDataDir.resolve(os, emptyEnv(), HOME).isAbsolute(),
                    os + " resolved to a relative path, which would follow the working directory");
        }
    }
}
