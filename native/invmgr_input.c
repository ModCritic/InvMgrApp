/*
 * InvMgr Android host layer: touch and key events.
 *
 * The Activity has already flattened each Android MotionEvent into four parallel arrays.
 * What is left to do here is translate the action codes, work out which point the gesture
 * is about, and pass the lot to JavaFX.
 *
 * ⚠ This file carries every gesture in the app. M6.4 and M6.4a spent ten bugs and a great
 * many builds getting touch to feel right, and none of that is testable anywhere except on
 * a phone. Treat MANUAL.md's M6.4a section as the acceptance list for changes here.
 */

#include "invmgr_host.h"

#include <android/log.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, INVMGR_LOG_TAG, __VA_ARGS__)

/*
 * Which touch point the gesture is really about.
 *
 * ⚠ THIS IS THE ONLY PART OF THE BATCH JAVAFX ACTUALLY READS, which is worth knowing before
 * spending time on the rest. Decompiling AndroidInputDeviceRegistry.gotTouchEventFromNative
 * shows it never loads the actions array at all: it walks ids, xs and ys, builds a
 * TouchState from the points present, and works out presses and releases by comparing that
 * against the previous state. The actions we send only reach JavaFX through this function.
 *
 * -1 means "add no points", which is how a gesture ends. Any other value is an index and
 * every point in the batch is added.
 */
static int primary_index(int count, const int *actions)
{
    int primary = 0;
    int released = 0;

    for (int i = 0; i < count; i++) {
        if (actions[i] == INVMGR_TOUCH_RELEASED) {
            released++;
        }
        if (actions[i] != INVMGR_TOUCH_STILL) {
            primary = i;
        }
    }

    /*
     * Every finger has gone. -1 tells JavaFX to build an empty touch state, which is how it
     * learns the gesture ended.
     *
     * ⚠ The test is "all of them", not "there was one and it lifted". A canceled gesture
     * arrives with several fingers all marked released at once, and reporting an index
     * there would leave JavaFX believing those fingers are still down, with no further
     * events coming to correct it.
     */
    if (released == count) {
        return -1;
    }
    return primary;
}

JNIEXPORT void JNICALL
Java_com_modcritic_invmgr_android_InvMgrActivity_nativeTouchEvent(JNIEnv *env, jobject activity,
                                                                  jint count,
                                                                  jintArray jactions,
                                                                  jintArray jids,
                                                                  jintArray jxs,
                                                                  jintArray jys)
{
    (void) activity;

    if (count <= 0) {
        return;
    }

    jint *actions = (*env)->GetIntArrayElements(env, jactions, NULL);
    jint *ids     = (*env)->GetIntArrayElements(env, jids, NULL);
    jint *xs      = (*env)->GetIntArrayElements(env, jxs, NULL);
    jint *ys      = (*env)->GetIntArrayElements(env, jys, NULL);

    if (actions == NULL || ids == NULL || xs == NULL || ys == NULL) {
        LOGE("could not read a touch batch");
        goto release;
    }

    /*
     * Android's action codes are not JavaFX's. OpenJFX ships the mapping in dalvikUtils, so
     * use that rather than writing a second copy of it that can drift.
     *
     * The converted values are read by primary_index and by nothing else, since JavaFX
     * ignores the array itself. Converting in place is safe: the arrays are ours until they
     * are released, and JNI_ABORT below throws the modified copy away rather than writing it
     * back to Java.
     */
    for (int i = 0; i < count; i++) {
        actions[i] = to_jfx_touch_action(actions[i]);
    }

    androidJfx_gotTouchEvent(count, actions, ids, xs, ys, primary_index(count, actions));

release:
    /* JNI_ABORT: we changed `actions` and do not want that copied back into the Java array. */
    if (actions != NULL) (*env)->ReleaseIntArrayElements(env, jactions, actions, JNI_ABORT);
    if (ids != NULL)     (*env)->ReleaseIntArrayElements(env, jids, ids, JNI_ABORT);
    if (xs != NULL)      (*env)->ReleaseIntArrayElements(env, jxs, xs, JNI_ABORT);
    if (ys != NULL)      (*env)->ReleaseIntArrayElements(env, jys, ys, JNI_ABORT);
}

/*
 * ⚠ NOTHING IS CONVERTED HERE, AND THAT IS DELIBERATE.
 *
 * The obvious move is to run the action and the key code through OpenJFX's own
 * to_jfx_key_action and to_linux_keycode, the way the touch path uses
 * to_jfx_touch_action. The first version did exactly that and it was wrong twice over.
 *
 * androidJfx_gotKeyEvent ends at MonocleView.notifyKey, which is Glass API. It wants Glass
 * action constants (PRESS 111, RELEASE 112, TYPED 113) and a JavaFX KeyCode value.
 * to_linux_keycode produces LINUX EVDEV codes instead, so backspace arrived as 14, which is
 * no JavaFX key at all. Gluon's equivalent passes both straight through for the same
 * reason, and does the mapping in Java where a real KeyCode table exists.
 *
 * So InvMgrActivity sends Glass values and this function is a courier.
 */
JNIEXPORT void JNICALL
Java_com_modcritic_invmgr_android_InvMgrActivity_nativeKeyEvent(JNIEnv *env, jobject activity,
                                                                jint action, jint key,
                                                                jcharArray jchars, jint count,
                                                                jint modifiers)
{
    (void) activity;

    jchar *chars = NULL;
    if (jchars != NULL && count > 0) {
        chars = (*env)->GetCharArrayElements(env, jchars, NULL);
    }

    androidJfx_gotKeyEvent(action, key, chars, count, modifiers);

    if (chars != NULL) {
        (*env)->ReleaseCharArrayElements(env, jchars, chars, JNI_ABORT);
    }
}
