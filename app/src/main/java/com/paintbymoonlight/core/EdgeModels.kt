package com.paintbymoonlight.core

import android.content.res.AssetManager
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** A line model's map of a photo: a byte per pixel, 255 where there is a line. */
internal class LineMap(val width: Int, val height: Int, val levels: ByteArray)

/**
 * The coloring book's two line models (assets/models, written by tools/models/convert.py, whose
 * docstring is their provenance): HED's contours and the line drawing, the networks and weights
 * the iOS app runs. The core prepares each model's input and makes the map of its outputs
 * (MoonlightCore's EdgeModels.swift); LiteRT runs the model between, on the CPU (XNNPACK) in
 * float32, as iOS runs its models on the CPU only: a GPU computes in a precision that differs
 * between chips, and a photo should make the same painting on every phone.
 *
 * Blocking; [cancel] stops a model midway from another thread.
 */
internal class EdgeModels(private val assets: AssetManager) {
    enum class Model(val code: Int, val file: String) {
        Contours(0, "models/hed.tflite"),
        Drawing(1, "models/lineart.tflite"),
    }

    @Volatile private var running: Interpreter? = null
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        running?.setCancelled(true)
    }

    /** [model]'s map of straight RGBA8 sRGB pixels; throws on a failure, [CancellationException] once cancelled. */
    fun map(model: Model, rgba: ByteArray, width: Int, height: Int): LineMap {
        val size = IntArray(4)
        val input = NativeCore.modelInput(rgba, width, height, model.code, size)
        val (w, h, paddedWidth, paddedHeight) = size
        val options = Interpreter.Options()
            .setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            .setCancellable(true)
        Interpreter(load(model), options).use { interpreter ->
            running = interpreter
            try {
                if (cancelled) throw kotlinx.coroutines.CancellationException("cancelled")
                interpreter.resizeInput(0, intArrayOf(1, paddedHeight, paddedWidth, 3))
                interpreter.allocateTensors()
                val shapes = (0 until interpreter.outputTensorCount).map { interpreter.getOutputTensor(it).shape() }
                // Each output is [1, height, width, 1].
                val outputs = shapes.map { FloatBuffer.allocate(it[1] * it[2]) }
                interpreter.runForMultipleInputsOutputs(arrayOf(FloatBuffer.wrap(input)), outputs.indices.associateWith { outputs[it] })
                val dims = IntArray(shapes.size * 2) { shapes[it / 2][1 + it % 2] }
                val levels = NativeCore.modelMap(model.code, outputs.map { it.array() }.toTypedArray(), dims, size)
                return LineMap(w, h, levels)
            } catch (e: IllegalArgumentException) {
                // LiteRT reports a run stopped by setCancelled as an IllegalArgumentException.
                if (cancelled) throw kotlinx.coroutines.CancellationException("cancelled") else throw e
            } finally {
                running = null
            }
        }
    }

    /** Memory-mapped from the APK, where the models are stored uncompressed (app/build.gradle.kts). */
    private fun load(model: Model): MappedByteBuffer = assets.openFd(model.file).use { fd ->
        FileInputStream(fd.fileDescriptor).use { stream ->
            stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }
}
