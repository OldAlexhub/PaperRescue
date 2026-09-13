package com.oldalexhub.paperrescue.vision

import org.opencv.core.Point
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

data class LiveFrameQuality(
    val sharpness: Double,
    val glareRatio: Double,
    val meanBrightness: Double,
    val focusConverged: Boolean?,
    val exposureConverged: Boolean?,
)

data class TrackingResult(
    val smoothedQuad: DocScanCV.Quad?,
    val stabilityScore: Double,
    val stableFrameCount: Int,
    val readyForAutoCapture: Boolean,
    val waitingReason: String,
)

/** Four-corner tracking with a responsive EMA and explicit capture-quality gates. */
class DocumentTracker(
    private val requiredStableFrames: Int = 7,
    private val movementTolerance: Double = 0.018,
    private val cooldownMs: Long = 3_500L,
) {
    private var smoothedNormalized: Array<Point>? = null
    private var previousRawNormalized: Array<Point>? = null
    private var previousArea = 0.0
    private var previousPerspective = 0.0
    private var stableFrames = 0
    private var cooldownUntilMs = 0L

    fun update(
        detection: DocumentDetectionResult,
        width: Int,
        height: Int,
        quality: LiveFrameQuality,
        nowMs: Long,
    ): TrackingResult {
        val quad = detection.quad
        if (quad == null || !detection.isConfident || width <= 0 || height <= 0) {
            stableFrames = 0
            previousRawNormalized = null
            return TrackingResult(null, 0.0, 0, false, if (quad == null) "no_document" else "low_confidence")
        }

        val normalized = Array(4) { i -> Point(quad.points[i].x / width, quad.points[i].y / height) }
        val previous = previousRawNormalized
        val maxCornerMovement = if (previous == null) Double.POSITIVE_INFINITY else normalized.indices.maxOf {
            hypot(normalized[it].x - previous[it].x, normalized[it].y - previous[it].y)
        }
        val currentArea = QuadGeometry.area(normalized)
        val areaChange = if (previousArea <= 1e-8) Double.POSITIVE_INFINITY else abs(currentArea - previousArea) / previousArea
        val perspective = DocScanCV.perspectiveDeviation(DocScanCV.Quad(normalized))
        val perspectiveChange = abs(perspective - previousPerspective)

        val movementScore = if (maxCornerMovement.isFinite()) (1.0 - maxCornerMovement / movementTolerance).coerceIn(0.0, 1.0) else 0.0
        val areaScore = if (areaChange.isFinite()) (1.0 - areaChange / 0.055).coerceIn(0.0, 1.0) else 0.0
        val perspectiveScore = (1.0 - perspectiveChange / 0.09).coerceIn(0.0, 1.0)
        val stability = 0.60 * movementScore + 0.25 * areaScore + 0.15 * perspectiveScore

        val geometricallyStable = maxCornerMovement <= movementTolerance && areaChange <= 0.055 && perspectiveChange <= 0.09
        stableFrames = if (geometricallyStable && detection.confidence >= 0.64) stableFrames + 1 else 0
        previousRawNormalized = normalized
        previousArea = currentArea
        previousPerspective = perspective

        val oldSmooth = smoothedNormalized
        val alpha = if (maxCornerMovement > movementTolerance * 1.8) 0.62 else 0.38
        smoothedNormalized = if (oldSmooth == null) normalized else Array(4) { i ->
            Point(
                oldSmooth[i].x * (1.0 - alpha) + normalized[i].x * alpha,
                oldSmooth[i].y * (1.0 - alpha) + normalized[i].y * alpha,
            )
        }
        val smoothedPixels = smoothedNormalized?.let { values ->
            DocScanCV.Quad(Array(4) { i -> Point(values[i].x * width, values[i].y * height) })
        }

        val areaRatio = currentArea
        val reason = when {
            nowMs < cooldownUntilMs -> "cooldown"
            detection.confidence < 0.68 -> "confidence"
            areaRatio < 0.24 -> "move_closer"
            stableFrames < requiredStableFrames -> "hold_steady"
            quality.sharpness < 42.0 -> "focus"
            quality.glareRatio > 0.075 -> "glare"
            quality.meanBrightness !in 38.0..232.0 -> "exposure"
            quality.focusConverged == false -> "autofocus"
            quality.exposureConverged == false -> "auto_exposure"
            else -> "ready"
        }
        return TrackingResult(smoothedPixels, stability, stableFrames, reason == "ready", reason)
    }

    fun markCaptured(nowMs: Long) {
        cooldownUntilMs = nowMs + cooldownMs
        reset(keepCooldown = true)
    }

    fun reset(keepCooldown: Boolean = false) {
        smoothedNormalized = null
        previousRawNormalized = null
        previousArea = 0.0
        previousPerspective = 0.0
        stableFrames = 0
        if (!keepCooldown) cooldownUntilMs = 0L
    }

    fun previousQuad(width: Int, height: Int): DocScanCV.Quad? = smoothedNormalized?.let { values ->
        DocScanCV.Quad(Array(4) { i -> Point(values[i].x * width, values[i].y * height) })
    }
}
