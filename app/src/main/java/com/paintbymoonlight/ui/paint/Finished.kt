package com.paintbymoonlight.ui.paint

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import com.paintbymoonlight.ui.theme.Moonlight
import kotlin.math.roundToInt

/**
 * Under a finished painting: the gold burst and Finished!, at [top] px down the screen (just
 * under the painting). Finished here, the burst grows out of a circle on a bouncy spring; opened
 * finished, it is simply there.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FinishedHeading(celebrate: Boolean, top: () -> Float, modifier: Modifier = Modifier) {
    Row(
        modifier.offset { IntOffset(0, top().roundToInt()) },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Burst(celebrate, Modifier.size(30.dp))
        Spacer(Modifier.width(10.dp))
        Text("Finished!", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    }
}

/** The circle morphing into the gold burst. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Burst(celebrate: Boolean, modifier: Modifier) {
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.SoftBurst) }
    val grow = remember { Animatable(if (celebrate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (grow.value < 1f) grow.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
    }
    val path = remember { Path() }
    val matrix = remember { Matrix() }
    Canvas(modifier) {
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

/** Share and the big print, floating at the foot of a finished painting. */
@Composable
fun FinishedActions(onShare: () -> Unit, onPrint: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f).height(56.dp)) {
            Icon(Icons.Rounded.Share, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Share")
        }
        if (onPrint != null) {
            Button(onClick = onPrint, modifier = Modifier.weight(1.4f).height(56.dp)) {
                Icon(Icons.Rounded.Print, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Print it big")
            }
        }
    }
}
