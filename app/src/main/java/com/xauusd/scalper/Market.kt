package com.xauusd.scalper

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Data realtime XAUUSD dari biquote.io (gratis, no API key).
 *
 * Struktur:
 *  - tick()     → harga 1 detik (mid/bid/ask/spread)
 *  - snapshot() → tick + OHLC M1/M5/M15 (candle terakhir = live mid)
 */
object Market {
    private const val BASE = "https://biquote.io"
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    data class Tick(
        val mid: Double,
        val bid: Double,
        val ask: Double,
        val spread: Double,
        val dayDiff: Double
    )

    /** Poll harga setiap 1 detik — hanya endpoint tick */
    fun tick(): Tick {
        val req = Request.Builder()
            .url("$BASE/api/XAUUSD")
            .header("User-Agent", "XAUUSDScalper/5.8")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("tick HTTP ${resp.code}")
            val o = JSONObject(resp.body?.string() ?: throw Exception("empty body"))
            val mid = o.optDouble("mid", o.optDouble("price", Double.NaN))
            val bid = o.optDouble("bid", mid)
            val ask = o.optDouble("ask", mid)
            val spread = when {
                !bid.isNaN() && !ask.isNaN() -> ask - bid
                o.has("spread") -> o.optDouble("spread")
                else -> 0.25
            }
            val day = o.optDouble("dayDiff", o.optDouble("dayDiffPercent", 0.0))
            if (mid.isNaN()) throw Exception("no mid price")
            return Tick(mid, bid, ask, spread, day)
        }
    }

    /** Snapshot penuh untuk chart + engine */
    fun snapshot(lastPrice: Double = Double.NaN): MarketSnapshot {
        return try {
            val t = tick()
            val m1 = ensureLiveCandle(fetchOhlc("1m", 150), t.mid)
            val m5 = ensureLiveCandle(fetchOhlc("5m", 120), t.mid)
            val m15 = ensureLiveCandle(fetchOhlc("15m", 100), t.mid)
            MarketSnapshot(
                price = t.mid,
                bid = t.bid,
                ask = t.ask,
                m1 = m1,
                m5 = m5,
                m15 = m15,
                spread = t.spread
            )
        } catch (_: Exception) {
            val base = if (lastPrice.isNaN()) 2650.0 else lastPrice
            MarketSnapshot(
                price = base,
                bid = base - 0.12,
                ask = base + 0.12,
                m1 = demo(base, 80, 0.12),
                m5 = demo(base, 80, 0.28),
                m15 = demo(base, 60, 0.45),
                spread = 0.30
            )
        }
    }

    /**
 * Candle terakhir selalu sinkron mid live.
 * Mencegah candle M5/M15 "hilang" / tertinggal dari harga 1s.
 */
    private fun ensureLiveCandle(bars: List<Candle>, mid: Double): List<Candle> {
        if (bars.isEmpty()) return demo(mid, 60, 0.2)
        val sorted = sortOldestFirst(bars)
        val last = sorted.last()
        val updated = last.copy(
            close = mid,
            high = maxOf(last.high, mid, last.open),
            low = minOf(last.low, mid, last.open)
        )
        return sorted.dropLast(1) + updated
    }

    private fun sortOldestFirst(list: List<Candle>): List<Candle> {
        if (list.size < 2) return list
        val a = list.first().time
        val b = list.last().time
        return if (a.isNotBlank() && b.isNotBlank() && a > b) list.asReversed() else list
    }

    private fun fetchOhlc(interval: String, limit: Int): List<Candle> {
        val req = Request.Builder()
            .url("$BASE/api/XAUUSD/ohlc?interval=$interval&limit=$limit")
            .header("User-Agent", "XAUUSDScalper/5.8")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val root = JSONObject(body)
            val bars = when {
                root.has("bars") -> root.getJSONArray("bars")
                body.trim().startsWith("[") -> JSONArray(body)
                else -> return emptyList()
            }
            val list = ArrayList<Candle>(bars.length())
            for (i in 0 until bars.length()) {
                val b = bars.getJSONObject(i)
                list.add(
                    Candle(
                        time = b.optString("openTime", b.optString("time", "$i")),
                        open = b.getDouble("open"),
                        high = b.getDouble("high"),
                        low = b.getDouble("low"),
                        close = b.getDouble("close")
                    )
                )
            }
            return sortOldestFirst(list)
        }
    }

    private fun demo(base: Double, n: Int, vol: Double): List<Candle> {
        val list = ArrayList<Candle>(n)
        var p = base
        for (i in 0 until n) {
            val d = (Math.random() - 0.48) * vol
            val o = p
            val c = p + d
            list.add(
                Candle(
                    "D$i", o,
                    maxOf(o, c) + Math.random() * vol * 0.3,
                    minOf(o, c) - Math.random() * vol * 0.3,
                    c
                )
            )
            p = c
        }
        return list
    }
}
