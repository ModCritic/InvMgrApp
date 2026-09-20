/*
 * InvMgr Android host layer: the door the application knocks on.
 *
 * Every other file here carries events from Android INTO JavaFX. This one goes the other
 * way: the app asks Android a question and gets an answer back.
 *
 * There are two questions, and both are M6.5c's:
 *
 *   1. What has the phone taken of the screen, and how much is the keyboard using?
 *      That is OD-3. ui/SystemInsets shipped Android's published 24 dip status bar as a
 *      constant because the documented way to read the real one was a GPL-licensed library
 *      this app may not link. Owning the Activity makes it a plain framework call.
 *   2. Which keyboard should the next text field raise? That is B6; a number field is
 *      supposed to get the dial pad and was getting the full keyboard.
 *   3. Where should this file be saved, or which one should be opened? JavaFX's FileChooser
 *      throws on this platform, so Save, Load and Export were all dead.
 *
 * ⚠⚠ THERE ARE TWO VIRTUAL MACHINES IN THIS PROCESS AND THIS FILE STANDS BETWEEN THEM.
 *
 * This is the trap that cost a device test on 2026-09-03, and it produces a symptom that
 * points nowhere near the cause: GetStaticMethodID returns NULL for a method that is
 * demonstrably in the APK, with the right name, static, and the right signature.
 *
 *   - The APPLICATION runs inside the GraalVM native image. It has its own JNI
 *     implementation, and the JNIEnv handed to the functions below is ITS env.
 *   - The ACTIVITY runs in Dalvik, Android's VM. invmgr_vm points at that one.
 *
 * They share nothing. A Dalvik class reference means nothing to GraalVM's env, and an
 * object created by one cannot be returned to the other. So:
 *
 *   - Talking to the Activity uses invmgr_attach_current_thread(), which is a DALVIK env.
 *     Never the `env` argument. invmgr_launcher.c's call_activity_void does the same and is
 *     the pattern to copy.
 *   - A value coming back has to be COPIED across rather than passed. Below, the five ints
 *     are read out of Dalvik's array and put into a fresh one made with the image's env.
 *
 * ⚠ HOW THESE GET LINKED, because it looks like it should need configuration and does not.
 *
 * native-image compiles a `native` method in application code into a call to the ordinary
 * JNI symbol for it, left undefined, and the linker resolves it against this file. Nothing
 * has to be added to jniconfig; the generated one does not mention OpenJFX's own
 * TextFieldSkinAndroid.showSoftwareKeyboard either, and that has worked since M6.5b.
 *
 * The consequence is that the Java name IS the contract. Renaming AndroidBridge, moving it
 * to another package, or changing a signature breaks the LINK, not the compile.
 */

#include "invmgr_host.h"

#include <android/log.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, INVMGR_LOG_TAG, __VA_ARGS__)

/* top, right, bottom, left, keyboard. Must match AndroidBridge's own indices. */
#define INSET_COUNT 5

/*
 * Looked up per call rather than cached in JNI_OnLoad.
 *
 * Deliberate: these run when the app starts, when a dialog opens or when the phone turns,
 * not per frame, and a cached jmethodID is one more thing to invalidate for no measurable
 * gain. `env` here must be Dalvik's; see the file comment.
 */
static jmethodID activity_static_method(JNIEnv *dalvik, const char *name, const char *signature)
{
    if (invmgr_activity_class == NULL) {
        LOGE("%s asked for before the activity was ready", name);
        return NULL;
    }
    jmethodID method = (*dalvik)->GetStaticMethodID(dalvik, invmgr_activity_class, name, signature);
    if (method == NULL) {
        (*dalvik)->ExceptionClear(dalvik);
        LOGE("InvMgrActivity has no static %s%s", name, signature);
    }
    return method;
}

/*
 * Five numbers in real pixels: top, right, bottom, left, keyboard.
 *
 * Returns NULL when the Activity cannot answer yet, which happens before its view is
 * attached to a window. The app treats that as "ask again later" and falls back to the
 * arithmetic ui/SystemInsets has always used.
 */
