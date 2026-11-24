package com.paintbymoonlight.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.paintbymoonlight.core.Scene
import kotlin.math.max

/** Draws a painting as it stands, for the gallery: painted areas in their paint, the rest paper, the lines over. */
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
            strokeWidth = 0.8f / k
            color = 0xFFB4B0B8.toInt()
        }
        canvas.drawPath(scene.lines, line)
        return bitmap
    }

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
