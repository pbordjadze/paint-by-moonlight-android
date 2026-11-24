package com.paintbymoonlight.ui.paint

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint as AndroidPaint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import com.paintbymoonlight.core.Edge
import com.paintbymoonlight.ui.theme.Moonlight
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * What moves on the canvas besides the camera, on iOS's canvas clock: fills spreading and
 * lifting, the pick of a paint gliding in, a number popping up, the hint's beat, a finished
 * color's gloss and the fill's sparkles. Drawing reads [frame], which ticks only while
 * something moves.
 */
@Stable
class CanvasMotion {
    var frame by mutableLongStateOf(0L)
        private set
    internal var wake by mutableIntStateOf(0)
        private set
    internal var activeUntil = 0L
        private set
    /** Under Reduce Motion: nothing spreads, glides, pops or shines. */
    var still = false
    /** Where a drag's brush is, in the view (px), while one paints. */
    internal var brush by mutableStateOf<Offset?>(null)

    /**
     * When something began: stamped with the first frame after it was asked for, on the frame
     * clock (so a paused test clock pauses it too), [delay] seconds on. Until stamped it reads
     * as just beginning.
     */
    internal class Moment(private val delay: Float) {
        var start: Long? = null
        fun since(frame: Long) = (start?.let { (frame - it) / 1e9f } ?: 0f) - delay
    }

    internal class Spread(val region: Int, val x: Float, val y: Float, val at: Moment, val seconds: Float, val radius: Float, val painted: Boolean) {
        /** The wet front's gradient, made once at its full reach and scaled as it grows. */
        var front: RadialGradient? = null
    }
    internal class Sparkle(val x: Float, val y: Float, val size: Float, val at: Moment, val star: Boolean)

    private val unstamped = ArrayList<Pair<Moment, Float>>()
    internal val spreads = ArrayList<Spread>()
    internal val sparkles = ArrayList<Sparkle>()
    internal var selectionAt: Moment? = null
    internal var bumpRegion = -1
    internal var bumpAt: Moment? = null
    internal var pulseRegion = -1
    internal var pulseAt: Moment? = null
    /** The paint a gloss sweeps (-1: the whole painting), or none. */
    internal var shinePaint: Int? = null
    internal var shineAt: Moment? = null
    internal var lastSparkles: Moment? = null

    /** Something begins [delay] seconds from now, and frames keep coming [lasts] seconds after that. */
    internal fun moment(delay: Float = 0f, lasts: Float): Moment {
        val m = Moment(delay)
        unstamped.add(m to delay + lasts)
        wake++
        return m
    }

    /** A frame at [t]: stamps what began since the last. */
    internal fun tick(t: Long) {
        for ((m, lasts) in unstamped) {
            m.start = t
            activeUntil = max(activeUntil, t + (lasts * 1e9f).toLong())
        }
        unstamped.clear()
        frame = t
    }

    internal fun since(m: Moment?) = m?.since(frame) ?: Float.MAX_VALUE

    /** Beats the hint on [region], starting [delay] seconds from now. */
    fun pulse(region: Int, delay: Float) {
        pulseRegion = region
        pulseAt = moment(if (still) 0f else delay, lasts = 2.1f)
    }

    /** Sweeps a gloss over [paint]'s areas (-1: all of them) after [delay] seconds. */
    fun shine(paint: Int, delay: Float) {
        if (still) return
        shinePaint = paint
        shineAt = moment(delay, lasts = CanvasLook.shineSeconds + 0.1f)
    }
}

/**
 * What drawing keeps between frames, remade only when the painting changes: the fills' render
 * node (every painted area, recorded once per fill or undo), the paint in hand's unpainted
 * cells, the classic lines, a gloss's areas; and per frame, without allocating, which spread
 * each area has. A plain holder, since drawing reads and writes it.
 */
private class Recorded(regionCount: Int) {
    val node = RenderNode("fills")
    var changes = -1
    var tintChanges = -1
    var tintHeld = -2
    // Even-odd, as each area's own path: an area's hole stays out unless its island is in too.
    val tint = Path().apply { fillType = Path.FillType.EVEN_ODD }
    var linesChanges = -1
    var linesHeld = -2
    var lines = FloatArray(0)
    var bold = FloatArray(0)
    /** A spreading area's own lines, which the classic lines leave out once it is painted. */
    val spreadLines = FloatArrayBuilder(256)
    var shineKey: Int? = null
    val shine = Path().apply { fillType = Path.FillType.EVEN_ODD }
    /** Each area's spread this frame (an index into the spreads), or -1. */
    val spreadOf = IntArray(regionCount) { -1 }
}

