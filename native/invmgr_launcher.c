/*
 * InvMgr Android host layer: startup, and the bridge back to the Activity.
 *
 * This file owns three things:
 *
 *   1. JNI_OnLoad, which is the first of our code Android runs.
 *   2. Starting the application inside the native image.
 *   3. The keyboard, which JavaFX asks for and only the Activity can provide.
 *
 * See docs/PLAN-M6.5B-HOST-LAYER.md for why this exists at all. It replaces Gluon's
 * launcher.c, which is GPL-3.0-or-later and cannot ship in a 0BSD app.
 */

#include "invmgr_host.h"

#include <android/log.h>
#include <stdlib.h>
#include <string.h>

JavaVM *invmgr_vm = NULL;
jclass invmgr_activity_class = NULL;

static jmethodID activity_show_ime;
static jmethodID activity_hide_ime;

#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, INVMGR_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, INVMGR_LOG_TAG, __VA_ARGS__)

/*
 * SubstrateVM reads this to decide whether it is running inside a statically linked binary.
 * We are: everything is linked into libsubstrate.so. Leaving it undefined makes the isolate
 * take a path meant for a dynamically linked image and fail in ways that do not name this
 * as the cause.
 *
 * Weak so that it does no harm if the image also defines it. Written as a definition
 * rather than an initialized extern, which is what it actually is and which avoids
 * -Wextern-initializer.
 */
int __svm_vm_is_static_binary __attribute__((weak)) = 1;

/*
 * The argument vector handed to the application.
 *
 * These are not preferences. Each one switches JavaFX onto its Android and Monocle paths,
 * and the app does not start correctly without them. In particular:
 *
 *   monocle.platform=Android      picked up by com.sun.glass.ui.monocle.NativePlatformFactory
 *   com.sun.javafx.isEmbedded     this is the one that enables the 3D pipeline
 *   use.egl                       draw through EGL rather than looking for a framebuffer
 *   prism.verbose                 makes Prism name the pipeline it chose in logcat, which is
 *                                 the single most useful line when 3D silently draws nothing
 *
 * ⚠ com.sun.javafx.gestures.scroll=true is kept deliberately, even though it is the source
 * of the platform fling that M6.4a's B9 had to suppress in ui/PanMomentum. M6.5b's bar is
 * that behavior does not change, so this list matches what the app has shipped with. It is
 * now ours to change, which is worth remembering when M6.7 revisits scrolling.
 *
 * argv[0] is a program name and is not used for anything.
 */
static const char *app_args[] = {
    "invmgr",
    "-Djavafx.platform=android",
    "-Dmonocle.platform=Android",
    "-Dembedded=monocle",
    "-Dglass.platform=Monocle",
    "-Duse.egl=true",
    "-Dcom.sun.javafx.isEmbedded=true",
    "-Dcom.sun.javafx.touch=true",
    "-Dcom.sun.javafx.gestures.zoom=true",
    "-Dcom.sun.javafx.gestures.rotate=true",
    "-Dcom.sun.javafx.gestures.scroll=true",
    "-Djavafx.verbose=false",
    "-Dmonocle.input.touchRadius=1",
    "-Dmonocle.input.traceEvents.verbose=false",
    "-Dprism.verbose=true",
    "-Xmx4g"
};

