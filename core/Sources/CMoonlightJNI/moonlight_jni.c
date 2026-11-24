// JNI entry points of com.paintbymoonlight.core.NativeCore (NativeCore.kt). Each copies its
// Java arrays in, calls MoonlightCore's C ABI and copies the result out; a failure throws
// com.paintbymoonlight.core.CoreException with the status (1 cancelled, 2 failed, 3 bad input,
// 4 a file from a newer app).
// Hosts without jni.h (the probe built on Linux) compile none of it.
#if __has_include(<jni.h>)
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "CMoonlightJNI.h"

#define FN(name) Java_com_paintbymoonlight_core_NativeCore_##name

static void throw_status(JNIEnv *env, int32_t status) {
    jclass cls = (*env)->FindClass(env, "com/paintbymoonlight/core/CoreException");
    if (cls == NULL) return; // NoClassDefFoundError is already pending
    jmethodID init = (*env)->GetMethodID(env, cls, "<init>", "(I)V");
    if (init == NULL) return;
    jobject e = (*env)->NewObject(env, cls, init, (jint)status);
    if (e != NULL) (*env)->Throw(env, (jthrowable)e);
}

// Copies a buffer the core handed out into a new Java array and frees it. NULL when the copy
// failed, with the Java exception (OutOfMemoryError) pending.
static jbyteArray to_java(JNIEnv *env, uint8_t *bytes, int64_t count) {
    jbyteArray array = (*env)->NewByteArray(env, (jsize)count);
    if (array != NULL) {
        (*env)->SetByteArrayRegion(env, array, 0, (jsize)count, (const jbyte *)bytes);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->DeleteLocalRef(env, array);
            array = NULL;
        }
    }
    mc_free(bytes);
    return array;
}

JNIEXPORT jint JNICALL FN(pipelineVersion)(JNIEnv *env, jclass cls) { return (jint)mc_pipeline_version(); }
JNIEXPORT jlong JNICALL FN(jobNew)(JNIEnv *env, jclass cls) { return (jlong)(intptr_t)mc_job_new(); }
JNIEXPORT void JNICALL FN(jobFree)(JNIEnv *env, jclass cls, jlong job) { mc_job_free((void *)(intptr_t)job); }
JNIEXPORT void JNICALL FN(jobCancel)(JNIEnv *env, jclass cls, jlong job) { mc_job_cancel((void *)(intptr_t)job); }
JNIEXPORT jfloat JNICALL FN(jobProgress)(JNIEnv *env, jclass cls, jlong job) {
    return mc_job_progress((void *)(intptr_t)job);
}

// Returns { template bytes, settings JSON }.
JNIEXPORT jobjectArray JNICALL FN(make)(JNIEnv *env, jclass cls, jlong job, jbyteArray rgba, jint width, jint height,
                                        jint length) {
    if (width <= 0 || height <= 0 || (int64_t)(*env)->GetArrayLength(env, rgba) < (int64_t)width * height * 4) {
        throw_status(env, 3);
        return NULL;
    }
    jbyte *pixels = (*env)->GetByteArrayElements(env, rgba, NULL);
    if (pixels == NULL) return NULL;
    uint8_t *template_bytes = NULL, *settings = NULL;
    int64_t template_count = 0, settings_count = 0;
    int32_t status = mc_make((void *)(intptr_t)job, (const uint8_t *)pixels, width, height, length,
                             &template_bytes, &template_count, &settings, &settings_count);
    (*env)->ReleaseByteArrayElements(env, rgba, pixels, JNI_ABORT);
    if (status != 0) {
        throw_status(env, status);
        return NULL;
    }
    // Each step can leave an exception pending, after which no other JNI call is allowed: check
    // after each, and free what the core handed out that was not yet copied.
    jclass byteArray = (*env)->FindClass(env, "[B");
    jobjectArray result = byteArray != NULL ? (*env)->NewObjectArray(env, 2, byteArray, NULL) : NULL;
    if (result == NULL) {
        mc_free(template_bytes);
        mc_free(settings);
        return NULL;
    }
    jbyteArray t = to_java(env, template_bytes, template_count);
    if (t == NULL) {
        mc_free(settings);
        return NULL;
    }
    (*env)->SetObjectArrayElement(env, result, 0, t);
    if ((*env)->ExceptionCheck(env)) {
        mc_free(settings);
        return NULL;
    }
    jbyteArray s = to_java(env, settings, settings_count);
    if (s == NULL) return NULL;
    (*env)->SetObjectArrayElement(env, result, 1, s);
    if ((*env)->ExceptionCheck(env)) return NULL;
    return result;
}

