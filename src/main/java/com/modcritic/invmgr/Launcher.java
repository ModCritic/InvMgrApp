package com.modcritic.invmgr;

import javafx.application.Application;

/**
 * The program's entry point — the first code that runs when InvMgr starts.
 *
 * <p>This class exists only to start {@link App}, and it must stay that way. Two
 * separate reasons, both of which cost the OD-1 spike a build:
 *
 * <ol>
 *   <li>{@code Application.launch(args)} — the short form — figures out which class
 *       to start by inspecting the call stack and looking that class up by name. A
 *       native image (the Android build) has no such lookup table, so it fails with
 *       {@code ClassNotFoundException} and a black screen. Naming the class outright,
 *       as below, avoids the lookup entirely.
 *   <li>The class holding {@code main} should not itself be the JavaFX application
 *       class, or the JavaFX libraries end up on the module path in a way that breaks
 *       plain {@code java -jar} launches.
 * </ol>
 */
public final class Launcher {

    /** Not meant to be instantiated — this class is only a container for {@link #main}. */
    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(App.class, args);
    }
}
