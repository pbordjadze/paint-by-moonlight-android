package com.paintbymoonlight.ui.museum

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.data.PaintingMeta
import com.paintbymoonlight.museum.MuseumCatalog
import com.paintbymoonlight.museum.MuseumImages
import com.paintbymoonlight.museum.MuseumLibrary
import com.paintbymoonlight.museum.MuseumPainted
import com.paintbymoonlight.museum.MuseumQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * The Museum (debug builds only, as iOS's is behind `#if UNRELEASED`; painting-company row 369):
 * the catalog's works hung on the page, each whole at its own shape, in the order and under the
 * movement the painter picks, found by artist, title or year. A work opens its page, its wall
 * label and Paint This. The app's own chrome is the bar, the search, the movements' row and one
 * menu; a work's label is on its page, so the wall is pictures alone.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MuseumScreen(library: Library, onWork: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val museum = remember { MuseumLibrary.of(context) }
    val state by museum.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { museum.open() }

    var movement by rememberSaveable { mutableStateOf<String?>(null) }
    var kinds by rememberSaveable { mutableStateOf(MuseumQuery.Kinds.Everything) }
    var order by rememberSaveable { mutableStateOf(MuseumQuery.Order.Colorful) }
    var search by rememberSaveable { mutableStateOf("") }
    // The wall follows the search once typing pauses: each key would sort 31,000 works again.
    var searched by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(search) {
        if (search.isNotEmpty()) delay(250)
        searched = search
    }
    var seed by rememberSaveable { mutableStateOf(1L) }
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Museum") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close") } },
                actions = {
                    if (state is MuseumLibrary.State.Ready) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "Sort and Show") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                MenuSection("Order")
                                for (o in MuseumQuery.Order.entries) {
                                    Choice(o.label, o == order) {
                                        // Each Shuffle a new order.
                                        if (o == MuseumQuery.Order.Shuffled) seed = Random.nextLong(1, Long.MAX_VALUE)
                                        order = o
                                        menu = false
                                    }
                                }
                                HorizontalDivider()
                                MenuSection("Show")
                                for (k in MuseumQuery.Kinds.entries) Choice(k.label, k == kinds) { kinds = k; menu = false }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when (val s = state) {
                MuseumLibrary.State.Loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    ContainedLoadingIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Opening the Museum…", style = MaterialTheme.typography.bodyLarge)
                }
                MuseumLibrary.State.Failed -> Column(
                    Modifier.align(Alignment.Center).padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Rounded.AccountBalance, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Can’t Reach the Museum", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("Check your connection, then try again.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { museum.retry() }) { Text("Try Again") }
                }
                is MuseumLibrary.State.Ready -> {
                    val query = MuseumQuery(movement, kinds, order, searched, seed)
                    Wall(
                        catalog = s.catalog,
                        query = query,
                        library = library,
                        search = search,
                        onSearch = { search = it },
                        onMovement = { movement = it },
                        onWork = onWork,
                        bottom = padding.calculateBottomPadding(),
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuSection(title: String) {
    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun Choice(label: String, chosen: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = { if (chosen) Icon(Icons.Rounded.Check, contentDescription = null) },
        modifier = Modifier.semantics { selected = chosen },
    )
}

/** The painter's paintings by name: the finished, and the newest under way of each name. A painting of a library picture is the library's, whatever its name. */
private fun shelf(paintings: List<PaintingMeta>): Pair<Set<String>, Map<String, String>> {
    val finished = HashSet<String>()
    val underWay = HashMap<String, String>()
    for (p in paintings.filter { it.sampleName == null }.sortedBy { maxOf(it.updated, it.created) }) {
        val name = MuseumPainted.key(p.title)
        if (p.finished != null) finished.add(name) else underWay[name] = p.id
    }
    return finished to underWay
}

@Composable
private fun Wall(
    catalog: MuseumCatalog,
    query: MuseumQuery,
    library: Library,
    search: String,
    onSearch: (String) -> Unit,
    onMovement: (String?) -> Unit,
    onWork: (String) -> Unit,
    bottom: Dp,
) {
    val context = LocalContext.current
    val images = remember { MuseumImages.of(context) }
    val museum = remember { MuseumLibrary.of(context) }
    val shown by produceState<List<MuseumCatalog.Work>?>(null, catalog, query) {
        value = withContext(Dispatchers.Default) { query.works(catalog) }
    }
    val counts = remember(catalog, query.kinds) { query.counts(catalog) }
    val paintings by library.paintings.collectAsStateWithLifecycle()
    val finished = remember(paintings) { shelf(paintings).first }
    val recorded = remember(paintings) { museum.recorded() }
    val grid = rememberLazyGridState()
    // A new movement, order or kind starts at the top; coming back from a work keeps the place.
    val arrangement = listOf(query.movement, query.order.name, query.kinds.name, query.seed.toString())
    var arranged by rememberSaveable { mutableStateOf(arrangement) }
    LaunchedEffect(arrangement) {
        if (arranged != arrangement) {
            arranged = arrangement
            grid.scrollToItem(0)
        }
    }
    // The next screenful's tiles fetched ahead of the scroll, so they come from the device.
    LaunchedEffect(grid, shown) {
        val works = shown ?: return@LaunchedEffect
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> images.prefetch(works.drop(last + 1).take(PREFETCH).map { it.thumb }) }
    }

    Column(Modifier.fillMaxSize()) {
        SearchField(search, onSearch)
        Movements(catalog.movements, counts, query.movement, onMovement)
        val works = shown
        when {
            works == null -> Unit
            works.isEmpty() -> Text(
                if (query.search.isBlank()) "Nothing to show here." else "No Results for “${query.search.trim()}”",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
            )
            else -> BoxWithConstraints {
                val wide = maxWidth >= 600.dp
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (wide) 170.dp else 104.dp),
                    state = grid,
                    contentPadding = PaddingValues(start = if (wide) 32.dp else 20.dp, end = if (wide) 32.dp else 20.dp, top = 8.dp, bottom = bottom + 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (wide) 20.dp else 10.dp),
                    verticalArrangement = Arrangement.spacedBy(if (wide) 24.dp else 12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(works, key = { it.id }) { work ->
                        WorkTile(
                            work = work,
                            painted = MuseumPainted.matches(work, finished, recorded, catalog.sharedNames),
                            images = images,
                            onClick = { onWork(work.id) },
                        )
                    }
                }
            }
        }
    }
}

