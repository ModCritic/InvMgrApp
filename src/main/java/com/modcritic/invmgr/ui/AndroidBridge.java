package com.modcritic.invmgr.ui;

/**
 * The app's door into Android, for the two things only Android can answer.
 *
 * <p>Everything else about running on a phone is handled below this app; JavaFX draws into a
 * surface the host layer gives it, and fingers arrive as ordinary JavaFX touch events. Two
 * questions have no answer inside JavaFX at all, and this class is how they get asked:
 *
 * <ul>
 *   <li><b>How much of the screen belongs to the phone</b>, and how much of it the keyboard is
 *       currently covering. See {@link #systemInsets()} and {@link #keyboardHeight()}.
 *   <li><b>Which keyboard a text field should raise</b>: the dial pad for a number, the full
 *       keyboard for a name. See {@link #setKeyboardType}.
 * </ul>
 *
 * <h2>⚠ The availability check is not the same question {@code Device} asks</h2>
 *
 * <p>{@link Device#isAndroid()} answers "should this look like a phone", and it can be forced true
 * on a desktop with {@code -Dinvmgr.platform=android} so the touch layout can be run and
 * screenshotted here. That override is documented in {@code CLAUDE.md} §10 and is used constantly.
 *
 * <p>This class must ask a different question: <b>is there really an Android underneath</b>. The
 * methods below reach native code that only exists inside the phone build, so calling one on a
 * forced-android desktop would throw {@link UnsatisfiedLinkError} and break exactly the workflow
 * the override exists for. {@link #isAvailable()} therefore reads what JavaFX reports and ignores
 * the override.
 *
 * <h2>How the native methods are linked</h2>
 *
 * <p>The two {@code native} methods are implemented in {@code native/invmgr_bridge.c} and resolved
 * by the linker when the phone build runs, so <b>this class's name and package are part of the
 * contract</b>. Renaming or moving it breaks the link rather than the compile. Nothing needs
 * registering anywhere for that to work; see the C file's comment for why.
 */
public final class AndroidBridge {

    /** Index into what the host layer reports. Kept beside each other so they cannot drift. */
    private static final int TOP = 0;
    private static final int RIGHT = 1;
    private static final int BOTTOM = 2;
    private static final int LEFT = 3;
    private static final int KEYBOARD = 4;
    private static final int COUNT = 5;

    /**
     * JavaFX's own numbering for keyboard layouts, which the host layer maps to Android's.
     * Only the two this app has a use for are named.
     */
    public static final int KEYBOARD_TEXT = 0;

    /** A whole number: a quantity, or a count of items. Raises the dial pad. */
    public static final int KEYBOARD_NUMBER = 1;

    /** A number that may have a decimal point: every measurement in this app. */
    public static final int KEYBOARD_DECIMAL = 2;

    private AndroidBridge() {
    }

    /**
     * Is there a real Android host underneath to ask?
     *
     * <p>Deliberately not {@link Device#isAndroid()}. See the class comment: that one can be
     * forced on from the command line, and this one must not be.
     */
    public static boolean isAvailable() {
        return "android".equals(System.getProperty(Device.PLATFORM_PROPERTY));
    }

    /**
     * What the phone has taken of the screen, in design pixels, or null if it cannot say yet.
     *
     * <p>Null is a real answer rather than a failure: Android reports nothing about the window
     * until the host layer's view has been attached to one. The caller falls back to
     * {@link SystemInsets#forAndroid} when that happens.
     *
     * @param density how many real pixels make one design pixel, which JavaFX already knows
     */
    public static SystemInsets systemInsets(double density) {
        return toInsets(read(), density);
    }

    /**
     * The conversion on its own, so it can be tested without a phone.
     *
     * <p>Package-private and taking the raw numbers as an argument for exactly that reason: the
     * public method above cannot run anywhere but a phone, and the arithmetic in it is the part
     * that could be wrong.
     *
     * @param raw     the five numbers the host layer reports, in real pixels, or null
     * @param density how many real pixels make one design pixel
     * @return the four edges in design pixels, or null if there is nothing to convert
     */
    static SystemInsets toInsets(int[] raw, double density) {
        if (raw == null || raw.length != COUNT || density <= 0) {
            return null;
        }
        return new SystemInsets(raw[TOP] / density, raw[RIGHT] / density,
                                raw[BOTTOM] / density, raw[LEFT] / density);
    }

