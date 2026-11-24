package com.paintbymoonlight.ui.source

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paintbymoonlight.pictures.Pictures
import com.paintbymoonlight.pictures.Sample
import com.paintbymoonlight.ui.theme.Moonlight
import com.paintbymoonlight.ui.theme.raised

/**
 * A picture's tile, as the Paint page and the gallery's shelf show it: the picture at 4:3, its
 * title and (a painting's) painter on it over a dark fade; at the largest text sizes under it,
 * where they wrap whole. TalkBack reads the title and who made it.
 */
@Composable
fun SampleTile(
    sample: Sample,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    state: String? = null,
    badge: @Composable BoxScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, sample.id) {
        value = Pictures.tile(context, sample)?.asImageBitmap()
    }
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 26.dp else 18.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "corner")
    val shape = RoundedCornerShape(corner)
    val below = LocalDensity.current.fontScale >= 1.5f
    Card(
        modifier = modifier
            .clip(shape)
            .clickable(interactionSource = press, indication = ripple(), onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = sample.label
                role = Role.Button
                state?.let { stateDescription = it }
            },
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.raised),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            val shown by animateFloatAsState(if (image != null) 1f else 0f, tween(200), label = "fade")
            image?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(shown)) }
            if (!below) {
                // Dark by the caption's first line, so white words read over spray or sky.
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.62f), 1f to Color.Black.copy(alpha = 0.8f)))
                        .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 12.dp),
                ) {
                    Caption(sample, Color.White, Color.White.copy(alpha = 0.9f))
                }
            }
            badge()
        }
        if (below) {
            Column(Modifier.padding(12.dp)) {
                Caption(sample, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.onSurfaceVariant, wraps = true)
            }
        }
    }
}

@Composable
private fun Caption(sample: Sample, color: Color, secondary: Color, wraps: Boolean = false) {
    Text(
        sample.title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        maxLines = if (wraps) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis,
    )
    sample.painter?.let {
        Text(it, style = MaterialTheme.typography.labelMedium, color = secondary, maxLines = if (wraps) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Tonight's, told from the nights after it by the crescent alone, gold on a dark disc so a warm picture doesn't swallow it. */
@Composable
fun BoxScope.TonightMoon() {
    Box(
        Modifier.align(Alignment.TopEnd).padding(10.dp).size(36.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = Moonlight.gold, modifier = Modifier.size(20.dp))
    }
}
