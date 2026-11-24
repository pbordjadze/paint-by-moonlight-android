package com.paintbymoonlight.core

/**
 * The native core's JNI entry points (core/Sources/CMoonlightJNI/moonlight_jni.c). Everything
 * here is blocking and thread-safe; [Core] is the API the app uses.
 */
internal object NativeCore {
    init {
        System.loadLibrary("MoonlightCore")
    }

    @JvmStatic external fun pipelineVersion(): Int
    @JvmStatic external fun jobNew(): Long
    @JvmStatic external fun jobFree(job: Long)
    @JvmStatic external fun jobCancel(job: Long)
    @JvmStatic external fun jobProgress(job: Long): Float

    /** Returns { encoded template, settings JSON }; [findings] as [Findings.encoded] writes them, or empty; throws [CoreException]. */
    @JvmStatic external fun make(job: Long, rgba: ByteArray, width: Int, height: Int, length: Int, findings: ByteArray): Array<ByteArray>

    /** A line model's [1, H, W, 3] input; [size] gets the map's width and height, then the padded ones (EdgeModels.swift). */
    @JvmStatic external fun modelInput(rgba: ByteArray, width: Int, height: Int, model: Int, size: IntArray): FloatArray

    /** A line model's map, a byte per pixel, from its outputs ({ height, width } of each in [shapes]) and modelInput's [size]. */
    @JvmStatic external fun modelMap(model: Int, outputs: Array<FloatArray>, shapes: IntArray, size: IntArray): ByteArray

    /** The scene (Scene.kt) of an encoded template; throws [CoreException]. */
    @JvmStatic external fun scene(template: ByteArray, nicknameSeed: Long, nicknameVersion: Int): ByteArray

    /** Returns { encoded template, info JSON, photo or null } of a `.moonlight` file; throws [CoreException]. */
    @JvmStatic external fun sharedRead(file: ByteArray): Array<ByteArray?>

    /** A `.moonlight` file of a painting; throws [CoreException]. */
    @JvmStatic external fun sharedWrite(
        template: ByteArray, title: ByteArray, settings: ByteArray?, photo: ByteArray?, nicknameSeed: Long, nicknameVersion: Int,
    ): ByteArray
}

/** A failure the native core reports: 1 cancelled, 2 failed, 3 bad input, 4 a file from a newer app. */
class CoreException(val status: Int) : Exception(
    when (status) {
        1 -> "cancelled"
        2 -> "the painting could not be made"
        4 -> "made by a newer app"
        else -> "bad input"
    }
)
