package com.oldalexhub.paperrescue.vision

import org.opencv.core.Mat
import org.opencv.core.Point
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal data class QuadCandidate(
    val quad: DocScanCV.Quad,
    val source: DetectionSource,
    val contourFill: Double = 1.0,
    val signalCount: Int = 1,
)

internal data class ScoredCandidate(
    val candidate: QuadCandidate,
    val diagnostics: CandidateDiagnostics,
)

/** Pure score aggregation/calibration, separated for deterministic unit tests. */
object QuadScoreModel {
    fun combine(
        area: Double,
        geometry: Double,
        edge: Double,
        contrast: Double,
        center: Double,
        boundary: Double,
        texture: Double,
        temporal: Double,
        sourceAdjustment: Double = 0.0,
        fullFramePenalty: Double = 0.0,
    ): Double = (
        0.13 * area +
            0.22 * geometry +
            0.27 * edge +
            0.14 * contrast +
            0.06 * center +
            0.04 * boundary +
            0.08 * texture +
            0.06 * temporal +
            sourceAdjustment - fullFramePenalty
        ).coerceIn(0.0, 1.0)

    fun confidence(bestScore: Double, runnerUpScore: Double): Double {
        val margin = (bestScore - runnerUpScore).coerceAtLeast(0.0)
        val separation = (margin / 0.18).coerceIn(0.0, 1.0)
        return (bestScore * (0.88 + 0.12 * separation)).coerceIn(0.0, 1.0)
    }
}

/** Scores document evidence explicitly instead of equating "largest rectangle" with "page". */
internal object QuadScorer {
    fun score(
        candidate: QuadCandidate,
        gray: Mat,
        gradient: Mat,
        previousQuad: DocScanCV.Quad?,
        sampleCount: Int,
    ): ScoredCandidate? {
        val points = candidate.quad.points
        val width = gray.cols()
        val height = gray.rows()
        val validation = QuadGeometry.validate(points, width, height)
        if (!validation.valid) return null

        val areaRatio = validation.areaRatio
        val areaScore = when {
            areaRatio < 0.025 -> areaRatio / 0.025 * 0.25
            areaRatio < 0.18 -> 0.25 + (areaRatio - 0.025) / 0.155 * 0.55
            areaRatio <= 0.85 -> 0.80 + (areaRatio - 0.18) / 0.67 * 0.20
            else -> (1.0 - (areaRatio - 0.85) / 0.15 * 0.35).coerceAtLeast(0.55)
        }
        val perspectiveScore = QuadGeometry.perspectivePlausibility(points)
        val diagonal = hypot(width.toDouble(), height.toDouble())
        val sideScore = ((validation.minSide / diagonal - 0.018) / 0.16).coerceIn(0.0, 1.0)
        val geometryScore = (0.75 * perspectiveScore + 0.25 * sideScore) * candidate.contourFill.coerceIn(0.55, 1.0)

        val edgeStrengths = edgeStrengths(points, gradient, sampleCount)
        val averageEdge = edgeStrengths.average()
        val weakestEdge = edgeStrengths.minOrNull() ?: 0.0
        val edgeScore = 0.62 * averageEdge + 0.38 * weakestEdge
        val contrastScore = boundaryContrast(points, gray, sampleCount)

        val center = QuadGeometry.centroid(points)
        val normalizedCenterDistance = hypot(center.x / width - 0.5, center.y / height - 0.5) / 0.707106
        val centerScore = (1.0 - normalizedCenterDistance).coerceIn(0.0, 1.0)

        val minBoundaryDistance = points.minOf {
            min(min(it.x, width - 1.0 - it.x), min(it.y, height - 1.0 - it.y))
        }
        val boundaryScore = (0.40 + 0.60 * (minBoundaryDistance / (min(width, height) * 0.07)).coerceIn(0.0, 1.0))
        val fullFramePenalty = if (areaRatio > 0.92 && minBoundaryDistance < 3.0) 0.12 else 0.0

        val textureScore = internalDocumentStructure(points, gray, gradient)
        val temporalScore = previousQuad?.let {
            val rms = QuadGeometry.cornerRmsDistance(points, it.points, width, height)
            (1.0 - rms / 0.12).coerceIn(0.0, 1.0)
        } ?: 0.55
        val sourceBonus = when (candidate.source) {
            DetectionSource.ADAPTIVE_CANNY, DetectionSource.CLAHE_CANNY -> 0.025
            DetectionSource.ILLUMINATION_CANNY -> 0.02
            DetectionSource.ML_SEGMENTATION -> 0.04
            DetectionSource.TRACKED_PRIOR -> 0.035
            DetectionSource.LINE_RECONSTRUCTION -> -0.015
            else -> 0.0
        } + ((candidate.signalCount - 1) * 0.012).coerceAtMost(0.04)

        val total = QuadScoreModel.combine(
            areaScore,
            geometryScore,
            edgeScore,
            contrastScore,
            centerScore,
            boundaryScore,
            textureScore,
            temporalScore,
            sourceBonus,
            fullFramePenalty,
        )

        return ScoredCandidate(
            candidate,
            CandidateDiagnostics(
                source = candidate.source,
                totalScore = total,
                areaRatio = areaRatio,
                geometryScore = geometryScore,
                edgeScore = edgeScore,
                contrastScore = contrastScore,
                centerScore = centerScore,
                boundaryScore = boundaryScore,
                textureScore = textureScore,
                temporalScore = temporalScore,
                edgeStrengths = edgeStrengths,
                signalCount = candidate.signalCount,
            ),
        )
    }

