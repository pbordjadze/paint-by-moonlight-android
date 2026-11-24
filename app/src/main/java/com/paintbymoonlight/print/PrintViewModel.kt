package com.paintbymoonlight.print

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paintbymoonlight.core.Core
import com.paintbymoonlight.core.Scene
import com.paintbymoonlight.data.Library
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Print's preview (the iOS app's `PrintPreviewSheet`): a painting's printable pages laid out on
 * the region's paper, turned the picture's way, with the painter's choice of size and layers,
 * kept across the process being recreated. [app] is the application's context, which outlives
 * the screen: the PDF is written with it and handed to the screen to share.
 */
class PrintViewModel(
    private val app: Context,
    private val library: Library,
    private val id: String,
    private val paper: Paper,
    private val saved: SavedStateHandle,
) : ViewModel() {
    var title by mutableStateOf("")
        private set
    var scene by mutableStateOf<Scene?>(null)
        private set
    var loadFailed by mutableStateOf(false)
        private set
    var choices by mutableStateOf(
        PrintChoices(
            across = saved.get<Int>(ACROSS),
            numbers = saved.get<Boolean>(NUMBERS) ?: true,
            colorHints = saved.get<Boolean>(HINTS) ?: false,
        ),
    )
        private set
    /** The document the preview shows, for [choices]. */
    var document by mutableStateOf<TemplatePrint?>(null)
        private set
    var sharing by mutableStateOf(false)
        private set

    /** What a share came to: the PDF to share, or null when it couldn't be written. */
    class Shared(val uri: Uri?)

    private val shares = Channel<Shared>(Channel.BUFFERED)
    val shared: Flow<Shared> = shares.receiveAsFlow()

    init {
        viewModelScope.launch {
            try {
                val meta = library.meta(id) ?: error("no painting $id")
                title = meta.title
                scene = Core.scene(library.template(id), meta.seed, meta.nicknameVersion)
                layOut()
                // A size kept from before may not be offered here.
                if (choices.across != null && sizes.none { it.across == choices.across }) choose(choices.copy(across = null))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Loading a painting to print failed", e)
                loadFailed = true
            }
        }
    }

    /** The sizes offered for the layers chosen, smallest first. */
    val sizes: List<Sheets.Size> get() = document?.sizes.orEmpty()

    fun chooseSize(across: Int?) = choose(choices.copy(across = across))

    fun toggleNumbers() = choose(choices.copy(numbers = !choices.numbers))

    fun toggleColorHints() = choose(choices.copy(colorHints = !choices.colorHints))

    private fun choose(next: PrintChoices) {
        choices = next
        layOut()
        // A size no longer offered goes back to the smallest, so the row says what prints.
        if (next.across != null && sizes.none { it.across == next.across }) {
            choices = next.copy(across = null)
            layOut()
        }
        saved[ACROSS] = choices.across
        saved[NUMBERS] = choices.numbers
        saved[HINTS] = choices.colorHints
    }

    private fun layOut() {
        val scene = scene ?: return
        document = TemplatePrint(scene, title, choices, paper)
    }

    /** The system's print dialog on the pages chosen. */
    fun print(context: Context) {
        val scene = scene ?: return
        TemplatePrintAdapter.print(context, scene, title, choices)
    }

    /** Writes the pages as a PDF to share, for whoever prints it elsewhere or hasn't the app; [shared] says when. */
    fun share() {
        val document = document ?: return
        if (sharing) return
        sharing = true
        viewModelScope.launch {
            val uri = try {
                val file = withContext(Dispatchers.Default) {
                    val dir = File(app.cacheDir, "shared").apply { mkdirs() }
                    File(dir, TemplatePrintAdapter.fileName(title)).also { out -> out.outputStream().use { document.write(it) } }
                }
                FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Writing the template's PDF to share failed", e)
                null
            } finally {
                sharing = false
            }
            shares.send(Shared(uri))
        }
    }

    companion object {
        private const val TAG = "Print"
        private const val ACROSS = "across"
        private const val NUMBERS = "numbers"
        private const val HINTS = "colorHints"
    }
}
