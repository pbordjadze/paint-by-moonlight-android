package com.paintbymoonlight

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paintbymoonlight.core.Core
import com.paintbymoonlight.core.CoreException
import com.paintbymoonlight.core.PaintingLength
import com.paintbymoonlight.core.SharedPainting
import com.paintbymoonlight.data.ReceiveException
import com.paintbymoonlight.data.SharedPaintings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Paintings sent between painters: the iOS app's frozen `.moonlight` fixtures (copied from
 * paint-by-number's Tests/PaintCoreTests/Fixtures) read through the core, a painting made here
 * sent and read back, a received file added to the library and opened, and Send Template's
 * sheet photographed into files/shots/ with the tour's.
 */
@RunWith(AndroidJUnit4::class)
class ExchangeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as MoonlightApp
    private val added = mutableListOf<String>()

    private fun asset(name: String) = instrumentation.context.assets.open(name).use { it.readBytes() }

    /** [bytes] as a file in the app's cache, by the app's FileProvider, as another app hands one over. */
    private fun opened(bytes: ByteArray, name: String = "Irises at Dusk Template.moonlight"): Uri {
        val file = File(app.cacheDir, "sending/$name").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    @After
    fun removeAdded() = runBlocking { added.forEach { app.library.delete(it) } }

    @Test
    fun iosFixturesReadThroughTheCore() = runBlocking {
        val named = SharedPainting.read(asset("shared-v1-names.moonlight"))
        assertEquals("Irises at Dusk", named.title)
        assertEquals(0x9E3779B97F4A7C15uL.toLong(), named.nicknameSeed)
        assertEquals(2, named.nicknameVersion)
        assertNull(named.photo)
        // The template is template-v2-book.pbnt's, byte for byte (its FNV-1a, as moonlight-probe prints it).
        assertEquals(10147, named.template.size)
        assertEquals("31657d265798b30e", fnv1a(named.template))
        assertEquals(5, Core.scene(named.template, named.nicknameSeed!!, named.nicknameVersion!!).regions.size)

        val withPhoto = SharedPainting.read(asset("shared-v1-photo.moonlight"))
        assertNull(withPhoto.nicknameSeed)
        assertEquals(663, withPhoto.photo!!.size)
        assertArrayEquals(named.template, withPhoto.template)
    }

    @Test
    fun aPaintingMadeHereGoesAndComesBack() = runBlocking {
        val photo = instrumentation.context.assets.open("lighthouse.jpg").use { android.graphics.BitmapFactory.decodeStream(it) }
        val made = Core.make(photo, PaintingLength.Quick)
        val jpeg = java.io.ByteArrayOutputStream().also { photo.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        val file = SharedPainting.write(made.template, "Lighthouse", made.settingsJson, jpeg, -7, 2)
        assertTrue(String(file, 0, 4, Charsets.US_ASCII) == "PBMS")
        val back = SharedPainting.read(file)
        assertArrayEquals(made.template, back.template)
        assertEquals("Lighthouse", back.title)
        assertEquals(-7L, back.nicknameSeed)
        assertEquals(2, back.nicknameVersion)
        assertArrayEquals(jpeg, back.photo)
        assertEquals(made.settingsJson, back.settingsJson)

        val bare = SharedPainting.read(SharedPainting.write(made.template, "Lighthouse", made.settingsJson, null, 1, 2))
        assertNull(bare.photo)
    }

    @Test
    fun aReceivedFileIsANewPaintingOnce() = runBlocking {
        val library = app.library
        val id = SharedPaintings.receive(library, app.contentResolver, opened(asset("shared-v1-photo.moonlight")))
        added += id
        val meta = library.meta(id)!!
        assertEquals("Irises at Dusk", meta.title)
        assertEquals(5, meta.regionCount)
        assertEquals(0, meta.paintedCount)
        assertNotNull(meta.receivedDigest)
        assertNotNull(library.photo(id))
        // Opened again, the same painting.
        assertEquals(id, SharedPaintings.receive(library, app.contentResolver, opened(asset("shared-v1-photo.moonlight"))))

        val named = SharedPaintings.receive(library, app.contentResolver, opened(asset("shared-v1-names.moonlight")))
        added += named
        assertEquals(0x9E3779B97F4A7C15uL.toLong(), library.meta(named)!!.seed)
        assertEquals(2, library.meta(named)!!.nicknameVersion)
        assertNull(library.photo(named))
    }

    @Test
    fun aDamagedOrNewerFileSaysSoCalmly() = runBlocking {
        val good = asset("shared-v1-names.moonlight")
        val newer = good.copyOf().also { it[4] = 2 }
        val cases = mapOf(
            good.copyOf(good.size / 2) to ReceiveException.Reason.Damaged,
            "not a painting".encodeToByteArray() to ReceiveException.Reason.Damaged,
            newer to ReceiveException.Reason.NeedsNewerApp,
        )
        for ((bytes, expected) in cases) {
            try {
                added += SharedPaintings.receive(app.library, app.contentResolver, opened(bytes, "broken.moonlight"))
                fail("a damaged file was added")
            } catch (e: ReceiveException) {
                assertEquals(expected, e.reason)
            }
        }
        try {
            SharedPainting.read(newer)
            fail("a newer file was read")
        } catch (e: CoreException) {
            assertEquals(4, e.status)
        }
    }

    @Test
    fun openingAFileOpensItsPaintingAndSendTemplateOffersIt() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClass(app, MainActivity::class.java)
            setDataAndType(opened(asset("shared-v1-photo.moonlight")), SharedPainting.MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("Painting canvas")).fetchSemanticsNodes().isNotEmpty() }
            added += app.library.paintings.value.filter { it.title == "Irises at Dusk" }.map { it.id }
            shoot("5-received")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Irises at Dusk", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasContentDescription("Irises at Dusk", substring = true)).performTouchInput { longClick() }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Send Template…")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Send Template…")).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Original Photo")).fetchSemanticsNodes().isNotEmpty() }
            shoot("6-send-template")
        }
    }

    /** The whole screen, sheets and dialogs included. */
    private fun shoot(name: String) {
        compose.waitForIdle()
        Thread.sleep(800)
        val shots = File(app.filesDir, "shots").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun fnv1a(bytes: ByteArray): String {
        var hash = 0xcbf29ce484222325uL
        for (b in bytes) hash = (hash xor b.toUByte().toULong()) * 0x100000001b3uL
        return hash.toString(16)
    }
}
