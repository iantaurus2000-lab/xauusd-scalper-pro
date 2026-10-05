package com.xauusd.scalper

import kotlin.math.abs

/**
 * Analisa AI lokal untuk prediksi candle berikutnya (hijau/merah).
 * Memakai 2 bar M5 sebelum candle terakhir + EMA/RSI/MACD/pola.
 */
object AiAnalyst {

    data class Forecast(
        val greenPct: Int,
        val redPct: Int,
        val bias: String,
        val nextHint: String,
        val reasons: List<String>,
        val confidence: Int,
        val patternNote: String,
        val fullText: String
    )

    fun forecast(s: MarketSnapshot): Forecast {
        if (s.m5.size < 20 || s.m1.size < 25) {
            return Forecast(
                50, 50, "NETRAL", "Data kurang",
                listOf("Tunggu data OHLC"), 0, "-", "Loading data market..."
            )
        }

        val m5 = s.m5
        val m1 = s.m1
        val i5 = IndicatorEngine.snapshot(m5)
        val i1 = IndicatorEngine.snapshot(m1)
        val macd = IndicatorEngine.macd(m5.map { it.close })
        val patterns = CandlePatterns.analyze(m5)

        val cA = m5[m5.lastIndex - 2]
        val cB = m5[m5.lastIndex - 1]
        val cZ = m5.last()

        var greenScore = 50.0
        val reasons = ArrayList<String>()

        when {
            cZ.close > i5.ema50 && i5.ema20 > i5.ema50 -> {
                greenScore += 12.0
                reasons.add("M5 di atas EMA50 (bull bias)")
            }
            cZ.close < i5.ema50 && i5.ema20 < i5.ema50 -> {
                greenScore -= 12.0
                reasons.add("M5 di bawah EMA50 (bear bias)")
            }
            else -> reasons.add("M5 netral terhadap EMA")
        }

        val upAB = cA.close < cB.close && cB.close > cB.open
        val dnAB = cA.close > cB.close && cB.close < cB.open
        if (upAB) {
            greenScore += 10.0
            reasons.add("2 bar M5 sebelumnya bullish")
        }
        if (dnAB) {
            greenScore -= 10.0
            reasons.add("2 bar M5 sebelumnya bearish")
        }

        val bodyB = abs(cB.close - cB.open).coerceAtLeast(0.01)
        val lowWickB = minOf(cB.open, cB.close) - cB.low
        val upWickB = cB.high - maxOf(cB.open, cB.close)
        if (lowWickB >= bodyB * 1.2) {
            greenScore += 14.0
            reasons.add("Wick rejection BAWAH di M5-2 (jarum beli)")
        }
        if (upWickB >= bodyB * 1.2) {
            greenScore -= 14.0
            reasons.add("Wick rejection ATAS di M5-2 (jarum jual)")
        }

        when {
            i1.rsi14 < 35 -> {
                greenScore += 8.0
                reasons.add("RSI oversold - rebound hijau")
            }
            i1.rsi14 > 65 -> {
                greenScore -= 8.0
                reasons.add("RSI overbought - koreksi merah")
            }
            else -> reasons.add("RSI netral (" + "%.0f".format(i1.rsi14) + ")")
        }

        if (macd.hist > 0 && macd.bull) {
            greenScore += 8.0
            reasons.add("MACD hist positif")
        } else if (macd.hist < 0 && !macd.bull) {
            greenScore -= 8.0
            reasons.add("MACD hist negatif")
        }

        for (p in patterns) {
            val d = p.strength * 2.5
            if (p.bullish) {
                greenScore += d
                reasons.add("Pola " + p.name + " (bull)")
            } else {
                greenScore -= d
                reasons.add("Pola " + p.name + " (bear)")
            }
        }

        if (SessionHelper.isGoodSession()) {
            reasons.add("Sesi aktif: " + SessionHelper.sessionLabel())
        } else {
            greenScore = 50.0 + (greenScore - 50.0) * 0.7
            reasons.add("Di luar sesi utama - lebih hati-hati")
        }

        if (s.spread > 0.4) {
            reasons.add("Spread lebar " + "%.2f".format(s.spread))
        }

        greenScore = greenScore.coerceIn(8.0, 92.0)
        val green = greenScore.toInt()
        val red = 100 - green
        val bias = when {
            green >= 62 -> "HIJAU (BUY bias)"
            red >= 62 -> "MERAH (SELL bias)"
            else -> "NETRAL / RANGE"
        }
        val conf = when {
            green >= 72 || red >= 72 -> 5
            green >= 65 || red >= 65 -> 4
            green >= 58 || red >= 58 -> 3
            else -> 2
        }
        val hint = when {
            green >= 62 -> "Candle berikutnya lebih mungkin HIJAU. Siapkan BUY LIMIT di ujung jarum / EMA."
            red >= 62 -> "Candle berikutnya lebih mungkin MERAH. Siapkan SELL LIMIT di ujung jarum / EMA."
            else -> "Belum ada edge kuat. Tunggu wick rejection + BOS di M1."
        }
        val patNote = patterns.joinToString(", ") { it.name }.ifBlank { "tidak dominan" }

        val stars = "★".repeat(conf) + "☆".repeat(5 - conf)
        val text = buildString {
            appendLine("AI ANALISA XAUUSD")
            appendLine("-----------------")
            appendLine("Prediksi candle berikutnya:")
            appendLine("  HIJAU  " + green + "%")
            appendLine("  MERAH  " + red + "%")
            appendLine("Bias: " + bias)
            appendLine("Confidence: " + stars)
            appendLine()
            appendLine("Pola: " + patNote)
            appendLine("RSI M1: " + "%.0f".format(i1.rsi14) + " | ATR: " + "%.2f".format(i1.atr14))
            appendLine("Spread: " + "%.2f".format(s.spread))
            appendLine()
            appendLine("Alasan:")
            reasons.take(8).forEach { appendLine("- " + it) }
            appendLine()
            appendLine("Saran: " + hint)
            appendLine()
            appendLine("Catatan: prediksi berbasis aturan +")
            appendLine("statistik lokal, bukan jaminan profit.")
        }

        return Forecast(green, red, bias, hint, reasons, conf, patNote, text)
    }
}
