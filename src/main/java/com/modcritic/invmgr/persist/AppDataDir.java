package com.modcritic.invmgr.persist;

import java.nio.file.Path;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Where this machine expects an application to keep its own data.
 *
 * <p>The autosave lives here and never in the working directory: a user launching the jar from
 * their Downloads folder must not end up with app files scattered there, and on a machine where
 * the working directory is read-only the app would simply fail to save.
 *
 * <p>Every platform's answer is different and only one of them can be tried from this container,
 * so {@link #resolve} takes the operating system name, the environment and the home directory as
 * parameters rather than reading them. That is the whole reason the method is shaped this way:
 * the Windows and macOS branches are testable on Linux, and were.
 */
public final class AppDataDir {

    /** The folder name used inside whichever per-user directory the platform provides. */
    public static final String APP_FOLDER = "InvMgr";

    private AppDataDir() {
    }

    /** This machine's directory for the app, using the real environment. */
    public static Path current() {
        return resolve(System.getProperty("os.name", ""),
                System::getenv,
                Path.of(System.getProperty("user.home", ".")));
    }

    /**
     * The app-data directory for a given platform.
     *
     * @param osName  the value of the {@code os.name} system property
     * @param env     reads an environment variable, returning {@code null} when it is unset
     * @param userHome the value of the {@code user.home} system property
     */
    public static Path resolve(String osName, UnaryOperator<String> env, Path userHome) {
        String os = osName.toLowerCase(Locale.ROOT);

        if (os.contains("win")) {
            // %APPDATA% is the roaming profile — it follows the user between machines on a
            // domain, which is right for a document-shaped file like this one. It is set on
            // every supported Windows, but a service or a stripped environment can lack it,
            // and the default location is worth spelling out rather than crashing.
            String appData = env.apply("APPDATA");
            return notBlank(appData)
                    ? Path.of(appData).resolve(APP_FOLDER)
                    : userHome.resolve("AppData").resolve("Roaming").resolve(APP_FOLDER);
        }

        if (os.contains("mac")) {
            return userHome.resolve("Library").resolve("Application Support").resolve(APP_FOLDER);
        }

        // Linux and anything else, per the XDG base directory specification. XDG_DATA_HOME is
        // for data the user would miss if it vanished, which is exactly the autosave; the cache
        // directory would be wrong, as it is defined as safe to delete.
        //
        // The spec says a relative XDG_DATA_HOME must be ignored, and this honours that rather
        // than resolving it against the working directory.
        String xdgData = env.apply("XDG_DATA_HOME");
        if (notBlank(xdgData)) {
            Path candidate = Path.of(xdgData);
            if (candidate.isAbsolute()) {
                return candidate.resolve(APP_FOLDER);
            }
        }
        // Android lands here, and it is correct there too: on device user.home is the app's own
        // private directory, so this becomes <private>/.local/share/InvMgr — writable, private,
        // and removed when the app is uninstalled. M6 may want to shorten it, but nothing about
        // it is wrong.
        return userHome.resolve(".local").resolve("share").resolve(APP_FOLDER);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
