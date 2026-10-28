package com.modcritic.invmgr.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

/**
 * The Android side of InvMgr's host layer: the code that starts the app's Java VM on the phone
 * and hands JavaFX the screen and the touches.
 *
 * WHAT THIS IS
 *
 * Android will not run a JavaFX app directly. It runs an Activity. This class is that
 * Activity: it owns the window, receives every touch and key event Android delivers, and
 * hands them to the JavaFX code compiled inside libsubstrate.so. It draws nothing itself.
 * Everything you see is drawn by JavaFX into the Surface this class gives it.
 *
 * WHY IT EXISTS, WHICH IS A LICENSING ANSWER RATHER THAN A TECHNICAL ONE
 *
 * Gluon Substrate ships an Activity of its own and used to supply this. That file is
 * GPL-3.0-or-later with no classpath exception, and it was dexed into every APK this
 * project built until this class replaced it, which conflicts with shipping the app under
 * 0BSD. The app behaves identically either way; the difference is the license on the binary.
 *
 * WHAT IS DELIBERATELY UNCHANGED
 *
 * Replacing Gluon's Activity had to leave behavior unchanged, so the window is set up the
 * way Gluon's Activity set it up, including two things that look like bugs and are not:
 *
 *   - The window does NOT resize when the keyboard appears. A dialog that would sit under
 *     the keyboard moves itself. The setting was later switched to ADJUST_RESIZE for what
 *     it reports, not for what it does; see the comment in onCreate.
 *   - Edge to edge is on, and both bars are transparent, so the app draws under the status
 *     and navigation bars and shows through them. The 3D view wants that (it is full bleed
 *     and looks better than the original), and the 2D chrome pads itself through
 *     ui/SystemInsets.
 *
 * ⚠ The replacement copied only half of the second one, and the bars went opaque for three
 * weeks without anyone noticing. See setEdgeToEdge.
 *
 * HOW IT CONNECTS
 *
 *   Android  ->  this class  ->  native/invmgr_*.c  ->  OpenJFX's own Android backend
 *
 * The native half is small because OpenJFX already contains a complete Android backend
 * (libglass_monocle.a, GPLv2 with the Classpath Exception). It needs a Surface, a screen
 * density, and input. It asks us for nothing.
 */
public class InvMgrActivity extends Activity implements SurfaceHolder.Callback, SurfaceHolder.Callback2 {

    private static final String TAG = "InvMgr";

    /**
     * The one and only instance. Static because everything the native side calls on this class
     * is static; the ones that need Android's UI thread reach it through this field.
     */
    private static InvMgrActivity instance;

    private static SurfaceView view;
    private static InputMethodManager ime;

    /**
     * The keyboard layout to ask for the next time the IME connects. JavaFX sets this
     * through setKeyboardType when a text field takes focus, so a number field can get the
     * dial pad instead of the full QWERTY.
     */
    private static int inputType = InputType.TYPE_CLASS_TEXT;

    /**
     * Guards against starting the app twice.
     *
     * ⚠ STATIC ON PURPOSE. Android can destroy and recreate the Activity while keeping the
     * process alive, and a fresh instance would otherwise arrive with this false and start
     * a second isolate. GraalVM does not allow that: the second attempt aborts the whole VM
     * inside Isolates.setCurrentIsFirstIsolate, which prints a register dump and nothing
     * that names the cause. Seen on 2026-09-03 after the app died on a font and was
     * relaunched into the same process.
     */
    private static boolean started;

    /**
     * Pixels per logical unit, from the phone's DisplayMetrics.
     *
     * JavaFX works in logical units rather than device pixels. On the test device the
     * screen is 1080x2220 with a density of 3, and JavaFX's viewport is 360x740. Touch
     * coordinates have to be divided by this before they are sent, or every tap lands
     * three times too far right and too far down, which for most of the screen is nowhere.
     */
    private static float density = 1f;

    static {
        // The native image is packaged as libsubstrate.so regardless of the app's name;
        // Substrate copies the linked library to that fixed filename. Loading it here runs
        // JNI_OnLoad in native/invmgr_launcher.c, which is what caches the JavaVM pointer.
        System.loadLibrary("substrate");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // ⚠ ADJUST_RESIZE IS HERE FOR WHAT IT MEASURES, NOT FOR WHAT IT IS NAMED AFTER.
        //
        // It does not resize anything in this app and cannot: an edge-to-edge window is never
        // resized for the keyboard, and setEdgeToEdge below is not optional because the 3D
        // view is full-bleed by design. Measured on the phone 2026-09-03: with it set, the
        // window stayed 1080x2220 with the keyboard up and surfaceChanged never fired.
        //
        // What it does change is what getRootWindowInsets REPORTS, and that is the only way
        // to learn the keyboard's height before API 30. With ADJUST_NOTHING the bottom inset
        // stays at the navigation bar's 144 whatever the keyboard does; with this it goes to
        // 1020 when the keyboard is up. systemInsets() below hands that number to the app so
        // a dialog can move clear of the keyboard.
        //
        // So do not "simplify" this back to ADJUST_NOTHING because the window does not
        // resize. The window not resizing is the point; the reading is the reason.
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        // RGBA_8888 rather than the default. The 3D view blends, and a 16 bit surface bands
        // visibly on the room's shaded walls.
        getWindow().setFormat(PixelFormat.RGBA_8888);

        // Draw under the system bars. ui/SystemInsets is what keeps the 2D controls clear of
        // them; the 3D view wants the full screen.
        setEdgeToEdge();

        view = new InvMgrSurfaceView(this);
        view.getHolder().addCallback(this);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);

