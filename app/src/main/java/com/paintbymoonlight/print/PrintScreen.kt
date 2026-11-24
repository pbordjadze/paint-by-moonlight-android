package com.paintbymoonlight.print

import android.graphics.Bitmap
import android.icu.util.LocaleData
import android.icu.util.ULocale
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paintbymoonlight.ui.theme.Moonlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Print (the iOS app's Print's preview): the pages as they will come out of the printer, how big
 * the picture prints (the fewest legible sheets, or across more) and what prints over the lines
 * (numbers, color hints, the color key). Print hands the pages to the system's dialog, which asks
 * for the printer and paper (Save as PDF among them); Share sends the PDF itself.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PrintScreen(model: PrintViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
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
                            onClick = { model.share(context) },
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
    HorizontalPager(pager, Modifier.fillMaxWidth().height(340.dp), key = { "$it·${document.choices}" }) { index ->
        val image by produceState<Bitmap?>(null, document, index) {
            value = withContext(Dispatchers.Default) { document.pageImage(index, 1100) }
        }
        Box(Modifier.fillMaxSize().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f)
                    .shadow(8.dp, RoundedCornerShape(1.dp))
                    .background(Moonlight.sheet)
                    .semantics { contentDescription = caption(document, index) },
                contentAlignment = Alignment.Center,
            ) {
                val picture = image
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

/** The sizes offered, a connected button group: the sheets each takes and how big the picture prints. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Sizes(sizes: List<Sheets.Size>, chosen: Int?, onSelect: (Int?) -> Unit) {
    val selected = sizes.firstOrNull { it.across == chosen } ?: sizes.firstOrNull()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        sizes.forEachIndexed { index, size ->
            val sheets = if (size.sheets.count == 1) "One Page" else "${size.sheets.count} sheets"
            ToggleButton(
                checked = size == selected,
                onCheckedChange = { onSelect(size.across) },
                shapes = when {
                    sizes.size == 1 -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    index == sizes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(sheets, maxLines = 1)
                    Text(sizeText(size.sheets), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

/** What prints over the lines, which always print. */
@Composable
private fun Layers(model: PrintViewModel) {
    val choices = model.choices
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Layer("Numbers", choices.numbers, model::toggleNumbers)
        Layer("Color Hints", choices.colorHints, model::toggleColorHints)
        Layer("Color Key", choices.colorKey, model::toggleColorKey)
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
