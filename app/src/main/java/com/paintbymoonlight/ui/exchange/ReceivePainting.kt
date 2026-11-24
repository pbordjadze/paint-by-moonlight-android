package com.paintbymoonlight.ui.exchange

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.data.ReceiveException
import com.paintbymoonlight.data.SharedPaintings
import kotlinx.coroutines.launch

/** The file an intent opens the app with, if it brings one (a `.moonlight` file someone sent, viewed or shared to the app). */
fun openedFile(intent: Intent?): Uri? = when (intent?.action) {
    Intent.ACTION_VIEW -> intent.data
    Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    else -> null
}

/**
 * Adds the painting in [file] (a `.moonlight` file someone sent) to the library, then [onAdded]
 * opens it; when it can't be added, a calm alert says why, in the iOS app's words. Survives a
 * configuration change without adding it twice.
 */
@Composable
fun ReceivePainting(file: Uri?, library: Library, resolver: ContentResolver, onAdded: (String) -> Unit) {
    if (file == null) return
    val model = viewModel { ReceiveViewModel(library, resolver, file) }
    LaunchedEffect(model.added) {
        model.added?.let { id ->
            model.added = null
            onAdded(id)
        }
    }
    model.failure?.let { message ->
        AlertDialog(
            onDismissRequest = { model.failure = null },
            title = { Text("Couldn’t Open the Painting") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { model.failure = null }) { Text("OK") } },
        )
    }
}

private class ReceiveViewModel(library: Library, resolver: ContentResolver, file: Uri) : ViewModel() {
    /** The painting added, until it has been opened. */
    var added by mutableStateOf<String?>(null)
    var failure by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch {
            try {
                added = SharedPaintings.receive(library, resolver, file)
            } catch (e: ReceiveException) {
                failure = e.message
            }
        }
    }
}