private const val PREFETCH = 30

@Composable
private fun SearchField(text: String, onChange: (String) -> Unit) {
    TextField(
        value = text,
        onValueChange = onChange,
        placeholder = { Text("Artists, titles, years") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (text.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Clear, contentDescription = "Clear search") }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** All, then each movement that holds a work of the kind shown, the chosen one filled. */
@Composable
private fun Movements(movements: List<String>, counts: Map<String, Int>, chosen: String?, onChoose: (String?) -> Unit) {
    val shown = remember(movements, counts) { listOf<String?>(null) + movements.filter { (counts[it] ?: 0) > 0 } }
    val row = rememberLazyListState()
    LaunchedEffect(Unit) { shown.indexOf(chosen).takeIf { it > 0 }?.let { row.scrollToItem(it) } }
    LazyRow(
        state = row,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = Modifier.semantics { contentDescription = "Movements" },
    ) {
        items(shown, key = { it ?: "" }) { m ->
            FilterChip(selected = m == chosen, onClick = { onChoose(m) }, label = { Text(m ?: "All") })
        }
    }
}

/**
 * A work on the wall: the picture whole at its own shape, standing on a shared line at the foot
 * of a square, its mean color while it comes. One the painter has painted hangs in a little
 * frame, a mat inside a dark wood moulding.
 */
@Composable
private fun WorkTile(work: MuseumCatalog.Work, painted: Boolean, images: MuseumImages, onClick: () -> Unit) {
    BoxWithConstraints(
        Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = listOf(work.title, work.artist, work.year).filter { it.isNotEmpty() }.joinToString(", ")
                role = Role.Button
                if (painted) stateDescription = "Painted"
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        val moulding = if (painted) (maxWidth * 0.035f).coerceIn(3.dp, 7.dp) else 0.dp
        val mat = if (painted) (moulding * 0.7f).coerceAtLeast(2.dp) else 0.dp
        val inset = (moulding + mat) * 2
        val box = maxWidth - inset
        val ratio = work.aspectRatio
        val (w, h) = if (ratio >= 1f) box to box / ratio else box * ratio to box
        val pixels = with(LocalDensity.current) { min(maxWidth, maxHeight).roundToPx() }.coerceAtMost(600)
        val image by produceState(images.cached(work.thumb, pixels)?.asImageBitmap(), work.id) {
            if (value == null) value = images.picture(work.thumb, pixels)?.asImageBitmap()
        }
        Box(
            Modifier
                .shadow(if (painted) 6.dp else 4.dp, RectangleShape)
                .then(if (painted) Modifier.background(MOULDING).border(0.75.dp, Color.White.copy(alpha = 0.22f)) else Modifier)
                .padding(moulding)
                .then(if (painted) Modifier.background(MAT) else Modifier)
                .padding(mat),
        ) {
            Picture(image, work.average, Modifier.size(w, h).clip(if (painted) RectangleShape else RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
internal fun Picture(image: ImageBitmap?, average: String, modifier: Modifier) {
    Box(modifier.background(averageColor(average) ?: MaterialTheme.colorScheme.surfaceContainerHigh)) {
        AnimatedVisibility(image != null, enter = fadeIn(tween(250)), modifier = Modifier.fillMaxSize()) {
            image?.let { Image(it, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
        }
    }
}

/** A color from the catalog's `rrggbb`; null when it doesn't read. */
internal fun averageColor(hex: String): Color? {
    if (hex.length != 6) return null
    val value = hex.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or value)
}

private val MAT = Color(0xFFF7F2E8)
private val MOULDING = Brush.linearGradient(listOf(Color(0xFF6B4A2E), Color(0xFF40291A)))
