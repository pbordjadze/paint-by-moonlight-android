package com.paintbymoonlight.museum

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.paintbymoonlight.pictures.Net
import com.paintbymoonlight.pictures.Pictures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * The Museum's pictures, fetched from the museums' servers: tiles, a work's large picture and the
 * full image a painting is made from. Every fetch is kept on disk (the cache folder's
 * Museum/Images, the oldest let go past [DISK_LIMIT]), so a tile scrolled past and back, or a
 * work opened again, comes from the device; decoded tiles are kept in memory. One fetch per
 * picture at a time, six at once, as iOS asks of the museums.
 */
class MuseumImages private constructor(private val folder: File) {
    private val memory = object : LruCache<String, Bitmap>(64 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val connections = Semaphore(6)
    private val inFlight = HashMap<String, Deferred<File>>()
    /** Fetches that finish whatever asked for them: a tile scrolled away still lands on disk for the way back. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { trim() }
    }

    /** A tile already decoded at [maxSide], at once, for the first frame of a tile scrolled back. */
    fun cached(url: String, maxSide: Int): Bitmap? = memory.get("$url@$maxSide")

    /** The picture at [url] at most [maxSide] px on its long side; null when it can't be fetched or decoded. */
    suspend fun picture(url: String, maxSide: Int): Bitmap? {
        val key = "$url@$maxSide"
        memory.get(key)?.let { return it }
        val file = runCatching { file(url) }.getOrNull() ?: return null
        return withContext(Dispatchers.Default) {
            runCatching { Pictures.decodeSampled({ file.inputStream() }, maxSide) }.getOrNull()
        }?.also { memory.put(key, it) }
    }

    /** The bytes at [url] on disk, fetched now if they aren't there yet. */
    suspend fun file(url: String): File {
        val target = target(url)
        if (target.exists()) return target.also { it.setLastModified(System.currentTimeMillis()) }
        val fetch = synchronized(inFlight) {
            inFlight.getOrPut(url) {
                scope.async {
                    try {
                        connections.withPermit { if (!target.exists()) Net.download(url, target) }
                        target
                    } finally {
                        synchronized(inFlight) { inFlight.remove(url) }
                    }
                }
            }
        }
        return fetch.await()
    }

    /** Fetches [urls] ahead of the scroll, quietly: the next screenful of tiles comes from disk. */
    fun prefetch(urls: List<String>) {
        for (url in urls) if (!target(url).exists()) scope.launch { runCatching { file(url) } }
    }

    /** Lays [bytes] in the disk cache as [url]'s, as if fetched (the tour's own works). */
    internal fun seed(url: String, bytes: ByteArray) {
        target(url).apply { parentFile?.mkdirs() }.writeBytes(bytes)
    }

    private fun target(url: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(folder, digest)
    }

    private fun trim() {
        val files = folder.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) {
            total += f.length()
            if (total > DISK_LIMIT) f.delete()
        }
    }

    companion object {
        private const val DISK_LIMIT = 512L shl 20

        @Volatile private var shared: MuseumImages? = null

        fun of(context: Context): MuseumImages = shared ?: synchronized(this) {
            shared ?: MuseumImages(File(context.applicationContext.cacheDir, "Museum/Images").apply { mkdirs() }).also { shared = it }
        }
    }
}
