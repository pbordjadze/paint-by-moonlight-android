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
                                        jint length, jbyteArray findings) {
    if (width <= 0 || height <= 0 || (int64_t)(*env)->GetArrayLength(env, rgba) < (int64_t)width * height * 4) {
        throw_status(env, 3);
        return NULL;
    }
    jbyte *pixels = (*env)->GetByteArrayElements(env, rgba, NULL);
    if (pixels == NULL) return NULL;
    jsize findings_count = (*env)->GetArrayLength(env, findings);
    jbyte *found = (*env)->GetByteArrayElements(env, findings, NULL);
    if (found == NULL) {
        (*env)->ReleaseByteArrayElements(env, rgba, pixels, JNI_ABORT);
        return NULL;
    }
    uint8_t *template_bytes = NULL, *settings = NULL;
    int64_t template_count = 0, settings_count = 0;
    int32_t status = mc_make((void *)(intptr_t)job, (const uint8_t *)pixels, width, height, length, (const uint8_t *)found,
                             findings_count, &template_bytes, &template_count, &settings, &settings_count);
    (*env)->ReleaseByteArrayElements(env, findings, found, JNI_ABORT);
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

// A line model's input; `size` gets the map's width and height, then the padded width and height.
JNIEXPORT jfloatArray JNICALL FN(modelInput)(JNIEnv *env, jclass cls, jbyteArray rgba, jint width, jint height, jint model,
                                             jintArray size) {
    if (width <= 0 || height <= 0 || (int64_t)(*env)->GetArrayLength(env, rgba) < (int64_t)width * height * 4 ||
        (*env)->GetArrayLength(env, size) < 4) {
        throw_status(env, 3);
        return NULL;
    }
    jbyte *pixels = (*env)->GetByteArrayElements(env, rgba, NULL);
    if (pixels == NULL) return NULL;
    float *values = NULL;
    int64_t count = 0;
    int32_t dims[4] = {0, 0, 0, 0};
    int32_t status = mc_model_input((const uint8_t *)pixels, width, height, model, &values, &count, dims);
    (*env)->ReleaseByteArrayElements(env, rgba, pixels, JNI_ABORT);
    if (status != 0) {
        throw_status(env, status);
        return NULL;
    }
    jfloatArray array = (*env)->NewFloatArray(env, (jsize)count);
    if (array != NULL) {
        (*env)->SetFloatArrayRegion(env, array, 0, (jsize)count, values);
        (*env)->SetIntArrayRegion(env, size, 0, 4, (const jint *)dims);
    }
    mc_free(values);
    return array;
}

// A line model's map from its outputs ({ height, width } per output in `shapes`) and the `size`
// modelInput gave.
JNIEXPORT jbyteArray JNICALL FN(modelMap)(JNIEnv *env, jclass cls, jint model, jobjectArray outputs, jintArray shapes,
                                          jintArray size) {
    jsize count = (*env)->GetArrayLength(env, outputs);
    if (count <= 0 || count > 16 || (*env)->GetArrayLength(env, shapes) < 2 * count ||
        (*env)->GetArrayLength(env, size) < 4) {
        throw_status(env, 3);
        return NULL;
    }
    jint dims[32], box[4];
    (*env)->GetIntArrayRegion(env, shapes, 0, 2 * count, dims);
    (*env)->GetIntArrayRegion(env, size, 0, 4, box);
    jfloatArray arrays[16];
    const float *values[16];
    jsize got = 0;
    int32_t status = 0;
    for (; got < count; got++) {
        arrays[got] = (jfloatArray)(*env)->GetObjectArrayElement(env, outputs, got);
        if (arrays[got] == NULL ||
            (int64_t)(*env)->GetArrayLength(env, arrays[got]) < (int64_t)dims[2 * got] * dims[2 * got + 1]) {
            status = 3;
            break;
        }
        values[got] = (*env)->GetFloatArrayElements(env, arrays[got], NULL);
        if (values[got] == NULL) {
            status = -1;
            break;
        }
    }
    uint8_t *out = NULL;
    int64_t out_count = 0;
    if (status == 0) {
        status = mc_model_map(model, values, (const int32_t *)dims, count, (const int32_t *)box, &out, &out_count);
    }
    // Every array whose elements were taken (the first `got`) is released, also after a failure.
    for (jsize i = 0; i < got; i++) {
        (*env)->ReleaseFloatArrayElements(env, arrays[i], (jfloat *)values[i], JNI_ABORT);
    }
    if (status != 0) {
        if (status > 0) throw_status(env, status);
        return NULL;
    }
    return to_java(env, out, out_count);
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

#endif