/** A drag that paints (iOS's long press, then drag): where it goes, in the view (px). */
interface CanvasStroke {
    /** The press has held: whether there is paint in hand to brush with. */
    fun begin(at: Offset): Boolean
    fun move(from: Offset, to: Offset)
    fun end()
}

/**
 * The painting, drawn as iOS's Metal canvas draws it: the warm sheet on the app's paper with a
 * soft shadow; its painted areas in their Display P3 paints; the paint in hand's unpainted
 * cells tinted with it and hatched with fine diagonal lines in screen space that glide in when
 * it is picked; the lines (a classic painting's dissolve between painted areas and ring the
 * paint in hand's cells boldly; a coloring book's drawing stays whole); and the numbers in the
 * ink, legible or not drawn. A fill spreads from the tap with a soft wet front, deepens and
 * settles; two gold sparkles pop beside it. Painted areas are recorded into a RenderNode
 * replayed at any zoom. [placement] says where the camera starts.
 */
@Composable
fun PaintCanvas(
    session: PaintSession,
    camera: Camera,
    motion: CanvasMotion,
    placement: () -> (IntSize) -> Placement,
    onTap: (Offset) -> Unit,
    stroke: CanvasStroke,
    darkBackdrop: Boolean,
    backdrop: Long,
    modifier: Modifier = Modifier,
    showNumbers: Boolean = true,
    /** Whether the fill's sparkles are stars: the Moon's, as its music turns. */
    stars: () -> Boolean = { false },
) {
    val scene = session.scene
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val paints = remember(scene) { scene.paints.map { Linear.of(it.p3) } }
    val recorded = remember(scene) {
        Recorded(scene.regions.size).apply { node.setPosition(0, 0, scene.width.toInt() + 1, scene.height.toInt() + 1) }
    }
    DisposableEffect(recorded) { onDispose { recorded.node.discardDisplayList() } }
    val numerals = remember(scene) { Array(scene.paints.size) { (it + 1).toString() } }
    val edgesOf = remember(scene) { edgesByRegion(scene) }
    val book = remember(scene) { if (scene.isColoringBook) bookLines(session) else null }
    val sparkle = remember { sparklePath() }
    val star = remember { starPath() }
    val hatchTile = remember(density) { hatchTile(density) }
    val local = remember { Matrix() }
    val strokeHeld = remember { BooleanArray(1) }
    // The gestures outlive a composition: they call whatever the latest one handed in.
    val tapNow by rememberUpdatedState(onTap)
    val strokeNow by rememberUpdatedState(stroke)

    val fill = remember { AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { style = AndroidPaint.Style.FILL } }
    val sheet = remember { AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { style = AndroidPaint.Style.FILL } }
    val hatch = remember(hatchTile) {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(hatchTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }
    val gloss = remember { AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS } }
    // The gloss's band, across the diagonal, 6 × 70 pt wide and centered on 0: moved into place each frame.
    val band = remember(density) {
        val stops = floatArrayOf(0f, 0.2f, 0.35f, 0.5f, 0.65f, 0.8f, 1f)
        val colors = IntArray(stops.size) {
            val x = (stops[it] - 0.5f) * 6f
            android.graphics.Color.argb(0.13f * exp(-x * x), 1f, 1f, 1f)
        }
        val reach = 3 * 70f * density * 0.70710678f
        LinearGradient(-reach, -reach, reach, reach, colors, stops, Shader.TileMode.CLAMP)
    }
    val line = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.STROKE
            strokeJoin = AndroidPaint.Join.ROUND
            strokeCap = AndroidPaint.Cap.ROUND
        }
    }
    // The canvas's digits are New York bold on iOS: the system serif, bold, with even figures.
    val number = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            textAlign = AndroidPaint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            fontFeatureSettings = "tnum"
        }
    }
    // The digits' middle, as a fraction of the size below the baseline (negative: above).
    val digitMiddle = remember(number) {
        val r = Rect()
        number.textSize = 100f
        number.getTextBounds("0123456789", 0, 10, r)
        -(r.top + r.bottom) / 200f
    }

    // Before the first layout, which places the camera within these.
    remember(scene, density) { camera.setPainting(scene.width, scene.height, 16 * density, scene.smallLabelSize); scene }

    // Frames tick only while something moves.
    LaunchedEffect(motion.wake) {
        while (true) {
            val t = withFrameNanos { it }
            motion.tick(t)
            if (t > motion.activeUntil) break
        }
    }

    // A new pick glides its hatch in.
    LaunchedEffect(session.selected) {
        motion.selectionAt = if (motion.still) null else motion.moment(lasts = 2.5f)
    }

    DisposableEffect(session, motion) {
        val stop = session.listen { event ->
            when (event) {
                is PaintEvent.Painted -> {
                    val region = scene.regions[event.region]
                    val b = region.bounds
                    val radius = max(
                        1f,
                        maxOf(hypot(event.x - b.left, event.y - b.top), hypot(event.x - b.right, event.y - b.top),
                            hypot(event.x - b.left, event.y - b.bottom), hypot(event.x - b.right, event.y - b.bottom)),
                    )
                    val seconds = CanvasLook.fillSeconds(radius * camera.scale / density) *
                        if (event.finishedPainting) CanvasLook.lastFillSlowdown else 1f
                    motion.spreads.removeAll { it.region == event.region }
                    if (!motion.still) {
                        val at = motion.moment(lasts = seconds + CanvasLook.wetSettle + 0.1f)
                        motion.spreads.add(CanvasMotion.Spread(event.region, event.x, event.y, at, seconds, radius, true))
                        if (!event.finishedPainting) popSparkles(motion, scene, camera, density, event.region, seconds, stars())
                    }
                    if (event.finishedPaint && !event.finishedPainting) {
                        motion.shine(region.paint, seconds + CanvasLook.shineDelay)
                    }
                }
                is PaintEvent.Undone -> {
                    motion.spreads.removeAll { it.region == event.region }
                    if (!motion.still) {
                        val at = motion.moment(lasts = CanvasLook.unpaintSeconds + 0.05f)
                        motion.spreads.add(CanvasMotion.Spread(event.region, 0f, 0f, at, CanvasLook.unpaintSeconds, 0f, false))
                    }
                    motion.shinePaint = null
                }
                is PaintEvent.Rejected -> {
                    motion.bumpRegion = event.region
                    motion.bumpAt = motion.moment(lasts = CanvasLook.bumpSeconds + 0.1f)
                }
            }
        }
        onDispose { stop() }
    }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { camera.resize(it, placement) }
            .semantics { contentDescription = "Painting canvas" }
            .pointerInput(scene) {
                // Pinch and pan, held to the camera's limits, gliding on when let go; or a press
                // held still (0.28 s within 12 pt, as iOS's) that then paints as it drags.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    tracker.addPointerInputChange(down)
                    val holdBy = down.uptimeMillis + 280
                    val stillWithin = 12 * density
                    var zoom = 1f
                    var pan = Offset.Zero
                    var moving = false
                    var several = false
                    var strayed = false
                    var brush: Offset? = null
                    var stroked = false
                    try {
                        while (true) {
                            val waiting = !moving && !several && !strayed && brush == null
                            val event: PointerEvent? = if (waiting) {
                                withTimeoutOrNull((holdBy - currentEvent.changes.first().uptimeMillis).coerceAtLeast(0)) { awaitPointerEvent() }
                            } else awaitPointerEvent()
                            if (event == null) {
                                // Held: a stroke, if there is paint in hand.
                                if (strokeNow.begin(down.position)) {
                                    brush = down.position
                                    strokeHeld[0] = true
                                    motion.brush = down.position
                                } else strayed = true
                                continue
                            }
                            if (event.changes.any { it.isConsumed }) break
                            if (event.changes.count { it.pressed } > 1) several = true
                            val finger = event.changes.firstOrNull { it.id == down.id }
                            if (brush != null) {
                                if (finger == null || !finger.pressed || several) break
                                if (finger.positionChanged()) {
                                    strokeNow.move(brush, finger.position)
                                    brush = finger.position
                                    motion.brush = brush
                                    finger.consume()
                                }
                                continue
                            }
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (!moving) {
                                zoom *= zoomChange
                                pan += panChange
                                if (finger != null && (finger.position - down.position).getDistance() > stillWithin) strayed = true
                                val zoomMotion = abs(1 - zoom) * event.calculateCentroidSize(useCurrent = false)
                                if (zoomMotion > viewConfiguration.touchSlop || pan.getDistance() > viewConfiguration.touchSlop) {
                                    moving = true
                                    camera.moved()
                                }
                            }
                            if (moving) {
                                if (zoomChange != 1f || panChange != Offset.Zero) {
                                    camera.transform(event.calculateCentroid(useCurrent = false), panChange, zoomChange, camera.deepest(density))
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            finger?.let { tracker.addPointerInputChange(it) }
                            if (event.changes.none { it.pressed }) break
                        }
                    } finally {
                        // Lifted, or the gesture cancelled (the screen leaving, another taking the touch): the stroke ends either way.
                        if (brush != null) {
                            stroked = true
                            motion.brush = null
                            strokeNow.end()
                        }
                    }
                    if (!stroked && moving && !several) {
                        // A pinch doesn't fling: its last finger lifting would throw the painting.
                        camera.fling(scope, tracker.calculateVelocity().let { Offset(it.x, it.y) })
                    }
                }
            }
            .pointerInput(scene) {
                var stoppedFling = false
                detectTapGestures(
                    onPress = {
                        stoppedFling = camera.stopFling()
                        strokeHeld[0] = false
                    },
                    // A tap that stops a glide only stops it; one during a flight lands it and
                    // paints; a press that became a stroke has painted already.
                    onTap = { position ->
                        if (!stoppedFling && !strokeHeld[0]) {
                            camera.land()
                            tapNow(position)
                        }
                    },
                )
            }
    ) {
        motion.spreads.removeAll { motion.since(it.at) > it.seconds + if (it.painted) CanvasLook.wetSettle else 0f }
        val scale = camera.scale
        val depth = camera.depth
        val spreads = motion.spreads
        val spreadOf = recorded.spreadOf
        for (i in spreads.indices) spreadOf[spreads[i].region] = i
        // Whether paint is still spreading over area r (it shows the spread, not the fill).
        fun spreading(r: Int): Boolean {
            val i = spreadOf[r]
            return i >= 0 && spreads[i].painted && motion.since(spreads[i].at) < spreads[i].seconds
        }
        if (recorded.changes != session.changes) {
            recordFills(recorded.node, session, paints, fill)
            recorded.changes = session.changes
        }
        val held = session.selected.takeIf { it in scene.paints.indices && !session.isComplete }

        drawIntoCanvas { c ->
            val canvas = c.nativeCanvas
            canvas.drawColor(backdrop)
            canvas.save()
            canvas.translate(camera.offset.x, camera.offset.y)
            canvas.scale(scale, scale)
            val px = 1f / scale

            // The sheet, lifted off the paper by a soft shadow and a tight contact one.
            val shadow = CanvasLook.shadowOpacity(darkBackdrop)
            sheet.setColor(CanvasLook.paper.pack())
            sheet.setShadowLayer(11 * density * px, 0f, 4 * density * px, android.graphics.Color.argb(shadow * 0.6f, 0f, 0f, 0f))
            canvas.drawRect(0f, 0f, scene.width, scene.height, sheet)
            sheet.setShadowLayer(1.5f * density * px, 0f, 0.75f * density * px, android.graphics.Color.argb(shadow * 0.4f, 0f, 0f, 0f))
            canvas.drawRect(0f, 0f, scene.width, scene.height, sheet)
            sheet.clearShadowLayer()

            // The paint in hand's cells: a tint of it, hatched in screen space.
            if (held != null) {
                if (recorded.tintChanges != session.changes || recorded.tintHeld != held) {
                    recorded.tint.rewind()
                    for (r in scene.regionsByPaint[held]) {
                        if (!session.progress.isPainted(r)) recorded.tint.addPath(scene.regions[r].path)
                    }
                    recorded.tintChanges = session.changes
                    recorded.tintHeld = held
                }
                val since = motion.selectionAt?.let { motion.since(it).coerceAtLeast(0f) } ?: 10f
                val paint = paints[held]
                fill.setColor(CanvasLook.tint(paint, since).pack())
                val tileSide = CanvasLook.hatchPeriod * 1.4142135f * density
                val phase = CanvasLook.hatchPhase(since) * 0.70710678f * density
                local.setScale(tileSide / hatchTile.width, tileSide / hatchTile.height)
                local.postTranslate(-phase, -phase)
                local.postTranslate(-camera.offset.x, -camera.offset.y)
                local.postScale(px, px)
                hatch.shader.setLocalMatrix(local)
                hatch.setColor(CanvasLook.hatch(paint, since).pack())
                canvas.drawPath(recorded.tint, fill)
                canvas.drawPath(recorded.tint, hatch)
                // Its cells paint is spreading over still show the tint until the front passes.
                for (sp in spreads) {
                    if (!spreading(sp.region) || scene.regions[sp.region].paint != held) continue
                    canvas.drawPath(scene.regions[sp.region].path, fill)
                    canvas.drawPath(scene.regions[sp.region].path, hatch)
                }
            }

            // The painted areas, but those paint is still spreading over, which draw below.
            canvas.save()
            for (sp in spreads) if (spreading(sp.region)) canvas.clipOutPath(scene.regions[sp.region].path)
            canvas.drawRenderNode(recorded.node)
            canvas.restore()

            // Paint spreading from the tap (a soft front with a bright wet rim), paint lifting
            // off after an undo, and fresh paint a little deeper until it settles.
            for (s in motion.spreads) {
                val region = scene.regions[s.region]
                val color = paints[region.paint]
                val t = motion.since(s.at) / s.seconds
                if (!s.painted) {
                    if (t >= 1f) continue
                    fill.setColor(color.pack(1 - smoothstep(0f, 1f, t)))
                    canvas.drawPath(region.path, fill)
                    continue
                }
                if (t >= 1f) {
                    val settle = ((motion.since(s.at) - s.seconds) / CanvasLook.wetSettle).coerceIn(0f, 1f)
                    val wet = (1 - settle) * (1 - settle)
                    if (wet <= 0f) continue
                    fill.setColor((color * (1 - 0.12f * wet)).pack())
                    canvas.drawPath(region.path, fill)
                    continue
                }
                val e = 1 - (1 - t.coerceIn(0f, 1f)).pow(3)
                val soft = max(s.radius * 0.1f, 2f * px)
                val reach = (s.radius + soft) * 1.12f
                val front = e * reach
                if (front <= 0f) continue
                // Made once, at its full reach (its soft rim a fixed share of it), and scaled.
                val gradient = s.front ?: run {
                    val wet = color * 0.88f
                    val wetColor = wet.pack()
                    val sheen = Linear(min(color.r * 1.3f + 0.07f, 1f), min(color.g * 1.3f + 0.07f, 1f), min(color.b * 1.3f + 0.07f, 1f))
                    val inner = ((reach - soft) / reach).coerceIn(0f, 1f)
                    val rimAt = ((reach - soft * 0.7f) / reach).coerceIn(inner, 1f)
                    RadialGradient(
                        0f, 0f, 1f,
                        longArrayOf(wetColor, wetColor, wet.mix(sheen, 0.4f).pack(), wet.pack(0f)),
                        floatArrayOf(0f, inner, rimAt, 1f),
                        Shader.TileMode.CLAMP,
                    ).also { s.front = it }
                }
                local.setScale(front, front)
                local.postTranslate(s.x, s.y)
                gradient.setLocalMatrix(local)
                canvas.save()
                canvas.clipPath(region.path)
                fill.shader = gradient
                canvas.drawCircle(s.x, s.y, front, fill)
                fill.shader = null
                canvas.restore()
            }

            // The hint's area, beating with the paint in hand.
            if (motion.pulseRegion in scene.regions.indices && !session.progress.isPainted(motion.pulseRegion)) {
                val p = CanvasLook.pulse(motion.since(motion.pulseAt), motion.still)
                if (p > 0f) {
                    fill.setColor(paints[scene.regions[motion.pulseRegion].paint].pack(0.6f * p))
                    canvas.drawPath(scene.regions[motion.pulseRegion].path, fill)
                }
            }

            // A finished color's gloss, or the whole painting's: a bright band sweeping across.
            motion.shinePaint?.let { shining ->
                val since = motion.since(motion.shineAt)
                if (since < 0f || since >= CanvasLook.shineSeconds) return@let
                if (recorded.shineKey != shining) {
                    recorded.shine.rewind()
                    if (shining < 0) recorded.shine.addRect(0f, 0f, scene.width, scene.height, Path.Direction.CW)
                    else scene.regionsByPaint[shining].forEach { recorded.shine.addPath(scene.regions[it].path) }
                    recorded.shineKey = shining
                }
                val span = (size.width + size.height) / density * 0.70710678f
                val head = (-160f + (span + 320f) * smoothstep(0f, CanvasLook.shineSeconds, since)) * density
                val d = 0.70710678f
                local.setTranslate(head * d - camera.offset.x, head * d - camera.offset.y)
                local.postScale(px, px)
                band.setLocalMatrix(local)
                gloss.shader = band
                canvas.drawPath(recorded.shine, gloss)
            }

            // The lines.
            if (book != null) {
                val widthPx = CanvasLook.bookWidth(depth) * density
                line.strokeWidth = max(widthPx, 1f) * px
                line.setColor(CanvasLook.ink.pack(min(1f, widthPx)))
                canvas.drawLines(book, line)
            } else {
                if (recorded.linesChanges != session.changes || recorded.linesHeld != (held ?: -1)) {
                    val (normal, bold) = classicLines(session, held)
                    recorded.lines = normal
                    recorded.bold = bold
                    recorded.linesChanges = session.changes
                    recorded.linesHeld = held ?: -1
                }
                val strength = CanvasLook.outlineOpacity * CanvasLook.classicStrength(depth)
                val widthPx = CanvasLook.classicWidth(depth) * density
                line.strokeWidth = max(widthPx, 1f) * px
                line.setColor(CanvasLook.ink.pack(strength * min(1f, widthPx)))
                canvas.drawLines(recorded.lines, line)
                // An area paint is spreading over keeps the lines its fill dissolves until it lands.
                val extra = recorded.spreadLines
                extra.clear()
                for (sp in spreads) {
                    if (!spreading(sp.region)) continue
                    for (e in edgesOf[sp.region]) {
                        val edge = scene.edges[e]
                        if (dissolved(session, edge.left) && dissolved(session, edge.right)) extra.polyline(edge.points)
                    }
                }
                if (extra.size > 0) canvas.drawLines(extra.array, 0, extra.size, line)
                val boldPx = (CanvasLook.classicWidth(depth) + CanvasLook.selectedExtraWidth) * density
                line.strokeWidth = max(boldPx, 1f) * px
                line.setColor(CanvasLook.ink.pack(min(1f, strength * 1.3f) * min(1f, boldPx)))
                canvas.drawLines(recorded.bold, line)
            }

            // The numbers: in the ink, half strong and the paint in hand's nearly whole, fading
            // in as they grow legible and no bigger than a comfortable size; one tapped with the
            // wrong paint pops up so it reads at any zoom.
            if (showNumbers) {
                val left = -camera.offset.x * px
                val top = -camera.offset.y * px
                val right = left + size.width * px
                val bottom = top + size.height * px
                val fadeFrom = CanvasLook.numberFadeFrom * density
                val fadeTo = CanvasLook.numberFadeTo * density
                val largest = CanvasLook.numberMax * density
                val bumpT = motion.since(motion.bumpAt)
                for (label in scene.labels) {
                    val region = label.region
                    var fontPx = label.fontSize * scale
                    var bump = 0f
                    if (region == motion.bumpRegion && bumpT >= 0f && bumpT < CanvasLook.bumpSeconds) {
                        val fade = 1 - smoothstep(0.8f, CanvasLook.bumpSeconds, bumpT)
                        bump = (if (motion.still) 1f else sin(min(bumpT / 0.16f, 1f) * 1.5707964f)) * fade
                    }
                    val legible = smoothstep(fadeFrom, fadeTo, fontPx)
                    if (legible <= 0f && bump <= 0f) continue
                    val reach = label.fontSize
                    if (label.x < left - reach || label.x > right + reach || label.y < top - reach || label.y > bottom + reach) continue
                    val coverage = paintCoverage(session, motion, spreadOf[region], region)
                    if (coverage >= 1f) continue
                    fontPx = min(fontPx, largest)
                    if (bump > 0f) fontPx += (max(fontPx * 1.3f, CanvasLook.numberBumped * density) - fontPx) * (if (motion.still) 1f else bump)
                    val paint = scene.regions[region].paint
                    val emphasis = if (paint == held) 1f else 0f
                    val opacity = if (bump > 0f) CanvasLook.selectedNumberOpacity
                    else CanvasLook.numberOpacity + (CanvasLook.selectedNumberOpacity - CanvasLook.numberOpacity) * emphasis
                    val alpha = max(legible, bump) * (1 - coverage) * opacity
                    if (alpha < 0.004f) continue
                    val textSize = fontPx * px
                    number.textSize = textSize
                    if (emphasis > 0f) {
                        number.style = AndroidPaint.Style.FILL_AND_STROKE
                        number.strokeWidth = textSize * 0.04f
                    } else {
                        number.style = AndroidPaint.Style.FILL
                    }
                    number.setColor(CanvasLook.ink.pack(alpha))
                    canvas.drawText(numerals[paint], label.x, label.y + textSize * digitMiddle, number)
                }
            }
            canvas.restore()
            for (sp in spreads) spreadOf[sp.region] = -1

            // The fill's two gold sparkles, in the view's space as iOS lays them.
            val gold = Moonlight.gold
            motion.sparkles.removeAll { motion.since(it.at) > 0.75f }
            for (s in motion.sparkles) {
                val t = motion.since(s.at) / 0.7f
                if (t < 0f || t > 1f) continue
                val grow = if (t < 0.35f) 0.2f + (1.15f - 0.2f) * t / 0.35f else 1.15f + (0.85f - 1.15f) * (t - 0.35f) / 0.65f
                val opacity = when {
                    t < 0.2f -> t / 0.2f
                    t < 0.55f -> 1f
                    else -> 1 - (t - 0.55f) / 0.45f
                }
                fill.setColor(android.graphics.Color.pack(gold.red, gold.green, gold.blue, opacity))
                val side = s.size * grow
                canvas.save()
                canvas.translate(s.x - side / 2, s.y - side / 2)
                canvas.scale(side, side)
                canvas.drawPath(if (s.star) star else sparkle, fill)
                canvas.restore()
            }

            // A drag's brush, as large as it paints: a disc tinted with the paint in hand, a
            // white rim, and a faint dark halo that keeps it seen on light paint.
            motion.brush?.let { at ->
                val pt = density
                val r = min(CanvasLook.brushRadius * pt, CanvasLook.maxBrushRadius * scale)
                val tint = held?.let { paints[it] } ?: CanvasLook.ink
                fill.setColor(tint.pack(0.28f))
                canvas.drawCircle(at.x, at.y, r, fill)
                line.setColor(android.graphics.Color.pack(0f, 0f, 0f, 0.25f))
                line.strokeWidth = 1.6f * pt
                canvas.drawCircle(at.x, at.y, r + 1.4f * pt, line)
                line.setColor(android.graphics.Color.pack(0.95f, 0.95f, 0.95f, 0.95f))
                line.strokeWidth = 1.8f * pt
                canvas.drawCircle(at.x, at.y, r, line)
            }
        }
    }
}

