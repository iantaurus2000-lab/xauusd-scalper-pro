package com.xauusd.scalper

import kotlin.math.abs
import kotlin.math.max

/**
 * Professional confluence engine — skor 0–100, sinyal hanya jika ≥ 78.
 * BUY rules (dari spesifikasi user):
 * - M5: price > EMA50 && EMA20 > EMA50
 * - M1: pullback ke EMA20 + struktur bullish
 * - RSI 40–65
 * - Range candle > rata-rata 10 (proxy volume CFD)
 * - Session London / NY (WITA)
 * - Spread ≤ 0.25
 * - Di atas support, jauh dari resistance
 */
object SignalEngine {

    data class Confluence(
        val score: Int,
        val stars: Int,
        val checks: Map<String, Boolean>,
        val side: String
    )

    fun evaluate(s: MarketSnapshot): Pair<SignalResult?, Map<String, String>> {
        if (s.m1.size < 40 || s.m5.size < 60) {
            return null to mapOf(
                "bias" to "WAITING",
                "watch" to "LOADING",
                "score" to "0",
                "session" to SessionHelper.sessionLabel()
            )
        }

        val i5 = IndicatorEngine.snapshot(s.m5)
        val i1 = IndicatorEngine.snapshot(s.m1)
        val c1 = s.m1.last()
        val prev = s.m1[s.m1.lastIndex - 1]

        // --- BUY checks ---
        val buyTrend = s.m5.last().close > i5.ema50 && i5.ema20 > i5.ema50
        val buyPullback = abs(c1.low - i1.ema20) <= max(i1.atr14 * 0.45, 0.35) ||
            (c1.close > i1.ema20 && prev.low <= i1.ema20 * 1.0002)
        val buyRsi = i1.rsi14 in 40.0..65.0
        val avgRange = s.m1.takeLast(11).dropLast(1).map { it.high - it.low }.average()
        val buyVol = (c1.high - c1.low) >= avgRange * 0.95 // proxy volume
        val sessionOk = SessionHelper.isGoodSession()
        val buySpread = s.spread <= 0.25
        val buyStruct = s.price > i1.support && abs(i1.resistance - s.price) > i1.atr14 * 0.6
        val buyBos = c1.close > prev.high || c1.close > s.m1.takeLast(8).dropLast(1).maxOf { it.high } * 0.999

        val buyFlags = linkedMapOf(
            "trend_m5" to buyTrend,
            "pullback_m1" to buyPullback,
            "rsi" to buyRsi,
            "range_vol" to buyVol,
            "session" to sessionOk,
            "spread" to buySpread,
            "structure" to buyStruct,
            "bos" to buyBos
        )
        val buyHits = buyFlags.values.count { it }
        val buyScore = (buyHits * 100) / 8

        // --- SELL checks (mirror) ---
        val sellTrend = s.m5.last().close < i5.ema50 && i5.ema20 < i5.ema50
        val sellPullback = abs(c1.high - i1.ema20) <= max(i1.atr14 * 0.45, 0.35) ||
            (c1.close < i1.ema20 && prev.high >= i1.ema20 * 0.9998)
        val sellRsi = i1.rsi14 in 35.0..60.0
        val sellVol = buyVol
        val sellSpread = buySpread
        val sellStruct = s.price < i1.resistance && abs(s.price - i1.support) > i1.atr14 * 0.6
        val sellBos = c1.close < prev.low || c1.close < s.m1.takeLast(8).dropLast(1).minOf { it.low } * 1.001

        val sellFlags = linkedMapOf(
            "trend_m5" to sellTrend,
            "pullback_m1" to sellPullback,
            "rsi" to sellRsi,
            "range_vol" to sellVol,
            "session" to sessionOk,
            "spread" to sellSpread,
            "structure" to sellStruct,
            "bos" to sellBos
        )
        val sellHits = sellFlags.values.count { it }
        val sellScore = (sellHits * 100) / 8

        val side: String
        val score: Int
        val flags: Map<String, Boolean>
        when {
            buyScore >= sellScore && buyScore >= 78 -> {
                side = "BUY"; score = buyScore; flags = buyFlags
            }
            sellScore > buyScore && sellScore >= 78 -> {
                side = "SELL"; score = sellScore; flags = sellFlags
            }
            else -> {
                val watch = when {
                    !sessionOk -> "OFF SESSION"
                    s.spread > 0.25 -> "SPREAD HIGH"
                    buyScore >= 50 -> "BUY WATCH ${buyScore}%"
                    sellScore >= 50 -> "SELL WATCH ${sellScore}%"
                    else -> "WAIT"
                }
                return null to mapOf(
                    "bias" to when {
                        buyTrend -> "BUY"
                        sellTrend -> "SELL"
                        else -> "NEUTRAL"
                    },
                    "watch" to watch,
                    "score" to max(buyScore, sellScore).toString(),
                    "stars" to (max(buyScore, sellScore) / 20).coerceIn(0, 5).toString(),
                    "session" to SessionHelper.sessionLabel(),
                    "rsi" to "%.1f".format(i1.rsi14),
                    "spread" to "%.2f".format(s.spread)
                )
            }
        }

        val stars = (score / 20).coerceIn(1, 5)
        val limit = if (side == "BUY") {
            // limit di area EMA20 / low wick
            minOf(i1.ema20, c1.low)
        } else {
            maxOf(i1.ema20, c1.high)
        }
        val risk = max(i1.atr14 * 0.9, 0.50)
        val sl = if (side == "BUY") limit - risk else limit + risk
        val tp1 = if (side == "BUY") limit + risk else limit - risk
        val tp2 = if (side == "BUY") limit + risk * 1.8 else limit - risk * 1.8
        val near = abs(s.price - limit) <= max(i1.atr14 * 0.4, 0.30)
        val entryType = if (side == "BUY") "BUY LIMIT" else "SELL LIMIT"
        val state = if (near) "ENTRY READY" else "LIMIT PENDING"

        val status = mutableMapOf(
            "bias" to side,
            "watch" to state,
            "score" to score.toString(),
            "stars" to stars.toString(),
            "session" to SessionHelper.sessionLabel(),
            "rsi" to "%.1f".format(i1.rsi14),
            "spread" to "%.2f".format(s.spread),
            "checks" to flags.filter { it.value }.keys.joinToString(",")
        )

        return SignalResult(
            side = side,
            state = state,
            entry = limit,
            entryType = entryType,
            sl = sl,
            tp1 = tp1,
            tp2 = tp2,
            confidence = stars,
            candleTime = c1.time,
            setup = "Confluence $score% (≥78) → $entryType",
            reason = "Score $score/100 • ${flags.count { it.value }}/8 checks"
        ) to status
    }
}
