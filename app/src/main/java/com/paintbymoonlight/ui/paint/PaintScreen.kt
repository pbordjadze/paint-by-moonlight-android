package com.paintbymoonlight.ui.paint

import android.os.SystemClock
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.paintbymoonlight.ui.paintingBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.graphics.shapes.Morph
import com.paintbymoonlight.ui.theme.Moonlight
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import java.text.DateFormat
import java.util.Date
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.paintbymoonlight.data.Preferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The painting screen: the canvas under a slim top bar (Back, the title and how far along,
 * Hint, Zen, Undo, More) and the palette along the bottom. The canvas opens where the painter
 * left it, or filling the screen for a new painting; a double tap on it shows the whole
 * painting. When the last area is painted the ring bursts into gold, the Finished bar takes
 * the palette's place and the camera eases out to the whole painting above it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PaintScreen(
    model: PaintViewModel,
    preferences: Preferences,
    thumbnail: File,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onPrint: (() -> Unit)?,
) {
    when (val state = model.state) {
        PaintViewModel.State.Loading -> Opening(model.id, thumbnail)
        is PaintViewModel.State.Failed -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(state.message, style = MaterialTheme.typography.bodyLarge)
        }
        is PaintViewModel.State.Ready -> Painting(model, state, preferences, onBack, onShare, onPrint)
    }
}

