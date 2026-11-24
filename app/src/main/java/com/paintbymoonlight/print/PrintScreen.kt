package com.paintbymoonlight.print

import android.content.Intent
import android.graphics.Bitmap
import android.icu.util.LocaleData
import android.icu.util.ULocale
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paintbymoonlight.ui.theme.Moonlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/**
 * Print (the iOS app's Print's preview): the pages as they will come out of the printer, how big
 * the picture prints (the fewest legible sheets, or across more) and what prints over the lines
 * (numbers, which bring the color key, and color hints), and One Big Sheet where it is offered.
 * Print hands the pages to the system's dialog, which asks for the printer and paper (Save as
 * PDF among them); Share sends the PDF itself.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PrintScreen(model: PrintViewModel, onBack: () -> Unit, onBigSheet: (() -> Unit)? = null) {
    val context = LocalContext.current
    LaunchedEffect(model) {
        model.shared.collect { shared ->
            val uri = shared.uri
            if (uri == null) {
                Toast.makeText(context, "The pages couldn’t be shared.", Toast.LENGTH_SHORT).show()
                return@collect
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, model.title))
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Print") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            if (!model.loadFailed) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = model::share,
                            enabled = model.document != null && !model.sharing,
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) {
                            Icon(Icons.Rounded.Share, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Share PDF")
                        }
                        Button(
                            onClick = { model.print(context) },
                            enabled = model.document != null,
                            modifier = Modifier.weight(1.4f).height(56.dp),
                        ) {
                            Icon(Icons.Rounded.Print, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Print")
                        }
                    }
                }
            }
        },
    ) { padding ->
        val document = model.document
        when {
            model.loadFailed -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text("This painting couldn’t be opened.", style = MaterialTheme.typography.bodyLarge)
            }
            document == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Pages(document)
                Spacer(Modifier.height(16.dp))
                Label("Size")
                Sizes(model.sizes, model.choices.across, model::chooseSize)
                Spacer(Modifier.height(16.dp))
                Label("Layers")
                Layers(model)
                Spacer(Modifier.height(16.dp))
                if (onBigSheet != null) {
                    BigSheetRow(onBigSheet)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** The pages side by side, one at a time, each on white paper whatever the screen's appearance. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Pages(document: TemplatePrint) {
    val pager = rememberPagerState { document.pageCount }
    val aspect = document.pageWidth / document.pageHeight.toFloat()
    // The last few full pages, so swiping back shows one at once, without a quick pass first.
    val drawn = remember(document) { LruCache<Int, Bitmap>(KEPT_PAGES) }
    HorizontalPager(pager, Modifier.fillMaxWidth().height(340.dp), key = { "$it·${document.choices}" }) { index ->
        val image by produceState(drawn[index]?.let { it to true }, document, index) {
            if (value != null) return@produceState
            // Choices changed under a drawing: it stops where it is rather than finishing a stale page.
            val job = coroutineContext.job
            // A quick small pass first, for the page in view only, so it shows at once.
            if (index == pager.currentPage) {
                value = withContext(Dispatchers.Default) { document.pageImage(index, QUICK_SIDE) { !job.isActive } } to false
            }
            val full = withContext(Dispatchers.Default) { document.pageImage(index, FULL_SIDE) { !job.isActive } }
            drawn.put(index, full)
            value = full to true
        }
        Box(Modifier.fillMaxSize().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f)
                    .shadow(8.dp, RoundedCornerShape(1.dp))
                    .background(Moonlight.sheet)
                    .semantics {
                        contentDescription = caption(document, index)
                        if (image?.second != true) stateDescription = "Loading"
                    },
                contentAlignment = Alignment.Center,
            ) {
                val picture = image?.first
                if (picture != null) {
                    val bitmap = remember(picture) { picture.asImageBitmap() }
                    Image(bitmap, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                } else {
                    LoadingIndicator(Modifier.size(32.dp), color = Color.Gray)
                }
            }
        }
    }
    Text(
        caption(document, pager.currentPage.coerceAtMost(document.pageCount - 1)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The preview's quick first pass and its full drawing, in px on the page's long side. */
private const val QUICK_SIDE = 360
private const val FULL_SIDE = 1100
private const val KEPT_PAGES = 4

/** Which page, and what it is: the sheets laid together, or its place among the pages. */
private fun caption(document: TemplatePrint, index: Int): String =
    if (document.pages[index] == TemplatePrint.PageKind.Overview) "How the sheets tape together · ${sizeText(document.sheets)}"
    else "Page ${index + 1} of ${document.pageCount}"

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
    )
}

/**
 * The sizes offered, a connected button group: the sheets each takes and how big the picture
 * prints. At a large font they stand one above another, full width, so neither line clips.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Sizes(sizes: List<Sheets.Size>, chosen: Int?, onSelect: (Int?) -> Unit) {
    val selected = sizes.firstOrNull { it.across == chosen } ?: sizes.firstOrNull()
    val stacked = LocalDensity.current.fontScale >= 1.5f
    @Composable
    fun Option(index: Int, size: Sheets.Size, modifier: Modifier) {
        val sheets = if (size.sheets.count == 1) "One Page" else "${size.sheets.count} sheets"
        ToggleButton(
            checked = size == selected,
            onCheckedChange = { onSelect(size.across) },
            shapes = when {
                stacked || sizes.size == 1 -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                index == sizes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            },
            modifier = modifier.semantics { role = Role.RadioButton },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(sheets, textAlign = TextAlign.Center, maxLines = 2)
                Text(sizeText(size.sheets), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
    if (stacked) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            sizes.forEachIndexed { index, size -> Option(index, size, Modifier.fillMaxWidth()) }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            sizes.forEachIndexed { index, size -> Option(index, size, Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}

/**
 * One Big Sheet, as iOS offers it from Print's preview: the template printed whole on one
 * sheet and mailed.
 */
@Composable
private fun BigSheetRow(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Wallpaper, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("One Big Sheet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Printed full size and mailed to you",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What prints over the lines, which always print; the color key comes with the numbers. */
@Composable
private fun Layers(model: PrintViewModel) {
    val choices = model.choices
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Layer("Numbers", choices.numbers, model::toggleNumbers)
        Layer("Color Hints", choices.colorHints, model::toggleColorHints)
    }
}

@Composable
private fun Layer(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
        } else null,
    )
}

/** The printed picture's size, in inches where they are used, centimetres elsewhere. */
private fun sizeText(sheets: Sheets): String {
    val inches = LocaleData.getMeasurementSystem(ULocale.getDefault()) == LocaleData.MeasurementSystem.US
    fun length(points: Float): String {
        val value = points / 72f * (if (inches) 1f else 2.54f)
        return Math.round(value).toString()
    }
    return "${length(sheets.printedWidth)} × ${length(sheets.printedHeight)} ${if (inches) "in" else "cm"}"
}