    /**
     * How much of the screen the keyboard is covering right now, in design pixels, or zero.
     *
     * <p>Zero when the keyboard is down, when there is no Android to ask, and when Android has
     * not attached the window yet. All three mean the same thing to the caller: nothing to move
     * out of the way of.
     */
    public static double keyboardHeight(double density) {
        return toKeyboardHeight(read(), density);
    }

    /** The keyboard conversion on its own, testable without a phone. See {@link #toInsets}. */
    static double toKeyboardHeight(int[] raw, double density) {
        if (raw == null || raw.length != COUNT || density <= 0) {
            return 0;
        }
        return raw[KEYBOARD] / density;
    }

    /**
     * Asks for a particular keyboard the next time a field takes focus.
     *
     * <p>Does nothing off the phone, so callers do not have to check first. Takes effect on the
     * next field that raises the keyboard rather than immediately, which is why the app sets it
     * when a field gains focus rather than when the dialog is built.
     *
     * @param type one of {@link #KEYBOARD_TEXT}, {@link #KEYBOARD_NUMBER}, {@link #KEYBOARD_DECIMAL}
     */
    public static void setKeyboardType(int type) {
        if (isAvailable()) {
            nativeSetKeyboardType(type);
        }
    }

    /**
     * The mark a control wears to say which keyboard it wants.
     *
     * <p>Stored in the control's own property map rather than in a list kept somewhere else,
     * so the answer travels with the field and cannot go stale.
     */
    private static final String WANTED_KEYBOARD = "invmgr.keyboard";

    /**
     * Says that this box holds a number, so a phone should raise the keypad for it.
     *
     * <p>Anything not marked is treated as ordinary text, which is the safe default: the worst
     * a wrongly-lettered field costs is an unnecessary keyboard, where a wrongly-numbered one
     * cannot have its name typed at all.
     */
    public static void marksANumberField(javafx.scene.control.TextInputControl field) {
        if (field != null) {
            field.getProperties().put(WANTED_KEYBOARD, KEYBOARD_DECIMAL);
        }
    }

    /**
     * Watches the whole window and asks for the right keyboard whenever focus moves.
     *
     * <p><b>This is B6, and it replaces a per-field version that shipped broken.</b> The first
     * one hung a listener on each field as it was built, from the two places that build fields.
     * The Add dialog's Name box is built by neither (it is a {@code TextArea}, because the
     * original's is a {@code <textarea>}), so it was never wired, and tapping it after a size
     * box left the keypad up with no letters on it. That is exactly the bug the user reported.
     *
     * <p>Watching the window instead means <b>a new field cannot be forgotten</b>: anything that
     * can take focus is covered the moment it exists, and a box that wants the keypad says so
     * with {@link #marksANumberField} rather than by being wired up correctly somewhere else.
     *
     * <p>On focus rather than at build time for the reason that has not changed: the phone has
     * one keyboard setting for the whole window, so it must follow whichever box was last
     * touched.
     */
    public static void followTheFocusedField(javafx.scene.Scene scene) {
        if (scene == null || !isAvailable()) {
            return;
        }
        scene.focusOwnerProperty().addListener((observable, lost, gained) -> {
            if (gained instanceof javafx.scene.control.TextInputControl) {
                Object wanted = gained.getProperties().get(WANTED_KEYBOARD);
                setKeyboardType(wanted instanceof Integer ? (Integer) wanted : KEYBOARD_TEXT);
            }
        });
        watchForTheNextKey(scene);
    }

    /**
     * Watches for the keypad's Next key and steps to the following number box.
     *
     * <p><b>A poll, for the same reason everything else here is one:</b> Android answers a key
     * by calling the Activity, and nothing outside this app can call into it. The Activity
     * counts the presses and this reads the count.
     *
     * <p>It runs only while a number box actually has focus, so it costs nothing at any other
     * moment and cannot be left going: the timer starts when one takes focus and stops when
     * one loses it.
     */
    private static void watchForTheNextKey(javafx.scene.Scene scene) {
        javafx.animation.AnimationTimer watcher = new javafx.animation.AnimationTimer() {
            private int seen = nextFieldRequests();

            @Override
            public void handle(long now) {
                int asked = nextFieldRequests();
                if (asked != seen) {
                    seen = asked;
                    stepToTheNextNumberField(scene);
                }
            }
        };
        scene.focusOwnerProperty().addListener((observable, lost, gained) -> {
            if (isANumberField(gained)) {
                watcher.start();
            } else {
                watcher.stop();
            }
        });
    }

