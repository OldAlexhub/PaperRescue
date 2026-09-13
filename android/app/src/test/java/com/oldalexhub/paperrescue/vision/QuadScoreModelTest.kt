package com.oldalexhub.paperrescue.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadScoreModelTest {
    @Test
    fun strongDocumentEvidenceBeatsLargeWeakRectangle() {
        val document = QuadScoreModel.combine(0.82, 0.90, 0.86, 0.72, 0.80, 0.70, 0.75, 0.82)
        val backgroundRectangle = QuadScoreModel.combine(
            area = 1.0,
            geometry = 0.88,
            edge = 0.24,
            contrast = 0.12,
            center = 0.95,
            boundary = 0.40,
            texture = 0.25,
            temporal = 0.25,
            fullFramePenalty = 0.12,
        )
        assertTrue(document > backgroundRectangle)
    }

    @Test
    fun confidenceUsesEvidenceAndCandidateSeparation() {
        val separated = QuadScoreModel.confidence(0.78, 0.46)
        val ambiguous = QuadScoreModel.confidence(0.78, 0.76)
        assertTrue(separated > ambiguous)
        assertTrue(separated >= DocumentDetectionResult.CONFIDENT_THRESHOLD)
        assertFalse(QuadScoreModel.confidence(0.45, 0.10) >= DocumentDetectionResult.CONFIDENT_THRESHOLD)
    }
}
