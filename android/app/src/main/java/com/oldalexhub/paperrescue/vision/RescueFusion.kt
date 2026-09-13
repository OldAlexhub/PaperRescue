package com.oldalexhub.paperrescue.vision

import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import com.oldalexhub.paperrescue.util.BitmapIO

/**
 * Rescue Scan's fusion pipeline: combines a short burst of frames of the same
 * page into a single best result. Every step here is real OpenCV work — if
 * frames can't be reliably aligned, [Report.fallbackUsed] is set and the
 * pipeline degrades to "pick the best single frame and enhance it" rather
 * than pretending to fuse frames that don't line up.
 */
object RescueFusion {

    const val WORKING_DIMENSION = 1600
    private const val MIN_GOOD_MATCHES = 15
    private const val MIN_INLIER_RATIO = 0.45

    data class FrameScore(
        val mat: Mat,
        val quad: DocScanCV.Quad?,
        val sharpness: Double,
        val glareRatio: Double,
        val composite: Double,
    )

    data class Report(
        val output: Mat,
        val quad: DocScanCV.Quad,
        val framesCaptured: Int,
        val framesUsable: Int,
        val alignmentConfidence: Double,
        val fallbackUsed: Boolean,
        val glareRegionsFused: Boolean,
        val sharpnessScore: Double,
        /** Index into the original input list/file paths that was chosen as the reference frame. */
        val referenceFrameIndex: Int,
        /** Pixel size of the reference frame [quad]'s points are expressed in (pre-crop), for normalizing corners. */
        val referenceFrameWidth: Int,
        val referenceFrameHeight: Int,
    )

    private fun scoreFrame(mat: Mat): FrameScore {
        val quad = DocScanCV.findDocumentQuad(mat)
        val sharpness = DocScanCV.sharpnessScore(mat)
        val glare = DocScanCV.glareRatio(mat)
        // Reward sharpness, penalize glare, and strongly prefer frames where a
        // document edge was actually found.
        val composite = (sharpness / 500.0) - (glare * 40.0) + (if (quad != null) 10.0 else 0.0)
        return FrameScore(mat, quad, sharpness, glare, composite)
    }

    private fun alignToReference(reference: FrameScore, candidate: FrameScore): Pair<Mat, Double>? {
        val orb = ORB.create(700)
        val grayRef = Mat(); val grayCand = Mat()
        Imgproc.cvtColor(reference.mat, grayRef, Imgproc.COLOR_BGR2GRAY)
        Imgproc.cvtColor(candidate.mat, grayCand, Imgproc.COLOR_BGR2GRAY)

        val kpRef = MatOfKeyPoint(); val kpCand = MatOfKeyPoint()
        val descRef = Mat(); val descCand = Mat()
        orb.detectAndCompute(grayRef, Mat(), kpRef, descRef)
        orb.detectAndCompute(grayCand, Mat(), kpCand, descCand)

        if (descRef.empty() || descCand.empty()) {
            grayRef.release(); grayCand.release(); kpRef.release(); kpCand.release(); descRef.release(); descCand.release()
            return null
        }

        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
        val knnMatches = mutableListOf<MatOfDMatch>()
        matcher.knnMatch(descCand, descRef, knnMatches, 2)

        val refPointsList = kpRef.toArray()
        val candPointsList = kpCand.toArray()
        val goodCandPts = mutableListOf<Point>()
        val goodRefPts = mutableListOf<Point>()

        for (knn in knnMatches) {
            val matches = knn.toArray()
            if (matches.size < 2) continue
            val (m, n) = matches[0] to matches[1]
            if (m.distance < 0.75 * n.distance) {
                goodCandPts.add(candPointsList[m.queryIdx].pt)
                goodRefPts.add(refPointsList[m.trainIdx].pt)
            }
        }

        grayRef.release(); grayCand.release(); kpRef.release(); kpCand.release(); descRef.release(); descCand.release()
        knnMatches.forEach { it.release() }

        if (goodCandPts.size < MIN_GOOD_MATCHES) return null

        val candMat2f = MatOfPoint2f(*goodCandPts.toTypedArray())
        val refMat2f = MatOfPoint2f(*goodRefPts.toTypedArray())
        val inlierMask = Mat()
        val homography = Calib3d.findHomography(candMat2f, refMat2f, Calib3d.RANSAC, 4.0, inlierMask)
        candMat2f.release(); refMat2f.release()

        if (homography.empty()) { inlierMask.release(); return null }

        val inlierCount = org.opencv.core.Core.countNonZero(inlierMask)
        val inlierRatio = inlierCount.toDouble() / goodCandPts.size.toDouble()
        inlierMask.release()

        if (inlierRatio < MIN_INLIER_RATIO) { homography.release(); return null }

        val warped = Mat()
        Imgproc.warpPerspective(candidate.mat, warped, homography, reference.mat.size())
        homography.release()

        return Pair(warped, inlierRatio)
    }

