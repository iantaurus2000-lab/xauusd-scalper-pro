package com.xauusd.scalper

data class Candle(
    val time: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

data class MarketSnapshot(
    val price: Double,
    val m1: List<Candle>,
    val m5: List<Candle>,
    val m15: List<Candle> = emptyList(),
    val spread: Double = 0.30
)

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
