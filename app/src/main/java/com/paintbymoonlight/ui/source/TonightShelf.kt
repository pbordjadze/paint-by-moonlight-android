package com.paintbymoonlight.ui.source

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paintbymoonlight.Unreleased
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.pictures.Sample
import com.paintbymoonlight.pictures.SampleLibrary
import java.time.ZoneId

/**
 * Something new to paint on the gallery itself, under the painter's own paintings, as iOS's
 * shelf: Tonight's Picture while the gallery doesn't hold it; while the gallery holds only a few
 * paintings, the nights after it fill out its row (in debug builds with the museums' schedule,
 * the earlier nights), and More to Paint opens the Paint page. The tiles take the cards' own
 * columns, so no library picture outshines the painter's own.
 *
 * It also asks for the museums' schedule (debug builds) each time the app comes back.
 */
@Composable
fun TonightShelf(library: Library, onPaint: (Sample) -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val nights = remember { Unreleased.nights(context) }
    if (nights != null) {
        LifecycleResumeEffect(Unit) {
            nights.refreshSoon()
            onPauseOrDispose {}
        }
    }
    val revision = nightsRevision(nights)
    val today = rememberToday()
    val paintings by library.paintings.collectAsStateWithLifecycle()
    val held = remember(paintings) { paintings.mapNotNull { it.sampleName }.toSet() }
    val hasRoom = paintings.size < FEW_PAINTINGS
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = maxOf(1, ((maxWidth + 14.dp) / (160.dp + 14.dp)).toInt())
        // The pictures, and whether the first is tonight's (its crescent, its heading) or the earlier nights begin.
        val (shown, earlier) = remember(revision, today, held, hasRoom, columns) {
            val count = if (hasRoom) columns else 1
            val night = nights?.night(today)
            when {
                night == null -> SampleLibrary.of(context).nights(today, ZoneId.systemDefault(), count, held) to false
                night.id !in held -> listOf(night) to false
                hasRoom -> nights.earlierNights(today).map { it.sample }.filter { it.id !in held }.take(columns) to true
                else -> emptyList<Sample>() to false
            }
        }
        if (shown.isEmpty()) return@BoxWithConstraints
        Column(Modifier.padding(top = 18.dp)) {
            Text(
                if (earlier) "Earlier Nights" else "Tonight’s Picture",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 4.dp, bottom = 14.dp).semantics { heading() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (i in 0 until columns) {
                    val sample = shown.getOrNull(i)
                    if (sample == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        val isTonight = i == 0 && !earlier
                        SampleTile(
                            sample, onClick = { onPaint(sample) }, modifier = Modifier.weight(1f),
                            state = if (isTonight) "Tonight’s Picture" else null,
                            badge = { if (isTonight) TonightMoon() },
                        )
                    }
                }
            }
            if (hasRoom) {
                FilledTonalButton(onClick = onMore, modifier = Modifier.padding(top = 16.dp)) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("More to Paint")
                }
            }
        }
    }
}

/** The gallery holds only a few paintings: room for the nights after tonight. */
private const val FEW_PAINTINGS = 4