    /** Puts the keyboard back up. Does nothing off the phone. */
    private static void showKeyboard() {
        if (isAvailable()) {
            nativeShowKeyboard();
        }
    }

    /** How many times the keypad's Next key has been pressed, or zero off the phone. */
    private static int nextFieldRequests() {
        return isAvailable() ? nativeNextFieldRequests() : 0;
    }

    /** Is this a box that was marked as holding a number? */
    private static boolean isANumberField(javafx.scene.Node node) {
        return node instanceof javafx.scene.control.TextInputControl
                && node.getProperties().get(WANTED_KEYBOARD) instanceof Integer;
    }

    /**
     * Moves from one number box to the next, for the keypad's Next key.
     *
     * <p><b>⚠ This exists because JavaFX's own Tab order is not the order of the boxes.</b>
     * Measured on the phone: pressing Next in the Add dialog's Width went to Length and then
     * to the <b>ID</b> box, skipping Height, so the keyboard changed to letters half way
     * through entering three numbers and the digits after that went in as letters. Tab
     * traverses everything focusable in scene-graph order, and the dialog's rows are not laid
     * out in the order they are read.
     *
     * <p>So the step is taken over <b>the marked number boxes only</b>, in the order they
     * appear, which is the order somebody filling the dialog in expects. It wraps, so Next on
     * the last box returns to the first rather than escaping into the buttons.
     *
     * <p>Returns false when the focus is not on a number box at all, leaving Tab to mean what
     * it has always meant everywhere else.
     */
    private static boolean stepToTheNextNumberField(javafx.scene.Scene scene) {
        javafx.scene.Node focused = scene.getFocusOwner();
        if (!isANumberField(focused)) {
            return false;
        }
        java.util.List<javafx.scene.control.TextInputControl> boxes = new java.util.ArrayList<>();
        collectNumberFields(scene.getRoot(), boxes);
        int at = boxes.indexOf(focused);
        if (at < 0 || boxes.size() < 2) {
            return false;
        }
        boxes.get((at + 1) % boxes.size()).requestFocus();

        // ⚠ AND SAY THAT THE KEYBOARD IS STILL WANTED, or Next reads as doing nothing at all.
        //
        // Moving focus makes two of JavaFX's own skins act: the box being left asks for the
        // keyboard to go away and the box being entered asks for it back, and the hide lands
        // last. Measured on the phone: the keyboard slides away and the dialog drops back
        // down, which looks exactly like the focus never moved.
        //
        // The host layer now holds a hide for a fifth of a second to see whether a show is
        // behind it, so this is the show that overtakes it; see InvMgrActivity's
        // HIDE_SETTLE_MS. It is still needed, and it is still queued rather than called
        // straight away, so that it lands after both skins have had their turn and inside
        // that window. Safe because this is the only place that asks: it runs for the
        // keypad's Next key alone and cannot raise a keyboard nobody wanted.
        javafx.application.Platform.runLater(AndroidBridge::showKeyboard);
        return true;
    }

    /** Every visible number box under {@code node}, in the order they are laid out. */
    private static void collectNumberFields(javafx.scene.Node node,
                                            java.util.List<javafx.scene.control.TextInputControl> into) {
        if (node == null || !node.isVisible()) {
            return;
        }
        if (node instanceof javafx.scene.control.TextInputControl
                && node.getProperties().get(WANTED_KEYBOARD) instanceof Integer) {
            into.add((javafx.scene.control.TextInputControl) node);
            return;
        }
        if (node instanceof javafx.scene.Parent) {
            for (javafx.scene.Node child : ((javafx.scene.Parent) node).getChildrenUnmodifiable()) {
                collectNumberFields(child, into);
            }
        }
    }

    // ---------------------------------------------------------------- picking a file
    //
    // JavaFX's FileChooser throws UnsupportedOperationException on a phone, so Save, Load and
    // the item list's Export were all dead. Android's own picker needs no permissions, which
    // is the reason to use it rather than writing somewhere fixed: this app asks for nothing
    // today and that is worth keeping.

    /** Nothing has been asked for. */
    public static final int FILE_IDLE = 0;
    /** The picker is open and the user has not answered yet. */
    public static final int FILE_PENDING = 1;
    /** It worked. After an open, {@link #takeFileText()} has the contents. */
    public static final int FILE_DONE = 2;
    /** The user backed out. Not an error, and it should say nothing. */
    public static final int FILE_CANCELED = 3;
    /** It failed; {@link #takeFileError()} says why. */
    public static final int FILE_FAILED = 4;