    /** Loads each burst frame from disk (recycling bitmaps as it goes) and runs [process]. */
    fun processFromFiles(framePaths: List<String>): Report {
        val frames = framePaths.map { path ->
            val bitmap = BitmapIO.loadBitmap(path, WORKING_DIMENSION)
            val mat = BitmapIO.bitmapToMat(bitmap)
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
            bitmap.recycle()
            mat
        }
        return process(frames)
    }

    /**
     * Fuses [frames] (already loaded at [WORKING_DIMENSION]) into one page.
     * Ownership of the input Mats transfers to this function — callers should
     * not reuse them afterward.
     */
    fun process(frames: List<Mat>): Report {
        require(frames.isNotEmpty()) { "Rescue Scan needs at least one captured frame." }

        val scored = frames.map { scoreFrame(it) }
        val referenceIndex = scored
            .withIndex()
            .filter { it.value.quad != null }
            .maxByOrNull { it.value.composite }?.index
            ?: scored.withIndex().maxByOrNull { it.value.composite }!!.index

        val reference = scored[referenceIndex]
        val referenceFrameWidth = reference.mat.cols()
        val referenceFrameHeight = reference.mat.rows()
        val others = scored.filterIndexed { i, _ -> i != referenceIndex }

        val alignedFrames = mutableListOf<Mat>()
        val inlierRatios = mutableListOf<Double>()
        for (other in others) {
            val aligned = try { alignToReference(reference, other) } catch (e: Exception) { null }
            if (aligned != null) {
                alignedFrames.add(aligned.first)
                inlierRatios.add(aligned.second)
            }
        }

        val fallbackUsed = alignedFrames.isEmpty()
        var working = reference.mat.clone()
        var glareRegionsFused = false

        if (!fallbackUsed) {
            val refGlareMask = DocScanCV.glareMask(reference.mat)
            val glarePixelCount = org.opencv.core.Core.countNonZero(refGlareMask)
            if (glarePixelCount > 0) {
                for (alignedFrame in alignedFrames) {
                    val candidateGlare = DocScanCV.glareMask(alignedFrame)
                    // Pixels glare-free in this alternate frame but glared in the reference.
                    val recoverable = Mat()
                    val invCandidateGlare = Mat()
                    org.opencv.core.Core.bitwise_not(candidateGlare, invCandidateGlare)
                    org.opencv.core.Core.bitwise_and(refGlareMask, invCandidateGlare, recoverable)
                    val recoverableCount = org.opencv.core.Core.countNonZero(recoverable)
                    if (recoverableCount > 0) {
                        alignedFrame.copyTo(working, recoverable)
                        glareRegionsFused = true
                    }
                    candidateGlare.release(); invCandidateGlare.release(); recoverable.release()
                }
            }
            refGlareMask.release()
        }

        val quad = reference.quad ?: DocScanCV.findDocumentQuad(working) ?: DocScanCV.fullFrameQuad(working)
        val cropped = DocScanCV.warpToQuad(working, quad)
        working.release()

        val illuminationNormalized = DocScanCV.normalizeIllumination(cropped, strength = 0.65)
        cropped.release()
        val contrastBoosted = DocScanCV.claheContrast(illuminationNormalized)
        illuminationNormalized.release()
        val denoised = Mat()
        Imgproc.bilateralFilter(contrastBoosted, denoised, 7, 45.0, 45.0)
        contrastBoosted.release()
        val sharpened = DocScanCV.unsharpMask(denoised, amount = 0.5)
        denoised.release()

        val finalSharpness = DocScanCV.sharpnessScore(sharpened)

        frames.forEach { if (it !== reference.mat) it.release() }
        reference.mat.release()
        alignedFrames.forEach { it.release() }

        return Report(
            output = sharpened,
            quad = quad,
            framesCaptured = frames.size,
            framesUsable = 1 + alignedFrames.size,
            alignmentConfidence = if (inlierRatios.isEmpty()) 0.0 else inlierRatios.average(),
            fallbackUsed = fallbackUsed,
            glareRegionsFused = glareRegionsFused,
            sharpnessScore = finalSharpness,
            referenceFrameIndex = referenceIndex,
            referenceFrameWidth = referenceFrameWidth,
            referenceFrameHeight = referenceFrameHeight,
        )
    }
}
