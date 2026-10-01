package com.xauusd.scalper

import android.content.Context
import kotlin.math.abs

object RiskHelper {
    private const val PREF = "risk_cfg"

    fun save(ctx: Context, balance: Double, riskPct: Double, maxDailyLossPct: Double, maxPos: Int) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putFloat("balance", balance.toFloat())
            .putFloat("risk", riskPct.toFloat())
            .putFloat("daily", maxDailyLossPct.toFloat())
            .putInt("maxpos", maxPos)
            .apply()
    }

    fun balance(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getFloat("balance", 1000f).toDouble()
    fun riskPct(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getFloat("risk", 1f).toDouble()
    fun dailyLossPct(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getFloat("daily", 3f).toDouble()
    fun maxPos(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("maxpos", 1)

    /** Lot XAU approx: risk$ / (SL distance * 100) for standard gold contract sizing simplified */
    fun autoLot(ctx: Context, entry: Double, sl: Double): Double {
        val riskMoney = balance(ctx) * (riskPct(ctx) / 100.0)
        val dist = abs(entry - sl).coerceAtLeast(0.10)
        val lot = riskMoney / (dist * 100.0)
        return (lot * 100).toInt().coerceIn(1, 500) / 100.0 // 0.01 step, max 5.00
    }

    fun summary(ctx: Context, entry: Double, sl: Double, tp1: Double): String {
        val lot = autoLot(ctx, entry, sl)
        val rr = abs(tp1 - entry) / abs(entry - sl).coerceAtLeast(0.01)
        return buildString {
            appendLine("RISK MANAGEMENT")
            appendLine("Balance: ${"%.2f".format(balance(ctx))}")
            appendLine("Risk/trade: ${riskPct(ctx)}%")
            appendLine("Max daily loss: ${dailyLossPct(ctx)}%")
            appendLine("Max positions: ${maxPos(ctx)}")
            appendLine("Auto lot: ${"%.2f".format(lot)}")
            appendLine("RR ke TP1: ${"%.2f".format(rr)}")
        }
    }
}
