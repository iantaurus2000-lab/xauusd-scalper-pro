package com.xauusd.scalper

import android.content.Context

data class TradeResult(
    val side: String,
    val entry: Double,
    val sl: Double,
    val tp1: Double,
    val result: String,
    val time: String
)

object ResultsTracker {
    private const val PREF = "results_tracker"
    private const val KEY = "trades"

    fun add(ctx: Context, t: TradeResult) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val old = p.getString(KEY, "") ?: ""
        val line = "${t.side}|${t.entry}|${t.sl}|${t.tp1}|${t.result}|${t.time}"
        p.edit().putString(KEY, (line + "\n" + old).take(8000)).apply()
    }

    fun stats(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = p.getString(KEY, "") ?: ""
        val list = raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val x = line.split("|")
            if (x.size < 6) null
            else TradeResult(x[0], x[1].toDoubleOrNull() ?: 0.0, x[2].toDoubleOrNull() ?: 0.0,
                x[3].toDoubleOrNull() ?: 0.0, x[4], x[5])
        }
        if (list.isEmpty()) return "Belum ada hasil entry."
        val closed = list.filter { it.result == "WIN" || it.result == "LOSS" }
        val wins = closed.count { it.result == "WIN" }
        val losses = closed.count { it.result == "LOSS" }
        val total = wins + losses
        val wr = if (total > 0) wins * 100.0 / total else 0.0
        return buildString {
            append("HASIL ENTRY\n\n")
            append("Total: ${list.size}\nClosed: $total\nWin: $wins\nLoss: $losses\n")
            append("Winrate: %.1f%%\n\n".format(wr))
            list.take(5).forEach {
                append("${it.side} ${it.result} E:${"%.2f".format(it.entry)} ${it.time}\n")
            }
        }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
