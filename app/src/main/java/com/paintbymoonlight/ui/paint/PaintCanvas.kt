package com.paintbymoonlight.ui.paint

import android.graphics.Paint as AndroidPaint
import android.graphics.RenderNode
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import com.paintbymoonlight.data.SavedView
import com.paintbymoonlight.ui.theme.Moonlight
import kotlin.math.max
import kotlin.math.min

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

private fun centered(size: IntSize, x: Float, y: Float, scale: Float) =
    Placement(scale, Offset(size.width / 2f - x * scale, size.height / 2f - y * scale))

/**
 * Where the canvas looks: a point of the painting is drawn at `point * scale + offset` on
 * screen. Until the painter moves it, the camera rests at a placement that follows the
 * viewport as it changes size (the Finished bar coming in, a rotation, a shared transition).
 * [flyTo] moves it on a spring, as the iOS canvas's flights do.
 */
@Stable
class Camera {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
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

    /** Takes the viewport's size; the first time, places the camera with [initial]. */
    fun resize(size: IntSize, initial: () -> (IntSize) -> Placement) {
        if (size == viewport && placed) return
        viewport = size
        if (!placed) {
            placed = true
            rest = initial()
        }
        rest?.let { place(it(size)) }
    }

    private fun place(p: Placement) {
        scale = p.scale
        offset = p.offset
    }

    /** The painter moved the canvas. */
    fun moved() {
        rest = null
        moves++
    }

    fun toCanvas(screen: Offset) = (screen - offset) / scale

    /** Where the camera looks now, to save with the painting. */
    fun view(width: Float, height: Float, inset: Float): SavedView {
        val center = toCanvas(Offset(viewport.width / 2f, viewport.height / 2f))
        return SavedView(center.x, center.y, scale / fitScale(viewport, width, height, inset))
    }

    suspend fun flyTo(target: Placement, stiffness: Float = Spring.StiffnessLow) {
        val fromScale = scale
        val fromOffset = offset
        animate(0f, 1f, animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = stiffness)) { t, _ ->
            // Interpolate the scale geometrically, so a zoom feels even.
            scale = fromScale * Math.pow((target.scale / fromScale).toDouble(), t.toDouble()).toFloat()
            offset = fromOffset + (target.offset - fromOffset) * t
        }
    }

    /** Flies to [placement] and rests there, following the viewport, until the painter moves. */
    suspend fun settle(placement: (IntSize) -> Placement) {
        val before = moves
        flyTo(placement(viewport))
        if (moves == before) {
            rest = placement
            place(placement(viewport))
        }
    }

    /**
     * Flies to center a canvas rectangle so it spans about a third of the view's short side,
     * between [minScale] and [maxScale].
     */
    suspend fun flyToArea(left: Float, top: Float, right: Float, bottom: Float, minScale: Float, maxScale: Float) {
        moved()
        val side = max(max(right - left, bottom - top), 1f)
        val s = (min(viewport.width, viewport.height) / (3 * side)).coerceIn(minScale, max(minScale, maxScale))
        flyTo(centered(viewport, (left + right) / 2, (top + bottom) / 2, s))
    }
}

/** The area Hint just flew to, and how strongly it shows (0…1), for the canvas to mark it. */
@Stable
class HintPulse {
    var region by mutableStateOf<Int?>(null)
    val strength = Animatable(0f)
}

/** The fills' render node was last recorded for this key; a plain holder, since drawing reads and writes it. */
private class Recorded(var key: Long? = null)

/**
 * The painting: the sheet, its painted areas, the areas of the paint in hand tinted, its
 * lines and its numbers. Fills are recorded into a RenderNode replayed at any zoom, so a frame
 * costs one replay, the lines and the numbers in view; a new fill is revealed from the tap in
 * a growing circle before it joins the node. [placement] says where the camera starts.
 */