/** How much paint covers [region] now, 0…1: what fades its number. [spread] is its spread's index, or -1. */
private fun paintCoverage(session: PaintSession, motion: CanvasMotion, spread: Int, region: Int): Float {
    val painted = session.progress.isPainted(region)
    if (spread < 0) return if (painted) 1f else 0f
    val s = motion.spreads[spread]
    val t = (motion.since(s.at) / s.seconds).coerceIn(0f, 1f)
    return if (s.painted) smoothstep(0f, 1f, t) else 1 - smoothstep(0f, 1f, t)
}

private fun smoothstep(a: Float, b: Float, x: Float): Float {
    val k = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return k * k * (3 - 2 * k)
}

/** Records every painted area in its paint. */
private fun recordFills(node: RenderNode, session: PaintSession, paints: List<Linear>, fill: AndroidPaint) {
    val scene = session.scene
    val canvas = node.beginRecording()
    try {
        scene.regions.forEachIndexed { i, region ->
            if (session.progress.isPainted(i)) {
                fill.setColor(paints[region.paint].pack())
                canvas.drawPath(region.path, fill)
            }
        }
    } finally {
        node.endRecording()
    }
}

/** Appends [points]' polyline as segments for `drawLines`. */
private fun FloatArrayBuilder.polyline(points: FloatArray) {
    val n = points.size / 2
    for (i in 0 until n - 1) add(points[2 * i], points[2 * i + 1], points[2 * i + 2], points[2 * i + 3])
}

