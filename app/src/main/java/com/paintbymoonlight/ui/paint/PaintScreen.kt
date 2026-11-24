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
import kotlinx.coroutines.withContext
import java.io.File
import android.view.HapticFeedbackConstants
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
import androidx.compose.ui.platform.LocalViewConfiguration
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
    val pulse = remember { HintPulse() }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current.density
    val doubleTapMillis = LocalViewConfiguration.current.doubleTapTimeoutMillis
    val pulseSpec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    var shake by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var finishedHere by remember { mutableStateOf(false) }
    var finishedAt by remember { mutableStateOf<Long?>(null) }
    // A tap that painted nothing, so a second one close by and soon is a double tap (Whole
    // painting). A tap that paints is never held back waiting to see whether a second follows.
    val lastMiss = remember { arrayOfNulls<Pair<Long, Offset>>(1) }
    val inset = 24 * density
    val finishedInset = 12 * density
    val scene = session.scene
    val complete = session.let { it.changes; it.isComplete }

    // Save on leaving the app; leaving the screen saves as the model is cleared.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.saveNow() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Where the canvas looks is saved with the painting, once it has been still a moment.
    LaunchedEffect(camera) {
        snapshotFlow { camera.scale to camera.offset }.drop(1).collectLatest {
            delay(500)
            if (camera.viewport.width > 0) {
                session.progress.view = camera.view(scene.width, scene.height, inset)
                model.viewChanged()
            }
        }
    }

    fun flyToNext(hint: Boolean) {
        val center = camera.toCanvas(Offset(camera.viewport.width / 2f, camera.viewport.height / 2f))
        val next = session.nextArea(nearX = center.x, nearY = center.y) ?: return
        val b = scene.regions[next].bounds
        val fit = fitScale(camera.viewport, scene.width, scene.height, inset)
        scope.launch {
            camera.flyToArea(b.left, b.top, b.right, b.bottom, minScale = fit, maxScale = fit * 12)
            if (hint) {
                pulse.region = next
                try {
                    // Three beats that never quite go out until the last.
                    repeat(3) { beat ->
                        pulse.strength.animateTo(1f, pulseSpec)
                        pulse.strength.animateTo(if (beat < 2) 0.35f else 0f, pulseSpec)
                    }
                } finally {
                    pulse.strength.snapTo(0f)
                    if (pulse.region == next) pulse.region = null
                }
            }
        }
    }

    fun flyToFit() {
        scope.launch { camera.settle(fitting(scene.width, scene.height, if (complete) finishedInset else inset)) }
    }

    fun undo() {
        if (session.undo() != null) {
            model.changed()
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
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
                onHint = { flyToNext(hint = true) },
                onZen = { preferences.zenMode = !preferences.zenMode },
                onUndo = ::undo,
                onMore = { menu = true },
            )
            Box(Modifier.weight(1f).fillMaxWidth().then(paintingBounds(model.id, RectangleShape))) {
                PaintCanvas(
                    session = session,
                    camera = camera,
                    placement = {
                        val saved = session.progress.view
                        when {
                            session.isComplete -> fitting(scene.width, scene.height, finishedInset)
                            saved != null -> restoring(saved, scene.width, scene.height, inset)
                            else -> filling(scene.width, scene.height, inset)
                        }
                    },
                    minScale = { fitScale(it, scene.width, scene.height, inset) },
                    pulse = pulse,
                    modifier = Modifier.fillMaxSize(),
                    onTap = { screen ->
                        val p = camera.toCanvas(screen)
                        val now = SystemClock.uptimeMillis()
                        when (val tap = session.tap(p.x, p.y)) {
                            is Tap.Painted -> {
                                lastMiss[0] = null
                                model.changed()
                                view.performHapticFeedback(
                                    if (tap.finishedPaint) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK)
                                if (tap.finishedPainting) {
                                    finishedHere = true
                                    finishedAt = System.currentTimeMillis()
                                    // Once the Finished bar is in, the whole painting in the room above it.
                                    scope.launch {
                                        delay(450)
                                        camera.settle(fitting(scene.width, scene.height, finishedInset))
                                    }
                                } else if (preferences.zenMode) {
                                    scope.launch { delay(if (tap.finishedPaint) 900 else 380); flyToNext(hint = false) }
                                }
                            }
                            else -> {
                                val miss = lastMiss[0]
                                val slop = 48 * density
                                if (miss != null && now - miss.first <= doubleTapMillis && (screen - miss.second).getDistance() <= slop) {
                                    lastMiss[0] = null
                                    flyToFit()
                                } else {
                                    lastMiss[0] = now to screen
                                    if (tap is Tap.OtherPaint) {
                                        shake = tap.paint to ((shake?.second ?: 0) + 1)
                                        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                                    }
                                }
                            }
                        }
                    },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.align(Alignment.TopEnd)) {
                    DropdownMenuItem(
                        text = { Text("Whole painting") },
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
                            session.select(it)
                            model.changed()
                            if (preferences.zenMode) flyToNext(hint = false)
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
