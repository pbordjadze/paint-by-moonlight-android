package com.paintbymoonlight.ui.source

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.paintbymoonlight.Unreleased
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.pictures.Pictures
import com.paintbymoonlight.pictures.Sample
import com.paintbymoonlight.pictures.SampleKind
import com.paintbymoonlight.pictures.SampleLibrary
import com.paintbymoonlight.ui.create.Picked
import com.paintbymoonlight.ui.theme.raised
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private object PageRoutes {
    const val PAGE = "page"
    const val CREDITS = "credits"
}

/**
 * New Painting's first step, the Paint page, as iOS's: Tonight's Picture large at the top, then
 * Your Own (a photo, the camera and, in debug builds, the Museum), then The Library's paintings
 * and photographs; in debug builds, once the museums' schedule is here, the earlier nights in
 * the library's place. A picture picked goes to the create screen ([onPicked]); the Museum's
 * Resume opens a painting under way ([onResume]). The Museum and the pictures' credits open
 * over the page, in its own navigation.
 */
@Composable
fun PaintPage(library: Library, onPicked: (Picked) -> Unit, onResume: (String) -> Unit, onClose: () -> Unit) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = PageRoutes.PAGE) {
        composable(PageRoutes.PAGE) {
            PaintSources(
                onPicked = onPicked,
                onMuseum = { nav.navigate(Unreleased.MUSEUM) { launchSingleTop = true } },
                onCredits = { nav.navigate(PageRoutes.CREDITS) { launchSingleTop = true } },
                onClose = onClose,
            )
        }
        composable(PageRoutes.CREDITS) { PictureCredits(onBack = { nav.popBackStack() }) }
        Unreleased.museumRoutes(this, nav, library, onPicked, onResume)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PaintSources(onPicked: (Picked) -> Unit, onMuseum: () -> Unit, onCredits: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val samples = remember { SampleLibrary.of(context) }
    val nights = remember { Unreleased.nights(context) }
    val revision = nightsRevision(nights)
    val today = rememberToday()
    val tonight = remember(revision, today) { Pictures.tonight(context, today) }
    val earlier = remember(revision, today) {
        if (nights?.night(today) != null) nights.earlierNights(today) else emptyList()
    }

    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPicked(Picked.Photo(uri.toString()))
    }
    var shot by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = shot
        shot = null
        if (uri == null) return@rememberLauncherForActivityResult
        // A capture called off leaves nothing behind; a photo taken goes once its painting is saved (CreateViewModel).
        if (taken) onPicked(Picked.Photo(uri)) else Uri.parse(uri).lastPathSegment?.let { File(cameraFolder(context), it).delete() }
    }
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val takePhoto: () -> Unit = {
        val file = File(cameraFolder(context), "photo-${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        shot = uri.toString()
        try {
            camera.launch(uri)
        } catch (_: ActivityNotFoundException) {
            shot = null
            file.delete()
        }
    }
    val pick = { sample: Sample -> onPicked(Picked.Sample(sample.id)) }
    val window = LocalConfiguration.current.screenHeightDp

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paint") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (tonight != null) {
                heading("Tonight’s Picture")
                item(span = { GridItemSpan(maxLineSpan) }, key = "tonight") {
                    // Never taller than about 45% of the window, so Your Own shows beneath it held sideways too.
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        SampleTile(
                            tonight, onClick = { pick(tonight) },
                            modifier = Modifier.widthIn(max = (window * 0.45f * 4f / 3f).dp),
                            badge = { TonightMoon() },
                        )
                    }
                }
            }
            heading("Your Own")
            item(span = { GridItemSpan(maxLineSpan) }, key = "own") {
                // One row of them where the width allows, else wrapped: every way in shows.
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val count = 1 + (if (hasCamera) 1 else 0) + (if (Unreleased.ON) 1 else 0)
                    val least = OWN_TILE_WIDTH * LocalDensity.current.fontScale.coerceAtLeast(1f)
                    val perRow = ((maxWidth + 12.dp) / (least + 12.dp)).toInt().coerceIn(1, count)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        maxItemsInEachRow = perRow,
                    ) {
                        // Equal heights in a row, so a wrapped name doesn't make one card taller.
                        val tile = Modifier.weight(1f).fillMaxRowHeight()
                        OwnTile("A Photo", Icons.Rounded.PhotoLibrary, modifier = tile) {
                            photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        if (hasCamera) OwnTile("Take Photo", Icons.Rounded.PhotoCamera, modifier = tile, onClick = takePhoto)
                        if (Unreleased.ON) {
                            OwnTile("Museum", Icons.Rounded.AccountBalance, hint = "Browse thousands of artworks to paint", modifier = tile, onClick = onMuseum)
                        }
                    }
                }
            }
            if (earlier.isNotEmpty()) {
                heading("Earlier Nights")
                item(span = { GridItemSpan(maxLineSpan) }, key = "earlier") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(earlier, key = { it.day }) { night ->
                            val label = whenText(night.day, today)
                            Column(Modifier.width(180.dp)) {
                                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                                SampleTile(night.sample, onClick = { pick(night.sample) }, state = label)
                            }
                        }
                    }
                }
            } else {
                heading("The Library")
                for (kind in SampleKind.entries) {
                    val shown = samples.all(kind)
                    if (shown.isEmpty()) continue
                    subheading(if (kind == SampleKind.Painting) "Art" else "Photographs")
                    items(shown, key = { it.id }) { sample -> SampleTile(sample, onClick = { pick(sample) }) }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "credits") {
                TextButton(onClick = onCredits, modifier = Modifier.fillMaxWidth()) { Text("Picture credits") }
            }
        }
    }
}

private fun LazyGridScope.heading(text: String) = item(span = { GridItemSpan(maxLineSpan) }, key = "h-$text") {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp).semantics { heading() },
    )
}

private fun LazyGridScope.subheading(text: String) = item(span = { GridItemSpan(maxLineSpan) }, key = "s-$text") {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = 4.dp, top = 4.dp).semantics { heading() },
    )
}

/** One of Your Own's ways in: a small card with its symbol and name. */
@Composable
private fun OwnTile(title: String, icon: ImageVector, modifier: Modifier = Modifier, hint: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.raised),
        modifier = modifier
            .heightIn(min = 86.dp)
            .clip(shape)
            .clickable(indication = ripple(), interactionSource = null, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = if (hint != null) "$title. $hint" else title
                role = Role.Button
            },
    ) {
        Column(Modifier.padding(12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

/** Your Own's least tile width at the default text size: three to a phone's row. */
private val OWN_TILE_WIDTH = 100.dp

/** Where Take Photo's camera app writes (`file_paths.xml`'s `camera`). */
internal fun cameraFolder(context: Context) = File(context.cacheDir, "camera")

/** An earlier night as the painter says it: "Last Night", a weekday this past week, a date before that. */
internal fun whenText(day: String, today: LocalDate, locale: Locale = Locale.getDefault()): String {
    val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return day
    val days = ChronoUnit.DAYS.between(date, today)
    return when {
        days == 1L -> "Last Night"
        days < 7 -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        else -> date.format(DateTimeFormatter.ofPattern("MMM d", locale))
    }
}
