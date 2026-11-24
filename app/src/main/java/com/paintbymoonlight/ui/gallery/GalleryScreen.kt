package com.paintbymoonlight.ui.gallery

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MediumExtendedFloatingActionButton
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.paintbymoonlight.ui.paintingBounds
import com.paintbymoonlight.ui.theme.Moonlight
import com.paintbymoonlight.ui.theme.raised
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The painter's paintings, newest first, each whole on a raised card in a staggered grid; a
 * long press opens a card's menu. New painting opens the photo picker.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GalleryScreen(
    library: Library,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onPrintPages: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val paintings by library.paintings.collectAsStateWithLifecycle()
    val loaded by library.loaded.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val grid = rememberLazyStaggeredGridState()
    // Collapses to its icon while the painter scrolls down the paintings, back on the way up.
    val fabExpanded by remember { derivedStateOf { !grid.canScrollBackward || grid.lastScrolledBackward } }
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
            MediumExtendedFloatingActionButton(
                text = { Text("New painting") },
                icon = { Icon(Icons.Rounded.Add, contentDescription = if (fabExpanded) null else "New painting") },
                onClick = onNew,
                expanded = fabExpanded,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        if (paintings.isEmpty()) {
            if (loaded) Empty(Modifier.padding(padding))
        } else {
            // Each painting whole at its own shape, so the columns stagger.
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(minSize = 160.dp),
                state = grid,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalItemSpacing = 14.dp,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(paintings, key = { it.id }) { meta ->
                    PaintingCard(
                        meta = meta,
                        thumbnail = library.thumbnailFile(meta.id),
                        onOpen = { onOpen(meta.id) },
                        onShare = { onShare(meta.id) },
                        onSend = { sending = meta },
                        onPrintPages = { onPrintPages(meta.id) },
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
    onPrintPages: () -> Unit,
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
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    // The card's corners round further under the finger, Expressive's press shape morph.
    val corner by animateDpAsState(if (pressed) 32.dp else 24.dp, MaterialTheme.motionScheme.fastSpatialSpec())
    val shape = RoundedCornerShape(corner)
    // A sliver of a panorama or a very tall painting is cropped rather than stretching the column.
    val aspect = (if (meta.aspect > 0f) meta.aspect else 1f).coerceIn(0.5f, 2f)
    Card(
        modifier = modifier
            .clip(shape)
            .combinedClickable(
                interactionSource = press,
                indication = ripple(),
                onClick = onOpen,
                onLongClick = { menu = true },
                onLongClickLabel = "More",
            )
            .semantics { contentDescription = "${meta.title}, ${if (meta.finished != null) "finished" else "${(fraction * 100).toInt()}% painted"}" },
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.raised),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .then(paintingBounds(meta.id, RoundedCornerShape(topStart = corner, topEnd = corner)))
                .background(Moonlight.sheet),
        ) {
            image?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Row(
            Modifier.padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                meta.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            if (meta.finished != null) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Moonlight.gold, modifier = Modifier.size(20.dp))
            } else {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 3.dp,
                    gapSize = 2.dp,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = { menu = false; onShare() })
            // One Big Sheet is offered inside Print, as on iOS: one way to print per menu.
            DropdownMenuItem(text = { Text("Print…") }, leadingIcon = { Icon(Icons.Rounded.Print, null) }, onClick = { menu = false; onPrintPages() })
            // Paint by Moonlight, on either platform, opens it as a new painting, blank.
            DropdownMenuItem(
                text = { Text("Send Template…") },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Send, null) },
                onClick = { menu = false; onSend() },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}
