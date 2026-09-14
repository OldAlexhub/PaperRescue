package com.oldalexhub.paperrescue.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Point

class CaptureQuadSelectorTest {
    @Test
    fun stableOuterTrackBeatsSmallerInternalStillWinner() {
        val outer = quad(80.0, 70.0, 920.0, 1430.0)
        val inner = quad(90.0, 220.0, 910.0, 760.0)
        val selection = CaptureQuadSelector.select(
            detection = detection(inner, 0.79),
            trackedPrior = outer,
            trackedConfidence = 0.76,
            trackedStable = true,
            width = 1000,
            height = 1500,
        )

        assertSame(outer, selection.quad)
        assertTrue(selection.autoCropAllowed)
        assertEquals("stable_outer_over_inner", selection.reason)
    }

    @Test
    fun severeUnexplainedDisagreementRoutesToManualCrop() {
        val tracked = quad(80.0, 70.0, 920.0, 1430.0)
        val displaced = quad(0.0, 400.0, 999.0, 1499.0)
        val selection = CaptureQuadSelector.select(
            detection = detection(displaced, 0.81),
            trackedPrior = tracked,
            trackedConfidence = 0.75,
            trackedStable = true,
            width = 1000,
            height = 1500,
        )

        assertFalse(selection.autoCropAllowed)
        assertTrue(selection.confidence < DocumentDetectionResult.CONFIDENT_THRESHOLD)
        assertEquals("manual_live_still_disagreement", selection.reason)
    }

    private fun quad(left: Double, top: Double, right: Double, bottom: Double) = DocScanCV.Quad(
        arrayOf(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom)),
    )

    private fun detection(quad: DocScanCV.Quad, confidence: Double) = DocumentDetectionResult(
        quad = quad,
        confidence = confidence,
        source = DetectionSource.ADAPTIVE_CANNY,
        diagnostics = DetectionDiagnostics(1L, 1, 1, confidence, 0.0, emptyList()),
    )
}
