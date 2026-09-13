package com.oldalexhub.paperrescue.scanner

import androidx.camera.core.ImageProxy
import org.opencv.core.Mat
import org.opencv.core.CvType

/**
 * Extracts the Y (luma) plane of a YUV_420_888 camera frame as a grayscale
 * Mat in crop-rect coordinates. CameraX maps those coordinates into PreviewView;
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
    return packed
}
