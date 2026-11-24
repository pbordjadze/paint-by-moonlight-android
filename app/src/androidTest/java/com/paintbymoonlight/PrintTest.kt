package com.paintbymoonlight

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paintbymoonlight.core.Box
import com.paintbymoonlight.core.Core
import com.paintbymoonlight.core.Edge
import com.paintbymoonlight.core.Label
import com.paintbymoonlight.core.PaintingLength
import com.paintbymoonlight.core.Paint
import com.paintbymoonlight.core.Region
import com.paintbymoonlight.core.Scene
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.data.PaintingMeta
import com.paintbymoonlight.data.Progress
import com.paintbymoonlight.data.Thumbnails
import com.paintbymoonlight.print.Paper
import com.paintbymoonlight.print.PrintChoices
import com.paintbymoonlight.print.PrintScreen
import com.paintbymoonlight.print.PrintViewModel
import com.paintbymoonlight.print.Sheets
import com.paintbymoonlight.print.TemplatePrint
import com.paintbymoonlight.ui.theme.MoonlightTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The printable template on a device: a test scene made into a PDF, its pages counted and drawn
 * by the platform's own PDF reader, and their first pages kept in the tour's shots
 * (files/shots/print-*.png) for a look; then a painting the core makes, printed and shown in
 * Print's preview.
 */
