package com.paintbymoonlight.ui.source

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.paintbymoonlight.Unreleased
import com.paintbymoonlight.pictures.Nights
import com.paintbymoonlight.pictures.Sample
import com.paintbymoonlight.pictures.SampleLibrary

/**
 * The pictures' credits, as iOS's Acknowledgements lists them: each picture's title, who made it
 * and when, the collection its file comes from and its license, verbatim as its record has them;
 * the library's, then (debug builds) the nights' on this device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PictureCredits(onBack: () -> Unit) {
    val context = LocalContext.current
    val pictures = remember {
        val library = SampleLibrary.of(context).all
        val nights = if (Unreleased.ON) Nights.of(context).let { n -> n.schedule?.pictures?.keys.orEmpty().sorted().mapNotNull(n::sample) } else emptyList()
        library + nights
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Picture credits") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            items(pictures, key = { it.id }) { CreditRow(it) }
        }
    }
}

@Composable
private fun CreditRow(sample: Sample) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics(mergeDescendants = true) {}) {
        Text(sample.title, style = MaterialTheme.typography.titleMedium)
        Text(sample.provenance.byline, style = MaterialTheme.typography.bodyMedium)
        Text(sample.provenance.credit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(sample.provenance.license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}