JNIEnv *invmgr_attach_current_thread(void)
{
    JNIEnv *env = NULL;
    if (invmgr_vm == NULL) {
        return NULL;
    }
    if ((*invmgr_vm)->GetEnv(invmgr_vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }
    if ((*invmgr_vm)->AttachCurrentThread(invmgr_vm, &env, NULL) != JNI_OK) {
        LOGE("could not attach this thread to the VM");
        return NULL;
    }
    return env;
}

/*
 * Android calls this when InvMgrActivity's static block runs System.loadLibrary.
 *
 * It is the earliest point at which we have a VM pointer, and it runs on whatever thread
 * did the loading, so it does nothing but record what it is given.
 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void) reserved;
    invmgr_vm = vm;
    invmgr_start_logger(INVMGR_LOG_TAG);
    LOGV("native library loaded");
    return JNI_VERSION_1_6;
}

/*
 * Caches what the native side needs to call back into the Activity.
 *
 * The lookups are by name, so a rename on the Java side breaks this at runtime rather than
 * at compile time. InvMgrActivity's "Called from the native side" section says the same
 * thing from the other direction.
 */
static void cache_activity(JNIEnv *env, jobject activity)
{
    /*
     * The class, not the instance. Everything the native side calls on the Activity is
     * static (see invmgr_bridge.c), so the instance is never needed after this point.
     * A global reference to it was taken here until M6.7c and read by nothing, which
     * pinned the Activity for the life of the process to no purpose.
     */
    jclass local = (*env)->GetObjectClass(env, activity);
    invmgr_activity_class = (jclass) (*env)->NewGlobalRef(env, local);

    activity_show_ime = (*env)->GetStaticMethodID(env, invmgr_activity_class, "showIME", "()V");
    activity_hide_ime = (*env)->GetStaticMethodID(env, invmgr_activity_class, "hideIME", "()V");

    if (activity_show_ime == NULL || activity_hide_ime == NULL) {
        LOGE("could not find showIME/hideIME on the activity; the keyboard will not appear");
        (*env)->ExceptionClear(env);
    }
}

JNIEXPORT void JNICALL
Java_com_modcritic_invmgr_android_InvMgrActivity_nativeStartApp(JNIEnv *env, jobject activity,
                                                                jobjectArray extraArgs)
{
    LOGV("starting the application");
    cache_activity(env, activity);

    int fixed = (int) (sizeof(app_args) / sizeof(app_args[0]));
    int extra = extraArgs == NULL ? 0 : (*env)->GetArrayLength(env, extraArgs);
    int total = fixed + extra;

    char **argv = (char **) malloc((size_t) total * sizeof(char *));
    if (argv == NULL) {
        LOGE("out of memory building the argument vector");
        return;
    }
    for (int i = 0; i < fixed; i++) {
        argv[i] = (char *) app_args[i];
    }
    for (int i = 0; i < extra; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, extraArgs, i);
        argv[fixed + i] = (char *) (*env)->GetStringUTFChars(env, item, NULL);
    }

    LOGV("entering the application with %d arguments", total);

    /*
     * Does not return while the app is alive. InvMgrActivity calls this on a thread of its
     * own for that reason; if it ever ends up on the UI thread the app hangs at a blank
     * screen and Android kills it.
     */
    run_main(total, argv);

    LOGV("the application returned");
    free(argv);
}

/* ---- The keyboard ------------------------------------------------------------------
 *
 * JavaFX's Android text skins declare showSoftwareKeyboard and hideSoftwareKeyboard as
 * native methods and nothing in OpenJFX implements them, so the host has to. There are two
 * skins, TextField and TextArea, and both need both calls, which is why there are four
 * functions here doing two things.
 *
 * These run inside the native image, on the JavaFX thread. Showing a keyboard has to happen
 * on Android's UI thread, so the Java side hops threads with runOnUiThread rather than
 * doing it here.
 */

static void call_activity_void(jmethodID method, const char *what)
{
    if (invmgr_activity_class == NULL || method == NULL) {
        LOGE("%s asked for before the activity was ready", what);
        return;
    }
    JNIEnv *env = invmgr_attach_current_thread();
    if (env == NULL) {
        return;
    }
    (*env)->CallStaticVoidMethod(env, invmgr_activity_class, method);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

static void show_keyboard(void) { call_activity_void(activity_show_ime, "show keyboard"); }
static void hide_keyboard(void) { call_activity_void(activity_hide_ime, "hide keyboard"); }

JNIEXPORT void JNICALL
Java_javafx_scene_control_skin_TextFieldSkinAndroid_showSoftwareKeyboard(JNIEnv *env, jobject skin)
{
    (void) env; (void) skin;
    show_keyboard();
}

JNIEXPORT void JNICALL
Java_javafx_scene_control_skin_TextFieldSkinAndroid_hideSoftwareKeyboard(JNIEnv *env, jobject skin)
{
    (void) env; (void) skin;
    hide_keyboard();
}

JNIEXPORT void JNICALL
Java_javafx_scene_control_skin_TextAreaSkinAndroid_showSoftwareKeyboard(JNIEnv *env, jobject skin)
{
    (void) env; (void) skin;
    show_keyboard();
}

JNIEXPORT void JNICALL
Java_javafx_scene_control_skin_TextAreaSkinAndroid_hideSoftwareKeyboard(JNIEnv *env, jobject skin)
{
    (void) env; (void) skin;
    hide_keyboard();
}