@RunWith(AndroidJUnit4::class)
class PrintTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val shots get() = File(context.filesDir, "shots").apply { mkdirs() }

    /**
     * A wide grid of [columns] × [rows] square areas of [cell] canvas units, painted in [paints]
     * paints, each numbered at [numberSize] units (a detail area's at half when [detail]).
     */
    private fun scene(columns: Int, rows: Int, cell: Float, paints: Int, numberSize: Float, detail: Boolean = false): Scene {
        val palette = List(paints) { i ->
            val color = Color.HSVToColor(floatArrayOf(i * 360f / paints, 0.55f, 0.85f))
            // An sRGB color, its P3 channels left at the sRGB values: a print reads only [Paint.color].
            Paint(color, Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, "Paint ${i + 1}", "", "")
        }
        val regions = ArrayList<Region>()
        val labels = ArrayList<Label>()
        val edges = ArrayList<Edge>()
        for (r in 0 until rows) for (c in 0 until columns) {
            val x = c * cell
            val y = r * cell
            val loop = floatArrayOf(x, y, x + cell, y, x + cell, y + cell, x, y + cell)
            val isDetail = detail && (r + c) % 7 == 0
            regions += Region((r * 3 + c) % paints, cell * cell, isDetail, Box(x, y, x + cell, y + cell), listOf(loop))
            labels += Label(x + cell / 2, y + cell / 2, if (isDetail) numberSize / 2 else numberSize, regions.size - 1, cell / 2)
            edges += Edge(regions.size - 1, Edge.OUTSIDE, true, floatArrayOf(x, y, x + cell, y, x + cell, y + cell, x, y + cell, x, y))
        }
        return Scene(columns * cell, rows * cell, false, palette, regions, edges, labels, emptyList())
    }

    private fun pages(pdf: ByteArray, name: String): Int {
        val file = File(context.cacheDir, "$name.pdf").apply { writeBytes(pdf) }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                for (i in 0 until minOf(renderer.pageCount, 3)) {
                    renderer.openPage(i).use { page ->
                        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        File(shots, "print-$name-${i + 1}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
                return renderer.pageCount
            }
        }
    }

    @Test
    fun aSimpleTemplatePrintsOnOnePageAndItsKey() {
        val scene = scene(columns = 8, rows = 6, cell = 100f, paints = 12, numberSize = 14f)
        val print = TemplatePrint(scene, "Simple", PrintChoices(), Paper.LETTER)
        assertFalse(print.sheets.isTiled)
        assertEquals(792, print.pageWidth)
        assertEquals(612, print.pageHeight)
        assertEquals(2, pages(print.pdf(), "simple"))
        assertTrue(print.smallestNumber(detail = false)!! >= Sheets.MINIMUM_NUMBER)
    }

    @Test
    fun aDetailedTemplateTilesSheetsWithLegibleNumbers() {
        // 1 canvas unit numbers across 1600 units: about 0.4 pt fitted to a page.
        val scene = scene(columns = 40, rows = 30, cell = 40f, paints = 120, numberSize = 4f, detail = true)
        val print = TemplatePrint(scene, "Detailed", PrintChoices(), Paper.A4)
        assertTrue(print.sheets.isTiled)
        assertEquals(595, print.pageHeight)
        // The overview, every sheet, the key.
        assertEquals(1 + print.sheets.count + 1, print.pageCount)
        assertEquals(print.pageCount, pages(print.pdf(), "detailed"))
        assertTrue(print.smallestNumber(detail = false)!! >= Sheets.MINIMUM_NUMBER - 1e-3f)
        assertTrue(print.smallestNumber(detail = true)!! >= Sheets.MINIMUM_DETAIL_NUMBER - 1e-3f)
    }

    @Test
    fun aColoringPageHasNoKeyAndNeedsNoSheets() {
        val scene = scene(columns = 40, rows = 30, cell = 40f, paints = 120, numberSize = 4f)
        val print = TemplatePrint(scene, "Coloring", PrintChoices(numbers = false, colorHints = true), Paper.LETTER)
        assertEquals(1, print.pageCount)
        assertEquals(1, pages(print.pdf(), "coloring"))
    }

    /** Each sheet holds its part of the template, not all of it under a clip: a poster's PDF grows far slower than its sheets. */
    @Test
    fun aPostersSheetsHoldOnlyTheirPart() {
        val scene = scene(columns = 80, rows = 60, cell = 20f, paints = 60, numberSize = 6f)
        val one = TemplatePrint(scene, "Poster", PrintChoices(numbers = false), Paper.LETTER)
        assertEquals(1, one.sheets.count)
        val poster = TemplatePrint(scene, "Poster", PrintChoices(numbers = false, across = 5), Paper.LETTER)
        val count = poster.sheets.count
        assertTrue("$count sheets", count >= 20)
        val onePage = one.pdf().size
        val bytes = poster.pdf().size
        Log.i("PrintTest", "one page: $onePage bytes; $count sheets and their overview: $bytes bytes")
        // The overview is the whole template, and the sheets together about one more with their
        // overlaps; drawn whole under a clip they would be about $count times one page.
        assertTrue("$bytes bytes for $count sheets, $onePage for one page", bytes < onePage * 5)
    }

    @Test
    fun askingForMoreSheetsPrintsBigger() {
        val scene = scene(columns = 8, rows = 6, cell = 100f, paints = 12, numberSize = 14f)
        val one = TemplatePrint(scene, "Simple", PrintChoices(), Paper.LETTER)
        val sizes = one.sizes
        assertTrue(sizes.size >= 2)
        val bigger = TemplatePrint(scene, "Simple", PrintChoices(across = sizes[1].across), Paper.LETTER)
        assertEquals(sizes[1].sheets.count, bigger.sheets.count)
        assertTrue(bigger.sheets.scale > one.sheets.scale)
    }

    @Test
    fun aPaintingPrintsAndPreviews() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = context.applicationContext as MoonlightApp
        val photo = instrumentation.context.assets.open("parrots.jpg").use { BitmapFactory.decodeStream(it) }
        val meta = runBlocking {
            val made = Core.make(photo, PaintingLength.Relaxed)
            val scene = Core.scene(made.template, 7, Core.NICKNAME_VERSION)
            val print = TemplatePrint(scene, "Parrots", PrintChoices(), Paper.LETTER)
            assertEquals(print.pageCount, pages(print.pdf(), "parrots"))
            assertTrue(print.smallestNumber(detail = false)!! >= Sheets.MINIMUM_NUMBER - 1e-3f)
            val meta = PaintingMeta(
                // Titled as the PDF above is, so the preview's page and the printed one read alike.
                id = Library.newId(), title = "Parrots", created = System.currentTimeMillis(),
                length = PaintingLength.Relaxed.name, seed = 7, pipelineVersion = Core.pipelineVersion,
                settings = made.settingsJson, regionCount = scene.regions.size,
                nicknameVersion = Core.NICKNAME_VERSION, aspect = scene.width / scene.height,
            )
            app.library.add(meta, made.template, photo, Thumbnails.render(scene, Progress(scene.regions.size)))
        }
        try {
            val model = PrintViewModel(app, app.library, meta.id, Paper.LETTER, SavedStateHandle())
            compose.runOnUiThread {
                // As MainActivity draws: edge to edge, the bars' icons following the appearance
                // (the test's plain activity otherwise keeps white icons on the light screen).
                compose.activity.enableEdgeToEdge()
                compose.activity.setContent { MoonlightTheme { PrintScreen(model = model, onBack = {}) } }
            }
            // Until the first page is drawn in full (its quick pass says Loading).
            compose.waitUntil(30_000) {
                compose.onAllNodes(
                    (hasContentDescription("How the sheets tape together", substring = true) or hasContentDescription("Page 1 of", substring = true))
                        and SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription),
                ).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            Thread.sleep(1_500)
            val shot = instrumentation.uiAutomation.takeScreenshot()
            if (shot != null) File(shots, "print-preview.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            runBlocking { app.library.delete(meta.id) }
        }
    }
}
