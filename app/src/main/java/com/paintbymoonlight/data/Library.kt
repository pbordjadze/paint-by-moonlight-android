package com.paintbymoonlight.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

/** What a painting records beside its template. */
@Serializable
data class PaintingMeta(
    val id: String,
    val title: String,
    val created: Long,
    /** The Painting Length it was made for; empty for a painting someone sent. */
    val length: String,
    /** Seeds the pipeline and the paints' nicknames. */
    val seed: Long,
    val pipelineVersion: Int,
    /** The settings' JSON as PaintCore records it (GenerationSettings). */
    val settings: String,
    val regionCount: Int = 0,
    val paintedCount: Int = 0,
    val finished: Long? = null,
    /**
     * The nickname vocabulary the paints were named from. Absent means 2: every painting this
     * app made before the field existed was named by version 2.
     */
    val nicknameVersion: Int = 2,
    /** When progress was last saved (the thumbnail with it), so the gallery knows to reload it. */
    val updated: Long = 0,
    /** Width over height of the painting; 0 in a meta saved before it was recorded. */
    val aspect: Float = 0f,
    /** The SHA-256 (hex) of the `.moonlight` file it was received from, so opening it again opens it (SharedPaintings.kt). */
    val receivedDigest: String? = null,
)

/**
 * A painter's paintings, one folder each in the app's files: meta.json, template.pbnt (the
 * template, byte for byte as iOS saves it), progress.bin ([Progress]), photo.jpg, thumb.png.
 * Writes go to a temporary file renamed into place, so a crash never leaves half a file, and
 * run one at a time, so a save, a rename and a delete never interleave.
 *
 * The list loads once, when the app starts; everything that looks a painting up waits for it,
 * so a screen restored after the process was killed finds its painting.
 */
class Library(context: Context) {
    private val root = File(context.filesDir, "paintings").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _paintings = MutableStateFlow<List<PaintingMeta>>(emptyList())
    private val _loaded = MutableStateFlow(false)
    private val ready = CompletableDeferred<Unit>()
    private val writes = Mutex()
    /** The newest progress written for each painting, so an older save that lost the race never overwrites it. */
    private val newestSaved = mutableMapOf<String, Long>()

