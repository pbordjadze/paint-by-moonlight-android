package com.paintbymoonlight.ui.paint

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
 * Hint, Zen, Undo, More) and the palette along the bottom. When the last area is painted the
 * camera eases out to the whole painting and the Finished bar takes the palette's place.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PaintScreen(
    model: PaintViewModel,
    preferences: Preferences,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onPrint: (() -> Unit)?,
) {
    when (val state = model.state) {
        PaintViewModel.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        is PaintViewModel.State.Failed -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(state.message, style = MaterialTheme.typography.bodyLarge)
        }
        is PaintViewModel.State.Ready -> Painting(model, state, preferences, onBack, onShare, onPrint)
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
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current.density
    var shake by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var finishedHere by remember { mutableStateOf(false) }
    val inset = 24 * density
    val scene = session.scene

    // Save on leaving the app or the screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.saveNow() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler { model.saveNow(); onBack() }

    fun flyToNext() {
        val center = camera.toCanvas(androidx.compose.ui.geometry.Offset(camera.viewport.width / 2f, camera.viewport.height / 2f))
        val next = session.nextArea(nearX = center.x, nearY = center.y) ?: return
        val b = scene.regions[next].bounds
        val fit = camera.fitScale(scene.width, scene.height, inset)
        scope.launch { camera.flyToArea(b.left, b.top, b.right, b.bottom, minScale = fit, maxScale = fit * 30) }
    }

    fun flyToFit() {
        val s = camera.fitScale(scene.width, scene.height, inset)
        scope.launch { camera.flyTo(s, camera.fitOffset(scene.width, scene.height, s)) }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = state.meta.title,
                fraction = session.let { it.changes; it.fraction },
                complete = session.let { it.changes; it.isComplete },
                zen = preferences.zenMode,
                canUndo = session.let { it.changes; it.canUndo },
                onBack = { model.saveNow(); onBack() },
                onHint = { flyToNext() },
                onZen = { preferences.zenMode = !preferences.zenMode },
                onUndo = {
                    if (session.undo() != null) {
                        model.changed()
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                },
                onMore = { menu = true },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                PaintCanvas(
                    session = session,
                    camera = camera,
                    inset = inset,
                    modifier = Modifier.fillMaxSize(),
                    onTap = { screen ->
                        val p = camera.toCanvas(screen)
                        when (val tap = session.tap(p.x, p.y)) {
                            is Tap.Painted -> {
                                model.changed()
                                if (preferences.haptics) {
                                    view.performHapticFeedback(
                                        if (tap.finishedPaint) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK)
                                }
                                if (tap.finishedPainting) {
                                    finishedHere = true
                                    scope.launch { delay(450); flyToFit() }
                                } else if (preferences.zenMode) {
                                    scope.launch { delay(if (tap.finishedPaint) 900 else 380); flyToNext() }
                                }
                            }
                            is Tap.OtherPaint -> {
                                shake = tap.paint to ((shake?.second ?: 0) + 1)
                                if (preferences.haptics) view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                            }
                            Tap.Nothing -> Unit
                        }
                    },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.align(Alignment.TopEnd)) {
                    DropdownMenuItem(
                        text = { Text("Whole painting") },
                        leadingIcon = { Icon(Icons.Rounded.Fullscreen, null) },
                        onClick = { menu = false; flyToFit() },
                    )
                    DropdownMenuItem(
                        text = { Text("Zen Mode") },
                        leadingIcon = { Icon(Icons.Rounded.SelfImprovement, null) },
                        trailingIcon = { Switch(checked = preferences.zenMode, onCheckedChange = null) },
                        onClick = { preferences.zenMode = !preferences.zenMode },
                    )
                    DropdownMenuItem(
                        text = { Text("Haptics") },
                        trailingIcon = { Switch(checked = preferences.haptics, onCheckedChange = null) },
                        onClick = { preferences.haptics = !preferences.haptics },
                    )
                }
            }
            val complete = session.let { it.changes; it.isComplete }
            AnimatedVisibility(!complete, enter = fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 2.dp) {
                    PaletteBar(
                        session = session,
                        shake = shake,
                        onSelect = {
                            session.select(it)
                            model.changed()
                            if (preferences.zenMode) flyToNext()
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
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
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
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) {
                CircularWavyProgressIndicator(progress = { fraction }, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (complete) "Finished!" else "${(fraction * 100).toInt()}% painted",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!complete) {
                IconButton(onClick = onHint) { Icon(Icons.Rounded.CenterFocusStrong, contentDescription = "Hint: find the next area") }
                FilledTonalIconToggleButton(checked = zen, onCheckedChange = { onZen() }) {
                    Icon(Icons.Rounded.SelfImprovement, contentDescription = "Zen Mode")
                }
            }
            IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Undo") }
            IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
        }
    }
}
