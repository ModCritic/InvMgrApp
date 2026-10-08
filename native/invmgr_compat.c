/*
 * InvMgr Android host layer: logging, and the handful of stubs the linker wants.
 *
 * Nothing here is interesting. It exists so that the interesting files do not have to
 * carry it.
 */

#include "invmgr_host.h"

#include <android/log.h>
#include <errno.h>
#include <pthread.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>

/* ---- stdout and stderr into logcat --------------------------------------------------
 *
 * A native image writes its diagnostics to stdout and stderr. On Android nothing reads
 * either, so without this the most useful startup line the app produces goes nowhere:
 *
 *     InvMgr - java 17, javafx 21, scene3d true, fonts ...
 *
 * and so does Prism's report of which pipeline it chose, which is the first thing to look
 * at when the 3D view draws nothing. Redirecting them costs one pipe and one thread.
 */

static int log_pipe[2];
static pthread_t log_thread;
static const char *log_tag = INVMGR_LOG_TAG;

static void *pump_log(void *ignored)
{
    (void) ignored;
    char line[512];
    ssize_t used = 0;

    for (;;) {
        ssize_t got = read(log_pipe[0], line + used, sizeof(line) - 1 - (size_t) used);
        if (got <= 0) {
            break;
        }
        used += got;
        line[used] = '\0';

        /* Emit whole lines, and keep any partial tail for the next read. */
        char *start = line;
        char *newline;
        while ((newline = strchr(start, '\n')) != NULL) {
            *newline = '\0';
            __android_log_write(ANDROID_LOG_INFO, log_tag, start);
            start = newline + 1;
        }
        used = (ssize_t) strlen(start);
        memmove(line, start, (size_t) used + 1);

        /* A line longer than the buffer would never contain a newline. Flush it as is. */
        if (used == (ssize_t) sizeof(line) - 1) {
            __android_log_write(ANDROID_LOG_INFO, log_tag, line);
            used = 0;
        }
    }
    return NULL;
}

int invmgr_start_logger(const char *tag)
{
    if (tag != NULL) {
        log_tag = tag;
    }

    /* Unbuffered, so a crash does not swallow the lines that would explain it. */
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    if (pipe(log_pipe) != 0) {
        return -1;
    }
    dup2(log_pipe[1], STDOUT_FILENO);
    dup2(log_pipe[1], STDERR_FILENO);

    if (pthread_create(&log_thread, NULL, pump_log, NULL) != 0) {
        return -1;
    }
    pthread_detach(log_thread);
    return 0;
}

/* ---- Stubs for natives that are referenced and never called -------------------------
 *
 * The JDK inside the native image declares these, and the parts of it that would define
 * them are not built for Android. Nothing in this app touches AWT or a datagram socket, so
 * the calls never happen; the symbols simply have to resolve for the library to load.
 *
 * ⚠ THE LIST IS NOT JUST Java_* FUNCTIONS, AND ASSUMING IT WAS COST A BUILD.
 *
 * The first version of this file was built from `nm -D -u libInvMgr.so | grep '^Java_'`,
 * which is a subset. JNI's static linking convention also produces JNI_OnLoad_<library>
 * entry points, two of which the JDK references here, and grepping for Java_ silently
 * dropped both. The APK then died on the first launch:
 *
 *     UnsatisfiedLinkError: dlopen failed: cannot locate symbol "JNI_OnLoad_awt"
 *
 * Android's loader resolves everything up front, so one missing symbol is a crash before
 * a single line of the app runs, not a lazy failure at the call site.
 *
 * Do not maintain this list by hand or by grep. Run:
 *
 *     ./tools/android/check-undefined-symbols.sh
 *
 * which compares every undefined symbol in the linked library against what the NDK's
 * system libraries and libfreetype actually export, and fails the build on anything left
 * over.
 *
 * ⚠ Gluon's equivalent file also carried glibc compatibility shims (__xstat, __getdelim,
 * __xmknod and friends) for symbols Bionic does not provide. This build references none of
 * them, checked the same way, so they are deliberately absent rather than forgotten.
 */

JNIEXPORT void JNICALL Java_java_awt_Toolkit_initIDs(JNIEnv *env, jclass cls)
{
    (void) env; (void) cls;
}

