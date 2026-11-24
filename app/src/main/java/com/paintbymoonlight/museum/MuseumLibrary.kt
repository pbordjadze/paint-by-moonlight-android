package com.paintbymoonlight.museum

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.paintbymoonlight.pictures.Net
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The catalog as the app holds it: the copy kept in the cache folder read at once, a fresh one
 * fetched when there is none or it is a day old (as iOS does). A copy that can't be fetched or
 * read is no failure while an older one is kept.
 */
class MuseumLibrary private constructor(private val folder: File, private val prefs: SharedPreferences) {
    sealed interface State {
        data object Loading : State
        class Ready(val catalog: MuseumCatalog) : State
        data object Failed : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val file get() = File(folder, "museum.json")

    /** Loads the kept catalog and fetches a newer one when it is due. Again while it runs, or once ready and fresh, it does nothing. */
    @Synchronized
    fun open() {
        if (job?.isActive == true || _state.value is State.Ready) return
        job = scope.launch { load() }
    }

    /** After a failure: try the network again. */
    @Synchronized
    fun retry() {
        if (job?.isActive == true) return
        _state.value = State.Loading
        job = scope.launch { load() }
    }

    /** Puts [catalog] in place of the network's (the tour's own works). */
    internal fun use(catalog: MuseumCatalog) {
        job?.cancel()
        _state.value = State.Ready(catalog)
    }

    private suspend fun load() {
        val kept = withContext(Dispatchers.IO) {
            runCatching { file.inputStream().buffered().use(MuseumCatalog::decode) to file.lastModified() }.getOrNull()
        }
        if (kept != null) _state.value = State.Ready(kept.first)
        if (kept != null && System.currentTimeMillis() - kept.second < 24 * 3600_000L) return
        try {
            val incoming = File(folder, "museum.json.new")
            Net.download(MuseumCatalog.SOURCE, incoming, limit = 256L shl 20, timeoutMillis = 60_000)
            val catalog = withContext(Dispatchers.IO) {
                val catalog = incoming.inputStream().buffered().use(MuseumCatalog::decode)
                if (!incoming.renameTo(file)) incoming.delete()
                catalog
            }
            _state.value = State.Ready(catalog)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the museum's catalog didn't load", e)
            if (kept == null) _state.value = State.Failed
        }
    }

    /** The works whose Paint This was chosen ([MuseumPainted]). */
    fun recorded(): Set<String> = prefs.getStringSet(PAINTED, emptySet()).orEmpty()

    fun record(id: String) {
        val ids = recorded()
        if (id !in ids) prefs.edit { putStringSet(PAINTED, ids + id) }
    }

    companion object {
        private const val TAG = "Museum"
        private const val PAINTED = "paintedWorks"

        @Volatile private var shared: MuseumLibrary? = null

        fun of(context: Context): MuseumLibrary = shared ?: synchronized(this) {
            shared ?: MuseumLibrary(
                File(context.applicationContext.cacheDir, "Museum").apply { mkdirs() },
                context.applicationContext.getSharedPreferences("museum", Context.MODE_PRIVATE),
            ).also { shared = it }
        }
    }
}
