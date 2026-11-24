package com.paintbymoonlight.pictures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class TonightTest {
    private val library = SampleLibrary(SampleLibrary.listed(File("src/main/assets/samples/library.json").readText()))

    /**
     * Foundation's own count, `Calendar.dateComponents([.day], from: Date(timeIntervalSinceReferenceDate: 0),
     * to: calendar.startOfDay(for: date)).day`, as swift-corelibs-foundation printed it for each zone and
     * date (east of UTC a part day isn't counted, so those zones are a day behind): iOS's Tonight's Picture
     * turns on it, so the same phone clock shows the same picture on both.
     */
    private val foundation = listOf(
        "America/New_York 2026-11-24 9458",
        "America/New_York 2026-03-08 9197",
        "America/New_York 2026-03-29 9218",
        "America/New_York 2026-10-25 9428",
        "America/New_York 2001-01-01 0",
        "America/New_York 2000-12-31 0",
        "America/New_York 2027-01-01 9496",
        "America/New_York 2018-11-04 6516",
        "UTC 2026-11-24 9458",
        "UTC 2026-03-08 9197",
        "UTC 2026-03-29 9218",
        "UTC 2026-10-25 9428",
        "UTC 2001-01-01 0",
        "UTC 2000-12-31 -1",
        "UTC 2027-01-01 9496",
        "UTC 2018-11-04 6516",
        "Asia/Tokyo 2026-11-24 9457",
        "Asia/Tokyo 2026-03-08 9196",
        "Asia/Tokyo 2026-03-29 9217",
        "Asia/Tokyo 2026-10-25 9427",
        "Asia/Tokyo 2001-01-01 0",
        "Asia/Tokyo 2000-12-31 -1",
        "Asia/Tokyo 2027-01-01 9495",
        "Asia/Tokyo 2018-11-04 6515",
        "Pacific/Kiritimati 2026-11-24 9457",
        "Pacific/Kiritimati 2026-03-08 9196",
        "Pacific/Kiritimati 2026-03-29 9217",
        "Pacific/Kiritimati 2026-10-25 9427",
        "Pacific/Kiritimati 2001-01-01 0",
        "Pacific/Kiritimati 2000-12-31 -1",
        "Pacific/Kiritimati 2027-01-01 9495",
        "Pacific/Kiritimati 2018-11-04 6515",
        "Pacific/Pago_Pago 2026-11-24 9458",
        "Pacific/Pago_Pago 2026-03-08 9197",
        "Pacific/Pago_Pago 2026-03-29 9218",
        "Pacific/Pago_Pago 2026-10-25 9428",
        "Pacific/Pago_Pago 2001-01-01 0",
        "Pacific/Pago_Pago 2000-12-31 0",
        "Pacific/Pago_Pago 2027-01-01 9496",
        "Pacific/Pago_Pago 2018-11-04 6516",
        "Europe/Berlin 2026-11-24 9457",
        "Europe/Berlin 2026-03-08 9196",
        "Europe/Berlin 2026-03-29 9217",
        "Europe/Berlin 2026-10-25 9427",
        "Europe/Berlin 2001-01-01 0",
        "Europe/Berlin 2000-12-31 -1",
        "Europe/Berlin 2027-01-01 9495",
        "Europe/Berlin 2018-11-04 6515",
        "Australia/Lord_Howe 2026-11-24 9457",
        "Australia/Lord_Howe 2026-03-08 9196",
        "Australia/Lord_Howe 2026-03-29 9217",
        "Australia/Lord_Howe 2026-10-25 9427",
        "Australia/Lord_Howe 2001-01-01 0",
        "Australia/Lord_Howe 2000-12-31 -1",
        "Australia/Lord_Howe 2027-01-01 9495",
        "Australia/Lord_Howe 2018-11-04 6515",
        "America/Sao_Paulo 2026-11-24 9458",
        "America/Sao_Paulo 2026-03-08 9197",
        "America/Sao_Paulo 2026-03-29 9218",
        "America/Sao_Paulo 2026-10-25 9428",
        "America/Sao_Paulo 2001-01-01 0",
        "America/Sao_Paulo 2000-12-31 0",
        "America/Sao_Paulo 2027-01-01 9496",
        "America/Sao_Paulo 2018-11-04 6516",
    )

    @Test
    fun dayCountIsFoundations() {
        for (row in foundation) {
            val (zone, day, count) = row.split(" ")
            assertEquals(row, count.toLong(), SampleLibrary.dayNumber(LocalDate.parse(day), ZoneId.of(zone)))
        }
    }

    @Test
    fun tonightIsTheLibrarysPaintingsInTurn() {
        val zone = ZoneId.of("America/New_York")
        assertEquals("oleanders", library.tonight(LocalDate.parse("2026-11-24"), zone)?.id)
        val paintings = library.all(SampleKind.Painting)
        assertEquals(25, paintings.size)
        // Tomorrow is the next painting of library.json's order, whatever its photographs between.
        val next = paintings[(paintings.indexOfFirst { it.id == "oleanders" } + 1) % paintings.size]
        assertEquals(next.id, library.tonight(LocalDate.parse("2026-11-25"), zone)?.id)
    }

    @Test
    fun nightsSkipWhatTheGalleryHolds() {
        val zone = ZoneId.of("UTC")
        val date = LocalDate.parse("2026-11-24")
        val nights = library.nights(date, zone, count = 3, held = setOf("oleanders"))
        assertEquals(3, nights.size)
        assertTrue(nights.none { it.id == "oleanders" })
        assertTrue(nights.all { it.provenance.kind == SampleKind.Painting })
        assertEquals(emptyList<Sample>(), library.nights(date, zone, count = 0, held = emptySet()))
        assertNull(SampleLibrary(emptyList()).tonight(date, zone))
    }

    @Test
    fun theLibraryIsIosOwn() {
        assertEquals(44, library.all.size)
        assertEquals("great-wave", library.all.first().id)
        val wave = library.named("great-wave")!!
        assertEquals("Katsushika Hokusai", wave.painter)
        assertEquals("Katsushika Hokusai, c. 1830–32", wave.provenance.byline)
        assertNull(library.named("delicate-arch")!!.painter)
        for (sample in library.all) assertTrue(sample.id, File("src/main/assets/${SampleLibrary.asset(sample.id)}").isFile)
    }
}
