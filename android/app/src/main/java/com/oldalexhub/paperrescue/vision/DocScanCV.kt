package com.oldalexhub.paperrescue.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Core, on-device computer vision used by every scanning feature: edge
 * detection, perspective correction, sharpness/glare/shadow measurement and
 * the enhancement filters. Pure OpenCV — no network calls, no invented data.
 */
object DocScanCV {

    data class Quad(val points: Array<Point>) {
        init { require(points.size == 4) }
    }

    /** Attempts to find the document's 4 corners in [src] (BGR or gray Mat). Returns null if none found. */
    fun findDocumentQuad(src: Mat): Quad? {
        val gray = Mat()
        if (src.channels() > 1) Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY) else src.copyTo(gray)

        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(blurred, edges, 50.0, 150.0)

        val dilated = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, dilated, kernel)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        val imageArea = src.rows().toDouble() * src.cols().toDouble()
        var best: Array<Point>? = null
        var bestArea = 0.0

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < imageArea * 0.15 || area <= bestArea) continue

            val contour2f = MatOfPoint2f(*contour.toArray())
            val perimeter = Imgproc.arcLength(contour2f, true)
            val approx2f = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx2f, 0.02 * perimeter, true)
            val approxPoints = approx2f.toArray()

            if (approxPoints.size == 4 && Imgproc.isContourConvex(MatOfPoint(*approxPoints))) {
                best = approxPoints
                bestArea = area
            }
        }

        gray.release(); blurred.release(); edges.release(); dilated.release(); hierarchy.release()
        contours.forEach { it.release() }

        return best?.let { Quad(orderCorners(it)) }
    }

    /** Orders 4 arbitrary points as top-left, top-right, bottom-right, bottom-left. */
    fun orderCorners(points: Array<Point>): Array<Point> {
        val sorted = points.sortedBy { it.x + it.y }
        val tl = sorted.first()
        val br = sorted.last()
        val remaining = points.filter { it !== tl && it !== br }
        val tr = remaining.maxByOrNull { it.x - it.y } ?: remaining[0]
        val bl = remaining.minByOrNull { it.x - it.y } ?: remaining[1]
        return arrayOf(tl, tr, br, bl)
    }

    /** Full-frame quad (used as a fallback when no document edge is detected). */
    fun fullFrameQuad(mat: Mat): Quad = Quad(
        arrayOf(
            Point(0.0, 0.0),
            Point(mat.cols() - 1.0, 0.0),
            Point(mat.cols() - 1.0, mat.rows() - 1.0),
            Point(0.0, mat.rows() - 1.0),
        )
    )

    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    /** Perspective-corrects [src] to a flat rectangle using the 4 [quad] corners. */
    fun warpToQuad(src: Mat, quad: Quad): Mat {
        val (tl, tr, br, bl) = quad.points
        val widthTop = distance(tl, tr)
        val widthBottom = distance(bl, br)
        val heightLeft = distance(tl, bl)
        val heightRight = distance(tr, br)

        val outWidth = max(widthTop, widthBottom).toInt().coerceAtLeast(1)
        val outHeight = max(heightLeft, heightRight).toInt().coerceAtLeast(1)

        val srcMat = MatOfPoint2f(tl, tr, br, bl)
        val dstMat = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outWidth - 1.0, 0.0),
            Point(outWidth - 1.0, outHeight - 1.0),
            Point(0.0, outHeight - 1.0),
        )

        val transform = Imgproc.getPerspectiveTransform(srcMat, dstMat)
        val dst = Mat()
        Imgproc.warpPerspective(src, dst, transform, Size(outWidth.toDouble(), outHeight.toDouble()))
        transform.release(); srcMat.release(); dstMat.release()
        return dst
    }

    /** Variance of the Laplacian — a standard, well-understood focus/blur proxy. Higher = sharper. */
    fun sharpnessScore(src: Mat): Double {
        val gray = Mat()
        if (src.channels() > 1) Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY) else src.copyTo(gray)
        val laplacian = Mat()
        Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
        val mean = MatOfDouble(); val stddev = MatOfDouble()
        Core.meanStdDev(laplacian, mean, stddev)
        val sigma = stddev.toArray().getOrElse(0) { 0.0 }
        gray.release(); laplacian.release(); mean.release(); stddev.release()
        return sigma * sigma
    }

    /** Fraction (0..1) of pixels that are near-white/blown-out — a glare/overexposure proxy. */
    fun glareRatio(src: Mat): Double {
        val gray = Mat()
        if (src.channels() > 1) Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY) else src.copyTo(gray)
        val mask = Mat()
        Core.inRange(gray, Scalar(248.0), Scalar(255.0), mask)
        val glarePixels = Core.countNonZero(mask)
        val ratio = glarePixels.toDouble() / (gray.rows().toDouble() * gray.cols().toDouble())
        gray.release(); mask.release()
        return ratio
    }

    /** A 0..1 "glare mask" flagging blown-out pixels, used by Rescue Scan fusion. */
    fun glareMask(src: Mat): Mat {
        val gray = Mat()
        if (src.channels() > 1) Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY) else src.copyTo(gray)
        val mask = Mat()
        Core.inRange(gray, Scalar(245.0), Scalar(255.0), mask)
        Imgproc.GaussianBlur(mask, mask, Size(15.0, 15.0), 0.0)
        gray.release()
        return mask
    }

    /** Mean brightness (0..255) and its standard deviation across the page. */
    fun brightnessStats(src: Mat): Pair<Double, Double> {
        val gray = Mat()
        if (src.channels() > 1) Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY) else src.copyTo(gray)
        val mean = MatOfDouble(); val stddev = MatOfDouble()
        Core.meanStdDev(gray, mean, stddev)
        val result = Pair(mean.toArray().getOrElse(0) { 0.0 }, stddev.toArray().getOrElse(0) { 0.0 })
        gray.release(); mean.release(); stddev.release()
        return result
    }

    /**
     * Heuristic shadow severity (0..1): splits the page into a grid and looks at
     * how unevenly lit the blocks are relative to each other. A perfectly lit
     * page scores near 0; a page with a strong shadow gradient scores higher.
     */
    fun shadowSeverity(src: Mat): Double {
        val gray = Mat()
        if (src.channels() > 1) Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY) else src.copyTo(gray)
        val gridSize = 6
        val blockW = gray.cols() / gridSize
        val blockH = gray.rows() / gridSize
        if (blockW < 4 || blockH < 4) { gray.release(); return 0.0 }

        val blockMeans = mutableListOf<Double>()
        for (row in 0 until gridSize) {
            for (col in 0 until gridSize) {
                val rect = Rect(col * blockW, row * blockH, blockW, blockH)
                val block = Mat(gray, rect)
                blockMeans.add(Core.mean(block).`val`[0])
            }
        }
        gray.release()

        val overallMean = blockMeans.average()
        if (overallMean < 1.0) return 0.0
        val variance = blockMeans.sumOf { (it - overallMean) * (it - overallMean) } / blockMeans.size
        val relativeSpread = sqrt(variance) / overallMean
        // A relative spread above ~0.35 reads as a visible shadow gradient.
        return (relativeSpread / 0.35).coerceIn(0.0, 1.0)
    }

    /** How far a quad deviates from a perfect rectangle, 0 (perfect) to 1 (very skewed). */
    fun perspectiveDeviation(quad: Quad): Double {
        val (tl, tr, br, bl) = quad.points
        fun angle(a: Point, b: Point, c: Point): Double {
            val v1x = a.x - b.x; val v1y = a.y - b.y
            val v2x = c.x - b.x; val v2y = c.y - b.y
            val dot = v1x * v2x + v1y * v2y
            val mag = hypot(v1x, v1y) * hypot(v2x, v2y)
            if (mag < 1e-6) return 90.0
            val cos = (dot / mag).coerceIn(-1.0, 1.0)
            return Math.toDegrees(Math.acos(cos))
        }
        val angles = listOf(angle(bl, tl, tr), angle(tl, tr, br), angle(tr, br, bl), angle(br, bl, tl))
        val maxDeviation = angles.maxOf { abs(it - 90.0) }
        return (maxDeviation / 35.0).coerceIn(0.0, 1.0)
    }

    // ---- Enhancement pipeline ----

    data class EnhanceOptions(
        val mode: String, // "original" | "enhanced" | "grayscale" | "bw"
        val brightness: Int = 0, // -100..100
        val contrast: Int = 0, // -100..100
        val sharpen: Int = 0, // 0..100
        val denoise: Int = 0, // 0..100
        val whitenBackground: Boolean = false,
        val reduceShadow: Boolean = false,
    )

    fun applyEnhancements(src: Mat, options: EnhanceOptions): Mat {
        var working = src.clone()

        if (options.whitenBackground || options.reduceShadow || options.mode == "enhanced") {
            val normalized = normalizeIllumination(working, strength = if (options.mode == "enhanced") 0.85 else 0.6)
            working.release()
            working = normalized
        }

        if (options.denoise > 0) {
            val denoised = Mat()
            val strength = options.denoise.coerceIn(0, 100)
            val d = 5 + (strength / 100.0 * 4).toInt()
            val sigma = 20.0 + strength * 0.8
            Imgproc.bilateralFilter(working, denoised, d, sigma, sigma)
            working.release()
            working = denoised
        }

        if (options.contrast != 0 || options.brightness != 0) {
            val adjusted = Mat()
            val alpha = 1.0 + (options.contrast.coerceIn(-100, 100) / 100.0) * 0.6
            val beta = (options.brightness.coerceIn(-100, 100) / 100.0) * 80.0
            working.convertTo(adjusted, -1, alpha, beta)
            working.release()
            working = adjusted
        }

        if (options.sharpen > 0) {
            val sharpened = unsharpMask(working, amount = options.sharpen / 100.0)
            working.release()
            working = sharpened
        }

        when (options.mode) {
            "grayscale" -> {
                val gray = Mat()
                Imgproc.cvtColor(working, gray, Imgproc.COLOR_BGR2GRAY)
                working.release()
                working = gray
            }
            "bw" -> {
                val gray = Mat()
                Imgproc.cvtColor(working, gray, Imgproc.COLOR_BGR2GRAY)
                val binary = Mat()
                Imgproc.adaptiveThreshold(
                    gray, binary, 255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY,
                    35, 15.0,
                )
                gray.release(); working.release()
                working = binary
            }
        }

        return working
    }

    /**
     * Divides out the page's large-scale illumination gradient (estimated via a
     * heavy blur) so the background reads as uniform white. This is the same
     * "flat-fielding" idea used by real scanner software, and doubles as shadow
     * reduction since a cast shadow is itself a slow illumination gradient.
     */
    fun normalizeIllumination(src: Mat, strength: Double): Mat {
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        val background = Mat()
        val kernelSize = (min(src.rows(), src.cols()) / 6).let { if (it % 2 == 0) it + 1 else it }.coerceAtLeast(31)
        Imgproc.GaussianBlur(gray, background, Size(kernelSize.toDouble(), kernelSize.toDouble()), 0.0)

        val grayF = Mat(); val backgroundF = Mat()
        gray.convertTo(grayF, CvType.CV_32F)
        background.convertTo(backgroundF, CvType.CV_32F, 1.0, 1.0) // +1 avoids divide-by-zero

        val whiteLevel = Mat(backgroundF.size(), backgroundF.type(), Scalar(255.0))
        val gain = Mat()
        Core.divide(whiteLevel, backgroundF, gain)
        whiteLevel.release()

        val srcF = Mat()
        src.convertTo(srcF, CvType.CV_32FC3)
        val channels = mutableListOf<Mat>()
        Core.split(srcF, channels)
        val normalizedChannels = channels.map { channel ->
            val out = Mat()
            Core.multiply(channel, gain, out)
            out
        }
        val mergedF = Mat()
        Core.merge(normalizedChannels, mergedF)

        val normalized8U = Mat()
        mergedF.convertTo(normalized8U, CvType.CV_8UC3)

        val blended = Mat()
        Core.addWeighted(normalized8U, strength.coerceIn(0.0, 1.0), src, 1.0 - strength.coerceIn(0.0, 1.0), 0.0, blended)

        gray.release(); background.release(); grayF.release(); backgroundF.release(); gain.release()
        srcF.release(); channels.forEach { it.release() }; normalizedChannels.forEach { it.release() }
        mergedF.release(); normalized8U.release()

        return blended
    }

    /** Local contrast boost (CLAHE on the L channel of Lab) — makes faint text pop without blowing out highlights. */
    fun claheContrast(src: Mat, clipLimit: Double = 2.2): Mat {
        val lab = Mat()
        Imgproc.cvtColor(src, lab, Imgproc.COLOR_BGR2Lab)
        val channels = mutableListOf<Mat>()
        Core.split(lab, channels)

        val clahe = Imgproc.createCLAHE(clipLimit, Size(8.0, 8.0))
        val lChannelOut = Mat()
        clahe.apply(channels[0], lChannelOut)
        channels[0].release()
        channels[0] = lChannelOut

        val mergedLab = Mat()
        Core.merge(channels, mergedLab)
        val result = Mat()
        Imgproc.cvtColor(mergedLab, result, Imgproc.COLOR_Lab2BGR)

        lab.release(); channels.forEach { it.release() }; mergedLab.release()
        return result
    }

    fun unsharpMask(src: Mat, amount: Double): Mat {
        val blurred = Mat()
        Imgproc.GaussianBlur(src, blurred, Size(0.0, 0.0), 3.0)
        val sharpened = Mat()
        val weight = 1.0 + amount * 1.5
        Core.addWeighted(src, weight, blurred, -(weight - 1.0), 0.0, sharpened)
        blurred.release()
        return sharpened
    }

    fun rotate90(src: Mat, times: Int): Mat {
        var result = src.clone()
        val normalizedTimes = ((times % 4) + 4) % 4
        repeat(normalizedTimes) {
            val rotated = Mat()
            Core.rotate(result, rotated, Core.ROTATE_90_CLOCKWISE)
            result.release()
            result = rotated
        }
        return result
    }
}
