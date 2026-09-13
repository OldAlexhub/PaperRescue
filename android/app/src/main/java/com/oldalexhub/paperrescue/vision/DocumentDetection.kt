package com.oldalexhub.paperrescue.vision

import org.opencv.core.Mat

/** Selects the amount of work appropriate for a live frame or a captured still. */
enum class DetectionMode { LIVE, STILL }

enum class DetectionSource {
    ADAPTIVE_CANNY,
    CLAHE_CANNY,
    ILLUMINATION_CANNY,
    ADAPTIVE_THRESHOLD,
    SCHARR,
    LINE_RECONSTRUCTION,
    ML_SEGMENTATION,
    NONE,
}

data class CandidateDiagnostics(
    val source: DetectionSource,
    val totalScore: Double,
    val areaRatio: Double,
    val geometryScore: Double,
    val edgeScore: Double,
    val contrastScore: Double,
    val centerScore: Double,
    val boundaryScore: Double,
    val textureScore: Double,
    val temporalScore: Double,
    val edgeStrengths: List<Double>,
)

data class DetectionDiagnostics(
    val processingMs: Long,
    val candidateCount: Int,
    val acceptedCandidateCount: Int,
    val bestScore: Double,
    val runnerUpScore: Double,
    val candidates: List<CandidateDiagnostics>,
)

/** A low-confidence result may still contain the best quad for manual-crop initialization. */
data class DocumentDetectionResult(
    val quad: DocScanCV.Quad?,
    val confidence: Double,
    val source: DetectionSource,
    val diagnostics: DetectionDiagnostics,
) {
    val isConfident: Boolean get() = quad != null && confidence >= CONFIDENT_THRESHOLD

    companion object {
        const val CONFIDENT_THRESHOLD = 0.58

        fun empty(processingMs: Long = 0L) = DocumentDetectionResult(
            quad = null,
            confidence = 0.0,
            source = DetectionSource.NONE,
            diagnostics = DetectionDiagnostics(processingMs, 0, 0, 0.0, 0.0, emptyList()),
        )
    }
}

interface DocumentDetector {
    /** [previousQuad] is in the same pixel coordinate system as [image]. */
    fun detect(
        image: Mat,
        mode: DetectionMode = DetectionMode.STILL,
        previousQuad: DocScanCV.Quad? = null,
    ): DocumentDetectionResult
}

/**
 * Optional primary detector for a future bundled, verified segmentation model.
 * Implementations must return the model mask-derived quad and calibrated confidence;
 * there is deliberately no pretend model and no runtime download path here.
 */
interface DocumentSegmentationDetector : DocumentDetector

/**
 * Runs an optional on-device segmentation detector first and uses classical CV as
 * a fallback/cross-check. The shipping configuration supplies no ML detector, so
 * it remains entirely offline and uses [ClassicalDocumentDetector].
 */
class HybridDocumentDetector(
    private val classical: DocumentDetector = ClassicalDocumentDetector(),
    private val segmentation: DocumentSegmentationDetector? = null,
) : DocumentDetector {
    override fun detect(image: Mat, mode: DetectionMode, previousQuad: DocScanCV.Quad?): DocumentDetectionResult {
        val ml = segmentation?.detect(image, mode, previousQuad)
        if (ml != null && ml.isConfident) return ml

        val cv = classical.detect(image, mode, previousQuad)
        if (ml == null || cv.confidence >= ml.confidence) return cv
        return ml
    }
}
