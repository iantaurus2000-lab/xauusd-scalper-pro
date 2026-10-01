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
        startForeground(1, buildNotification("Monitoring XAUUSD...", false))
        scope.launch {
            while (isActive) {
                try {
                    val snap = Market.snapshot()
                    val (sig, st) = SignalEngine.evaluate(snap)
                    val watch = st["watch"] ?: "WAIT"
                    if (sig != null && sig.confidence >= 4) {
                        val key = "${sig.side}-${sig.entry}-${sig.candleTime}"
                        if (key != lastKey) {
                            lastKey = key
                            val msg = "${sig.entryType} @ ${"%.2f".format(sig.entry)} | SL ${"%.2f".format(sig.sl)} TP1 ${"%.2f".format(sig.tp1)}"
                            notifyEntry(sig.side, msg)
                            vibrate()
                        }
                    }
                    val status = if (sig != null) "${sig.entryType} ${sig.state}" else watch
                    startForeground(1, buildNotification("$status | ${SessionHelper.sessionLabel()}", sig != null))
                    sendBroadcast(Intent(ACTION).apply {
                        putExtra("price", snap.price)
                        putExtra("bias", st["bias"] ?: "")
                        putExtra("signal", if (sig != null) "${sig.entryType} ${sig.state}" else watch)
                        putExtra("log", SessionHelper.tickerText())
                    })
                } catch (e: Exception) {
                    startForeground(1, buildNotification("Error: ${e.message}", false))
                }
                delay(20_000)
            }
        }
        return START_STICKY
    }

    private fun buildNotification(text: String, alert: Boolean): Notification {
        val chId = "xau_signal"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(chId, "XAU Signals", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        return NotificationCompat.Builder(this, chId)
            .setContentTitle("XAUUSD Scalper Pro")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(if (alert) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notifyEntry(side: String, detail: String) {
        val chId = "xau_entry"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(chId, "Entry Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                }
            )
        }
        val n = NotificationCompat.Builder(this, chId)
            .setContentTitle("$side LIMIT ENTRY")
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .build()
        nm.notify(2, n)
    }

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = getSystemService(VibratorManager::class.java)
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
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
    }
}
