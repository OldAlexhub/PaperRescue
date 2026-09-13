package com.oldalexhub.paperrescue.vision

import org.opencv.core.Mat
import kotlin.math.sqrt

/**
 * Turns raw CV measurements into the plain-language "Scan Quality" score shown
 * to the user. This is an internal usability heuristic, not a scientific
 * measurement — thresholds are calibrated by feel, not by a formal study, and
 * the UI must never claim otherwise.
 */
object QualityAnalyzer {

    data class Factors(
        val blur: Int,
        val glare: Int,
        val brightness: Int,
        val shadow: Int,
        val perspective: Int,
        val completeness: Int,
        val resolution: Int,
        val ocrConfidence: Int?,
    )

    data class Report(
        val score: Int,
        val factors: Factors,
        val messages: List<String>,
        val warnings: List<String>,
        val recommendation: String, // "good" | "retake_recommended" | "rescue_recommended"
    )

    fun analyze(
        mat: Mat,
        quadDetected: Boolean,
        quad: DocScanCV.Quad?,
        ocrWordCount: Int?,
    ): Report {
        val lapVariance = DocScanCV.sharpnessScore(mat)
        val lapStd = sqrt(lapVariance)
        val blurFactor = (((lapStd - 4.0) / (26.0 - 4.0)) * 100.0).coerceIn(0.0, 100.0).toInt()

        val glareRatio = DocScanCV.glareRatio(mat)
        val glareFactor = (100.0 - glareRatio * 100.0 * 8.0).coerceIn(0.0, 100.0).toInt()

        val (meanBrightness, _) = DocScanCV.brightnessStats(mat)
        val brightnessFactor = when {
            meanBrightness < 70 -> ((meanBrightness / 70.0) * 60.0).toInt().coerceIn(0, 60)
            meanBrightness > 250 -> 55
            meanBrightness in 150.0..235.0 -> 100
            meanBrightness < 150.0 -> (60 + ((meanBrightness - 70.0) / (150.0 - 70.0)) * 40.0).toInt().coerceIn(0, 100)
            else -> (100 - ((meanBrightness - 235.0) / (250.0 - 235.0)) * 45.0).toInt().coerceIn(0, 100)
        }

        val shadowSeverity = DocScanCV.shadowSeverity(mat)
        val shadowFactor = (100.0 - shadowSeverity * 100.0).coerceIn(0.0, 100.0).toInt()

        val perspectiveFactor = if (quad != null) {
            (100.0 - DocScanCV.perspectiveDeviation(quad) * 100.0).coerceIn(0.0, 100.0).toInt()
        } else 60

        val completenessFactor = if (quadDetected) 100 else 45

        val longEdge = maxOf(mat.rows(), mat.cols())
        val resolutionFactor = ((longEdge / 1800.0) * 100.0).coerceIn(20.0, 100.0).toInt()

        val ocrConfidenceFactor = ocrWordCount?.let { words ->
            ((words / 40.0) * 100.0).coerceIn(0.0, 100.0).toInt()
        }

        val factors = Factors(
            blur = blurFactor,
            glare = glareFactor,
            brightness = brightnessFactor,
            shadow = shadowFactor,
            perspective = perspectiveFactor,
            completeness = completenessFactor,
            resolution = resolutionFactor,
            ocrConfidence = ocrConfidenceFactor,
        )

        val weighted = mutableListOf<Pair<Int, Double>>().apply {
            add(factors.blur to 0.26)
            add(factors.glare to 0.15)
            add(factors.brightness to 0.10)
            add(factors.shadow to 0.10)
            add(factors.perspective to 0.15)
            add(factors.completeness to 0.15)
            add(factors.resolution to 0.05)
            factors.ocrConfidence?.let { add(it to 0.08) }
        }
        val totalWeight = weighted.sumOf { it.second }
        val score = weighted.sumOf { it.first * (it.second / totalWeight) }.toInt().coerceIn(0, 100)

        val messages = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (blurFactor >= 70) messages.add("Text looks sharp") else warnings.add("Possible blur detected — some text may be hard to read")
        if (glareFactor >= 80) messages.add("No significant glare detected") else if (glareFactor < 50) warnings.add("Strong glare detected on the page")
        if (perspectiveFactor >= 80) messages.add("Perspective corrected") else if (perspectiveFactor < 55) warnings.add("Page edges may be skewed")
        if (completenessFactor == 100) messages.add("Full page detected") else warnings.add("Page edge may be missing from the frame")
        if (shadowFactor < 55) warnings.add("Shadow detected across part of the page")
        if (brightnessFactor < 50) warnings.add(if (meanBrightness < 100) "Page looks quite dark" else "Page looks overexposed")
        if (ocrConfidenceFactor != null && ocrConfidenceFactor < 35) warnings.add("OCR confidence is low for this page")

        val recommendation = when {
            score >= 78 -> "good"
            score >= 50 -> "rescue_recommended"
            else -> "retake_recommended"
        }

        return Report(score, factors, messages, warnings, recommendation)
    }
}
