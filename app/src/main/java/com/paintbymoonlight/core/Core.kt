package com.paintbymoonlight.core

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

    /**
     * Makes a painting from [photo] as the iOS app makes a new one: Auto's settings for
     * [length], beauty paint. Cancelling the coroutine stops the pipeline; [onProgress] gets
     * 0…1 on the main thread's dispatcher of the caller.
     */
    suspend fun make(photo: Bitmap, length: PaintingLength, onProgress: (Float) -> Unit = {}): Made =
        coroutineScope {
            val rgba = withContext(Dispatchers.Default) { rgbaBytes(photo) }
            val job = NativeCore.jobNew()
            // Reports progress while the pipeline runs, and cancels it when this coroutine is
            // cancelled: a child is cancelled at once, while the blocking call below runs on.
            val watcher = launch {
                try {
                    while (true) {
                        onProgress(NativeCore.jobProgress(job))
                        delay(50)
                    }
                } finally {
                    NativeCore.jobCancel(job)
                }
            }
            try {
                val result = withContext(Dispatchers.Default) {
                    NativeCore.make(job, rgba, photo.width, photo.height, length.ordinal)
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

    /** The scene the canvas draws, with the paints' nicknames seeded by [nicknameSeed]. */
    suspend fun scene(template: ByteArray, nicknameSeed: Long): Scene = withContext(Dispatchers.Default) {
        Scene.read(NativeCore.scene(template, nicknameSeed))
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
