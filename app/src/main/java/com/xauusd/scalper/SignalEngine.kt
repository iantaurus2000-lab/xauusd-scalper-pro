package com.xauusd.scalper

import kotlin.math.abs
import kotlin.math.max

/**
 * High-accuracy scalping engine
 * Filters: Session + M5 bias + Sweep + Wick + BOS + RSI + EMA
 * Entry mode: BUY LIMIT / SELL LIMIT (retrace to wick tip)
 */
object SignalEngine {
    private fun body(c: Candle) = abs(c.close - c.open)
    private fun lowerWick(c: Candle) = minOf(c.open, c.close) - c.low
    private fun upperWick(c: Candle) = c.high - maxOf(c.open, c.close)

    fun evaluate(s: MarketSnapshot): Pair<SignalResult?, Map<String, String>> {
        if (s.m1.size < 30 || s.m5.size < 60) {
            return null to mapOf(
                "bias" to "WAITING",
                "watch" to "WAIT",
                "session" to SessionHelper.sessionLabel()
            )
        }

        val m1 = s.m1
        val m5 = s.m5
        val i5 = IndicatorEngine.snapshot(m5)
        val i1 = IndicatorEngine.snapshot(m1)
        val sessionOk = SessionHelper.isGoodSession()
        val best = SessionHelper.isBestSession()

        val bias = when {
            i5.ema20 > i5.ema50 && m5.last().close > i5.ema20 -> "BUY"
            i5.ema20 < i5.ema50 && m5.last().close < i5.ema20 -> "SELL"
            else -> "NEUTRAL"
        }

        val sweep = m1[m1.lastIndex - 2]
        val confirm = m1[m1.lastIndex - 1]
        val prior = m1.subList(max(0, m1.lastIndex - 12), m1.lastIndex - 2)
        val ph = prior.maxOf { it.high }
        val pl = prior.minOf { it.low }

        val bullW = lowerWick(sweep) >= max(body(sweep) * 1.6, i1.atr14 * 0.25) && sweep.close > sweep.open
        val bearW = upperWick(sweep) >= max(body(sweep) * 1.6, i1.atr14 * 0.25) && sweep.close < sweep.open
        val sweepLow = sweep.low < pl && sweep.close > pl
        val sweepHigh = sweep.high > ph && sweep.close < ph
        val bosBuy = confirm.close > sweep.high
        val bosSell = confirm.close < sweep.low

        var buyScore = 0
        var sellScore = 0
        if (bias == "BUY") buyScore++
        if (bias == "SELL") sellScore++
        if (bullW) buyScore++
        if (bearW) sellScore++
        if (sweepLow) buyScore++
        if (sweepHigh) sellScore++
        if (bosBuy) buyScore++
        if (bosSell) sellScore++
        if (i1.rsi14 in 42.0..65.0) buyScore++
        if (i1.rsi14 in 35.0..58.0) sellScore++
        if (sessionOk && best) {
            if (buyScore >= 3) buyScore++
            if (sellScore >= 3) sellScore++
        }

        // Spread filter: skip if spread too wide for scalping
        if (s.spread > 0.80) {
            return null to mapOf(
                "bias" to bias,
                "watch" to "SPREAD HIGH",
                "session" to SessionHelper.sessionLabel()
            )
        }

        val side = when {
            !sessionOk -> ""
            buyScore >= 4 -> "BUY"
            sellScore >= 4 -> "SELL"
            else -> ""
        }

        val status = mutableMapOf(
            "bias" to bias,
            "wick" to if (bullW || bearW) "OK" else "WAIT",
            "sweep" to if (sweepLow || sweepHigh) "OK" else "WAIT",
            "bos" to if (bosBuy || bosSell) "OK" else "WAIT",
            "rsi" to "%.1f".format(i1.rsi14),
            "session" to SessionHelper.sessionLabel(),
            "score" to "B$buyScore/S$sellScore"
        )

        if (side.isEmpty()) {
            status["watch"] = when {
                !sessionOk -> "OFF SESSION"
                bias == "BUY" && (bullW || sweepLow) -> "BUY WATCH"
                bias == "SELL" && (bearW || sweepHigh) -> "SELL WATCH"
                else -> "WAIT"
            }
            return null to status
        }

        // LIMIT entry at wick tip (manual or auto-limit style)
        val limitPrice = if (side == "BUY") sweep.low else sweep.high
        val risk = max(
            abs(limitPrice - if (side == "BUY") i1.support else i1.resistance),
            max(i1.atr14 * 0.8, 0.50)
        )
        val sl = if (side == "BUY") limitPrice - risk else limitPrice + risk
        val tp1 = if (side == "BUY") limitPrice + risk else limitPrice - risk
        val tp2 = if (side == "BUY") limitPrice + risk * 1.8 else limitPrice - risk * 1.8
        val near = abs(s.price - limitPrice) <= max(i1.atr14 * 0.35, 0.25)
        val conf = max(buyScore, sellScore).coerceIn(1, 5)
        val entryType = if (side == "BUY") "BUY LIMIT" else "SELL LIMIT"
        val state = if (near) "LIMIT FILL ZONE" else "LIMIT PENDING"

        status["entryState"] = state
        status["watch"] = state

        return SignalResult(
            side = side,
            state = state,
            entry = limitPrice,
            entryType = entryType,
            sl = sl,
            tp1 = tp1,
            tp2 = tp2,
            confidence = conf,
            candleTime = confirm.time,
            setup = "Session+Sweep+Wick+BOS → $entryType",
            reason = if (near) "Harga di zona limit • siap fill" else "Pasang $entryType @ ${"%.2f".format(limitPrice)}"
        ) to status
    }
}
