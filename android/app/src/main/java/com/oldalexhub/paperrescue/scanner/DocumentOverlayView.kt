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

    /** Points are already mapped into this view by CameraX CoordinateTransform. */
    fun setQuad(points: Array<Point>?) {
        quad = points
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val points = quad ?: return

        val path = Path()
        points.forEachIndexed { index, p ->
            if (index == 0) path.moveTo(p.x.toFloat(), p.y.toFloat()) else path.lineTo(p.x.toFloat(), p.y.toFloat())
        }
        path.close()
        canvas.drawPath(path, strokePaint)
        points.forEach { p -> canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 10f, cornerPaint) }
    }
}