/**
 * While the painting opens: its gallery picture where the canvas will be, so the shared
 * transition has somewhere to grow to. A loading indicator only if opening takes a while.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Opening(id: String, thumbnail: File) {
    val image by produceState<ImageBitmap?>(null, thumbnail) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(thumbnail.path)?.asImageBitmap() }
    }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(600); slow = true }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).statusBarsPadding().navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        image?.let {
            Image(
                it, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.padding(24.dp).then(paintingBounds(id, RectangleShape)),
            )
        }
        if (slow) LoadingIndicator()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Painting(
    model: PaintViewModel,
    state: PaintViewModel.State.Ready,
    preferences: Preferences,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onPrint: (() -> Unit)?,
) {
    val session = state.session
    val camera = remember { Camera() }
    val motion = remember { CanvasMotion() }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val haptics = remember(view) { Haptics(view) }
    val density = LocalDensity.current.density
    val still = rememberReduceMotion()
    motion.still = still
    var shake by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var finishedHere by remember { mutableStateOf(false) }
    var finishedAt by remember { mutableStateOf<Long?>(null) }
    var zenNote by remember { mutableIntStateOf(0) }
    // A tap that painted nothing, so a second one close by and soon is a double tap (iOS's
    // zoom step). A tap that paints is never held back waiting to see whether a second follows.
    val lastMiss = remember { arrayOfNulls<Pair<Long, Offset>>(1) }
    val flightJob = remember { arrayOfNulls<Job>(1) }
    // iOS keeps the painting 16 pt clear of the view's edges.
    val inset = 16 * density
    val scene = state.session.scene
    val complete = session.let { it.changes; it.isComplete }
    val paper = MaterialTheme.colorScheme.background
    val darkBackdrop = paper.luminance() < 0.5f

    WideColor()

    // Save on leaving the app; leaving the screen saves as the model is cleared.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.saveNow() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Where the painter left the canvas is saved with the painting, once it has been still a
    // moment (after a flight too). Automatic placements (fits, transitions, the finish) are not
    // the painter's and save nothing.
    LaunchedEffect(camera) {
        snapshotFlow { camera.moves }.drop(1).collectLatest {
            delay(900)
            if (camera.viewport.width > 0) {
                session.progress.view = camera.view()
                model.viewChanged()
            }
        }
    }

    /** A flight of the painter's: a new one takes over from the one going. */
    fun fly(block: suspend () -> Unit) {
        flightJob[0]?.cancel()
        flightJob[0] = scope.launch { block() }
    }

    /**
     * Hint, and Zen Mode's glide: to the unpainted area of the paint in hand nearest [nearX],
     * [nearY] (the middle of the view when null), zoomed until its number reads, beating as the
     * flight lands (iOS's `reveal`).
     */
    fun showNext(near: Offset? = null) {
        val at = near ?: camera.toCanvas(Offset(camera.viewport.width / 2f, camera.viewport.height / 2f))
        val next = session.nextArea(nearX = at.x, nearY = at.y) ?: return
        val region = scene.regions[next]
        val label = scene.labelOf[next].takeIf { it >= 0 }?.let { scene.labels[it] }
        val b = region.bounds
        val target = camera.revealTarget(
            x = label?.x ?: b.centerX(), y = label?.y ?: b.centerY(),
            extent = max(b.width(), b.height()), labelSize = label?.fontSize ?: 8f,
            legiblePx = 17 * density, maxScale = camera.deepest(density),
        )
        motion.pulse(next, delay = 0.4f)
        fly { camera.travel(target, 0.55f, still) }
    }

    fun flyToFit() {
        flightJob[0]?.cancel()
        flightJob[0] = scope.launch { camera.settle(fitting(scene.width, scene.height, inset), 0.5f, still) }
    }

    fun undo() {
        if (session.undo() != null) {
            model.changed()
            haptics.tick()
        }
    }

    /**
     * The finish: once the last fill has landed the camera rests a moment, then eases out to the
     * whole painting, which shines as it lands; already showing about all of it, the camera stays
     * and the shine sweeps once the fill has landed. Under Reduce Motion it cuts, and nothing shines.
     */
    fun finish(fillSeconds: Float) {
        flightJob[0]?.cancel()
        flightJob[0] = scope.launch {
            val fitted = abs(camera.scale / camera.fit - 1) <= 0.05f
            delay(((fillSeconds + if (fitted) 0f else 0.35f) * 1000).toLong())
            if (!session.isComplete) return@launch
            if (fitted) {
                motion.shine(-1, 0f)
                return@launch
            }
            motion.shine(-1, if (still) 0f else 0.8f)
            camera.settle(fitting(scene.width, scene.height, inset), 0.8f, still)
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = state.meta.title,
                fraction = session.let { it.changes; it.fraction },
                complete = complete,
                celebrate = finishedHere,
                finishedOn = state.meta.finished ?: finishedAt,
                zen = preferences.zenMode,
                canUndo = session.let { it.changes; it.canUndo },
                onBack = { model.saveNow(); onBack() },
                onHint = { showNext() },
                onZen = {
                    preferences.zenMode = !preferences.zenMode
                    if (preferences.zenMode) zenNote++
                },
                onUndo = ::undo,
                onMore = { menu = true },
            )
            Box(Modifier.weight(1f).fillMaxWidth().then(paintingBounds(model.id, RectangleShape))) {
                PaintCanvas(
                    session = session,
                    camera = camera,
                    motion = motion,
                    placement = {
                        val saved = session.progress.view
                        when {
                            session.isComplete -> fitting(scene.width, scene.height, inset)
                            saved != null -> restoring(saved, scene.width, scene.height, inset)
                            else -> filling(scene.width, scene.height, inset)
                        }
                    },
                    darkBackdrop = darkBackdrop,
                    backdrop = android.graphics.Color.pack(paper.toArgb()),
                    modifier = Modifier.fillMaxSize(),
                    onTap = { screen ->
                        val p = camera.toCanvas(screen)
                        val now = SystemClock.uptimeMillis()
                        // A fingertip's reach (14 pt) finds a small area of the paint in hand just beside the tap.
                        val tap = session.tap(p.x, p.y, tolerance = 14 * density / camera.scale)
                        when (tap) {
                            is Tap.Painted -> {
                                lastMiss[0] = null
                                model.changed()
                                val area = scene.regions[tap.region].area
                                val size = min(1f, kotlin.math.sqrt(area / (scene.width * scene.height)) * 6)
                                when {
                                    tap.finishedPainting -> {
                                        haptics.celebrate()
                                        finishedHere = true
                                        finishedAt = System.currentTimeMillis()
                                        finish(fillSeconds = 0.9f)
                                    }
                                    tap.finishedPaint -> haptics.colorComplete()
                                    else -> haptics.paint(size)
                                }
                                if (!tap.finishedPainting && preferences.zenMode) {
                                    // Zen Mode glides on once the fill shows; after a finished
                                    // color's shine, which a flight leaving at once would hide.
                                    val changes = session.changes
                                    scope.launch {
                                        delay(if (tap.finishedPaint && !still) 1300 else 350)
                                        if (session.changes == changes && !session.isComplete) showNext(near = p)
                                    }
                                }
                            }
                            else -> {
                                val miss = lastMiss[0]
                                if (miss != null && now - miss.first <= 300 && (screen - miss.second).getDistance() <= 40 * density) {
                                    lastMiss[0] = null
                                    val step = camera.stepTarget(screen, camera.deepest(density))
                                    if (step == null) flyToFit() else fly { camera.travel(step, 0.42f, still) }
                                } else {
                                    lastMiss[0] = now to screen
                                    if (tap is Tap.OtherPaint) {
                                        shake = tap.paint to ((shake?.second ?: 0) + 1)
                                        haptics.reject()
                                    }
                                }
                            }
                        }
                    },
                )
                ZenNote(zenNote, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.align(Alignment.TopEnd)) {
                    DropdownMenuItem(
                        text = { Text("Fit to Screen") },
                        leadingIcon = { Icon(Icons.Rounded.Fullscreen, null) },
                        onClick = { menu = false; flyToFit() },
                    )
                    if (complete) {
                        DropdownMenuItem(
                            text = { Text("Undo last area") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Undo, null) },
                            onClick = { menu = false; undo() },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Share") },
                            leadingIcon = { Icon(Icons.Rounded.Share, null) },
                            onClick = { menu = false; onShare() },
                        )
                    }
                }
            }
            AnimatedVisibility(!complete, enter = fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 2.dp) {
                    PaletteBar(
                        session = session,
                        shake = shake,
                        onSelect = {
                            if (it == session.selected) {
                                // The paint in hand's swatch, tapped again: where to paint next.
                                showNext()
                            } else {
                                session.select(it)
                                model.changed()
                                haptics.tick()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                    )
                }
            }
            AnimatedVisibility(complete, enter = slideInVertically { it } + fadeIn(MaterialTheme.motionScheme.slowEffectsSpec())) {
                FinishedBar(
                    celebrate = finishedHere,
                    onShare = onShare,
                    onPrint = onPrint,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * What Zen Mode does, said for a few seconds when it is turned on, as iOS's toast says it:
 * nothing on screen otherwise explains why the page moves.
 */
@Composable
private fun ZenNote(shown: Int, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(shown) {
        if (shown == 0) return@LaunchedEffect
        visible = true
        delay(3500)
        visible = false
    }
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut()) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.padding(horizontal = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SelfImprovement, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Zen Mode is on: after each fill, the page glides to the next area of your color.", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Asks for the screen's wide color gamut while the painting shows, where the screen has one,
 * so the paints are the Display P3 colors iOS shows; every other screen keeps the default.
 */
@Composable
private fun WideColor() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val wide = view.display?.isWideColorGamut == true
        val before = window?.colorMode
        if (wide && window != null) window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        onDispose { if (wide && window != null && before != null) window.colorMode = before }
    }
}

/** Whether the painter has turned animations off (the system's Remove animations): iOS's Reduce Motion. */
@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TopBar(
    title: String,
    fraction: Float,
    complete: Boolean,
    celebrate: Boolean,
    finishedOn: Long?,
    zen: Boolean,
    canUndo: Boolean,
    onBack: () -> Unit,
    onHint: () -> Unit,
    onZen: () -> Unit,
    onUndo: () -> Unit,
    onMore: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            ProgressBadge(fraction, complete, celebrate)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        !complete -> "${(fraction * 100).toInt()}% painted"
                        finishedOn != null -> "Finished ${DateFormat.getDateInstance(DateFormat.LONG).format(Date(finishedOn))}"
                        else -> "Finished"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!complete) {
                IconButton(onClick = onHint) { Icon(Icons.Rounded.MyLocation, contentDescription = "Hint: find the next area") }
                IconToggleButton(
                    checked = zen,
                    onCheckedChange = { onZen() },
                    colors = IconButtonDefaults.iconToggleButtonColors(checkedContentColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(if (zen) Icons.Rounded.SelfImprovement else Icons.Outlined.SelfImprovement, contentDescription = "Zen Mode")
                }
                IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Undo") }
            }
            IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
        }
    }
}

/**
 * How far along, as a flat ring; once finished, a gold burst. Finishing here morphs the
 * circle into the burst on a bouncy spring.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProgressBadge(fraction: Float, complete: Boolean, celebrate: Boolean) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) {
        if (!complete) {
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(34.dp),
                strokeWidth = 3.dp,
                gapSize = 2.dp,
            )
        } else {
            val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.SoftBurst) }
            val grow = remember { Animatable(if (celebrate) 0f else 1f) }
            LaunchedEffect(Unit) {
                if (grow.value < 1f) grow.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
            }
            val path = remember { Path() }
            val matrix = remember { Matrix() }
            Canvas(Modifier.size(34.dp).semantics { contentDescription = "Finished" }) {
                val t = grow.value
                path.reset()
                morph.toPath(t.coerceIn(0f, 1f), path)
                matrix.reset()
                matrix.scale(size.width, size.height)
                path.transform(matrix)
                rotate(90f * (1f - t)) {
                    scale(0.55f + 0.45f * t) { drawPath(path, Moonlight.gold) }
                }
            }
        }
    }
}
