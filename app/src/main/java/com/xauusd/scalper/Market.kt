package com.xauusd.scalper

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Realtime data from biquote.io (gratis, no API key).
 * REST: /api/XAUUSD + /api/XAUUSD/ohlc
 */
object Market {
    private const val BASE = "https://biquote.io"
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun snapshot(lastPrice: Double = Double.NaN): MarketSnapshot {
        return try {
            val tick = fetchTick()
            val m1 = fetchOhlc("1m", 120)
            val m5 = fetchOhlc("5m", 100)
            val m15 = fetchOhlc("15m", 80)
            val price = tick.first
            val spread = tick.second
            MarketSnapshot(
                price = if (price.isNaN() && m1.isNotEmpty()) m1.last().close else price,
                m1 = m1.ifEmpty { demoCandles(if (lastPrice.isNaN()) 2650.0 else lastPrice, 80, 0.12) },
                m5 = m5.ifEmpty { demoCandles(if (lastPrice.isNaN()) 2650.0 else lastPrice, 80, 0.28) },
                m15 = m15.ifEmpty { demoCandles(if (lastPrice.isNaN()) 2650.0 else lastPrice, 60, 0.45) },
                spread = if (spread.isNaN()) 0.25 else spread
            )
        } catch (_: Exception) {
            val base = if (lastPrice.isNaN()) 2650.0 else lastPrice
            MarketSnapshot(base, demoCandles(base, 80, 0.12), demoCandles(base, 80, 0.28), demoCandles(base, 60, 0.45), 0.30)
        }
    }

    private fun fetchTick(): Pair<Double, Double> {
        val req = Request.Builder()
            .url("$BASE/api/XAUUSD")
            .header("User-Agent", "XAUUSDScalper/5.2")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return Double.NaN to Double.NaN
            val body = resp.body?.string() ?: return Double.NaN to Double.NaN
            val o = JSONObject(body)
            val mid = o.optDouble("mid", o.optDouble("price", Double.NaN))
            val bid = o.optDouble("bid", Double.NaN)
            val ask = o.optDouble("ask", Double.NaN)
            val spread = when {
                !bid.isNaN() && !ask.isNaN() -> ask - bid
                o.has("spread") -> o.optDouble("spread")
                else -> 0.25
            }
            return mid to spread
        }
    }

    private fun fetchOhlc(interval: String, limit: Int): List<Candle> {
        val req = Request.Builder()
            .url("$BASE/api/XAUUSD/ohlc?interval=$interval&limit=$limit")
            .header("User-Agent", "XAUUSDScalper/5.2")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val root = JSONObject(body)
            val bars: JSONArray = when {
                root.has("bars") -> root.getJSONArray("bars")
                body.trim().startsWith("[") -> JSONArray(body)
                else -> return emptyList()
            }
            val list = ArrayList<Candle>(bars.length())
            for (i in 0 until bars.length()) {
                val b = bars.getJSONObject(i)
                val t = b.optString("openTime", b.optString("time", "C$i"))
                list.add(
                    Candle(
                        t,
                        b.getDouble("open"),
                        b.getDouble("high"),
                        b.getDouble("low"),
                        b.getDouble("close")
                    )
                )
            }
            // API often newest-first → oldest first for indicators
            return if (list.size >= 2 && list.first().close != list.last().close) {
                // check time order if possible; reverse if needed by comparing size only
                list.asReversed()
            } else list.asReversed()
        }
    }

    private fun demoCandles(base: Double, n: Int, vol: Double): List<Candle> {
        val list = ArrayList<Candle>(n)
        var p = base
        for (i in 0 until n) {
            val d = (Math.random() - 0.48) * vol
            val o = p
            val c = p + d
            val h = maxOf(o, c) + Math.random() * vol * 0.35
            val l = minOf(o, c) - Math.random() * vol * 0.35
            list.add(Candle("D$i", o, h, l, c))
            p = c
        }
        return list
    }
}
