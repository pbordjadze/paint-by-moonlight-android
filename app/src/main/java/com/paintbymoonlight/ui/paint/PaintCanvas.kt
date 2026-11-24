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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.paintbymoonlight.ui.theme.Moonlight
import kotlin.math.max
import kotlin.math.min

/**
 * Where the canvas looks: a point of the painting is drawn at `point * scale + offset` on
 * screen. [flyTo] moves it on a spring, as the iOS canvas's flights do.
 */
@Stable
class Camera {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var viewport by mutableStateOf(IntSize.Zero)
    private var content = IntSize.Zero
    private var fitted = false

    /** The scale at which the whole painting fits, inset by [inset] px. */
    fun fitScale(width: Float, height: Float, inset: Float): Float =
        if (viewport.width == 0) 1f else min((viewport.width - 2 * inset) / width, (viewport.height - 2 * inset) / height)

    fun fitOffset(width: Float, height: Float, scale: Float) =
        Offset((viewport.width - width * scale) / 2, (viewport.height - height * scale) / 2)

    /** Fits the painting the first time the viewport is known, and keeps it fitted when the viewport changes before the painter moves it. */
    fun ensureFitted(width: Float, height: Float, inset: Float, size: IntSize) {
        if (size == viewport && fitted) return
        val keepFit = !fitted || content == viewport
        viewport = size
        if (keepFit) {
            scale = fitScale(width, height, inset)
            offset = fitOffset(width, height, scale)
            content = size
            fitted = true
        }
    }

    fun moved() { content = IntSize.Zero }

    fun toCanvas(screen: Offset) = (screen - offset) / scale

    suspend fun flyTo(targetScale: Float, targetOffset: Offset, stiffness: Float = Spring.StiffnessLow) {
        val fromScale = scale
        val fromOffset = offset
        animate(0f, 1f, animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = stiffness)) { t, _ ->
            // Interpolate the scale geometrically, so a zoom feels even.
            scale = fromScale * Math.pow((targetScale / fromScale).toDouble(), t.toDouble()).toFloat()
            offset = fromOffset + (targetOffset - fromOffset) * t
        }
    }

    /** Flies to center a canvas rectangle, at least at [minScale], so it fills about a third of the view. */
    suspend fun flyToArea(left: Float, top: Float, right: Float, bottom: Float, minScale: Float, maxScale: Float) {
        moved()
        val w = max(right - left, 1f)
        val h = max(bottom - top, 1f)
        val s = min(viewport.width / (3 * w), viewport.height / (3 * h)).coerceIn(max(minScale, scale.coerceAtMost(maxScale)), maxScale)
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        flyTo(s, Offset(viewport.width / 2f - cx * s, viewport.height / 2f - cy * s))
    }
}

/**
 * The painting: the sheet, its painted areas, the areas of the paint in hand tinted, its
 * lines and its numbers. Fills are recorded into a RenderNode replayed at any zoom, so a frame
 * costs one replay, the lines and the numbers in view; a new fill is revealed from the tap in
 * a growing circle before it joins the node.
 */
@Composable
fun PaintCanvas(
    session: PaintSession,
    camera: Camera,
    onTap: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    inset: Float = 0f,
    showNumbers: Boolean = true,
) {
    val scene = session.scene
    val density = LocalDensity.current.density
    val node = remember { RenderNode("fills").apply { setPosition(0, 0, scene.width.toInt() + 1, scene.height.toInt() + 1) } }
    var recordedFor by remember { mutableStateOf(-1L) }
    var revealing by remember { mutableStateOf<Int?>(null) }
    var revealFrom by remember { mutableStateOf(Offset.Zero) }
    val reveal = remember { Animatable(1f) }
    var lastTap by remember { mutableStateOf(Offset.Zero) }

    val fill = remember { AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { style = AndroidPaint.Style.FILL } }
    val line = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.STROKE
            strokeJoin = AndroidPaint.Join.ROUND
            strokeCap = AndroidPaint.Cap.ROUND
            color = Moonlight.sheetInk.toArgb()
        }
    }
    val number = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            textAlign = AndroidPaint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
    }

    // A new fill: reveal it from where the painter tapped.
    LaunchedEffect(session.changes) {
        val fill = session.lastFill
        if (fill != null && session.progress.isPainted(fill)) {
            revealing = fill
            revealFrom = lastTap
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(durationMillis = 320))
            revealing = null
        }
    }

    Canvas(
        modifier
            .onSizeChanged { camera.ensureFitted(scene.width, scene.height, inset, it) }
            .semantics { contentDescription = "Painting canvas" }
            .pointerInput(scene) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    camera.moved()
                    val fit = camera.fitScale(scene.width, scene.height, inset)
                    val newScale = (camera.scale * zoom).coerceIn(fit * 0.8f, fit * 40f)
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
        if (recordedFor != key) {
            recordFills(node, session, revealing, fill)
            recordedFor = key
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