JNIEXPORT jintArray JNICALL
Java_com_modcritic_invmgr_ui_AndroidBridge_nativeInsets(JNIEnv *image, jclass cls)
{
    (void) cls;

    JNIEnv *dalvik = invmgr_attach_current_thread();
    if (dalvik == NULL) {
        return NULL;
    }

    jmethodID method = activity_static_method(dalvik, "systemInsets", "()[I");
    if (method == NULL) {
        return NULL;
    }

    jobject reported = (*dalvik)->CallStaticObjectMethod(dalvik, invmgr_activity_class, method);
    if ((*dalvik)->ExceptionCheck(dalvik)) {
        (*dalvik)->ExceptionDescribe(dalvik);
        (*dalvik)->ExceptionClear(dalvik);
        return NULL;
    }
    if (reported == NULL) {
        return NULL;                    /* the window is not attached yet; not an error */
    }

    jint values[INSET_COUNT];
    jsize length = (*dalvik)->GetArrayLength(dalvik, (jarray) reported);
    if (length != INSET_COUNT) {
        LOGE("the activity reported %d insets, expected %d", (int) length, INSET_COUNT);
        (*dalvik)->DeleteLocalRef(dalvik, reported);
        return NULL;
    }
    (*dalvik)->GetIntArrayRegion(dalvik, (jintArray) reported, 0, INSET_COUNT, values);
    (*dalvik)->DeleteLocalRef(dalvik, reported);

    /* Across the boundary: a fresh array belonging to the image, never Dalvik's. */
    jintArray answer = (*image)->NewIntArray(image, INSET_COUNT);
    if (answer == NULL) {
        return NULL;
    }
    (*image)->SetIntArrayRegion(image, answer, 0, INSET_COUNT, values);
    return answer;
}

/*
 * Which keyboard the next text field should raise. The numbers are JavaFX's own ordering
 * and the Activity maps them; see InvMgrActivity.setKeyboardType.
 */
JNIEXPORT void JNICALL
Java_com_modcritic_invmgr_ui_AndroidBridge_nativeSetKeyboardType(JNIEnv *image, jclass cls, jint type)
{
    (void) image; (void) cls;

    JNIEnv *dalvik = invmgr_attach_current_thread();
    if (dalvik == NULL) {
        return;
    }

    jmethodID method = activity_static_method(dalvik, "setKeyboardType", "(I)V");
    if (method == NULL) {
        return;
    }

    (*dalvik)->CallStaticVoidMethod(dalvik, invmgr_activity_class, method, type);
    if ((*dalvik)->ExceptionCheck(dalvik)) {
        (*dalvik)->ExceptionDescribe(dalvik);
        (*dalvik)->ExceptionClear(dalvik);
    }
}


/* ---- Strings across the boundary ---------------------------------------------------
 *
 * A Java String belongs to whichever VM made it, so one cannot be handed from the image to
 * Dalvik or back. Both directions go through a plain C buffer. See the file comment.
 *
 * The copies are why these take a length-unbounded string and are called once per user
 * action rather than per frame: a room's save file is the largest thing this app holds as
 * text, and it is copied twice on the way through here.
 */

/* Image string -> Dalvik string. Returns NULL for NULL, which callers pass straight on. */
static jstring to_dalvik(JNIEnv *image, JNIEnv *dalvik, jstring from)
{
    if (from == NULL) {
        return NULL;
    }
    const char *utf = (*image)->GetStringUTFChars(image, from, NULL);
    if (utf == NULL) {
        return NULL;
    }
    jstring to = (*dalvik)->NewStringUTF(dalvik, utf);
    (*image)->ReleaseStringUTFChars(image, from, utf);
    return to;
}

/* Dalvik string -> image string. */
static jstring to_image(JNIEnv *image, JNIEnv *dalvik, jstring from)
{
    if (from == NULL) {
        return NULL;
    }
    const char *utf = (*dalvik)->GetStringUTFChars(dalvik, from, NULL);
    if (utf == NULL) {
        return NULL;
    }
    jstring to = (*image)->NewStringUTF(image, utf);
    (*dalvik)->ReleaseStringUTFChars(dalvik, from, utf);
    return to;
}

/* Asks the Activity for a static String it is holding, and brings it back across. */
static jstring take_activity_string(JNIEnv *image, const char *name)
{
    JNIEnv *dalvik = invmgr_attach_current_thread();
    if (dalvik == NULL) {
        return NULL;
    }
    jmethodID method = activity_static_method(dalvik, name, "()Ljava/lang/String;");
    if (method == NULL) {
        return NULL;
    }
    jstring held = (jstring) (*dalvik)->CallStaticObjectMethod(dalvik, invmgr_activity_class, method);
    if ((*dalvik)->ExceptionCheck(dalvik)) {
        (*dalvik)->ExceptionDescribe(dalvik);
        (*dalvik)->ExceptionClear(dalvik);
        return NULL;
    }
    jstring answer = to_image(image, dalvik, held);
    if (held != NULL) {
        (*dalvik)->DeleteLocalRef(dalvik, held);
    }
    return answer;
}