JNIEXPORT void JNICALL Java_java_awt_Font_initIDs(JNIEnv *env, jclass cls)
{
    (void) env; (void) cls;
}

/*
 * ⚠ THIS ONE IS NOT REFERENCED BY ANYTHING, AND THE APP DIES WITHOUT IT.
 *
 * JavaFX's font subsystem calls System.loadLibrary("javafx_font"). Inside a native image
 * there is no separate library to load, so the runtime looks for the builtin entry point
 * named after it, JNI_OnLoad_javafx_font, BY NAME. Nothing links against that name, so it
 * appears in no undefined-symbol list and neither the linker nor
 * tools/android/check-undefined-symbols.sh can see it missing.
 *
 * What happens without it is a long way from the cause: the font library fails to
 * register, the font factory stays null, and the first Font.loadFont call dies inside
 * PrismFontLoader with a bare NullPointerException. On the phone that is a black screen.
 *
 *     Caused by: java.lang.NullPointerException
 *         at com.sun.javafx.font.PrismFontLoader.loadFont(PrismFontLoader.java:109)
 *         at com.modcritic.invmgr.ui.Fonts.<clinit>(Fonts.java:109)
 *
 * Found by diffing the exported symbols of this library against the last APK known to run
 * on hardware, which is the only check that catches a name resolved at runtime.
 * check-undefined-symbols.sh does that diff now.
 */
JNIEXPORT jint JNICALL JNI_OnLoad_javafx_font(JavaVM *vm, void *reserved)
{
    (void) vm; (void) reserved;
    return JNI_VERSION_1_6;
}

/*
 * When a JNI library is linked statically the runtime looks for JNI_OnLoad_<library>
 * rather than plain JNI_OnLoad. The static JDK here references the two AWT ones because
 * parts of java.desktop are reachable, even though this app never touches AWT and runs
 * headless. Returning a version number is enough; there is nothing to initialize.
 *
 * JNI_VERSION_1_6 rather than 1_8, because Android's jni.h does not define anything above
 * 1_6 and naming a higher one does not compile.
 */
JNIEXPORT jint JNICALL JNI_OnLoad_awt(JavaVM *vm, void *reserved)
{
    (void) vm; (void) reserved;
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL JNI_OnLoad_awt_headless(JavaVM *vm, void *reserved)
{
    (void) vm; (void) reserved;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL Java_java_net_DatagramPacket_init(JNIEnv *env, jclass cls)
{
    (void) env; (void) cls;
}

JNIEXPORT jboolean JNICALL
Java_java_net_AbstractPlainSocketImpl_isReusePortAvailable0(JNIEnv *env, jclass cls)
{
    (void) env; (void) cls;
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_java_net_AbstractPlainDatagramSocketImpl_isReusePortAvailable0(JNIEnv *env, jclass cls)
{
    (void) env; (void) cls;
    return JNI_FALSE;
}

/*
 * Added 2026-09-25 with the About screen's Source code link. NOT a JNI function: a libc one,
 * and Bionic only has it from API 28, where this app supports phones back to API 21.
 *
 * The desktop half of About's Source code link calls JavaFX's HostServices.showDocument, which
 * starts xdg-open (or open, or rundll32) as a separate process. The native image compiles every
 * line it can reach, whether or not it ever runs on a phone, so the JDK's process-starting code
 * came into this library with it, and that code calls posix_spawn. On a phone, App.openLink
 * always takes the Android route through InvMgrActivity instead, so this is never called.
 *
 * If it somehow were, "not supported" is the honest answer: the JDK turns it into an IOException,
 * which HostServices catches and logs. Declared here with plain pointers rather than spawn.h's
 * types, because the API 21 headers hide spawn.h's declaration; only the name has to match for
 * the symbol to resolve. check-undefined-symbols.sh found it missing.
 */
int posix_spawn(pid_t *pid, const char *path, const void *file_actions, const void *attrp,
                char *const argv[], char *const envp[])
{
    (void) pid; (void) path; (void) file_actions; (void) attrp; (void) argv; (void) envp;
    return ENOSYS;
}
