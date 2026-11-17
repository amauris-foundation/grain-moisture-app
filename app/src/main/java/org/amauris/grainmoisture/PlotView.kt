package org.amauris.grainmoisture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.util.Locale

/** Scatter plot of signal level (dB) against reference moisture (%), with a fit line. */
class PlotView(context: Context) : View(context) {
    private var points: List<Pair<Double, Double>> = emptyList()
    private var fit: Pair<Double, Double>? = null // slope, intercept
    private var xLabel = "Reference moisture (%)"
    private var yLabel = "Signal level (dB)"

    private val density = resources.displayMetrics.density
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        strokeWidth = 1.5f * density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 225, 225)
        strokeWidth = 1f * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(46, 125, 50) }
    private val fitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(198, 40, 40)
        strokeWidth = 2f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 12f * density
    }

    fun setData(pts: List<Pair<Double, Double>>, fitLine: Pair<Double, Double>?, xl: String, yl: String) {
        points = pts
        fit = fitLine
        xLabel = xl
        yLabel = yl
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.75).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 52f * density
        val right = width - 12f * density
        val top = 12f * density
        val bottom = height - 40f * density

        canvas.drawLine(left, bottom, right, bottom, axisPaint)
        canvas.drawLine(left, top, left, bottom, axisPaint)
        canvas.drawText(xLabel, left, height - 6f * density, textPaint)

        if (points.isEmpty()) {
            canvas.drawText("No calibrated readings at this frequency yet", left + 8f * density, (top + bottom) / 2, textPaint)
            return
        }

        var x0 = points.minOf { it.first }
        var x1 = points.maxOf { it.first }
        var y0 = points.minOf { it.second }
        var y1 = points.maxOf { it.second }
        if (x1 - x0 < 0.5) { x0 -= 1.0; x1 += 1.0 }
        if (y1 - y0 < 0.5) { y0 -= 1.0; y1 += 1.0 }
        val xp = (x1 - x0) * 0.06
        val yp = (y1 - y0) * 0.08
        x0 -= xp; x1 += xp; y0 -= yp; y1 += yp

        fun px(x: Double) = (left + (x - x0) / (x1 - x0) * (right - left)).toFloat()
        fun py(y: Double) = (bottom - (y - y0) / (y1 - y0) * (bottom - top)).toFloat()

        for (k in 0..4) {
            val gy = y0 + (y1 - y0) * k / 4.0
            canvas.drawLine(left, py(gy), right, py(gy), gridPaint)
            canvas.drawText(String.format(Locale.US, "%.1f", gy), 2f * density, py(gy) + 4f * density, textPaint)
        }
        for (k in 0..4) {
            val gx = x0 + (x1 - x0) * k / 4.0
            canvas.drawText(String.format(Locale.US, "%.1f", gx), px(gx) - 10f * density, bottom + 14f * density, textPaint)
        }

        fit?.let { (slope, intercept) ->
            canvas.drawLine(px(x0), py(slope * x0 + intercept), px(x1), py(slope * x1 + intercept), fitPaint)
        }
        for ((x, y) in points) canvas.drawCircle(px(x), py(y), 4.5f * density, dotPaint)
        canvas.drawText(yLabel, left + 6f * density, top + 12f * density, textPaint)
    }
}
