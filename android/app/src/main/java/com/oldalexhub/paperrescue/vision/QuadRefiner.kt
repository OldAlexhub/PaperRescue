package com.oldalexhub.paperrescue.vision

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class QuadRefinementResult(
    val quad: DocScanCV.Quad,
    val refined: Boolean,
    val meanEdgeShiftPixels: Double,
    val reason: String,
)

/** Fits each page edge from full-resolution aligned gradients, then intersects the four robust lines. */
object QuadRefiner {
    private data class EdgePoint(val point: Point, val offset: Double, val strength: Double)

    fun refineDocumentQuad(fullResolutionImage: Mat, initialQuad: DocScanCV.Quad): QuadRefinementResult {
        val width = fullResolutionImage.cols()
        val height = fullResolutionImage.rows()
        val original = DocScanCV.Quad(QuadGeometry.orderCorners(initialQuad.points))
        if (!QuadGeometry.validate(original.points, width, height).valid) {
            return QuadRefinementResult(original, false, 0.0, "invalid_initial_quad")
        }

        val gray = Mat()
        val gx = Mat()
        val gy = Mat()
        try {
            when (fullResolutionImage.channels()) {
                1 -> fullResolutionImage.copyTo(gray)
                4 -> Imgproc.cvtColor(fullResolutionImage, gray, Imgproc.COLOR_RGBA2GRAY)
                else -> Imgproc.cvtColor(fullResolutionImage, gray, Imgproc.COLOR_BGR2GRAY)
            }
            Imgproc.GaussianBlur(gray, gray, org.opencv.core.Size(3.0, 3.0), 0.0)
            Imgproc.Scharr(gray, gx, CvType.CV_32F, 1, 0)
            Imgproc.Scharr(gray, gy, CvType.CV_32F, 0, 1)

            val fitted = mutableListOf<QuadGeometry.Line>()
            val shifts = mutableListOf<Double>()
            for (i in 0 until 4) {
                val edge = collectEdgePoints(original.points[i], original.points[(i + 1) % 4], gx, gy)
                val line = robustFit(edge) ?: return QuadRefinementResult(original, false, 0.0, "weak_edge_$i")
                fitted += line
                shifts += edge.map { abs(it.offset) }.average()
            }

            val intersections = Array(4) { i ->
                QuadGeometry.intersection(fitted[(i + 3) % 4], fitted[i])
                    ?: return QuadRefinementResult(original, false, 0.0, "parallel_edges")
            }
            val ordered = try { QuadGeometry.orderCorners(intersections) } catch (_: IllegalArgumentException) {
                return QuadRefinementResult(original, false, 0.0, "corner_ordering_failed")
            }
            val validation = QuadGeometry.validate(ordered, width, height, boundsTolerance = 3.0)
            if (!validation.valid) return QuadRefinementResult(original, false, 0.0, "invalid_refinement_${validation.reason}")

            val originalArea = QuadGeometry.area(original.points)
            val refinedArea = QuadGeometry.area(ordered)
            val areaRatio = refinedArea / originalArea.coerceAtLeast(1.0)
            if (areaRatio !in 0.72..1.34) return QuadRefinementResult(original, false, 0.0, "area_changed_too_much")

            val diagonal = hypot(width.toDouble(), height.toDouble())
            val maximumCornerMove = original.points.indices.maxOf { QuadGeometry.distance(original.points[it], ordered[it]) }
            if (maximumCornerMove > diagonal * 0.09) {
                return QuadRefinementResult(original, false, 0.0, "corner_moved_too_far")
            }
            if (QuadGeometry.perspectivePlausibility(ordered) + 0.12 < QuadGeometry.perspectivePlausibility(original.points)) {
                return QuadRefinementResult(original, false, 0.0, "perspective_worsened")
            }
            return QuadRefinementResult(
                DocScanCV.Quad(QuadGeometry.clamp(ordered, width, height)),
                true,
                shifts.average(),
                "refined",
            )
        } finally {
            gray.release(); gx.release(); gy.release()
        }
    }

    private fun collectEdgePoints(a: Point, b: Point, gx: Mat, gy: Mat): List<EdgePoint> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = hypot(dx, dy).coerceAtLeast(1.0)
        val nx = -dy / length
        val ny = dx / length
        val searchRadius = (min(gx.cols(), gx.rows()) * 0.018).toInt().coerceIn(6, 38)
        val samples = (length / 14.0).toInt().coerceIn(28, 110)
        val result = mutableListOf<EdgePoint>()

        for (sample in 2 until samples - 1) {
            val t = sample.toDouble() / samples
            val baseX = a.x + dx * t
            val baseY = a.y + dy * t
            var best: EdgePoint? = null
            for (offset in -searchRadius..searchRadius) {
                val x = baseX + nx * offset
                val y = baseY + ny * offset
                if (x < 1.0 || y < 1.0 || x >= gx.cols() - 1.0 || y >= gx.rows() - 1.0) continue
                val ix = x.toInt()
                val iy = y.toInt()
                val gradX = gx.get(iy, ix)?.getOrElse(0) { 0.0 } ?: 0.0
                val gradY = gy.get(iy, ix)?.getOrElse(0) { 0.0 } ?: 0.0
                val aligned = abs(gradX * nx + gradY * ny)
                val directionality = aligned / hypot(gradX, gradY).coerceAtLeast(1.0)
                val score = aligned * (0.55 + 0.45 * directionality) * (1.0 - abs(offset).toDouble() / (searchRadius * 3.0))
                if (best == null || score > best.strength) best = EdgePoint(Point(x, y), offset.toDouble(), score)
            }
            if (best != null) result += best
        }
        if (result.size < 12) return emptyList()

        val strengths = result.map { it.strength }.sorted()
        val strengthFloor = max(80.0, strengths[(strengths.size * 0.35).toInt()])
        val medianOffset = median(result.filter { it.strength >= strengthFloor }.map { it.offset })
        val allowedOffsetSpread = max(2.5, searchRadius * 0.32)
        return result.filter { it.strength >= strengthFloor && abs(it.offset - medianOffset) <= allowedOffsetSpread }
    }

    private fun robustFit(points: List<EdgePoint>): QuadGeometry.Line? {
        if (points.size < 10) return null
        val input = MatOfPoint2f(*points.map { it.point }.toTypedArray())
        val fit = Mat()
        return try {
            Imgproc.fitLine(input, fit, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)
            val vx = fit.get(0, 0)?.getOrElse(0) { 0.0 } ?: 0.0
            val vy = fit.get(1, 0)?.getOrElse(0) { 0.0 } ?: 0.0
            val x0 = fit.get(2, 0)?.getOrElse(0) { 0.0 } ?: 0.0
            val y0 = fit.get(3, 0)?.getOrElse(0) { 0.0 } ?: 0.0
            QuadGeometry.Line.through(Point(x0, y0), Point(x0 + vx, y0 + vy))
        } finally {
            input.release(); fit.release()
        }
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }
}
