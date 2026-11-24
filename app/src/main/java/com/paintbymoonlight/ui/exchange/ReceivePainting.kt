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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.data.ReceiveException
import com.paintbymoonlight.data.SharedPaintings
import kotlinx.coroutines.launch

/**
 * The file an intent opens the app with, if it brings one (a `.moonlight` file someone sent,
 * viewed or shared to the app); none when the app is reopened from Recents, which delivers the
 * intent that first opened it again.
 */
fun openedFile(intent: Intent?): Uri? = if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) null else when (intent.action) {
    Intent.ACTION_VIEW -> intent.data
    Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    else -> null
}

/**
 * Adds the painting in [file] (a `.moonlight` file someone sent) to the library, then [onAdded]
 * opens it; when it can't be added, a calm alert says why, in the iOS app's words. Its model
 * outlives a configuration change (a receive under way finishes and is opened) and remembers in
 * its saved state that the file was handled, so an activity restored after the process was
 * killed doesn't add or open it again.
 */
@Composable
fun ReceivePainting(file: Uri?, library: Library, resolver: ContentResolver, onAdded: (String) -> Unit) {
    if (file == null) return
    val model = viewModel { ReceiveViewModel(createSavedStateHandle(), library, resolver, file) }
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

private class ReceiveViewModel(state: SavedStateHandle, library: Library, resolver: ContentResolver, file: Uri) : ViewModel() {
    /** The painting added, until it has been opened. */
    var added by mutableStateOf<String?>(null)
    var failure by mutableStateOf<String?>(null)

    init {
        if (state.get<Boolean>(HANDLED) != true) {
            viewModelScope.launch {
                try {
                    added = SharedPaintings.receive(library, resolver, file)
                } catch (e: ReceiveException) {
                    failure = e.message
                }
                state[HANDLED] = true
            }
        }
    }

    private companion object {
        const val HANDLED = "handled"
    }
}