private class FloatArrayBuilder(capacity: Int) {
    var array = FloatArray(max(capacity, 16))
        private set
    var size = 0
        private set
    fun add(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (size + 4 > array.size) array = array.copyOf(array.size * 2)
        array[size] = x0; array[size + 1] = y0; array[size + 2] = x1; array[size + 3] = y1
        size += 4
    }
    fun clear() { size = 0 }
    fun build() = array.copyOf(size)
}

/** A coloring book's drawing: its drawn edges and the lines inside cells, all alike. */
private fun bookLines(session: PaintSession): FloatArray {
    val scene = session.scene
    val out = FloatArrayBuilder(scene.edges.sumOf { if (it.drawn) it.points.size * 2 else 0 })
    scene.edges.forEach { if (it.drawn) out.polyline(it.points) }
    scene.strokePoints.forEach { out.polyline(it) }
    return out.build()
}

/** Whether an edge's side lets it dissolve: a painted area, or the sheet's border. */
private fun dissolved(session: PaintSession, r: Int) = r == Edge.OUTSIDE || session.progress.isPainted(r)

/**
 * A classic painting's lines, as iOS's outline pass draws them: an edge between two painted
 * areas (or a painted one and the sheet's border) dissolves; one beside an unpainted cell of the
 * paint in hand is drawn bold.
 */
