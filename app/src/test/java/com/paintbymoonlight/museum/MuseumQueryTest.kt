package com.paintbymoonlight.museum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Museum's browser shows what iOS's shows: `ios.txt` is iOS's own code run on the same catalog (scripts/museum-reference.sh). */
class MuseumQueryTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/museum/$name")!!
    private val catalog = resource("catalog.json").use(MuseumCatalog::decode)
    private val ios = resource("ios.txt").bufferedReader().readLines()

    private fun expected(name: String) = ios.first { it.startsWith("$name ") }.removePrefix("$name ").split(",").filter { it.isNotEmpty() }
    private fun ids(query: MuseumQuery) = query.works(catalog).map { it.id }

    @Test
    fun aKindNoBuildKnowsIsLeftOut() {
        assertEquals(ios.first().removePrefix("works ").toInt(), catalog.works.size)
        assertFalse(catalog.works.any { it.id == "x-unknown" })
    }

    @Test
    fun ordersAreIos() {
        assertEquals(expected("colorful"), ids(MuseumQuery()))
        assertEquals(expected("ukiyo"), ids(MuseumQuery(movement = "Ukiyo-e")))
        assertEquals(expected("oldest"), ids(MuseumQuery(order = MuseumQuery.Order.Oldest)))
        assertEquals(expected("newest"), ids(MuseumQuery(order = MuseumQuery.Order.Newest)))
        assertEquals(expected("shuffled"), ids(MuseumQuery(order = MuseumQuery.Order.Shuffled, seed = 12345)))
        assertEquals(expected("paintings"), ids(MuseumQuery(kinds = MuseumQuery.Kinds.Paintings)))
        assertEquals(expected("prints"), ids(MuseumQuery(kinds = MuseumQuery.Kinds.PrintsAndDrawings, order = MuseumQuery.Order.Oldest)))
    }

    @Test
    fun searchFoldsCaseAndDiacriticsAndMatchesWordStarts() {
        assertEquals(expected("search-gerome"), ids(MuseumQuery(search = "gerome")))
        assertEquals(expected("search-felicien"), ids(MuseumQuery(search = "FÉLIC ro")))
        assertTrue(ids(MuseumQuery(search = "erome")).isEmpty())
    }

    @Test
    fun paintingTitlesAndSharedNamesAreIos() {
        val titles = ios.filter { it.startsWith("title ") }.associate { line ->
            val rest = line.removePrefix("title ")
            rest.substringBefore(' ') to rest.substringAfter(' ')
        }
        for (work in catalog.works) assertEquals(work.id, titles[work.id], work.paintingTitle)
        assertEquals(ios.first { it.startsWith("shared") }.removePrefix("shared").trim(), catalog.sharedNames.sorted().joinToString("|"))
    }

    @Test
    fun shuffleHashIsIos() {
        val (a, b) = ios.first { it.startsWith("mix ") }.removePrefix("mix ").split(" ")
        assertEquals(a.toULong(), MuseumQuery.mix("aic-100037", 1uL))
        assertEquals(b.toULong(), MuseumQuery.mix("cma-150694", ULong.MAX_VALUE))
    }

    @Test
    fun aWorkPaintedMatchesByNameOrRecordedId() {
        val work = catalog.works.first()
        val name = MuseumPainted.key(work.paintingTitle)
        assertTrue(MuseumPainted.matches(work, setOf(name), emptySet(), emptySet()))
        assertFalse(MuseumPainted.matches(work, setOf(name), emptySet(), setOf(name)))
        assertTrue(MuseumPainted.matches(work, setOf(name), setOf(work.id), setOf(name)))
        assertFalse(MuseumPainted.matches(work, emptySet(), setOf(work.id), emptySet()))
    }
}
