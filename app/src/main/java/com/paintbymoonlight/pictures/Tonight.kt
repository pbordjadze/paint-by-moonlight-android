package com.paintbymoonlight.pictures

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.paintbymoonlight.Unreleased
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneId

/** Where the pictures that aren't the painter's own are looked up: the library, and in debug builds the nights. */
object Pictures {
    /**
     * The Paint page's and the gallery's Tonight's Picture: in debug builds the museums'
     * schedule's night first ([Nights]), the same for every painter; otherwise, or offline with
     * nothing downloaded, the library's turn ([SampleLibrary.tonight]).
     */
    fun tonight(context: Context, date: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Sample? {
        if (Unreleased.ON) Nights.of(context).night(date)?.let { return it }
        return SampleLibrary.of(context).tonight(date, zone)
    }

    /** The picture a painting was made from, by its id; null for one neither the library nor the schedule holds now. */
    fun named(context: Context, id: String): Sample? =
        SampleLibrary.of(context).named(id) ?: if (Unreleased.ON) Nights.of(context).sample(id) else null

    private val tiles = object : LruCache<String, Bitmap>(32 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** A picture's tile, at most [maxSide] px on its long side (a night's from its thumbnail); null if it can't be read. */
    suspend fun tile(context: Context, sample: Sample, maxSide: Int = 640): Bitmap? {
        val key = "${sample.id}@$maxSide"
        tiles.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val night = sample.night
                val open: () -> InputStream =
                    if (night != null) ({ night.thumbnail.inputStream() }) else ({ context.assets.open(SampleLibrary.asset(sample.id)) })
                decodeSampled(open, maxSide)
            }.getOrNull()?.also { tiles.put(key, it) }
        }
    }

    /** Decodes at the power-of-two reduction nearest above [maxSide], so a 2048 px picture costs a quarter of its pixels. */
    internal fun decodeSampled(open: () -> InputStream, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        if (long <= 0) return null
        var sample = 1
        while (long / (sample * 2) >= maxSide) sample *= 2
        return open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }
}
