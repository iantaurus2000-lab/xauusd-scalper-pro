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
    private var timeframe = "M1"
    private var m1: List<Candle> = emptyList()
    private var m5: List<Candle> = emptyList()
    private var m15: List<Candle> = emptyList()
    private var lastPrice = Double.NaN
    private var lastSignal: SignalResult? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i ?: return
            val p = i.getDoubleExtra("price", Double.NaN)
            if (!p.isNaN()) {
                price.text = "%.2f".format(p)
                lastPrice = p
            }
            i.getStringExtra("log")?.let {
                ticker.text = it
                ticker.isSelected = true
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        price = findViewById(R.id.price)
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
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        findViewById<Button>(R.id.tfM1).setOnClickListener { timeframe = "M1"; renderChart() }
        findViewById<Button>(R.id.tfM5).setOnClickListener { timeframe = "M5"; renderChart() }
        findViewById<Button>(R.id.tfM15).setOnClickListener { timeframe = "M15"; renderChart() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startBot() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnResults).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Winrate / Journal")
                .setMessage(ResultsTracker.stats(this))
                .setPositiveButton("Tutup", null)
                .setNeutralButton("Hapus") { _, _ -> ResultsTracker.clear(this) }
                .show()
        }
        findViewById<Button>(R.id.btnFund).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Fundamental / Session")
                .setMessage(FundamentalTips.todayBriefing())
                .setPositiveButton("Tutup", null)
                .show()
        }

        ticker.isSelected = true
        ticker.text = SessionHelper.tickerText() + FundamentalTips.tickerExtra()
        refresh()
        scope.launch {
            while (isActive) {
                delay(12_000)
                refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, IntentFilter(SignalService.ACTION), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, IntentFilter(SignalService.ACTION))
        }
    }

    override fun onPause() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startBot() {
        startForegroundService(Intent(this, SignalService::class.java))
        connection.text = "LIVE"
        connection.setTextColor(Color.rgb(70, 230, 150))
        log.text = "Log: monitor aktif • biquote"
    }

    private fun refresh() {
        scope.launch(Dispatchers.IO) {
            try {
                val s = Market.snapshot(lastPrice)
                val (sig, st) = SignalEngine.evaluate(s)
                withContext(Dispatchers.Main) {
                    m1 = s.m1; m5 = s.m5; m15 = s.m15
                    lastPrice = s.price
                    price.text = "%.2f".format(s.price)
                    spreadLine.text = "Spread %.2f  •  ${SessionHelper.sessionLabel()}".format(s.spread)
                    connection.text = "LIVE"
                    connection.setTextColor(Color.rgb(70, 230, 150))
                    ticker.text = SessionHelper.tickerText() + FundamentalTips.tickerExtra()
                    ticker.isSelected = true
                    applySignal(sig, st)
                    renderChart()
                    log.text = "Log: biquote OK • score ${st["score"]}% • ${st["checks"] ?: ""}"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    connection.text = "ERROR"
                    connection.setTextColor(Color.rgb(255, 90, 90))
                    log.text = "Log: ${e.message}"
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
        m5Bias.text = "M5 ${st["bias"] ?: "-"}"
        m1State.text = "M1 ${st["watch"] ?: "WAIT"}"
        confidence.text = "Score $score%  ${stars(starN.coerceIn(0, 5))}"

        if (sig == null) {
            val w = st["watch"] ?: "WAIT"
            when {
                w.contains("BUY") -> {
                    lamp.text = "🟢"; signalState.text = w
                    signalState.setTextColor(Color.rgb(0, 220, 120))
                }
                w.contains("SELL") -> {
                    lamp.text = "🔴"; signalState.text = w
                    signalState.setTextColor(Color.rgb(255, 80, 80))
                }
                else -> {
                    lamp.text = "⚪"; signalState.text = w
                    signalState.setTextColor(Color.rgb(150, 160, 175))
                }
            }
            signalDetail.text = "RSI ${st["rsi"]} • Spread ${st["spread"]} • Min score 78%"
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
}
