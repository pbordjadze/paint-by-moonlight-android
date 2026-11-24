package com.paintbymoonlight.ui.paint

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paintbymoonlight.core.Paint
import com.paintbymoonlight.ui.theme.NumeralFamily
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The palette, as iOS's: the paint in hand named over the swatches ("12 · Harbor Fog"), then a
 * round swatch per paint left with its number on it and a ring round it for how much of it is
 * painted. The paint in hand stands a little larger, its ring dark; tapped again it shows where
 * to paint next. Held, a swatch shows the paint's number, name, shade and hex code. A swatch
 * shakes when the painter taps an area of its paint with another in hand ([shake] changes), and
 * the bar brings it into view. A finished paint leaves.
 */
@Composable
fun PaletteBar(
    session: PaintSession,
    shake: Pair<Int, Int>?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scene = session.scene
    // Read so the bar recomposes when a fill finishes a paint.
    session.changes
    val left = scene.paints.indices.filter { !session.isPaintFinished(it) || it == session.selected }
    val listState = rememberLazyListState()
    val metrics = SwatchMetrics(LocalDensity.current.fontScale)
    // The bar moves only as far as brings a swatch into view, and not at all while it shows.
    suspend fun reveal(paint: Int) {
        val i = left.indexOf(paint)
        if (i < 0) return
        val shown = listState.layoutInfo.visibleItemsInfo
        val item = shown.firstOrNull { it.index == i }
        val start = listState.layoutInfo.viewportStartOffset
        val end = listState.layoutInfo.viewportEndOffset
        if (item != null && item.offset >= start && item.offset + item.size <= end) return
        listState.animateScrollToItem(maxOf(0, i - 2))
    }
    LaunchedEffect(session.selected) { reveal(session.selected) }
    LaunchedEffect(shake) { shake?.let { reveal(it.first) } }
    // Painting on with the paint in hand brings its swatch back, wherever a shake left the bar.
    LaunchedEffect(session.selected.takeIf { it in scene.paints.indices }?.let { session.remaining(it) }) { reveal(session.selected) }
    Column(modifier) {
        CurrentPaint(session, metrics, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(metrics.spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(left, key = { it }) { paint ->
                Swatch(
                    index = paint,
                    paint = scene.paints[paint],
                    painted = session.total(paint) - session.remaining(paint),
                    total = session.total(paint),
                    held = paint == session.selected,
                    finished = session.isPaintFinished(paint),
                    shake = shake?.takeIf { it.first == paint }?.second ?: 0,
                    metrics = metrics,
                    onClick = { onSelect(paint) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

/**
 * Swatch geometry at a font scale, as iOS's `PaletteMetrics`: 40 dp swatches on a 56 dp pitch,
 * the numerals growing with the text up to 1.4×.
 */
private class SwatchMetrics(fontScale: Float) {
    val scale = when {
        fontScale >= 1.8f -> 1.4f
        fontScale >= 1.5f -> 1.32f
        fontScale >= 1.3f -> 1.24f
        fontScale >= 1.15f -> 1.16f
        fontScale > 1f -> 1.08f
        else -> 1f
    }
    val diameter: Dp = 40.dp * scale
    /** Between rings: a 56 dp pitch at the default size, as iOS's. */
    val spacing: Dp = 8.dp * scale
    // In sp of the default scale, so the numeral grows with the swatch and no more.
    val numeral = (16 * scale / fontScale).sp
    val caption = (13 * scale / fontScale).sp
}

/** The paint in hand: a dot of it, its number and its name ("12 · Harbor Fog"). */
@Composable
private fun CurrentPaint(session: PaintSession, metrics: SwatchMetrics, modifier: Modifier = Modifier) {
    val paint = session.selected
    if (paint !in session.scene.paints.indices) return
    val p = session.scene.paints[paint]
    Row(
        modifier.height(24.dp * metrics.scale - 8.dp).clearAndSetSemantics {
            contentDescription = "Current color: ${p.spoken(paint)}"
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(10.dp).clip(CircleShape).background(p.composeColor())
                .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            p.numbered(paint),
            fontSize = metrics.caption,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Swatch(
    index: Int,
    paint: Paint,
    painted: Int,
    total: Int,
    held: Boolean,
    finished: Boolean,
    shake: Int,
    metrics: SwatchMetrics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = paint.composeColor()
    // Lighter paints take a dark numeral, the rest a white one.
    val ink = if (color.luminance() > 0.36f) Color.Black.copy(alpha = 0.75f) else Color.White
    val grow by animateFloatAsState(if (held) 1.14f else 1f, spring(dampingRatio = 0.6f, stiffness = 380f), label = "held")
    val fraction by animateFloatAsState(if (total > 0) painted / total.toFloat() else 0f, tween(350), label = "painted")
    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(shake) {
        if (shake > 0) {
            shakeX.snapTo(0f)
            shakeX.animateTo(1f, tween(400))
            shakeX.snapTo(0f)
        }
    }
    var details by remember { mutableStateOf(false) }
    val ring = MaterialTheme.colorScheme.onSurface
    val percent = if (total > 0) painted * 100 / total else 0
    Box(
        modifier
            .graphicsLayer { translationX = 5.dp.toPx() * sin(shakeX.value * PI.toFloat() * 4) }
            .size(metrics.diameter + 8.dp)
            .alpha(if (finished && !held) 0.4f else 1f)
            .scale(grow)
            .clip(CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = { details = true })
            .semantics {
                role = Role.Button
                selected = held
                contentDescription = paint.spoken(index)
                stateDescription = if (total > 0 && painted >= total) "Finished" else "$percent percent painted"
                onClick(label = if (held) "Show where to paint next" else "Select this color") { onClick(); true }
                customActions = listOf(CustomAccessibilityAction("Show Color Details") { details = true; true })
            },
        contentAlignment = Alignment.Center,
    ) {
        // The ring round the swatch: a faint track, and how much of the paint is done.
        Canvas(Modifier.size(metrics.diameter + 8.dp)) {
            val stroke = 2.5.dp.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawCircle(ring.copy(alpha = if (held) 0.16f else 0.08f), radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
            if (fraction > 0f) {
                drawArc(
                    ring.copy(alpha = if (held) 0.85f else 0.4f), startAngle = -90f, sweepAngle = 360f * min(fraction, 1f),
                    useCenter = false, topLeft = Offset(inset, inset), size = arc, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Box(
            Modifier.size(metrics.diameter).clip(CircleShape).background(color)
                .border(0.5.dp, Color.Black.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (finished) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = ink, modifier = Modifier.size(metrics.diameter * 0.45f))
            } else {
                Text(
                    "${index + 1}",
                    color = ink,
                    fontFamily = NumeralFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (index + 1 >= 100) metrics.numeral * 0.8f else metrics.numeral,
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(expanded = details, onDismissRequest = { details = false }) {
            SwatchDetails(index, paint)
        }
    }
}

/** What holding a swatch shows: the paint's number, nickname, plain color name and hex code. */
@Composable
private fun SwatchDetails(index: Int, paint: Paint) {
    Column(Modifier.widthIn(min = 240.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(paint.numbered(index), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        DetailRow("Number", "${index + 1}")
        if (paint.name.isNotEmpty()) DetailRow("Name", paint.name)
        DetailRow("Shade", paint.shade)
        DetailRow("Hex", paint.hex)
    }
}

@Composable
private fun DetailRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

/** The paint as Compose draws it: its Display P3 color, shown as such on a wide-gamut window. */
fun Paint.composeColor(): Color = Color(
    android.graphics.Color.red(p3), android.graphics.Color.green(p3), android.graphics.Color.blue(p3),
    colorSpace = ColorSpaces.DisplayP3,
)
