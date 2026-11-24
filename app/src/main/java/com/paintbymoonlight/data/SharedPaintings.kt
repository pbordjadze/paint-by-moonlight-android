package com.paintbymoonlight.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.paintbymoonlight.core.Core
import com.paintbymoonlight.core.CoreException
import com.paintbymoonlight.core.SharedPainting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.max
import kotlin.random.Random

/** Why a painting someone sent couldn't be added; its message is what the painter reads (the iOS app's words). */
class ReceiveException(val reason: Reason) : Exception(reason.message) {
    enum class Reason(val message: String) {
        /** Sent from a newer app, in a format this one can't read. */
        NeedsNewerApp("This painting was sent from a newer version of Paint by Moonlight. Update the app to open it."),
        /** Not a painting, or a damaged one. */
        Damaged("This file may be damaged, or it isn’t a painting. Ask whoever sent it to send it again."),
        SaveFailed("The painting couldn’t be saved on this device. Free up some space and open it again."),
    }
}

/**
 * Paintings sent between painters as `.moonlight` files ([SharedPainting]), as the iOS app's
 * Send Template… and its library's `receive` do: a painting sent is its template, title,
 * settings, paint names and, by the painter's choice, photo; a painting received is a new
 * painting, blank, its paints named as the sender's are.
 */
object SharedPaintings {
    /** A painting's file is a few megabytes; one past this isn't one an app wrote. */
    private const val MAX_FILE_BYTES = 64 * 1024 * 1024
    private const val MAX_PHOTO_SIDE = 2048
    private const val TAG = "SharedPaintings"

    /** One file added at a time, so the same file opened twice adds one painting. */
    private val receiving = Mutex()

    /**
     * Adds the painting in [uri] to [library] and returns its id. The same file opened again (the
     * attachment tapped twice) opens the painting it made rather than adding a copy. Throws
     * [ReceiveException].
     */
    suspend fun receive(library: Library, resolver: ContentResolver, uri: Uri): String = receiving.withLock {
        val bytes = withContext(Dispatchers.IO) {
            try {
                read(resolver, uri)
            } catch (e: Exception) {
                // Whatever the sending app's provider throws (it may be anyone's): not a file to open.
                Log.e(TAG, "couldn't read the file", e)
                null
            }
        } ?: throw ReceiveException(ReceiveException.Reason.Damaged)
        val digest = withContext(Dispatchers.Default) {
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
        library.awaitLoaded()
        library.paintings.value.firstOrNull { it.receivedDigest == digest }?.let { return@withLock it.id }

        val shared = try {
            SharedPainting.read(bytes)
        } catch (e: CoreException) {
            Log.e(TAG, "a received painting couldn't be read: status ${e.status}")
            throw ReceiveException(if (e.status == 4) ReceiveException.Reason.NeedsNewerApp else ReceiveException.Reason.Damaged)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "a received painting is too large to read", e)
            throw ReceiveException(ReceiveException.Reason.Damaged)
        }
        val photo = shared.photo?.let { decodePhoto(it) }
        if (shared.photo != null && photo == null) Log.e(TAG, "a received painting's photo couldn't be decoded")

        // The sender's names, by a vocabulary this build knows; a file without them, names of its own.
        val seed = shared.nicknameSeed ?: Random.nextLong()
        val version = shared.nicknameVersion?.coerceAtMost(Core.NICKNAME_VERSION) ?: Core.NICKNAME_VERSION
        val scene = try {
            Core.scene(shared.template, seed, version)
        } catch (e: CoreException) {
            throw ReceiveException(ReceiveException.Reason.Damaged)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "a received painting is too large to draw", e)
            throw ReceiveException(ReceiveException.Reason.Damaged)
        }
        try {
            val thumbnail = withContext(Dispatchers.Default) { Thumbnails.render(scene, Progress(scene.regions.size)) }
            val meta = PaintingMeta(
                id = Library.newId(),
                title = shared.title.ifEmpty { titleOf(resolver, uri) ?: "Painting" },
                created = System.currentTimeMillis(),
                length = "",
                seed = seed,
                pipelineVersion = Core.pipelineVersion,
                settings = shared.settingsJson,
                regionCount = scene.regions.size,
                nicknameVersion = version,
                aspect = scene.width / scene.height,
                receivedDigest = digest,
            )
            library.add(meta, shared.template, photo, thumbnail).id
        } catch (e: IOException) {
            Log.e(TAG, "saving a received painting failed", e)
            throw ReceiveException(ReceiveException.Reason.SaveFailed)
        }
    }

    /**
     * Writes painting [id]'s `.moonlight` file, with its photo when [includesPhoto], into the
     * app's cache for the share sheet (the last one sent is cleared first). Throws when the
     * painting can't be read or written.
     */
    suspend fun prepare(context: Context, library: Library, id: String, includesPhoto: Boolean): File {
        val meta = library.meta(id) ?: throw IOException("no painting $id")
        val template = library.template(id)
        val photo = if (includesPhoto) withContext(Dispatchers.IO) { photoFile(library, id).takeIf { it.isFile }?.readBytes() } else null
        val file = SharedPainting.write(template, meta.title, meta.settings, photo, meta.seed, meta.nicknameVersion)
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, SENDING).apply { deleteRecursively(); mkdirs() }
            File(dir, "${fileName(meta.title)} Template.${SharedPainting.EXTENSION}").apply { writeBytes(file) }
        }
    }

    /** Whether painting [id] has a photo to send. */
    suspend fun hasPhoto(library: Library, id: String): Boolean = withContext(Dispatchers.IO) { photoFile(library, id).isFile }

    /** The cache folder [prepare] writes in (res/xml/file_paths.xml shares it). */
    private const val SENDING = "sending"

    private fun photoFile(library: Library, id: String) = File(library.folder(id), Library.PHOTO)

    /**
     * The title as a file's name: letters, digits and a little punctuation, at most
     * [MAX_NAME_BYTES] of UTF-8 so " Template.moonlight" still fits in a file system's 255 bytes.
     */
    private fun fileName(title: String): String {
        val cleaned = title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim()
        val name = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < cleaned.length) {
            val codePoint = cleaned.codePointAt(i)
            val size = String(Character.toChars(codePoint)).encodeToByteArray().size
            if (bytes + size > MAX_NAME_BYTES) break
            name.appendCodePoint(codePoint)
            bytes += size
            i += Character.charCount(codePoint)
        }
        return name.toString().trim().ifBlank { "Painting" }
    }

    private const val MAX_NAME_BYTES = 120

    private fun read(resolver: ContentResolver, uri: Uri): ByteArray? {
        val input = resolver.openInputStream(uri) ?: return null
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        input.use { stream ->
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_FILE_BYTES) return null
            }
        }
        return out.toByteArray()
    }

    /** The file's name without its extension, the title of a painting sent without one. */
    private fun titleOf(resolver: ContentResolver, uri: Uri): String? {
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
        return name?.substringBeforeLast(".${SharedPainting.EXTENSION}")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun decodePhoto(bytes: ByteArray): Bitmap? = withContext(Dispatchers.Default) {
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val long = max(info.size.width, info.size.height)
                if (long > MAX_PHOTO_SIDE) {
                    val k = MAX_PHOTO_SIDE.toFloat() / long
                    decoder.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: Exception) {
            null
        }
    }
}
