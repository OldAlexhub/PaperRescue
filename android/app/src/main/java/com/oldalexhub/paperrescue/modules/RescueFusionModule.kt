package com.oldalexhub.paperrescue.modules

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.oldalexhub.paperrescue.OpenCVStatus
import com.oldalexhub.paperrescue.util.BitmapIO
import com.oldalexhub.paperrescue.vision.RescueFusion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Exposes the Rescue Scan multi-frame fusion pipeline to JS. */
class RescueFusionModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun getName() = "PaperRescueFusion"

    @ReactMethod
    fun processBurst(framePaths: ReadableArray, outputPath: String, promise: Promise) {
        if (!OpenCVStatus.isReady) {
            promise.reject("E_OPENCV_NOT_READY", "The on-device vision engine failed to initialize.")
            return
        }
        if (framePaths.size() == 0) {
            promise.reject("E_INVALID_ARGS", "Rescue Scan needs at least one captured frame.")
            return
        }
        scope.launch {
            try {
                val paths = (0 until framePaths.size()).map { i -> framePaths.getString(i)!! }
                val report = RescueFusion.processFromFiles(paths)
                val outBitmap = BitmapIO.matToBitmap(DocumentProcessingModule.toRgba(report.output))
                BitmapIO.saveJpeg(outBitmap, File(outputPath), quality = 95)
                report.output.release()

                val result = Arguments.createMap()
                result.putString("path", outputPath)
                result.putInt("width", outBitmap.width)
                result.putInt("height", outBitmap.height)
                result.putInt("framesCaptured", report.framesCaptured)
                result.putInt("framesUsable", report.framesUsable)
                result.putDouble("alignmentConfidence", report.alignmentConfidence)
                result.putBoolean("fallbackUsed", report.fallbackUsed)
                result.putBoolean("glareRegionsFused", report.glareRegionsFused)
                result.putDouble("sharpnessScore", report.sharpnessScore)
                outBitmap.recycle()
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("E_RESCUE_FAILED", e.message, e)
            }
        }
    }
}
