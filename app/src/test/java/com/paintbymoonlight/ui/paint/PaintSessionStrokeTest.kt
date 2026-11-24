package com.paintbymoonlight.ui.paint

import com.paintbymoonlight.core.Box
import com.paintbymoonlight.core.Paint
import com.paintbymoonlight.core.Region
import com.paintbymoonlight.core.Scene
import com.paintbymoonlight.data.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A drag paints as one stroke and undoes as one step, as iOS's `beginStroke`/`endStroke`. */
class PaintSessionStrokeTest {
    /** Four 10×10 squares in a row: paint 0, 0, 1, 0. */
    private fun session(): PaintSession {
        fun square(i: Int, paint: Int): Region {
            val x = 10f * i
            return Region(paint, 100f, false, Box(x, 0f, x + 10, 10f), listOf(floatArrayOf(x, 0f, x + 10, 0f, x + 10, 10f, x, 10f)))
        }
        val paints = List(2) { Paint(0, 0f, 0f, 0f, "", "", "") }
        val regions = listOf(square(0, 0), square(1, 0), square(2, 1), square(3, 0))
        val scene = Scene(40f, 10f, false, paints, regions, emptyList(), emptyList(), emptyList())
        return PaintSession(scene, Progress(regions.size))
    }

    @Test fun aStrokePaintsItsPaintAlongTheWayAndUndoesAsOne() {
        val s = session()
        assertEquals(0, s.selected)
        assertTrue(s.beginStroke())
        val painted = s.drag(1f, 5f, 38f, 5f, radius = 1f)
        // In the order it reached them, and never the other paint's area it crossed.
        assertEquals(listOf(0, 1, 3), painted.map { it.region })
        assertFalse(s.progress.isPainted(2))
        assertEquals(3, s.endStroke())
        // A tap after it is a step of its own.
        s.select(1)
        assertTrue(s.tap(25f, 5f, tolerance = 0f) is Tap.Painted)
        assertEquals(2, s.undo())
        assertTrue(s.progress.isPainted(0))
        s.undo()
        assertEquals(0, s.progress.paintedCount)
        assertEquals(null, s.undo())
    }

    @Test fun aStrokeKeepsItsPaintWhenFinishingItMovesTheHandOn() {
        val s = session()
        s.beginStroke()
        s.drag(1f, 5f, 18f, 5f, radius = 1f)
        s.drag(18f, 5f, 38f, 5f, radius = 1f)
        // Paint 0 finished: the hand moved on to paint 1, which the stroke still doesn't paint.
        assertEquals(1, s.selected)
        assertFalse(s.progress.isPainted(2))
        assertEquals(3, s.endStroke())
    }

    @Test fun undoMidStrokeEndsItAndTakesBackWhatItPainted() {
        val s = session()
        s.beginStroke()
        s.drag(1f, 5f, 18f, 5f, radius = 1f)
        assertEquals(2, s.progress.paintedCount)
        s.undo()
        assertEquals(0, s.progress.paintedCount)
        // The rest of the drag paints nothing; its end has nothing left to close.
        assertTrue(s.drag(18f, 5f, 38f, 5f, radius = 1f).isEmpty())
        assertEquals(0, s.endStroke())
        assertEquals(null, s.undo())
    }

    @Test fun theBrushReachesAnAreaWithinItsRadius() {
        val s = session()
        s.beginStroke()
        // Along the top of the row, 3 units above it: a 2-unit brush misses, a 3.2-unit one reaches it and not the next, 3.6 away.
        assertTrue(s.drag(1f, -3f, 8f, -3f, radius = 2f).isEmpty())
        assertEquals(listOf(0), s.drag(1f, -3f, 8f, -3f, radius = 3.2f).map { it.region })
        s.endStroke()
    }
}
