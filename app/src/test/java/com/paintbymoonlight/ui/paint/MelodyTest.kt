package com.paintbymoonlight.ui.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MelodyTest {
    @Test
    fun playsTheTuneInOrderAndRestsAtEachPhrasesEnd() {
        val melody = Melody()
        val notes = (0 until 16).map { melody.next(now = it * 300L, requested = Sky.Sun) }
        assertEquals(Melody.TUNE.take(16), notes.map { it.index })
        assertTrue(notes[7].endsPhrase)
        assertFalse(notes[6].endsPhrase)
        assertTrue(notes[15].endsPhrase)
    }

    @Test
    fun afterABreathPicksUpAtTheNextPhrase() {
        val melody = Melody()
        repeat(3) { melody.next(now = it * 300L, requested = Sky.Sun) }
        val after = melody.next(now = 600L + Melody.BREATH_MS, requested = Sky.Sun)
        assertEquals(Melody.TUNE[Melody.PHRASE], after.index)
    }

    @Test
    fun theSkyTurnsOnlyWhereAPhraseBegins() {
        val melody = Melody()
        repeat(3) { melody.next(now = it * 300L, requested = Sky.Sun) }
        assertEquals(Sky.Sun, melody.next(now = 900L, requested = Sky.Moon).sky)
        repeat(4) { melody.next(now = 1200L + it * 300L, requested = Sky.Moon) }
        // The ninth note begins the second phrase.
        assertEquals(Sky.Moon, melody.next(now = 2400L, requested = Sky.Moon).sky)
    }
}
