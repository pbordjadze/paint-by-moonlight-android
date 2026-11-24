package com.paintbymoonlight.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * What the app finds in a photo before the core makes its painting, as iOS finds it with Core ML
 * and Vision: the coloring book's two maps ([EdgeModels]), and from ML Kit the subjects' mask,
 * the faces with their eyes' contours and the lines of text. The core turns them into the
 * importance map, the hints and the coloring book's input iOS gives PaintCore (MoonlightCore's
 * Findings.swift, whose documentation is the format [encoded] writes).
 *
 * Each piece is optional: one that fails (an ML Kit model Play services hasn't downloaded yet,
 * a phone without Play services) is left out, and without both maps the painting is classic.
 */
internal class Findings(
    val drawing: LineMap? = null,
    val contours: LineMap? = null,
    val mask: LineMap? = null,
    val faces: List<Face> = emptyList(),
    /** Each line's quadrilateral, normalized, clockwise from the top left. */
    val text: List<List<PointF>> = emptyList(),
) {
    /** Normalized to the photo, origin top-left. */
    class Face(val x: Float, val y: Float, val width: Float, val height: Float, val leftEye: List<PointF>, val rightEye: List<PointF>)

    fun encoded(): ByteArray {
        val out = ByteArrayOutputStream()
        fun record(tag: String, body: ByteArray) {
            out.write(tag.toByteArray(Charsets.US_ASCII))
            out.write(le(4).putInt(body.size).array())
            out.write(body)
        }
        fun map(m: LineMap): ByteArray = le(8 + m.levels.size).putInt(m.width).putInt(m.height).put(m.levels).array()
        fun points(points: List<PointF>, buffer: ByteBuffer) = points.forEach { buffer.putFloat(it.x).putFloat(it.y) }

        out.write("MLIN".toByteArray(Charsets.US_ASCII))
        out.write(le(4).putInt(VERSION).array())
        drawing?.let { record("DRAW", map(it)) }
        contours?.let { record("CONT", map(it)) }
        mask?.let { record("MASK", map(it)) }
        for (face in faces) {
            val body = le(16 + 8 + 8 * (face.leftEye.size + face.rightEye.size))
            body.putFloat(face.x).putFloat(face.y).putFloat(face.width).putFloat(face.height)
            body.putInt(face.leftEye.size)
            points(face.leftEye, body)
            body.putInt(face.rightEye.size)
            points(face.rightEye, body)
            record("FACE", body.array())
        }
        for (quad in text) {
            val body = le(32)
            points(quad, body)
            record("TEXT", body.array())
        }
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "Findings"
        private const val VERSION = 1
        /** How long an ML Kit request may take before its finding is left out. */
        private const val ML_KIT_SECONDS = 30L
        /** Lines ML Kit reads with less confidence, or fewer letters and digits, are marks taken for text (iOS's TextFinder). */
        private const val MINIMUM_TEXT_CONFIDENCE = 0.3f
        private const val MINIMUM_TEXT_CHARACTERS = 2

        private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        /**
         * Everything found in [photo] ([rgba], its pixels). The models run one after the other
         * while ML Kit runs beside them; [onProgress] gets 0…1 as the models finish. Cancelling
         * stops the models at the end of the one running (LiteRT's CPU kernels run a model whole).
         */
        suspend fun find(context: Context, photo: Bitmap, rgba: ByteArray, onProgress: (Float) -> Unit): Findings = coroutineScope {
            val subjects = async(Dispatchers.IO) { mlKit("subjects") { subjects(photo) } }
            val faces = async(Dispatchers.IO) { mlKit("faces") { faces(photo) } ?: emptyList() }
            val text = async(Dispatchers.IO) { mlKit("text") { text(photo) } ?: emptyList() }
            val models = EdgeModels(context.assets)
            val stopper = launch {
                try {
                    awaitCancellation()
                } finally {
                    models.cancel()
                }
            }
            val maps = try {
                val contours = withContext(Dispatchers.Default) { models.map(EdgeModels.Model.Contours, rgba, photo.width, photo.height) }
                onProgress(0.6f)
                val drawing = withContext(Dispatchers.Default) { models.map(EdgeModels.Model.Drawing, rgba, photo.width, photo.height) }
                onProgress(0.9f)
                contours to drawing
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "The line models failed: the painting will be classic", e)
                null
            } finally {
                stopper.cancelAndJoin()
            }
            val found = Findings(maps?.second, maps?.first, subjects.await(), faces.await(), text.await())
            onProgress(1f)
            found
        }

        private suspend fun <T> mlKit(what: String, find: () -> T): T? = try {
            runInterruptible { find() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit found no $what", e)
            null
        }

        /** Waits for an ML Kit request on this thread (its own callbacks come on the main thread). */
        private fun <T> Task<T>.result(): T = Tasks.await(this, ML_KIT_SECONDS, TimeUnit.SECONDS)

        /** The subjects' mask: ML Kit's foreground confidence, a byte per pixel of the photo. */
        private fun subjects(photo: Bitmap): LineMap {
            val segmenter = SubjectSegmentation.getClient(
                SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
            try {
                val confidence = segmenter.process(InputImage.fromBitmap(photo, 0)).result().foregroundConfidenceMask
                    ?: throw IllegalStateException("no mask")
                confidence.rewind()
                val levels = ByteArray(photo.width * photo.height) {
                    (confidence.get().coerceIn(0f, 1f) * 255 + 0.5f).toInt().toByte()
                }
                return LineMap(photo.width, photo.height, levels)
            } finally {
                segmenter.close()
            }
        }

        /** Every face's box, and the most prominent face's eye contours (ML Kit gives contours for that one alone). */
        private fun faces(photo: Bitmap): List<Face> {
            val detector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                    .build())
            val w = photo.width.toFloat()
            val h = photo.height.toFloat()
            fun normalized(points: List<PointF>?) = points.orEmpty().map { PointF(it.x / w, it.y / h) }
            try {
                return detector.process(InputImage.fromBitmap(photo, 0)).result().map { face ->
                    val box = face.boundingBox
                    Face(
                        box.left / w, box.top / h, box.width() / w, box.height() / h,
                        normalized(face.getContour(FaceContour.LEFT_EYE)?.points),
                        normalized(face.getContour(FaceContour.RIGHT_EYE)?.points),
                    )
                }
            } finally {
                detector.close()
            }
        }

        /** The lines of text, each line's corners normalized. */
        private fun text(photo: Bitmap): List<List<PointF>> {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val w = photo.width.toFloat()
            val h = photo.height.toFloat()
            try {
                return recognizer.process(InputImage.fromBitmap(photo, 0)).result().textBlocks
                    .flatMap { it.lines }
                    .filter { line ->
                        line.confidence >= MINIMUM_TEXT_CONFIDENCE &&
                            line.text.count { it.isLetterOrDigit() } >= MINIMUM_TEXT_CHARACTERS
                    }
                    .mapNotNull { line -> line.cornerPoints?.takeIf { it.size == 4 }?.map { PointF(it.x / w, it.y / h) } }
            } finally {
                recognizer.close()
            }
        }
    }
}