    /** Saves that outlive the screen that asked for them; a failure is logged, never thrown. */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e(TAG, "save failed", e) })

    /** Newest first. */
    val paintings: StateFlow<List<PaintingMeta>> = _paintings.asStateFlow()

    /** False until the list is read, so the gallery doesn't flash its empty page. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch {
            try {
                _paintings.value = root.listFiles().orEmpty().mapNotNull(::read).sortedByDescending { it.created }
            } finally {
                _loaded.value = true
                ready.complete(Unit)
            }
        }
    }

    private fun read(dir: File): PaintingMeta? {
        // Temporary files a crash left behind mid-write.
        dir.listFiles { f -> f.name.endsWith(TEMP) }?.forEach { it.delete() }
        val meta = runCatching { json.decodeFromString<PaintingMeta>(File(dir, META).readText()) }.getOrNull() ?: return null
        if (meta.aspect > 0f) return meta
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(File(dir, THUMB).path, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) meta.copy(aspect = bounds.outWidth / bounds.outHeight.toFloat()) else meta
    }

    /** Waits until the list is read. */
    suspend fun awaitLoaded() = ready.await()

    /** A painting's meta, once the list is read; null if there is no such painting. */
    suspend fun meta(id: String): PaintingMeta? {
        awaitLoaded()
        return _paintings.value.firstOrNull { it.id == id }
    }

    fun folder(id: String) = File(root, id)

    /** [photo] is null for a painting someone sent without theirs. */
    suspend fun add(meta: PaintingMeta, template: ByteArray, photo: Bitmap?, thumbnail: Bitmap): PaintingMeta {
        awaitLoaded()
        return withContext(Dispatchers.IO) {
            writes.withLock {
                val dir = folder(meta.id).apply { mkdirs() }
                File(dir, TEMPLATE).writeAtomically(template)
                photo?.let { p -> File(dir, PHOTO).writeAtomically { p.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
                File(dir, THUMB).writeAtomically { thumbnail.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(dir, META).writeAtomically(json.encodeToString(meta).toByteArray())
                _paintings.value = listOf(meta) + _paintings.value.filter { it.id != meta.id }
                meta
            }
        }
    }

    suspend fun template(id: String): ByteArray = withContext(Dispatchers.IO) { File(folder(id), TEMPLATE).readBytes() }

    suspend fun progress(id: String, regions: Int): Progress = withContext(Dispatchers.IO) {
        val file = File(folder(id), PROGRESS)
        if (file.exists()) Progress.decode(file.readBytes(), regions) ?: Progress(regions) else Progress(regions)
    }

    suspend fun photo(id: String): Bitmap? = withContext(Dispatchers.IO) {
        BitmapFactory.decodeFile(File(folder(id), PHOTO).path)
    }

    fun thumbnailFile(id: String) = File(folder(id), THUMB)

    /**
     * Saves progress, the thumbnail as it now looks (if given), and the counts the gallery
     * shows. A painting deleted meanwhile is left gone, and a snapshot older than one already
     * saved is dropped. Throws [IOException] when the disk refuses.
     */
    suspend fun save(id: String, progress: Progress.Snapshot, thumbnail: (() -> Bitmap)?) {
        awaitLoaded()
        withContext(Dispatchers.IO) {
            writes.withLock {
                val dir = folder(id)
                if (!dir.isDirectory || _paintings.value.none { it.id == id }) return@withLock
                if (progress.taken < (newestSaved[id] ?: Long.MIN_VALUE)) return@withLock
                File(dir, PROGRESS).writeAtomically(progress.bytes)
                thumbnail?.let { draw ->
                    val t = draw()
                    File(dir, THUMB).writeAtomically { t.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                newestSaved[id] = progress.taken
                update(id) {
                    it.copy(
                        regionCount = progress.regionCount,
                        paintedCount = progress.paintedCount,
                        finished = if (progress.isComplete) it.finished ?: System.currentTimeMillis() else null,
                        updated = System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    /** [save] from somewhere that can't wait for it (a screen being torn down). */
    fun saveDetached(id: String, progress: Progress.Snapshot, thumbnail: (() -> Bitmap)?) {
        scope.launch { save(id, progress, thumbnail) }
    }

    /** Renames a painting; false if the disk refused. */
    suspend fun rename(id: String, title: String): Boolean {
        awaitLoaded()
        return withContext(Dispatchers.IO) {
            writes.withLock {
                try {
                    update(id) { it.copy(title = title) }
                    true
                } catch (e: IOException) {
                    Log.e(TAG, "rename failed", e)
                    false
                }
            }
        }
    }

    suspend fun delete(id: String) {
        awaitLoaded()
        withContext(Dispatchers.IO) {
            writes.withLock {
                folder(id).deleteRecursively()
                newestSaved.remove(id)
                _paintings.value = _paintings.value.filter { it.id != id }
            }
        }
    }

    /** Called with [writes] held. */
    private fun update(id: String, change: (PaintingMeta) -> PaintingMeta) {
        val current = _paintings.value.firstOrNull { it.id == id } ?: return
        val next = change(current)
        File(folder(id), META).writeAtomically(json.encodeToString(next).toByteArray())
        _paintings.value = _paintings.value.map { if (it.id == id) next else it }
    }

    companion object {
        const val META = "meta.json"
        const val TEMPLATE = "template.pbnt"
        const val PROGRESS = "progress.bin"
        const val PHOTO = "photo.jpg"
        const val THUMB = "thumb.png"
        private const val TEMP = ".tmp"
        private const val TAG = "Library"

        fun newId(): String = UUID.randomUUID().toString()
    }
}

private fun File.writeAtomically(bytes: ByteArray) = writeAtomically { it.write(bytes) }

/** Writes to a temporary file of its own and renames it into place; throws [IOException] on failure. */
private fun File.writeAtomically(write: (java.io.OutputStream) -> Unit) {
    val temp = File.createTempFile(name, ".tmp", parentFile)
    try {
        temp.outputStream().use { out ->
            write(out)
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(this)) throw IOException("could not save $name")
    } finally {
        temp.delete()
    }
}
