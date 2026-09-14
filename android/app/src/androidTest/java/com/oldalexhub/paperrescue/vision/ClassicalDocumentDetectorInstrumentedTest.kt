package com.oldalexhub.paperrescue.vision

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/** Runs against the real Android OpenCV native library, not a mocked score model. */
@RunWith(AndroidJUnit4::class)
class ClassicalDocumentDetectorInstrumentedTest {
    @Test
    fun enclosingPageBeatsStrongInternalRectangles() {
        val scene = Mat(960, 720, CvType.CV_8UC1, Scalar(27.0))
        val expected = arrayOf(
            Point(72.0, 76.0),
            Point(650.0, 58.0),
            Point(680.0, 900.0),
            Point(48.0, 918.0),
        )
        val page = MatOfPoint(*expected)
        try {
            Imgproc.fillConvexPoly(scene, page, Scalar(218.0))

            // Strong content boxes and rules that must not become the crop boundary.
            Imgproc.rectangle(scene, Point(110.0, 150.0), Point(610.0, 330.0), Scalar(65.0), 8)
            Imgproc.rectangle(scene, Point(390.0, 190.0), Point(590.0, 305.0), Scalar(35.0), -1)
            for (row in 390..780 step 42) {
                Imgproc.line(scene, Point(120.0, row.toDouble()), Point(600.0, row.toDouble()), Scalar(92.0), 5)
            }

            val result = ClassicalDocumentDetector().detect(scene, DetectionMode.STILL)
            val detected = result.quad
            assertNotNull("Expected the enclosing page to be detected", detected)
            assertTrue("Expected a confident page detection, got ${result.confidence}", result.isConfident)
            assertTrue(
                "Internal content won instead of the page: area=${detected?.let { QuadGeometry.area(it.points) / (scene.cols() * scene.rows()) }}",
                detected != null && QuadGeometry.area(detected.points) / (scene.cols() * scene.rows()) > 0.62,
            )
            assertTrue(
                "Detected corners drifted from the enclosing page",
                detected != null && QuadGeometry.cornerRmsDistance(
                    QuadGeometry.orderCorners(detected.points),
                    QuadGeometry.orderCorners(expected),
                    scene.cols(),
                    scene.rows(),
                ) < 0.055,
            )
        } finally {
            page.release()
            scene.release()
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            assertTrue("OpenCV failed to load on the test device", OpenCVLoader.initLocal())
        }
    }
}
