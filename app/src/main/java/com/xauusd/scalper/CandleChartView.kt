package com.xauusd.scalper

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

class CandleChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var candles: List<Candle> = emptyList()
    private var timeframe = "M1"
    private var currentPrice: Double? = null
    private var signal: SignalResult? = null
    private var showEma = true
    private var showLevels = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var offset = 0
    private var dragX = 0f
    private var dragY = 0f
    private var scaleY = 1f
    private var visibleCount = 55

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleY = (scaleY * detector.scaleFactor).coerceIn(0.5f, 3f)
                visibleCount = (visibleCount / detector.scaleFactor).toInt().coerceIn(20, 90)
                invalidate()
                return true
            }
        })

    fun setData(data: List<Candle>, tf: String, live: Double?, sig: SignalResult?) {
        candles = data.takeLast(120)
        timeframe = tf
        currentPrice = live ?: candles.lastOrNull()?.close
        signal = sig
        invalidate()
    }

    fun setLayers(ema: Boolean, levels: Boolean) {
        showEma = ema
        showLevels = levels
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragX = e.x; dragY = e.y; return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val dx = e.x - dragX
                    val dy = e.y - dragY
                    if (kotlin.math.abs(dx) > 8) {
                        offset += if (dx > 0) -1 else 1
                        offset = offset.coerceIn(0, max(0, candles.size - 15))
                        dragX = e.x
                        invalidate()
                    }
                    if (kotlin.math.abs(dy) > 12) {
                        scaleY = (scaleY + dy * 0.002f).coerceIn(0.5f, 3f)
                        dragY = e.y
                        invalidate()
                    }
                }
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(13, 16, 21))
        if (candles.isEmpty()) {
            paint.color = Color.LTGRAY
            paint.textSize = 16f
            canvas.drawText("NO DATA", 24f, height / 2f, paint)
            return
        }
        val visible = candles.drop(offset).takeLast(min(visibleCount, max(1, candles.size - offset)))
        val left = 16f
        val right = width - 70f
        val top = 52f
        val bottom = height - 40f
        val w = max(1f, right - left)
        val h = max(1f, bottom - top)
        val hi = visible.maxOf { it.high }
        val lo = visible.minOf { it.low }
        val mid = (hi + lo) / 2
        val range = max((hi - lo) / scaleY, 0.5)
        val maxP = mid + range / 2
        val minP = mid - range / 2
        fun y(p: Double) = (bottom - ((p - minP) / (maxP - minP) * h)).toFloat()

        paint.textSize = 18f
        paint.color = Color.WHITE
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("XAU/USD • $timeframe", left, 26f, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 11f
        paint.color = Color.GRAY
        canvas.drawText("Geser H/V • Pinch zoom", left, 44f, paint)

        for (i in 0..4) {
            val gy = top + h * i / 4f
            paint.color = Color.rgb(40, 45, 55)
            canvas.drawLine(left, gy, right, gy, paint)
            paint.color = Color.LTGRAY
            canvas.drawText("%.2f".format(maxP - (maxP - minP) * i / 4), right + 3, gy + 4, paint)
        }

        val step = w / visible.size
        val bw = max(3f, step * 0.55f)
        visible.forEachIndexed { i, c ->
            val x = left + step * i + step / 2
            val up = c.close >= c.open
            paint.color = if (up) Color.rgb(42, 190, 120) else Color.rgb(235, 80, 90)
            paint.strokeWidth = 1.6f
            canvas.drawLine(x, y(c.high), x, y(c.low), paint)
            canvas.drawRect(x - bw / 2, y(max(c.open, c.close)), x + bw / 2, y(min(c.open, c.close)), paint)
        }

        if (showEma) {
            val e20 = IndicatorEngine.ema(visible.map { it.close }, 20)
            val e50 = IndicatorEngine.ema(visible.map { it.close }, 50)
            fun line(vals: List<Double>, col: Int) {
                paint.color = col
                paint.strokeWidth = 2f
                var prev: PointF? = null
                vals.forEachIndexed { i, v ->
                    if (!v.isNaN()) {
                        val pt = PointF(left + step * i + step / 2, y(v))
                        if (prev != null) canvas.drawLine(prev!!.x, prev!!.y, pt.x, pt.y, paint)
                        prev = pt
                    }
                }
            }
            line(e20, Color.rgb(255, 193, 7))
            line(e50, Color.rgb(66, 165, 245))
        }

        if (showLevels && candles.isNotEmpty()) {
            val ind = IndicatorEngine.snapshot(candles)
            paint.strokeWidth = 1.4f
            paint.color = Color.rgb(80, 170, 255)
            canvas.drawLine(left, y(ind.support), right, y(ind.support), paint)
            paint.color = Color.rgb(255, 100, 100)
            canvas.drawLine(left, y(ind.resistance), right, y(ind.resistance), paint)
        }

        currentPrice?.let {
            val py = y(it)
            paint.color = Color.WHITE
            paint.strokeWidth = 1.8f
            canvas.drawLine(left, py, right, py, paint)
        }

        signal?.let {
            val sy = y(it.entry)
            paint.color = if (it.side == "BUY") Color.rgb(0, 220, 120) else Color.rgb(255, 70, 80)
            paint.strokeWidth = 2.5f
            canvas.drawLine(left, sy, right, sy, paint)
            paint.textSize = 12f
            canvas.drawText("${it.entryType} ${"%.2f".format(it.entry)}", left + 4, sy - 6, paint)
        }
    }
}
