package com.paintbymoonlight.unlock

import android.app.Activity
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class UnlimitedPaintingsTest {
    private val zone = ZoneId.of("America/New_York")
    private var now = Instant.parse("2026-11-24T15:00:00Z")

    /** Play as the tests need it: owned, not owned, out of reach, or never answering. */
    private class Store(var owned: Boolean?, val hangs: Boolean = false) : UnlockStore {
        override suspend fun price() = "$4.99"
        override suspend fun owned(): Boolean? {
            if (hangs) awaitCancellation()
            return owned
        }
        override suspend fun buy(activity: Activity) = PurchaseOutcome.Unlocked
        override val changes: Flow<Unit> = emptyFlow()
    }

    private fun paintings(store: UnlockStore, memory: UnlockMemory = UnlockMemory.inMemory()) =
        UnlimitedPaintings(store, memory, now = { now }, zone = { zone })

    @Test
    fun fivePaintingsThenTheSheet() = runBlocking {
        val memory = UnlockMemory.inMemory()
        val paintings = paintings(Store(owned = false), memory)
        repeat(5) {
            assertTrue(paintings.mayStart())
            paintings.started()
        }
        assertFalse(paintings.mayStart())
        assertEquals(0, paintings.left())
        assertEquals(0, memory.free.left)
    }

    @Test
    fun theCountIsKeptBetweenLaunches() = runBlocking {
        val memory = UnlockMemory.inMemory()
        paintings(Store(owned = false), memory).started()
        val again = paintings(Store(owned = false), memory)
        assertEquals(4, again.left())
    }

    @Test
    fun theNextDayBringsOneBack() = runBlocking {
        val paintings = paintings(Store(owned = false), UnlockMemory.inMemory(FreePaintings(0, now)))
        assertFalse(paintings.mayStart())
        now = now.plusSeconds(24 * 3600)
        assertTrue(paintings.mayStart())
    }

    @Test
    fun theUnlockTakesNoFreePaintings() = runBlocking {
        val memory = UnlockMemory.inMemory(FreePaintings(0, now))
        val paintings = paintings(Store(owned = true), memory)
        assertTrue(paintings.mayStart())
        paintings.started()
        assertEquals(FreePaintings(0, now), memory.free)
        assertTrue(memory.owned)
    }

    @Test
    fun aPurchaseIsRememberedWhilePlayIsOutOfReach() = runBlocking {
        val paintings = paintings(Store(owned = null), UnlockMemory.inMemory(FreePaintings(0, now), owned = true))
        assertTrue(paintings.mayStart())
        assertNull(paintings.refresh())
        assertTrue(paintings.unlocked.value)
    }

    @Test
    fun aRefundLocksAgain() = runBlocking {
        val memory = UnlockMemory.inMemory(owned = true)
        val paintings = paintings(Store(owned = false), memory)
        assertEquals(false, paintings.refresh())
        assertFalse(paintings.unlocked.value)
        assertFalse(memory.owned)
    }

    @Test
    fun aFreePaintingLeftStartsWithoutAskingPlay() = runBlocking {
        // A Play that never answers would hold the test up: it isn't asked.
        val paintings = paintings(Store(owned = false, hangs = true))
        assertTrue(paintings.mayStart())
    }

    @Test
    fun withNoneLeftPlayTakingTooLongAnswersWithWhatItSaidBefore() = runBlocking {
        val none = FreePaintings(0, now)
        assertFalse(paintings(Store(owned = false, hangs = true), UnlockMemory.inMemory(none)).mayStart())
        assertTrue(paintings(Store(owned = false, hangs = true), UnlockMemory.inMemory(none, owned = true)).mayStart())
    }

    @Test
    fun withNoneLeftPlayIsAskedOnce() = runBlocking {
        val store = Store(owned = true)
        val paintings = paintings(store, UnlockMemory.inMemory(FreePaintings(0, now)))
        assertTrue(paintings.mayStart())
        store.owned = false
        // Asked once a launch; later answers come with the foreground's refresh.
        assertTrue(paintings.mayStart())
    }

    @Test
    fun thePriceIsPlays() = runBlocking {
        val paintings = paintings(Store(owned = false))
        assertNull(paintings.price.value)
        paintings.loadPrice()
        assertEquals("$4.99", paintings.price.value)
    }
}
