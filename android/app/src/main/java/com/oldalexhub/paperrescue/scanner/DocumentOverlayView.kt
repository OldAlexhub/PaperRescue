package com.oldalexhub.paperrescue.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import org.opencv.core.Point

/** Draws the live detected document quadrilateral over the CameraX preview. */
class DocumentOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var quad: Array<Point>? = null
    private var sourceWidth = 0
    private var sourceHeight = 0

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#FF7A29")
        strokeJoin = Paint.Join.ROUND
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF7A29")
    }

    fun setQuad(points: Array<Point>?, srcWidth: Int, srcHeight: Int) {
        quad = points
        sourceWidth = srcWidth
        sourceHeight = srcHeight
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val points = quad ?: return
        if (sourceWidth <= 0 || sourceHeight <= 0) return

        // The analysis frame may not share the view's aspect ratio; map with a
        // centered "fill" scale so the overlay lines up with what's on screen.
        val scale = maxOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val offsetX = (width - sourceWidth * scale) / 2f
        val offsetY = (height - sourceHeight * scale) / 2f

        fun mapX(x: Double) = (x * scale + offsetX).toFloat()
        fun mapY(y: Double) = (y * scale + offsetY).toFloat()

        val path = Path()
        points.forEachIndexed { index, p ->
            if (index == 0) path.moveTo(mapX(p.x), mapY(p.y)) else path.lineTo(mapX(p.x), mapY(p.y))
        }
        path.close()
        canvas.drawPath(path, strokePaint)
        points.forEach { p -> canvas.drawCircle(mapX(p.x), mapY(p.y), 10f, cornerPaint) }
    }
}