private fun classicLines(session: PaintSession, held: Int?): Pair<FloatArray, FloatArray> {
    val scene = session.scene
    fun painted(r: Int) = dissolved(session, r)
    fun selected(r: Int) = held != null && r != Edge.OUTSIDE && scene.regions[r].paint == held && !painted(r)
    val normal = FloatArrayBuilder(1024)
    val bold = FloatArrayBuilder(256)
    for (edge in scene.edges) {
        if (painted(edge.left) && painted(edge.right)) continue
        if (selected(edge.left) || selected(edge.right)) bold.polyline(edge.points) else normal.polyline(edge.points)
    }
    return normal.build() to bold.build()
}

/**
 * The hatch's tile: lines along x + y one hatch period apart (measured across), each
 * [CanvasLook.hatchLine] wide, a half-pixel edge; an alpha mask the paint colors.
 */
private fun hatchTile(density: Float): Bitmap {
    val sidePt = CanvasLook.hatchPeriod * 1.4142135f
    val n = max(8, (sidePt * density).toInt())
    val bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
    val pixels = ByteArray(n * n)
    val aa = 0.5f / density
    val half = CanvasLook.hatchLine / 2
    for (y in 0 until n) for (x in 0 until n) {
        val px = (x + 0.5f) * sidePt / n
        val py = (y + 0.5f) * sidePt / n
        val w = (px + py) * 0.70710678f
        val f = w / CanvasLook.hatchPeriod
        val d = abs(f - kotlin.math.floor(f) - 0.5f) * CanvasLook.hatchPeriod
        val a = 1 - smoothstep(half - aa, half + aa, d)
        pixels[y * n + x] = (a * 255 + 0.5f).toInt().toByte()
    }
    bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pixels))
    return bitmap
}