JNIEXPORT void JNICALL
Java_com_modcritic_invmgr_ui_AndroidBridge_nativeRequestSaveFile(JNIEnv *image, jclass cls,
                                                                 jstring name, jstring mime,
                                                                 jstring content)
{
    (void) cls;

    JNIEnv *dalvik = invmgr_attach_current_thread();
    if (dalvik == NULL) {
        return;
    }
    jmethodID method = activity_static_method(dalvik, "requestSaveFile",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    if (method == NULL) {
        return;
    }

    jstring dalvikName = to_dalvik(image, dalvik, name);
    jstring dalvikMime = to_dalvik(image, dalvik, mime);
    jstring dalvikContent = to_dalvik(image, dalvik, content);

    (*dalvik)->CallStaticVoidMethod(dalvik, invmgr_activity_class, method,
                                    dalvikName, dalvikMime, dalvikContent);
    if ((*dalvik)->ExceptionCheck(dalvik)) {
        (*dalvik)->ExceptionDescribe(dalvik);
        (*dalvik)->ExceptionClear(dalvik);
    }

    if (dalvikName != NULL)    { (*dalvik)->DeleteLocalRef(dalvik, dalvikName); }
    if (dalvikMime != NULL)    { (*dalvik)->DeleteLocalRef(dalvik, dalvikMime); }
    if (dalvikContent != NULL) { (*dalvik)->DeleteLocalRef(dalvik, dalvikContent); }
}

JNIEXPORT void JNICALL
Java_com_modcritic_invmgr_ui_AndroidBridge_nativeRequestOpenFile(JNIEnv *image, jclass cls,
                                                                 jstring mime)
{
    (void) cls;

    JNIEnv *dalvik = invmgr_attach_current_thread();
    if (dalvik == NULL) {
        return;
    }
    jmethodID method = activity_static_method(dalvik, "requestOpenFile", "(Ljava/lang/String;)V");
    if (method == NULL) {
        return;
    }

    jstring dalvikMime = to_dalvik(image, dalvik, mime);
    (*dalvik)->CallStaticVoidMethod(dalvik, invmgr_activity_class, method, dalvikMime);
    if ((*dalvik)->ExceptionCheck(dalvik)) {
        (*dalvik)->ExceptionDescribe(dalvik);
        (*dalvik)->ExceptionClear(dalvik);
    }
    if (dalvikMime != NULL) {
        (*dalvik)->DeleteLocalRef(dalvik, dalvikMime);
    }
}

/* How far the picker has got. The numbers are InvMgrActivity's FILE_ constants. */
JNIEXPORT jint JNICALL
Java_com_modcritic_invmgr_ui_AndroidBridge_nativeFileState(JNIEnv *image, jclass cls)
{
    (void) image; (void) cls;

    JNIEnv *dalvik = invmgr_attach_current_thread();
    if (dalvik == NULL) {
        return 0;
    }
    jmethodID method = activity_static_method(dalvik, "fileState", "()I");
    if (method == NULL) {
        return 0;
    }
    jint state = (*dalvik)->CallStaticIntMethod(dalvik, invmgr_activity_class, method);
    if ((*dalvik)->ExceptionCheck(dalvik)) {
        (*dalvik)->ExceptionDescribe(dalvik);
        (*dalvik)->ExceptionClear(dalvik);
        return 0;
    }
    return state;
}

JNIEXPORT jstring JNICALL
Java_com_modcritic_invmgr_ui_AndroidBridge_nativeTakeFileText(JNIEnv *image, jclass cls)
{
    (void) cls;
    return take_activity_string(image, "takeFileText");
}

JNIEXPORT jstring JNICALL
Java_com_modcritic_invmgr_ui_AndroidBridge_nativeTakeFileError(JNIEnv *image, jclass cls)
{
    (void) cls;
    return take_activity_string(image, "takeFileError");
}


/* How many times the keypad's Next key has been pressed. See InvMgrActivity. */
JNIEXPORT jint JNICALL
Java_com_modcritic_invmgr_ui_AndroidBridge_nativeNextFieldRequests(JNIEnv *image, jclass cls)
{
    (void) image; (void) cls;

    JNIEnv *dalvik = invmgr_attach_current_thread();
    if (dalvik == NULL) {
        return 0;
    }
    jmethodID method = activity_static_method(dalvik, "nextFieldRequests", "()I");
    if (method == NULL) {
        return 0;
    }
    jint count = (*dalvik)->CallStaticIntMethod(dalvik, invmgr_activity_class, method);
    if ((*dalvik)->ExceptionCheck(dalvik)) {
        (*dalvik)->ExceptionDescribe(dalvik);
        (*dalvik)->ExceptionClear(dalvik);
        return 0;
    }
    return count;
}


/* Raise the keyboard again. See AndroidBridge.showKeyboard for why that is ever needed. */
JNIEXPORT void JNICALL
Java_com_modcritic_invmgr_ui_AndroidBridge_nativeShowKeyboard(JNIEnv *image, jclass cls)
{
    (void) image; (void) cls;

    JNIEnv *dalvik = invmgr_attach_current_thread();
    if (dalvik == NULL) {
        return;
    }
    jmethodID method = activity_static_method(dalvik, "showIME", "()V");
    if (method == NULL) {
        return;
    }
    (*dalvik)->CallStaticVoidMethod(dalvik, invmgr_activity_class, method);
    if ((*dalvik)->ExceptionCheck(dalvik)) {
        (*dalvik)->ExceptionDescribe(dalvik);
        (*dalvik)->ExceptionClear(dalvik);
    }
}
