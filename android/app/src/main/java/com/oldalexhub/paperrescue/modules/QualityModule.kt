package com.oldalexhub.paperrescue.modules

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.oldalexhub.paperrescue.OpenCVStatus
import com.oldalexhub.paperrescue.util.BitmapIO
import com.oldalexhub.paperrescue.vision.DocScanCV
import com.oldalexhub.paperrescue.vision.QualityAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.opencv.imgproc.Imgproc

/** Computes the plain-language Scan Quality score shown on Page Review / Document Editor. */
class QualityModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun getName() = "PaperRescueQuality"

    @ReactMethod
    fun analyze(imagePath: String, ocrWordCount: Int, hasOcrWordCount: Boolean, promise: Promise) {
        if (!OpenCVStatus.isReady) {
            promise.reject("E_OPENCV_NOT_READY", "The on-device vision engine failed to initialize.")
            return
        }
        scope.launch {
            try {
                val bitmap = BitmapIO.loadBitmap(imagePath, BitmapIO.MAX_ANALYSIS_DIMENSION)
                val mat = BitmapIO.bitmapToMat(bitmap)
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)

                val quad = DocScanCV.findDocumentQuad(mat)
                val report = QualityAnalyzer.analyze(
                    mat = mat,
                    quadDetected = quad != null,
                    quad = quad,
                    ocrWordCount = if (hasOcrWordCount) ocrWordCount else null,
                )

                val result = Arguments.createMap()
                result.putInt("score", report.score)
                result.putString("recommendation", report.recommendation)

                val factors = Arguments.createMap()
                factors.putInt("blur", report.factors.blur)
                factors.putInt("glare", report.factors.glare)
                factors.putInt("brightness", report.factors.brightness)
                factors.putInt("shadow", report.factors.shadow)
                factors.putInt("perspective", report.factors.perspective)
                factors.putInt("completeness", report.factors.completeness)
                factors.putInt("resolution", report.factors.resolution)
                if (report.factors.ocrConfidence != null) {
                    factors.putInt("ocrConfidence", report.factors.ocrConfidence)
                } else {
                    factors.putNull("ocrConfidence")
                }
                result.putMap("factors", factors)

                val messages = Arguments.createArray()
                report.messages.forEach { messages.pushString(it) }
                result.putArray("messages", messages)

                val warnings = Arguments.createArray()
                report.warnings.forEach { warnings.pushString(it) }
                result.putArray("warnings", warnings)

                mat.release(); bitmap.recycle()
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("E_PROCESSING_FAILED", e.message, e)
            }
        }
    }
}
