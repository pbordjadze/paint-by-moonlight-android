package com.paintbymoonlight.ui.paint

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.paintbymoonlight.data.SavedView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A scale and offset: where a point of the painting lands on screen is `point * scale + offset`. */
data class Placement(val scale: Float, val offset: Offset)

/** The scale at which a [width] × [height] painting fits [size], inset by [inset] px. */
fun fitScale(size: IntSize, width: Float, height: Float, inset: Float): Float =
    if (size.width == 0 || size.height == 0) 1f
    else min((size.width - 2 * inset) / width, (size.height - 2 * inset) / height).coerceAtLeast(1e-3f)

/** The whole painting, centered and inset. */
fun fitting(width: Float, height: Float, inset: Float): (IntSize) -> Placement = { size ->
    centered(size, width / 2, height / 2, fitScale(size, width, height, inset))
}

/** The painting filling the view (cropped along one side), centered: numbers big enough to read. */
fun filling(width: Float, height: Float, inset: Float): (IntSize) -> Placement = { size ->
    val fit = fitScale(size, width, height, inset)
    val fill = max((size.width - 2 * inset) / width, (size.height - 2 * inset) / height)
    // A panorama on a phone would crop to a sliver: never more than this over the fit.
    centered(size, width / 2, height / 2, min(fill, fit * 2.5f))
}

/** Back where the painter left it: [view]'s center in the middle, at its zoom over the fitted scale. */
fun restoring(view: SavedView, width: Float, height: Float, inset: Float): (IntSize) -> Placement = { size ->
    centered(size, view.centerX, view.centerY, view.zoom * fitScale(size, width, height, inset))
}

internal fun centered(size: IntSize, x: Float, y: Float, scale: Float) =
    Placement(scale, Offset(size.width / 2f - x * scale, size.height / 2f - y * scale))

/**
 * Where the canvas looks, held as iOS's canvas holds its scroll view: a point of the painting
 * is drawn at `point * scale + offset` on screen, never further out than the whole painting
 * ([fit], `inset` clear all round) nor in past where its small numbers read comfortably
 * ([deepest]), and never panned further than `inset` past an edge (a painting smaller than the
 * view stays centered). Until the painter moves it, the camera rests at a placement that follows
 * the viewport as it changes size (the Finished bar coming in, a rotation, a shared transition).
 * [flyTo] eases there as iOS's flights do (out-cubic, the zoom geometric about the motion's fixed
 * point); a fling glides on and slows.
 */
@Stable
class Camera {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var viewport by mutableStateOf(IntSize.Zero)
        private set
    private var rest: ((IntSize) -> Placement)? = null
    private var placed = false
    /**
     * Counts the painter's moves (gestures, Hint and Zen flights), so a flight they interrupt
     * doesn't claim the camera rests, and so only their moves, never an automatic placement,
     * are saved as where the painting was left.
     */
    var moves by mutableIntStateOf(0)
        private set

    private var width = 1f
    private var height = 1f
    private var inset = 0f
    private var smallLabel = 4f

    /** A flight under way and where it lands; a fling under way. */
    private var flight: Job? = null
    private var landing: Placement? = null
    private var fling: Job? = null
    val isFlying get() = flight?.isActive == true
    val isFlinging get() = fling?.isActive == true

    /** The painting's size, the margin kept round it, and its small numbers' size (canvas units). */
    fun setPainting(width: Float, height: Float, inset: Float, smallLabelSize: Float) {
        this.width = width
        this.height = height
        this.inset = inset
        smallLabel = smallLabelSize.coerceAtLeast(0.25f)
    }

    /** The whole painting's scale. */
    val fit get() = fitScale(viewport, width, height, inset)

    /** In until a small number is 15 px tall at [density], at least 4× the fit and at most 40×. */
    fun deepest(density: Float) = min(max(15 * density / smallLabel, fit * 4), fit * 40)

    /** How many doublings in from the fit (0 fitted). */
    val depth get() = (Math.log((scale / fit).toDouble()) / Math.log(2.0)).toFloat().coerceAtLeast(0f)

    /** Takes the viewport's size; the first time, places the camera with [initial]. */
    fun resize(size: IntSize, initial: () -> (IntSize) -> Placement) {
        if (size == viewport && placed) return
        viewport = size
        if (!placed) {
            placed = true
            rest = initial()
        }
        val r = rest
        if (r != null) place(r(size)) else place(Placement(scale, clamped(scale, offset)))
    }

    private fun place(p: Placement) {
        scale = p.scale
        offset = clamped(p.scale, p.offset)
    }

    /** [o] held so the painting covers the view or sits centered in it, `inset` to spare. */
    fun clamped(s: Float, o: Offset): Offset {
        fun axis(o: Float, content: Float, view: Int): Float {
            val room = view - 2 * inset
            return if (content <= room) (view - content) / 2 else o.coerceIn(view - inset - content, inset)
        }
        return Offset(axis(o.x, width * s, viewport.width), axis(o.y, height * s, viewport.height))
    }

    /** The painter moved the canvas: whatever flight or glide was going stops. */
    fun moved() {
        rest = null
        moves++
        flight?.cancel()
        fling?.cancel()
    }

