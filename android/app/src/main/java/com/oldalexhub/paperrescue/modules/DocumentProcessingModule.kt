package com.oldalexhub.paperrescue.modules

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.oldalexhub.paperrescue.OpenCVStatus
import com.oldalexhub.paperrescue.util.BitmapIO
import com.oldalexhub.paperrescue.vision.DocScanCV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import java.io.File

/** Perspective correction, enhancement filters, thumbnails and rotation — all on-device OpenCV. */
class DocumentProcessingModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun getName() = "PaperRescueImaging"

    private fun requireOpenCv(promise: Promise): Boolean {
        if (!OpenCVStatus.isReady) {
            promise.reject("E_OPENCV_NOT_READY", "The on-device vision engine failed to initialize.")
            return false
        }
        return true
    }

    /** Corners cross the JS bridge as fractions (0..1) of image width/height, so they stay valid no matter what resolution an image is later loaded at. */
    private fun normalizedCornersToPixels(array: ReadableArray, width: Int, height: Int): Array<Point> {
        require(array.size() == 8) { "Expected 8 numbers (4 x/y pairs)." }
        return arrayOf(
            Point(array.getDouble(0) * width, array.getDouble(1) * height),
            Point(array.getDouble(2) * width, array.getDouble(3) * height),
            Point(array.getDouble(4) * width, array.getDouble(5) * height),
            Point(array.getDouble(6) * width, array.getDouble(7) * height),
        )
    }

    private fun quadToNormalizedArray(quad: DocScanCV.Quad, width: Int, height: Int) = Arguments.createArray().apply {
        quad.points.forEach { p -> pushDouble((p.x / width).coerceIn(0.0, 1.0)); pushDouble((p.y / height).coerceIn(0.0, 1.0)) }
    }

    @ReactMethod
    fun detectDocumentCorners(imagePath: String, promise: Promise) {
        if (!requireOpenCv(promise)) return
        scope.launch {
            try {
                val bitmap = BitmapIO.loadBitmap(imagePath, BitmapIO.MAX_ANALYSIS_DIMENSION)
                val mat = BitmapIO.bitmapToMat(bitmap)
                val quad = DocScanCV.findDocumentQuad(mat)
                val result = Arguments.createMap()
                if (quad != null) {
                    result.putArray("corners", quadToNormalizedArray(quad, bitmap.width, bitmap.height))
                    result.putBoolean("detected", true)
                } else {
                    result.putNull("corners")
                    result.putBoolean("detected", false)
                }
                result.putInt("analyzedWidth", bitmap.width)
                result.putInt("analyzedHeight", bitmap.height)
                mat.release(); bitmap.recycle()
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("E_PROCESSING_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun warpPerspective(imagePath: String, corners: ReadableArray, outputPath: String, promise: Promise) {
        if (!requireOpenCv(promise)) return
        scope.launch {
            try {
                val bitmap = BitmapIO.loadBitmap(imagePath, BitmapIO.MAX_PAGE_DIMENSION)
                val mat = BitmapIO.bitmapToMat(bitmap)
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
                val quad = DocScanCV.Quad(normalizedCornersToPixels(corners, bitmap.width, bitmap.height))
                val warped = DocScanCV.warpToQuad(mat, quad)
                val outBitmap = BitmapIO.matToBitmap(toRgba(warped))
                BitmapIO.saveJpeg(outBitmap, File(outputPath))

                val result = Arguments.createMap()
                result.putString("path", outputPath)
                result.putInt("width", outBitmap.width)
                result.putInt("height", outBitmap.height)
                mat.release(); warped.release(); bitmap.recycle(); outBitmap.recycle()
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("E_PROCESSING_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun enhance(imagePath: String, outputPath: String, options: ReadableMap, promise: Promise) {
        if (!requireOpenCv(promise)) return
        scope.launch {
            try {
                val bitmap = BitmapIO.loadBitmap(imagePath, BitmapIO.MAX_PAGE_DIMENSION)
                val mat = BitmapIO.bitmapToMat(bitmap)
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)

                val enhanceOptions = DocScanCV.EnhanceOptions(
                    mode = if (options.hasKey("mode")) options.getString("mode") ?: "original" else "original",
                    brightness = if (options.hasKey("brightness")) options.getInt("brightness") else 0,
                    contrast = if (options.hasKey("contrast")) options.getInt("contrast") else 0,
                    sharpen = if (options.hasKey("sharpen")) options.getInt("sharpen") else 0,
                    denoise = if (options.hasKey("denoise")) options.getInt("denoise") else 0,
                    whitenBackground = options.hasKey("whitenBackground") && options.getBoolean("whitenBackground"),
                    reduceShadow = options.hasKey("reduceShadow") && options.getBoolean("reduceShadow"),
                )

                val processed = DocScanCV.applyEnhancements(mat, enhanceOptions)
                val outBitmap = BitmapIO.matToBitmap(toRgba(processed))
                BitmapIO.saveJpeg(outBitmap, File(outputPath))

                val result = Arguments.createMap()
                result.putString("path", outputPath)
                mat.release(); processed.release(); bitmap.recycle(); outBitmap.recycle()
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("E_PROCESSING_FAILED", e.message, e)
            }
        }
    }

    /** One-tap "Rescue Automatically" for a single already-captured page (no burst available). */
    @ReactMethod
    fun autoRescue(imagePath: String, outputPath: String, promise: Promise) {
        if (!requireOpenCv(promise)) return
        scope.launch {
            try {
                val bitmap = BitmapIO.loadBitmap(imagePath, BitmapIO.MAX_PAGE_DIMENSION)
                val mat = BitmapIO.bitmapToMat(bitmap)
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)

                val normalized = DocScanCV.normalizeIllumination(mat, 0.6)
                val contrasted = DocScanCV.claheContrast(normalized)
                val denoised = Mat()
                Imgproc.bilateralFilter(contrasted, denoised, 7, 40.0, 40.0)
                val sharpened = DocScanCV.unsharpMask(denoised, 0.45)

                val outBitmap = BitmapIO.matToBitmap(toRgba(sharpened))
                BitmapIO.saveJpeg(outBitmap, File(outputPath))

                val result = Arguments.createMap()
                result.putString("path", outputPath)
                listOf(mat, normalized, contrasted, denoised, sharpened).forEach { it.release() }
                bitmap.recycle(); outBitmap.recycle()
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("E_PROCESSING_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun rotateImage(imagePath: String, outputPath: String, degrees: Int, promise: Promise) {
        if (!requireOpenCv(promise)) return
        scope.launch {
            try {
                val bitmap = BitmapIO.loadBitmap(imagePath, BitmapIO.MAX_PAGE_DIMENSION)
                val mat = BitmapIO.bitmapToMat(bitmap)
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
                val rotated = DocScanCV.rotate90(mat, degrees / 90)
                val outBitmap = BitmapIO.matToBitmap(toRgba(rotated))
                BitmapIO.saveJpeg(outBitmap, File(outputPath))
                val result = Arguments.createMap()
                result.putString("path", outputPath)
                mat.release(); rotated.release(); bitmap.recycle(); outBitmap.recycle()
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("E_PROCESSING_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun generateThumbnail(imagePath: String, outputPath: String, maxDimension: Int, promise: Promise) {
        scope.launch {
            try {
                val bitmap = BitmapIO.loadBitmap(imagePath, maxDimension)
                BitmapIO.saveJpeg(bitmap, File(outputPath), quality = 80)
                val result = Arguments.createMap()
                result.putString("path", outputPath)
                bitmap.recycle()
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("E_PROCESSING_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun exportJpeg(imagePath: String, outputPath: String, quality: Int, promise: Promise) {
        scope.launch {
            try {
                val bitmap = BitmapIO.loadBitmap(imagePath, BitmapIO.MAX_PAGE_DIMENSION)
                val outFile = File(outputPath)
                BitmapIO.saveJpeg(bitmap, outFile, quality)
                val result = Arguments.createMap()
                result.putString("path", outputPath)
                result.putDouble("sizeBytes", outFile.length().toDouble())
                bitmap.recycle()
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("E_PROCESSING_FAILED", e.message, e)
            }
        }
    }

    companion object {
        /** Bitmap→Mat via OpenCV's Utils gives RGBA; enhancement math runs in BGR to match desktop OpenCV conventions. */
        fun toRgba(bgrOrGray: Mat): Mat {
            val out = Mat()
            when (bgrOrGray.channels()) {
                1 -> Imgproc.cvtColor(bgrOrGray, out, Imgproc.COLOR_GRAY2RGBA)
                else -> Imgproc.cvtColor(bgrOrGray, out, Imgproc.COLOR_BGR2RGBA)
            }
            return out
        }

        fun toRgbaAny(mat: Mat): Mat = toRgba(mat)
    }
}
