package com.paintbymoonlight.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.paintbymoonlight.core.Scene
import kotlin.math.max

/**
 * Draws a painting as it stands, for the gallery and the Send Template page: painted areas in
 * their paint, the rest paper with their numbers, the lines over at about 60% ink, so a blank
 * template reads as a page to paint rather than a faint sketch.
 */
object Thumbnails {
    fun render(scene: Scene, progress: Progress, longSide: Int = 640): Bitmap {
        val k = longSide / max(scene.width, scene.height)
        val bitmap = Bitmap.createBitmap(
            (scene.width * k).toInt().coerceAtLeast(1), (scene.height * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(k, k)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(0xFFFFFFFF.toInt())
        scene.regions.forEachIndexed { i, region ->
            if (progress.isPainted(i)) {
                fill.color = scene.paints[region.paint].color
                canvas.drawPath(region.path, fill)
            }
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f / k
            strokeJoin = Paint.Join.ROUND
            color = INK
        }
        canvas.drawPath(scene.lines, line)
        if (!scene.strokes.isEmpty) canvas.drawPath(scene.strokes, line)
        val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NUMBER
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        for (label in scene.labels) {
            val region = scene.regions.getOrNull(label.region) ?: continue
            // A number too small to make out at this size would only be a speck.
            if (progress.isPainted(label.region) || label.fontSize * k < MIN_NUMBER_PX) continue
            number.textSize = label.fontSize
            canvas.drawText("${region.paint + 1}", label.x, label.y + label.fontSize * 0.36f, number)
        }
        return bitmap
    }

    /** The lines: the canvas's ink at about 60%. */
    private const val INK = 0x99201C24.toInt()
    private const val NUMBER = 0xFF6A6670.toInt()
    private const val MIN_NUMBER_PX = 4.5f

    /** The finished painting, no lines: what a painter shares. */
    fun finished(scene: Scene, longSide: Int = 2048): Bitmap {
        val k = longSide / max(scene.width, scene.height)
        val bitmap = Bitmap.createBitmap(
            (scene.width * k).toInt().coerceAtLeast(1), (scene.height * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(k, k)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = 0.6f }
        canvas.drawColor(0xFFFFFFFF.toInt())
        // Stroked a hair as well as filled, so neighbours meet without a seam of paper.
        scene.regions.forEach { region ->
            fill.color = scene.paints[region.paint].color
            canvas.drawPath(region.path, fill)
        }
        return bitmap
    }
}
