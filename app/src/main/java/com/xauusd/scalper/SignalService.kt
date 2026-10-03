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
        when (intent?.action) {
            "TEST_NOTIF" -> {
                entryNotif("TEST", "Tes dering & getar", null)
                if (AppPrefs.vibeOn(this)) vibrate()
                return START_STICKY
            }
            "COPY_OPEN" -> {
                // Dipanggil dari aksi notifikasi — MainActivity handle lebih baik;
                // di service hanya pastikan status
                return START_STICKY
            }
        }
        startForeground(1, statusNotif("Monitoring HP..."))
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
                            // Auto-salin ke clipboard agar tinggal tempel di MT5 HP
                            val lot = try {
                                RiskHelper.autoLot(applicationContext, sig.entry, sig.sl)
                                    .takeIf { it >= 0.01 } ?: AppPrefs.autoLot(applicationContext)
                            } catch (_: Exception) {
                                AppPrefs.autoLot(applicationContext)
                            }
                            PhoneTradeHelper.copyAll(applicationContext, sig, lot)
                            entryNotif(
                                sig.side,
                                "${sig.entryType} ${"%.2f".format(sig.entry)} · sudah disalin",
                                sig
                            )
                            if (AppPrefs.vibeOn(applicationContext)) vibrate()
                            TelegramHelper.sendSignal(applicationContext, sig, snap.price)
                            if (AppPrefs.autoEntry(applicationContext)) {
                                AutoEntryBridge.send(applicationContext, sig, snap.price)
                            }
                        }
                    }
                    val text = when {
                        sig != null && sig.state.contains("READY") ->
                            "${sig.entryType} READY · clipboard"
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

    private fun entryNotif(side: String, detail: String, sig: SignalResult?) {
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

        // Tap notifikasi → buka MainActivity
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Aksi: buka MT5/Exness
        val openTrade = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply {
                action = "OPEN_TRADE"
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val b = NotificationCompat.Builder(this, ch)
            .setContentTitle("$side ENTRY READY")
            .setContentText(detail)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (sig != null)
                        "${sig.entryType}\nEntry ${"%.2f".format(sig.entry)}\nSL ${"%.2f".format(sig.sl)}\nTP ${"%.2f".format(sig.tp1)}\nSudah di clipboard — ketuk BUKA MT5"
                    else detail
                )
            )
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_share, "BUKA MT5", openTrade)
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
