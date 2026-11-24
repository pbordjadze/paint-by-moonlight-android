package com.paintbymoonlight.print

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A painting's One Big Sheet order keys, by product, kept in its folder from the moment a try
 * of an order starts until the relay has placed it or certainly given it up. A painter who leaves
 * mid order, or whose app is closed, tries the same order again under the same key, and the
 * relay answers it without charging twice.
 */
class PendingOrders(folder: File) {
    private val file = File(folder, FILE)

    suspend fun load(): Map<String, String> = lock.withLock { read() }

    /** Records [key] as [product]'s order under way. */
    suspend fun put(product: BigSheetProduct, key: String) = lock.withLock {
        write(read() + (product.id to key))
    }

    /** Forgets [product]'s order, if [key] is still the one recorded. */
    suspend fun remove(product: BigSheetProduct, key: String) = lock.withLock {
        val keys = read()
        if (keys[product.id] == key) write(keys - product.id)
    }

    private suspend fun read(): Map<String, String> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyMap()
        runCatching { json.decodeFromString<Map<String, String>>(file.readText()) }.getOrDefault(emptyMap())
    }

    private suspend fun write(keys: Map<String, String>) = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) {
            file.delete()
            return@withContext
        }
        val temp = File(file.parentFile, "$FILE.tmp")
        temp.writeText(json.encodeToString(keys))
        if (!temp.renameTo(file)) temp.delete()
    }

    companion object {
        private const val FILE = "print-orders.json"
        private val json = Json
        private val lock = Mutex()

        /**
         * Where a sheet's upload and its order run: the app's, not the screen's, so leaving the
         * screen mid order never drops a payment the relay may already be charging.
         */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
