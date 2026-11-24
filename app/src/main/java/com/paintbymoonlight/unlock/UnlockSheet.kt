package com.paintbymoonlight.unlock

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paintbymoonlight.ui.theme.Moonlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Unlimited Paintings' sheet: what it unlocks, its price, Not Now and Restore. Opened when a new
 * painting finds no free painting left ([UnlockGate]); it closes itself once the unlock is bought
 * and [onUnlocked] lets the painting go on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockSheet(paintings: UnlimitedPaintings, onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    val state = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    val unlocked by paintings.unlocked.collectAsStateWithLifecycle()
    // Bought here, restored, or a pending payment that went through while the sheet was up.
    LaunchedEffect(unlocked) {
        if (unlocked) {
            state.hide()
            onUnlocked()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surface) {
        UnlockContent(paintings, onNotNow = {
            // Hidden first so the sheet slides away rather than vanishing.
            state.hide()
            onDismiss()
        })
    }
}

/** What the painter is told after an answer from the store. */
private sealed interface Note {
    data object Pending : Note
    data object NothingToRestore : Note
    data class Failed(val message: String) : Note
}

/** The sheet's content, apart from the sheet itself (so a test can photograph it). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UnlockContent(paintings: UnlimitedPaintings, onNotNow: suspend () -> Unit, modifier: Modifier = Modifier) {
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val price by paintings.price.collectAsStateWithLifecycle()
    var note by remember { mutableStateOf<Note?>(null) }
    var buying by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }

    suspend fun loadPrice() {
        try {
            paintings.loadPrice()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note = Note.Failed(e.message ?: UnlockException.unavailable.message!!)
        }
    }
    LaunchedEffect(Unit) { loadPrice() }

    fun buy(activity: Activity) {
        if (buying || restoring) return
        note = null
        if (price == null) {
            scope.launch { loadPrice() }
            return
        }
        buying = true
        scope.launch {
            try {
                if (paintings.buy(activity) == PurchaseOutcome.Pending) note = Note.Pending
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                note = Note.Failed(e.message ?: UnlockException.failed.message!!)
            } finally {
                buying = false
            }
        }
    }

    fun restore() {
        if (buying || restoring) return
        note = null
        restoring = true
        scope.launch {
            when (paintings.refresh()) {
                true -> {}
                false -> note = Note.NothingToRestore
                null -> note = Note.Failed(UnlockException.unavailable.message!!)
            }
            restoring = false
        }
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .padding(bottom = 20.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 440.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            MoonMark()
            Spacer(Modifier.height(18.dp))
            Text(
                "Unlimited Paintings",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your next free painting comes tomorrow. One purchase unlocks as many new paintings as you like.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            note?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    when (it) {
                        Note.Pending -> "Your payment is on its way. Your paintings unlock as soon as it goes through."
                        Note.NothingToRestore -> "This Google account hasn’t bought Unlimited Paintings yet."
                        is Note.Failed -> it.message
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (it is Note.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = { activity?.let(::buy) },
                enabled = activity != null && !buying && !restoring,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                // While buying the price stays, unseen, under the indicator, so the button keeps its size.
                Box(contentAlignment = Alignment.Center) {
                    val label = when {
                        price != null -> "Unlock for $price"
                        note is Note.Failed -> "Try again"
                        else -> null
                    }
                    if (label != null) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (buying) Color.Transparent else Color.Unspecified,
                        )
                    }
                    if (buying || label == null) {
                        LoadingIndicator(
                            Modifier.size(32.dp).semantics { contentDescription = if (buying) "Unlocking…" else "Loading…" },
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { scope.launch { onNotNow() } }) { Text("Not now") }
                TextButton(onClick = ::restore, enabled = !buying && !restoring) { Text("Restore purchase") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                FOOTNOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** What stays free, under the sheet's buttons, in the iOS app's words: a painting someone sends never takes a free painting. */
private const val FOOTNOTE = "Paintings someone sends you are always free."

/** The app's crescent in gold with two of the Moon's stars, as the iOS sheet draws them. */
@Composable
private fun MoonMark() {
    val gold = Moonlight.gold
    Canvas(Modifier.size(width = 63.dp, height = 45.dp)) {
        val side = size.height / 1.25f
        val moonLeft = side * 0.05f
        val moonTop = side * 0.2f
        drawPath(
            crescent(Offset(moonLeft + side / 2, moonTop + side / 2), side),
            gold,
            style = Stroke(width = maxOf(2.dp.toPx(), side * 0.06f), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(star(Offset(side * 1.3f, side * 0.3f), side * 0.34f), gold)
        drawPath(star(Offset(side * 1.55f, side * 0.78f), side * 0.24f), gold)
    }
}

/** The design system's crescent: an outer circle of radius 26 and an inner one of 23, 6.41 right of and 2.89 above its centre, on a moon 52 wide. */
private fun crescent(centre: Offset, width: Float): Path {
    val s = width / 52f
    val inner = Offset(centre.x + 6.41f * s, centre.y - 2.89f * s)
    val path = Path()
    var first = true
    fun arc(c: Offset, radius: Float, from: Double, to: Double) {
        val steps = 40
        for (k in 0..steps) {
            val a = (from + (to - from) * k / steps) * PI / 180
            val x = c.x + radius * cos(a).toFloat()
            val y = c.y + radius * sin(a).toFloat()
            if (first) path.moveTo(x, y) else path.lineTo(x, y)
            first = false
        }
    }
    arc(centre, 26 * s, -81.75, -326.7)
    arc(inner, 23 * s, 48.24, 263.31)
    path.close()
    return path
}

/** A four-pointed sparkle of [size] across, its sides drawn in toward the centre. */
private fun star(centre: Offset, size: Float): Path {
    val half = size / 2
    val pinch = half * 0.18f
    return Path().apply {
        moveTo(centre.x, centre.y - half)
        quadraticTo(centre.x + pinch, centre.y - pinch, centre.x + half, centre.y)
        quadraticTo(centre.x + pinch, centre.y + pinch, centre.x, centre.y + half)
        quadraticTo(centre.x - pinch, centre.y + pinch, centre.x - half, centre.y)
        quadraticTo(centre.x - pinch, centre.y - pinch, centre.x, centre.y - half)
        close()
    }
}