/** iOS's `Sparkle`: four arms pinched in to curves, in the unit square. */
private fun sparklePath(pinch: Float = 0.133f): Path {
    val k = 0.5f * pinch
    return Path().apply {
        moveTo(0.5f, 0f)
        quadTo(0.5f + k, 0.5f - k, 1f, 0.5f)
        quadTo(0.5f + k, 0.5f + k, 0.5f, 1f)
        quadTo(0.5f - k, 0.5f + k, 0f, 0.5f)
        quadTo(0.5f - k, 0.5f - k, 0.5f, 0f)
        close()
    }
}

/**
 * The Moon's star (iOS's `Sparkle.starPath`): the sparkle with shorter rays on its diagonals,
 * their tips rounded and their lengths a touch uneven, as if inked by hand.
 */
private fun starPath(): Path {
    val p = sparklePath()
    val half = 0.5f
    val base = half * 0.30f
    val tip = half * 0.045f
    listOf(0.68f, 0.60f, 0.65f, 0.58f).forEachIndexed { k, fraction ->
        val angle = (-Math.PI / 4 + Math.PI / 2 * k).toFloat()
        val length = half * fraction
        val c = cos(angle)
        val s = sin(angle)
        fun x(a: Float, b: Float) = 0.5f + a * c - b * s
        fun y(a: Float, b: Float) = 0.5f + a * s + b * c
        val middle = (length - tip) / 2
        val side = (base + tip) / 2
        p.moveTo(x(0f, -base), y(0f, -base))
        p.quadTo(x(middle, -side), y(middle, -side), x(length - tip, -tip), y(length - tip, -tip))
        p.quadTo(x(length + tip * 0.35f, 0f), y(length + tip * 0.35f, 0f), x(length - tip, tip), y(length - tip, tip))
        p.quadTo(x(middle, side), y(middle, side), x(0f, base), y(0f, base))
        p.close()
    }
    return p
}

