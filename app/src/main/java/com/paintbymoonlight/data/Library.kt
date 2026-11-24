package com.paintbymoonlight.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** What a painting records beside its template. */
@Serializable
data class PaintingMeta(
    val id: String,
    val title: String,
    val created: Long,
    val length: String,
    /** Seeds the pipeline and the paints' nicknames. */
    val seed: Long,
    val pipelineVersion: Int,
    /** The settings' JSON as PaintCore records it (GenerationSettings). */
    val settings: String,
    val regionCount: Int = 0,
    val paintedCount: Int = 0,
    val finished: Long? = null,
)

/**
 * A painter's paintings, one folder each in the app's files: meta.json, template.pbnt (the
 * template, byte for byte as iOS saves it), progress.bin ([Progress]), photo.jpg, thumb.png.
 * Writes go to a temporary file renamed into place, so a crash never leaves half a file.
 */
class Library(context: Context) {
    private val root = File(context.filesDir, "paintings").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val _paintings = MutableStateFlow<List<PaintingMeta>>(emptyList())

    /** Newest first. */
    val paintings: StateFlow<List<PaintingMeta>> = _paintings.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _paintings.value = root.listFiles().orEmpty().mapNotNull { dir ->
            runCatching { json.decodeFromString<PaintingMeta>(File(dir, META).readText()) }.getOrNull()
        }.sortedByDescending { it.created }
    }

    fun folder(id: String) = File(root, id)

    suspend fun add(meta: PaintingMeta, template: ByteArray, photo: Bitmap, thumbnail: Bitmap): PaintingMeta =
        withContext(Dispatchers.IO) {
            val dir = folder(meta.id).apply { mkdirs() }
            File(dir, TEMPLATE).writeAtomically(template)
            File(dir, PHOTO).writeAtomically { photo.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            File(dir, THUMB).writeAtomically { thumbnail.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(dir, META).writeAtomically(json.encodeToString(meta).toByteArray())
            _paintings.value = listOf(meta) + _paintings.value.filter { it.id != meta.id }
            meta
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

    /** Saves progress, the thumbnail as it now looks, and the counts the gallery shows. */
    suspend fun save(id: String, progress: Progress.Snapshot, thumbnail: Bitmap?) = withContext(Dispatchers.IO) {
        val dir = folder(id)
        File(dir, PROGRESS).writeAtomically(progress.bytes)
        thumbnail?.let { t -> File(dir, THUMB).writeAtomically { t.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        update(id) {
            it.copy(
                regionCount = progress.regionCount,
                paintedCount = progress.paintedCount,
                finished = if (progress.isComplete) it.finished ?: System.currentTimeMillis() else null,
            )
        }
    }

    suspend fun rename(id: String, title: String) = withContext(Dispatchers.IO) { update(id) { it.copy(title = title) } }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        folder(id).deleteRecursively()
        _paintings.value = _paintings.value.filter { it.id != id }
    }

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

        fun newId(): String = UUID.randomUUID().toString()
    }
}

private fun File.writeAtomically(bytes: ByteArray) = writeAtomically { it.write(bytes) }

private fun File.writeAtomically(write: (java.io.OutputStream) -> Unit) {
    val temp = File(parentFile, "$name.tmp")
    temp.outputStream().use { out ->
        write(out)
        out.flush()
        out.fd.sync()
    }
    if (!temp.renameTo(this)) {
        temp.delete()
        error("could not save $name")
    }
}
