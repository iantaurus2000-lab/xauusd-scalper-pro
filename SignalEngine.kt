package com.xauusd.scalper

import kotlin.math.abs
import kotlin.math.max

object SignalEngine {
    private fun body(c:Candle)=abs(c.close-c.open)
    private fun lowerWick(c:Candle)=minOf(c.open,c.close)-c.low
    private fun upperWick(c:Candle)=c.high-maxOf(c.open,c.close)

    fun evaluate(s:MarketSnapshot):Pair<SignalResult?,Map<String,String>>{
        if(s.m1.size<30||s.m5.size<60)return null to mapOf("bias" to "WAITING","watch" to "WAIT")
        val m1=s.m1; val m5=s.m5; val i5=IndicatorEngine.snapshot(m5); val i1=IndicatorEngine.snapshot(m1)
        val bias=when{ i5.ema20>i5.ema50 && m5.last().close>i5.ema20->"BUY"; i5.ema20<i5.ema50 && m5.last().close<i5.ema20->"SELL"; else->"NEUTRAL" }
        val sweep=m1[m1.lastIndex-2]; val confirm=m1[m1.lastIndex-1]
        val prior=m1.subList(max(0,m1.lastIndex-12),m1.lastIndex-2); val ph=prior.maxOf{it.high}; val pl=prior.minOf{it.low}
        val bullW=lowerWick(sweep)>=max(body(sweep)*1.6,i1.atr14*.25)&&sweep.close>sweep.open
        val bearW=upperWick(sweep)>=max(body(sweep)*1.6,i1.atr14*.25)&&sweep.close<sweep.open
        val sweepLow=sweep.low<pl&&sweep.close>pl; val sweepHigh=sweep.high>ph&&sweep.close<ph
        val bosBuy=confirm.close>sweep.high; val bosSell=confirm.close<sweep.low
        val nearBuy=abs(s.price-sweep.low)<=max(i1.atr14*.35,.20); val nearSell=abs(s.price-sweep.high)<=max(i1.atr14*.35,.20)
        val buyScore=listOf(bias=="BUY",bullW,sweepLow,bosBuy,i1.rsi14 in 45.0..68.0).count{it}
        val sellScore=listOf(bias=="SELL",bearW,sweepHigh,bosSell,i1.rsi14 in 32.0..55.0).count{it}
        val side=when{buyScore>=4->"BUY";sellScore>=4->"SELL";else->""}
        val status=mutableMapOf("bias" to bias,"wick" to if(bullW||bearW)"CONFIRMED" else "WAITING","sweep" to if(sweepLow||sweepHigh)"CONFIRMED" else "WAITING","bos" to if(bosBuy||bosSell)"CONFIRMED" else "WAITING","rsi" to "%.1f".format(i1.rsi14),"ema" to "%.2f / %.2f".format(i1.ema20,i1.ema50))
        if(side.isEmpty()){status["watch"]=when{bias=="BUY"&&(bullW||sweepLow)->"BUY WATCH";bias=="SELL"&&(bearW||sweepHigh)->"SELL WATCH";else->"WAIT"};return null to status}
        val tip=if(side=="BUY")sweep.low else sweep.high; val risk=max(abs(tip-(if(side=="BUY")i1.support else i1.resistance)),max(i1.atr14*.8,.5)); val sl=if(side=="BUY")tip-risk else tip+risk; val tp1=if(side=="BUY")tip+risk else tip-risk; val tp2=if(side=="BUY")tip+risk*1.8 else tip-risk*1.8; val ready=if(side=="BUY")nearBuy else nearSell
        status["entryState"]=if(ready)"ENTRY CONFIRMED" else "ENTRY READY"
        return SignalResult(side,if(ready)"ENTRY CONFIRMED" else "ENTRY READY",tip,sl,tp1,tp2,max(buyScore,sellScore),confirm.time,"Liquidity Sweep + Wick Rejection + BOS",tip,if(ready)"Harga dekat ujung wick" else "Tunggu retrace ke ujung wick") to status
    }
}
