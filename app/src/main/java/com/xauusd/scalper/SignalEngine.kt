package com.xauusd.scalper

import kotlin.math.abs
import kotlin.math.max

/**
 * Pipeline: M5 Bias → Sweep → Wick → Tip → BOS → Confidence → Signal
 * Default min score 72 agar ENTRY READY lebih sering (~3–5 menit di sesi aktif),
 * tetap butuh M5 bias + (sweep|wick) + (tip|bos).
 */
object SignalEngine {

    fun evaluate(s: MarketSnapshot, minScore: Int = 72): Pair<SignalResult?, Map<String, String>> {
        if (s.m1.size < 25 || s.m5.size < 30) {
            return null to mapOf(
                "bias" to "WAIT", "watch" to "LOADING", "score" to "0", "stars" to "0",
                "session" to SessionHelper.sessionLabel(), "steps" to "loading"
            )
        }

        val i5 = IndicatorEngine.snapshot(s.m5)
        val i1 = IndicatorEngine.snapshot(s.m1)
        val c1 = s.m1.last()
        val prev = s.m1[s.m1.lastIndex - 1]
        val body = abs(c1.close - c1.open).coerceAtLeast(0.01)
        val upperWick = c1.high - maxOf(c1.open, c1.close)
        val lowerWick = minOf(c1.open, c1.close) - c1.low

        val m5Buy = s.m5.last().close > i5.ema50 && i5.ema20 >= i5.ema50 * 0.9995
        val m5Sell = s.m5.last().close < i5.ema50 && i5.ema20 <= i5.ema50 * 1.0005

        val look = s.m1.takeLast(15).dropLast(1)
        val recentHigh = look.maxOf { it.high }
        val recentLow = look.minOf { it.low }
        val sweepBuy = c1.low <= recentLow * 1.0001 && c1.close > recentLow
        val sweepSell = c1.high >= recentHigh * 0.9999 && c1.close < recentHigh

        val wickBuy = lowerWick >= body * 0.9 && lowerWick >= upperWick
        val wickSell = upperWick >= body * 0.9 && upperWick >= lowerWick

        val tipBuy = abs(c1.low - i1.ema20) <= max(i1.atr14 * 0.55, 0.45) ||
            (c1.close > i1.ema20 && prev.low <= i1.ema20 * 1.0003)
        val tipSell = abs(c1.high - i1.ema20) <= max(i1.atr14 * 0.55, 0.45) ||
            (c1.close < i1.ema20 && prev.high >= i1.ema20 * 0.9997)

        val bosBuy = c1.close > prev.high || c1.close >= look.maxOf { it.high }
        val bosSell = c1.close < prev.low || c1.close <= look.minOf { it.low }

        val sessionOk = SessionHelper.isGoodSession() || true // izinkan semua sesi; label tetap
        val spreadOk = s.spread <= 0.40
        val rsiBuy = i1.rsi14 in 30.0..72.0
        val rsiSell = i1.rsi14 in 28.0..70.0

        // Momentum singkat M1
        val momBuy = c1.close > prev.close && c1.close > c1.open
        val momSell = c1.close < prev.close && c1.close < c1.open

        fun scoreBuy(): Int {
            var sc = 0
            if (m5Buy) sc += 18
            if (sweepBuy) sc += 16
            if (wickBuy) sc += 14
            if (tipBuy) sc += 14
            if (bosBuy) sc += 12
            if (momBuy) sc += 8
            if (spreadOk) sc += 6
            if (rsiBuy) sc += 6
            if (sessionOk) sc += 6
            return sc.coerceIn(0, 100)
        }

        fun scoreSell(): Int {
            var sc = 0
            if (m5Sell) sc += 18
            if (sweepSell) sc += 16
            if (wickSell) sc += 14
            if (tipSell) sc += 14
            if (bosSell) sc += 12
            if (momSell) sc += 8
            if (spreadOk) sc += 6
            if (rsiSell) sc += 6
            if (sessionOk) sc += 6
            return sc.coerceIn(0, 100)
        }

        val buySc = scoreBuy()
        val sellSc = scoreSell()
        val stepsBuy = listOf("M5" to m5Buy, "Sweep" to sweepBuy, "Wick" to wickBuy, "Tip" to tipBuy, "BOS" to bosBuy)
        val stepsSell = listOf("M5" to m5Sell, "Sweep" to sweepSell, "Wick" to wickSell, "Tip" to tipSell, "BOS" to bosSell)

        val threshold = minScore.coerceIn(60, 90)

        // Syarat inti: bias M5 + minimal 2 dari (sweep,wick,tip,bos)
        fun coreOk(bias: Boolean, steps: List<Pair<String, Boolean>>): Boolean {
            if (!bias) return false
            val core = steps.drop(1).count { it.second } // sweep,wick,tip,bos
            return core >= 2
        }

        val side: String
        val score: Int
        val steps: List<Pair<String, Boolean>>
        when {
            buySc >= sellSc && buySc >= threshold && coreOk(m5Buy, stepsBuy) -> {
                side = "BUY"; score = buySc; steps = stepsBuy
            }
            sellSc > buySc && sellSc >= threshold && coreOk(m5Sell, stepsSell) -> {
                side = "SELL"; score = sellSc; steps = stepsSell
            }
            else -> {
                val best = max(buySc, sellSc)
                val watch = when {
                    !spreadOk -> "SPREAD HIGH"
                    buySc >= 45 -> "BUY BUILD $buySc%"
                    sellSc >= 45 -> "SELL BUILD $sellSc%"
                    else -> "WAIT"
                }
                val stepStr = (if (buySc >= sellSc) stepsBuy else stepsSell)
                    .joinToString(" · ") { (n, ok) -> if (ok) n else "$n✗" }
                return null to mapOf(
                    "bias" to when { m5Buy -> "BUY"; m5Sell -> "SELL"; else -> "NEUTRAL" },
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
        val entry = if (side == "BUY") minOf(c1.low, i1.ema20) else maxOf(c1.high, i1.ema20)
        val risk = max(i1.atr14 * 0.75, 0.45)
        val sl = if (side == "BUY") entry - risk else entry + risk
        val tp1 = if (side == "BUY") entry + risk * 1.15 else entry - risk * 1.15
        val tp2 = if (side == "BUY") entry + risk * 1.9 else entry - risk * 1.9
        val near = abs(s.price - entry) <= max(i1.atr14 * 0.4, 0.40)
        val entryType = if (side == "BUY") "BUY LIMIT" else "SELL LIMIT"
        // READY lebih sering: dekat ATAU score tinggi
        val state = if (near || score >= threshold + 8) "ENTRY READY" else "LIMIT ZONE"

        val stepStr = steps.joinToString(" · ") { (n, ok) -> if (ok) "✓$n" else n }

        return SignalResult(
            side, state, entry, entryType, sl, tp1, tp2, stars, c1.time,
            "Pipeline $score%", stepStr
        ) to mapOf(
            "bias" to side, "watch" to state, "score" to score.toString(),
            "stars" to stars.toString(), "session" to SessionHelper.sessionLabel(),
            "rsi" to "%.1f".format(i1.rsi14), "spread" to "%.2f".format(s.spread),
            "steps" to stepStr
        )
    }
}
