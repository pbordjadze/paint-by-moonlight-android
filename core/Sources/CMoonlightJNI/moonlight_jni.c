// JNI entry points of com.paintbymoonlight.core.NativeCore (NativeCore.kt). Each copies its
// Java arrays in, calls MoonlightCore's C ABI and copies the result out; a failure throws
// com.paintbymoonlight.core.CoreException with the status (1 cancelled, 2 failed, 3 bad input).
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

static jbyteArray to_java(JNIEnv *env, uint8_t *bytes, int64_t count) {
    jbyteArray array = (*env)->NewByteArray(env, (jsize)count);
    if (array != NULL) (*env)->SetByteArrayRegion(env, array, 0, (jsize)count, (const jbyte *)bytes);
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
    jclass byteArray = (*env)->FindClass(env, "[B");
    jobjectArray result = byteArray ? (*env)->NewObjectArray(env, 2, byteArray, NULL) : NULL;
    jbyteArray t = to_java(env, template_bytes, template_count);
    jbyteArray s = to_java(env, settings, settings_count);
    if (result == NULL || t == NULL || s == NULL) return NULL;
    (*env)->SetObjectArrayElement(env, result, 0, t);
    (*env)->SetObjectArrayElement(env, result, 1, s);
    return result;
}

JNIEXPORT jbyteArray JNICALL FN(scene)(JNIEnv *env, jclass cls, jbyteArray template_bytes, jlong nickname_seed) {
    jsize count = (*env)->GetArrayLength(env, template_bytes);
    jbyte *bytes = (*env)->GetByteArrayElements(env, template_bytes, NULL);
    if (bytes == NULL) return NULL;
    uint8_t *out = NULL;
    int64_t out_count = 0;
    int32_t status = mc_scene((const uint8_t *)bytes, count, (uint64_t)nickname_seed, &out, &out_count);
    (*env)->ReleaseByteArrayElements(env, template_bytes, bytes, JNI_ABORT);
    if (status != 0) {
        throw_status(env, status);
        return NULL;
    }
    return to_java(env, out, out_count);
}

#endif
