package com.xauusd.scalper

import android.content.Context

/**
 * Jembatan auto entry APK → MT5 (Exness).
 *
 * FAKTA TEKNIS:
 * - MT5 Mobile Android TIDAK punya API resmi untuk order dari APK pihak ketiga.
 * - Order otomatis resmi hanya lewat terminal MT5 desktop/VPS + MQL5 EA.
 *
 * SOLUSI GRATIS yang dipakai:
 * 1) APK (saat ENTRY READY + Auto Entry ON) mengirim paket order terstruktur ke Telegram.
 * 2) EA gratis `XAUUSD_AutoLimit_EA.mq5` di MT5 (PC atau VPS gratis) membaca bot Telegram
 *    dan menempatkan BUY LIMIT / SELL LIMIT di akun Exness yang login di terminal itu.
 *
 * Format paket (wajib dipatuhi EA):
 * #XAUUSD BUY_LIMIT
 * ENTRY:4153.72
 * SL:4149.20
 * TP:4157.95
 * LOT:0.01
 * SCORE:84
 * ID:uniquekey
 */
object AutoEntryBridge {

    fun buildPacket(sig: SignalResult, lot: Double, price: Double): String {
        val type = when {
            sig.side == "BUY" -> "BUY_LIMIT"
            else -> "SELL_LIMIT"
        }
        val id = "${sig.side}-${"%.2f".format(sig.entry)}-${sig.candleTime}"
            .replace(" ", "_")
            .replace(":", "")
        return buildString {
            appendLine("#XAUUSD $type")
            appendLine("ENTRY:${"%.2f".format(sig.entry)}")
            appendLine("SL:${"%.2f".format(sig.sl)}")
            appendLine("TP:${"%.2f".format(sig.tp1)}")
            appendLine("LOT:${"%.2f".format(lot.coerceIn(0.01, 5.0))}")
            appendLine("SCORE:${sig.confidence * 20}")
            appendLine("PRICE:${"%.2f".format(price)}")
            appendLine("STATE:${sig.state}")
            appendLine("ID:$id")
            appendLine("SRC:XAUUSD_SCALPER_APK")
        }.trim()
    }

    /**
     * Kirim auto entry. Return true jika Telegram sukses.
     * Lot: pakai RiskHelper.autoLot jika balance diset, else AppPrefs.autoLot.
     */
    fun send(ctx: Context, sig: SignalResult, price: Double): Boolean {
        if (!AppPrefs.autoEntry(ctx)) return false
        if (!TelegramHelper.isConfigured(ctx)) return false
        if (!sig.state.contains("READY")) return false

        val lot = try {
            val calc = RiskHelper.autoLot(ctx, sig.entry, sig.sl)
            if (calc >= 0.01) calc else AppPrefs.autoLot(ctx)
        } catch (_: Exception) {
            AppPrefs.autoLot(ctx)
        }

        val packet = buildPacket(sig, lot, price)
        return TelegramHelper.send(
            TelegramHelper.token(ctx),
            TelegramHelper.chatId(ctx),
            packet
        )
    }
}
