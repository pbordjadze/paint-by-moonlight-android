package com.paintbymoonlight.ui.paint

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paintbymoonlight.core.Core
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.data.PaintingMeta
import com.paintbymoonlight.data.Thumbnails
import com.paintbymoonlight.data.UnreadableProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Opens a painting and keeps its progress saved: a second after the last fill, and on leaving.
 * Where the canvas looks is saved with it, but moving the canvas alone waits for the next save.
 */
class PaintViewModel(private val library: Library, val id: String) : ViewModel() {
    sealed interface State {
        data object Loading : State
        data class Ready(val meta: PaintingMeta, val session: PaintSession) : State
        data class Failed(val message: String) : State
    }

    var state by mutableStateOf<State>(State.Loading)
        private set

    private var saveJob: Job? = null
    /** Fills or the paint in hand changed since the last save: progress and thumbnail. */
    private var dirty = false
    /** Only the view moved: progress alone. */
    private var viewMoved = false
    private val saving = Mutex()

    init {
        viewModelScope.launch {
            state = try {
                val meta = library.meta(id) ?: error("This painting is gone.")
                val scene = Core.scene(library.template(id), meta.seed, meta.nicknameVersion)
                val progress = library.progress(id, scene.regions.size)
                State.Ready(meta, PaintSession(scene, progress))
            } catch (e: UnreadableProgress) {
                // Nothing is saved from a painting that failed to open, so its progress file stays as it was.
                Log.e("PaintViewModel", "progress unreadable", e)
                State.Failed("This painting can’t be opened right now. Its progress is kept as it was.")
            } catch (e: Exception) {
                State.Failed(e.message ?: "This painting couldn't be opened.")
            }
        }
    }

    /** Call after every change to the painting. */
    fun changed() {
        dirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1000)
            save()
        }
    }

    /** Call when the canvas settles somewhere new. */
    fun viewChanged() {
        viewMoved = true
    }

    /**
     * Writes what changed. One save runs at a time, so a save that returns has everything
     * before it on disk; a failed one leaves the painting marked unsaved for the next.
     */
    private suspend fun save() = saving.withLock {
        val ready = state as? State.Ready ?: return@withLock
        if (!dirty && !viewMoved) return@withLock
        val withThumbnail = dirty
        dirty = false
        viewMoved = false
        val progress = ready.session.progress.copy()
        val snapshot = progress.snapshot()
        withContext(NonCancellable) {
            try {
                library.save(id, snapshot, if (withThumbnail) { { Thumbnails.render(ready.session.scene, progress) } } else null)
            } catch (e: IOException) {
                Log.e("PaintViewModel", "save failed", e)
                if (withThumbnail) dirty = true else viewMoved = true
            }
        }
    }

    /** Saves now; join the job to act on what is on disk (sharing, printing). */
    fun saveNow(): Job {
        saveJob?.cancel()
        return viewModelScope.launch { save() }
    }

    override fun onCleared() {
        // viewModelScope is gone by now: the last save runs on the library's own.
        val ready = state as? State.Ready ?: return
        if (!dirty && !viewMoved) return
        val progress = ready.session.progress.copy()
        library.saveDetached(id, progress.snapshot(), if (dirty) { { Thumbnails.render(ready.session.scene, progress) } } else null)
    }
}
