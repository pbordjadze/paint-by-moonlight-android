package com.paintbymoonlight

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.paintbymoonlight.core.Core
import com.paintbymoonlight.core.PaintingLength
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.data.PaintingMeta
import com.paintbymoonlight.data.Progress
import com.paintbymoonlight.data.Thumbnails
import com.paintbymoonlight.museum.MuseumCatalog
import com.paintbymoonlight.museum.MuseumImages
import com.paintbymoonlight.museum.MuseumLibrary
import com.paintbymoonlight.pictures.Pictures
import com.paintbymoonlight.ui.create.CreateScreen
import com.paintbymoonlight.ui.create.CreateViewModel
import com.paintbymoonlight.ui.create.Picked
import com.paintbymoonlight.ui.theme.MoonlightTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import kotlin.math.abs

/**
 * The app on a device, photographed for review. It starts with an empty gallery (first
 * launch), opens the create screen on a test photo and starts making it, then the core makes
 * two paintings from the test photos (Kodak's parrots and lighthouse), one left a third
 * painted and one finished, and the tour goes through the gallery, a card's menu, the
 * painting screen with Hint and More, the finished painting, Print it big, dark mode, a
 * 1.3× font, the Paint page, Tonight's Picture picked and the Museum. Screenshots land in the app's files/shots/ as NN-name.png (sorted in tour
 * order), which CI pulls with `run-as` (scripts/emulator-tour.sh).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AppTour {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    companion object {
        private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
        private val app get() = instrumentation.targetContext.applicationContext as MoonlightApp
        private val shots get() = File(app.filesDir, "shots").apply { mkdirs() }
        private var made = false

        @JvmStatic
        @BeforeClass
        fun emptyLibrary() {
            runBlocking {
                val library = app.library
                library.awaitLoaded()
                library.paintings.value.forEach { library.delete(it.id) }
                shots.listFiles()?.forEach { it.delete() }
            }
        }

        /** The two paintings the rest of the tour looks at, made once. */
        private fun ensurePaintings() = runBlocking {
            if (made) return@runBlocking
            make(app.library, "lighthouse.jpg", "Lighthouse", PaintingLength.Quick, paintedShare = 1f)
            make(app.library, "parrots.jpg", "Parrots", PaintingLength.Relaxed, paintedShare = 0.35f)
            made = true
        }

        private suspend fun make(library: Library, asset: String, title: String, length: PaintingLength, paintedShare: Float) {
            val photo = instrumentation.context.assets.open(asset).use { BitmapFactory.decodeStream(it) }
            val start = System.nanoTime()
            val made = Core.make(photo, length)
            val seconds = (System.nanoTime() - start) / 1e9
            val scene = Core.scene(made.template, 7, Core.NICKNAME_VERSION)
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
                nicknameVersion = Core.NICKNAME_VERSION, aspect = scene.width / scene.height,
            )
            library.add(meta, made.template, photo, Thumbnails.render(scene, Progress(scene.regions.size)))
            library.save(meta.id, progress.snapshot()) { Thumbnails.render(scene, progress) }
        }

        private fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
    }

    /**
     * The whole screen as the device shows it (status bar, menus and dialogs included), once
     * the app is idle; the root's own image where the device can't take one.
     */
    private fun shoot(name: String) {
        compose.waitForIdle()
        Thread.sleep(600)
        save(name, instrumentation.uiAutomation.takeScreenshot() ?: compose.onRoot().captureToImage().asAndroidBitmap())
    }

    /**
     * The screen now, status bar included, without waiting for the app's animations to end (the
     * clock may be paused, or an animation never ends); the root's own image where the device
     * can't take one.
     */
    private fun capture(name: String) {
        Thread.sleep(300)
        save(name, instrumentation.uiAutomation.takeScreenshot() ?: compose.onRoot().captureToImage().asAndroidBitmap())
    }

    private fun save(name: String, bitmap: Bitmap) {
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Waits for a node; one that never comes leaves a shot of the screen as it was, for the report. */
    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 20_000) {
        try {
            compose.waitUntil(millis) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            instrumentation.uiAutomation.takeScreenshot()?.let { save("zz-missing-${matcher.description.take(40)}", it) }
            compose.onRoot(useUnmergedTree = true).printToLog("AppTour")
            throw e
        }
    }

    /** New painting, whether the button shows its words or, collapsed, only its icon. */
    private val newPainting = hasText("New painting") or hasContentDescription("New painting")

    private fun card(title: String) = hasContentDescription(title, substring = true)
    private val canvas = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Painting canvas"))

    private fun openPainting(title: String) {
        waitFor(card(title))
        compose.onNode(card(title)).performClick()
        waitFor(canvas)
    }

    @Test
    fun a_emptyGallery() {
        waitFor(hasText("Your photos, painted by numbers"), 10_000)
        // The shelf shows once its pictures are read.
        waitFor(hasText("Tonight’s Picture") or hasText("Earlier Nights"), 10_000)
        shoot("01-empty-gallery")
    }

    /** The system photo picker can't be driven, so the create screen is shown on its own with a test photo. */
    @Test
    fun b_create() {
        val photo = File(app.cacheDir, "tour-photo.jpg")
        instrumentation.context.assets.open("parrots.jpg").use { input -> photo.outputStream().use { input.copyTo(it) } }
        val model = CreateViewModel(app.library, app, Picked.Photo(Uri.fromFile(photo).toString()), app.paintings)
        compose.runOnUiThread {
            compose.activity.setContent {
                MoonlightTheme { CreateScreen(model = model, preferences = app.preferences, onBack = {}, onMade = {}) }
            }
        }
        waitFor(hasContentDescription("Your photo"))
        shoot("02-create")
        compose.onNode(hasText("Start painting")).performClick()
        waitFor(hasText("Mixing your paints", substring = true), 5_000)
        Thread.sleep(600)
        capture("03-making")
        compose.runOnUiThread { model.cancel() }
        // Had it finished first, the gallery's shots would show it too.
        runBlocking { app.library.paintings.value.forEach { app.library.delete(it.id) } }
    }

    @Test
    fun c_gallery() {
        ensurePaintings()
        waitFor(card("Parrots"), 10_000)
        shoot("04-gallery")
        compose.onNode(card("Parrots")).performTouchInput { longClick() }
        waitFor(hasText("Rename"), 5_000)
        shoot("05-card-menu")
    }

    @Test
    fun d_paintingInProgress() {
        ensurePaintings()
        openPainting("Parrots")
        shoot("06-painting")
        // Paused, so the shot lands mid-pulse rather than after it.
        compose.mainClock.autoAdvance = false
        compose.onNode(hasContentDescription("Hint: find the next area")).performClick()
        // The flight lands in 0.55 s and the area's first beat peaks 0.4 + 0.33 s in.
        compose.mainClock.advanceTimeBy(750)
        capture("07-hint")
        compose.mainClock.autoAdvance = true
        compose.onNode(hasContentDescription("More")).performClick()
        waitFor(hasText("Fit to Screen"), 5_000)
        shoot("08-more-menu")
    }

    @Test
    fun e_finished() {
        ensurePaintings()
        openPainting("Lighthouse")
        waitFor(hasText("Print it big"), 5_000)
        shoot("09-finished")
        compose.onNode(hasText("Print it big")).performClick()
        waitFor(hasText("One Big Sheet"))
        // No network in CI: the price either fails (Try Again) or never comes; show whichever.
        try {
            waitFor(hasText("Try Again"), 30_000)
        } catch (e: ComposeTimeoutException) {
            Log.i("AppTour", "no price yet: ${e.message}")
        }
        shoot("10-print")
    }

    @Test
    fun f_dark() {
        ensurePaintings()
        try {
            shell("cmd uimode night yes")
            waitForConfiguration { it.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
            waitFor(card("Parrots"))
            shoot("11-dark-gallery")
            openPainting("Parrots")
            shoot("12-dark-painting")
        } finally {
            shell("cmd uimode night no")
            waitForConfiguration(lenient = true) { it.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_NO }
        }
    }

    @Test
    fun g_largeText() {
        ensurePaintings()
        try {
            shell("settings put system font_scale 1.3")
            waitForConfiguration { abs(it.fontScale - 1.3f) < 0.01f }
            openPainting("Parrots")
            shoot("13-painting-large-text")
        } finally {
            shell("settings put system font_scale 1.0")
            waitForConfiguration(lenient = true) { abs(it.fontScale - 1f) < 0.01f }
        }
    }

    @Test
    fun h_paintPage() {
        ensurePaintings()
        waitFor(newPainting, 10_000)
        compose.onNode(newPainting).performClick()
        waitFor(hasText("Your Own"))
        shoot("14-paint-page")
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Picture credits"))
        shoot("15-paint-page-more")
        compose.onNode(hasText("Picture credits")).performClick()
        waitFor(hasText("CC0", substring = true))
        shoot("16-picture-credits")
    }

    /** Tonight's Picture picked: the create screen on it, its title and painter under it. */
    @Test
    fun i_tonightsPicture() {
        val tonight = Pictures.tonight(app) ?: error("no Tonight's Picture")
        waitFor(newPainting, 10_000)
        compose.onNode(newPainting).performClick()
        waitFor(hasContentDescription(tonight.label))
        compose.onAllNodes(hasContentDescription(tonight.label)).onFirst().performClick()
        waitFor(hasContentDescription(tonight.title))
        shoot("17-create-tonight")
    }

    /**
     * The Museum. Its catalog and pictures come from the network; where the device has none, the
     * tour hangs its own two test photos as works (named as such), laid in the picture cache as
     * if fetched, so the wall, a work's page and Paint This still show.
     */
    @Test
    fun j_museum() {
        val museum = MuseumLibrary.of(app)
        museum.open()
        try {
            compose.waitUntil(45_000) { museum.state.value !is MuseumLibrary.State.Loading }
        } catch (e: ComposeTimeoutException) {
            Log.i("AppTour", "the museum's catalog didn't come: ${e.message}")
        }
        if (museum.state.value !is MuseumLibrary.State.Ready) museum.use(tourCatalog())
        waitFor(newPainting, 10_000)
        compose.onNode(newPainting).performClick()
        waitFor(hasContentDescription("Museum", substring = true))
        compose.onNode(hasContentDescription("Museum", substring = true)).performClick()
        waitFor(hasText("Artists, titles, years"))
        Thread.sleep(4_000)
        shoot("18-museum")
        compose.onAllNodes(hasContentDescription(", ", substring = true) and hasClickAction()).onFirst().performClick()
        waitFor(hasText("Paint This"))
        Thread.sleep(3_000)
        shoot("19-museum-work")
        compose.onNode(hasText("Paint This")).performClick()
        waitFor(hasText("Start painting"), 60_000)
        shoot("20-museum-create")
    }

    private fun tourCatalog(): MuseumCatalog {
        val images = MuseumImages.of(app)
        val photos = listOf("parrots.jpg", "lighthouse.jpg")
        for (photo in photos) images.seed("https://tour.invalid/$photo", instrumentation.context.assets.open(photo).use { it.readBytes() })
        val works = (1..12).joinToString(",") { i ->
            val photo = photos[i % 2]
            val (w, h) = if (photo == "parrots.jpg") 768 to 512 else 512 to 768
            """{"id": "tour-$i", "title": "Tour photo $i", "artist": "Kodak test photo", "year": "1991", "date": ${1990 + i},
              "movement": "${if (i % 3 == 0) "Realism" else "Impressionism"}", "kind": "${if (i % 4 == 0) "print" else "painting"}",
              "colorfulness": ${20 + i * 3}, "average": "8a7a6a", "width": $w, "height": $h, "museum": "tour",
              "thumb": "https://tour.invalid/$photo", "image": "https://tour.invalid/$photo", "medium": "Photograph"}"""
        }
        return MuseumCatalog.decode(
            """{"version": 1, "made": "2026-11-24", "museums": {"tour": "The tour's own photos"},
              "movements": ["Impressionism", "Realism"], "works": [$works]}""",
            hosts = setOf("tour.invalid"),
        )
    }

    /**
     * Waits until the running activity has the configuration a setting just changed (the change
     * recreates it, after the shell command returns) and the recreated activity is idle. Lenient
     * when putting a setting back, so the restore never fails a test whose shots are taken.
     */
    private fun waitForConfiguration(lenient: Boolean = false, matches: (Configuration) -> Boolean) {
        try {
            compose.waitUntil(15_000) {
                var current: Configuration? = null
                instrumentation.runOnMainSync {
                    current = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .firstOrNull()?.resources?.configuration
                }
                current?.let(matches) == true
            }
            compose.waitForIdle()
        } catch (e: ComposeTimeoutException) {
            if (!lenient) throw e
            Log.w("AppTour", "the setting wasn't put back in time: ${e.message}")
        }
    }
}
