package com.paintbymoonlight.ui.exchange

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.paintbymoonlight.core.Core
import com.paintbymoonlight.core.SharedPainting
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.data.PaintingMeta
import com.paintbymoonlight.data.Progress
import com.paintbymoonlight.data.SharedPaintings
import com.paintbymoonlight.data.Thumbnails
import com.paintbymoonlight.ui.theme.Moonlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Send Template…, as the iOS app's sheet: the blank page someone else will paint, the Original
 * Photo switch (off until the painter turns it on: the photo is the private part of what is
 * sent), then the share sheet with the painting's `.moonlight` file, which Paint by Moonlight
 * on either platform opens as a new painting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendTemplateSheet(meta: PaintingMeta, library: Library, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberBottomSheetState(SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    var includesPhoto by rememberSaveable(meta.id) { mutableStateOf(INCLUDES_PHOTO_BY_DEFAULT) }
    var canIncludePhoto by remember(meta.id) { mutableStateOf(false) }
    var preview by remember(meta.id) { mutableStateOf<ImageBitmap?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(meta.id) {
        canIncludePhoto = SharedPaintings.hasPhoto(library, meta.id)
        preview = try {
            val scene = Core.scene(library.template(meta.id), meta.seed, meta.nicknameVersion)
            withContext(Dispatchers.Default) { Thumbnails.render(scene, Progress(scene.regions.size), 800).asImageBitmap() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Page(meta, preview)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Send Template", style = MaterialTheme.typography.titleLarge)
                Text(
                    meta.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (canIncludePhoto) {
                Row(
                    Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .toggleable(value = includesPhoto, enabled = !preparing, role = Role.Switch) { includesPhoto = it }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Original Photo", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = includesPhoto, onCheckedChange = null, enabled = !preparing)
                }
            }
            Text(
                explanation(canIncludePhoto, includesPhoto),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
            if (failed) {
                Text(
                    "The painting couldn’t be prepared for sending.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = {
                        if (preparing) return@Button
                        preparing = true
                        failed = false
                        scope.launch {
                            try {
                                val file = SharedPaintings.prepare(context, library, meta.id, includesPhoto && canIncludePhoto)
                                share(context, file, meta.title)
                                onDismiss()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e("SendTemplate", "preparing a template to send failed", e)
                                failed = true
                            } finally {
                                preparing = false
                            }
                        }
                    },
                    enabled = !preparing,
                    modifier = Modifier.semantics { if (preparing) contentDescription = "Preparing…" },
                ) {
                    if (preparing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("Send…")
                    }
                }
            }
        }
    }
}

/** The blank page on the canvas sheet's paper, dimmed by night as iOS dims it. */
@Composable
private fun Page(meta: PaintingMeta, preview: ImageBitmap?) {
    val dim = if (isSystemInDarkTheme()) ColorFilter.tint(NIGHT_PAPER, BlendMode.Multiply) else null
    val shape = RoundedCornerShape(10.dp)
    val description = "A blank copy of “${meta.title}”"
    val aspect = if (meta.aspect > 0f) meta.aspect else 1f
    Box(
        Modifier
            .heightIn(max = 220.dp)
            .aspectRatio(aspect)
            .shadow(6.dp, shape)
            .clip(shape)
            .semantics { contentDescription = description },
    ) {
        if (preview != null) {
            Image(preview, contentDescription = null, contentScale = ContentScale.Fit, colorFilter = dim)
        } else {
            Box(Modifier.matchParentSize().background(if (dim != null) NIGHT_PAPER else Moonlight.sheet))
        }
    }
}

/** The sheet's white at 82%, as iOS dims the page by night (a white page glared on the dark sheet). */
private val NIGHT_PAPER = Color(0xFFD1D1D1)

/** Off: the photo goes only when the painter says so (iOS's `includesPhotoByDefault`). */
private const val INCLUDES_PHOTO_BY_DEFAULT = false

/** The note under the switch, in the iOS app's words. */
private fun explanation(canIncludePhoto: Boolean, includesPhoto: Boolean): String = when {
    !canIncludePhoto -> "They get a blank copy of this page to paint, your changes to the drawing included."
    includesPhoto -> "They get a blank copy of this page to paint, your changes to the drawing included, and your photo to peek at."
    else -> "They get a blank copy of this page to paint, your changes to the drawing included. Your photo stays on this device."
}

private fun share(context: Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = SharedPainting.MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, title))
}