JNIEXPORT jbyteArray JNICALL FN(scene)(JNIEnv *env, jclass cls, jbyteArray template_bytes, jlong nickname_seed,
                                       jint nickname_version) {
    jsize count = (*env)->GetArrayLength(env, template_bytes);
    jbyte *bytes = (*env)->GetByteArrayElements(env, template_bytes, NULL);
    if (bytes == NULL) return NULL;
    uint8_t *out = NULL;
    int64_t out_count = 0;
    int32_t status = mc_scene((const uint8_t *)bytes, count, (uint64_t)nickname_seed, (int32_t)nickname_version, &out,
                              &out_count);
    (*env)->ReleaseByteArrayElements(env, template_bytes, bytes, JNI_ABORT);
    if (status != 0) {
        throw_status(env, status);
        return NULL;
    }
    return to_java(env, out, out_count);
}

// Returns { template bytes, info JSON, photo bytes or null } of a .moonlight file someone sent.
JNIEXPORT jobjectArray JNICALL FN(sharedRead)(JNIEnv *env, jclass cls, jbyteArray file) {
    jsize count = (*env)->GetArrayLength(env, file);
    jbyte *bytes = (*env)->GetByteArrayElements(env, file, NULL);
    if (bytes == NULL) return NULL;
    uint8_t *template_bytes = NULL, *info = NULL, *photo = NULL;
    int64_t template_count = 0, info_count = 0, photo_count = 0;
    int32_t status = mc_shared_read((const uint8_t *)bytes, count, &template_bytes, &template_count, &info, &info_count,
                                    &photo, &photo_count);
    (*env)->ReleaseByteArrayElements(env, file, bytes, JNI_ABORT);
    if (status != 0) {
        throw_status(env, status);
        return NULL;
    }
    // As in make: after each step that can leave an exception pending, free what wasn't copied.
    uint8_t *pending[3] = {template_bytes, info, photo};
    int64_t counts[3] = {template_count, info_count, photo_count};
    jclass byteArray = (*env)->FindClass(env, "[B");
    jobjectArray result = byteArray != NULL ? (*env)->NewObjectArray(env, 3, byteArray, NULL) : NULL;
    for (int i = 0; i < 3; i++) {
        if (result == NULL || pending[i] == NULL) {
            if (pending[i] != NULL) mc_free(pending[i]);
            continue;
        }
        jbyteArray a = to_java(env, pending[i], counts[i]);
        if (a != NULL) (*env)->SetObjectArrayElement(env, result, i, a);
        if (a == NULL || (*env)->ExceptionCheck(env)) result = NULL;
    }
    return result;
}

// Borrows a Java array's bytes, or none for a null array.
static jbyte *borrow(JNIEnv *env, jbyteArray array, jsize *count) {
    *count = 0;
    if (array == NULL) return NULL;
    *count = (*env)->GetArrayLength(env, array);
    return (*env)->GetByteArrayElements(env, array, NULL);
}

static void give_back(JNIEnv *env, jbyteArray array, jbyte *bytes) {
    if (array != NULL && bytes != NULL) (*env)->ReleaseByteArrayElements(env, array, bytes, JNI_ABORT);
}

JNIEXPORT jbyteArray JNICALL FN(sharedWrite)(JNIEnv *env, jclass cls, jbyteArray template_bytes, jbyteArray title,
                                             jbyteArray settings, jbyteArray photo, jlong nickname_seed,
                                             jint nickname_version) {
    jsize counts[4];
    jbyteArray arrays[4] = {template_bytes, title, settings, photo};
    jbyte *bytes[4] = {NULL, NULL, NULL, NULL};
    int borrowed = 1;
    for (int i = 0; i < 4; i++) {
        bytes[i] = borrow(env, arrays[i], &counts[i]);
        if (arrays[i] != NULL && bytes[i] == NULL) borrowed = 0;
    }
    uint8_t *out = NULL;
    int64_t out_count = 0;
    int32_t status = 3;
    if (borrowed) {
        status = mc_shared_write((const uint8_t *)bytes[0], counts[0], (const uint8_t *)bytes[1], counts[1],
                                 (const uint8_t *)bytes[2], counts[2], (const uint8_t *)bytes[3], counts[3],
                                 (uint64_t)nickname_seed, (int32_t)nickname_version, &out, &out_count);
    }
    for (int i = 0; i < 4; i++) give_back(env, arrays[i], bytes[i]);
    if (!borrowed) return NULL; // OutOfMemoryError is pending
    if (status != 0) {
        throw_status(env, status);
        return NULL;
    }
    return to_java(env, out, out_count);
}

#endif
