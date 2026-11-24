package com.paintbymoonlight.ui.museum

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.museum.MuseumCatalog
import com.paintbymoonlight.museum.MuseumImages
import com.paintbymoonlight.museum.MuseumLibrary
import com.paintbymoonlight.museum.MuseumPainted
import com.paintbymoonlight.ui.create.Picked
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import com.paintbymoonlight.pictures.Pictures

/**
 * A work of the Museum, large, with its wall label: title, artist, date, medium, movement and
 * the museum that holds it, and a link to its page there. Its full image is fetched as the page
 * opens, so Paint This usually has it at once; tapped before, the button waits for it, and a
 * failure says so. A work whose painting is under way offers Resume instead, which opens it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MuseumWorkScreen(id: String, library: Library, onPaint: (Picked) -> Unit, onResume: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val museum = remember { MuseumLibrary.of(context) }
    val state by museum.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { museum.open() }
    val catalog = (state as? MuseumLibrary.State.Ready)?.catalog
    val work = catalog?.work(id)
    val paintings by library.paintings.collectAsStateWithLifecycle()
    val underWay = remember(paintings, catalog, work) {
        if (catalog == null || work == null) return@remember null
        val shelf = paintings.filter { it.sampleName == null && it.finished == null }.sortedBy { maxOf(it.updated, it.created) }
            .associate { MuseumPainted.key(it.title) to it.id }
        if (MuseumPainted.matches(work, shelf.keys, museum.recorded(), catalog.sharedNames)) shelf[MuseumPainted.key(work.paintingTitle)] else null
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            if (work == null) {
                if (state is MuseumLibrary.State.Loading) ContainedLoadingIndicator(Modifier.align(Alignment.Center))
                else Text("This work isn’t in the Museum any more.", modifier = Modifier.align(Alignment.Center).padding(32.dp))
            } else {
                WorkPage(work, catalog.museums[work.museum].orEmpty(), underWay, onPaint = {
                    onPaint(Picked.Museum(work.id, work.paintingTitle, work.image))
                }, onResume = onResume)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WorkPage(work: MuseumCatalog.Work, museum: String, underWay: String?, onPaint: () -> Unit, onResume: (String) -> Unit) {
    val context = LocalContext.current
    val images = remember { MuseumImages.of(context) }
    var picture by remember(work.id) { mutableStateOf<ImageBitmap?>(null) }
    var fetched by remember(work.id) { mutableStateOf(false) }
    var failed by remember(work.id) { mutableStateOf(false) }
    var wantsToPaint by remember(work.id) { mutableStateOf(false) }
    var showsFailure by remember { mutableStateOf(false) }
    var attempt by remember(work.id) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val uri = LocalUriHandler.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val width = (maxWidth - if (wide) 64.dp else 40.dp).coerceIn(1.dp, 680.dp)
        val height = (maxHeight * 0.6f).coerceAtLeast(1.dp)
        val ratio = work.aspectRatio
        val (w, h) = if (width / height > ratio) height * ratio to height else width to width / ratio
        val pixels = with(density) { maxOf(w, h).roundToPx() }.coerceAtLeast(900)

        // The tile's picture at once, then the full image, which the page shows and Paint This paints.
        LaunchedEffect(work.id, attempt) {
            if (picture == null) images.picture(work.thumb, 900)?.let { picture = it.asImageBitmap() }
            try {
                val file = images.file(work.image)
                val large = withContext(Dispatchers.Default) { runCatching { Pictures.decodeSampled({ file.inputStream() }, pixels) }.getOrNull() }
                if (large == null) {
                    images.forget(work.image)
                    throw IOException("${work.image} isn't a picture")
                }
                picture = large.asImageBitmap()
                fetched = true
                if (wantsToPaint) {
                    wantsToPaint = false
                    onPaint()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
                if (wantsToPaint) {
                    wantsToPaint = false
                    showsFailure = true
                }
            }
        }

        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 32.dp else 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(12.dp))
                Picture(
                    picture, work.average,
                    Modifier.size(w, h).shadow(14.dp, RoundedCornerShape(2.dp)).clip(RoundedCornerShape(2.dp))
                        .semantics { contentDescription = work.title; role = Role.Image },
                )
                Spacer(Modifier.height(28.dp))
                Label(work, museum, Modifier.widthIn(max = 680.dp).fillMaxWidth()) { page ->
                    // A phone with no browser to open it says nothing rather than closing the app.
                    try {
                        uri.openUri(page)
                    } catch (_: ActivityNotFoundException) {
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            Button(
                onClick = {
                    when {
                        underWay != null -> onResume(underWay)
                        fetched -> onPaint()
                        failed -> {
                            failed = false
                            wantsToPaint = true
                            attempt++
                        }
                        else -> wantsToPaint = true
                    }
                },
                enabled = !wantsToPaint,
                modifier = Modifier.align(Alignment.CenterHorizontally).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)
                    .widthIn(max = 420.dp).fillMaxWidth().height(56.dp),
            ) {
                if (wantsToPaint) {
                    LoadingIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Getting the Picture…", style = MaterialTheme.typography.titleMedium)
                } else {
                    Icon(Icons.Rounded.Brush, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(if (underWay != null) "Resume" else "Paint This", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
    if (showsFailure) {
        AlertDialog(
            onDismissRequest = { showsFailure = false },
            title = { Text("Couldn’t Get This Picture") },
            text = { Text("Check your connection, then try again.") },
            confirmButton = { TextButton(onClick = { showsFailure = false }) { Text("OK") } },
        )
    }
}

/** The wall label, the museum's own words. */
@Composable
private fun Label(work: MuseumCatalog.Work, museum: String, modifier: Modifier, onPage: (String) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(work.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(work.artist, style = MaterialTheme.typography.titleMedium)
        val secondary = MaterialTheme.colorScheme.onSurfaceVariant
        if (work.year.isNotEmpty()) Text(work.year, color = secondary)
        if (work.medium.isNotEmpty()) Text(work.medium, style = MaterialTheme.typography.bodyMedium, color = secondary)
        Text(work.movement, style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), color = secondary)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (museum.isNotEmpty()) Text(museum, style = MaterialTheme.typography.bodySmall, color = secondary)
        work.page?.takeIf { it.startsWith("https://") }?.let { page ->
            TextButton(onClick = { onPage(page) }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("On the Museum’s Website", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                }
            }
        }
    }
}
