package com.paintbymoonlight.core

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/** How long a painting should take, as the iOS app's Painting Length. */
enum class PaintingLength { Quick, Relaxed, Detailed }

/** A painting the core made: the encoded template (iOS's `.pbnt`) and its settings' JSON. */
class Made(val template: ByteArray, val settingsJson: String)

/** The Swift core (PaintCore, the same pipeline as iOS) as the app calls it. */
object Core {
    val pipelineVersion: Int by lazy { NativeCore.pipelineVersion() }

    /** The application, whose assets hold the line models; until attached, paintings are classic. */
    @Volatile private var context: Context? = null

    fun attach(application: Context) {
        context = application.applicationContext
    }

    /**
     * Makes a painting from [photo] as the iOS app makes a new one: Auto's settings for
     * [length], beauty paint, and the coloring book from the line models and what ML Kit finds
     * ([Findings]), or a classic painting when the models can't run. Cancelling the coroutine
     * stops the work; [onProgress] gets 0…1 on the main thread's dispatcher of the caller.
     */
    suspend fun make(photo: Bitmap, length: PaintingLength, onProgress: (Float) -> Unit = {}): Made =
        coroutineScope {
            val rgba = withContext(Dispatchers.Default) { rgbaBytes(photo) }
            val findings = context?.let { Findings.find(it, photo, rgba) { p -> onProgress(FINDING * p) } } ?: Findings()
            val job = NativeCore.jobNew()
            // Reports progress while the pipeline runs, and cancels it when this coroutine is
            // cancelled: a child is cancelled at once, while the blocking call below runs on.
            val watcher = launch {
                try {
                    while (true) {
                        onProgress(FINDING + (1 - FINDING) * NativeCore.jobProgress(job))
                        delay(50)
                    }
                } finally {
                    NativeCore.jobCancel(job)
                }
            }
            try {
                val result = withContext(Dispatchers.Default) {
                    NativeCore.make(job, rgba, photo.width, photo.height, length.ordinal, findings.encoded())
                }
                onProgress(1f)
                Made(result[0], result[1].decodeToString())
            } catch (e: CoreException) {
                if (e.status == 1) throw CancellationException("cancelled") else throw e
            } finally {
                withContext(NonCancellable) { watcher.cancelAndJoin() }
                NativeCore.jobFree(job)
            }
        }

    /** The share of the progress bar the line models and ML Kit take: on a phone, about as long as the pipeline. */
    private const val FINDING = 0.5f

    /** The nickname vocabulary a new painting records (PaintCore's `ColorNickname.currentVersion`). */
    const val NICKNAME_VERSION = 2

    /**
     * The scene the canvas draws, with the paints' nicknames seeded by [nicknameSeed] from the
     * vocabulary the painting recorded ([nicknameVersion]), so a newer vocabulary never renames them.
     */
    suspend fun scene(template: ByteArray, nicknameSeed: Long, nicknameVersion: Int): Scene = withContext(Dispatchers.Default) {
        Scene.read(NativeCore.scene(template, nicknameSeed, nicknameVersion))
    }

    private fun rgbaBytes(photo: Bitmap): ByteArray {
        val bitmap = if (photo.config == Bitmap.Config.ARGB_8888) photo else photo.copy(Bitmap.Config.ARGB_8888, false)
        // ARGB_8888 stores R, G, B, A bytes in memory order; photos are opaque, so premultiplied
        // and straight alpha agree.
        val buffer = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        return buffer.array()
    }
}