    /**
     * About two seconds at sixty frames a second. Only ever reached when a request failed to
     * reach Android, which should not happen; see {@link #awaitFile}.
     */
    private static final int IDLE_FRAMES_BEFORE_GIVING_UP = 120;

    /** What a room file is, as far as the system picker is concerned. */
    public static final String JSON = "application/json";

    /** What an exported item list is. */
    public static final String PLAIN_TEXT = "text/plain";

    /**
     * Opens the system's "save as" picker and writes {@code content} wherever it is pointed.
     *
     * <p><b>The text is handed over now rather than fetched afterwards</b>, because by the time
     * the user has chosen a place, only the Android side can act; it cannot call back into the
     * app. Watch {@link #fileState()} for the answer.
     */
    public static void saveFile(String suggestedName, String mimeType, String content) {
        if (isAvailable()) {
            nativeRequestSaveFile(suggestedName, mimeType, content);
        }
    }

    /** Opens the system's file picker; the contents arrive through {@link #takeFileText()}. */
    public static void openFile(String mimeType) {
        if (isAvailable()) {
            nativeRequestOpenFile(mimeType);
        }
    }

    /**
     * How far the picker has got: one of the {@code FILE_} constants.
     *
     * <p>Asked once a frame while something is open. There is no being told: Android answers a
     * picker by calling the Activity, and nothing can call into this app from outside.
     */
    public static int fileState() {
        return isAvailable() ? nativeFileState() : FILE_IDLE;
    }

    /**
     * Waits for the picker the caller has just opened, and reports what happened.
     *
     * <p>Starts a timer that asks once a frame and stops itself the moment there is an answer,
     * so nothing is left running. There is only ever one file operation in flight; every one
     * of them starts from a button the user pressed and the picker is modal over the app.
     *
     * <p><b>Backing out of the picker calls neither handler.</b> Canceling is an ordinary
     * thing to do and the app already says nothing when a desktop file dialog is dismissed.
     *
     * @param onDone   given the file's text after an open, or null after a save
     * @param onFailed given a message describing what went wrong
     */
    public static void awaitFile(java.util.function.Consumer<String> onDone,
                                 java.util.function.Consumer<String> onFailed) {
        if (!isAvailable()) {
            return;
        }
        new javafx.animation.AnimationTimer() {

            /**
             * Frames spent seeing "idle", which should be none.
             *
             * <p>The request sets the state to pending before it returns, so by the time this
             * timer first runs there is always something in flight. Idle therefore means the
             * request never reached Android at all, and this counter is what stops the timer
             * running for the rest of the session in that case; a timer nothing stops is
             * invisible on screen and costs a phone its battery.
             */
            private int idleFrames;

            @Override
            public void handle(long now) {
                switch (fileState()) {
                    case FILE_PENDING:
                        idleFrames = 0;
                        return;
                    case FILE_DONE:
                        stop();
                        onDone.accept(takeFileText());
                        return;
                    case FILE_FAILED:
                        stop();
                        onFailed.accept(takeFileError());
                        return;
                    case FILE_CANCELED:
                        stop();
                        takeFileText();          // clears the state back to idle
                        return;
                    default:
                        if (++idleFrames > IDLE_FRAMES_BEFORE_GIVING_UP) {
                            stop();
                            onFailed.accept("the file picker did not open");
                        }
                        return;
                }
            }
        }.start();
    }

    /** What was read, handed over once. Null if there is nothing waiting. */
    public static String takeFileText() {
        return isAvailable() ? nativeTakeFileText() : null;
    }

    /** Why it failed, handed over once. Null if there is nothing waiting. */
    public static String takeFileError() {
        return isAvailable() ? nativeTakeFileError() : null;
    }

    /** The raw five numbers, or null when there is nothing to ask or nothing to tell. */
    private static int[] read() {
        if (!isAvailable()) {
            return null;
        }
        int[] raw = nativeInsets();
        return raw != null && raw.length == COUNT ? raw : null;
    }

    private static native int[] nativeInsets();

    private static native void nativeSetKeyboardType(int type);

    private static native void nativeRequestSaveFile(String suggestedName, String mimeType,
                                                     String content);

    private static native void nativeRequestOpenFile(String mimeType);

    private static native int nativeFileState();

    private static native int nativeNextFieldRequests();

    private static native void nativeShowKeyboard();

    private static native String nativeTakeFileText();

    private static native String nativeTakeFileError();
}
