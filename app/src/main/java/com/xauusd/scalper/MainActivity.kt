package com.xauusd.scalper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
    private lateinit var highLow: TextView
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
    private var lastBid = Double.NaN
    private var lastAsk = Double.NaN
    private var sessionHigh = Double.NaN
    private var sessionLow = Double.NaN
    private var lastSignal: SignalResult? = null
    private val activityLog = StringBuilder()

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
        highLow = findViewById(R.id.highLow)
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
        findViewById<Button>(R.id.btnStart).setOnClickListener { startAll() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopAll() }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { showMenu() }

        ticker.isSelected = true
        startPriceLoop()
        fullScan()
        addLog("App start v5.5")
    }

    private fun addLog(msg: String) {
        activityLog.insert(0, "$msg\n")
        if (activityLog.length > 3000) activityLog.setLength(3000)
        log.text = "Log: $msg"
    }

    private fun showMenu() {
        val items = arrayOf(
            // MARKET
            "📊 MARKET / QUOTES",
            "📈 CHART & INDICATORS",
            "🎯 SIGNAL CENTER",
            // CONFIG
            "⚙️ SETTINGS",
            "📱 TELEGRAM",
            "🔑 BIQUOTE MARKET DATA",
            "🧠 STRATEGY",
            "💰 RISK MANAGEMENT",
            // TOOLS
            "📋 SIGNAL HISTORY",
            "📜 LOG",
            "🔔 NOTIFICATION & ALARM",
            "■ STOP MONITOR",
            "✕ KELUAR APLIKASI"
        )
        AlertDialog.Builder(this)
            .setTitle("MENU")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showQuotes()
                    1 -> showChartHelp()
                    2 -> fullScan()
                    3 -> showSettings()
                    4 -> showTelegramDialog()
                    5 -> showBiquoteInfo()
                    6 -> showStrategy()
                    7 -> showRisk()
                    8 -> AlertDialog.Builder(this).setTitle("History")
                        .setMessage(ResultsTracker.stats(this)).setPositiveButton("OK", null).show()
                    9 -> AlertDialog.Builder(this).setTitle("LOG")
                        .setMessage(if (activityLog.isEmpty()) "Kosong" else activityLog.toString())
                        .setPositiveButton("OK", null).show()
                    10 -> AlertDialog.Builder(this).setTitle("Notification")
                        .setMessage("Notifikasi entry: 1x per setup\nTelegram: jika token tersimpan\nBackground: tekan START")
                        .setPositiveButton("OK", null).show()
                    11 -> stopAll()
                    12 -> exitApp()
                }
            }
            .setNegativeButton("✕ TUTUP MENU", null)
            .show()
    }

    private fun showQuotes() {
        AlertDialog.Builder(this)
            .setTitle("MARKET / QUOTES")
            .setMessage(
                "XAUUSD\n" +
                    "Mid: ${"%.2f".format(lastPrice)}\n" +
                    "Bid: ${"%.2f".format(lastBid)}\n" +
                    "Ask: ${"%.2f".format(lastAsk)}\n" +
                    "Spread: ${"%.2f".format(if (!lastBid.isNaN() && !lastAsk.isNaN()) lastAsk - lastBid else 0.0)}\n" +
                    "H: ${"%.2f".format(sessionHigh)}  L: ${"%.2f".format(sessionLow)}\n" +
                    "Server: Biquote • ${SessionHelper.sessionLabel()}"
            ).setPositiveButton("OK", null).show()
    }

    private fun showChartHelp() {
        AlertDialog.Builder(this)
            .setTitle("CHART & INDICATORS")
            .setMessage(
                "• Geser horizontal = scroll history\n" +
                    "• Geser vertikal / pinch = zoom\n" +
                    "• Sentuh chart = crosshair harga\n" +
                    "• Tap area kanan chart = AUTO scroll ke candle terakhir\n" +
                    "• EMA20 (kuning) EMA50 (biru)\n" +
                    "• Garis S/R putus-putus\n" +
                    "• Badge Bid/Ask/Mid di kanan"
            ).setPositiveButton("OK", null).show()
    }

    private fun showSettings() {
        AlertDialog.Builder(this)
            .setTitle("SETTINGS")
            .setMessage("Signal only • No auto broker order\nTF: M1/M5/M15\nMin score: 78%\nPackage: com.xauusd.scalper.v5")
            .setPositiveButton("OK", null).show()
    }

    private fun showBiquoteInfo() {
        AlertDialog.Builder(this)
            .setTitle("BIQUOTE")
            .setMessage("Sumber: https://biquote.io\nTick 1s + OHLC M1/M5/M15\nGratis • tanpa API key\nStatus: ${connection.text}")
            .setPositiveButton("Scan ulang") { _, _ -> fullScan() }
            .setNegativeButton("Tutup", null).show()
    }

    private fun showStrategy() {
        AlertDialog.Builder(this)
            .setTitle("STRATEGY")
            .setMessage(
                "8 confluence checks → skor 0–100\n" +
                    "Sinyal hanya jika ≥ 78%\n\n" +
                    "• M5 EMA trend bias\n" +
                    "• M1 pullback EMA20\n" +
                    "• Wick / structure\n" +
                    "• RSI zone\n" +
                    "• Range vol proxy\n" +
                    "• Session London/NY\n" +
                    "• Spread ≤ 0.25\n" +
                    "• BOS\n\n" +
                    "Entry: BUY LIMIT / SELL LIMIT"
            ).setPositiveButton("OK", null).show()
    }

    private fun showRisk() {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val bal = EditText(this).apply { hint = "Balance USD"; setText(RiskHelper.balance(this@MainActivity).toString()); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val risk = EditText(this).apply { hint = "Risk % per trade"; setText(RiskHelper.riskPct(this@MainActivity).toString()); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val daily = EditText(this).apply { hint = "Max daily loss %"; setText(RiskHelper.dailyLossPct(this@MainActivity).toString()); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val maxp = EditText(this).apply { hint = "Max positions"; setText(RiskHelper.maxPos(this@MainActivity).toString()); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        box.addView(bal); box.addView(risk); box.addView(daily); box.addView(maxp)
        val preview = if (lastSignal != null)
            RiskHelper.summary(this, lastSignal!!.entry, lastSignal!!.sl, lastSignal!!.tp1)
        else "Isi balance & risk, lalu ada sinyal untuk hitung lot."
        AlertDialog.Builder(this)
            .setTitle("RISK MANAGEMENT")
            .setMessage(preview)
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                RiskHelper.save(
                    this,
                    bal.text.toString().toDoubleOrNull() ?: 1000.0,
                    risk.text.toString().toDoubleOrNull() ?: 1.0,
                    daily.text.toString().toDoubleOrNull() ?: 3.0,
                    maxp.text.toString().toIntOrNull() ?: 1
                )
                addLog("Risk settings saved")
            }
            .setNegativeButton("Tutup", null).show()
    }

    private fun showTelegramDialog() {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val tokenIn = EditText(this).apply {
            hint = "Bot Token @BotFather"
            setText(TelegramHelper.token(this@MainActivity))
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        val chatIn = EditText(this).apply {
            hint = "Chat ID"
            setText(TelegramHelper.chatId(this@MainActivity))
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        box.addView(tokenIn); box.addView(chatIn)
        AlertDialog.Builder(this)
            .setTitle("TELEGRAM")
            .setMessage("Signal otomatis saat entry (1x).\nTP/SL alert manual dari history.")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                TelegramHelper.save(this, tokenIn.text.toString(), chatIn.text.toString())
                addLog("Telegram saved")
            }
            .setNeutralButton("Test") { _, _ ->
                TelegramHelper.save(this, tokenIn.text.toString(), chatIn.text.toString())
                scope.launch(Dispatchers.IO) {
                    val ok = TelegramHelper.send(
                        TelegramHelper.token(this@MainActivity),
                        TelegramHelper.chatId(this@MainActivity),
                        "Test XAUUSD Scalper v5.5"
                    )
                    withContext(Dispatchers.Main) { addLog(if (ok) "Telegram OK" else "Telegram GAGAL") }
                }
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun startAll() {
        running = true
        startForegroundService(Intent(this, SignalService::class.java))
        connection.text = "LIVE 1s"
        connection.setTextColor(Color.rgb(0, 230, 118))
        addLog("START monitor")
        fullScan()
    }

    private fun stopAll() {
        running = false
        try { stopService(Intent(this, SignalService::class.java)) } catch (_: Exception) {}
        connection.text = "STOPPED"
        connection.setTextColor(Color.rgb(255, 152, 0))
        addLog("STOP")
    }

    private fun exitApp() {
        stopAll()
        tickJob?.cancel(); scanJob?.cancel(); scope.cancel()
        finishAffinity()
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
                    lastBid = t.bid
                    lastAsk = t.ask
                    if (sessionHigh.isNaN() || t.mid > sessionHigh) sessionHigh = t.mid
                    if (sessionLow.isNaN() || t.mid < sessionLow) sessionLow = t.mid
                    price.text = "%.2f".format(t.mid)
                    val ch = if (prev.isNaN()) 0.0 else t.mid - prev
                    priceChange.text = (if (ch >= 0) "+" else "") + "%.2f".format(ch)
                    priceChange.setTextColor(if (ch >= 0) Color.rgb(0, 230, 118) else Color.rgb(239, 83, 80))
                    spreadLine.text = "Spread %.2f".format(t.spread)
                    highLow.text = "H ${"%.2f".format(sessionHigh)}   L ${"%.2f".format(sessionLow)}  •  B ${"%.2f".format(t.bid)} A ${"%.2f".format(t.ask)}"
                    connection.text = "LIVE 1s"
                    connection.setTextColor(Color.rgb(0, 230, 118))
                    if (m1.isNotEmpty()) {
                        val last = m1.last()
                        m1 = m1.dropLast(1) + last.copy(
                            close = t.mid,
                            high = maxOf(last.high, t.mid),
                            low = minOf(last.low, t.mid)
                        )
                        renderChart()
                    }
                } catch (e: Exception) {
                    connection.text = "OFFLINE"
                    connection.setTextColor(Color.rgb(255, 90, 90))
                    addLog("tick ${e.message}")
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
                    if (s.m1.isNotEmpty()) {
                        sessionHigh = s.m1.maxOf { it.high }
                        sessionLow = s.m1.minOf { it.low }
                    }
                    price.text = "%.2f".format(s.price)
                    highLow.text = "H ${"%.2f".format(sessionHigh)}   L ${"%.2f".format(sessionLow)}"
                    spreadLine.text = "Spread %.2f".format(s.spread)
                    ticker.text = SessionHelper.tickerText() + FundamentalTips.tickerExtra()
                    ticker.isSelected = true
                    applySignal(sig, st)
                    renderChart()
                    addLog("BIQUOTE OK score ${st["score"]}%")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { addLog("${e.message}") }
            }
        }
    }

    private fun stars(n: Int) = buildString {
        for (i in 1..5) append(if (i <= n) "★" else "☆")
    }

    private fun applySignal(sig: SignalResult?, st: Map<String, String>) {
        lastSignal = sig
        val score = st["score"]?.toIntOrNull() ?: 0
        val starN = st["stars"]?.toIntOrNull() ?: (score / 20)
        m5Bias.text = "M5 ${st["bias"] ?: "-"}"
        m1State.text = "M1 ${st["watch"] ?: "WAIT"}"
        confidence.text = "${stars(starN.coerceIn(0, 5))}  $score%"

        if (sig == null) {
            val w = st["watch"] ?: "WAIT"
            lamp.text = when {
                w.contains("BUY") -> "🟢"
                w.contains("SELL") -> "🔴"
                else -> "⚪"
            }
            signalState.text = w
            signalDetail.text = "Wick/Sweep/BOS • RSI ${st["rsi"]} • Spread ${st["spread"]}"
            boxEntry.text = "Entry\n--"; boxSl.text = "SL\n--"; boxTp1.text = "TP1\n--"; boxTp2.text = "TP2\n--"
        } else {
            lamp.text = if (sig.side == "BUY") "🟢" else "🔴"
            signalState.text = "${sig.entryType} ${sig.state}"
            val lot = RiskHelper.autoLot(this, sig.entry, sig.sl)
            signalDetail.text = "${sig.reason} • Lot ~${"%.2f".format(lot)}"
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
        chart.setData(
            data, timeframe,
            if (lastPrice.isNaN()) null else lastPrice,
            lastSignal,
            if (lastBid.isNaN()) null else lastBid,
            if (lastAsk.isNaN()) null else lastAsk
        )
    }

    override fun onDestroy() {
        tickJob?.cancel(); scanJob?.cancel(); scope.cancel()
        super.onDestroy()
    }
}
