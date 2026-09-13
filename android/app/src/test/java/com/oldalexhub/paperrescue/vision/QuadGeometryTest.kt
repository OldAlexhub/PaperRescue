package com.oldalexhub.paperrescue.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Point

class QuadGeometryTest {
    @Test
    fun ordersShuffledCornersClockwiseFromTopLeft() {
        val ordered = QuadGeometry.orderCorners(arrayOf(
            Point(920.0, 760.0), Point(110.0, 90.0), Point(80.0, 790.0), Point(900.0, 120.0),
        ))

        assertPoint(Point(110.0, 90.0), ordered[0])
        assertPoint(Point(900.0, 120.0), ordered[1])
        assertPoint(Point(920.0, 760.0), ordered[2])
        assertPoint(Point(80.0, 790.0), ordered[3])
        assertTrue(QuadGeometry.isConvex(ordered))
        assertFalse(QuadGeometry.isSelfIntersecting(ordered))
    }

    @Test
    fun ordersSeverelySkewedAndRotatedDocumentWithoutTwisting() {
        val ordered = QuadGeometry.orderCorners(arrayOf(
            Point(120.0, 510.0), Point(470.0, 40.0), Point(890.0, 430.0), Point(520.0, 920.0),
        ))

        assertTrue(QuadGeometry.isConvex(ordered))
        assertFalse(QuadGeometry.isSelfIntersecting(ordered))
        assertTrue(QuadGeometry.area(ordered) > 300_000.0)
        assertTrue(QuadGeometry.validate(ordered, 1000, 1000).valid)
    }

    @Test
    fun detectsSelfIntersectionAndDegenerateQuads() {
        val bowTie = arrayOf(
            Point(100.0, 100.0), Point(900.0, 900.0), Point(900.0, 100.0), Point(100.0, 900.0),
        )
        assertTrue(QuadGeometry.isSelfIntersecting(bowTie))
        assertFalse(QuadGeometry.validate(bowTie, 1000, 1000).valid)

        val nearlyCollinear = arrayOf(
            Point(100.0, 100.0), Point(900.0, 101.0), Point(920.0, 102.0), Point(80.0, 103.0),
        )
        assertFalse(QuadGeometry.validate(nearlyCollinear, 1000, 1000).valid)
    }

    @Test
    fun lineIntersectionIsStableAndRejectsParallelLines() {
        val horizontal = QuadGeometry.Line.through(Point(0.0, 20.0), Point(100.0, 20.0))!!
        val vertical = QuadGeometry.Line.through(Point(35.0, 0.0), Point(35.0, 100.0))!!
        assertPoint(Point(35.0, 20.0), QuadGeometry.intersection(horizontal, vertical)!!)

        val parallel = QuadGeometry.Line.through(Point(0.0, 40.0), Point(100.0, 40.0))!!
        assertEquals(null, QuadGeometry.intersection(horizontal, parallel))
    }

    @Test
    fun normalizedCoordinateConversionRoundTrips() {
        val points = arrayOf(Point(100.0, 80.0), Point(900.0, 70.0), Point(850.0, 720.0), Point(120.0, 750.0))
        val restored = QuadGeometry.denormalize(QuadGeometry.normalize(points, 1000, 800), 1000, 800)
        points.indices.forEach { assertPoint(points[it], restored[it]) }
    }

    @Test
    fun perspectivePlausibilityRanksRectangleAboveExtremeSliver() {
        val rectangle = arrayOf(Point(100.0, 100.0), Point(900.0, 100.0), Point(900.0, 700.0), Point(100.0, 700.0))
        val sliver = arrayOf(Point(100.0, 100.0), Point(900.0, 110.0), Point(610.0, 190.0), Point(390.0, 185.0))
        assertTrue(QuadGeometry.perspectivePlausibility(rectangle) > QuadGeometry.perspectivePlausibility(sliver))
    }

    private fun assertPoint(expected: Point, actual: Point) {
        assertEquals(expected.x, actual.x, 0.0001)
        assertEquals(expected.y, actual.y, 0.0001)
    }
}
