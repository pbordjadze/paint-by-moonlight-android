package com.paintbymoonlight.ui.create

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paintbymoonlight.core.Core
import com.paintbymoonlight.core.PaintingLength
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.data.PaintingMeta
import com.paintbymoonlight.data.Progress
import com.paintbymoonlight.data.Thumbnails
import com.paintbymoonlight.unlock.UnlimitedPaintings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random

/** Turns a picked photo into a painting: the photo shown, Painting Length chosen, then made. */
class CreateViewModel(
    private val library: Library,
    /** The application's, not an Activity's, since the model outlives a rotation. */
    private val resolver: ContentResolver,
    private val uri: Uri,
    /** Each painting made takes a free painting, until the unlock is bought. */
    val paintings: UnlimitedPaintings,
) : ViewModel() {
    var photo by mutableStateOf<Bitmap?>(null)
        private set
    var failed by mutableStateOf<String?>(null)
        private set
    var making by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var made by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    init {
        viewModelScope.launch {
            photo = runCatching { withContext(Dispatchers.IO) { decode() } }
                .onFailure { failed = "This photo couldn't be opened." }
                .getOrNull()
        }
    }

    /** The photo upright (ImageDecoder applies its orientation), at most 2048 px on its long side: more than the pipeline's working size. */
    private fun decode(): Bitmap {
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val long = max(info.size.width, info.size.height)
            if (long > MAX_SIDE) {
                val k = MAX_SIDE.toFloat() / long
                decoder.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        }.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun make(length: PaintingLength) {
        val bitmap = photo ?: return
        if (making) return
        making = true
        progress = 0f
        job = viewModelScope.launch {
            try {
                val seed = Random.nextLong()
                val result = Core.make(bitmap, length) { progress = it }
                val scene = Core.scene(result.template, seed, Core.NICKNAME_VERSION)
                val meta = PaintingMeta(
                    id = Library.newId(),
                    title = SimpleDateFormat("MMMM d", Locale.getDefault()).format(Date()),
                    created = System.currentTimeMillis(),
                    length = length.name,
                    seed = seed,
                    pipelineVersion = Core.pipelineVersion,
                    settings = result.settingsJson,
                    regionCount = scene.regions.size,
                    nicknameVersion = Core.NICKNAME_VERSION,
                    aspect = scene.width / scene.height,
                )
                val thumb = withContext(Dispatchers.Default) { Thumbnails.render(scene, Progress(scene.regions.size)) }
                library.add(meta, result.template, bitmap, thumb)
                paintings.started()
                made = meta.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = "Something went wrong making this painting. Try another photo?"
            } finally {
                making = false
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private companion object {
        const val MAX_SIDE = 2048
    }
}
