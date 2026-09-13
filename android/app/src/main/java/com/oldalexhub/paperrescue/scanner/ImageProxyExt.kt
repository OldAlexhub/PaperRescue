package com.oldalexhub.paperrescue.scanner

import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.CvType

/**
 * Extracts the Y (luma) plane of a YUV_420_888 camera frame as a grayscale
 * Mat, rotated to match on-screen orientation. Luma alone is enough for edge
 * detection and keeps the live-preview analysis pass fast and allocation-light.
 */
fun ImageProxy.toGrayMat(): Mat {
    val plane = planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride

    val packed = Mat(height, width, CvType.CV_8UC1)
    val rowBytes = ByteArray(rowStride)
    val tightRow = ByteArray(width)

    for (row in 0 until height) {
        buffer.position(row * rowStride)
        val remaining = buffer.remaining().coerceAtMost(rowStride)
        buffer.get(rowBytes, 0, remaining)
        if (pixelStride == 1) {
            System.arraycopy(rowBytes, 0, tightRow, 0, width)
        } else {
            for (col in 0 until width) tightRow[col] = rowBytes[col * pixelStride]
        }
        packed.put(row, 0, tightRow)
    }

    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return packed

    val rotated = Mat()
    when (rotation) {
        90 -> Core.rotate(packed, rotated, Core.ROTATE_90_CLOCKWISE)
        180 -> Core.rotate(packed, rotated, Core.ROTATE_180)
        270 -> Core.rotate(packed, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
        else -> { packed.copyTo(rotated) }
    }
    packed.release()
    return rotated
}
