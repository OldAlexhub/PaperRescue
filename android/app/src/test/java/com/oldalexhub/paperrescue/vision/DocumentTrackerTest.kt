package com.oldalexhub.paperrescue.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Point

class DocumentTrackerTest {
    private val diagnostics = DetectionDiagnostics(4, 4, 2, 0.80, 0.40, emptyList())
    private val goodQuality = LiveFrameQuality(120.0, 0.01, 145.0, true, true)

    @Test
    fun requiresAllCornersToRemainStable() {
        val tracker = DocumentTracker(requiredStableFrames = 4)
        var state: TrackingResult? = null
        repeat(5) { frame ->
            val shift = frame * 0.4
            state = tracker.update(result(quad(shift)), 1000, 800, goodQuality, frame * 180L)
        }
        assertTrue(state!!.readyForAutoCapture)

        val oneCornerMoved = DocScanCV.Quad(arrayOf(
            Point(130.0, 100.0), Point(900.0, 100.0), Point(900.0, 700.0), Point(100.0, 700.0),
        ))
        state = tracker.update(result(oneCornerMoved), 1000, 800, goodQuality, 1_200L)
        assertFalse(state!!.readyForAutoCapture)
        assertEquals(0, state!!.stableFrameCount)
    }

    @Test
    fun qualityGatesPreventCaptureEvenWhenGeometryIsStable() {
        val tracker = DocumentTracker(requiredStableFrames = 2)
        repeat(3) { tracker.update(result(quad()), 1000, 800, goodQuality, it * 180L) }
        val glare = tracker.update(result(quad()), 1000, 800, goodQuality.copy(glareRatio = 0.20), 800L)
        assertFalse(glare.readyForAutoCapture)
        assertEquals("glare", glare.waitingReason)
    }

    private fun result(quad: DocScanCV.Quad) = DocumentDetectionResult(
        quad,
        0.82,
        DetectionSource.ADAPTIVE_CANNY,
        diagnostics,
    )

    private fun quad(shift: Double = 0.0) = DocScanCV.Quad(arrayOf(
        Point(100.0 + shift, 100.0), Point(900.0 + shift, 100.0),
        Point(900.0 + shift, 700.0), Point(100.0 + shift, 700.0),
    ))
}
