/*
 * InvMgr Android host layer: the drawing surface.
 *
 * Android owns the window. It hands one over when the app becomes visible and takes it back
 * when the app goes to the background, and JavaFX has to be told both times.
 *
 * The taking-back half is the part worth reading. When the window is gone, EGL calls made
 * against it do not crash: they fail, and libEGL logs the failure, once per frame, forever.
 * JavaFX carries on rendering because nothing has told it to stop. The fix is to intercept
 * the two EGL calls that touch the window and refuse them while there is no window, which is
 * what the linker's --wrap does below.
 */

#include "invmgr_host.h"

#include <android/log.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <stdatomic.h>
#include <time.h>

#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, INVMGR_LOG_TAG, __VA_ARGS__)

static ANativeWindow *window = NULL;

/*
 * Whether the window is usable right now.
 *
 * Read from the JavaFX render thread and written from Android's UI thread, so it is atomic.
 * A plain bool would work in practice on every device this will ever run on and would still
 * be wrong.
 */
static atomic_bool surface_valid = ATOMIC_VAR_INIT(false);

bool invmgr_surface_is_valid(void)
{
    return atomic_load_explicit(&surface_valid, memory_order_acquire);
}

JNIEXPORT void JNICALL
Java_com_modcritic_invmgr_android_InvMgrActivity_nativeSetSurface(JNIEnv *env, jobject activity,
                                                                  jobject surface)
{
    (void) activity;

    if (surface == NULL) {
        LOGV("surface released");
        atomic_store_explicit(&surface_valid, false, memory_order_release);
        androidJfx_setNativeWindow(NULL);
        if (window != NULL) {
            ANativeWindow_release(window);
            window = NULL;
        }
        return;
    }

    ANativeWindow *replacement = ANativeWindow_fromSurface(env, surface);
    LOGV("surface set, native window at %p", (void *) replacement);

    if (window != NULL) {
        ANativeWindow_release(window);
    }
    window = replacement;

    androidJfx_setNativeWindow(window);
    atomic_store_explicit(&surface_valid, true, memory_order_release);
}

JNIEXPORT void JNICALL
Java_com_modcritic_invmgr_android_InvMgrActivity_nativeSetDensity(JNIEnv *env, jobject activity,
                                                                  jfloat density)
{
    (void) env; (void) activity;
    LOGV("density %f", (double) density);
    androidJfx_setDensity((float) density);
}

JNIEXPORT void JNICALL
Java_com_modcritic_invmgr_android_InvMgrActivity_nativeSurfaceRedrawNeeded(JNIEnv *env, jobject activity)
{
    (void) env; (void) activity;
    androidJfx_requestGlassToRedraw();
}

/* ---- Guarding EGL against a window that is no longer there --------------------------
 *
 * The link line carries -Wl,--wrap=eglSwapBuffers and -Wl,--wrap=eglCreateWindowSurface.
 * That makes the linker send every call to those two functions here instead, and gives us
 * __real_ versions to forward to once we are satisfied there is something to draw on.
 *
 * Without this, backgrounding the app produces an endless stream of EGL_BAD_SURFACE and
 * EGL_BAD_NATIVE_WINDOW in logcat.
 */

extern EGLBoolean __real_eglSwapBuffers(EGLDisplay display, EGLSurface surface);
extern EGLSurface __real_eglCreateWindowSurface(EGLDisplay display, EGLConfig config,
                                                EGLNativeWindowType native,
                                                const EGLint *attributes);

EGLBoolean __wrap_eglSwapBuffers(EGLDisplay display, EGLSurface surface)
{
    if (!invmgr_surface_is_valid()) {
        /*
         * Sleep about one frame before returning. The caller is a render loop, and letting
         * it spin at full speed against a dead surface burns the battery of a phone whose
         * screen is off.
         */
        struct timespec oneFrame = { 0, 16 * 1000 * 1000 };
        nanosleep(&oneFrame, NULL);
        return EGL_FALSE;
    }
    return __real_eglSwapBuffers(display, surface);
}

EGLSurface __wrap_eglCreateWindowSurface(EGLDisplay display, EGLConfig config,
                                         EGLNativeWindowType native, const EGLint *attributes)
{
    if (native == NULL || !invmgr_surface_is_valid()) {
        return EGL_NO_SURFACE;
    }
    return __real_eglCreateWindowSurface(display, config, native, attributes);
}
