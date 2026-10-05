package com.xauusd.scalper

import kotlin.math.abs

/**
 * Pola candle XAUUSD scalping — pinbar, engulfing, doji, hammer, shooting star,
 * inside bar, rejection wick. Dipakai 1–2 candle sebelum entry M5.
 */
object CandlePatterns {

    data class Pattern(
        val name: String,
        val bullish: Boolean,
        val strength: Int // 1–5
    )

    fun analyze(c: List<Candle>): List<Pattern> {
        if (c.size < 3) return emptyList()
        val out = ArrayList<Pattern>()
        val a = c[c.size - 3]
        val b = c[c.size - 2] // candle -2 (kunci early entry)
        val z = c.last()

        fun body(x: Candle) = abs(x.close - x.open)
        fun range(x: Candle) = (x.high - x.low).coerceAtLeast(0.01)
        fun upper(x: Candle) = x.high - maxOf(x.open, x.close)
        fun lower(x: Candle) = minOf(x.open, x.close) - x.low

        // Pinbar / hammer (bull)
        if (lower(z) >= body(z) * 1.5 && lower(z) > upper(z) * 1.2 && body(z) / range(z) < 0.4) {
            out.add(Pattern("HAMMER", true, 4))
        }
        // Shooting star (bear)
        if (upper(z) >= body(z) * 1.5 && upper(z) > lower(z) * 1.2 && body(z) / range(z) < 0.4) {
            out.add(Pattern("SHOOTING_STAR", false, 4))
        }
        // Bullish engulfing
        if (b.close < b.open && z.close > z.open && z.open <= b.close && z.close >= b.open) {
            out.add(Pattern("ENGULF_BULL", true, 5))
        }
        // Bearish engulfing
        if (b.close > b.open && z.close < z.open && z.open >= b.close && z.close <= b.open) {
            out.add(Pattern("ENGULF_BEAR", false, 5))
        }
        // Doji (indecision → tunggu konfirmasi)
        if (body(z) / range(z) < 0.12) {
            out.add(Pattern("DOJI", z.close >= z.open, 2))
        }
        // Inside bar
        if (z.high <= b.high && z.low >= b.low) {
            out.add(Pattern("INSIDE", z.close > z.open, 2))
        }
        // Rejection wick at extreme of b (candle -2) — early tip
        if (lower(b) >= body(b) * 1.2 && b.close > b.open) {
            out.add(Pattern("WICK_REJECT_LOW", true, 4))
        }
        if (upper(b) >= body(b) * 1.2 && b.close < b.open) {
            out.add(Pattern("WICK_REJECT_HIGH", false, 4))
        }
        // Strong marubozu
        if (body(z) / range(z) > 0.75) {
            out.add(Pattern(if (z.close > z.open) "MARU_BULL" else "MARU_BEAR", z.close > z.open, 3))
        }
        // Three-bar momentum on a-b-z
        if (a.close < a.open && b.close > b.open && z.close > z.open && z.close > a.high) {
            out.add(Pattern("REVERSAL_BULL", true, 4))
        }
        if (a.close > a.open && b.close < b.open && z.close < z.open && z.close < a.low) {
            out.add(Pattern("REVERSAL_BEAR", false, 4))
        }
        return out
    }

    fun bestForSide(patterns: List<Pattern>, buy: Boolean): Pattern? =
        patterns.filter { it.bullish == buy }.maxByOrNull { it.strength }
}
