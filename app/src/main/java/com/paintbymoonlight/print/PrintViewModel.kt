package com.paintbymoonlight.print

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paintbymoonlight.core.Core
import com.paintbymoonlight.core.Scene
import com.paintbymoonlight.data.Library
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Print's preview (the iOS app's `PrintPreviewSheet`): a painting's printable pages laid out on
 * the region's paper, turned the picture's way, with the painter's choice of size and layers.
 */
class PrintViewModel(private val library: Library, private val id: String, private val paper: Paper) : ViewModel() {
    var title by mutableStateOf("")
        private set
    var scene by mutableStateOf<Scene?>(null)
        private set
    var loadFailed by mutableStateOf(false)
        private set
    var choices by mutableStateOf(PrintChoices())
        private set
    /** The document the preview shows, for [choices]. */
    var document by mutableStateOf<TemplatePrint?>(null)
        private set
    var sharing by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            try {
                val meta = library.meta(id) ?: error("no painting $id")
                title = meta.title
                scene = Core.scene(library.template(id), meta.seed, meta.nicknameVersion)
                layOut()
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

    /** Numbers off is a coloring page: the key has nothing to look up and goes with them, and comes back with them. */
    fun toggleNumbers() = choose(choices.copy(numbers = !choices.numbers, colorKey = !choices.numbers))

    fun toggleColorHints() = choose(choices.copy(colorHints = !choices.colorHints))

    fun toggleColorKey() = choose(choices.copy(colorKey = !choices.colorKey))

    private fun choose(next: PrintChoices) {
        choices = next
        layOut()
        // A size no longer offered goes back to the smallest, so the row says what prints.
        if (next.across != null && sizes.none { it.across == next.across }) {
            choices = next.copy(across = null)
            layOut()
        }
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

    /** Shares the pages as a PDF, for whoever prints it elsewhere or hasn't the app. */
    fun share(context: Context) {
        val document = document ?: return
        if (sharing) return
        sharing = true
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.Default) {
                    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                    File(dir, TemplatePrintAdapter.fileName(title)).also { out -> out.outputStream().use { document.write(it) } }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, title))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Writing the template's PDF to share failed", e)
                Toast.makeText(context, "The pages couldn’t be shared.", Toast.LENGTH_SHORT).show()
            } finally {
                sharing = false
            }
        }
    }

    companion object {
        private const val TAG = "Print"
    }
}