    /** A pinch and pan: zoom by [zoom] about [centroid], then move by [pan], within the limits. */
    fun transform(centroid: Offset, pan: Offset, zoom: Float, maxScale: Float) {
        val s = (scale * zoom).coerceIn(fit, max(fit, maxScale))
        val k = s / scale
        offset = clamped(s, centroid - (centroid - offset) * k + pan)
        scale = s
    }

    /** Glides on from a pan let go at [velocity] (px/s), slowing as a scroll view does. */
    fun fling(scope: CoroutineScope, velocity: Offset) {
        fling?.cancel()
        if (velocity.getDistance() < 50f) return
        fling = scope.launch {
            var last = Offset.Zero
            AnimationState(Offset.VectorConverter, Offset.Zero, velocity).animateDecay(exponentialDecay(frictionMultiplier = 1.6f)) {
                val step = value - last
                last = value
                val before = offset
                offset = clamped(scale, offset + step)
                // Against an edge on both axes: nothing left to glide.
                if (offset == before) cancelAnimation()
            }
        }
    }

    /** Stops a glide; true if one was going (a tap that stops a fling paints nothing). */
    fun stopFling(): Boolean {
        val going = isFlinging
        fling?.cancel()
        return going
    }

    /** Ends a flight where it was going, at once: a tap during one lands it and paints. */
    fun land() {
        val target = landing ?: return
        flight?.cancel()
        place(target)
    }

    fun toCanvas(screen: Offset) = (screen - offset) / scale

    /** Where the camera looks now, to save with the painting. */
    fun view(): SavedView {
        val center = toCanvas(Offset(viewport.width / 2f, viewport.height / 2f))
        return SavedView(center.x, center.y, scale / fit)
    }

    /**
     * Eases to [target] over [seconds] (out-cubic), or cuts there under Reduce Motion
     * ([still]). [target] is asked each frame, so a flight to the whole painting follows the
     * view as it changes size on the way (the Finished bar coming in). Cancelled by the
     * painter's touch, or by [land].
     */
    suspend fun flyTo(seconds: Float, still: Boolean = false, target: () -> Placement) = coroutineScope {
        flight?.cancel()
        fun goal() = target().let { Placement(it.scale, clamped(it.scale, it.offset)) }
        landing = goal()
        if (still) {
            place(goal())
            return@coroutineScope
        }
        val fromScale = scale
        val fromOffset = offset
        val job = launch {
            val start = withFrameNanos { it }
            while (true) {
                val t = withFrameNanos { ((it - start) / 1e9f / seconds).coerceIn(0f, 1f) }
                val to = goal()
                landing = to
                val e = 1 - (1 - t).pow(3)
                val s = fromScale * (to.scale / fromScale).pow(e)
                offset = if (abs(to.scale - fromScale) < fromScale * 1e-4f) fromOffset + (to.offset - fromOffset) * e
                else {
                    // About the fixed point of the start-to-end similarity: one continuous motion.
                    val c = (to.offset - fromOffset) / (to.scale - fromScale)
                    val f = c * fromScale - fromOffset
                    c * s - f
                }
                scale = s
                if (t >= 1f) break
            }
        }
        flight = job
        try {
            job.join()
        } catch (e: CancellationException) {
            job.cancel()
            throw e
        } finally {
            if (flight === job) landing = null
        }
    }

    /** Flies to [placement] and rests there, following the viewport, until the painter moves. */
    suspend fun settle(placement: (IntSize) -> Placement, seconds: Float, still: Boolean = false) {
        val before = moves
        flyTo(seconds, still) { placement(viewport) }
        if (moves == before) {
            rest = placement
            place(placement(viewport))
        }
    }

    /** The painter's flight to [target], [seconds] long. */
    suspend fun travel(target: Placement, seconds: Float, still: Boolean) {
        moved()
        flyTo(seconds, still) { target }
    }

    /**
     * iOS's double tap: in 2.5× about [at] (to at least 2.5× the fit), or back out to the whole
     * painting when already near the deepest zoom.
     */
    fun stepTarget(at: Offset, maxScale: Float): Placement? {
        if (scale > maxScale * 0.8f) return null
        val s = max(scale * 2.5f, fit * 2.5f).coerceIn(fit, max(fit, maxScale))
        val c = toCanvas(at)
        return Placement(s, at - c * s)
    }

    /**
     * iOS's hint: zooms until the number, [labelSize] canvas units, reads at [legiblePx], never
     * past an area filling 45% of the view, always a little in (to at least twice the fit) so the
     * eye follows, and centers ([x], [y]).
     */
    fun revealTarget(x: Float, y: Float, extent: Float, labelSize: Float, legiblePx: Float, maxScale: Float): Placement {
        val legible = legiblePx / labelSize.coerceAtLeast(0.5f)
        val roomy = 0.45f * min(viewport.width, viewport.height) / extent.coerceAtLeast(1f)
        val s = max(scale, min(roomy, max(legible, fit * 2))).coerceIn(fit, max(fit, maxScale))
        return centered(viewport, x, y, s)
    }
}