    private fun edgeStrengths(points: Array<Point>, gradient: Mat, samples: Int): List<Double> =
        points.indices.map { i ->
            val a = points[i]
            val b = points[(i + 1) % 4]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = hypot(dx, dy).coerceAtLeast(1.0)
            val nx = -dy / length
            val ny = dx / length
            var sum = 0.0
            var count = 0
            for (step in 1 until samples) {
                val t = step.toDouble() / samples
                val x = a.x + dx * t
                val y = a.y + dy * t
                var strongest = 0.0
                for (offset in -2..2) {
                    strongest = max(strongest, pixel(gradient, x + nx * offset, y + ny * offset))
                }
                sum += strongest / 255.0
                count++
            }
            if (count == 0) 0.0 else (sum / count).coerceIn(0.0, 1.0)
        }

    /** Samples just inside/outside each edge, so similar-color backgrounds can still compete via gradients. */
    private fun boundaryContrast(points: Array<Point>, gray: Mat, samples: Int): Double {
        val center = QuadGeometry.centroid(points)
        val offset = (min(gray.cols(), gray.rows()) * 0.012).coerceIn(3.0, 10.0)
        val sideScores = points.indices.map { i ->
            val a = points[i]
            val b = points[(i + 1) % 4]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = hypot(dx, dy).coerceAtLeast(1.0)
            var nx = -dy / length
            var ny = dx / length
            val midpoint = Point((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)
            if ((center.x - midpoint.x) * nx + (center.y - midpoint.y) * ny < 0.0) {
                nx = -nx
                ny = -ny
            }
            var difference = 0.0
            var count = 0
            for (step in 2 until samples - 1) {
                val t = step.toDouble() / samples
                val x = a.x + dx * t
                val y = a.y + dy * t
                val inside = pixel(gray, x + nx * offset, y + ny * offset)
                val outside = pixel(gray, x - nx * offset, y - ny * offset)
                difference += abs(inside - outside)
                count++
            }
            if (count == 0) 0.0 else ((difference / count) / 42.0).coerceIn(0.0, 1.0)
        }
        val weakest = sideScores.minOrNull() ?: 0.0
        return 0.7 * sideScores.average() + 0.3 * weakest
    }

    /** Sparse interior sampling rewards paper-like regions with some text/ink, without assuming white paper. */
    private fun internalDocumentStructure(points: Array<Point>, gray: Mat, gradient: Mat): Double {
        val minX = points.minOf { it.x }.toInt().coerceIn(0, gray.cols() - 1)
        val maxX = points.maxOf { it.x }.toInt().coerceIn(0, gray.cols() - 1)
        val minY = points.minOf { it.y }.toInt().coerceIn(0, gray.rows() - 1)
        val maxY = points.maxOf { it.y }.toInt().coerceIn(0, gray.rows() - 1)
        if (maxX <= minX || maxY <= minY) return 0.0

        var luminanceSum = 0.0
        var luminanceSq = 0.0
        var activeGradients = 0
        var count = 0
        for (gy in 1..10) {
            val y = minY + (maxY - minY) * gy / 11
            for (gx in 1..10) {
                val x = minX + (maxX - minX) * gx / 11
                val p = Point(x.toDouble(), y.toDouble())
                if (!containsConvex(points, p)) continue
                val lum = pixel(gray, p.x, p.y)
                luminanceSum += lum
                luminanceSq += lum * lum
                if (pixel(gradient, p.x, p.y) > 32.0) activeGradients++
                count++
            }
        }
        if (count < 12) return 0.0
        val mean = luminanceSum / count
        val std = kotlin.math.sqrt((luminanceSq / count - mean * mean).coerceAtLeast(0.0))
        val consistency = (1.0 - abs(std - 32.0) / 70.0).coerceIn(0.15, 1.0)
        val gradientFraction = activeGradients.toDouble() / count
        val structure = when {
            gradientFraction < 0.015 -> gradientFraction / 0.015 * 0.45
            gradientFraction <= 0.32 -> 1.0
            else -> (1.0 - (gradientFraction - 0.32) / 0.55).coerceAtLeast(0.25)
        }
        return 0.55 * consistency + 0.45 * structure
    }

    private fun containsConvex(points: Array<Point>, p: Point): Boolean {
        var sign = 0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
            val next = when {
                cross > 1e-6 -> 1
                cross < -1e-6 -> -1
                else -> 0
            }
            if (next != 0 && sign != 0 && sign != next) return false
            if (next != 0) sign = next
        }
        return true
    }

    private fun pixel(mat: Mat, x: Double, y: Double): Double {
        val ix = x.toInt().coerceIn(0, mat.cols() - 1)
        val iy = y.toInt().coerceIn(0, mat.rows() - 1)
        return mat.get(iy, ix)?.getOrElse(0) { 0.0 } ?: 0.0
    }
}
