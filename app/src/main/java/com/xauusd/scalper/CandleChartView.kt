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
    private var visibleCount = 50

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleY = (scaleY * detector.scaleFactor).coerceIn(0.4f, 3.5f)
                visibleCount = (visibleCount / detector.scaleFactor).toInt().coerceIn(18, 100)
                invalidate()
                return true
            }
        })

    fun setData(data: List<Candle>, tf: String, live: Double?, sig: SignalResult?) {
        candles = data.takeLast(150)
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
                        offset = offset.coerceIn(0, max(0, candles.size - 12))
                        dragX = e.x
                        invalidate()
                    }
                    if (kotlin.math.abs(dy) > 12) {
                        scaleY = (scaleY + dy * 0.002f).coerceIn(0.4f, 3.5f)
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
        canvas.drawColor(Color.rgb(10, 14, 20))
        if (candles.isEmpty()) {
            paint.color = Color.LTGRAY
            paint.textSize = 16f
            canvas.drawText("NO DATA • tunggu Biquote", 20f, height / 2f, paint)
            return
        }

        val visible = candles.drop(offset).takeLast(min(visibleCount, max(1, candles.size - offset)))
        // Margin kanan lebar agar harga tidak tertutup
        val left = 12f
        val right = width - 78f
        val top = 36f
        val bottom = height - 28f
        val w = max(1f, right - left)
        val h = max(1f, bottom - top)

        var hi = visible.maxOf { it.high }
        var lo = visible.minOf { it.low }
        // Sertakan harga live supaya garis harga tidak keluar chart
        currentPrice?.let {
            hi = max(hi, it)
            lo = min(lo, it)
        }
        signal?.let {
            hi = max(hi, max(it.entry, max(it.tp1, it.tp2)))
            lo = min(lo, min(it.entry, it.sl))
        }

        val pad = max((hi - lo) * 0.08, 0.35)
        val mid = (hi + lo) / 2.0
        val range = max(((hi - lo) + pad * 2) / scaleY, 0.8)
        val maxP = mid + range / 2
        val minP = mid - range / 2
        fun y(p: Double): Float {
            val t = ((p - minP) / (maxP - minP)).coerceIn(0.0, 1.0)
            return (bottom - t * h).toFloat()
        }

        // Header + H/L
        paint.textSize = 13f
        paint.color = Color.WHITE
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("XAU/USD  $timeframe  LIVE", left, 18f, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 11f
        paint.color = Color.rgb(129, 199, 132)
        canvas.drawText("H ${"%.2f".format(hi)}", left + 160f, 18f, paint)
        paint.color = Color.rgb(239, 83, 80)
        canvas.drawText("L ${"%.2f".format(lo)}", left + 250f, 18f, paint)

        // Grid + scale kanan
        paint.textSize = 10f
        for (i in 0..5) {
            val gy = top + h * i / 5f
            paint.color = Color.rgb(35, 42, 52)
            paint.strokeWidth = 1f
            canvas.drawLine(left, gy, right, gy, paint)
            val pv = maxP - (maxP - minP) * i / 5.0
            paint.color = Color.rgb(176, 190, 197)
            canvas.drawText("%.2f".format(pv), right + 4f, gy + 4f, paint)
        }

        // Candles
        val step = w / visible.size
        val bw = max(2.5f, step * 0.55f)
        visible.forEachIndexed { i, c ->
            val x = left + step * i + step / 2
            val up = c.close >= c.open
            paint.color = if (up) Color.rgb(38, 198, 120) else Color.rgb(239, 83, 80)
            paint.strokeWidth = 1.5f
            canvas.drawLine(x, y(c.high), x, y(c.low), paint)
            val topBody = y(max(c.open, c.close))
            val botBody = y(min(c.open, c.close))
            canvas.drawRect(x - bw / 2, topBody, x + bw / 2, max(botBody, topBody + 1f), paint)
        }

        // EMA
        if (showEma) {
            val closes = visible.map { it.close }
            fun drawEma(period: Int, col: Int) {
                val vals = IndicatorEngine.ema(closes, period)
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
            drawEma(20, Color.rgb(255, 193, 7))
            drawEma(50, Color.rgb(66, 165, 245))
        }

        // S/R
        if (showLevels && candles.isNotEmpty()) {
            val ind = IndicatorEngine.snapshot(candles)
            paint.strokeWidth = 1.5f
            paint.pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
            paint.color = Color.rgb(100, 181, 246)
            canvas.drawLine(left, y(ind.support), right, y(ind.support), paint)
            paint.color = Color.rgb(239, 83, 80)
            canvas.drawLine(left, y(ind.resistance), right, y(ind.resistance), paint)
            paint.pathEffect = null
            paint.textSize = 10f
            paint.color = Color.rgb(100, 181, 246)
            canvas.drawText("S ${"%.2f".format(ind.support)}", left + 4, y(ind.support) - 4, paint)
            paint.color = Color.rgb(239, 83, 80)
            canvas.drawText("R ${"%.2f".format(ind.resistance)}", left + 4, y(ind.resistance) - 4, paint)
        }

        // Live price line + badge kanan (tidak tertutup)
        currentPrice?.let { cp ->
            val py = y(cp)
            paint.color = Color.WHITE
            paint.strokeWidth = 1.5f
            paint.pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
            canvas.drawLine(left, py, right, py, paint)
            paint.pathEffect = null
            // Badge harga
            val label = "%.2f".format(cp)
            paint.textSize = 11f
            val tw = paint.measureText(label) + 12f
            val bx = right + 2f
            paint.color = Color.rgb(38, 50, 56)
            canvas.drawRoundRect(bx, py - 12f, bx + tw, py + 12f, 4f, 4f, paint)
            paint.color = Color.WHITE
            canvas.drawText(label, bx + 6f, py + 4f, paint)
        }

        // Entry line
        signal?.let {
            val sy = y(it.entry)
            paint.color = if (it.side == "BUY") Color.rgb(0, 230, 118) else Color.rgb(255, 82, 82)
            paint.strokeWidth = 2f
            canvas.drawLine(left, sy, right, sy, paint)
            paint.textSize = 11f
            canvas.drawText("${it.entryType} ${"%.2f".format(it.entry)}", left + 4, sy - 5, paint)
        }
    }
}
