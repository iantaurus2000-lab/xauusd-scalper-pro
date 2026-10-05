package com.xauusd.scalper

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object PhoneTradeHelper {

    private const val PREF = "trade_app_pref"
    private const val KEY_PKG = "preferred_pkg"

    private val KNOWN = listOf(
        "net.metaquotes.metatrader5",
        "net.metaquotes.metatrader5.group",
        "net.metaquotes.metatrader4",
        "com.exness.android.pa",
        "com.exness.android",
        "com.exness.trading",
        "com.exness.mobile",
        "com.exness.client"
    )

    data class TradeApp(val packageName: String, val label: String)

    fun preferred(ctx: Context): String? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_PKG, null)

    fun setPreferred(ctx: Context, pkg: String) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_PKG, pkg).apply()

    fun clearPreferred(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_PKG).apply()

    fun copyAll(ctx: Context, sig: SignalResult, lot: Double): String {
        val head = if (sig.side == "BUY") "🟢 XAUUSD SCALPING" else "🔴 XAUUSD SCALPING"
        val text = buildString {
            appendLine(head)
            appendLine(sig.entryType)
            appendLine("Entry: ${"%.2f".format(sig.entry)}")
            appendLine("SL: ${"%.2f".format(sig.sl)}")
            appendLine("TP1: ${"%.2f".format(sig.tp1)}")
            appendLine("TP2: ${"%.2f".format(sig.tp2)}")
            appendLine("Lot: ${"%.2f".format(lot)}")
            appendLine("★★★★★".take(sig.confidence) + "☆☆☆☆☆".take(5 - sig.confidence))
        }.trim()
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ORDER", text))
        } catch (_: Exception) {
        }
        return text
    }

    fun findInstalled(ctx: Context): List<TradeApp> {
        val pm = ctx.packageManager
        val out = ArrayList<TradeApp>()
        for (pkg in KNOWN) {
            try {
                pm.getPackageInfo(pkg, 0)
                val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
                if (launch.resolveActivity(pm) == null) continue
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) {
                    pkg.substringAfterLast('.')
                }
                out.add(TradeApp(pkg, label))
            } catch (_: Exception) {
            }
        }
        return out
    }

    fun appLabel(ctx: Context): String {
        val list = findInstalled(ctx)
        val pref = preferred(ctx)
        if (pref != null) list.firstOrNull { it.packageName == pref }?.let { return it.label }
        return when {
            list.isEmpty() -> "Install MT5"
            list.size == 1 -> list[0].label
            else -> "${list.size} app"
        }
    }

    private fun launchPkg(ctx: Context, pkg: String): Boolean {
        return try {
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(ctx, "Gagal: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun openTradingApp(ctx: Context, forcePick: Boolean = false): Boolean {
        return try {
            val list = findInstalled(ctx)
            val pref = preferred(ctx)
            if (!forcePick && pref != null && list.any { it.packageName == pref }) {
                return launchPkg(ctx, pref)
            }
            if (list.isEmpty()) {
                showInstall(ctx)
                return false
            }
            if (!forcePick && list.size == 1) {
                setPreferred(ctx, list[0].packageName)
                return launchPkg(ctx, list[0].packageName)
            }
            if (ctx is Activity && !ctx.isFinishing) {
                val labels = list.map { it.label }.toTypedArray()
                AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Pilih app")
                    .setItems(labels) { _, i ->
                        setPreferred(ctx, list[i].packageName)
                        launchPkg(ctx, list[i].packageName)
                    }
                    .setNeutralButton("Play Store") { _, _ -> openStore(ctx) }
                    .setNegativeButton("Batal", null)
                    .show()
                return true
            }
            setPreferred(ctx, list[0].packageName)
            launchPkg(ctx, list[0].packageName)
        } catch (e: Exception) {
            Toast.makeText(ctx, "${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun openStore(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=net.metaquotes.metatrader5"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=net.metaquotes.metatrader5"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun showInstall(ctx: Context) {
        if (ctx is Activity && !ctx.isFinishing) {
            try {
                AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("MT5 belum terpasang")
                    .setMessage("Install MetaTrader 5 dari Play Store.")
                    .setPositiveButton("Play Store") { _, _ -> openStore(ctx) }
                    .setNegativeButton("OK", null)
                    .show()
            } catch (_: Exception) {
                openStore(ctx)
            }
        } else openStore(ctx)
    }
}
