package com.xauusd.scalper

import android.app.*
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class SignalService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastReadyKey = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "TEST_NOTIF") {
            entryNotif("TEST", "Tes dering & getar")
            if (AppPrefs.vibeOn(this)) vibrate()
            return START_STICKY
        }
        startForeground(1, statusNotif("Monitoring..."))
        scope.launch {
            while (isActive) {
                val delayMs = AppPrefs.scanSec(applicationContext) * 1000L
                try {
                    val snap = Market.snapshot()
                    val minSc = AppPrefs.minScore(applicationContext)
                    val (sig, st) = SignalEngine.evaluate(snap, minSc)
                    if (sig != null && sig.state.contains("READY") && sig.confidence >= 3) {
                        val key = "${sig.side}-${"%.2f".format(sig.entry)}-${sig.candleTime}"
                        if (key != lastReadyKey) {
                            lastReadyKey = key
                            entryNotif(sig.side, "${sig.entryType} @ ${"%.2f".format(sig.entry)}")
                            if (AppPrefs.vibeOn(applicationContext)) vibrate()
                            // Notif biasa + Telegram ringkas
                            TelegramHelper.sendSignal(applicationContext, sig, snap.price)
                            // AUTO ENTRY → paket order untuk EA MT5
                            if (AppPrefs.autoEntry(applicationContext)) {
                                val ok = AutoEntryBridge.send(applicationContext, sig, snap.price)
                                startForeground(
                                    1,
                                    statusNotif(
                                        if (ok) "AUTO ${sig.entryType} terkirim EA"
                                        else "AUTO gagal (cek Telegram)"
                                    )
                                )
                            }
                        }
                    }
                    val text = when {
                        sig != null && sig.state.contains("READY") -> {
                            val auto = if (AppPrefs.autoEntry(applicationContext)) " · AUTO" else ""
                            "${sig.entryType} READY$auto"
                        }
                        sig != null -> "${sig.entryType} (layar)"
                        else -> st["watch"] ?: "WAIT"
                    }
                    startForeground(1, statusNotif(text))
                } catch (e: Exception) {
                    startForeground(1, statusNotif("Err: ${e.message}"))
                }
                delay(delayMs)
            }
        }
        return START_STICKY
    }

    private fun statusNotif(text: String): Notification {
        val ch = "xau_status"
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(ch, "Status", NotificationManager.IMPORTANCE_LOW)
                )
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
                    enableLights(true)
                }
            )
        }
        val b = NotificationCompat.Builder(this, ch)
            .setContentTitle("$side ENTRY READY")
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
        if (AppPrefs.soundOn(this)) {
            b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        } else {
            b.setSilent(true)
        }
        nm.notify(2, b.build())
    }

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
                    .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 150, 400), -1))
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(VIBRATOR_SERVICE) as Vibrator).let {
                    if (Build.VERSION.SDK_INT >= 26) {
                        it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 150, 400), -1))
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
