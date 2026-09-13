package com.oldalexhub.paperrescue.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfInt4
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Multi-signal, scored OpenCV detector used for both live frames and captured stills. */
class ClassicalDocumentDetector : DocumentDetector {
    private data class Representation(val source: DetectionSource, val mat: Mat)
    private data class Segment(val p1: Point, val p2: Point, val line: QuadGeometry.Line, val angle: Double, val length: Double)
    private data class OppositePair(val first: Segment, val second: Segment, val score: Double)

    override fun detect(image: Mat, mode: DetectionMode, previousQuad: DocScanCV.Quad?): DocumentDetectionResult {
        val started = System.nanoTime()
        if (image.empty() || image.cols() < 32 || image.rows() < 32) return DocumentDetectionResult.empty()

        val gray = Mat()
        val gradient = Mat()
        val representations = mutableListOf<Representation>()
        return try {
            when (image.channels()) {
                1 -> image.copyTo(gray)
                4 -> Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGBA2GRAY)
                else -> Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
            }
            buildGradient(gray, gradient)
            representations += buildRepresentations(gray, gradient, mode)

            val rawCandidates = mutableListOf<QuadCandidate>()
            for (representation in representations) {
                rawCandidates += contourCandidates(representation.mat, representation.source, image.cols(), image.rows(), mode)
            }
            val deduplicated = deduplicate(rawCandidates, image.cols(), image.rows())

            val needLineFallback = mode == DetectionMode.STILL && deduplicated.size < 4
            val withLines = if (needLineFallback) {
                deduplicate(deduplicated + lineCandidates(representations, image.cols(), image.rows()), image.cols(), image.rows())
            } else deduplicated

            val sampleCount = if (mode == DetectionMode.LIVE) 28 else 48
            val scored = withLines.mapNotNull {
                QuadScorer.score(it, gray, gradient, previousQuad, sampleCount)
            }.sortedByDescending { it.diagnostics.totalScore }

            val best = scored.firstOrNull()
            val runnerUp = scored.getOrNull(1)?.diagnostics?.totalScore ?: 0.0
            if (best == null) {
                DocumentDetectionResult.empty(elapsedMs(started)).copy(
                    diagnostics = DetectionDiagnostics(elapsedMs(started), rawCandidates.size, 0, 0.0, 0.0, emptyList()),
                )
            } else {
                // Confidence is evidence quality tempered by ambiguity. Similar candidates
                // do not zero confidence because multiple representations often find the same page.
                val confidence = QuadScoreModel.confidence(best.diagnostics.totalScore, runnerUp)
                DocumentDetectionResult(
                    quad = best.candidate.quad,
                    confidence = confidence,
                    source = best.candidate.source,
                    diagnostics = DetectionDiagnostics(
                        processingMs = elapsedMs(started),
                        candidateCount = rawCandidates.size,
                        acceptedCandidateCount = scored.size,
                        bestScore = best.diagnostics.totalScore,
                        runnerUpScore = runnerUp,
                        candidates = scored.take(8).map { it.diagnostics },
                    ),
                )
            }
        } finally {
            gray.release()
            gradient.release()
            representations.forEach { it.mat.release() }
        }
    }

    private fun buildRepresentations(gray: Mat, gradient: Mat, mode: DetectionMode): List<Representation> {
        val result = mutableListOf<Representation>()
        val smoothed = Mat()
        Imgproc.GaussianBlur(gray, smoothed, Size(5.0, 5.0), 0.0)
        result += Representation(DetectionSource.ADAPTIVE_CANNY, adaptiveCanny(smoothed))

        val claheImage = Mat()
        val clahe = Imgproc.createCLAHE(if (mode == DetectionMode.LIVE) 2.2 else 2.8, Size(8.0, 8.0))
        clahe.apply(gray, claheImage)
        val claheSmooth = Mat()
        Imgproc.GaussianBlur(claheImage, claheSmooth, Size(5.0, 5.0), 0.0)
        result += Representation(DetectionSource.CLAHE_CANNY, adaptiveCanny(claheSmooth))

        val gradientBinary = Mat()
        Imgproc.threshold(gradient, gradientBinary, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        result += Representation(DetectionSource.SCHARR, gradientBinary)

        if (mode == DetectionMode.STILL) {
            val illumination = illuminationNormalized(gray)
            val illuminationSmooth = Mat()
            Imgproc.GaussianBlur(illumination, illuminationSmooth, Size(5.0, 5.0), 0.0)
            result += Representation(DetectionSource.ILLUMINATION_CANNY, adaptiveCanny(illuminationSmooth))

            val adaptive = Mat()
            val blockSize = ((min(gray.cols(), gray.rows()) / 24) or 1).coerceIn(15, 51)
            Imgproc.adaptiveThreshold(
                illumination,
                adaptive,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                blockSize,
                7.0,
            )
            result += Representation(DetectionSource.ADAPTIVE_THRESHOLD, adaptive)
            illumination.release()
            illuminationSmooth.release()
        }

        smoothed.release()
        claheImage.release()
        claheSmooth.release()
        return result
    }

    private fun adaptiveCanny(gray: Mat): Mat {
        val mean = MatOfDouble()
        val stddev = MatOfDouble()
        Core.meanStdDev(gray, mean, stddev)
        val center = mean.toArray().firstOrNull() ?: 128.0
        val spread = stddev.toArray().firstOrNull() ?: 32.0
        mean.release()
        stddev.release()
        val lower = (center - 0.85 * spread).coerceIn(12.0, 110.0)
        val upper = max(lower + 28.0, center + 1.35 * spread).coerceIn(55.0, 235.0)
        return Mat().also { Imgproc.Canny(gray, it, lower, upper, 3, true) }
    }

    private fun illuminationNormalized(gray: Mat): Mat {
        val background = Mat()
        var kernel = ((min(gray.cols(), gray.rows()) / 11) or 1).coerceIn(21, 71)
        if (kernel % 2 == 0) kernel++
        Imgproc.GaussianBlur(gray, background, Size(kernel.toDouble(), kernel.toDouble()), 0.0)
        Core.max(background, org.opencv.core.Scalar(1.0), background)
        val normalized = Mat()
        Core.divide(gray, background, normalized, 128.0)
        background.release()
        return normalized
    }

    private fun buildGradient(gray: Mat, destination: Mat) {
        val gx16 = Mat()
        val gy16 = Mat()
        val gx = Mat()
        val gy = Mat()
        Imgproc.Scharr(gray, gx16, CvType.CV_16S, 1, 0)
        Imgproc.Scharr(gray, gy16, CvType.CV_16S, 0, 1)
        Core.convertScaleAbs(gx16, gx)
        Core.convertScaleAbs(gy16, gy)
        Core.addWeighted(gx, 0.5, gy, 0.5, 0.0, destination)
        gx16.release(); gy16.release(); gx.release(); gy.release()
    }

    private fun contourCandidates(
        input: Mat,
        source: DetectionSource,
        width: Int,
        height: Int,
        mode: DetectionMode,
    ): List<QuadCandidate> {
        val kernelSize = if (mode == DetectionMode.LIVE) 3.0 else 5.0
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kernelSize, kernelSize))
        val closed = Mat()
        Imgproc.morphologyEx(input, closed, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), if (mode == DetectionMode.LIVE) 1 else 2)
        Imgproc.dilate(closed, closed, kernel, Point(-1.0, -1.0), 1)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        val imageArea = width.toDouble() * height
        val minimumRatio = if (mode == DetectionMode.LIVE) 0.055 else 0.02
        val result = mutableListOf<QuadCandidate>()
        try {
            for (contour in contours.sortedByDescending { Imgproc.contourArea(it) }.take(if (mode == DetectionMode.LIVE) 12 else 24)) {
                val contourArea = abs(Imgproc.contourArea(contour))
                if (contourArea < imageArea * minimumRatio || contourArea > imageArea * 1.01) continue
                result += approximateQuads(contour, contourArea, source, width, height)
            }
        } finally {
            contours.forEach { it.release() }
            hierarchy.release(); closed.release(); kernel.release()
        }
        return result
    }

    private fun approximateQuads(
        contour: MatOfPoint,
        contourArea: Double,
        source: DetectionSource,
        width: Int,
        height: Int,
    ): List<QuadCandidate> {
        val result = mutableListOf<QuadCandidate>()
        val contour2f = MatOfPoint2f(*contour.toArray())
        val perimeter = Imgproc.arcLength(contour2f, true)
        try {
            for (factor in EPSILON_FACTORS) {
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(contour2f, approx, factor * perimeter, true)
                val points = approx.toArray()
                approx.release()
                if (points.size != 4) continue
                val ordered = try { QuadGeometry.orderCorners(points) } catch (_: IllegalArgumentException) { continue }
                if (QuadGeometry.validate(ordered, width, height).valid) {
                    result += QuadCandidate(DocScanCV.Quad(ordered), source)
                }
            }

            if (result.isEmpty()) {
                val rectangle = Imgproc.minAreaRect(contour2f)
                val boxArea = rectangle.size.width * rectangle.size.height
                val fill = if (boxArea > 1.0) contourArea / boxArea else 0.0
                if (fill >= 0.72) {
                    val box = Array(4) { Point() }
                    rectangle.points(box)
                    val ordered = QuadGeometry.orderCorners(box)
                    if (QuadGeometry.validate(ordered, width, height).valid) {
                        result += QuadCandidate(DocScanCV.Quad(ordered), source, fill.coerceAtMost(1.0))
                    }
                }
            }
        } finally {
            contour2f.release()
        }
        return result
    }

    private fun lineCandidates(representations: List<Representation>, width: Int, height: Int): List<QuadCandidate> {
        if (representations.isEmpty()) return emptyList()
        val combined = Mat.zeros(height, width, CvType.CV_8UC1)
        representations.forEach { Core.bitwise_or(combined, it.mat, combined) }
        val lines = MatOfInt4()
        Imgproc.HoughLinesP(
            combined,
            lines,
            1.0,
            PI / 180.0,
            55,
            min(width, height) * 0.20,
            min(width, height) * 0.045,
        )
        combined.release()

        val rawLines = lines.toArray()
        val segments = rawLines.indices.step(4).mapNotNull { index ->
            if (index + 3 >= rawLines.size) return@mapNotNull null
            val p1 = Point(rawLines[index].toDouble(), rawLines[index + 1].toDouble())
            val p2 = Point(rawLines[index + 2].toDouble(), rawLines[index + 3].toDouble())
            val length = QuadGeometry.distance(p1, p2)
            val line = QuadGeometry.Line.through(p1, p2) ?: return@mapNotNull null
            var angle = atan2(p2.y - p1.y, p2.x - p1.x)
            if (angle < 0.0) angle += PI
            Segment(p1, p2, line, angle, length)
        }.sortedByDescending { it.length }.take(18)
        lines.release()

        val minSeparation = min(width, height) * 0.10
        val pairs = mutableListOf<OppositePair>()
        for (i in segments.indices) for (j in i + 1 until segments.size) {
            val first = segments[i]
            val second = segments[j]
            if (angleDifference(first.angle, second.angle) > Math.toRadians(16.0)) continue
            val midpoint = Point((second.p1.x + second.p2.x) / 2.0, (second.p1.y + second.p2.y) / 2.0)
            val separation = abs(first.line.a * midpoint.x + first.line.b * midpoint.y + first.line.c)
            if (separation < minSeparation) continue
            pairs += OppositePair(first, second, first.length + second.length + separation * 0.4)
        }

        val strongestPairs = pairs.sortedByDescending { it.score }.take(20)
        val result = mutableListOf<QuadCandidate>()
        for (i in strongestPairs.indices) for (j in i + 1 until strongestPairs.size) {
            val a = strongestPairs[i]
            val b = strongestPairs[j]
            val directionDifference = angleDifference(a.first.angle, b.first.angle)
            if (directionDifference < Math.toRadians(38.0) || directionDifference > Math.toRadians(142.0)) continue
            val intersections = arrayOf(
                QuadGeometry.intersection(a.first.line, b.first.line),
                QuadGeometry.intersection(a.first.line, b.second.line),
                QuadGeometry.intersection(a.second.line, b.second.line),
                QuadGeometry.intersection(a.second.line, b.first.line),
            )
            if (intersections.any { it == null }) continue
            val ordered = try {
                QuadGeometry.orderCorners(intersections.filterNotNull().toTypedArray())
            } catch (_: IllegalArgumentException) { continue }
            if (QuadGeometry.validate(ordered, width, height, 2.0).valid) {
                result += QuadCandidate(DocScanCV.Quad(ordered), DetectionSource.LINE_RECONSTRUCTION, 0.82)
            }
        }
        return result.take(12)
    }

    private fun deduplicate(candidates: List<QuadCandidate>, width: Int, height: Int): List<QuadCandidate> {
        val kept = mutableListOf<QuadCandidate>()
        for (candidate in candidates) {
            val duplicateIndex = kept.indexOfFirst {
                QuadGeometry.cornerRmsDistance(candidate.quad.points, it.quad.points, width, height) < 0.018
            }
            if (duplicateIndex < 0) {
                kept += candidate
            } else {
                val existing = kept[duplicateIndex]
                kept[duplicateIndex] = existing.copy(
                    contourFill = max(existing.contourFill, candidate.contourFill),
                    signalCount = existing.signalCount + 1,
                )
            }
        }
        return kept.take(64)
    }

    private fun angleDifference(first: Double, second: Double): Double {
        val direct = abs(first - second)
        return min(direct, PI - direct)
    }

    private fun elapsedMs(started: Long) = (System.nanoTime() - started) / 1_000_000L

    companion object {
        private val EPSILON_FACTORS = doubleArrayOf(0.008, 0.012, 0.016, 0.022, 0.030, 0.040, 0.055)
    }
}
