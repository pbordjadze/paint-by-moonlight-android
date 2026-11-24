package com.paintbymoonlight.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.paintbymoonlight.core.Scene
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a painting as it stands, for the gallery and the Send Template page: painted areas in
 * their paint, the rest paper with their numbers, the lines over at about 60% ink, so a blank
 * template reads as a page to paint rather than a faint sketch.
 */
object Thumbnails {
    /**
     * [numbers] off for a picture of the painting as it stands that leaves the app (Share), as
     * iOS shares one: its numbers are for painting, not for showing.
     */
    fun render(scene: Scene, progress: Progress, longSide: Int = 640, numbers: Boolean = true): Bitmap {
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
        // Each number as the whole painting on a phone shows it, scaled down: its area's size, but
        // no bigger than the canvas lets a number grow, so a large area doesn't take a poster's.
        val largest = max(scene.width, scene.height) * NUMBER_MAX_FRACTION
        if (numbers) for (label in scene.labels) {
            val region = scene.regions.getOrNull(label.region) ?: continue
            val size = min(label.fontSize, largest)
            // A number too small to read at this size would only be a speck.
            if (progress.isPainted(label.region) || size * k < MIN_NUMBER_PX) continue
            number.textSize = size
            canvas.drawText("${region.paint + 1}", label.x, label.y + size * 0.36f, number)
        }
        return bitmap
    }

    /** The lines: the canvas's ink at about 60%. */
    private const val INK = 0x99201C24.toInt()
    private const val NUMBER = 0xFF6A6670.toInt()
    private const val MIN_NUMBER_PX = 8f
    /**
     * The largest number on the canvas (`CanvasLook.numberMax`, 22 dp) over the width a phone
     * fits a painting to (about 380 dp).
     */
    private const val NUMBER_MAX_FRACTION = 22f / 380f

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
