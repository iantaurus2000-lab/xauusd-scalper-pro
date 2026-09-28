package com.xauusd.scalper

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Market data provider using biquote.io (free, no API key).
 * Source: MetaTrader 5 broker feed.
 */
object Market {
    private const val SYMBOL = "XAUUSD"
    private const val BASE = "https://biquote.io/api"

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 12000
        c.requestMethod = "GET"
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "XAUUSD-Scalper/4.2")
        return try {
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            stream?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("Empty response (${c.responseCode})")
        } finally {
            c.disconnect()
        }
    }

    /** Live mid price (bid+ask)/2 */
    fun price(): Double {
        val text = get("$BASE/$SYMBOL")
        val root = JSONObject(text)
        val mid = root.optDouble("mid", Double.NaN)
        if (mid.isNaN() || mid <= 0) {
            val bid = root.optDouble("bid", Double.NaN)
            val ask = root.optDouble("ask", Double.NaN)
            if (!bid.isNaN() && !ask.isNaN()) return (bid + ask) / 2.0
            throw IllegalStateException("Harga XAUUSD tidak tersedia")
        }
        return mid
    }

    /**
     * OHLC candles. interval: "1m" | "5m" | "15m"
     * Returns oldest → newest (same order as previous Twelve Data code).
     */
    fun candles(interval: String, outputSize: Int = 120): List<Candle> {
        val mapped = when (interval) {
            "1min", "1m" -> "1m"
            "5min", "5m" -> "5m"
            "15min", "15m" -> "15m"
            else -> interval
        }
        val url = "$BASE/$SYMBOL/ohlc?interval=$mapped&limit=$outputSize"
        val root = JSONObject(get(url))
        val bars = root.optJSONArray("bars")
            ?: throw IllegalStateException("Data candle $mapped kosong")

        val out = ArrayList<Candle>(bars.length())
        // biquote returns newest-first → reverse to oldest-first
        for (i in bars.length() - 1 downTo 0) {
            val x = bars.getJSONObject(i)
            val o = x.optDouble("open", Double.NaN)
            val h = x.optDouble("high", Double.NaN)
            val l = x.optDouble("low", Double.NaN)
            val cl = x.optDouble("close", Double.NaN)
            if (o.isNaN() || h.isNaN() || l.isNaN() || cl.isNaN()) continue
            val time = x.optString("openTime", "")
            out.add(Candle(time, o, h, l, cl))
        }
        if (out.size < 30) throw IllegalStateException("Candle $mapped kurang dari 30 bar")
        return out
    }

    fun snapshot(previousPrice: Double = Double.NaN): MarketSnapshot {
        val m1 = candles("1m", 120)
        val m5 = candles("5m", 120)
        val m15 = candles("15m", 120)
        val live = price()
        return MarketSnapshot(
            price = live,
            m1 = m1,
            m5 = m5,
            m15 = m15,
            previousPrice = if (previousPrice.isNaN()) live else previousPrice
        )
    }
}
