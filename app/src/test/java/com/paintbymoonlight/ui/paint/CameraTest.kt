package com.paintbymoonlight.ui.paint

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraTest {
    /** A 1000 × 500 painting in a 1032 × 1032 view, 16 px kept clear: it fits at 1. */
    private fun camera() = Camera().apply {
        setPainting(width = 1000f, height = 500f, inset = 16f, smallLabelSize = 4f)
        resize(IntSize(1032, 1032)) { fitting(1000f, 500f, 16f) }
    }

    @Test
    fun opensFittedAndCentered() {
        val c = camera()
        assertEquals(1f, c.fit, 1e-4f)
        assertEquals(1f, c.scale, 1e-4f)
        assertEquals(Offset(16f, 266f), c.offset)
    }

    @Test
    fun zoomStaysBetweenTheWholePaintingAndWhereSmallNumbersRead() {
        val c = camera()
        // 15 px over a 4-unit number, at least 4x the fit, at most 40x.
        assertEquals(4f, c.deepest(density = 1f), 1e-4f)
        assertEquals(40f, c.deepest(density = 20f), 1e-4f)
        c.transform(Offset(516f, 516f), Offset.Zero, zoom = 0.2f, maxScale = 4f)
        assertEquals(1f, c.scale, 1e-4f)
        c.transform(Offset(516f, 516f), Offset.Zero, zoom = 100f, maxScale = 4f)
        assertEquals(4f, c.scale, 1e-4f)
    }

    @Test
    fun panningStopsAnInsetPastTheEdges() {
        val c = camera()
        c.transform(Offset(516f, 516f), Offset.Zero, zoom = 2f, maxScale = 4f)
        c.transform(Offset(516f, 516f), Offset(10_000f, 10_000f), zoom = 1f, maxScale = 4f)
        assertEquals(Offset(16f, 16f), c.offset)
        c.transform(Offset(516f, 516f), Offset(-10_000f, -10_000f), zoom = 1f, maxScale = 4f)
        assertEquals(Offset(1032f - 16f - 2000f, 1032f - 16f - 1000f), c.offset)
    }

    @Test
    fun aDoubleTapStepsInAboutTheTapThenBackOut() {
        val c = camera()
        val step = c.stepTarget(Offset(300f, 400f), maxScale = 10f)!!
        assertEquals(2.5f, step.scale, 1e-4f)
        // The canvas point under the tap stays under it.
        val under = (Offset(300f, 400f) - c.offset) / c.scale
        assertEquals(Offset(300f, 400f), under * step.scale + step.offset)
        c.transform(Offset(516f, 516f), Offset.Zero, zoom = 9f, maxScale = 10f)
        assertNull(c.stepTarget(Offset(300f, 400f), maxScale = 10f))
    }

    @Test
    fun aHintZoomsUntilTheNumberReadsWithoutOverflowingTheView() {
        val c = camera()
        // A 4-unit number reads at 17 px at 4.25, a 10-unit area fills 45% of the view far later.
        assertEquals(4.25f, c.revealTarget(500f, 250f, extent = 10f, labelSize = 4f, legiblePx = 17f, maxScale = 40f).scale, 1e-4f)
        // A big area stops where it fills 45% of the view.
        assertEquals(0.45f * 1032 / 400, c.revealTarget(500f, 250f, extent = 400f, labelSize = 4f, legiblePx = 17f, maxScale = 40f).scale, 1e-4f)
        // A big number still flies in to twice the fit, so the eye follows.
        assertEquals(2f, c.revealTarget(500f, 250f, extent = 10f, labelSize = 40f, legiblePx = 17f, maxScale = 40f).scale, 1e-4f)
    }
}
