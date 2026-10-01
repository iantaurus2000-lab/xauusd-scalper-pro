package com.xauusd.scalper

import java.util.Calendar
import java.util.TimeZone

/**
 * Briefing harian (WITA) — tips session + fokus high-impact umum.
 * Bukan feed berita live berbayar; jadwal & risiko session.
 */
object FundamentalTips {

    fun todayBriefing(): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Makassar"))
        val day = cal.get(Calendar.DAY_OF_WEEK)
        val session = SessionHelper.currentSession()

        val dayTip = when (day) {
            Calendar.MONDAY -> "Senin: sering gap/volatilitas buka minggu. Tunggu struktur M5 jelas."
            Calendar.TUESDAY, Calendar.WEDNESDAY -> "Tengah minggu: likuiditas London-NY biasanya lebih sehat untuk scalping."
            Calendar.THURSDAY -> "Kamis: waspadai data US (jobless/GDP/PCE) jika ada di kalender."
            Calendar.FRIDAY -> "Jumat: hati-hati flat/range sore; jangan overtrade menjelang close."
            Calendar.SATURDAY, Calendar.SUNDAY -> "Weekend: pasar tutup / sangat tipis. Jangan entry."
            else -> "Cek kalender ekonomi sebelum entry."
        }

        val sessionTip = when (session) {
            "LONDON+NY OVERLAP" -> "Overlap: volatilitas XAU tinggi. Prioritas sinyal confidence ≥4."
            "LONDON" -> "London: ikuti bias M5. Hindari counter-trend tanpa BOS."
            "NEW YORK" -> "New York: spread bisa melebar saat news US."
            "ASIA/TOKYO" -> "Asia/Tokyo: range sering sempit. Scalp kecil atau skip."
            else -> "Off-session: akurasi turun. Lebih baik WAIT."
        }

        return "FUNDAMENTAL / SESSION (WITA)\n\n$dayTip\n\n$sessionTip\n\n" +
            "High-impact umum XAU: CPI, NFP, FOMC, yield US10Y, DXY.\n" +
            "Saat news merah: jangan pasang limit baru; tunggu 5–15 menit setelah rilis."
    }

    fun tickerExtra(): String {
        val day = Calendar.getInstance(TimeZone.getTimeZone("Asia/Makassar")).get(Calendar.DAY_OF_WEEK)
        return when (day) {
            Calendar.FRIDAY -> " | Jumat: kurangi lot / early close"
            Calendar.MONDAY -> " | Senin: waspada gap"
            else -> " | Cek news sebelum limit"
        }
    }
}
