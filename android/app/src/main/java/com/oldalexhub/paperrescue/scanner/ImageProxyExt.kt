package com.oldalexhub.paperrescue.scanner

import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.CvType

/**
 * Extracts the Y (luma) plane of a YUV_420_888 camera frame as a grayscale
 * Mat in crop-rect coordinates, rotated into the use case's display orientation.
 * CameraX can then map those rotated coordinates into PreviewView exactly;
 * luma alone keeps the live-preview analysis pass fast and allocation-light.
 */
fun ImageProxy.toGrayMat(): Mat {
    val plane = planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride

    val crop = cropRect
    val cropWidth = crop.width()
    val cropHeight = crop.height()
    val packed = Mat(cropHeight, cropWidth, CvType.CV_8UC1)
    val tightRow = ByteArray(cropWidth)

    for (row in 0 until cropHeight) {
        val rowStart = (row + crop.top) * rowStride + crop.left * pixelStride
        if (pixelStride == 1) {
            for (col in 0 until cropWidth) tightRow[col] = buffer.get(rowStart + col)
        } else {
            for (col in 0 until cropWidth) tightRow[col] = buffer.get(rowStart + col * pixelStride)
        }
        packed.put(row, 0, tightRow)
    }
    val rotateCode = when (imageInfo.rotationDegrees) {
        90 -> Core.ROTATE_90_CLOCKWISE
        180 -> Core.ROTATE_180
        270 -> Core.ROTATE_90_COUNTERCLOCKWISE
        else -> return packed
    }
    val rotated = Mat()
    Core.rotate(packed, rotated, rotateCode)
    packed.release()
    return rotated
}
