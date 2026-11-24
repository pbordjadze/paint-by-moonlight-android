package com.paintbymoonlight

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paintbymoonlight.core.Core
import com.paintbymoonlight.core.PaintingLength
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.data.PaintingMeta
import com.paintbymoonlight.data.Progress
import com.paintbymoonlight.data.Thumbnails
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * The app on a device: the core makes paintings from two test photos (Kodak's parrots and
 * lighthouse), one left a third painted and one finished, and the tour photographs the gallery
 * and the painting screen. Screenshots land in the app's files/shots/, which CI pulls with
 * `run-as` (scripts/emulator-tour.sh).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AppTour {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    companion object {
        private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MoonlightApp
        private val shots get() = File(app.filesDir, "shots").apply { mkdirs() }

        @JvmStatic
        @BeforeClass
        fun makePaintings() = runBlocking {
            val library = app.library
            library.load()
            library.paintings.value.forEach { library.delete(it.id) }
            make(library, "lighthouse.jpg", "Lighthouse", PaintingLength.Quick, paintedShare = 1f)
            make(library, "parrots.jpg", "Parrots", PaintingLength.Relaxed, paintedShare = 0.35f)
        }

        private suspend fun make(library: Library, asset: String, title: String, length: PaintingLength, paintedShare: Float) {
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            val photo = assets.open(asset).use { BitmapFactory.decodeStream(it) }
            val start = System.nanoTime()
            val made = Core.make(photo, length)
            val seconds = (System.nanoTime() - start) / 1e9
            val scene = Core.scene(made.template, 7)
            Log.i("AppTour", "$asset: ${scene.regions.size} areas, ${scene.paints.size} paints in %.2f s".format(seconds))
            assertTrue(scene.regions.isNotEmpty())
            val progress = Progress(scene.regions.size)
            // Paint the largest areas first, as a painter's first sitting tends to.
            scene.regions.indices.sortedByDescending { scene.regions[it].area }
                .take((scene.regions.size * paintedShare).toInt())
                .forEach { progress.paint(it) }
            val meta = PaintingMeta(
                id = Library.newId(), title = title, created = System.currentTimeMillis(), length = length.name,
                seed = 7, pipelineVersion = Core.pipelineVersion, settings = made.settingsJson, regionCount = scene.regions.size,
            )
            library.add(meta, made.template, photo, Thumbnails.render(scene, Progress(scene.regions.size)))
            library.save(meta.id, progress.snapshot(), Thumbnails.render(scene, progress))
        }
    }

    private fun shoot(name: String) {
        compose.waitForIdle()
        Thread.sleep(600)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun a_gallery() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Parrots", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        shoot("1-gallery")
    }

    @Test
    fun b_paintingInProgress() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Parrots", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("Parrots", substring = true)).performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("Painting canvas")).fetchSemanticsNodes().isNotEmpty() }
        shoot("2-painting")
        compose.onNode(hasContentDescription("Hint: find the next area")).performClick()
        Thread.sleep(1500)
        shoot("3-hint")
    }

    @Test
    fun c_finished() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Lighthouse", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("Lighthouse", substring = true)).performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription, listOf("Painting canvas"))).fetchSemanticsNodes().isNotEmpty() }
        shoot("4-finished")
    }
}
