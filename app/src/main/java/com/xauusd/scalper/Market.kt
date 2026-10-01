package com.xauusd.scalper

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Free market data. Tries public endpoints; falls back to synthetic demo candles
 * so the app always runs for testing UI/signals offline.
 */
object Market {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    fun snapshot(lastPrice: Double = Double.NaN): MarketSnapshot {
        return try {
            fetchLive(lastPrice)
        } catch (_: Exception) {
            demoSnapshot(if (lastPrice.isNaN()) 2650.0 else lastPrice)
        }
    }

    private fun fetchLive(lastPrice: Double): MarketSnapshot {
        // Try a simple free gold-related feed pattern; on failure use demo
        val req = Request.Builder()
            .url("https://api.metals.live/v1/spot/gold")
            .header("User-Agent", "XAUUSDScalper/5.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return demoSnapshot(if (lastPrice.isNaN()) 2650.0 else lastPrice)
            val body = resp.body?.string() ?: return demoSnapshot(2650.0)
            // metals.live often returns array like [{"price": ...}]
            val price = parsePrice(body) ?: return demoSnapshot(if (lastPrice.isNaN()) 2650.0 else lastPrice)
            return demoSnapshot(price) // build candles around live price
        }
    }

    private fun parsePrice(body: String): Double? {
        return try {
            when {
                body.trim().startsWith("[") -> {
                    val arr = JSONArray(body)
                    if (arr.length() > 0) {
                        val o = arr.getJSONObject(0)
                        o.optDouble("price", o.optDouble("ask", Double.NaN)).takeIf { !it.isNaN() }
                    } else null
                }
                body.trim().startsWith("{") -> {
                    val o = JSONObject(body)
                    o.optDouble("price", o.optDouble("ask", Double.NaN)).takeIf { !it.isNaN() }
                }
                else -> body.trim().toDoubleOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun demoSnapshot(base: Double): MarketSnapshot {
        val m1 = genCandles(base, 80, 0.15)
        val m5 = genCandles(base, 80, 0.35)
        val m15 = genCandles(base, 60, 0.55)
        val price = m1.last().close
        return MarketSnapshot(price, m1, m5, m15, spread = 0.28)
    }

    private fun genCandles(base: Double, n: Int, vol: Double): List<Candle> {
        val list = ArrayList<Candle>(n)
        var p = base
        for (i in 0 until n) {
            val drift = (Random.nextDouble() - 0.48) * vol
            val o = p
            val c = p + drift
            val h = maxOf(o, c) + Random.nextDouble() * vol * 0.4
            val l = minOf(o, c) - Random.nextDouble() * vol * 0.4
            list.add(Candle("C$i", o, h, l, c))
            p = c
        }
        return list
    }
}
