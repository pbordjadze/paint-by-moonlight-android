package com.paintbymoonlight.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The reach a drag's brush needs to touch an outline: what decides which areas a stroke paints. */
class SegmentDistanceTest {
    @Test fun crossingSegmentsTouch() {
        assertEquals(0f, segmentDistance(0f, 0f, 10f, 10f, 0f, 10f, 10f, 0f), 0f)
    }

    @Test fun parallelSegmentsAreTheirGapApart() {
        assertEquals(3f, segmentDistance(0f, 0f, 10f, 0f, 0f, 3f, 10f, 3f), 1e-5f)
    }

    @Test fun aSegmentPastTheEndReachesItsNearestEnd() {
        assertEquals(5f, segmentDistance(13f, 4f, 20f, 4f, 0f, 0f, 10f, 0f), 1e-5f)
    }

    @Test fun aPointBrushIsAPointsDistance() {
        assertEquals(2f, segmentDistance(5f, 2f, 5f, 2f, 0f, 0f, 10f, 0f), 1e-5f)
    }
}
