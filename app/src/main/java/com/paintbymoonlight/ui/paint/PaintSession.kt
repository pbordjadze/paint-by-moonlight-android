package com.paintbymoonlight.ui.paint

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.paintbymoonlight.core.Scene
import com.paintbymoonlight.data.Progress
import kotlin.math.hypot

/** What a tap on the canvas did. */
sealed interface Tap {
    data class Painted(val region: Int, val finishedPaint: Boolean, val finishedPainting: Boolean) : Tap
    /** The area is another paint's: its swatch should shake. */
    data class OtherPaint(val paint: Int) : Tap
    data object Nothing : Tap
}

/**
 * The rules of painting one painting: which paint is in hand, what a tap paints, undo, and
 * what is left of each paint. Compose reads [changes] and [selected]; the canvas and palette
 * redraw when they move.
 */
class PaintSession(val scene: Scene, val progress: Progress) {
    /** Bumped by every fill and undo. */
    var changes by mutableIntStateOf(0)
        private set
    var selected by mutableIntStateOf(-1)
        private set
    /** The area most recently painted, for the canvas's reveal; null after an undo. */
    var lastFill by mutableStateOf<Int?>(null)
        private set

    private val remaining = IntArray(scene.paints.size)

    init {
        scene.regions.forEachIndexed { i, r -> if (!progress.isPainted(i)) remaining[r.paint]++ }
        selected = progress.inHand.takeIf { it in scene.paints.indices && remaining[it] > 0 }
            ?: firstPaintLeft()
            ?: -1
    }

    val isComplete get() = progress.isComplete
    val fraction get() = if (progress.regionCount == 0) 0f else progress.paintedCount / progress.regionCount.toFloat()
    fun remaining(paint: Int) = remaining[paint]
    fun total(paint: Int) = scene.regionsByPaint[paint].size
    fun isPaintFinished(paint: Int) = remaining[paint] == 0

    fun select(paint: Int) {
        if (paint in scene.paints.indices) {
            selected = paint
            progress.inHand = paint
        }
    }

    /** Paints the area under a canvas point with the paint in hand, if it is that paint's. */
    fun tap(x: Float, y: Float): Tap {
        val region = scene.regionAt(x, y) ?: return Tap.Nothing
        if (progress.isPainted(region)) return Tap.Nothing
        val paint = scene.regions[region].paint
        if (paint != selected) return Tap.OtherPaint(paint)
        return fill(region)
    }

    private fun fill(region: Int): Tap {
        val paint = scene.regions[region].paint
        if (!progress.paint(region)) return Tap.Nothing
        remaining[paint]--
        lastFill = region
        val finishedPaint = remaining[paint] == 0
        if (finishedPaint) {
            // The next paint left after this one, coming round, as iOS advances.
            nextPaintLeft(after = paint)?.let { select(it) }
        }
        changes++
        return Tap.Painted(region, finishedPaint, progress.isComplete)
    }

    fun undo(): Int? {
        val region = progress.undo() ?: return null
        val paint = scene.regions[region].paint
        remaining[paint]++
        select(paint)
        lastFill = null
        changes++
        return region
    }

    val canUndo get() = progress.paintedCount > 0

    /** The unpainted area of [paint] nearest a canvas point: where Hint and Zen go next. */
    fun nextArea(paint: Int = selected, nearX: Float, nearY: Float): Int? {
        if (paint !in scene.paints.indices) return null
        return scene.regionsByPaint[paint]
            .filter { !progress.isPainted(it) }
            .minByOrNull {
                val b = scene.regions[it].bounds
                hypot(b.centerX() - nearX, b.centerY() - nearY)
            }
    }

    private fun firstPaintLeft(): Int? = scene.paints.indices.firstOrNull { remaining[it] > 0 }

    private fun nextPaintLeft(after: Int): Int? {
        val n = scene.paints.size
        return (1..n).map { (after + it) % n }.firstOrNull { remaining[it] > 0 }
    }
}
