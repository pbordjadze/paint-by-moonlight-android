package com.paintbymoonlight.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class FreePaintingsTest {
    private val newYork = ZoneId.of("America/New_York")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun at(text: String, zone: ZoneId = newYork) = LocalDateTime.parse(text).atZone(zone).toInstant()

    @Test
    fun beginsWithFive() {
        val free = FreePaintings()
        assertEquals(5, free.left)
        assertNull(free.since)
        assertEquals(free, free.at(at("2026-11-24T10:00"), newYork))
    }

    @Test
    fun everyPaintingTakesOneUntilNoneAreLeft() {
        var free = FreePaintings()
        val now = at("2026-11-24T10:00")
        repeat(5) { free = free.take(now, newYork)!! }
        assertEquals(0, free.left)
        assertNull(free.take(now, newYork))
        assertNull(free.take(at("2026-11-24T23:59"), newYork))
    }

    @Test
    fun theCountBeginsAtTheStartOfTheDayOneWasUsed() {
        val free = FreePaintings().take(at("2026-11-24T22:30"), newYork)!!
        assertEquals(4, free.left)
        assertEquals(at("2026-11-24T00:00"), free.since)
    }

    @Test
    fun oneComesBackAtEachMidnight() {
        var free = FreePaintings()
        repeat(5) { free = free.take(at("2026-11-24T21:00"), newYork)!! }
        assertEquals(0, free.at(at("2026-11-24T23:59"), newYork).left)
        val tomorrow = free.at(at("2026-11-25T00:01"), newYork)
        assertEquals(1, tomorrow.left)
        assertEquals(at("2026-11-25T00:00"), tomorrow.since)
        assertEquals(3, free.at(at("2026-11-27T08:00"), newYork).left)
    }

    @Test
    fun neverMoreThanFiveWait() {
        val free = FreePaintings(0, at("2026-11-01T00:00"))
        val later = free.at(at("2027-03-01T12:00"), newYork)
        assertEquals(5, later.left)
        assertNull(later.since)
    }

    @Test
    fun takingAfterADayBackKeepsCountingFromThatDay() {
        var free = FreePaintings(0, at("2026-11-24T00:00"))
        free = free.take(at("2026-11-26T09:00"), newYork)!!
        // Two came back at the two midnights, and one was taken.
        assertEquals(1, free.left)
        assertEquals(at("2026-11-26T00:00"), free.since)
    }

    @Test
    fun aClockSetBackBeforeTheCountBeganGivesThemAllBack() {
        val free = FreePaintings(0, at("2026-11-24T00:00"))
        assertEquals(FreePaintings(), free.at(at("2026-11-20T12:00"), newYork))
    }

    @Test
    fun aClockSetBackWithinTheDayChangesNothing() {
        val free = FreePaintings(2, at("2026-11-24T00:00"))
        assertEquals(free, free.at(at("2026-11-24T01:00"), newYork))
    }

    @Test
    fun aTimeZoneCrossedMovesTheCountNoMoreThanTheClock() {
        // Used up in New York in the evening; the same instant is already the next day in Tokyo.
        val usedAt = at("2026-11-24T20:00")
        var free = FreePaintings()
        repeat(5) { free = free.take(usedAt, newYork)!! }
        // An hour later in Tokyo: the 24th's midnight in New York is the 24th in Tokyo too (14:00),
        // and Tokyo's clock reads the 25th: one back.
        assertEquals(1, free.at(usedAt.plusSeconds(3600), tokyo).left)
        // Flying back west, the same instant reads the 24th again: none.
        assertEquals(0, free.at(usedAt.plusSeconds(3600), newYork).left)
    }

    @Test
    fun daylightSavingTimeChangesNothingButTheDay() {
        // New York falls back on November 1, 2026: that day is 25 hours long.
        val free = FreePaintings(0, at("2026-10-31T00:00"))
        assertEquals(1, free.at(at("2026-11-01T23:30"), newYork).left)
        assertEquals(2, free.at(at("2026-11-02T00:00"), newYork).left)
    }

    @Test
    fun storedValuesAreKeptInBounds() {
        assertEquals(5, FreePaintings(9, at("2026-11-24T00:00")).left)
        assertNull(FreePaintings(9, at("2026-11-24T00:00")).since)
        assertEquals(0, FreePaintings(-3, at("2026-11-24T00:00")).left)
        // Fewer than five with no day to count from: as if all were left.
        assertEquals(FreePaintings(), FreePaintings(2, null).at(at("2026-11-24T12:00"), newYork))
    }
}
