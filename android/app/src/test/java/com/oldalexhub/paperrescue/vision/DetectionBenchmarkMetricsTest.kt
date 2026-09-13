package com.oldalexhub.paperrescue.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Point

class DetectionBenchmarkMetricsTest {
    @Test
    fun identicalPolygonHasPerfectIouAndZeroCornerError() {
        val quad = arrayOf(Point(0.1, 0.1), Point(0.9, 0.1), Point(0.9, 0.9), Point(0.1, 0.9))
        assertEquals(1.0, DetectionBenchmarkMetrics.polygonIou(quad, quad), 1e-8)
        assertEquals(0.0, DetectionBenchmarkMetrics.normalizedCornerError(quad, quad), 1e-8)
    }

    @Test
    fun partialOverlapProducesBoundedIou() {
        val first = arrayOf(Point(0.0, 0.0), Point(0.6, 0.0), Point(0.6, 0.6), Point(0.0, 0.6))
        val second = arrayOf(Point(0.4, 0.4), Point(1.0, 0.4), Point(1.0, 1.0), Point(0.4, 1.0))
        val iou = DetectionBenchmarkMetrics.polygonIou(first, second)
        assertTrue(iou > 0.0)
        assertTrue(iou < 1.0)
    }
}
