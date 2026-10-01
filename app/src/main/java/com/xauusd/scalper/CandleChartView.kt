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
    private var bid: Double? = null
    private var ask: Double? = null
    private var signal: SignalResult? = null
    private var showEma = true
    private var showLevels = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var offset = 0 // 0 = auto-scroll ke candle terakhir
    private var autoScroll = true
    private var dragX = 0f
    private var dragY = 0f
    private var scaleY = 1f
    private var visibleCount = 48
    private var crossX = -1f
    private var crossY = -1f
    private var showCross = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleY = (scaleY * detector.scaleFactor).coerceIn(0.35f, 4f)
                visibleCount = (visibleCount / detector.scaleFactor).toInt().coerceIn(15, 120)
                invalidate()
                return true
            }
        })

    fun setData(
        data: List<Candle>,
        tf: String,
        live: Double?,
        sig: SignalResult?,
        bidP: Double? = null,
        askP: Double? = null
    ) {
        candles = data.takeLast(200)
        timeframe = tf
        currentPrice = live ?: candles.lastOrNull()?.close
        bid = bidP
        ask = askP
        signal = sig
        if (autoScroll) offset = 0
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
                dragX = e.x; dragY = e.y
                showCross = true
                crossX = e.x; crossY = e.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val dx = e.x - dragX
                    val dy = e.y - dragY
                    if (kotlin.math.abs(dx) > 6) {
                        autoScroll = false
                        offset += if (dx > 0) -1 else 1
                        offset = offset.coerceIn(0, max(0, candles.size - 10))
                        dragX = e.x
                    }
                    if (kotlin.math.abs(dy) > 10) {
                        scaleY = (scaleY + dy * 0.002f).coerceIn(0.35f, 4f)
                        dragY = e.y
                    }
                    crossX = e.x; crossY = e.y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // double-tap area kanan = reset auto scroll
                if (e.x > width * 0.85f) {
                    autoScroll = true
                    offset = 0
                }
                showCross = false
                invalidate()
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(8, 12, 18))
        if (candles.isEmpty()) {
            paint.color = Color.LTGRAY
            paint.textSize = 15f
            canvas.drawText("Menunggu data Biquote...", 24f, height / 2f, paint)
            return
        }

        // Margin kanan LEBAR supaya candle terakhir + badge harga tidak tertutup
        val priceColW = 86f
        val left = 8f
        val right = width - priceColW
        val top = 40f
        val bottom = height - 24f
        val w = max(1f, right - left)
        val h = max(1f, bottom - top)

        val end = candles.size - offset
        val start = max(0, end - visibleCount)
        val visible = candles.subList(start, end.coerceAtMost(candles.size))

        var hi = visible.maxOf { it.high }
        var lo = visible.minOf { it.low }
        currentPrice?.let { hi = max(hi, it); lo = min(lo, it) }
        bid?.let { hi = max(hi, it); lo = min(lo, it) }
        ask?.let { hi = max(hi, it); lo = min(lo, it) }
        signal?.let {
            hi = maxOf(hi, it.entry, it.tp1, it.tp2)
            lo = minOf(lo, it.entry, it.sl)
        }

        val pad = max((hi - lo) * 0.12, 0.5)
        val mid = (hi + lo) / 2.0
        val range = max(((hi - lo) + pad * 2) / scaleY, 1.0)
        val maxP = mid + range / 2
        val minP = mid - range / 2
        fun y(p: Double): Float {
            val t = ((p - minP) / (maxP - minP)).coerceIn(0.02, 0.98)
            return (bottom - t * h).toFloat()
        }

        // Header
        paint.textSize = 12f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = Color.WHITE
        canvas.drawText("XAU/USD $timeframe", left, 16f, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 10f
        paint.color = Color.rgb(129, 199, 132)
        canvas.drawText("H ${"%.2f".format(hi)}", left + 110f, 16f, paint)
        paint.color = Color.rgb(239, 83, 80)
        canvas.drawText("L ${"%.2f".format(lo)}", left + 190f, 16f, paint)
        paint.color = Color.GRAY
        canvas.drawText(if (autoScroll) "AUTO" else "SCROLL", left + 270f, 16f, paint)

        // Grid + scale
        paint.textSize = 9f
        for (i in 0..5) {
            val gy = top + h * i / 5f
            paint.color = Color.rgb(28, 34, 44)
            paint.strokeWidth = 1f
            canvas.drawLine(left, gy, right, gy, paint)
            val pv = maxP - (maxP - minP) * i / 5.0
            paint.color = Color.rgb(158, 168, 178)
            canvas.drawText("%.2f".format(pv), right + 4f, gy + 3f, paint)
        }

        // Candles — sisakan ruang di ujung kanan agar candle terakhir penuh
        val n = visible.size
        val step = w / (n + 0.5f) // +0.5 = padding kanan dalam area chart
        val bw = max(2.2f, step * 0.6f)
        visible.forEachIndexed { i, c ->
            val x = left + step * i + step / 2
            val up = c.close >= c.open
            paint.color = if (up) Color.rgb(38, 198, 120) else Color.rgb(239, 83, 80)
            paint.strokeWidth = 1.4f
            canvas.drawLine(x, y(c.high), x, y(c.low), paint)
            val tb = y(max(c.open, c.close))
            val bb = y(min(c.open, c.close))
            canvas.drawRect(x - bw / 2, tb, x + bw / 2, max(bb, tb + 1f), paint)
        }

        if (showEma) {
            val closes = visible.map { it.close }
            fun emaLine(period: Int, col: Int) {
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
            emaLine(20, Color.rgb(255, 193, 7))
            emaLine(50, Color.rgb(66, 165, 245))
        }

        if (showLevels && candles.isNotEmpty()) {
            val ind = IndicatorEngine.snapshot(candles)
            paint.strokeWidth = 1.3f
            paint.pathEffect = DashPathEffect(floatArrayOf(7f, 5f), 0f)
            paint.color = Color.rgb(100, 181, 246)
            canvas.drawLine(left, y(ind.support), right, y(ind.support), paint)
            paint.color = Color.rgb(239, 83, 80)
            canvas.drawLine(left, y(ind.resistance), right, y(ind.resistance), paint)
            paint.pathEffect = null
        }

        // Bid / Ask / Mid lines
        currentPrice?.let { cp ->
            val py = y(cp)
            paint.color = Color.WHITE
            paint.strokeWidth = 1.4f
            paint.pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f)
            canvas.drawLine(left, py, right, py, paint)
            paint.pathEffect = null
            drawPriceBadge(canvas, right, py, "%.2f".format(cp), Color.rgb(38, 50, 56))
        }
        bid?.let { drawPriceBadge(canvas, right, y(it), "B ${"%.2f".format(it)}", Color.rgb(30, 80, 50)) }
        ask?.let { drawPriceBadge(canvas, right, y(it), "A ${"%.2f".format(it)}", Color.rgb(90, 40, 40)) }

        signal?.let {
            val sy = y(it.entry)
            paint.color = if (it.side == "BUY") Color.rgb(0, 230, 118) else Color.rgb(255, 82, 82)
            paint.strokeWidth = 2f
            canvas.drawLine(left, sy, right, sy, paint)
            paint.textSize = 10f
            canvas.drawText("${it.entryType} ${"%.2f".format(it.entry)}", left + 4, sy - 4, paint)
        }

        // Crosshair
        if (showCross && crossX in left..right && crossY in top..bottom) {
            paint.color = Color.argb(160, 200, 200, 200)
            paint.strokeWidth = 1f
            canvas.drawLine(crossX, top, crossX, bottom, paint)
            canvas.drawLine(left, crossY, right, crossY, paint)
            val priceAt = minP + (1.0 - ((crossY - top) / h).toDouble()) * (maxP - minP)
            paint.textSize = 10f
            paint.color = Color.YELLOW
            canvas.drawText("%.2f".format(priceAt), crossX + 6, crossY - 6, paint)
        }
    }

    private fun drawPriceBadge(canvas: Canvas, right: Float, py: Float, label: String, bg: Int) {
        paint.textSize = 10f
        val tw = paint.measureText(label) + 10f
        paint.color = bg
        canvas.drawRoundRect(right + 2f, py - 10f, right + 2f + tw, py + 10f, 3f, 3f, paint)
        paint.color = Color.WHITE
        canvas.drawText(label, right + 6f, py + 3.5f, paint)
    }
}
