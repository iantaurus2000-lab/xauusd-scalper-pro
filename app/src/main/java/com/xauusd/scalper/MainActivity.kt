package com.xauusd.scalper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.*

class MainActivity : Activity() {
    private lateinit var price: TextView
    private lateinit var priceChange: TextView
    private lateinit var connection: TextView
    private lateinit var lamp: TextView
    private lateinit var signalState: TextView
    private lateinit var signalDetail: TextView
    private lateinit var confidence: TextView
    private lateinit var m5Bias: TextView
    private lateinit var m1State: TextView
    private lateinit var ticker: TextView
    private lateinit var log: TextView
    private lateinit var spreadLine: TextView
    private lateinit var boxEntry: TextView
    private lateinit var boxSl: TextView
    private lateinit var boxTp1: TextView
    private lateinit var boxTp2: TextView
    private lateinit var chart: CandleChartView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickJob: Job? = null
    private var scanJob: Job? = null
    private var running = false

    private var timeframe = "M1"
    private var m1: List<Candle> = emptyList()
    private var m5: List<Candle> = emptyList()
    private var m15: List<Candle> = emptyList()
    private var lastPrice = Double.NaN
    private var lastSignal: SignalResult? = null
    private var lastNotifiedKey = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)

        price = findViewById(R.id.price)
        priceChange = findViewById(R.id.priceChange)
        connection = findViewById(R.id.connection)
        lamp = findViewById(R.id.lamp)
        signalState = findViewById(R.id.signalState)
        signalDetail = findViewById(R.id.signalDetail)
        confidence = findViewById(R.id.confidence)
        m5Bias = findViewById(R.id.m5Bias)
        m1State = findViewById(R.id.m1State)
        ticker = findViewById(R.id.ticker)
        log = findViewById(R.id.log)
        spreadLine = findViewById(R.id.spreadLine)
        boxEntry = findViewById(R.id.boxEntry)
        boxSl = findViewById(R.id.boxSl)
        boxTp1 = findViewById(R.id.boxTp1)
        boxTp2 = findViewById(R.id.boxTp2)
        chart = findViewById(R.id.chart)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        findViewById<Button>(R.id.tfM1).setOnClickListener { timeframe = "M1"; renderChart() }
        findViewById<Button>(R.id.tfM5).setOnClickListener { timeframe = "M5"; renderChart() }
        findViewById<Button>(R.id.tfM15).setOnClickListener { timeframe = "M15"; renderChart() }

        findViewById<Button>(R.id.btnStart).setOnClickListener { startAll() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopAll() }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { showMenu() }

        ticker.isSelected = true
        ticker.text = SessionHelper.tickerText()

        // Harga 1 detik selalu (tanpa START juga)
        startPriceLoop()
        // Scan sinyal pertama
        fullScan()
    }

    private fun showMenu() {
        val items = arrayOf(
            "Scan sinyal sekarang",
            "Winrate / Journal",
            "Fundamental / News",
            "Stop monitor",
            "Keluar aplikasi"
        )
        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> fullScan()
                    1 -> AlertDialog.Builder(this)
                        .setTitle("Winrate")
                        .setMessage(ResultsTracker.stats(this))
                        .setPositiveButton("Tutup", null)
                        .setNeutralButton("Hapus") { _, _ -> ResultsTracker.clear(this) }
                        .show()
                    2 -> AlertDialog.Builder(this)
                        .setTitle("Fundamental")
                        .setMessage(FundamentalTips.todayBriefing())
                        .setPositiveButton("Tutup", null)
                        .show()
                    3 -> stopAll()
                    4 -> exitApp()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun startAll() {
        running = true
        startForegroundService(Intent(this, SignalService::class.java))
        connection.text = "● LIVE 1s"
        connection.setTextColor(Color.rgb(0, 230, 118))
        log.text = "Log: START • monitor + notifikasi aktif"
        fullScan()
    }

    private fun stopAll() {
        running = false
        try {
            stopService(Intent(this, SignalService::class.java))
        } catch (_: Exception) {}
        connection.text = "● STOPPED"
        connection.setTextColor(Color.rgb(255, 152, 0))
        log.text = "Log: STOP • service dimatikan"
    }

    private fun exitApp() {
        stopAll()
        tickJob?.cancel()
        scanJob?.cancel()
        scope.cancel()
        finishAffinity()
        // Pastikan proses keluar
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun startPriceLoop() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                try {
                    val t = withContext(Dispatchers.IO) { Market.tick() }
                    val prev = lastPrice
                    lastPrice = t.mid
                    price.text = "%.2f".format(t.mid)
                    val ch = if (prev.isNaN()) 0.0 else t.mid - prev
                    priceChange.text = (if (ch >= 0) "+" else "") + "%.2f".format(ch)
                    priceChange.setTextColor(
                        if (ch >= 0) Color.rgb(0, 230, 118) else Color.rgb(239, 83, 80)
                    )
                    spreadLine.text = "Spread %.2f".format(t.spread)
                    connection.text = "● LIVE 1s"
                    connection.setTextColor(Color.rgb(0, 230, 118))
                    // Update last candle close visual
                    if (m1.isNotEmpty()) {
                        val last = m1.last()
                        m1 = m1.dropLast(1) + last.copy(close = t.mid, high = maxOf(last.high, t.mid), low = minOf(last.low, t.mid))
                        renderChart()
                    }
                } catch (e: Exception) {
                    connection.text = "● OFFLINE"
                    connection.setTextColor(Color.rgb(255, 90, 90))
                    log.text = "Log: tick ${e.message}"
                }
                delay(1000)
            }
        }
    }

    private fun fullScan() {
        scanJob?.cancel()
        scanJob = scope.launch(Dispatchers.IO) {
            try {
                val s = Market.snapshot(lastPrice)
                val (sig, st) = SignalEngine.evaluate(s)
                withContext(Dispatchers.Main) {
                    m1 = s.m1; m5 = s.m5; m15 = s.m15
                    lastPrice = s.price
                    price.text = "%.2f".format(s.price)
                    spreadLine.text = "Spread %.2f  •  ${SessionHelper.sessionLabel()}".format(s.spread)
                    ticker.text = SessionHelper.tickerText() + FundamentalTips.tickerExtra()
                    ticker.isSelected = true
                    applySignal(sig, st)
                    renderChart()
                    log.text = "Log: scan OK • score ${st["score"]}%"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    log.text = "Log: scan ${e.message}"
                }
            }
        }
    }

    private fun stars(n: Int): String = buildString {
        for (i in 1..5) append(if (i <= n) "★" else "☆")
    }

    private fun applySignal(sig: SignalResult?, st: Map<String, String>) {
        lastSignal = sig
        val score = st["score"]?.toIntOrNull() ?: 0
        val starN = st["stars"]?.toIntOrNull() ?: (score / 20)
        m5Bias.text = "● M5 ${st["bias"] ?: "-"}"
        m1State.text = "● M1 ${st["watch"] ?: "WAIT"}"
        confidence.text = "SIGNAL STRENGTH  ${stars(starN.coerceIn(0, 5))}  ($score%)"

        if (sig == null) {
            val w = st["watch"] ?: "WAIT"
            when {
                w.contains("BUY") -> {
                    lamp.text = "🟢"
                    signalState.text = w
                    signalState.setTextColor(Color.rgb(0, 230, 118))
                }
                w.contains("SELL") -> {
                    lamp.text = "🔴"
                    signalState.text = w
                    signalState.setTextColor(Color.rgb(239, 83, 80))
                }
                else -> {
                    lamp.text = "⚪"
                    signalState.text = w
                    signalState.setTextColor(Color.rgb(176, 190, 197))
                }
            }
            signalDetail.text = "RSI ${st["rsi"]} • Spread ${st["spread"]} • Min 78%"
            boxEntry.text = "Entry\n--"
            boxSl.text = "SL\n--"
            boxTp1.text = "TP1\n--"
            boxTp2.text = "TP2\n--"
        } else {
            lamp.text = if (sig.side == "BUY") "🟢" else "🔴"
            signalState.text = "${sig.entryType}\n${sig.state}"
            signalState.setTextColor(
                if (sig.side == "BUY") Color.rgb(0, 255, 130) else Color.rgb(255, 70, 80)
            )
            signalDetail.text = sig.reason
            boxEntry.text = "Entry\n${"%.2f".format(sig.entry)}"
            boxSl.text = "SL\n${"%.2f".format(sig.sl)}"
            boxTp1.text = "TP1\n${"%.2f".format(sig.tp1)}"
            boxTp2.text = "TP2\n${"%.2f".format(sig.tp2)}"

            // Notif hanya 1x per sinyal unik
            val key = "${sig.side}-${"%.2f".format(sig.entry)}-${sig.candleTime}"
            if (key != lastNotifiedKey && running) {
                lastNotifiedKey = key
            }
        }
    }

    private fun renderChart() {
        val data = when (timeframe) {
            "M5" -> m5
            "M15" -> m15
            else -> m1
        }
        chart.setLayers(true, true)
        chart.setData(data, timeframe, if (lastPrice.isNaN()) null else lastPrice, lastSignal)
    }

    override fun onDestroy() {
        tickJob?.cancel()
        scanJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
