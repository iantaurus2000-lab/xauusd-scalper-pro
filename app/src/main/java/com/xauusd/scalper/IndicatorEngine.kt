package com.xauusd.scalper

import kotlin.math.abs
import kotlin.math.max

/**
 * Indikator chart: EMA, RSI, ATR, Support/Resistance, Fibonacci.
 */
object IndicatorEngine {

    fun ema(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val out = MutableList(values.size) { Double.NaN }
        val p = period.coerceAtLeast(1)
        val alpha = 2.0 / (p + 1.0)
        var prev = values.take(p.coerceAtMost(values.size)).average()
        for (i in values.indices) {
            if (i < p - 1) continue
            if (i == p - 1) {
                prev = values.subList(0, p).average()
                out[i] = prev
                continue
            }
            prev = alpha * values[i] + (1 - alpha) * prev
            out[i] = prev
        }
        return out
    }

    fun rsi(values: List<Double>, period: Int = 14): Double {
        if (values.size <= period) return 50.0
        var gain = 0.0
        var loss = 0.0
        for (i in values.size - period until values.size) {
            val d = values[i] - values[i - 1]
            if (d >= 0) gain += d else loss -= d
        }
        if (loss == 0.0) return 100.0
        val rs = gain / loss
        return 100.0 - 100.0 / (1.0 + rs)
    }

    fun atr(c: List<Candle>, period: Int = 14): Double {
        if (c.size < period + 1) return 0.5
        val trs = ArrayList<Double>()
        for (i in 1 until c.size) {
            trs.add(
                max(
                    c[i].high - c[i].low,
                    max(abs(c[i].high - c[i - 1].close), abs(c[i].low - c[i - 1].close))
                )
            )
        }
        return trs.takeLast(period).average()
    }

    fun snapshot(c: List<Candle>): IndicatorSnapshot {
        val closes = c.map { it.close }
        val e20 = ema(closes, 20).lastOrNull()?.takeIf { !it.isNaN() } ?: closes.last()
        val e50 = ema(closes, 50).lastOrNull()?.takeIf { !it.isNaN() } ?: closes.last()
        val recent = c.takeLast(60)
        val support = recent.minOf { it.low }
        val resistance = recent.maxOf { it.high }
        val range = (resistance - support).coerceAtLeast(0.5)
        return IndicatorSnapshot(
            ema20 = e20,
            ema50 = e50,
            rsi14 = rsi(closes),
            atr14 = atr(c),
            support = support,
            resistance = resistance,
            fib236 = resistance - range * 0.236,
            fib382 = resistance - range * 0.382,
            fib50 = resistance - range * 0.5,
            fib618 = resistance - range * 0.618,
            fib786 = resistance - range * 0.786
        )
    }
}
