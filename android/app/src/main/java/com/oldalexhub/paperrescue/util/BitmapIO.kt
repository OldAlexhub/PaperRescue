package com.oldalexhub.paperrescue.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.File
import java.io.FileOutputStream

/**
 * Loads, saves and converts page images. Centralised here so every native
 * module downsamples and orients images the same way and never holds more
 * than one full-resolution bitmap in memory at a time.
 */
object BitmapIO {

    /** Long-edge cap applied to any image PaperRescue processes or stores. */
    const val MAX_PAGE_DIMENSION = 2480 // ~300dpi for a Letter/A4 page

    /** Long-edge cap used while scoring/aligning frames, kept small for speed. */
    const val MAX_ANALYSIS_DIMENSION = 1000

    private fun computeInSampleSize(rawWidth: Int, rawHeight: Int, maxDimension: Int): Int {
        var inSampleSize = 1
        val longEdge = maxOf(rawWidth, rawHeight)
        while (longEdge / inSampleSize > maxDimension * 2) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun readExifRotationDegrees(path: String): Int {
        return try {
            val exif = ExifInterface(path)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Loads a bitmap from disk, applying EXIF rotation and downsampling so the
     * long edge never exceeds [maxDimension]. Pass a large maxDimension (e.g.
     * [MAX_PAGE_DIMENSION]) for output-quality work, or [MAX_ANALYSIS_DIMENSION]
     * for fast scoring/alignment passes.
     */
    fun loadBitmap(path: String, maxDimension: Int = MAX_PAGE_DIMENSION): Bitmap {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, boundsOptions)
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            throw IllegalArgumentException("Unable to decode image at $path")
        }

        val sampleOptions = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(boundsOptions.outWidth, boundsOptions.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = BitmapFactory.decodeFile(path, sampleOptions)
            ?: throw IllegalArgumentException("Unable to decode image at $path")

        val rotation = readExifRotationDegrees(path)
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            bitmap = rotated
        }

        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge > maxDimension) {
            val scale = maxDimension.toFloat() / longEdge
            val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            if (scaled != bitmap) bitmap.recycle()
            bitmap = scaled
        }
        return bitmap
    }

    fun saveJpeg(bitmap: Bitmap, outFile: File, quality: Int = 92) {
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        }
    }

    fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        return mat
    }

    fun matToBitmap(mat: Mat): Bitmap {
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bitmap)
        return bitmap
    }
}
