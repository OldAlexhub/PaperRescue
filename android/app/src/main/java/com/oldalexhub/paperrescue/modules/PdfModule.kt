package com.oldalexhub.paperrescue.modules

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.oldalexhub.paperrescue.util.BitmapIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Builds watermark-free, multi-page PDFs with android.graphics.pdf.PdfDocument.
 * Pages are processed and recycled one at a time so a 50-page document never
 * holds more than one full-resolution bitmap in memory.
 */
class PdfModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun getName() = "PaperRescuePdf"

    // Assumed source scan resolution used to translate pixel dimensions into
    // PDF page points (1pt = 1/72in). 200dpi yields natural Letter/A4-sized pages.
    private val assumedDpi = 200.0

    private data class QualitySettings(val maxDimension: Int, val jpegQuality: Int, val recompress: Boolean)

    private fun qualityFor(preset: String): QualitySettings = when (preset) {
        "smaller" -> QualitySettings(1200, 55, true)
        "balanced" -> QualitySettings(1650, 78, true)
        else -> QualitySettings(BitmapIO.MAX_PAGE_DIMENSION, 95, false) // "original"
    }

    @ReactMethod
    fun buildPdf(pages: ReadableArray, quality: String, outputPath: String, searchable: Boolean, promise: Promise) {
        if (pages.size() == 0) {
            promise.reject("E_INVALID_ARGS", "A document needs at least one page to export.")
            return
        }
        scope.launch {
            val document = PdfDocument()
            try {
                val settings = qualityFor(quality)

                for (i in 0 until pages.size()) {
                    val pageMap = pages.getMap(i) ?: continue
                    val imagePath = pageMap.getString("imagePath") ?: continue

                    var bitmap = BitmapIO.loadBitmap(imagePath, settings.maxDimension)
                    if (settings.recompress) {
                        bitmap = recompress(bitmap, settings.jpegQuality)
                    }

                    val pageWidthPoints = (bitmap.width * 72.0 / assumedDpi)
                    val pageHeightPoints = (bitmap.height * 72.0 / assumedDpi)

                    val pageInfo = PdfDocument.PageInfo.Builder(
                        pageWidthPoints.toInt().coerceAtLeast(1),
                        pageHeightPoints.toInt().coerceAtLeast(1),
                        i + 1,
                    ).create()
                    val page = document.startPage(pageInfo)
                    val canvas = page.canvas
                    canvas.drawBitmap(bitmap, null, RectF(0f, 0f, pageWidthPoints.toFloat(), pageHeightPoints.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))

                    if (searchable && pageMap.hasKey("ocrBlocks") && pageMap.hasKey("ocrImageWidth") && pageMap.hasKey("ocrImageHeight")) {
                        val ocrImageWidth = pageMap.getDouble("ocrImageWidth")
                        val ocrImageHeight = pageMap.getDouble("ocrImageHeight")
                        val blocks = pageMap.getArray("ocrBlocks")
                        if (blocks != null && ocrImageWidth > 0 && ocrImageHeight > 0) {
                            drawInvisibleTextLayer(canvas, blocks, pageWidthPoints / ocrImageWidth, pageHeightPoints / ocrImageHeight)
                        }
                    }

                    document.finishPage(page)
                    bitmap.recycle()
                }

                val outFile = File(outputPath)
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { document.writeTo(it) }

                val result = Arguments.createMap()
                result.putString("path", outputPath)
                result.putDouble("sizeBytes", outFile.length().toDouble())
                result.putInt("pageCount", pages.size())
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("E_PDF_FAILED", e.message ?: "PDF export failed", e)
            } finally {
                document.close()
            }
        }
    }

    private fun recompress(bitmap: Bitmap, jpegQuality: Int): Bitmap {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)
        bitmap.recycle()
        val bytes = stream.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /**
     * Draws each OCR line as fully-transparent, width-matched text over its
     * detected position so the page becomes searchable/selectable without any
     * visible change to the scanned image. Best-effort: if a viewer doesn't
     * honor invisible text rendering the page still displays and prints normally.
     */
    private fun drawInvisibleTextLayer(canvas: Canvas, blocks: ReadableArray, scaleX: Double, scaleY: Double) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            alpha = 0
        }
        for (i in 0 until blocks.size()) {
            val block = blocks.getMap(i) ?: continue
            val text = block.getString("text") ?: continue
            val box = block.getArray("box") ?: continue
            if (box.size() < 4 || text.isBlank()) continue

            val x = box.getInt(0) * scaleX
            val y = box.getInt(1) * scaleY
            val w = box.getInt(2) * scaleX
            val h = box.getInt(3) * scaleY
            if (w <= 0 || h <= 0) continue

            paint.textSize = (h * 0.82).toFloat().coerceAtLeast(3f)
            val measuredWidth = paint.measureText(text)
            canvas.save()
            canvas.translate(x.toFloat(), (y + h * 0.9).toFloat())
            if (measuredWidth > 0) {
                canvas.scale((w / measuredWidth).toFloat().coerceIn(0.1f, 5f), 1f)
            }
            canvas.drawText(text, 0f, 0f, paint)
            canvas.restore()
        }
    }
}
