package com.paintbymoonlight.ui.create

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paintbymoonlight.core.PaintingLength
import com.paintbymoonlight.data.Preferences
import com.paintbymoonlight.unlock.FreePaintingsLeft
import com.paintbymoonlight.unlock.rememberUnlockGate

/**
 * The picked photo, Painting Length (Quick, Relaxed, Detailed: a connected button group) and
 * Start. While the painting is made the photo stays, a loading indicator over it and a wavy
 * bar under it; Back cancels.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CreateScreen(model: CreateViewModel, preferences: Preferences, onBack: () -> Unit, onMade: (String) -> Unit) {
    LaunchedEffect(model.made) { model.made?.let(onMade) }
    // Asked again at Start: the free painting the gallery saw may have gone since (another day, another painting).
    val gate = rememberUnlockGate(model.paintings)
    gate.Sheet()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New painting") },
                navigationIcon = {
                    IconButton(onClick = { model.cancel(); onBack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val photo = model.photo
                when {
                    model.failed != null && photo == null -> Text(model.failed!!, textAlign = TextAlign.Center)
                    photo == null -> ContainedLoadingIndicator()
                    else -> {
                        Image(
                            bitmap = photo.asImageBitmap(),
                            contentDescription = model.credit?.first ?: "Your photo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.clip(RoundedCornerShape(28.dp)),
                        )
                        if (model.making) ContainedLoadingIndicator(Modifier.size(72.dp))
                    }
                }
            }
            // A picture that isn't the painter's own keeps its maker's name in view.
            model.credit?.let { (title, byline) ->
                Spacer(Modifier.height(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                if (byline.isNotEmpty()) {
                    Text(byline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(20.dp))
            AnimatedContent(model.making, label = "controls") { making ->
                if (making) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("Mixing your paints…", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(14.dp))
                        LinearWavyProgressIndicator(progress = { model.progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { model.cancel() }) { Text("Cancel") }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        if (model.failed != null && model.photo != null) {
                            Text(model.failed!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                        }
                        Text("Painting length", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        LengthChoice(preferences.paintingLength) { preferences.paintingLength = it }
                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = { gate.start { model.make(preferences.paintingLength) } },
                            enabled = model.photo != null,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Icon(Icons.Rounded.Brush, null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("Start painting", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        FreePaintingsLeft(model.paintings)
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LengthChoice(selected: PaintingLength, onSelect: (PaintingLength) -> Unit) {
    val labels = mapOf(PaintingLength.Quick to "Quick", PaintingLength.Relaxed to "Relaxed", PaintingLength.Detailed to "Detailed")
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        PaintingLength.entries.forEachIndexed { i, length ->
            ToggleButton(
                checked = length == selected,
                onCheckedChange = { onSelect(length) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    PaintingLength.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                Text(labels.getValue(length))
            }
        }
    }
}
