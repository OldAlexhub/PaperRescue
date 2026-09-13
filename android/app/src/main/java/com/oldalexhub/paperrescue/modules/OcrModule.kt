package com.oldalexhub.paperrescue.modules

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.oldalexhub.paperrescue.util.BitmapIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * On-device OCR via ML Kit's Latin text recognizer. Never invents text: if
 * recognition fails or finds nothing, that is reported plainly to JS rather
 * than papered over.
 */
class OcrModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun getName() = "PaperRescueOcr"

    @ReactMethod
    fun recognize(imagePath: String, promise: Promise) {
        scope.launch {
            try {
                val bitmap = BitmapIO.loadBitmap(imagePath, BitmapIO.MAX_PAGE_DIMENSION)
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val text = Tasks.await(recognizer.process(inputImage), 45, TimeUnit.SECONDS)

                val blocks = Arguments.createArray()
                var wordCount = 0
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox
                        val blockMap = Arguments.createMap()
                        blockMap.putString("text", line.text)
                        val boxArray = Arguments.createArray()
                        if (box != null) {
                            boxArray.pushInt(box.left); boxArray.pushInt(box.top)
                            boxArray.pushInt(box.width()); boxArray.pushInt(box.height())
                        } else {
                            boxArray.pushInt(0); boxArray.pushInt(0); boxArray.pushInt(0); boxArray.pushInt(0)
                        }
                        blockMap.putArray("box", boxArray)
                        blocks.pushMap(blockMap)
                        wordCount += line.text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
                    }
                }

                val result = Arguments.createMap()
                result.putString("text", text.text)
                result.putInt("wordCount", wordCount)
                result.putArray("blocks", blocks)
                result.putInt("imageWidth", bitmap.width)
                result.putInt("imageHeight", bitmap.height)
                bitmap.recycle()
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("E_OCR_FAILED", e.message ?: "Text recognition failed", e)
            }
        }
    }
}
