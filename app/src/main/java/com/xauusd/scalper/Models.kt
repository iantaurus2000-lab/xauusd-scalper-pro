package com.xauusd.scalper

/** Satu candle OHLC */
data class Candle(
    val time: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

/** Snapshot pasar lengkap untuk chart + sinyal */
data class MarketSnapshot(
    val price: Double,
    val bid: Double = Double.NaN,
    val ask: Double = Double.NaN,
    val m1: List<Candle>,
    val m5: List<Candle>,
    val m15: List<Candle> = emptyList(),
    val spread: Double = 0.30
)

/** Hasil sinyal entry */
data class SignalResult(
    val side: String,
    val state: String,
    val entry: Double,
    val entryType: String,
    val sl: Double,
    val tp1: Double,
    val tp2: Double,
    val confidence: Int,
    val candleTime: String,
    val setup: String,
    val reason: String
)

/** Indikator terhitung untuk 1 timeframe */
data class IndicatorSnapshot(
    val ema20: Double,
    val ema50: Double,
    val rsi14: Double,
    val atr14: Double,
    val support: Double,
    val resistance: Double,
    val fib236: Double,
    val fib382: Double,
    val fib50: Double,
    val fib618: Double,
    val fib786: Double
)
