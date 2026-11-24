package com.paintbymoonlight.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class SheetsTest {
    private val letter = Paper.LETTER

    @Test
    fun paperFollowsTheRegion() {
        assertEquals(Paper.LETTER, Paper.forCountry("US"))
        assertEquals(Paper.LETTER, Paper.forCountry("ca"))
        assertEquals(Paper.A4, Paper.forCountry("DE"))
        assertEquals(Paper.A4, Paper.forCountry("JP"))
        assertEquals(Paper.A4, Paper.forCountry(null))
    }

    @Test
    fun legibleScaleIsSetByTheSmallestNumberOfEachKind() {
        assertNull(Sheets.legibleScale(emptyList()))
        val numbers = listOf(NumberSize(6f, false), NumberSize(2f, false), NumberSize(1.5f, true))
        // 2.6 / 2 for the usual floor, 2 / 1.5 for a detail area's: the larger wins.
        assertEquals(1.3333334f, Sheets.legibleScale(numbers)!!, 1e-5f)
        assertEquals(1.3f, Sheets.legibleScale(numbers.take(2))!!, 1e-5f)
    }

    @Test
    fun aTemplateWhoseNumbersAreLegibleFitsOnePage() {
        val sheets = Sheets.plan(1100f, 800f, letter.height, letter.width, legible = 0.2f, across = null)
        assertFalse(sheets.isTiled)
        // Fitted to the page body, which its height fills first.
        assertEquals(Sheets.bodyHeight(letter.width) / 800f, sheets.scale, 1e-4f)
        assertTrue(sheets.printedWidth <= Sheets.bodyWidth(letter.height) + 1e-3f)
    }

    @Test
    fun aDetailedTemplateTilesOverlappingSheetsAtALegibleScale() {
        // Its smallest number is 2 canvas units: 1.3 points per unit prints it at 2.6 pt.
        val legible = Sheets.legibleScale(listOf(NumberSize(2f, false)))!!
        val sheets = Sheets.plan(1600f, 1200f, letter.height, letter.width, legible, across = null)
        assertTrue(sheets.isTiled)
        assertTrue(sheets.scale >= legible)
        assertTrue(2f * sheets.scale >= Sheets.MINIMUM_NUMBER - 1e-4f)
        // The grid covers the whole picture, each neighbour overlapping by 18 pt, none empty.
        val last = sheets.window(sheets.columns - 1, sheets.rows - 1)
        assertEquals(sheets.printedWidth, last.x + last.width, 1e-2f)
        assertEquals(sheets.printedHeight, last.y + last.height, 1e-2f)
        assertTrue(last.width > Sheets.OVERLAP && last.height > Sheets.OVERLAP)
        val first = sheets.window(0, 0)
        val second = sheets.window(1, 0)
        assertEquals(Sheets.OVERLAP, first.x + first.width - second.x, 1e-3f)
        assertEquals(sheets.bodyWidth, first.width, 1e-3f)
    }

    @Test
    fun sizesGrowInSheetsAndAreAtMostThree() {
        val sizes = Sheets.sizes(1100f, 1400f, letter.width, letter.height, legible = 0.3f)
        assertEquals(3, sizes.size)
        assertNull(sizes[0].across)
        assertEquals(1, sizes[0].sheets.count)
        for (i in 1 until sizes.size) {
            assertTrue(sizes[i].sheets.count > sizes[i - 1].sheets.count)
            val s = sizes[i].sheets
            assertEquals(sizes[i].across, max(s.columns, s.rows))
        }
    }

    @Test
    fun withoutNumbersOnePageIsEnough() {
        val sheets = Sheets.plan(3000f, 2000f, letter.height, letter.width, legible = null, across = null)
        assertEquals(1, sheets.count)
    }

    @Test
    fun tinyPaperPrintsOnePageFitted() {
        // A9, 37 × 52 mm: no room for a body beside the margins, let alone overlapping sheets.
        for (paper in listOf(Paper(105f, 147f), Paper(74f, 105f), Paper(100f, 140f))) {
            val sheets = Sheets.plan(1100f, 800f, paper.width, paper.height, legible = 2f, across = 4)
            assertEquals(1, sheets.count)
            assertTrue(sheets.scale > 0f && sheets.scale.isFinite())
        }
    }
}
