package com.xauusd.scalper

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class SignalService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var token = ""
    private var chat = ""
    private var lastSignalKey = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        token = intent?.getStringExtra("token") ?: token
        chat = intent?.getStringExtra("chat") ?: chat
        createChannel()
        startForeground(7, notification("Mengambil data XAUUSD (biquote)..."))
        scope.coroutineContext.cancelChildren()
        scope.launch {
            while (isActive) {
                try {
                    val m1 = Market.candles("1m", 120)
                    val m5 = Market.candles("5m", 120)
                    val m15 = Market.candles("15m", 120)
                    val live = Market.price()
                    val s = MarketSnapshot(live, m1, m5, m15)
                    val (signal, status) = SignalEngine.evaluate(s)
                    sendUpdate(s, signal, status, null, false)
                    if (signal != null) {
                        val key = signal.side + signal.candleTime
                        if (key != lastSignalKey) {
                            val ok = Telegram.send(token, chat, formatSignal(signal)).first
                            sendUpdate(s, signal, status, if (ok) "Telegram: SIGNAL TERKIRIM" else "Telegram: SIGNAL GAGAL", true)
                            if (ok) lastSignalKey = key
                        }
                    }
                } catch (e: Exception) {
                    sendUpdate(null, null, emptyMap(), "DATA ERROR: ${e.message}", false)
                }
                delay(20_000) // slightly faster than before (20s)
            }
        }
        return START_STICKY
    }

    private fun sendUpdate(s: MarketSnapshot?, sig: SignalResult?, st: Map<String, String>, error: String?, alarm: Boolean) {
        val i = Intent(ACTION).setPackage(packageName)
        if (s != null) i.putExtra("price", s.price)
        i.putExtra("bias", st["bias"] ?: "WAITING")
        i.putExtra("wick", st["wick"] ?: "WAITING")
        i.putExtra("sweep", st["sweep"] ?: "WAITING")
        i.putExtra("bos", st["bos"] ?: "WAITING")
        i.putExtra("signal", sig?.let {
            "${it.side} | Entry ${"%.2f".format(it.entry)} | SL ${"%.2f".format(it.sl)} | TP1 ${"%.2f".format(it.tp1)} | TP2 ${"%.2f".format(it.tp2)}"
        } ?: "NO SIGNAL")
        i.putExtra("log", error ?: "Data OK • biquote • M1/M5 diperbarui")
        i.putExtra("alarm", alarm)
        sendBroadcast(i)
    }

    private fun formatSignal(s: SignalResult) =
        "🟢 XAUUSD SCALPING\n\n${s.side}\nEntry: ${"%.2f".format(s.entry)}\nSL: ${"%.2f".format(s.sl)}\nTP1: ${"%.2f".format(s.tp1)}\nTP2: ${"%.2f".format(s.tp2)}\n\nTF: M1\nBias: M5 ${s.side}\nSetup: ${s.setup}\nConfidence: ${s.confidence}/5\nSource: biquote"

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel("xau", "XAUUSD Bot", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(t: String) =
        NotificationCompat.Builder(this, "xau")
            .setContentTitle("XAUUSD Scalping Bot V4.2")
            .setContentText(t)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

    companion object {
        const val ACTION = "com.xauusd.scalper.MARKET_UPDATE"
    }
}
