package com.xauusd.scalper

import android.content.Context

/** Kontrol menu: indikator + notifikasi */
object AppPrefs {
    private const val P = "app_prefs"

    fun soundOn(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getBoolean("sound", true)
    fun vibeOn(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getBoolean("vibe", true)
    fun showEma(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getBoolean("ema", true)
    fun showSr(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getBoolean("sr", true)
    fun showFib(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getBoolean("fib", true)
    fun minScore(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getInt("minscore", 72)
    fun scanSec(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getInt("scansec", 18)

    fun setSound(ctx: Context, v: Boolean) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putBoolean("sound", v).apply()
    fun setVibe(ctx: Context, v: Boolean) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putBoolean("vibe", v).apply()
    fun setEma(ctx: Context, v: Boolean) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putBoolean("ema", v).apply()
    fun setSr(ctx: Context, v: Boolean) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putBoolean("sr", v).apply()
    fun setFib(ctx: Context, v: Boolean) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putBoolean("fib", v).apply()
    fun setMinScore(ctx: Context, v: Int) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putInt("minscore", v.coerceIn(60, 90)).apply()
    fun setScanSec(ctx: Context, v: Int) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putInt("scansec", v.coerceIn(12, 60)).apply()
}