@Composable
fun PaintCanvas(
    session: PaintSession,
    camera: Camera,
    placement: () -> (IntSize) -> Placement,
    minScale: (IntSize) -> Float,
    onTap: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    pulse: HintPulse? = null,
    showNumbers: Boolean = true,
) {
    val scene = session.scene
    val density = LocalDensity.current.density
    val node = remember { RenderNode("fills").apply { setPosition(0, 0, scene.width.toInt() + 1, scene.height.toInt() + 1) } }
    // Starts empty, so the first frame records (a finished painting holds no paint: its key is -1).
    val recorded = remember { Recorded() }
    var revealing by remember { mutableStateOf<Int?>(null) }
    var revealFrom by remember { mutableStateOf(Offset.Zero) }
    val reveal = remember { Animatable(1f) }
    var lastTap by remember { mutableStateOf(Offset.Zero) }
    val pulseColor = MaterialTheme.colorScheme.primary.toArgb()

    val fill = remember { AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { style = AndroidPaint.Style.FILL } }
    val line = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.STROKE
            strokeJoin = AndroidPaint.Join.ROUND
            strokeCap = AndroidPaint.Cap.ROUND
            color = Moonlight.sheetInk.toArgb()
        }
    }
    val outline = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.STROKE
            strokeJoin = AndroidPaint.Join.ROUND
        }
    }
    val number = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            textAlign = AndroidPaint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
    }

    // A new fill: reveal it from where the painter tapped. An undo cancels the reveal midway,
    // so the circle is cleared however it ends.
    LaunchedEffect(session.changes) {
        val fill = session.lastFill
        if (fill != null && session.progress.isPainted(fill)) {
            try {
                revealing = fill
                revealFrom = lastTap
                reveal.snapTo(0f)
                reveal.animateTo(1f, tween(durationMillis = 320))
            } finally {
                revealing = null
            }
        }
    }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { camera.resize(it, placement) }
            .semantics { contentDescription = "Painting canvas" }
            .pointerInput(scene) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    camera.moved()
                    val least = minScale(camera.viewport)
                    val newScale = (camera.scale * zoom).coerceIn(least * 0.8f, least * 40f)
                    val k = newScale / camera.scale
                    camera.offset = centroid - (centroid - camera.offset) * k + pan
                    camera.scale = newScale
                }
            }
            .pointerInput(scene) {
                detectTapGestures { position ->
                    lastTap = camera.toCanvas(position)
                    onTap(position)
                }
            }
    ) {
        val key = session.changes.toLong() * 4096 + session.selected + if (revealing != null) 1L shl 40 else 0L
        if (recorded.key != key) {
            recordFills(node, session, revealing, fill)
            recorded.key = key
        }
        val scale = camera.scale
        drawIntoCanvas { c ->
            val canvas = c.nativeCanvas
            canvas.save()
            canvas.translate(camera.offset.x, camera.offset.y)
            canvas.scale(scale, scale)
            canvas.drawRenderNode(node)

            // The fill being revealed, in a circle growing from the tap.
            revealing?.let { r ->
                val region = scene.regions[r]
                val b = region.bounds
                val reach = max(
                    max(kotlin.math.abs(revealFrom.x - b.left), kotlin.math.abs(revealFrom.x - b.right)),
                    max(kotlin.math.abs(revealFrom.y - b.top), kotlin.math.abs(revealFrom.y - b.bottom)),
                ) * 1.42f
                canvas.save()
                canvas.clipPath(region.path)
                fill.color = scene.paints[region.paint].color
                canvas.drawCircle(revealFrom.x, revealFrom.y, reach * reveal.value, fill)
                canvas.restore()
            }

            // Lines a constant width on screen.
            line.strokeWidth = 0.9f * density / scale
            canvas.drawPath(scene.lines, line)
            if (!scene.strokes.isEmpty) canvas.drawPath(scene.strokes, line)

            // The area Hint found: a brighter wash of its paint and a ring, beating.
            pulse?.region?.let { r ->
                val strength = pulse.strength.value
                if (strength > 0f && !session.progress.isPainted(r)) {
                    val region = scene.regions[r]
                    fill.color = (scene.paints[region.paint].color and 0x00FFFFFF) or ((0x90 * strength).toInt() shl 24)
                    canvas.drawPath(region.path, fill)
                    outline.color = (pulseColor and 0x00FFFFFF) or ((0xFF * strength).toInt() shl 24)
                    outline.strokeWidth = 3f * density / scale
                    canvas.drawPath(region.path, outline)
                }
            }

            if (showNumbers) {
                val left = -camera.offset.x / scale
                val top = -camera.offset.y / scale
                val right = left + size.width / scale
                val bottom = top + size.height / scale
                val minimum = 5.5f * density / scale
                for (label in scene.labels) {
                    if (label.fontSize < minimum) continue
                    if (label.x < left - label.fontSize || label.x > right + label.fontSize) continue
                    if (label.y < top - label.fontSize || label.y > bottom + label.fontSize) continue
                    if (session.progress.isPainted(label.region)) continue
                    val paint = scene.regions[label.region].paint
                    val held = paint == session.selected
                    number.textSize = label.fontSize
                    number.color = if (held) 0xFF2A2430.toInt() else 0xFF8E8A93.toInt()
                    number.isFakeBoldText = held
                    canvas.drawText((paint + 1).toString(), label.x, label.y + label.fontSize * 0.36f, number)
                }
            }
            canvas.restore()
        }
    }
}

/** Records the sheet, every painted area but [except], and the paint in hand's areas tinted. */
private fun recordFills(node: RenderNode, session: PaintSession, except: Int?, fill: AndroidPaint) {
    val scene = session.scene
    val canvas = node.beginRecording()
    try {
        fill.color = Moonlight.sheet.toArgb()
        canvas.drawRect(0f, 0f, scene.width, scene.height, fill)
        val held = session.selected
        scene.regions.forEachIndexed { i, region ->
            val painted = session.progress.isPainted(i) && i != except
            if (painted) {
                fill.color = scene.paints[region.paint].color
                canvas.drawPath(region.path, fill)
            } else if (region.paint == held) {
                // The areas waiting for the paint in hand, a light wash of it.
                fill.color = (scene.paints[region.paint].color and 0x00FFFFFF) or (0x40 shl 24)
                canvas.drawPath(region.path, fill)
            }
        }
    } finally {
        node.endRecording()
    }
}
