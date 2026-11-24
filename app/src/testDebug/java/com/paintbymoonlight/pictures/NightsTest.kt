package com.paintbymoonlight.pictures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import com.paintbymoonlight.ui.source.whenText
import java.time.LocalDate
import java.util.Locale

/** The museums' schedule is read as hostile input, judged as iOS judges it. */
class NightsTest {
    private fun picture(id: String, image: String = "pictures/$id.jpg", sha: String = "0".repeat(64)) =
        """"$id": {"id": "$id", "title": "A title", "kind": "painting", "creator": "Someone", "year": "1890", "credit": "A museum",
        "license": "CC0", "source": "https://example.org", "image": "$image", "thumb": "thumbs/$id.jpg", "sha256": "$sha"}"""

    private fun schedule(
        pictures: String = picture("cma-1") + "," + picture("cma-2") + "," + picture("cma-3") + "," + picture("aic-4"),
        nights: String = """{"date": "2026-11-23", "picture": "cma-1"}, {"date": "2026-11-24", "picture": "cma-2"}""",
        ballots: String = """{"night": "2026-11-26", "opens": "2026-11-24T08:00:00Z", "closes": "2026-11-25T08:00:00Z", "choices": ["cma-1", "cma-3", "aic-4"]}""",
        format: Int = 1,
    ) = """{"format": $format, "generated": "2026-11-24T19:28:26Z", "pictures": {$pictures}, "nights": [$nights], "ballots": [$ballots]}"""

    @Test
    fun aWellMadeScheduleReads() {
        val read = Nights.decode(schedule())
        assertNotNull(read)
        assertEquals(listOf("cma-1", "cma-2"), read!!.nights.map { it.picture })
        assertEquals(4, read.pictures.size)
    }

    @Test
    fun anythingItCantVouchForIsRefused() {
        assertNull(Nights.decode(schedule(format = 2)))
        assertNull(Nights.decode("not json"))
        assertNull(Nights.decode(schedule(pictures = picture("cma-1", image = "../../shared_prefs/x.jpg"))))
        assertNull(Nights.decode(schedule(pictures = picture("Bad_Id"))))
        assertNull(Nights.decode(schedule(pictures = picture("cma-1", sha = "abc"))))
        assertNull(Nights.decode(schedule(nights = """{"date": "2026-11-24", "picture": "cma-9"}""")))
        assertNull(Nights.decode(schedule(nights = """{"date": "Nov 24", "picture": "cma-1"}""")))
        assertNull(Nights.decode(schedule(ballots = """{"night": "2026-11-26", "opens": "", "closes": "", "choices": ["cma-1", "cma-3"]}""")))
    }

    @Test
    fun anEarlierNightIsLabelledAsThePainterSaysIt() {
        val today = LocalDate.parse("2026-11-24")
        assertEquals("Last Night", whenText("2026-11-23", today, Locale.US))
        assertEquals("Friday", whenText("2026-11-20", today, Locale.US))
        assertEquals("Nov 12", whenText("2026-11-12", today, Locale.US))
    }
}
