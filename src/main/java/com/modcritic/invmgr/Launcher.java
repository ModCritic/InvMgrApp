package com.modcritic.invmgr;

import com.modcritic.invmgr.ui.Device;
import javafx.application.Application;

/**
 * The program's entry point: the first code that runs when InvMgr starts.
 *
 * <p>This class exists only to start {@link App}, and it must stay that way. Two
 * separate reasons, both of which cost the OD-1 spike a build:
 *
 * <ol>
 *   <li>{@code Application.launch(args)} (the short form) figures out which class
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

    /** Not meant to be instantiated; this class is only a container for {@link #main}. */
    private Launcher() {
    }

    /**
     * Switches off the platform's own scroll fling, before anything can read the setting.
     *
     * <p>The app's own Android launcher hard-codes {@code -Dcom.sun.javafx.gestures.scroll=true}
     * ({@code native/invmgr_launcher.c}), so a phone builds a {@code ScrollGestureRecognizer} and
     * no other platform does. That recognizer
     * flings the room by itself, and it decides whether to by asking how long the gesture lasted
     * rather than how fast the finger was going when it lifted, which is why the room's momentum
     * read as arbitrary. Its {@code <clinit>} ends in a {@code doPrivileged} block reading exactly
     * two properties, and this is the one that turns the fling off outright; the two constants that
     * would let it be tuned instead are plain literals with nothing reading them.
     *
     * <p><b>Set here, before {@code launch}, because it is only read once</b> and the read happens
     * the first time the class is touched, which is when the first scene is built. Setting it any
     * later than this would be setting it after the answer had been taken.
     *
     * <p><b>And it is not the only guard.</b> A native image may run a static initializer at build
     * time and bake the result, in which case nothing set at runtime is ever consulted; that cannot
     * be tested from a desktop. {@code RoomCanvasView} therefore also consumes any scroll event
     * carrying the inertia flag, which works whatever this line does. This one costs nothing and
     * stops the events being manufactured at all when it works.
     */
    private static final String PLATFORM_FLING = "com.sun.javafx.gestures.scroll.inertia";

    /**
     * Prism's own switch for "use the graphics card even though you have decided not to".
     *
     * <p><b>Why the app sets this for itself.</b> Prism refuses a graphics driver it does not
     * recognize as good enough and falls back to its software pipeline, which cannot draw 3D at
     * all: {@code SCENE3D} comes back false, the 3D button is disabled (§5.5 D-10) and the startup
     * log says so. That is the right answer on a machine with no 3D. It was the WRONG answer on a
     * virtual machine the user tested, where the OpenGL driver is Mesa's {@code llvmpipe}: a real
     * OpenGL implementation that happens to run on the processor. Forcing it there gives a working
     * 3D view, slowly, which beats no 3D view.
     *
     * <p>Until now that needed {@code -Dprism.forceGPU=true} on the command line, which is fine for
     * testing and no use at all once this is something you install and double-click.
     *
     * <p><b>Measured, not assumed, on 2026-09-15:</b> with no flags the jar reports
     * {@code scene3d false}; with this property alone it reports {@code scene3d true}; and setting
     * it here, before {@code launch}, is early enough for Prism to read it.
     *
     * <p><b>⚠ It must be this property and NOT {@code prism.order}.</b> Setting
     * {@code -Dprism.order=es2} on its own kills the app before a window ever appears:
     * {@code Error initializing QuantumRenderer: no suitable pipeline found}. It replaces the list
     * of pipelines to try, so there is nothing left to fall back to. This property leaves the list
     * alone: {@code prism.verbose} prints {@code Prism pipeline init order: es2 sw} either way, so
     * a card that fails to initialize still lands on the software pipeline and the app still runs.
     */
    static final String PRISM_FORCE_GPU = "prism.forceGPU";

    /**
     * The way out, for a machine where forcing the driver is worse than not having 3D.
     *
     * <p>{@code -Dinvmgr.forcegpu=false} and nothing else; see {@link #shouldForceGpu}. It exists
     * because the check being bypassed is a real one: Prism rejects a driver it thinks is broken,
     * and a driver that starts and then misbehaves could take the whole program down rather than
     * just the 3D button. §5.5 D-10 already decided which way that trade goes, in the same words:
     * losing the whole program to save one button is the worse trade. So there has to be a way
     * back that does not need a rebuild, and the startup log prints it.
     */
    public static final String FORCE_GPU_OVERRIDE = "invmgr.forcegpu";

    public static void main(String[] args) {
        System.setProperty(PLATFORM_FLING, "false");
        if (shouldForceGpu(System.getProperty(FORCE_GPU_OVERRIDE),
                System.getProperty(PRISM_FORCE_GPU), Device.isAndroid())) {
            System.setProperty(PRISM_FORCE_GPU, "true");
        }
        Application.launch(App.class, args);
    }

    /**
     * Whether to ask Prism for the graphics card, with every answer handed in so it can be tested.
     *
     * <p>Three ways to end up not doing it, and each is somebody saying so:
     *
     * <ul>
     *   <li><b>The opt-out was used.</b> Exactly the word {@code false}, in any case. Anything else
     *       is ignored rather than treated as "off", the same rule {@code Device}'s platform
     *       override follows: a typo must not silently change what the program does.
     *   <li><b>Prism's own property was already set</b>, either way. Somebody who passes
     *       {@code -Dprism.forceGPU=false} means it, and a default has no business overruling an
     *       argument.
     *   <li><b>This is a phone.</b> Android reaches its 3D through OpenJFX's own backend and has
     *       never needed this; bypassing a safety check on a platform that does not need it is
     *       risk for nothing. M6.5b and M6.5c got 3D running on the phone without it.
     * </ul>
     *
     * @param override what {@link #FORCE_GPU_OVERRIDE} says, or null
     * @param alreadySet what {@link #PRISM_FORCE_GPU} already says, or null
     * @param android whether this is a phone
     */
    static boolean shouldForceGpu(String override, String alreadySet, boolean android) {
        if (android || alreadySet != null) {
            return false;
        }
        return !"false".equalsIgnoreCase(override);
    }
}
