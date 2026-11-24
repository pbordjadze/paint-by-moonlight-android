package com.paintbymoonlight

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paintbymoonlight.ui.theme.MoonlightTheme
import com.paintbymoonlight.unlock.FakeUnlockStore
import com.paintbymoonlight.unlock.FreePaintings
import com.paintbymoonlight.unlock.PurchaseOutcome
import com.paintbymoonlight.unlock.UnlimitedPaintings
import com.paintbymoonlight.unlock.UnlockContent
import com.paintbymoonlight.unlock.UnlockMemory
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * Unlimited Paintings' sheet with no free painting left, bought from [FakeUnlockStore] (nothing
 * here asks Google Play), photographed light and dark into the tour's shots.
 */
@RunWith(AndroidJUnit4::class)
class UnlockTour {
    @get:Rule
    val compose = createComposeRule()

    private val shots get() = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "shots").apply { mkdirs() }

    private fun paintings(store: FakeUnlockStore) =
        UnlimitedPaintings(store, UnlockMemory.inMemory(FreePaintings(0, Instant.now())))

    private fun show(paintings: UnlimitedPaintings, dark: Boolean) {
        compose.setContent { Sheet(paintings, dark) }
    }

    @Composable
    private fun Sheet(paintings: UnlimitedPaintings, dark: Boolean) {
        MoonlightTheme(darkTheme = dark) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                UnlockContent(paintings, onNotNow = {}, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    private fun shoot(name: String) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Unlock for $4.99")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun light() {
        show(paintings(FakeUnlockStore()), dark = false)
        shoot("9-unlock")
    }

    @Test
    fun dark() {
        show(paintings(FakeUnlockStore()), dark = true)
        shoot("9-unlock-dark")
    }

    @Test
    fun buyingUnlocks() {
        val paintings = paintings(FakeUnlockStore())
        show(paintings, dark = false)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Unlock for $4.99")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Unlock for $4.99").performClick()
        compose.waitUntil(5_000) { paintings.unlocked.value }
    }

    @Test
    fun aPendingPaymentSaysSo() {
        val paintings = paintings(FakeUnlockStore(purchase = PurchaseOutcome.Pending))
        show(paintings, dark = false)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Unlock for $4.99")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Unlock for $4.99").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("goes through", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(!paintings.unlocked.value)
    }

    @Test
    fun restoreSaysWhenThereIsNothing() {
        show(paintings(FakeUnlockStore()), dark = false)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Unlock for $4.99")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Restore purchase").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("hasn’t bought", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }
}
