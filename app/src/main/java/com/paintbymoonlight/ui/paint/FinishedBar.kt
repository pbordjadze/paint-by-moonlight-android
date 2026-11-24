package com.paintbymoonlight.ui.paint

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.paintbymoonlight.ui.theme.Moonlight

/** Takes the palette's place once the painting is finished: Finished!, Share, and the big print. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FinishedBar(celebrate: Boolean, onShare: () -> Unit, onPrint: (() -> Unit)?, modifier: Modifier = Modifier) {
    val pop = remember { Animatable(if (celebrate) 0.4f else 1f) }
    val spring = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(celebrate) { if (celebrate) pop.animateTo(1f, spring) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 2.dp, modifier = modifier) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.AutoAwesome, contentDescription = null, tint = Moonlight.gold,
                    modifier = Modifier.size(28.dp).scale(pop.value).rotate((1 - pop.value) * 90),
                )
                Spacer(Modifier.width(10.dp))
                Text("Finished!", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            }
            Spacer(Modifier.size(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Share, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Share")
                }
                if (onPrint != null) {
                    Button(onClick = onPrint, modifier = Modifier.weight(1.4f)) {
                        Icon(Icons.Rounded.Print, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Print it big")
                    }
                }
            }
        }
    }
}
