package com.xauusd.scalper

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast

/**
 * Buka app trading di HP.
 * Banyak build MT5/Exness punya package name berbeda → scan + pilih manual.
 */
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
        "com.exness.client",
        "net.metaquotes.metatrader5.exness",
        "net.metaquotes.metatrader4.exness"
    )

    private val KEYWORDS = listOf(
        "metatrader", "metaquotes", "exness", "mt5", "mt4",
        "xauusd", "forex", "trading"
    )

    data class TradeApp(val packageName: String, val label: String)

    fun preferred(ctx: Context): String? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_PKG, null)

    fun setPreferred(ctx: Context, pkg: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_PKG, pkg).apply()
    }

    fun clearPreferred(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_PKG).apply()
    }

    fun copyAll(ctx: Context, sig: SignalResult, lot: Double): String {
        val text = buildString {
            appendLine(sig.entryType)
            appendLine("Symbol: XAUUSD")
            appendLine("Entry: ${"%.2f".format(sig.entry)}")
            appendLine("SL: ${"%.2f".format(sig.sl)}")
            appendLine("TP: ${"%.2f".format(sig.tp1)}")
            appendLine("Lot: ${"%.2f".format(lot)}")
        }.trim()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("XAUUSD_ORDER", text))
        return text
    }

    /** Scan semua app terpasang yang mirip trading */
    fun findInstalled(ctx: Context): List<TradeApp> {
        val pm = ctx.packageManager
        val found = LinkedHashMap<String, TradeApp>()

        // 1) daftar dikenal
        for (pkg in KNOWN) {
            try {
                pm.getPackageInfo(pkg, 0)
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) {
                    pkg
                }
                found[pkg] = TradeApp(pkg, label)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }

        // 2) scan semua package (keyword)
        try {
            @Suppress("DEPRECATION")
            val apps: List<ApplicationInfo> = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (ai in apps) {
                val pkg = ai.packageName.lowercase()
                val label = try {
                    pm.getApplicationLabel(ai).toString()
                } catch (_: Exception) {
                    ai.packageName
                }
                val hay = (pkg + " " + label.lowercase())
                if (KEYWORDS.any { hay.contains(it) }) {
                    // harus bisa diluncurkan
                    if (pm.getLaunchIntentForPackage(ai.packageName) != null) {
                        found[ai.packageName] = TradeApp(ai.packageName, label)
                    }
                }
            }
        } catch (_: Exception) {
        }

        return found.values.toList().sortedBy { it.label.lowercase() }
    }

    fun appLabel(ctx: Context): String {
        val pref = preferred(ctx)
        if (pref != null) {
            val hit = findInstalled(ctx).firstOrNull { it.packageName == pref }
            if (hit != null) return hit.label
        }
        val list = findInstalled(ctx)
        return when {
            list.isEmpty() -> "Belum ketemu MT5/Exness"
            list.size == 1 -> list[0].label
            else -> "${list.size} app trading"
        }
    }

    private fun launchPkg(ctx: Context, pkg: String): Boolean {
        return try {
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(launch)
                true
            } else false
        } catch (e: Exception) {
            Toast.makeText(ctx, "Gagal buka: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    /** Buka app: preferensi → satu app → dialog pilih → Play Store */
    fun openTradingApp(ctx: Context, forcePick: Boolean = false): Boolean {
        val list = findInstalled(ctx)
        val pref = preferred(ctx)

        if (!forcePick && pref != null && list.any { it.packageName == pref }) {
            return launchPkg(ctx, pref)
        }

        if (list.isEmpty()) {
            showInstallHelp(ctx)
            return false
        }

        if (!forcePick && list.size == 1) {
            setPreferred(ctx, list[0].packageName)
            return launchPkg(ctx, list[0].packageName)
        }

        // Dialog pilih
        if (ctx is android.app.Activity) {
            val labels = list.map { it.label }.toTypedArray()
            AlertDialog.Builder(ctx)
                .setTitle("Pilih app trading")
                .setItems(labels) { _, which ->
                    val app = list[which]
                    setPreferred(ctx, app.packageName)
                    launchPkg(ctx, app.packageName)
                }
                .setNeutralButton("Play Store MT5") { _, _ -> openPlayStore(ctx) }
                .setNegativeButton("Batal", null)
                .show()
            return true
        }

        // Non-activity: buka yang pertama
        setPreferred(ctx, list[0].packageName)
        return launchPkg(ctx, list[0].packageName)
    }

    fun copyAndOpen(ctx: Context, sig: SignalResult, lot: Double): Boolean {
        copyAll(ctx, sig, lot)
        Toast.makeText(ctx, "Order disalin", Toast.LENGTH_SHORT).show()
        return openTradingApp(ctx)
    }

    private fun openPlayStore(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=net.metaquotes.metatrader5")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            try {
                ctx.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=net.metaquotes.metatrader5")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                Toast.makeText(ctx, "Install MT5 dari Play Store", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showInstallHelp(ctx: Context) {
        if (ctx is android.app.Activity) {
            AlertDialog.Builder(ctx)
                .setTitle("MT5 / Exness belum terdeteksi")
                .setMessage(
                    "Pastikan MT5 atau aplikasi Exness sudah terpasang.\n\n" +
                        "Jika sudah terpasang tapi tetap gagal:\n" +
                        "• Menu → Pilih app trading manual\n" +
                        "• Atau install ulang MT5 dari Play Store\n\n" +
                        "Setelah install, tekan lagi BUKA MT5."
                )
                .setPositiveButton("Play Store MT5") { _, _ -> openPlayStore(ctx) }
                .setNegativeButton("Tutup", null)
                .show()
        } else {
            Toast.makeText(ctx, "Install MT5/Exness dulu", Toast.LENGTH_LONG).show()
            openPlayStore(ctx)
        }
    }
}
