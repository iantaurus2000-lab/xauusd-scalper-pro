package com.xauusd.scalper

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class SignalService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastKey = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(1, statusNotif("Monitoring XAUUSD...", false))
        scope.launch {
            while (isActive) {
                try {
                    val snap = Market.snapshot()
                    val (sig, st) = SignalEngine.evaluate(snap)
                    if (sig != null && sig.confidence >= 4) {
                        val key = "${sig.side}-${"%.2f".format(sig.entry)}-${sig.candleTime}"
                        // Notifikasi HANYA 1 kali per setup unik
                        if (key != lastKey) {
                            lastKey = key
                            val msg = "${sig.entryType} @ ${"%.2f".format(sig.entry)} | SL ${"%.2f".format(sig.sl)} TP1 ${"%.2f".format(sig.tp1)}"
                            entryNotif(sig.side, msg)
                            vibrate()
                        }
                    }
                    val text = if (sig != null) "${sig.entryType} ${sig.state}" else (st["watch"] ?: "WAIT")
                    startForeground(1, statusNotif("$text | ${SessionHelper.currentSession()}", sig != null))
                } catch (e: Exception) {
                    startForeground(1, statusNotif("Error: ${e.message}", false))
                }
                delay(25_000)
            }
        }
        return START_STICKY
    }

    private fun statusNotif(text: String, alert: Boolean): Notification {
        val ch = "xau_status"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(ch, "Status", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, ch)
            .setContentTitle("XAUUSD Scalper v5")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun entryNotif(side: String, detail: String) {
        val ch = "xau_entry"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(ch, "Entry Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    setShowBadge(true)
                }
            )
        }
        nm.notify(
            2,
            NotificationCompat.Builder(this, ch)
                .setContentTitle("$side LIMIT ENTRY")
                .setContentText(detail)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
        )
    }

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java)
                    .defaultVibrator
                    .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
                }
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION = "com.xauusd.scalper.UPDATE"
        const val ACTION_STOP = "com.xauusd.scalper.STOP"
    }
}
