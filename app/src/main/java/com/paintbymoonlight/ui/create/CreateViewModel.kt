package com.paintbymoonlight.ui.create

import android.content.Context
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
import com.paintbymoonlight.Unreleased
import com.paintbymoonlight.pictures.Pictures
import com.paintbymoonlight.pictures.Sample
import com.paintbymoonlight.ui.source.cameraFolder
import com.paintbymoonlight.pictures.SampleLibrary
import com.paintbymoonlight.unlock.UnlimitedPaintings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random

/** Turns a picked picture into a painting: the picture shown, Painting Length chosen, then made. */
class CreateViewModel(
    private val library: Library,
    /** The application's, not an Activity's, since the model outlives a rotation. */
    private val context: Context,
    private val picked: Picked,
    /** Each painting made takes a free painting, until the unlock is bought. */
    val paintings: UnlimitedPaintings,
) : ViewModel() {
    var photo by mutableStateOf<Bitmap?>(null)
        private set
    /** A small first read of the picture, shown while the full one is decoded. */
    var preview by mutableStateOf<Bitmap?>(null)
        private set
    /** A picture that isn't the painter's own: its title, and its maker and date under it. */
    var credit by mutableStateOf<Pair<String, String>?>(null)
        private set
    /** The painting's name: the picture's, or the day's for a photo of the painter's own. */
    private var title: String? = null
    /** The library picture or night it is made from, which the painting records. */
    private var sampleName: String? = null
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
            // What the picture is called is set here, on the main thread; its bytes are read on IO.
            val sample = (picked as? Picked.Sample)?.let { withContext(Dispatchers.IO) { Pictures.named(context, it.id) } }
            when {
                sample != null -> {
                    title = sample.title
                    sampleName = sample.id
                    credit = sample.title to sample.provenance.byline
                }
                picked is Picked.Museum -> title = picked.title
            }
            val fail = if (picked is Picked.Photo) "This photo couldn't be opened." else "This picture couldn’t be fetched. Check your connection, then try again."
            // Found (and, for a museum's, fetched) once: a failure shows after one try, not two.
            val source = runCatching { withContext(Dispatchers.IO) { source(sample) } }
                .onFailure { if (it is CancellationException) throw it }
                .getOrElse { failed = fail; return@launch }
            // Both decodes at once (a file, asset or content source decodes any number of times):
            // the small one only shows if it beats the full one.
            launch {
                val small = runCatching { withContext(Dispatchers.IO) { decode(source, PREVIEW_SIDE) } }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
                if (photo == null) preview = small
            }
            photo = runCatching { withContext(Dispatchers.IO) { decode(source) } }
                .onFailure {
                    if (it is CancellationException) throw it
                    failed = fail
                }
                .getOrNull()
            if (photo != null) preview = null
        }
    }

    /** Where the picture's bytes are: the painter's photo, a bundled or downloaded picture, a museum's. */
    private suspend fun source(sample: Sample?): ImageDecoder.Source = when (picked) {
        is Picked.Photo -> ImageDecoder.createSource(context.contentResolver, Uri.parse(picked.uri))
        is Picked.Sample -> when {
            sample == null -> throw IOException("no picture ${picked.id}")
            sample.night != null -> ImageDecoder.createSource(Unreleased.nights(context)?.fullPicture(sample.id) ?: throw IOException("no nights here"))
            else -> ImageDecoder.createSource(context.assets, SampleLibrary.asset(sample.id))
        }
        is Picked.Museum -> ImageDecoder.createSource(Unreleased.museumPicture(context, picked.image))
    }

    /** The picture upright (ImageDecoder applies its orientation), at most [maxSide] px on its long side: by default more than the pipeline's working size. */
    private fun decode(source: ImageDecoder.Source, maxSide: Int = MAX_SIDE): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val long = max(info.size.width, info.size.height)
            if (long > maxSide) {
                val k = maxSide.toFloat() / long
                decoder.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        }.copy(Bitmap.Config.ARGB_8888, false)

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
                    title = title ?: SimpleDateFormat("MMMM d", Locale.getDefault()).format(Date()),
                    created = System.currentTimeMillis(),
                    length = length.name,
                    seed = seed,
                    pipelineVersion = Core.pipelineVersion,
                    settings = result.settingsJson,
                    regionCount = scene.regions.size,
                    nicknameVersion = Core.NICKNAME_VERSION,
                    aspect = scene.width / scene.height,
                    sampleName = sampleName,
                )
                val thumb = withContext(Dispatchers.Default) { Thumbnails.render(scene, Progress(scene.regions.size)) }
                // Saved and counted together: a Back or Cancel now can't leave a painting saved but not counted.
                withContext(NonCancellable) {
                    library.add(meta, result.template, bitmap, thumb)
                    paintings.started()
                }
                when (picked) {
                    is Picked.Museum -> Unreleased.museumPainted(context, picked.work)
                    // The painting keeps its own copy: Take Photo's files have done their work.
                    is Picked.Photo -> withContext(Dispatchers.IO) { cameraFolder(context).deleteRecursively() }
                    is Picked.Sample -> Unit
                }
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
        const val PREVIEW_SIDE = 480
    }
}
