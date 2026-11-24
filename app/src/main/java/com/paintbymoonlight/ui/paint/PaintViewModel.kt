package com.paintbymoonlight.ui.paint

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paintbymoonlight.core.Core
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.data.PaintingMeta
import com.paintbymoonlight.data.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens a painting and keeps its progress saved: a second after the last fill, and on leaving. */
class PaintViewModel(private val library: Library, val id: String) : ViewModel() {
    sealed interface State {
        data object Loading : State
        data class Ready(val meta: PaintingMeta, val session: PaintSession) : State
        data class Failed(val message: String) : State
    }

    var state by mutableStateOf<State>(State.Loading)
        private set

    private var saveJob: Job? = null
    private var dirty = false

    init {
        viewModelScope.launch {
            state = try {
                val meta = library.paintings.value.firstOrNull { it.id == id } ?: error("This painting is gone.")
                val scene = Core.scene(library.template(id), meta.seed)
                val progress = library.progress(id, scene.regions.size)
                State.Ready(meta, PaintSession(scene, progress))
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

    suspend fun save() {
        val ready = state as? State.Ready ?: return
        if (!dirty) return
        dirty = false
        val snapshot = ready.session.progress.snapshot()
        withContext(NonCancellable) {
            val thumb = withContext(Dispatchers.Default) { Thumbnails.render(ready.session.scene, ready.session.progress) }
            library.save(id, snapshot, thumb)
        }
    }

    fun saveNow() {
        viewModelScope.launch { save() }
    }

    override fun onCleared() {
        // viewModelScope is gone by now: the last save runs on its own.
        val ready = state as? State.Ready ?: return
        if (!dirty) return
        val snapshot = ready.session.progress.snapshot()
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            library.save(id, snapshot, Thumbnails.render(ready.session.scene, ready.session.progress))
        }
    }
}
