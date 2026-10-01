package com.xauusd.scalper

import kotlin.math.abs
import kotlin.math.max

/**
 * Pipeline akurat:
 * M5 Bias → Liquidity Sweep → Wick Rejection → Wick-Tip Entry → BOS → Confidence → Signal
 * Sinyal hanya jika confidence ≥ 4 (≈80%+).
 */
object SignalEngine {

    fun evaluate(s: MarketSnapshot): Pair<SignalResult?, Map<String, String>> {
        if (s.m1.size < 30 || s.m5.size < 40) {
            return null to mapOf(
                "bias" to "WAIT", "watch" to "LOADING",
                "score" to "0", "stars" to "0",
                "session" to SessionHelper.sessionLabel(),
                "steps" to "loading"
            )
        }

        val i5 = IndicatorEngine.snapshot(s.m5)
        val i1 = IndicatorEngine.snapshot(s.m1)
        val c1 = s.m1.last()
        val prev = s.m1[s.m1.lastIndex - 1]
        val body = abs(c1.close - c1.open).coerceAtLeast(0.01)
        val upperWick = c1.high - maxOf(c1.open, c1.close)
        val lowerWick = minOf(c1.open, c1.close) - c1.low

        // 1) M5 Bias
        val m5Buy = s.m5.last().close > i5.ema50 && i5.ema20 > i5.ema50
        val m5Sell = s.m5.last().close < i5.ema50 && i5.ema20 < i5.ema50

        // 2) Liquidity Sweep (ambil likuiditas di atas high / bawah low recent)
        val look = s.m1.takeLast(12).dropLast(1)
        val recentHigh = look.maxOf { it.high }
        val recentLow = look.minOf { it.low }
        val sweepBuy = c1.low < recentLow && c1.close > recentLow // sweep low lalu close balik
        val sweepSell = c1.high > recentHigh && c1.close < recentHigh

        // 3) Wick Rejection
        val wickBuy = lowerWick >= body * 1.2 && lowerWick > upperWick
        val wickSell = upperWick >= body * 1.2 && upperWick > lowerWick

        // 4) Wick-Tip Entry zone (entry di ujung wick / EMA20)
        val tipBuy = abs(c1.low - i1.ema20) <= max(i1.atr14 * 0.5, 0.40) ||
            (c1.close > i1.ema20 && prev.low <= i1.ema20)
        val tipSell = abs(c1.high - i1.ema20) <= max(i1.atr14 * 0.5, 0.40) ||
            (c1.close < i1.ema20 && prev.high >= i1.ema20)

        // 5) BOS
        val bosBuy = c1.close > prev.high || c1.close > look.maxOf { it.high }
        val bosSell = c1.close < prev.low || c1.close < look.minOf { it.low }

        // Filter pendukung
        val sessionOk = SessionHelper.isGoodSession()
        val spreadOk = s.spread <= 0.30
        val rsiBuy = i1.rsi14 in 35.0..68.0
        val rsiSell = i1.rsi14 in 32.0..65.0

        fun scoreBuy(): Int {
            var sc = 0
            if (m5Buy) sc += 20
            if (sweepBuy) sc += 18
            if (wickBuy) sc += 16
            if (tipBuy) sc += 14
            if (bosBuy) sc += 12
            if (sessionOk) sc += 8
            if (spreadOk) sc += 6
            if (rsiBuy) sc += 6
            return sc.coerceIn(0, 100)
        }

        fun scoreSell(): Int {
            var sc = 0
            if (m5Sell) sc += 20
            if (sweepSell) sc += 18
            if (wickSell) sc += 16
            if (tipSell) sc += 14
            if (bosSell) sc += 12
            if (sessionOk) sc += 8
            if (spreadOk) sc += 6
            if (rsiSell) sc += 6
            return sc.coerceIn(0, 100)
        }

        val buySc = scoreBuy()
        val sellSc = scoreSell()

        val stepsBuy = listOf(
            "M5Bias" to m5Buy, "Sweep" to sweepBuy, "Wick" to wickBuy,
            "Tip" to tipBuy, "BOS" to bosBuy
        )
        val stepsSell = listOf(
            "M5Bias" to m5Sell, "Sweep" to sweepSell, "Wick" to wickSell,
            "Tip" to tipSell, "BOS" to bosSell
        )

        val side: String
        val score: Int
        val steps: List<Pair<String, Boolean>>
        when {
            buySc >= sellSc && buySc >= 78 && m5Buy && (sweepBuy || wickBuy) && (tipBuy || bosBuy) -> {
                side = "BUY"; score = buySc; steps = stepsBuy
            }
            sellSc > buySc && sellSc >= 78 && m5Sell && (sweepSell || wickSell) && (tipSell || bosSell) -> {
                side = "SELL"; score = sellSc; steps = stepsSell
            }
            else -> {
                val best = max(buySc, sellSc)
                val watch = when {
                    !sessionOk -> "OFF SESSION"
                    !spreadOk -> "SPREAD HIGH"
                    buySc >= 50 -> "BUY BUILD $buySc%"
                    sellSc >= 50 -> "SELL BUILD $sellSc%"
                    else -> "WAIT"
                }
                val stepStr = (if (buySc >= sellSc) stepsBuy else stepsSell)
                    .joinToString(" · ") { (n, ok) -> if (ok) n else "$n✗" }
                return null to mapOf(
                    "bias" to when {
                        m5Buy -> "BUY"
                        m5Sell -> "SELL"
                        else -> "NEUTRAL"
                    },
                    "watch" to watch,
                    "score" to best.toString(),
                    "stars" to (best / 20).coerceIn(0, 5).toString(),
                    "session" to SessionHelper.sessionLabel(),
                    "rsi" to "%.1f".format(i1.rsi14),
                    "spread" to "%.2f".format(s.spread),
                    "steps" to stepStr
                )
            }
        }

        val stars = (score / 20).coerceIn(1, 5)
        // Wick-tip entry: low/high of rejection candle
        val entry = if (side == "BUY") minOf(c1.low, i1.ema20) else maxOf(c1.high, i1.ema20)
        val risk = max(i1.atr14 * 0.85, 0.55)
        val sl = if (side == "BUY") entry - risk else entry + risk
        val tp1 = if (side == "BUY") entry + risk * 1.2 else entry - risk * 1.2
        val tp2 = if (side == "BUY") entry + risk * 2.0 else entry - risk * 2.0
        val near = abs(s.price - entry) <= max(i1.atr14 * 0.35, 0.35)
        val entryType = if (side == "BUY") "BUY LIMIT" else "SELL LIMIT"
        val state = if (near) "ENTRY READY" else "LIMIT ZONE"

        val stepStr = steps.joinToString(" · ") { (n, ok) -> if (ok) "✓$n" else n }

        return SignalResult(
            side = side,
            state = state,
            entry = entry,
            entryType = entryType,
            sl = sl,
            tp1 = tp1,
            tp2 = tp2,
            confidence = stars,
            candleTime = c1.time,
            setup = "Pipeline $score%",
            reason = stepStr
        ) to mapOf(
            "bias" to side,
            "watch" to state,
            "score" to score.toString(),
            "stars" to stars.toString(),
            "session" to SessionHelper.sessionLabel(),
            "rsi" to "%.1f".format(i1.rsi14),
            "spread" to "%.2f".format(s.spread),
            "steps" to stepStr
        )
    }
}
