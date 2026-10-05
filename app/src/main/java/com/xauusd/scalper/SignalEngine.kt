package com.xauusd.scalper

import kotlin.math.abs
import kotlin.math.max

/**
 * SIGNAL ENGINE v5.13
 *
 * Pipeline:
 *  M5 Bias → Liquidity Sweep → Wick Rejection (ujung jarum)
 *  → Wick-Tip Entry (M5 candle -2 early) → BOS → EMA/RSI/MACD/ATR/Spread
 *  → Candle pattern AI score → Confidence 1–5 → Signal
 *
 * Format notif:
 *  🟢 XAUUSD SCALPING
 */
object SignalEngine {

    fun evaluate(s: MarketSnapshot, minScore: Int = 68): Pair<SignalResult?, Map<String, String>> {
        if (s.m1.size < 30 || s.m5.size < 35) {
            return null to mapOf(
                "bias" to "WAIT", "watch" to "LOADING", "score" to "0", "stars" to "0",
                "session" to SessionHelper.sessionLabel(), "steps" to "loading", "pattern" to "-"
            )
        }

        val i5 = IndicatorEngine.snapshot(s.m5)
        val i1 = IndicatorEngine.snapshot(s.m1)
        val macd5 = IndicatorEngine.macd(s.m5.map { it.close })
        val macd1 = IndicatorEngine.macd(s.m1.map { it.close })
        val patterns = CandlePatterns.analyze(s.m5)

        val c1 = s.m1.last()
        val prev1 = s.m1[s.m1.lastIndex - 1]
        // M5: candle terakhir + 2 candle sebelumnya (early entry)
        val m5z = s.m5.last()
        val m5b = s.m5[s.m5.lastIndex - 1]
        val m5a = s.m5[s.m5.lastIndex - 2]

        val body1 = abs(c1.close - c1.open).coerceAtLeast(0.01)
        val upper1 = c1.high - maxOf(c1.open, c1.close)
        val lower1 = minOf(c1.open, c1.close) - c1.low

        // ——— M5 Bias (EMA 20/50 + close) ———
        val m5Buy = m5z.close > i5.ema50 && i5.ema20 >= i5.ema50 * 0.9994
        val m5Sell = m5z.close < i5.ema50 && i5.ema20 <= i5.ema50 * 1.0006

        // ——— Liquidity Sweep (M1) ———
        val look = s.m1.takeLast(18).dropLast(1)
        val recentHigh = look.maxOf { it.high }
        val recentLow = look.minOf { it.low }
        val sweepBuy = c1.low <= recentLow * 1.00015 && c1.close > recentLow
        val sweepSell = c1.high >= recentHigh * 0.99985 && c1.close < recentHigh

        // ——— Wick Rejection / jarum ———
        val wickBuy = lower1 >= body1 * 0.85 && lower1 >= upper1
        val wickSell = upper1 >= body1 * 0.85 && upper1 >= lower1

        // ——— Early tip dari M5 candle -2 (2 candle sebelum) ———
        fun m5WickLow(x: Candle): Double {
            val bd = abs(x.close - x.open).coerceAtLeast(0.01)
            return minOf(x.open, x.close) - x.low
        }
        fun m5WickHigh(x: Candle): Double {
            return x.high - maxOf(x.open, x.close)
        }
        val earlyTipBuy = m5WickLow(m5b) >= abs(m5b.close - m5b.open) * 1.0 ||
            m5WickLow(m5a) >= abs(m5a.close - m5a.open) * 1.1
        val earlyTipSell = m5WickHigh(m5b) >= abs(m5b.close - m5b.open) * 1.0 ||
            m5WickHigh(m5a) >= abs(m5a.close - m5a.open) * 1.1

        // Tip M1 ke EMA20
        val tipBuy = abs(c1.low - i1.ema20) <= max(i1.atr14 * 0.6, 0.4) ||
            (c1.close > i1.ema20 && prev1.low <= i1.ema20 * 1.0004) || earlyTipBuy
        val tipSell = abs(c1.high - i1.ema20) <= max(i1.atr14 * 0.6, 0.4) ||
            (c1.close < i1.ema20 && prev1.high >= i1.ema20 * 0.9996) || earlyTipSell

        // ——— BOS ———
        val bosBuy = c1.close > prev1.high || c1.close >= look.maxOf { it.high }
        val bosSell = c1.close < prev1.low || c1.close <= look.minOf { it.low }

        // ——— Filters ———
        val spreadOk = s.spread <= 0.45
        val atrOk = i1.atr14 in 0.15..3.5 // volatilitas wajar XAU scalping
        val rsiBuy = i1.rsi14 in 28.0..68.0
        val rsiSell = i1.rsi14 in 32.0..72.0
        val macdBuy = macd5.bull || macd1.hist > 0
        val macdSell = !macd5.bull || macd1.hist < 0
        val emaFilterBuy = s.price >= i1.ema20 * 0.999 || s.price >= i5.ema20 * 0.999
        val emaFilterSell = s.price <= i1.ema20 * 1.001 || s.price <= i5.ema20 * 1.001

        val patBuy = CandlePatterns.bestForSide(patterns, true)
        val patSell = CandlePatterns.bestForSide(patterns, false)

        fun scoreBuy(): Int {
            var sc = 0
            if (m5Buy) sc += 16
            if (sweepBuy) sc += 12
            if (wickBuy) sc += 12
            if (tipBuy) sc += 12
            if (earlyTipBuy) sc += 8
            if (bosBuy) sc += 10
            if (emaFilterBuy) sc += 6
            if (rsiBuy) sc += 6
            if (macdBuy) sc += 6
            if (atrOk) sc += 4
            if (spreadOk) sc += 4
            sc += (patBuy?.strength ?: 0) * 2
            return sc.coerceIn(0, 100)
        }

        fun scoreSell(): Int {
            var sc = 0
            if (m5Sell) sc += 16
            if (sweepSell) sc += 12
            if (wickSell) sc += 12
            if (tipSell) sc += 12
            if (earlyTipSell) sc += 8
            if (bosSell) sc += 10
            if (emaFilterSell) sc += 6
            if (rsiSell) sc += 6
            if (macdSell) sc += 6
            if (atrOk) sc += 4
            if (spreadOk) sc += 4
            sc += (patSell?.strength ?: 0) * 2
            return sc.coerceIn(0, 100)
        }

        val buySc = scoreBuy()
        val sellSc = scoreSell()
        val stepsBuy = listOf(
            "M5" to m5Buy, "Sweep" to sweepBuy, "Wick" to wickBuy,
            "Tip" to tipBuy, "BOS" to bosBuy, "EMA" to emaFilterBuy,
            "RSI" to rsiBuy, "MACD" to macdBuy
        )
        val stepsSell = listOf(
            "M5" to m5Sell, "Sweep" to sweepSell, "Wick" to wickSell,
            "Tip" to tipSell, "BOS" to bosSell, "EMA" to emaFilterSell,
            "RSI" to rsiSell, "MACD" to macdSell
        )

        val threshold = minScore.coerceIn(55, 88)

        fun coreOk(bias: Boolean, steps: List<Pair<String, Boolean>>, early: Boolean): Boolean {
            if (!bias) return false
            val core = listOf(steps[1], steps[2], steps[3], steps[4]).count { it.second }
            // Early: cukup bias + tip/wick dari M5-2 + 1 konfirmasi
            if (early && core >= 1) return true
            return core >= 2
        }

        val side: String
        val score: Int
        val steps: List<Pair<String, Boolean>>
        val patName: String
        when {
            buySc >= sellSc && buySc >= threshold && coreOk(m5Buy, stepsBuy, earlyTipBuy) -> {
                side = "BUY"; score = buySc; steps = stepsBuy
                patName = patBuy?.name ?: if (earlyTipBuy) "M5-2_WICK" else "CONFLUENCE"
            }
            sellSc > buySc && sellSc >= threshold && coreOk(m5Sell, stepsSell, earlyTipSell) -> {
                side = "SELL"; score = sellSc; steps = stepsSell
                patName = patSell?.name ?: if (earlyTipSell) "M5-2_WICK" else "CONFLUENCE"
            }
            else -> {
                val best = max(buySc, sellSc)
                val watch = when {
                    !spreadOk -> "SPREAD HIGH"
                    !atrOk -> "ATR FILTER"
                    buySc >= 40 -> "BUY BUILD $buySc%"
                    sellSc >= 40 -> "SELL BUILD $sellSc%"
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
                    "rsi" to "%.0f".format(i1.rsi14),
                    "spread" to "%.2f".format(s.spread),
                    "steps" to stepStr,
                    "pattern" to (patterns.firstOrNull()?.name ?: "-")
                )
            }
        }

        val stars = (score / 20).coerceIn(1, 5)
        // Entry di ujung jarum: low/high wick atau EMA
        val entry = if (side == "BUY") {
            minOf(c1.low, m5b.low, i1.ema20)
        } else {
            maxOf(c1.high, m5b.high, i1.ema20)
        }
        val risk = max(i1.atr14 * 0.7, max(i5.atr14 * 0.35, 0.40))
        val sl = if (side == "BUY") entry - risk else entry + risk
        val tp1 = if (side == "BUY") entry + risk * 1.2 else entry - risk * 1.2
        val tp2 = if (side == "BUY") entry + risk * 2.0 else entry - risk * 2.0
        val near = abs(s.price - entry) <= max(i1.atr14 * 0.45, 0.50)
        val entryType = if (side == "BUY") "BUY LIMIT" else "SELL LIMIT"
        val state = if (near || score >= threshold + 6 || earlyTipBuy || earlyTipSell) {
            "ENTRY READY"
        } else "LIMIT ZONE"

        val stepStr = steps.joinToString(" · ") { (n, ok) -> if (ok) "✓$n" else n }
        val header = if (side == "BUY") "🟢 XAUUSD SCALPING" else "🔴 XAUUSD SCALPING"
        val reason = "$header · $patName · RR 1:${"%.1f".format(1.2)}"

        return SignalResult(
            side, state, entry, entryType, sl, tp1, tp2, stars, c1.time,
            "AI $score% · $patName", reason
        ) to mapOf(
            "bias" to side, "watch" to state, "score" to score.toString(),
            "stars" to stars.toString(), "session" to SessionHelper.sessionLabel(),
            "rsi" to "%.0f".format(i1.rsi14), "spread" to "%.2f".format(s.spread),
            "steps" to stepStr, "pattern" to patName, "header" to header
        )
    }
}
