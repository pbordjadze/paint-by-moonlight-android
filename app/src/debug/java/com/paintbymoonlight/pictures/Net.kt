package com.paintbymoonlight.pictures

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Plain HTTPS GETs for the pictures: what the museums and the schedule serve, to a file. */
internal object Net {
    private const val AGENT = "PaintByMoonlight-Android/1.0"

    /**
     * Downloads [url] into [target] through a temporary file beside it, so a cancelled or failed
     * fetch never leaves half a file. HTTPS only, at most [limit] bytes.
     */
    suspend fun download(url: String, target: File, limit: Long = 64L shl 20, timeoutMillis: Int = 45_000) =
        withContext(Dispatchers.IO) {
            if (!url.startsWith("https://")) throw IOException("not https: $url")
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = timeoutMillis
                connection.readTimeout = timeoutMillis
                connection.setRequestProperty("User-Agent", AGENT)
                connection.useCaches = false
                if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode} for $url")
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, "${target.name}.part${System.nanoTime()}")
                try {
                    var total = 0L
                    connection.inputStream.use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > limit) throw IOException("more than $limit bytes at $url")
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    if (total == 0L) throw IOException("empty response for $url")
                    if (!temp.renameTo(target)) throw IOException("couldn't keep $target")
                } finally {
                    temp.delete()
                }
            } finally {
                connection.disconnect()
            }
        }
}
