package com.xauusd.scalper

import java.util.Calendar
import java.util.TimeZone

object SessionHelper {
    private fun hourWita(): Int =
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Makassar")).get(Calendar.HOUR_OF_DAY)

    private fun minuteWita(): Int =
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Makassar")).get(Calendar.MINUTE)

    fun currentSession(): String {
        val mins = hourWita() * 60 + minuteWita()
        return when {
            mins in (19 * 60 + 30)..(23 * 60) -> "LONDON+NY OVERLAP"
            mins in (14 * 60)..(23 * 60) -> "LONDON"
            mins in (6 * 60)..(15 * 60) -> "ASIA/TOKYO"
            mins >= (19 * 60 + 30) || mins < (4 * 60) -> "NEW YORK"
            else -> "OFF-SESSION"
        }
    }

    fun isGoodSession(): Boolean {
        val s = currentSession()
        return s == "LONDON" || s == "NEW YORK" || s == "LONDON+NY OVERLAP"
    }

    fun isBestSession(): Boolean = currentSession() == "LONDON+NY OVERLAP"

    fun sessionLabel(): String {
        val h = hourWita()
        val m = "%02d".format(minuteWita())
        return when (currentSession()) {
            "LONDON+NY OVERLAP" -> "OVERLAP London+NY • $h:$m WITA"
            "LONDON" -> "LONDON OPEN • $h:$m WITA"
            "NEW YORK" -> "NEW YORK OPEN • $h:$m WITA"
            "ASIA/TOKYO" -> "ASIA/TOKYO • $h:$m WITA"
            else -> "OFF SESSION • $h:$m WITA"
        }
    }

    fun tickerText(): String {
        val tip = when (currentSession()) {
            "LONDON+NY OVERLAP" -> "Session terbaik scalping XAUUSD"
            "LONDON" -> "London aktif • tunggu M5 + Wick"
            "NEW YORK" -> "NY aktif • jaga spread"
            "ASIA/TOKYO" -> "Asia • range sempit"
            else -> "Off session • hindari entry agresif"
        }
        return "${sessionLabel()} | $tip"
    }
}
