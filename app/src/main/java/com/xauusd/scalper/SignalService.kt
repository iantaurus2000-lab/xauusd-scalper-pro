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

/**
 * Background monitor.
 * Notif SUARA hanya saat ENTRY READY (bukan setiap LIMIT zone).
 */
class SignalService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastReadyKey = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, statusNotif("Monitoring..."))
        scope.launch {
            while (isActive) {
                try {
                    val snap = Market.snapshot()
                    val (sig, st) = SignalEngine.evaluate(snap)
                    // Hanya notif keras saat ENTRY READY
                    if (sig != null && sig.state.contains("READY") && sig.confidence >= 4) {
                        val key = "${sig.side}-${"%.2f".format(sig.entry)}-${sig.candleTime}"
                        if (key != lastReadyKey) {
                            lastReadyKey = key
                            val msg = "${sig.entryType} @ ${"%.2f".format(sig.entry)}"
                            entryNotif(sig.side, msg)
                            vibrate()
                            TelegramHelper.sendSignal(applicationContext, sig, snap.price)
                        }
                    }
                    val text = when {
                        sig != null && sig.state.contains("READY") -> "${sig.entryType} READY"
                        sig != null -> "${sig.entryType} (layar)"
                        else -> st["watch"] ?: SessionHelper.currentSession()
                    }
                    startForeground(1, statusNotif(text))
                } catch (e: Exception) {
                    startForeground(1, statusNotif("Err: ${e.message}"))
                }
                delay(30_000)
            }
        }
        return START_STICKY
    }

    private fun statusNotif(text: String): Notification {
        val ch = "xau_status"
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(ch, "Status", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, ch)
            .setContentTitle("XAUUSD Scalper")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun entryNotif(side: String, detail: String) {
        val ch = "xau_entry"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(ch, "Entry Ready", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                }
            )
        }
        nm.notify(
            2,
            NotificationCompat.Builder(this, ch)
                .setContentTitle("$side ENTRY READY")
                .setContentText(detail)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
                    .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 150, 350), -1))
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(VIBRATOR_SERVICE) as Vibrator).let {
                    if (Build.VERSION.SDK_INT >= 26)
                        it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 150, 350), -1))
                }
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
