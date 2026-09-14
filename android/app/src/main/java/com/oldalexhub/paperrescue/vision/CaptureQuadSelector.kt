package com.oldalexhub.paperrescue.vision

/** Final arbitration between the captured-frame detector and a stable live-page track. */
data class CaptureQuadSelection(
    val quad: DocScanCV.Quad?,
    val confidence: Double,
    val autoCropAllowed: Boolean,
    val reason: String,
)

object CaptureQuadSelector {
    fun select(
        detection: DocumentDetectionResult,
        trackedPrior: DocScanCV.Quad?,
        trackedConfidence: Double,
        trackedStable: Boolean,
        width: Int,
        height: Int,
    ): CaptureQuadSelection {
        val detected = detection.quad?.takeIf { QuadGeometry.validate(it.points, width, height).valid }
        val prior = trackedPrior?.takeIf { QuadGeometry.validate(it.points, width, height).valid }
        val reliableTrack = prior != null && trackedStable && trackedConfidence >= 0.68

        if (detected == null) {
            return if (reliableTrack) {
                CaptureQuadSelection(prior, trackedConfidence * 0.94, true, "stable_live_fallback")
            } else {
                CaptureQuadSelection(prior, trackedConfidence.coerceIn(0.0, 0.57), false, "manual_no_still_detection")
            }
        }
        if (prior == null) {
            return CaptureQuadSelection(detected, detection.confidence, detection.isConfident, "still_only")
        }

        val disagreement = QuadGeometry.cornerRmsDistance(detected.points, prior.points, width, height)
        val detectedArea = QuadGeometry.area(detected.points)
        val priorArea = QuadGeometry.area(prior.points)
        val smallerInnerWinner = disagreement > 0.065 && detectedArea < priorArea * 0.82

        // A common false positive is a header, text box, or fold spanning only
        // part of the page. A stable multi-frame outer track is stronger evidence
        // than that single-frame, substantially smaller rectangle.
        if (reliableTrack && smallerInnerWinner) {
            return CaptureQuadSelection(prior, trackedConfidence * 0.94, true, "stable_outer_over_inner")
        }

        val unexplainedSevereDisagreement = reliableTrack && disagreement > 0.12
        return CaptureQuadSelection(
            quad = detected,
            confidence = if (unexplainedSevereDisagreement) detection.confidence.coerceAtMost(0.57) else detection.confidence,
            autoCropAllowed = detection.isConfident && !unexplainedSevereDisagreement,
            reason = if (unexplainedSevereDisagreement) "manual_live_still_disagreement" else "still_detection",
        )
    }
}