        ViewGroup group = new FrameLayout(this);
        group.addView(view);
        setContentView(group);
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            rehideBarsAfterKeyboard();
            return v.onApplyWindowInsets(insets);
        });
        if (backArrivesAsCallback()) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::backAsEscape);
        }

        ime = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        sweepAwayOldFontCopies();
        Log.v(TAG, "InvMgrActivity created");
    }

    /**
     * Deletes the copies of the bundled font that previous launches left behind.
     *
     * ⚠ THE APP LEAKS ONE OF THESE EVERY TIME IT STARTS, and nothing else clears them. Found
     * on the test phone 2026-09-04 with SIXTY-FIVE of them, 48 MB, in the app's own data
     * directory; on somebody's phone that grows for as long as they use the app.
     *
     * The cause is a consequence of a decision that is otherwise correct. {@code launchArguments}
     * sets {@code java.io.tmpdir} to the app's private directory, because that is the only place
     * an Android app may write and JavaFX needs somewhere to unpack a bundled font before
     * handing it to freetype. JavaFX writes the file and never removes it, and a private
     * directory is not a real temporary directory, so nothing else removes it either.
     *
     * Only files matching JavaFX's own name for them are touched, and only ones from before
     * this launch, so a copy in use right now cannot be pulled out from under it.
     */
    private void sweepAwayOldFontCopies() {
        java.io.File[] leftovers = getApplicationInfo() == null ? null
                : new java.io.File(getApplicationInfo().dataDir)
                        .listFiles((directory, name) -> name.startsWith("+JXF") && name.endsWith(".tmp"));
        if (leftovers == null) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        int swept = 0;
        for (java.io.File leftover : leftovers) {
            if (leftover.lastModified() < startedAt && leftover.delete()) {
                swept++;
            }
        }
        if (swept > 0) {
            Log.v(TAG, "swept " + swept + " font copies left by earlier launches");
        }
    }

    /**
     * Lets the app draw behind the status and navigation bars, and makes both bars
     * transparent so it shows through them, with light icons on top.
     *
     * Done by hand rather than through androidx's WindowCompat.enableEdgeToEdge, which is
     * what Gluon's Activity called, so that this class does not drag in androidx for one
     * call. androidx is on the Dalvik classpath anyway, but the fewer things this file
     * depends on the fewer ways the build can break.
     *
     * ⚠ SO THIS HAS TO DO EVERYTHING THAT CALL DID TO THE BARS, and at first it did not.
     * Read out of androidx core 1.17.0's bytecode on 2026-09-24: besides the layout flags,
     * enableEdgeToEdge sets both bar colors to transparent and turns off Android 10's
     * contrast scrim. The first version copied only the flags. With no colors set the window
     * keeps the default theme's (the manifest names no theme): a black status bar and a
     * #f2f2f2 navigation bar, which the window paints itself, over the app. On the Samsung
     * test phone the light navigation bar also brought dark icons with it, apparently because
     * Samsung picks the icon shade from the bar's color. Every build from the day this class
     * replaced Gluon's to 2026-09-24 shipped like that; installing each one on the phone found it.
     *
     * ⚠ Two routes, because the app's minSdk is 21 and the modern calls arrived in API 30.
     * Compiling against API 36 makes them available to the compiler on every branch, so an
     * unguarded call would build cleanly and then throw NoSuchMethodError on an older phone.
     * The version checks are the whole point. The contrast calls arrived in API 29.
     *
     * ⚠ The API 30 route has never run on hardware: the test phone is Android 10, API 29.
     * Below 30 the icons are light because the flags set here leave the two "light bar" bits
     * out; from 30 on they are asked for through the insets controller instead.
     */
    @SuppressWarnings("deprecation")
    private void setEdgeToEdge() {
        Window window = getWindow();
        // ⚠ THE DECOR VIEW FIRST, FOR TWO REASONS, BOTH FOUND ON EMULATORS 2026-09-29. This runs
        // in onCreate, before setContentView would build the window's decor view, and
        // getDecorView() builds it now.
        // - Android 11 CRASHED ON LAUNCH without it: its PhoneWindow.getInsetsController, used
        //   below, reads the mDecor field with no check (read out of that image's
        //   framework.jar), so it threw a NullPointerException the moment the app opened.
        // - Android 16 PAINTED THE NAVIGATION BAR without it: the transparent color set below
        //   did not survive the decor view being built afterwards, and three-button navigation
        //   drew the theme's window background at 80% over the app, (26,27,31) over the 2D
        //   view's (26,26,26) and a visible band over the 3D room. Built first, the bar shows
        //   the app's own pixels.
        window.getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }

        // ⚠ Google Play reports both setters as deprecated edge-to-edge APIs, and they are, from
        // API 35. Do not drop them to quiet that report. Measured on the Android 15 and 16
        // emulators 2026-10-28 with three-button navigation: without them Android 15 drew
        // exactly the same, but Android 16 drew the back, home and recents buttons dim gray
        // (#666666) instead of white. The status bar setter made no difference on either.
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        // Left on, these let Android 10 and later decide a transparent bar needs a scrim to
        // keep its icons readable against the app, and draw one: a band over the room that
        // Gluon's build never had. (Android 10's Window.java, the javadoc on both setters.)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }

        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.getInsetsController().setSystemBarsAppearance(0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
    }

    /**
     * Whether the user asked for the status and navigation bars to be hidden (the Settings
     * window's Hide system bars option).
     *
     * Kept here as well as in the app's settings file because Android can put the bars back on
     * its own, and the Activity is the one that hears about it: see onWindowFocusChanged.
     */
    private static volatile boolean barsHidden;

    /**
     * Hides the status and navigation bars until a swipe in from an edge brings them back for a
     * moment, or shows them again.
     *
     * The two routes do the same thing, which androidx's WindowInsetsControllerCompat was read to
     * confirm (core 1.17.0's bytecode, 2026-09-25), since Android's own page on immersive mode
     * describes only the API 30 route:
     *
     * - From API 30: the window's insets controller hides WindowInsets.Type.systemBars() with
     *   BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE. ⚠ Never run on hardware: the test phone is API 29.
     * - Below 30: the flags androidx sets itself below 30. SYSTEM_UI_FLAG_FULLSCREEN hides the
     *   status bar, SYSTEM_UI_FLAG_HIDE_NAVIGATION the navigation bar, and
     *   SYSTEM_UI_FLAG_IMMERSIVE_STICKY is what "show transient bars by swipe" turns into there.
     *   They are added to the edge-to-edge flags setEdgeToEdge set, never in place of them.
     *
     * Android's page (developer.android.com, "Hide system bars for immersive mode", read
     * 2026-09-25) says the bars a swipe reveals "overlay your app's content" and "are
     * automatically hidden after a short timeout", so nothing in the app moves when they appear.
     */
    static void setSystemBarsHidden(boolean hidden) {
        barsHidden = hidden;
        if (instance != null) {
            instance.runOnUiThread(instance::applySystemBars);
        }
    }

    @SuppressWarnings("deprecation")
    private void applySystemBars() {
        Window window = getWindow();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller == null) {
                return;
            }
            if (barsHidden) {
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(android.view.WindowInsets.Type.systemBars());
            } else {
                controller.show(android.view.WindowInsets.Type.systemBars());
            }
            return;
        }
        View decor = window.getDecorView();
        int hiding = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        int flags = decor.getSystemUiVisibility();
        decor.setSystemUiVisibility(barsHidden ? flags | hiding : flags & ~hiding);
    }

    /**
     * Puts the bars away again when the window gets focus back, if they are meant to be hidden.
     *
     * ⚠ NOT A PRECAUTION: WITHOUT IT THE BARS COME BACK. Measured on the test phone 2026-09-25 with
     * this method's body taken out: bars hidden, flags 0x970e; one trip to the file picker and
     * back, and Android had cleared SYSTEM_UI_FLAG_FULLSCREEN and _HIDE_NAVIGATION, 0x9708, with
     * both bars showing. The task switcher and Home did the same. With it, all three come back
     * 0x970e. The device suite's "the bars stay hidden after leaving the app" holds it there.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && barsHidden) {
            applySystemBars();
        }
    }

    /**
     * Puts the bars away again once the keyboard has gone, if they are meant to be hidden.
     *
     * ⚠ THE KEYBOARD BRINGS THE BARS BACK, AND onWindowFocusChanged NEVER HEARS ABOUT IT, because
     * the keyboard does not take the window's focus. Measured on the test phone 2026-09-29 with
     * the bars hidden, 0x970e: the moment the keyboard showed, the window manager cleared
     * SYSTEM_UI_FLAG_FULLSCREEN and _HIDE_NAVIGATION on this window, 0x9718, and when the keyboard
     * went away it dropped only the light navigation bit, 0x9708, leaving both bars over the
     * island and the status line until something took the focus away and gave it back.
     *
     * Called from the view's insets listener, which runs on every change to the window's insets,
     * and the keyboard coming or going is one. While the keyboard is up this leaves the bars
     * alone: Android is the one showing them then, and hiding them under it would only be
     * cleared again. Once the keyboard's height reads zero, the bars go back.
     *
     * ⚠ THE KEYBOARD IS READ OFF THE ROOT INSETS, NOT THE ONES THE LISTENER IS HANDED. By the time
     * insets reach this view the window has used up their stable part, so the stable bottom reads
     * 0 and the navigation bar's 144 looks like a keyboard that never goes away. Logged on the
     * phone 2026-09-29, the first version of this failing because of it. The root insets are the
     * window's own, the ones systemInsets reads for the same reason.
     *
     * ⚠ Below API 30 only, where it was measured. The API 30 route hides the bars through the
     * insets controller instead, and on the Android 15 and 16 emulators the keyboard did not
     * bring them back there (2026-09-29); no Android 11+ phone has run it.
     */
    @SuppressWarnings("deprecation")
    private void rehideBarsAfterKeyboard() {
        android.view.WindowInsets root = view.getRootWindowInsets();
        if (!barsHidden || android.os.Build.VERSION.SDK_INT >= 30 || root == null
                || legacyKeyboardHeight(root) > 0) {
            return;
        }
        int hiding = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        if ((getWindow().getDecorView().getSystemUiVisibility() & hiding) != hiding) {
            // Posted rather than done here: this runs in the middle of Android handing out
            // insets, and changing the flags asks for a new layout of the same window.
            view.post(this::applySystemBars);
        }
    }

    /**
     * Ends the process along with the Activity.
     *
     * ⚠ THIS IS NOT TIDINESS, IT IS THE ONLY WAY OUT. A GraalVM native image can start
     * exactly one isolate per process. Android is happy to destroy an Activity and later
     * build a new one in the same process, and that second Activity would either try to
     * start a second isolate, which aborts the VM inside Isolates.setCurrentIsFirstIsolate,
     * or find the guard already set and sit there showing nothing.
     *
     * Killing the process makes the next launch a genuinely fresh one. Gluon's Activity does
     * the same thing for the same reason.
     */
    @Override
    protected void onDestroy() {
        Log.v(TAG, "onDestroy, ending the process");
        super.onDestroy();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    // ---- Surface lifecycle ------------------------------------------------------------
    //
    // JavaFX cannot draw before it has a Surface, and must not draw after it loses one.
    // That second half is not a detail: when the app is backgrounded Android releases the
    // native window, and calling EGL with a stale one makes libEGL log an error for every
    // frame. The native side keeps a validity flag for exactly this and the C guards both
    // EGL calls with it.

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.v(TAG, "surfaceCreated");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.v(TAG, "surfaceChanged, " + width + "x" + height + " format " + format);

        density = getResources().getDisplayMetrics().density;
        nativeSetDensity(density);
        nativeSetSurface(holder.getSurface());
        surfaceHeld = true;
        if (started) {
            uiThread.removeCallbacks(redrawTheNewSurface);
            for (long delay : REDRAW_AFTER_MS) {
                uiThread.postDelayed(redrawTheNewSurface, delay);
            }
        }

        if (!started) {
            started = true;
            // Two things matter here and both cause a hang if got wrong.
            //
            // Start only once the surface exists, because JavaFX queries the screen while it
            // starts up and gets nonsense if there is nothing to query.
            //
            // And start on a thread of its own, because nativeStartApp does not return: it
            // runs the whole JavaFX application. Calling it from surfaceChanged would block
            // the UI thread forever and Android would kill the app for not responding.
            Thread launcher = new Thread(() -> nativeStartApp(launchArguments()), "InvMgr-launcher");
            launcher.setDaemon(false);
            launcher.start();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.v(TAG, "surfaceDestroyed");
        surfaceHeld = false;
        uiThread.removeCallbacks(redrawTheNewSurface);
        nativeSetSurface(null);
    }

    /** Whether the app has a surface right now: from surfaceChanged to surfaceDestroyed. */
    private static volatile boolean surfaceHeld;

    /**
     * How long after a returning surface to ask JavaFX, again, to redraw everything.
     *
     * ⚠ WITHOUT THESE THE SCREEN CAN STAY BLACK after the app comes back, for as long as nothing
     * on screen changes. Seen on the Android 15 emulator 2026-09-29, one return from the file
     * picker in about eight, and caught with every EGL swap logged: Android's own redraw
     * request (surfaceRedrawNeeded) arrives while JavaFX still holds the GL surface of the
     * window it lost, so that full frame is drawn into the dead surface and its swap fails
     * (EGL_BAD_SURFACE). JavaFX then builds a surface on the new window and draws only what it
     * thinks changed. A good return logged two swaps after that and showed the app; a black
     * one logged one, then nothing, for as long as it was watched; a tap on blank space did
     * not wake it. Two later requests, by which time JavaFX has built its new surface, give it
     * the full frame the first one lost.
     */
    private static final long[] REDRAW_AFTER_MS = { 500, 1500 };

    /** Asks JavaFX to redraw everything, if the surface it would draw on is still there. */
    private static final Runnable redrawTheNewSurface = () -> {
        if (surfaceHeld && instance != null) {
            instance.nativeSurfaceRedrawNeeded();
        }
    };

    @Override
    public void surfaceRedrawNeeded(SurfaceHolder holder) {
        nativeSurfaceRedrawNeeded();
    }

    /**
     * The settings that can only be worked out on the phone, at the moment the app starts.
     *
     * The fixed part of the argument list lives in native/invmgr_launcher.c, because it never
     * changes. These four cannot: they depend on where Android put this app and where the
     * user is.
     *
     * ⚠ EVERY ONE OF THESE IS LOAD-BEARING, and leaving them out fails a long way from here.
     *
     *   user.home       the app's private directory, which is the ONLY place it may write.
     *                   Autosave and the save file both write relative to this, and it is
     *                   the whole reason this app needs no Gluon Attach library and no
     *                   storage permission. Wrong value, silent data loss.
     *   java.io.tmpdir  where JavaFX writes a bundled font before handing it to freetype.
     *                   The default is /tmp, which an Android app cannot write to, and the
     *                   failure surfaces as Font.loadFont simply returning null with nothing
     *                   logged. That was the second black screen, on 2026-09-03.
     *   android.tmpdir  read by some of the platform code for the same purpose.
     *   user.timezone   otherwise dates are computed in the wrong zone.
     *
     * Gluon's Activity passed three more, for Attach's launch URL and its notification
     * hooks. This app has no Attach and no notifications, so they are deliberately absent.
     */
    private String[] launchArguments() {
        String dataDir = getApplicationInfo().dataDir;
        java.util.List<String> args = new java.util.ArrayList<>(java.util.Arrays.asList(
            "-Duser.home=" + dataDir,
            "-Djava.io.tmpdir=" + dataDir,
            "-Dandroid.tmpdir=" + dataDir,
            "-Duser.timezone=" + java.util.TimeZone.getDefault().getID()));

        String verbose = verboseTopicsFromTheIntent();
        if (verbose != null) {
            args.add("-Dinvmgr.verbose=" + verbose);
            java.util.List<String> words = java.util.Arrays.asList(verbose.split(","));
            keyboardLogging = words.contains("keyboard") || words.contains("all");
        }
        return args.toArray(new String[0]);
    }

    /**
     * Whether JavaFxInputConnection logs the keyboard's calls, which only the verbose word
     * "keyboard" (or "all") turns on:
     *
     *   adb shell am start -n \
     *       com.asterkazika.invmgr/com.modcritic.invmgr.android.InvMgrActivity --es verbose keyboard
     *
     * Off by default because Android's log keeps them, and before 2026-10-06 they were always
     * on and held every word typed into the app. They now hold lengths and key names instead;
     * see JavaFxInputConnection.lengthOf.
     */
    private static volatile boolean keyboardLogging;

    /**
     * The one launch setting a person outside the phone is allowed to choose.
     *
     * A desktop passes -Dinvmgr.verbose on the command line. A phone has no command line, so
     * the same switch arrives as an intent extra and is copied into the argument vector here:
     *
     *   adb shell am start -n \
     *       com.asterkazika.invmgr/com.modcritic.invmgr.android.InvMgrActivity --es verbose latency
     *
     * The class name is written out in full because the app ID and this class's package are
     * different strings since 2026-09-22. A leading dot would resolve against the app ID and
     * name a class that does not exist.
     *
     * ⚠ WHY ONLY THIS ONE, and why the value is checked rather than passed through. Everything
     * in launchArguments() above puts JavaFX on its Android paths and the app does not start
     * without them; an intent that could add arbitrary -D flags could break any of that, and
     * an exported Activity takes intents from anything on the phone. So the allowed words are
     * the topic names Verbose already knows, matched exactly, and anything else is dropped
     * without the app starting differently.
     *
     * Added because the phone is the machine the latency suite (tools/perf) most needs to hear
     * from and there was no other way to ask it.
     */
    private String verboseTopicsFromTheIntent() {
        Intent intent = getIntent();
        String asked = intent == null ? null : intent.getStringExtra("verbose");
        if (asked == null) {
            return null;
        }
        StringBuilder kept = new StringBuilder();
        for (String word : asked.split(",")) {
            String trimmed = word.trim().toLowerCase(java.util.Locale.ROOT);
            // The same words Verbose understands, written out because this class is compiled
            // for Dalvik and cannot see the app's own classes: the two halves live in separate
            // VMs (see the class documentation on the bridge). A word added to Verbose.Topic and
            // forgotten here never reaches the app: this loop drops it, and the warning it logs,
            // "ignoring unknown verbose topic", is what says so. A word listed here that Verbose
            // does not know does reach the app, and the app's own complaint is what says so.
            if (trimmed.equals("all") || trimmed.equals("3d") || trimmed.equals("latency")
                    || trimmed.equals("keyboard")) {
                kept.append(kept.length() == 0 ? "" : ",").append(trimmed);
            } else {
                Log.w(TAG, "ignoring unknown verbose topic from the intent: " + trimmed);
            }
        }
        if (kept.length() == 0) {
            return null;
        }
        Log.v(TAG, "verbose logging asked for: " + kept);
        return kept.toString();
    }

    // ---- Input ------------------------------------------------------------------------

    /**
     * Not an Android action code. OpenJFX uses -1 for a finger that did not change in this
     * batch, and its own converter turns that into JavaFX's TOUCH_STILL.
     *
     * Verified by disassembling to_jfx_touch_action inside libglass_monocle.a: it accepts
     * -1 through 6, and the first entry of its table, the one for -1, is 814, which is
     * TOUCH_STILL. Anything outside that range returns 0, which is not a touch state.
     */
    private static final int ACTION_POINTER_STILL = -1;

    /**
     * Repackages one Android MotionEvent into the four parallel arrays JavaFX wants.
     *
     * Android delivers every finger in one event with an index per pointer. JavaFX wants an
     * array of actions, one of ids, and one each of x and y, in its own action codes, which
     * the native side converts using OpenJFX's own table rather than this class guessing.
     *
     * ⚠ Two things here are easy to get wrong, and the first version got both wrong.
     *
     * COORDINATES ARE DIVIDED BY DENSITY, because JavaFX works in logical units. Sending
     * device pixels puts every touch at three times the right position on this phone, which
     * for most of the screen means off the end of it.
     *
     * A FINGER THAT DID NOT CHANGE IS MARKED STILL, not moved. When one finger goes down or
     * lifts, only that finger has an action. Claiming the others moved makes JavaFX believe
     * a multi-finger drag is under way, and it also changes which pointer the native side
     * picks as the primary one. The exception is a plain move, where every finger really
     * did move and all of them carry ACTION_MOVE.
     */
    private void dispatchTouch(MotionEvent event) {
        int actionCode = event.getActionMasked();

        // ACTION_OUTSIDE maps to 0, which is not a JavaFX touch state at all, so it is
        // dropped here rather than sent and misread.
        if (actionCode == MotionEvent.ACTION_OUTSIDE) {
            return;
        }

        int count = event.getPointerCount();
        int[] actions = new int[count];
        int[] ids = new int[count];
        int[] xs = new int[count];
        int[] ys = new int[count];

        int actionIndex = event.getActionIndex();
        boolean everyPointerMoved = actionCode == MotionEvent.ACTION_MOVE;

        // A canceled gesture ends every finger at once. Marking only the action index would
        // leave JavaFX believing the rest are still down, with nothing further coming to
        // correct it, which strands a phantom pointer for the life of the app.
        boolean everyPointerEnded = actionCode == MotionEvent.ACTION_CANCEL;

        for (int i = 0; i < count; i++) {
            actions[i] = everyPointerMoved || everyPointerEnded || i == actionIndex
                    ? actionCode : ACTION_POINTER_STILL;
            ids[i] = event.getPointerId(i);
            xs[i] = (int) (event.getX(i) / density);
            ys[i] = (int) (event.getY(i) / density);
        }
        nativeTouchEvent(count, actions, ids, xs, ys);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // The IME will not open for a view that does not hold focus, and the tap that should
        // have given it focus is usually this one.
        if (view != null && !view.isFocused()) {
            view.requestFocus();
        }
        dispatchTouch(event);
        return true;
    }

    // ---- Keys -------------------------------------------------------------------------
    //
    // ⚠ EVERYTHING HERE SPEAKS GLASS, NOT ANDROID. The values below are JavaFX's own, and
    // they go to the native side unconverted. The first version ran them through OpenJFX's
    // to_jfx_key_action and to_linux_keycode helpers, which are for a different caller and
    // produce Linux evdev codes; backspace arrived as 14, which is not a JavaFX key, and
    // typing produced nothing at all.

    /** Glass KeyEvent.PRESS. */
    private static final int GLASS_PRESS = 111;
    /** Glass KeyEvent.RELEASE. */
    private static final int GLASS_RELEASE = 112;
    /**
     * Glass KeyEvent.TYPED, and the one that actually puts a character in a field.
     *
     * PRESS and RELEASE carry a key, not text. JavaFX discards the characters attached to
     * them. Without a TYPED event every text field on the phone is read-only, which is
     * exactly how the first version behaved.
     */
    private static final int GLASS_TYPED = 113;

    private static final int MODIFIER_SHIFT = 1;
    private static final int MODIFIER_CONTROL = 2;
    private static final int MODIFIER_ALT = 4;
    private static final int MODIFIER_META = 8;

    private static int modifiersOf(android.view.KeyEvent event) {
        int meta = event.getMetaState();
        int mods = 0;
        if ((meta & android.view.KeyEvent.META_SHIFT_MASK) != 0) mods |= MODIFIER_SHIFT;
        if ((meta & android.view.KeyEvent.META_CTRL_MASK) != 0)  mods |= MODIFIER_CONTROL;
        if ((meta & android.view.KeyEvent.META_ALT_MASK) != 0)   mods |= MODIFIER_ALT;
        if ((meta & android.view.KeyEvent.META_META_ON) != 0)    mods |= MODIFIER_META;
        return mods;
    }

    /**
     * Android key code to JavaFX key code.
     *
     * Only the keys this app can actually receive are listed. Everything that produces a
     * character is handled by the TYPED event instead, which carries the character itself
     * and needs no key identity, so this map covers the keys that DO something rather than
     * the keys that type something.
     *
     * javafx.scene.input.KeyCode is compiled into the Android side of the build for exactly
     * this purpose; it is Oracle's, under GPLv2 with the Classpath Exception.
     */
    private static javafx.scene.input.KeyCode toJavaFxKey(int androidKeyCode) {
        switch (androidKeyCode) {
            case android.view.KeyEvent.KEYCODE_DEL:         return javafx.scene.input.KeyCode.BACK_SPACE;
            case android.view.KeyEvent.KEYCODE_FORWARD_DEL: return javafx.scene.input.KeyCode.DELETE;
            case android.view.KeyEvent.KEYCODE_ENTER:       return javafx.scene.input.KeyCode.ENTER;
            case android.view.KeyEvent.KEYCODE_TAB:         return javafx.scene.input.KeyCode.TAB;
            case android.view.KeyEvent.KEYCODE_ESCAPE:      return javafx.scene.input.KeyCode.ESCAPE;
            // The phone's Back button. The app has no other use for it, and Escape is what
            // its dialogs already close on.
            case android.view.KeyEvent.KEYCODE_BACK:        return javafx.scene.input.KeyCode.ESCAPE;
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT:   return javafx.scene.input.KeyCode.LEFT;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:  return javafx.scene.input.KeyCode.RIGHT;
            case android.view.KeyEvent.KEYCODE_DPAD_UP:     return javafx.scene.input.KeyCode.UP;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN:   return javafx.scene.input.KeyCode.DOWN;
            default:                                        return javafx.scene.input.KeyCode.UNDEFINED;
        }
    }

    /**
     * The phone's Back button as Android 16 and later deliver it: Escape to the app, as the key
     * always was (see toJavaFxKey).
     *
     * ⚠ WITHOUT IT, BACK LEAVES THE APP ON ANDROID 16. For an app targeting API 36 on Android 16
     * or later, Back goes to callbacks like this one (developer.android.com, "Behavior changes:
     * apps targeting Android 16", read 2026-09-29), and with none registered Android's own
     * default sent the app to the home screen. Found on an Android 16 emulator 2026-09-29: Back
     * with the Menu open closed the Menu AND left the app.
     *
     * ⚠ AND THE KEY STILL ARRIVES THERE TOO, which is why dispatchKeyEvent drops it on these
     * versions. Google's page says KEYCODE_BACK "is not dispatched anymore", and onKeyDown and
     * onBackPressed are indeed quiet, but the Activity's own dispatchKeyEvent still got the key
     * down and up, with this callback running between them (logged on the emulator). Handling
     * both sent two Escapes, and Back on About closed the whole window instead of going back one
     * page.
     */
    private void backAsEscape() {
        char[] none = javafx.scene.input.KeyCode.ESCAPE.impl_getChar().toCharArray();
        sendKey(GLASS_PRESS, javafx.scene.input.KeyCode.ESCAPE, none, 0);
        sendKey(GLASS_RELEASE, javafx.scene.input.KeyCode.ESCAPE, none, 0);
    }

    /**
     * Whether Back reaches the app as an OnBackInvokedCallback rather than as a key.
     *
     * ⚠ API 36 AND UP, BECAUSE THE APP TARGETS 36. Android turns the callback on by default only
     * when both the phone and the app's target are 36 or later; from API 33 to 35 it needs
     * android:enableOnBackInvokedCallback in the manifest, which this app does not set, so
     * there the key is the only route, as it is below 33. Lower the target below 36 and this
     * has to change with it.
     */
    private static boolean backArrivesAsCallback() {
        return android.os.Build.VERSION.SDK_INT >= 36;
    }

    /** Sends one Glass key event. */
    private void sendKey(int glassType, javafx.scene.input.KeyCode code, char[] chars, int mods) {
        int count = chars == null ? 0 : chars.length;
        nativeKeyEvent(glassType, code.impl_getCode(), chars, count, mods);
    }

    /** A character the user typed, which is the only thing that puts text in a field. */
    private void sendTyped(char[] chars, int mods) {
        if (chars == null || chars.length == 0) {
            return;
        }
        nativeKeyEvent(GLASS_TYPED, javafx.scene.input.KeyCode.UNDEFINED.impl_getCode(),
                       chars, chars.length, mods);
    }

    /**
     * Turns one Android key event into the Glass events JavaFX expects.
     *
     * A key that produces a character sends three events, not two: press, release, and then
     * typed carrying the character. JavaFX needs all three, and the third is the one that
     * makes the character appear.
     *
     * ⚠ It types every character it is handed, so it must never be handed a copy of text
     * already typed. The only source of such copies is BaseInputConnection's own resend, and
     * {@link JavaFxInputConnection#sendKeyEvent} drops that before calling this.
     */
    private void processKeyEvent(android.view.KeyEvent event) {
        int mods = modifiersOf(event);
        javafx.scene.input.KeyCode code = toJavaFxKey(event.getKeyCode());

        switch (event.getAction()) {
            case android.view.KeyEvent.ACTION_DOWN:
                sendKey(GLASS_PRESS, code, code.impl_getChar().toCharArray(), mods);
                break;

            case android.view.KeyEvent.ACTION_UP:
                sendKey(GLASS_RELEASE, code, code.impl_getChar().toCharArray(), mods);
                int unicode = event.getUnicodeChar();
                if (unicode != 0) {
                    sendTyped(Character.toChars(unicode), mods);
                }
                break;

            case android.view.KeyEvent.ACTION_MULTIPLE:
                // The deprecated way of delivering a run of characters at once. The
                // superclass's resend uses it and never gets this far, so one that does is
                // the only copy of its text.
                String characters = event.getCharacters();
                if (characters != null) {
                    sendTyped(characters.toCharArray(), mods);
                }
                break;

            default:
                break;
        }
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        // Volume stays with Android. Everything else, including the Back button, belongs to
        // the app, which is why this returns true rather than falling through to the system.
        int code = event.getKeyCode();
        if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP
                || code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                || code == android.view.KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event);
        }
        // Back is backAsEscape's on the versions where it arrives as a callback; the key that
        // still comes here as well would be a second Escape. See backAsEscape.
        if (code == android.view.KeyEvent.KEYCODE_BACK && backArrivesAsCallback()) {
            return true;
        }
        // A real key, from a hardware keyboard or from `adb shell input`. Nothing else has
        // delivered its character.
        processKeyEvent(event);
        return true;
    }

    // ---- Called from the native side --------------------------------------------------
    //
    // These are looked up by JNI from native/invmgr_launcher.c and native/invmgr_bridge.c.
    // Renaming one, or changing a signature, breaks the app at runtime with no compiler
    // complaint, because the lookup is by string. Change both sides together.

    /**
     * How long a hide waits to find out whether a show is right behind it.
     *
     * ⚠ THIS IS WHAT STOPS THE SCREEN FLASHING, and the flash was on every move from one
     * text box to the next, not only on the keypad's Next key that the user noticed it on.
     *
     * Moving focus between two JavaFX text fields makes two of JavaFX's own skins act: the
     * box being left asks for the keyboard to go away and the box being entered asks for it
     * back. Both requests arrive here within a few milliseconds and the old code obeyed both,
     * so Samsung's keyboard was torn down and rebuilt every time. Recorded off the phone at
     * 30 frames a second on 2026-09-05: the bottom third of the screen goes black for about
     * seven frames, 76% of the picture repainting in one of them where a caret moving is
     * under 3%. After the keypad's Next it is worse, because the keyboard stays down long
     * enough for the dialog to read a keyboard height of zero and drop back to the middle of
     * the screen before rising again.
     *
     * So a hide is held for a moment first. A show cancels it, and the pair cancels out.
     * The number only has to outlast a frame or two of the app's own; a real hide arriving
     * a fifth of a second late is not something anyone can see, because the keyboard's own
     * slide away takes longer than that.
     */
    private static final long HIDE_SETTLE_MS = 200;

    /** The UI thread, for the held hide above. */
    private static final android.os.Handler uiThread =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Whether the app wants the keyboard up: true from a show until a hide has gone through.
     *
     * ⚠ THIS IS WHAT KEEPS THE KEYBOARD FROM COMING BACK BY ITSELF, reported by the user
     * 2026-09-24: type in a box, close the dialog it was in, go to the task switcher and come
     * back, and the keyboard was up again over a room with no box on it.
     *
     * The app did not ask for that. Android did. On coming back the window is marked
     * IS_FORWARD_NAVIGATION, and with STATE_UNSPECIFIED and ADJUST_RESIZE (both needed, see
     * onCreate) Android 10 shows the keyboard by itself for any focused view that calls
     * itself a text editor. This view used to say yes unconditionally. Read off the phone
     * with dumpsys input_method: the keyboard after the return was up with
     * mShowExplicitlyRequested=false, where every show this class asks for reads true.
     *
     * So the view calls itself a text editor only while this is true. Coming back after the
     * keyboard was put away brings nothing up; coming back while still typing lets Android
     * put it back, which is the one case where it should.
     */
    private static volatile boolean keyboardWanted;

    /** The hide, once it has waited its turn and no show has overtaken it. */
    private static final Runnable dropTheKeyboard = () -> {
        keyboardWanted = false;
        if (ime != null && view != null && view.getWindowToken() != null) {
            ime.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    };

    /**
     * What the IME's live connection was built with.
     *
     * Kept so that a show can tell whether restarting the input would change anything. A
     * restart tears the connection down and builds a new one, which Samsung's keyboard
     * answers by redrawing itself, so doing it on every show is a second flash on top of
     * the one above. See setKeyboardType, which is where a restart is genuinely needed.
     */
    private static int connectedInputType = -1;

    /** JavaFX asks for the keyboard when a text field takes focus. */
    static void showIME() {
        if (instance == null || ime == null || view == null) {
            return;
        }
        instance.runOnUiThread(() -> {
            uiThread.removeCallbacks(dropTheKeyboard);
            boolean wasWanted = keyboardWanted;
            keyboardWanted = true;
            view.requestFocus();
            if (!wasWanted || connectedInputType != inputType) {
                // When the layout being asked for is not the one the keyboard is already
                // showing (onCreateInputConnection records what it was given), and also when
                // the keyboard was put away before this. While it was away the view said it
                // was no text editor, and a restart is how Android hears that it is one again.
                // The keyboard is down in that case, so the restart cannot flash it.
                ime.restartInput(view);
            }
            ime.showSoftInput(view, 0);
        });
    }

    static void hideIME() {
        if (instance == null || ime == null || view == null) {
            return;
        }
        instance.runOnUiThread(() -> {
            uiThread.removeCallbacks(dropTheKeyboard);
            uiThread.postDelayed(dropTheKeyboard, HIDE_SETTLE_MS);
        });
    }

    /**
     * What the phone has taken of the screen, and how much of it the keyboard is using.
     *
     * Returns five numbers in REAL pixels, in this order: top, right, bottom, left, keyboard.
     * The app divides by the density itself, because the density is already something it
     * knows and passing design pixels from here would put the same arithmetic in two places.
     *
     * This is the measurement {@code ui/SystemInsets} was written to wait for. Until this
     * existed it shipped Android's published 24 dip status bar as a constant, because the
     * only documented way to read the real one was a GPL-licensed library this app may not
     * link. Owning the Activity makes it a plain framework call.
     *
     * ⚠ The four edges matter far more in landscape than in portrait, which is what made
     * this worth doing now. Held upright the navigation bar is at the bottom and the old
     * constant was very nearly right; turned on its side the bar moves to one edge, the old
     * code returned zero for both sides, and the room drew underneath it.
     *
     * ⚠ Returns null before the view is attached to a window, and the app falls back to the
     * old arithmetic when it does. That is not a theoretical case: getRootWindowInsets is
     * documented to return null until then.
     */
    static int[] systemInsets() {
        if (view == null) {
            return null;
        }
        android.view.WindowInsets insets = view.getRootWindowInsets();
        if (insets == null) {
            return null;
        }

        int top;
        int right;
        int bottom;
        int left;
        int keyboard;

        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // ⚠ THE CAMERA CUTOUT AS WELL AS THE BARS. From Android 15, for an app targeting
            // 35 or later, Android lays every window into the cutout (developer.android.com,
            // "Behavior changes: apps targeting Android 15": SHORT_EDGES, NEVER and DEFAULT
            // "are interpreted as ALWAYS"), where before it kept apps out of it on its own when
            // the phone was sideways. Measured on the Android 15 emulator 2026-09-29 with a
            // punch hole simulated: sideways, the hole took 136 px down the left edge, where
            // the layer slider's tab sits, and the bars alone reserved nothing there. Asking for
            // both gives the larger of the two on each edge, and the cutout stays reserved while
            // the bars are hidden, so the island never sits under the camera either.
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
            top = bars.top;
            right = bars.right;
            bottom = bars.bottom;
            left = bars.left;
            // Already the whole covered height, and already zero when the keyboard is down,
            // so it means the same thing as the legacy branch below.
            keyboard = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
        } else {
            // ⚠ STABLE insets for the bars, not system-window ones. The system-window bottom
            // grows to include the keyboard, so using it here would make the status bar
            // reserve a third of the screen the moment anything was typed into. The stable
            // insets are the bars alone and do not move.
            top = legacyTop(insets);
            right = insets.getStableInsetRight();
            bottom = insets.getStableInsetBottom();
            left = insets.getStableInsetLeft();
            keyboard = legacyKeyboardHeight(insets);
        }

        return new int[] { top, right, bottom, left, keyboard };
    }

    /**
     * The top inset before API 30, taking a camera cut-out into account.
     *
     * The status bar usually covers the cut-out, in which case the two numbers agree. On a
     * phone where the cut-out is taller, it is the one that must be cleared, which is the
     * case ui/SystemInsets called out as a known limitation of the old constant.
     */
    @SuppressWarnings("deprecation")
    private static int legacyTop(android.view.WindowInsets insets) {
        int bar = insets.getStableInsetTop();
        android.view.DisplayCutout cutout = insets.getDisplayCutout();
        return cutout == null ? bar : Math.max(bar, cutout.getSafeInsetTop());
    }

    /**
     * How much of the window's bottom the keyboard is covering, before API 30.
     *
     * {@code WindowInsets.Type.ime()} arrived in API 30. Below it the reading comes from the
     * gap between two insets that Android does still report:
     *
     * <ul>
     *   <li>the <b>stable</b> bottom inset is the navigation bar and never moves: 144 here,
     *   <li>the <b>system window</b> bottom inset grows to cover the keyboard as well.
     * </ul>
     *
     * <p>Measured on the phone: 144 and 144 with the keyboard down, 144 and 1020 with it up.
     *
     * ⚠ This only reads anything at all because onCreate asks for ADJUST_RESIZE. With
     * ADJUST_NOTHING both numbers stay at 144 forever and the keyboard is invisible from here.
     * That was two hours of looking at the wrong measurement; see onCreate.
     *
     * <p>Returns the WHOLE covered height rather than the keyboard's own, because that is what
     * a dialog has to clear: the navigation bar is drawn over the keyboard, so the unusable
     * strip runs all the way to the bottom of the screen. Zero when the keyboard is down.
     */
    @SuppressWarnings("deprecation")
    private static int legacyKeyboardHeight(android.view.WindowInsets insets) {
        int covered = insets.getSystemWindowInsetBottom();
        return covered > insets.getStableInsetBottom() ? covered : 0;
    }

    // ---- Picking a file, which JavaFX cannot do here at all -----------------------------
    //
    // FileChooser throws UnsupportedOperationException on this platform: Monocle implements no
    // file chooser, so Save, Load and Export were all dead. Android's answer is the Storage
    // Access Framework (the system picker), which needs NO PERMISSIONS AT ALL. That last part
    // matters beyond convenience: this app requests no permissions and should stay that way,
    // so anything that asked for storage access would be a step backwards.
    //
    // ⚠ THE WHOLE THING IS A POLL, AND THAT IS NOT LAZINESS. Android answers a file picker by
    // calling onActivityResult, and the Activity has no way to call into the application:
    // the app is a native image with its own runtime and calls only go the other way. So the
    // answer is parked in these fields and the app asks for it once a frame until it arrives.
    // ModalDialog does the same for the keyboard and for the same reason.

    private static final int REQUEST_SAVE = 1;
    private static final int REQUEST_OPEN = 2;

    /** Nothing has been asked for. */
    static final int FILE_IDLE = 0;
    /** The picker is open and the user has not answered. */
    static final int FILE_PENDING = 1;
    /** It worked. For an open, the text is waiting in takeFileText. */
    static final int FILE_DONE = 2;
    /** The user backed out, which is not an error and gets no message. */
    static final int FILE_CANCELED = 3;
    /** It failed. takeFileError says why. */
    static final int FILE_FAILED = 4;

    /*
     * Written on Android's UI thread and read from the application's, so volatile. There is
     * only ever one file operation in flight (the app asks from a modal dialog or a bar
     * button and waits for the answer), so one slot each is enough.
     */
    private static volatile int fileState = FILE_IDLE;
    private static volatile String fileText;
    private static volatile String fileError;

    /**
     * Opens the system's "save as" picker, and writes {@code content} wherever it is pointed.
     *
     * The content is handed over now rather than fetched later because the application cannot
     * be called back into. By the time the user has chosen somewhere, the only thing that can
     * write the file is this class.
     */
    static void requestSaveFile(String suggestedName, String mimeType, String content) {
        if (instance == null) {
            return;
        }
        fileState = FILE_PENDING;
        fileText = content;
        fileError = null;
        instance.runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(mimeType);
                intent.putExtra(Intent.EXTRA_TITLE, suggestedName);
                instance.startActivityForResult(intent, REQUEST_SAVE);
            } catch (RuntimeException e) {
                fail("could not open the save picker: " + e.getMessage());
            }
        });
    }

    /**
     * Opens a web address in whatever browser the phone uses (the About window's Source code
     * link).
     *
     * Hands the address to Android as an ACTION_VIEW intent and nothing more. The browser makes
     * the connection; this app asks for no permission and opens no connection of its own, and
     * this project never allows one. A phone with nothing to open it with gets a line in the
     * log and no other sign, which is also all the desktop does when HostServices finds no
     * browser.
     */
    static void openLink(String url) {
        if (instance == null || url == null) {
            return;
        }
        instance.runOnUiThread(() -> {
            try {
                instance.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
            } catch (RuntimeException e) {
                Log.e(TAG, "could not open " + url + ": " + e.getMessage());
            }
        });
    }

    /**
     * Hands a feedback message to the phone's mail app (the Menu's Feedback entry), addressed
     * and ready to send.
     *
     * Android's own recipe for "only email apps" ("Common intents", Compose an email,
     * developer.android.com, read 2026-09-25): ACTION_SENDTO with a bare "mailto:" as its data,
     * and the address, subject and text as extras. On the test phone Gmail and Samsung Email both
     * answer it, so Android asks which to use unless one is the default. The mail app sends it;
     * this app still opens no connection of its own. A phone with no mail app gets a line in the
     * log, as openLink does.
     */
    static void composeEmail(String to, String subject, String body) {
        if (instance == null || to == null) {
            return;
        }
        instance.runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:"));
                intent.putExtra(Intent.EXTRA_EMAIL, new String[] { to });
                intent.putExtra(Intent.EXTRA_SUBJECT, subject);
                intent.putExtra(Intent.EXTRA_TEXT, body);
                instance.startActivity(intent);
            } catch (RuntimeException e) {
                Log.e(TAG, "could not open a mail app: " + e.getMessage());
            }
        });
    }

    /** Opens the system's file picker, and reads whatever is chosen into takeFileText. */
    static void requestOpenFile(String mimeType) {
        if (instance == null) {
            return;
        }
        fileState = FILE_PENDING;
        fileText = null;
        fileError = null;
        instance.runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(mimeType);
                instance.startActivityForResult(intent, REQUEST_OPEN);
            } catch (RuntimeException e) {
                fail("could not open the file picker: " + e.getMessage());
            }
        });
    }

    /**
     * How many times the keypad's Next key has been pressed.
     *
     * <p>A counter rather than a flag, so the app can tell one press from two even if it looks
     * between them, and so nothing has to be cleared from the other side.
     */
    private static volatile int nextFieldRequests;

    /** How many times Next has been pressed since the app started. */
    static int nextFieldRequests() {
        return nextFieldRequests;
    }

    /** Where the current file operation has got to. One of the FILE_ constants. */
    static int fileState() {
        return fileState;
    }

    /**
     * The text that was read, handed over exactly once.
     *
     * Clearing it matters: a save file is the largest thing this app holds in a String, and
     * keeping a reference after the app has taken it would pin it for the session.
     */
    static String takeFileText() {
        String text = fileText;
        fileText = null;
        fileState = FILE_IDLE;
        return text;
    }

    /** Why the last operation failed, handed over exactly once. */
    static String takeFileError() {
        String error = fileError;
        fileError = null;
        fileState = FILE_IDLE;
        return error;
    }

    private static void fail(String why) {
        Log.e(TAG, why);
        fileError = why;
        fileText = null;
        fileState = FILE_FAILED;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SAVE && requestCode != REQUEST_OPEN) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            // Backing out of the picker is an ordinary thing to do, not a failure.
            fileText = null;
            fileState = FILE_CANCELED;
            return;
        }

        android.net.Uri uri = data.getData();
        try {
            if (requestCode == REQUEST_SAVE) {
                writeTo(uri, fileText);
                fileText = null;
            } else {
                fileText = readFrom(uri);
            }
            fileState = FILE_DONE;
        } catch (java.io.IOException | RuntimeException e) {
            fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Writes text to a document the user picked.
     *
     * ⚠ "wt" rather than the default "w". A content URI opened for writing does NOT truncate,
     * so saving a short room over a long one would leave the tail of the old file behind and
     * produce something that does not parse. The t is the truncate.
     */
    private void writeTo(android.net.Uri uri, String content) throws java.io.IOException {
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) {
                throw new java.io.IOException("nothing would open " + uri);
            }
            out.write((content == null ? "" : content).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /**
     * The largest file readFrom reads, the same 4 MB as SaveFormat.MAX_FILE_BYTES in the app. Written
     * out here because this class runs in Android's own Java and cannot see the app's classes.
     * See SaveFormat for why 4 MB refuses no real room.
     */
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    /**
     * Reads a document the user picked, as UTF-8, which is what the save format is.
     *
     * ⚠ Counted as it is read, and refused past MAX_FILE_BYTES. Without a limit the whole file
     * was read into memory, on Android's main thread, before anything looked at it. A large
     * enough one would fill the memory Android gives this app, and the OutOfMemoryError that
     * follows is not one of the exceptions onActivityResult catches, so the app would close.
     * That is from reading the code; a 5 MB file did not do it on the test phone, and nothing
     * larger was tried. Counting covers every document, whatever its provider says about its
     * size.
     */
    private String readFrom(android.net.Uri uri) throws java.io.IOException {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new java.io.IOException("nothing would open " + uri);
            }
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > MAX_FILE_BYTES) {
                    // The same words SaveFormat.TOO_LARGE uses on a desktop.
                    throw new java.io.IOException("the file is over 4 MB, and no room file is that big.");
                }
            }
            return new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * Chooses which keyboard layout the next IME connection asks for.
     *
     * The values match JavaFX's own keyboard type ordering so the native side can pass what
     * it is given straight through.
     */
    static void setKeyboardType(int type) {
        int mapped;
        switch (type) {
            case 1:  mapped = InputType.TYPE_CLASS_NUMBER; break;
            case 2:  mapped = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL; break;
            case 3:  mapped = InputType.TYPE_CLASS_PHONE; break;
            case 4:  mapped = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI; break;
            case 5:  mapped = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; break;
            default: mapped = InputType.TYPE_CLASS_TEXT; break;
        }
        if (mapped == inputType) {
            return;
        }
        inputType = mapped;
        if (instance != null && ime != null && view != null) {
            instance.runOnUiThread(() -> ime.restartInput(view));
        }
    }

    // ---- The surface the app draws into -----------------------------------------------

    /**
     * A plain SurfaceView with one addition: it tells the IME what kind of keyboard to show
     * and turns typed characters into key events.
     *
     * JavaFX has no Android text field to attach an IME to. It draws its own, so the
     * keyboard has nothing real to edit. The way through is to accept an InputConnection
     * anyway and translate whatever the IME commits into key events, which is the form
     * JavaFX already understands.
     */
    private static final class InvMgrSurfaceView extends SurfaceView {

        InvMgrSurfaceView(Context context) {
            super(context);
        }

        /** Only while the app wants the keyboard. See keyboardWanted for why not always. */
        @Override
        public boolean onCheckIsTextEditor() {
            return keyboardWanted;
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            outAttrs.inputType = inputType;
            // What this connection ends up with, so showIME can tell whether restarting it
            // would change anything. See connectedInputType.
            connectedInputType = inputType;
            // NO_EXTRACT_UI stops the IME replacing the whole screen with its own text box
            // in landscape, which would cover the app.
            //
            // IME_ACTION_NEXT turns the keypad's Enter into a "go to the next box" key. Only
            // on a number field: the dimension boxes come in threes and are what the request
            // was about, while Enter in a name is more likely to mean "I am done".
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI;
            if ((inputType & InputType.TYPE_CLASS_NUMBER) != 0) {
                outAttrs.imeOptions |= EditorInfo.IME_ACTION_NEXT;
            }
            return new JavaFxInputConnection(this);
        }
    }

    /**
     * Turns IME edits into the Glass key events JavaFX expects.
     *
     * JavaFX draws its own text fields, so the keyboard has nothing real to edit. The way
     * through is to accept an input connection anyway and translate what the IME sends.
     *
     * ⚠ Committed text goes out as TYPED, not as a press and release. JavaFX throws away
     * the characters attached to a press; only a TYPED event puts anything in a field. The
     * first version sent press/release pairs and every text field on the phone was silently
     * read-only.
     *
     * <h2>⚠ TEXT ARRIVES TWICE, AND ANDROID SENDS THE SECOND COPY</h2>
     *
     * This is the trap in this class. Every call logged on the phone 2026-09-26, with
     * Samsung's keyboard:
     *
     * <pre>
     *   tap a letter               setComposingText("des"), and nothing else
     *   tap the suggestion "desk"  commitText("desk"), then ACTION_MULTIPLE "desk"
     *   tap the space bar          commitText(" "), then KEYCODE_SPACE down and up
     *   tap Enter                  finishComposingText, then ACTION_MULTIPLE of the word,
     *                              then KEYCODE_ENTER down and up
     *   tap a digit on the pad     KEYCODE_7 down and up, and nothing else
     * </pre>
     *
     * The ACTION_MULTIPLE and KEYCODE_SPACE events do not come from the keyboard.
     * BaseInputConnection sends them itself, from inside commitText and finishComposingText;
     * see {@link #resending}. Until 2026-09-26 this class blamed the keyboard for them and
     * tried to recognize each copy by its characters, which missed the one sent when a word
     * finishes. That typed a word twice, or into the next box.
     *
     * So the rule is: <b>text belongs to commitText and setComposingText, and whatever
     * BaseInputConnection sends from inside those calls is dropped.</b> Every other key
     * event is the keyboard's own and is the only copy there will be: a digit from the
     * number pad, Backspace, Enter.
     *
     * <h2>Why composing text is forwarded, when it used to be dropped</h2>
     *
     * The first version deliberately sent nothing for a word still being predicted, so that
     * a half-guessed word could not appear and then need taking back. A predictive keyboard
     * composes every letter until you press space, so the field stayed visibly empty for the
     * whole word. It is forwarded instead, and {@link #mirrored} is what makes it retractable.
     */
    private static final class JavaFxInputConnection extends BaseInputConnection {

        /**
         * Exactly what JavaFX is showing for the word being composed.
         *
         * <p>The mirror this class keeps of the other side, so a change can be sent as a
         * difference rather than as a whole word. Emptied when the composition ends, because
         * from then on those characters are ordinary text that nothing here may take back.
         */
        private String mirrored = "";

        /**
         * The word the composition just ended on, in case the keyboard commits it again.
         *
         * <p>Recorded on 2026-09-04 as Samsung's keyboard ending a word with
         * finishComposingText and then committing the same text, which with the mirror
         * already emptied would type the word a second time. ⚠ That order did not show up on
         * 2026-09-26, when a space, Enter and a suggestion were each logged. The "deskdesk" it
         * was credited with fits the resend at {@link #resending} instead, and this guard could
         * not have stopped that: its branch still called super.commitText, which resent the
         * word. It stays because nothing shows the keyboard never does it, and it does nothing
         * when the keyboard does not.
         */
        private String justFinished = "";

        /**
         * True while BaseInputConnection is sending back text this class has already typed.
         *
         * <p>⚠ THIS IS WHAT STOPS A WORD GOING IN TWICE, OR GOING INTO THE NEXT BOX. Found on
         * the phone 2026-09-26, and it had three faces: a space after "abc" left "abcabc ",
         * Enter after "ab" left "abab", and "abcd" still being typed in the Add dialog's Name
         * box went into Width as well when Width was tapped, which then read "12abcd".
         *
         * <p>The {@code false} passed to the superclass puts it in what Android calls dummy
         * mode, meant for a view that only understands keys. In that mode commitText,
         * finishComposingText and setComposingRegion each end by handing the whole of the
         * superclass's own text buffer to {@link #sendKeyEvent}: key events for one
         * character, one ACTION_MULTIPLE event carrying the lot for more. Read out of the
         * phone's own {@code /system/framework/framework.jar} (Android 10) with dexdump, in
         * {@code BaseInputConnection.sendCurrentText}. And {@code closeConnection}, which
         * Android calls on the old connection when a box that wants a different keyboard
         * takes focus, is nothing but finishComposingText.
         *
         * <p>This class typed every one of those characters itself as they arrived, so the
         * resend is always a second copy, and while this is true sendKeyEvent drops it. The
         * old code tried to spot the copies by their characters, and finishComposingText
         * forgot the characters just before its own resend, so the word was typed again into
         * whichever box had focus by then.
         */
        private boolean resending;

        JavaFxInputConnection(View target) {
            super(target, false);
        }

        /** One of the superclass's calls that ends by resending its buffer; see resending. */
        private interface Resends {
            boolean call();
        }

        /** Runs {@code superCall} with its resend dropped. */
        private boolean dropTheResend(Resends superCall) {
            resending = true;
            try {
                return superCall.call();
            } finally {
                resending = false;
            }
        }

        /**
         * Makes JavaFX show {@code text} in place of whatever was last sent for this word.
         *
         * Backspacing first is what stops "c", "ca", "cat" accumulating into "ccacat".
         */
        private void replaceComposed(String text) {
            if (instance == null) {
                return;
            }
            // ⚠ ONLY THE DIFFERENCE, never the whole word again.
            //
            // The first version backspaced everything it had sent and retyped the lot on
            // every keystroke. That is a great deal of trust in a backspace, and it was
            // misplaced: Samsung's keyboard calls setComposingText TWICE per letter with the
            // SAME text both times (measured on the phone: "d", "de", "de", "des", "des",
            // "desk"), so a name typed as "desk" arrived as "deskdesk".
            //
            // Sending the difference makes a repeated identical call do nothing at all, which
            // is both correct and free. An ordinary letter now costs one TYPED event instead
            // of a word's worth of backspaces followed by the word.
            int shared = 0;
            while (shared < mirrored.length() && shared < text.length()
                    && mirrored.charAt(shared) == text.charAt(shared)) {
                shared++;
            }
            for (int i = mirrored.length(); i > shared; i--) {
                instance.sendKey(GLASS_PRESS, javafx.scene.input.KeyCode.BACK_SPACE, null, 0);
                instance.sendKey(GLASS_RELEASE, javafx.scene.input.KeyCode.BACK_SPACE, null, 0);
            }
            String added = text.substring(shared);
            if (!added.isEmpty()) {
                instance.sendTyped(added.toCharArray(), 0);
            }
            mirrored = text;
            justFinished = "";
        }

        /**
         * Text as the keyboard log shows it: how long it is, never what it says. The words are
         * the user's, and verbose lines are written to be pasted into a public bug report. A
         * doubled word still shows, as a doubled length. A newline is named, because whether a
         * keyboard sends Enter as text or as a key is one of the things the log is for.
         */
        private static String lengthOf(CharSequence text) {
            if (text == null) {
                return "nothing";
            }
            if ("\n".contentEquals(text)) {
                return "a newline";
            }
            return text.length() + (text.length() == 1 ? " char" : " chars");
        }

        /** A key event as the keyboard log shows it: the key's name, unless it types a character. */
        private static String describe(android.view.KeyEvent event) {
            if (event.getAction() == android.view.KeyEvent.ACTION_MULTIPLE) {
                return "multiple, " + lengthOf(event.getCharacters());
            }
            String action = event.getAction() == android.view.KeyEvent.ACTION_DOWN ? "down" : "up";
            return action + " " + (event.isPrintingKey() ? "a character key"
                    : android.view.KeyEvent.keyCodeToString(event.getKeyCode()));
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            if (keyboardLogging) {
                Log.v(TAG, "IC COMMIT " + lengthOf(text) + ", mirror " + lengthOf(mirrored)
                        + ", just finished " + lengthOf(justFinished));
            }
            String committed = text == null ? "" : text.toString();

            // A committed newline goes to JavaFX as the Enter key, since a literal newline in
            // a single-line field would be wrong. Samsung's keyboard does not come this way:
            // its Enter is a key event (logged 2026-09-26), which sendKeyEvent forwards.
            if ("\n".equals(committed)) {
                if (instance != null) {
                    instance.sendKey(GLASS_PRESS, javafx.scene.input.KeyCode.ENTER, null, 0);
                    instance.sendKey(GLASS_RELEASE, javafx.scene.input.KeyCode.ENTER, null, 0);
                }
                return dropTheResend(() -> super.commitText(text, newCursorPosition));
            }

            // The finished-then-committed-again case; see justFinished.
            if (mirrored.isEmpty() && !justFinished.isEmpty() && committed.equals(justFinished)) {
                justFinished = "";
                return dropTheResend(() -> super.commitText(text, newCursorPosition));
            }

            replaceComposed(committed);
            mirrored = "";                       // committed text can no longer be retracted
            justFinished = "";
            return dropTheResend(() -> super.commitText(text, newCursorPosition));
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            if (keyboardLogging) {
                Log.v(TAG, "IC COMPOSE " + lengthOf(text) + ", mirror " + lengthOf(mirrored));
            }
            replaceComposed(text == null ? "" : text.toString());
            return super.setComposingText(text, newCursorPosition);
        }

        /**
         * The IME has settled on what was being predicted, so it stops being retractable.
         *
         * <p>⚠ Android calls this too, on the old connection, when a box that wants a
         * different keyboard takes focus. By then JavaFX's focus is already in the new box,
         * which is why the superclass's resend must be dropped rather than typed; see
         * resending.
         */
        @Override
        public boolean finishComposingText() {
            if (keyboardLogging) {
                Log.v(TAG, "IC FINISH mirror " + lengthOf(mirrored));
            }
            justFinished = mirrored;      // a duplicate commit of this must not type it again
            mirrored = "";
            return dropTheResend(super::finishComposingText);
        }

        /**
         * The IME marking text it has already sent as the word being composed again.
         *
         * <p>Left to the superclass, with its resend dropped. The superclass
         * empties its buffer after every commit, so the keyboard cannot read back an earlier
         * word to take up again, and none of the runs logged on 2026-09-26 called this.
         */
        @Override
        public boolean setComposingRegion(int start, int end) {
            if (keyboardLogging) {
                Log.v(TAG, "IC REGION " + start + ".." + end);
            }
            return dropTheResend(() -> super.setComposingRegion(start, end));
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            if (instance != null) {
                for (int i = 0; i < beforeLength; i++) {
                    instance.sendKey(GLASS_PRESS, javafx.scene.input.KeyCode.BACK_SPACE, null, 0);
                    instance.sendKey(GLASS_RELEASE, javafx.scene.input.KeyCode.BACK_SPACE, null, 0);
                }
            }
            mirrored = "";
            return super.deleteSurroundingText(beforeLength, afterLength);
        }

        /**
         * Keys from the soft keyboard, less the superclass's resend of text already typed.
         *
         * <p>See the class comment and {@link #resending}. What is left is the keyboard's
         * own, and it is the only copy: a digit from the number pad, which arrives no other
         * way, and Backspace and Enter, which are keys rather than text.
         */
        @Override
        public boolean sendKeyEvent(android.view.KeyEvent event) {
            if (instance == null) {
                return true;
            }
            if (resending) {
                if (keyboardLogging) {
                    Log.v(TAG, "IC RESEND dropped " + describe(event));
                }
                return true;
            }
            if (keyboardLogging) {
                Log.v(TAG, "IC KEY " + describe(event));
            }
            instance.processKeyEvent(event);
            return true;
        }

        /**
         * The keypad's Enter, which moves to the next field rather than confirming.
         *
         * <p>Asked for by the user 2026-09-04: filling in Width, Length and Height meant
         * reaching past the keyboard to tap each box. JavaFX already moves focus on Tab, so
         * that is what Enter becomes, and the dialog's own field order decides where it goes.
         */
        @Override
        public boolean performEditorAction(int actionCode) {
            if (actionCode == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                // ⚠ NOT a synthetic Tab, which is what this tried first and it did not work.
                //
                // A Glass Tab does reach JavaFX, but its traversal order is scene-graph order
                // rather than the order the boxes are read: measured on the phone, Next in the
                // Add dialog went Width, Length, then the ID box, skipping Height, so the
                // keyboard changed to letters half way through entering three numbers and
                // every digit after that went in as a letter.
                //
                // So the request is counted here and the app picks it up, the same way it
                // already learns about the keyboard's height and the file picker's answer.
                // The app knows which boxes hold numbers; this side does not.
                nextFieldRequests++;
                return true;
            }
            return super.performEditorAction(actionCode);
        }
    }

    // ---- The native half --------------------------------------------------------------

    private native void nativeStartApp(String[] args);

    private native void nativeSetSurface(Surface surface);

    private native void nativeSetDensity(float density);

    private native void nativeSurfaceRedrawNeeded();

    private native void nativeTouchEvent(int count, int[] actions, int[] ids, int[] xs, int[] ys);

    private native void nativeKeyEvent(int action, int key, char[] chars, int count, int modifiers);
}
