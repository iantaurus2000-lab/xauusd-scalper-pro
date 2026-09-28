package com.xauusd.scalper

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class CandleChartView(context: Context) : View(context) {
    private var candles: List<Candle> = emptyList()
    private var timeframe = "M1"
    private var currentPrice: Double? = null
    private var signal: SignalResult? = null
    private var showEma = true
    private var showLevels = true
    private var showFib = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragX = 0f
    private var offset = 0

    fun setData(data: List<Candle>, tf: String, livePrice: Double? = null, sig: SignalResult? = null) { candles = data.takeLast(100); timeframe = tf; currentPrice = livePrice ?: candles.lastOrNull()?.close; signal = sig; invalidate() }
    fun setCurrentPrice(p: Double?) { currentPrice = p; invalidate() }
    fun setLayers(ema: Boolean, levels: Boolean, fib: Boolean) { showEma = ema; showLevels = levels; showFib = fib; invalidate() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragX = e.x; return true }
            MotionEvent.ACTION_MOVE -> { val d = e.x - dragX; if (kotlin.math.abs(d) > 10) { offset += if (d > 0) -1 else 1; offset = offset.coerceIn(0, max(0, candles.size - 20)); dragX = e.x; invalidate() }; return true }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(13,16,21)); if (candles.isEmpty()) { paint.color=Color.LTGRAY; paint.textSize=18f; canvas.drawText("NO MARKET DATA",24f,height/2f,paint); return }
        val visible = candles.drop(offset).takeLast(min(70, candles.size - offset).coerceAtLeast(1))
        val left=18f; val right=width-72f; val top=58f; val bottom=height-48f; val w=max(1f,right-left); val h=max(1f,bottom-top)
        val ema20=IndicatorEngine.ema(visible.map{it.close},20); val ema50=IndicatorEngine.ema(visible.map{it.close},50)
        val ind=IndicatorEngine.snapshot(candles); val hi=visible.maxOf{it.high}; val lo=visible.minOf{it.low}; val pad=max((hi-lo)*.08,.5); val maxP=hi+pad; val minP=lo-pad; val range=maxP-minP
        fun y(p:Double)=(bottom-((p-minP)/range*h)).toFloat()
        paint.textSize=22f; paint.typeface=Typeface.DEFAULT_BOLD; paint.color=Color.WHITE; canvas.drawText("XAU/USD  •  $timeframe",left,30f,paint); paint.typeface=Typeface.DEFAULT
        paint.textSize=12f; paint.color=Color.LTGRAY; canvas.drawText("LIVE",left,47f,paint)
        paint.strokeWidth=1f
        for(i in 0..5){ val gy=top+h*i/5f; paint.color=Color.rgb(42,47,56); canvas.drawLine(left,gy,right,gy,paint); paint.color=Color.LTGRAY; canvas.drawText("%.2f".format(maxP-range*i/5),right+5,gy+4,paint) }
        val step=w/visible.size; val bw=max(3f,step*.55f)
        visible.forEachIndexed { i,c -> val x=left+step*i+step/2; val up=c.close>=c.open; paint.color=if(up) Color.rgb(42,190,120) else Color.rgb(235,80,90); paint.strokeWidth=1.8f; canvas.drawLine(x,y(c.high),x,y(c.low),paint); canvas.drawRect(x-bw/2,y(max(c.open,c.close)),x+bw/2,y(min(c.open,c.close)).coerceAtMost(bottom),paint) }
        fun line(vals:List<Double>, color:Int){ paint.color=color; paint.strokeWidth=2f; var prev:PointF?=null; vals.forEachIndexed{ i,v-> if(!v.isNaN()){ val p=PointF(left+step*i+step/2,y(v)); if(prev!=null) canvas.drawLine(prev!!.x,prev!!.y,p.x,p.y,paint); prev=p } } }
        if(showEma){ line(ema20,Color.rgb(255,193,7)); line(ema50,Color.rgb(66,165,245)) }
        if(showLevels){ paint.strokeWidth=1.5f; paint.color=Color.rgb(80,170,255); canvas.drawLine(left,y(ind.support),right,y(ind.support),paint); paint.color=Color.rgb(255,100,100); canvas.drawLine(left,y(ind.resistance),right,y(ind.resistance),paint) }
        if(showFib){
            val levels = listOf(
                ind.fib236 to "23.6",
                ind.fib382 to "38.2",
                ind.fib50 to "50",
                ind.fib618 to "61.8",
                ind.fib786 to "78.6"
            )
            paint.strokeWidth = 1.2f
            paint.color = Color.rgb(180, 160, 90)
            paint.textSize = 10f
            levels.forEach { (lvl, label) ->
                val yy = y(lvl)
                canvas.drawLine(left, yy, right, yy, paint)
                canvas.drawText(label, left + 4, yy - 3, paint)
            }
        }
        currentPrice?.let{ val py=y(it); paint.color=Color.WHITE; paint.strokeWidth=2f; canvas.drawLine(left,py,right,py,paint); paint.style=Paint.Style.FILL; canvas.drawRect(right,py-12,right+70,py+12,paint); paint.color=Color.BLACK; paint.textSize=11f; canvas.drawText("%.2f".format(it),right+5,py+4,paint) }
        signal?.let{ val sy=y(it.entry); paint.color=if(it.side=="BUY") Color.rgb(0,220,120) else Color.rgb(255,70,80); paint.strokeWidth=3f; canvas.drawLine(left,sy,right,sy,paint); paint.style=Paint.Style.FILL; val txt="${it.side} ${it.state}"; paint.textSize=13f; canvas.drawText(txt,left+6,sy-7,paint) }
        paint.color=Color.LTGRAY; paint.textSize=11f; canvas.drawText("EMA20",left, height-28f,paint); canvas.drawText("EMA50",left+55,height-28f,paint); canvas.drawText("SR",left+110,height-28f,paint); if(showFib) canvas.drawText("FIB",left+140,height-28f,paint)
    }
}
