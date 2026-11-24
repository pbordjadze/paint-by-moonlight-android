package com.paintbymoonlight.ui.paint

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.paintbymoonlight.core.Scene
import com.paintbymoonlight.data.Progress
import kotlin.math.hypot

/** What a tap on the canvas did. */
sealed interface Tap {
    data class Painted(val region: Int, val finishedPaint: Boolean, val finishedPainting: Boolean) : Tap
    /** The area is another paint's: its swatch shakes and its number pops up. */
    data class OtherPaint(val region: Int, val paint: Int) : Tap
    data object Nothing : Tap
}

/** What happened to the painting, for the canvas to animate and the haptics to play. */
sealed interface PaintEvent {
    /** [region] was painted from the canvas point [x], [y]. */
    data class Painted(val region: Int, val x: Float, val y: Float, val finishedPaint: Boolean, val finishedPainting: Boolean) : PaintEvent
    data class Undone(val region: Int) : PaintEvent
    data class Rejected(val region: Int) : PaintEvent
}

/**
 * The rules of painting one painting, as iOS's `PaintingSession` keeps them: which paint is in
 * hand, what a tap paints (a tap just beside a small area of the paint in hand still finds it),
 * undo, and what is left of each paint. Compose reads [changes] and [selected]; the canvas and
 * palette redraw when they move, and [listen] hears each fill, undo and rejected tap.
 */
class PaintSession(val scene: Scene, val progress: Progress) {
    /** Bumped by every fill and undo. */
    var changes by mutableIntStateOf(0)
        private set
    var selected by mutableIntStateOf(-1)
        private set

    private val remaining = IntArray(scene.paints.size)
    private val listeners = ArrayList<(PaintEvent) -> Unit>()

    init {
        scene.regions.forEachIndexed { i, r -> if (!progress.isPainted(i)) remaining[r.paint]++ }
        selected = progress.inHand.takeIf { it in scene.paints.indices && remaining[it] > 0 }
            ?: openingPaint()
            ?: -1
    }

    val isComplete get() = progress.isComplete
    val fraction get() = if (progress.regionCount == 0) 0f else progress.paintedCount / progress.regionCount.toFloat()
    fun remaining(paint: Int) = remaining[paint]
    fun total(paint: Int) = scene.regionsByPaint[paint].size
    fun isPaintFinished(paint: Int) = remaining[paint] == 0

    /** Hears every event until the returned function is called. */
    fun listen(listener: (PaintEvent) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun emit(event: PaintEvent) = listeners.toList().forEach { it(event) }

    fun select(paint: Int) {
        if (paint in scene.paints.indices) {
            selected = paint
            progress.inHand = paint
        }
    }

    /**
     * Paints the area under a canvas point with the paint in hand, if it is that paint's; else
     * the nearest unpainted area of that paint within [tolerance] canvas units (a fingertip on
     * screen), so a slightly imprecise tap on a small area still lands.
     */
    fun tap(x: Float, y: Float, tolerance: Float = 0f): Tap {
        val hit = scene.regionAt(x, y)
        val target = if (hit != null && !progress.isPainted(hit) && scene.regions[hit].paint == selected) hit
        else nearestUnpainted(x, y, tolerance)
        if (target != null) return fill(target, x, y)
        if (hit == null || progress.isPainted(hit)) return Tap.Nothing
        emit(PaintEvent.Rejected(hit))
        return Tap.OtherPaint(hit, scene.regions[hit].paint)
    }

    private fun nearestUnpainted(x: Float, y: Float, tolerance: Float): Int? {
        if (tolerance <= 0f || selected !in scene.paints.indices) return null
        var best: Int? = null
        var bestDistance = tolerance
        for (r in scene.regionsByPaint[selected]) {
            if (progress.isPainted(r)) continue
            val b = scene.regions[r].bounds
            if (x < b.left - bestDistance || x > b.right + bestDistance || y < b.top - bestDistance || y > b.bottom + bestDistance) continue
            val d = scene.regions[r].distanceTo(x, y)
            if (d <= bestDistance) {
                best = r
                bestDistance = d
            }
        }
        return best
    }

    private fun fill(region: Int, x: Float, y: Float): Tap {
        val paint = scene.regions[region].paint
        if (!progress.paint(region)) return Tap.Nothing
        remaining[paint]--
        val finishedPaint = remaining[paint] == 0
        if (finishedPaint) {
            // The next paint left after this one, coming round, as iOS advances.
            nextPaintLeft(after = paint)?.let { select(it) }
        }
        changes++
        emit(PaintEvent.Painted(region, x, y, finishedPaint, progress.isComplete))
        return Tap.Painted(region, finishedPaint, progress.isComplete)
    }

    /**
     * Takes the last fill back. The paint in hand stays in hand, as on iOS; a finished painting,
     * which holds none, takes up the undone area's.
     */
    fun undo(): Int? {
        val region = progress.undo() ?: return null
        val paint = scene.regions[region].paint
        remaining[paint]++
        if (selected !in scene.paints.indices || remaining[selected] == 0) select(paint)
        changes++
        emit(PaintEvent.Undone(region))
        return region
    }

    val canUndo get() = progress.paintedCount > 0

    /**
     * The unpainted area of [paint] whose number is nearest a canvas point: where Hint and Zen
     * go next (iOS's `showHint(near:)`).
     */
    fun nextArea(paint: Int = selected, nearX: Float, nearY: Float): Int? {
        if (paint !in scene.paints.indices) return null
        return scene.regionsByPaint[paint]
            .filter { !progress.isPainted(it) }
            .minByOrNull {
                val label = scene.labelOf[it]
                if (label >= 0) hypot(scene.labels[label].x - nearX, scene.labels[label].y - nearY)
                else hypot(scene.regions[it].bounds.centerX() - nearX, scene.regions[it].bounds.centerY() - nearY)
            }
    }

    /**
     * A painting nobody has painted on opens on the paint of its largest area, so the first
     * tap finds a big area whose number reads at the opening zoom (iOS's `OpeningColor`); one
     * begun on its first paint left.
     */
    private fun openingPaint(): Int? {
        if (progress.paintedCount == 0) {
            // Ties go to the lower number.
            scene.regions.maxWithOrNull(compareBy({ it.area }, { -it.paint }))?.let { return it.paint }
        }
        return scene.paints.indices.firstOrNull { remaining[it] > 0 }
    }

    private fun nextPaintLeft(after: Int): Int? {
        val n = scene.paints.size
        return (1..n).map { (after + it) % n }.firstOrNull { remaining[it] > 0 }
    }
}