/**
 * Two gold sparkles pop at the area's edge, on its number's free circle, as the paint lands; not
 * where the area is too small on screen for them to read, nor more than one pair in 0.3 s.
 */
private fun popSparkles(
    motion: CanvasMotion, scene: com.paintbymoonlight.core.Scene, camera: Camera, density: Float, region: Int, seconds: Float, star: Boolean,
) {
    val labelIndex = scene.labelOf[region]
    if (labelIndex < 0) return
    val label = scene.labels[labelIndex]
    val reach = label.radius * camera.scale
    if (reach < 10 * density || motion.since(motion.lastSparkles) < 0.3f) return
    motion.lastSparkles = motion.moment(lasts = 0f)
    val cx = label.x * camera.scale + camera.offset.x
    val cy = label.y * camera.scale + camera.offset.y
    val size = (reach * 0.45f).coerceIn(10 * density, 22 * density)
    listOf(-0.7f, 2.3f).forEachIndexed { k, angle ->
        val at = motion.moment(delay = seconds * 0.8f + k * 0.08f, lasts = 0.8f)
        motion.sparkles.add(CanvasMotion.Sparkle(cx + reach * cos(angle), cy + reach * sin(angle), size * if (k == 0) 1f else 0.7f, at, star))
    }
}

/** Each area's edges, by index into the scene's. */
private fun edgesByRegion(scene: com.paintbymoonlight.core.Scene): Array<IntArray> {
    val lists = Array(scene.regions.size) { ArrayList<Int>() }
    scene.edges.forEachIndexed { i, e ->
        lists[e.left].add(i)
        if (e.right != Edge.OUTSIDE) lists[e.right].add(i)
    }
    return Array(lists.size) { lists[it].toIntArray() }
}
