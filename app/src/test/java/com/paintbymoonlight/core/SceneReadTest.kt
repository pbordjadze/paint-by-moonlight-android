package com.paintbymoonlight.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Scene v2 as core/Sources/MoonlightCore/Scene.swift writes it, read as hostile input. */
class SceneReadTest {
    /** Writes a scene field by field, as the Swift writer lays it out. */
    private class Writer {
        private val out = ByteArrayOutputStream()
        fun u32(v: Int) = apply { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()) }
        fun f32(v: Float) = apply { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()) }
        fun string(s: String) = apply { val b = s.encodeToByteArray(); u32(b.size); out.write(b) }
        fun ascii(s: String) = apply { out.write(s.encodeToByteArray()) }
        fun points(vararg p: Float) = apply { u32(p.size / 2); p.forEach { f32(it) } }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun scene(
        flags: Int = 1,
        regionPaint: Int = 1,
        labelRegion: Int = 0,
        edgeRight: Int = -1,
    ): ByteArray = Writer().ascii("MLSC").u32(Scene.VERSION).f32(40f).f32(20f).u32(flags)
        .u32(2)
        .f32(1f).f32(0f).f32(0f).f32(0.9f).f32(0.1f).f32(0.1f).string("Harbor Fog").string("Strong red").string("#FF0000")
        .f32(0f).f32(0f).f32(1f).f32(0.1f).f32(0.1f).f32(0.9f).string("").string("Vivid blue").string("#0000FF")
        .u32(2)
        .u32(0).f32(100f).u32(0).f32(0f).f32(0f).f32(10f).f32(10f).u32(1).points(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        .u32(regionPaint).f32(100f).u32(1).f32(10f).f32(0f).f32(20f).f32(10f).u32(1).points(10f, 0f, 20f, 0f, 20f, 10f, 10f, 10f)
        .u32(1).u32(0).u32(edgeRight).u32(1).points(0f, 0f, 10f, 0f)
        .u32(1).f32(5f).f32(5f).f32(4f).u32(labelRegion).f32(3f)
        .u32(1).points(1f, 1f, 2f, 2f, 3f, 1f)
        .bytes()

    @Test fun readsEverythingTheWriterLays() {
        val s = Scene.parse(scene())
        assertEquals(40f, s.width)
        assertEquals(20f, s.height)
        assertTrue(s.isColoringBook)
        assertEquals(2, s.paints.size)
        assertEquals("Harbor Fog", s.paints[0].name)
        assertEquals("Strong red", s.paints[0].shade)
        assertEquals("#FF0000", s.paints[0].hex)
        assertEquals("1 · Harbor Fog", s.paints[0].numbered(0))
        // Without a nickname, the plain name stands in.
        assertEquals("2 · Vivid blue", s.paints[1].numbered(1))
        assertEquals(0xFFFF0000.toInt(), s.paints[0].color)
        assertEquals(2, s.regions.size)
        assertEquals(1, s.regions[1].paint)
        assertTrue(s.regions[1].isDetail)
        assertFalse(s.regions[0].isDetail)
        assertTrue(s.regions[0].contains(5f, 5f))
        assertFalse(s.regions[0].contains(15f, 5f))
        assertEquals(Edge.OUTSIDE, s.edges[0].right)
        assertTrue(s.edges[0].drawn)
        assertEquals(0, s.labels[0].region)
        assertEquals(3f, s.labels[0].radius)
        assertEquals(6, s.strokePoints[0].size)
    }

    @Test fun flagsZeroIsClassic() {
        assertFalse(Scene.parse(scene(flags = 0)).isColoringBook)
        // Flags it doesn't know leave the coloring book bit alone.
        assertFalse(Scene.parse(scene(flags = 2)).isColoringBook)
    }

    @Test fun stringsAreUtf8() {
        val bytes = Writer().ascii("MLSC").u32(Scene.VERSION).f32(1f).f32(1f).u32(0)
            .u32(1).f32(0f).f32(0f).f32(0f).f32(0f).f32(0f).f32(0f).string("Crème Brûlée").string("Pale 黄").string("#000000")
            .u32(0).u32(0).u32(0).u32(0).bytes()
        val s = Scene.parse(bytes)
        assertEquals("Crème Brûlée", s.paints[0].name)
        assertEquals("Pale 黄", s.paints[0].shade)
    }

    @Test fun everyTruncationIsRefused() {
        val whole = scene()
        for (n in 0 until whole.size) refused(whole.copyOf(n), "cut at $n")
    }

    @Test fun indicesOutOfRangeAreRefused() {
        refused(scene(regionPaint = 2), "a region's paint past the palette")
        refused(scene(regionPaint = -5), "a negative paint")
        refused(scene(labelRegion = 2), "a label's area past the areas")
        refused(scene(edgeRight = 7), "an edge's side past the areas")
        refused(scene(edgeRight = -2), "an edge's side neither an area nor the border")
    }

    @Test fun badHeadersAreRefused() {
        refused(scene().also { it[0] = 'X'.code.toByte() }, "magic")
        refused(scene().also { it[4] = 9 }, "version")
        refused(scene().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(8, Float.NaN) }, "width")
    }

    @Test fun hugeCountsAreRefusedBeforeAllocating() {
        // The paint count, then the first paint's first string length, each made enormous.
        refused(scene().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(20, Int.MAX_VALUE) }, "paints")
        refused(scene().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(48, 0x7FFFFFF0) }, "a string")
        refused(scene().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(20, -1) }, "a negative count")
    }

    @Test fun changedBytesReadOrAreRefused() {
        val whole = scene()
        var state = 0x9E3779B97F4A7C15uL
        repeat(3000) {
            state = state * 6364136223846793005uL + 1442695040888963407uL
            val copy = whole.copyOf()
            copy[((state shr 33) % whole.size.toULong()).toInt()] = (state shr 13).toByte()
            try {
                Scene.parse(copy)
            } catch (_: IllegalArgumentException) {
                // Refused: fine. Anything else thrown fails the test.
            }
        }
    }

    private fun refused(bytes: ByteArray, what: String) {
        try {
            Scene.parse(bytes)
            fail("$what: read")
        } catch (_: IllegalArgumentException) {
        }
    }
}
