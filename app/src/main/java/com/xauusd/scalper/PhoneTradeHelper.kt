package com.xauusd.scalper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast

/**
 * Alur TRADING HANYA HP (tanpa PC/VPS).
 *
 * Fakta:
 * - MT5 / Exness Mobile tidak membuka API order ke APK lain.
 * - Order otomatis penuh ke MT5 HP secara resmi = tidak tersedia.
 *
 * Solusi paling cepat di HP:
 * 1) Saat ENTRY READY → salin SEMUA field sekali ketuk
 * 2) Buka app MT5 atau Exness yang terpasang
 * 3) Tempel / isi pending BUY LIMIT / SELL LIMIT manual (3–5 detik)
 */
object PhoneTradeHelper {

    /** Package kandidat app broker/terminal di Android */
    private val CANDIDATES = listOf(
        "net.metaquotes.metatrader5",      // MT5 resmi
        "net.metaquotes.metatrader4",      // MT4
        "com.exness.android.pa",           // Exness (varian)
        "com.exness.android",              // Exness alternatif
        "com.exness.trading",              // kemungkinan lain
        "net.metaquotes.metatrader5.group" // build broker
    )

    fun copyAll(ctx: Context, sig: SignalResult, lot: Double): String {
        val text = buildString {
            appendLine(sig.entryType)
            appendLine("Symbol: XAUUSD")
            appendLine("Entry: ${"%.2f".format(sig.entry)}")
            appendLine("SL: ${"%.2f".format(sig.sl)}")
            appendLine("TP: ${"%.2f".format(sig.tp1)}")
            appendLine("Lot: ${"%.2f".format(lot)}")
            appendLine("Score: ${sig.confidence}/5")
        }.trim()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("XAUUSD_ORDER", text))
        return text
    }

    fun copyEntryOnly(ctx: Context, price: Double) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ENTRY", "%.2f".format(price)))
    }

    /** Cari app trading terpasang */
    fun findTradingApp(ctx: Context): String? {
        val pm = ctx.packageManager
        for (pkg in CANDIDATES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return null
    }

    /** Buka MT5 / Exness; true jika sukses */
    fun openTradingApp(ctx: Context): Boolean {
        val pkg = findTradingApp(ctx)
        if (pkg != null) {
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(launch)
                return true
            }
        }
        // Fallback: buka Play Store cari MT5
        try {
            val market = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://details?id=net.metaquotes.metatrader5")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(market)
        } catch (_: Exception) {
            try {
                val web = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=net.metaquotes.metatrader5")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(web)
            } catch (_: Exception) {
                Toast.makeText(ctx, "Install MT5 atau Exness dulu", Toast.LENGTH_LONG).show()
                return false
            }
        }
        return false
    }

    /** Salin semua + buka app trading */
    fun copyAndOpen(ctx: Context, sig: SignalResult, lot: Double): Boolean {
        copyAll(ctx, sig, lot)
        Toast.makeText(ctx, "Order disalin — buka app trading", Toast.LENGTH_SHORT).show()
        return openTradingApp(ctx)
    }

    fun appLabel(ctx: Context): String {
        val pkg = findTradingApp(ctx) ?: return "Belum install MT5/Exness"
        return when {
            pkg.contains("exness") -> "Exness terpasang"
            pkg.contains("metatrader5") -> "MT5 terpasang"
            pkg.contains("metatrader4") -> "MT4 terpasang"
            else -> pkg
        }
    }
}
