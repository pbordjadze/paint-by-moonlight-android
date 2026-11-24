package com.paintbymoonlight.ui.paint

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paintbymoonlight.ui.theme.NumeralFamily

/**
 * The palette: a swatch per paint left, its number on it. The paint in hand is a scalloped
 * cookie rather than a circle and stands a little larger; a swatch shakes when the painter
 * taps an area of its paint with another in hand ([shake] changes). A finished paint leaves.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    LaunchedEffect(session.selected) {
        val i = left.indexOf(session.selected)
        if (i >= 0) listState.animateScrollToItem(maxOf(0, i - 2))
    }
    LaunchedEffect(shake) {
        val i = shake?.let { left.indexOf(it.first) } ?: -1
        if (i >= 0) listState.animateScrollToItem(maxOf(0, i - 2))
    }
    val cookie = MaterialShapes.Cookie9Sided.toShape()
    val circle = MaterialShapes.Circle.toShape()
    LazyRow(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(left, key = { _, paint -> paint }) { _, paint ->
            val color = Color(scene.paints[paint].color)
            val held = paint == session.selected
            val grow by animateFloatAsState(if (held) 1.12f else 1f, MaterialTheme.motionScheme.fastSpatialSpec())
            val shakeX = remember { Animatable(0f) }
            LaunchedEffect(shake) {
                if (shake?.first == paint) {
                    for (x in listOf(10f, -9f, 7f, -5f, 3f, 0f)) shakeX.animateTo(x, spring(stiffness = 2500f))
                }
            }
            val ink = if (color.luminance() > 0.45f) Color(0xFF231E26) else Color.White
            val name = scene.paints[paint].name
            Box(
                Modifier
                    .animateItem()
                    .graphicsLayer { translationX = shakeX.value }
                    .scale(grow)
                    .size(52.dp)
                    .clip(if (held) cookie else circle)
                    .background(color)
                    .then(
                        if (held) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, cookie)
                        else Modifier.border(1.dp, Color.Black.copy(alpha = 0.12f), circle)
                    )
                    .clickable { onSelect(paint) }
                    .semantics {
                        role = Role.Button
                        selected = held
                        contentDescription = "Paint ${paint + 1}${if (name.isNotEmpty()) ", $name" else ""}"
                        stateDescription = "${session.remaining(paint)} of ${session.total(paint)} areas left"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${paint + 1}",
                    color = ink,
                    fontFamily = NumeralFamily,
                    fontWeight = if (held) FontWeight.Bold else FontWeight.Medium,
                    fontSize = if (paint + 1 >= 100) 15.sp else 19.sp,
                )
                // How much of this paint is done, as a thin arc along the swatch's foot.
                val done = 1f - session.remaining(paint) / session.total(paint).coerceAtLeast(1).toFloat()
                if (done > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 7.dp)
                            .size(width = (26 * done).dp, height = 3.dp)
                            .clip(circle)
                            .background(ink.copy(alpha = 0.7f))
                    )
                }
            }
        }
    }
}
