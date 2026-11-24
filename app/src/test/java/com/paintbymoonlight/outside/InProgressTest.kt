package com.paintbymoonlight.outside

import com.paintbymoonlight.data.PaintingMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InProgressTest {
    private fun meta(id: String, created: Long, updated: Long = 0, finished: Long? = null) =
        PaintingMeta(id, id, created, "short", 1, 1, "{}", regionCount = 10, finished = finished, updated = updated)

    @Test fun nothingInProgressWithoutPaintings() = assertNull(inProgress(emptyList()))

    @Test fun finishedPaintingsAreNotInProgress() =
        assertNull(inProgress(listOf(meta("a", 1, finished = 5), meta("b", 2, finished = 6))))

    @Test fun theMostRecentlyPaintedUnfinishedOne() {
        val paintings = listOf(meta("new", created = 30), meta("painted", created = 10, updated = 40), meta("done", created = 50, finished = 60))
        assertEquals("painted", inProgress(paintings)?.id)
    }

    @Test fun aPaintingJustMadeCountsFromWhenItWasMade() {
        val paintings = listOf(meta("old", created = 10, updated = 20), meta("new", created = 30))
        assertEquals("new", inProgress(paintings)?.id)
    }

    @Test fun percentIsTheIOSRule() {
        assertEquals(0, percentPainted(0, 200))
        assertEquals(1, percentPainted(1, 200))
        assertEquals(42, percentPainted(85, 200))
        assertEquals(99, percentPainted(199, 200))
        assertEquals(100, percentPainted(200, 200))
        assertEquals(0, percentPainted(3, 0))
    }
}
