package com.paintbymoonlight.ui.gallery

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.data.PaintingMeta
import com.paintbymoonlight.ui.exchange.SendTemplateSheet
import com.paintbymoonlight.ui.theme.Moonlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The painter's paintings, newest first; New Painting opens the photo picker. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GalleryScreen(
    library: Library,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onPrint: ((String) -> Unit)?,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val paintings by library.paintings.collectAsStateWithLifecycle()
    val loaded by library.loaded.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var renaming by remember { mutableStateOf<PaintingMeta?>(null) }
    var deleting by remember { mutableStateOf<PaintingMeta?>(null) }
    var sending by remember { mutableStateOf<PaintingMeta?>(null) }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Paint by Moonlight") },
                subtitle = { if (paintings.isNotEmpty()) Text(countText(paintings)) },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("New painting") },
            )
        },
    ) { padding ->
        if (paintings.isEmpty()) {
            if (loaded) Empty(Modifier.padding(padding))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 104.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(paintings, key = { it.id }) { meta ->
                    PaintingCard(
                        meta = meta,
                        thumbnail = library.thumbnailFile(meta.id),
                        onOpen = { onOpen(meta.id) },
                        onShare = { onShare(meta.id) },
                        onSend = { sending = meta },
                        onPrint = onPrint?.let { print -> { print(meta.id) } },
                        onRename = { renaming = meta },
                        onDelete = { deleting = meta },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
    renaming?.let { meta ->
        var text by remember(meta.id) { mutableStateOf(meta.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { onRename(meta.id, text.trim().ifEmpty { meta.title }); renaming = null }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { meta ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${meta.title}”?") },
            text = { Text("The painting and its progress will be gone from this device.") },
            confirmButton = {
                TextButton(onClick = { onDelete(meta.id); deleting = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    sending?.let { meta -> SendTemplateSheet(meta, library) { sending = null } }
}

private fun countText(paintings: List<PaintingMeta>): String {
    val finished = paintings.count { it.finished != null }
    val n = paintings.size
    return "$n painting${if (n == 1) "" else "s"}" + if (finished > 0) ", $finished finished" else ""
}

@Composable
private fun Empty(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = Moonlight.gold, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text("Your photos, painted by numbers", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick a photo and it becomes a painting to fill in, one number at a time.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PaintingCard(
    meta: PaintingMeta,
    thumbnail: File,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSend: () -> Unit,
    onPrint: (() -> Unit)?,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    // Reloaded whenever progress is saved (the thumbnail is saved with it).
    val image by produceState<ImageBitmap?>(null, thumbnail, meta.updated) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(thumbnail.path)?.asImageBitmap() }
    }
    val fraction = if (meta.regionCount > 0) meta.paintedCount / meta.regionCount.toFloat() else 0f
    Card(
        modifier = modifier
            .combinedClickable(onClick = onOpen, onLongClick = { menu = true }, onLongClickLabel = "More")
            .semantics { contentDescription = "${meta.title}, ${if (meta.finished != null) "finished" else "${(fraction * 100).toInt()}% painted"}" },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(10.dp), contentAlignment = Alignment.Center) {
            image?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        Row(Modifier.padding(start = 14.dp, end = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(meta.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (meta.finished != null) "Finished" else "${(fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (meta.finished != null) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Moonlight.gold, modifier = Modifier.size(20.dp))
            } else {
                CircularWavyProgressIndicator(progress = { fraction }, modifier = Modifier.size(22.dp))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = { menu = false; onShare() })
            // Paint by Moonlight, on either platform, opens it as a new painting, blank.
            DropdownMenuItem(
                text = { Text("Send Template…") },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Send, null) },
                onClick = { menu = false; onSend() },
            )
            if (onPrint != null) {
                DropdownMenuItem(text = { Text("Print it big") }, leadingIcon = { Icon(Icons.Rounded.Print, null) }, onClick = { menu = false; onPrint() })
            }
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}
