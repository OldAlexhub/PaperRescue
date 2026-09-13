package com.oldalexhub.paperrescue.vision

import org.opencv.core.Point
import kotlin.math.abs
import kotlin.math.hypot

/** Dataset-independent metrics used when real, licensed regression images are added. */
object DetectionBenchmarkMetrics {
    data class SampleResult(
        val polygonIou: Double,
        val normalizedCornerError: Double,
        val detected: Boolean,
        val wrongObject: Boolean,
        val needsManualCorrection: Boolean,
        val latencyMs: Long,
    )

    fun normalizedCornerError(predicted: Array<Point>, truth: Array<Point>): Double {
        require(predicted.size == 4 && truth.size == 4)
        return predicted.indices.sumOf { hypot(predicted[it].x - truth[it].x, predicted[it].y - truth[it].y) } / 4.0
    }

    fun polygonIou(firstInput: Array<Point>, secondInput: Array<Point>): Double {
        var clipped = QuadGeometry.orderCorners(firstInput).toList()
        val clip = QuadGeometry.orderCorners(secondInput)
        for (i in clip.indices) {
            val a = clip[i]
            val b = clip[(i + 1) % clip.size]
            if (clipped.isEmpty()) break
            val output = mutableListOf<Point>()
            var previous = clipped.last()
            for (current in clipped) {
                val currentInside = isInside(a, b, current)
                val previousInside = isInside(a, b, previous)
                if (currentInside) {
                    if (!previousInside) segmentLineIntersection(previous, current, a, b)?.let(output::add)
                    output += current
                } else if (previousInside) {
                    segmentLineIntersection(previous, current, a, b)?.let(output::add)
                }
                previous = current
            }
            clipped = output
        }
        val intersection = polygonArea(clipped)
        val union = QuadGeometry.area(firstInput) + QuadGeometry.area(secondInput) - intersection
        return if (union <= 1e-12) 0.0 else (intersection / union).coerceIn(0.0, 1.0)
    }

    private fun isInside(a: Point, b: Point, point: Point): Boolean =
        (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x) >= -1e-9

    private fun segmentLineIntersection(p1: Point, p2: Point, q1: Point, q2: Point): Point? {
        val line1 = QuadGeometry.Line.through(p1, p2) ?: return null
        val line2 = QuadGeometry.Line.through(q1, q2) ?: return null
        return QuadGeometry.intersection(line1, line2)
    }

    private fun polygonArea(points: List<Point>): Double {
        if (points.size < 3) return 0.0
        var value = 0.0
        for (i in points.indices) {
            val next = points[(i + 1) % points.size]
            value += points[i].x * next.y - next.x * points[i].y
        }
        return abs(value) / 2.0
    }
}
