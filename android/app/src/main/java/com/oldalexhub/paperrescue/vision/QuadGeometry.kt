package com.oldalexhub.paperrescue.vision

import org.opencv.core.Point
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Pure geometry helpers kept separate so they can be unit-tested without loading OpenCV native code. */
object QuadGeometry {
    data class Validation(
        val valid: Boolean,
        val reason: String? = null,
        val areaRatio: Double = 0.0,
        val minSide: Double = 0.0,
    )

    data class Line(val a: Double, val b: Double, val c: Double) {
        companion object {
            fun through(p1: Point, p2: Point): Line? {
                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                val length = hypot(dx, dy)
                if (length < 1e-8) return null
                return Line(dy / length, -dx / length, (dx * p1.y - dy * p1.x) / length)
            }
        }
    }

    fun distance(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)

    fun signedArea(points: Array<Point>): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2.0
    }

    fun area(points: Array<Point>): Double = abs(signedArea(points))

    /**
     * Orders the convex hull cyclically, fixes winding, then chooses the uppermost
     * adjacent edge as the top edge. This avoids sum/difference corner swaps on
     * severe trapezoids and guarantees a non-self-intersecting TL/TR/BR/BL cycle.
     */
    fun orderCorners(input: Array<Point>): Array<Point> {
        require(input.size == 4) { "Exactly four corners are required." }
        require(input.all { it.x.isFinite() && it.y.isFinite() }) { "Corners must be finite." }

        val cx = input.sumOf { it.x } / 4.0
        val cy = input.sumOf { it.y } / 4.0
        var cycle = input.map { Point(it.x, it.y) }
            .sortedBy { atan2(it.y - cy, it.x - cx) }
        if (signedArea(cycle.toTypedArray()) < 0.0) cycle = cycle.reversed()

        // Pick an actual hull edge, not merely the two points with the lowest y.
        // The tiny x tie-break makes near-diamond rotations deterministic.
        val spanX = (input.maxOf { it.x } - input.minOf { it.x }).coerceAtLeast(1.0)
        val topEdgeIndex = cycle.indices.minByOrNull { i ->
            val a = cycle[i]
            val b = cycle[(i + 1) % 4]
            (a.y + b.y) / 2.0 + ((a.x + b.x) / 2.0 / spanX) * 1e-4
        } ?: 0
        val edgeA = cycle[topEdgeIndex]
        val edgeB = cycle[(topEdgeIndex + 1) % 4]
        return if (edgeA.x <= edgeB.x) {
            arrayOf(edgeA, edgeB, cycle[(topEdgeIndex + 2) % 4], cycle[(topEdgeIndex + 3) % 4])
        } else {
            arrayOf(edgeB, edgeA, cycle[(topEdgeIndex + 3) % 4], cycle[(topEdgeIndex + 2) % 4])
        }
    }

    fun centroid(points: Array<Point>) = Point(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)

    fun isConvex(points: Array<Point>): Boolean {
        if (points.size != 4) return false
        var sign = 0
        for (i in 0 until 4) {
            val a = points[i]
            val b = points[(i + 1) % 4]
            val c = points[(i + 2) % 4]
            val cross = cross(a, b, c)
            if (abs(cross) < 1e-7) return false
            val nextSign = if (cross > 0.0) 1 else -1
            if (sign != 0 && sign != nextSign) return false
            sign = nextSign
        }
        return true
    }

    fun isSelfIntersecting(points: Array<Point>): Boolean {
        if (points.size != 4) return true
        return segmentsIntersect(points[0], points[1], points[2], points[3]) ||
            segmentsIntersect(points[1], points[2], points[3], points[0])
    }

    fun validate(points: Array<Point>, width: Int, height: Int, boundsTolerance: Double = 1.5): Validation {
        if (points.size != 4 || width <= 1 || height <= 1) return Validation(false, "invalid_input")
        if (points.any { !it.x.isFinite() || !it.y.isFinite() }) return Validation(false, "non_finite")
        if (points.any {
                it.x < -boundsTolerance || it.y < -boundsTolerance ||
                    it.x > width - 1.0 + boundsTolerance || it.y > height - 1.0 + boundsTolerance
            }) return Validation(false, "outside_image")
        if (isSelfIntersecting(points)) return Validation(false, "self_intersection")
        if (!isConvex(points)) return Validation(false, "not_convex")

        val imageArea = width.toDouble() * height.toDouble()
        val areaRatio = area(points) / imageArea
        if (areaRatio < 0.008 || areaRatio > 1.02) return Validation(false, "area", areaRatio)

        val diagonal = hypot(width.toDouble(), height.toDouble())
        val sideLengths = points.indices.map { distance(points[it], points[(it + 1) % 4]) }
        val minSide = sideLengths.minOrNull() ?: 0.0
        if (minSide < max(8.0, diagonal * 0.018)) return Validation(false, "short_side", areaRatio, minSide)

        for (i in 0 until 4) {
            val prev = points[(i + 3) % 4]
            val current = points[i]
            val next = points[(i + 1) % 4]
            val l1 = distance(prev, current)
            val l2 = distance(current, next)
            val normalizedCross = abs(cross(prev, current, next)) / (l1 * l2).coerceAtLeast(1e-8)
            if (normalizedCross < 0.10) return Validation(false, "near_collinear", areaRatio, minSide)
        }
        return Validation(true, areaRatio = areaRatio, minSide = minSide)
    }

    fun intersection(first: Line, second: Line): Point? {
        val determinant = first.a * second.b - second.a * first.b
        if (abs(determinant) < 1e-6) return null
        val x = (first.b * second.c - second.b * first.c) / determinant
        val y = (first.c * second.a - second.c * first.a) / determinant
        if (!x.isFinite() || !y.isFinite()) return null
        return Point(x, y)
    }

    fun cornerRmsDistance(first: Array<Point>, second: Array<Point>, width: Int, height: Int): Double {
        if (first.size != 4 || second.size != 4) return Double.POSITIVE_INFINITY
        val diagonal = hypot(width.toDouble(), height.toDouble()).coerceAtLeast(1.0)
        return kotlin.math.sqrt(first.indices.sumOf {
            val d = distance(first[it], second[it]) / diagonal
            d * d
        } / 4.0)
    }

    fun clamp(points: Array<Point>, width: Int, height: Int): Array<Point> = Array(points.size) { i ->
        Point(
            points[i].x.coerceIn(0.0, width - 1.0),
            points[i].y.coerceIn(0.0, height - 1.0),
        )
    }

    fun normalize(points: Array<Point>, width: Int, height: Int): Array<Point> {
        require(width > 1 && height > 1)
        return Array(points.size) { i -> Point(points[i].x / (width - 1.0), points[i].y / (height - 1.0)) }
    }

    fun denormalize(points: Array<Point>, width: Int, height: Int): Array<Point> {
        require(width > 1 && height > 1)
        return Array(points.size) { i -> Point(points[i].x * (width - 1.0), points[i].y * (height - 1.0)) }
    }

    private fun cross(a: Point, b: Point, c: Point): Double =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun segmentsIntersect(a: Point, b: Point, c: Point, d: Point): Boolean {
        fun orientation(p: Point, q: Point, r: Point) = cross(p, q, r)
        val o1 = orientation(a, b, c)
        val o2 = orientation(a, b, d)
        val o3 = orientation(c, d, a)
        val o4 = orientation(c, d, b)
        return o1 * o2 < 0.0 && o3 * o4 < 0.0
    }

    fun oppositeSideConsistency(points: Array<Point>): Double {
        val sides = points.indices.map { distance(points[it], points[(it + 1) % 4]) }
        fun ratio(a: Double, b: Double) = min(a, b) / max(a, b).coerceAtLeast(1e-8)
        return (ratio(sides[0], sides[2]) + ratio(sides[1], sides[3])) / 2.0
    }

    fun perspectivePlausibility(points: Array<Point>): Double {
        if (!isConvex(points) || isSelfIntersecting(points)) return 0.0
        var worstSin = 1.0
        for (i in 0 until 4) {
            val prev = points[(i + 3) % 4]
            val current = points[i]
            val next = points[(i + 1) % 4]
            val denom = distance(prev, current) * distance(current, next)
            worstSin = min(worstSin, abs(cross(prev, current, next)) / denom.coerceAtLeast(1e-8))
        }
        val angleScore = ((worstSin - 0.10) / 0.55).coerceIn(0.0, 1.0)
        val sideScore = ((oppositeSideConsistency(points) - 0.12) / 0.55).coerceIn(0.0, 1.0)
        val diagonals = distance(points[0], points[2]) to distance(points[1], points[3])
        val diagonalScore = (min(diagonals.first, diagonals.second) / max(diagonals.first, diagonals.second).coerceAtLeast(1e-8) / 0.45)
            .coerceIn(0.0, 1.0)
        return 0.45 * angleScore + 0.30 * sideScore + 0.25 * diagonalScore
    }
}
