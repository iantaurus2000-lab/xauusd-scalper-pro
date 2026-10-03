package com.xauusd.scalper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private lateinit var resultsBar: TextView
    private lateinit var boxEntry: TextView
    private lateinit var boxSl: TextView
    private lateinit var boxTp1: TextView
    private lateinit var boxTp2: TextView
    private lateinit var chart: CandleChartView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickJob: Job? = null
    private var scanJob: Job? = null
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
        resultsBar = findViewById(R.id.resultsBar)
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

        findViewById<Button>(R.id.btnCopyAll).setOnClickListener { onCopyAll() }
        findViewById<Button>(R.id.btnOpenMt5).setOnClickListener { onOpenTrade() }

        findViewById<Button>(R.id.btnCopyEntry).setOnClickListener { copyPrice(lastSignal?.entry, "Entry") }
        findViewById<Button>(R.id.btnCopySl).setOnClickListener { copyPrice(lastSignal?.sl, "SL") }
        findViewById<Button>(R.id.btnCopyTp).setOnClickListener { copyPrice(lastSignal?.tp1, "TP1") }
        findViewById<Button>(R.id.btnMarkWin).setOnClickListener { markResult("WIN") }
        findViewById<Button>(R.id.btnMarkLoss).setOnClickListener { markResult("LOSS") }

        ticker.isSelected = true
        refreshResultsBar()
        log.text = "Log: ${PhoneTradeHelper.appLabel(this)}"
        handleIntent(intent)
        startPriceLoop()
        fullScan()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "OPEN_TRADE") {
            onOpenTrade()
        }
    }

    private fun currentLot(): Double {
        val sig = lastSignal ?: return AppPrefs.autoLot(this)
        return try {
            RiskHelper.autoLot(this, sig.entry, sig.sl).takeIf { it >= 0.01 }
                ?: AppPrefs.autoLot(this)
        } catch (_: Exception) {
            AppPrefs.autoLot(this)
        }
    }

    private fun onCopyAll() {
        val sig = lastSignal
        if (sig == null) {
            Toast.makeText(this, "Belum ada sinyal READY", Toast.LENGTH_SHORT).show()
            return
        }
        PhoneTradeHelper.copyAll(this, sig, currentLot())
        Toast.makeText(this, "Semua field order disalin", Toast.LENGTH_SHORT).show()
        log.text = "Log: clipboard ${sig.entryType} ${"%.2f".format(sig.entry)}"
    }

    private fun onOpenTrade() {
        val sig = lastSignal
        if (sig != null) {
            PhoneTradeHelper.copyAndOpen(this, sig, currentLot())
            log.text = "Log: buka trading + salin ${sig.entryType}"
        } else {
            PhoneTradeHelper.openTradingApp(this)
            log.text = "Log: buka ${PhoneTradeHelper.appLabel(this)}"
        }
    }

    private fun copyPrice(v: Double?, label: String) {
        if (v == null) {
            Toast.makeText(this, "Belum ada $label", Toast.LENGTH_SHORT).show()
            return
        }
        val text = "%.2f".format(v)
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label $text disalin", Toast.LENGTH_SHORT).show()
    }

    private fun markResult(result: String) {
        val sig = lastSignal ?: run {
            Toast.makeText(this, "Tidak ada sinyal", Toast.LENGTH_SHORT).show()
            return
        }
        val time = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())
        ResultsTracker.add(this, TradeResult(sig.side, sig.entry, sig.sl, sig.tp1, result, time))
        refreshResultsBar()
        Toast.makeText(this, "Dicatat $result", Toast.LENGTH_SHORT).show()
    }

    private fun refreshResultsBar() {
        resultsBar.text = ResultsTracker.stats(this).lines().take(3).joinToString(" • ")
            .ifBlank { "ENTRY RESULT • Win 0 • Loss 0 • WR 0%" }
    }

    private fun showMenu() {
        val items = arrayOf(
            "🔄 Scan sekarang",
            "📋 Salin semua + buka MT5",
            "📊 Hasil entry",
            "📱 Status app trading: ${PhoneTradeHelper.appLabel(this)}",
            "ℹ️ Mode HP saja (baca)",
            "📱 Telegram",
            "💰 Risk / lot",
            "🎯 Min score ${AppPrefs.minScore(this)}%",
            "🔔 Suara ${if (AppPrefs.soundOn(this)) "ON" else "OFF"}",
            "📳 Getar ${if (AppPrefs.vibeOn(this)) "ON" else "OFF"}",
            "▶️ TES notif",
            "■ Stop",
            "✕ Keluar"
        )
        AlertDialog.Builder(this)
            .setTitle("MODE HP")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> fullScan()
                    1 -> onOpenTrade()
                    2 -> AlertDialog.Builder(this).setTitle("Hasil")
                        .setMessage(ResultsTracker.stats(this))
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Hapus") { _, _ -> ResultsTracker.clear(this); refreshResultsBar() }
                        .show()
                    3 -> Toast.makeText(this, PhoneTradeHelper.appLabel(this), Toast.LENGTH_LONG).show()
                    4 -> AlertDialog.Builder(this).setTitle("Hanya HP")
                        .setMessage(
                            "MT5/Exness di HP tidak mengizinkan APK lain pasang order otomatis.\n\n" +
                                "Alur tercepat di HP:\n" +
                                "1. START di background\n" +
                                "2. Notif ENTRY READY (suara+getar)\n" +
                                "3. Order sudah di clipboard otomatis\n" +
                                "4. Ketuk BUKA MT5 di notif/app\n" +
                                "5. Buat Pending Order → tempel Entry/SL/TP\n\n" +
                                "Itu solusi resmi paling cepat tanpa PC."
                        ).setPositiveButton("OK", null).show()
                    5 -> showTelegram()
                    6 -> showRisk()
                    7 -> {
                        val opts = arrayOf("65%", "72%", "78%", "85%")
                        AlertDialog.Builder(this).setTitle("Min score").setItems(opts) { _, i ->
                            AppPrefs.setMinScore(this, listOf(65, 72, 78, 85)[i])
                            fullScan()
                        }.show()
                    }
                    8 -> AppPrefs.setSound(this, !AppPrefs.soundOn(this))
                    9 -> AppPrefs.setVibe(this, !AppPrefs.vibeOn(this))
                    10 -> {
                        startForegroundService(
                            Intent(this, SignalService::class.java).setAction("TEST_NOTIF")
                        )
                    }
                    11 -> stopAll()
                    12 -> exitApp()
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showTelegram() {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val tokenIn = EditText(this).apply {
            hint = "Bot Token"
            setText(TelegramHelper.token(this@MainActivity))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        val chatIn = EditText(this).apply {
            hint = "Chat ID"
            setText(TelegramHelper.chatId(this@MainActivity))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        box.addView(tokenIn)
        box.addView(chatIn)
        AlertDialog.Builder(this).setTitle("Telegram").setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                TelegramHelper.save(this, tokenIn.text.toString(), chatIn.text.toString())
            }.setNegativeButton("Batal", null).show()
    }

    private fun showRisk() {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val bal = EditText(this).apply {
            hint = "Balance"
            setText(RiskHelper.balance(this@MainActivity).toString())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        val risk = EditText(this).apply {
            hint = "Risk %"
            setText(RiskHelper.riskPct(this@MainActivity).toString())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        box.addView(bal)
        box.addView(risk)
        AlertDialog.Builder(this).setTitle("Risk").setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                RiskHelper.save(
                    this,
                    bal.text.toString().toDoubleOrNull() ?: 1000.0,
                    risk.text.toString().toDoubleOrNull() ?: 1.0,
                    3.0,
                    1
                )
            }.setNegativeButton("Tutup", null).show()
    }

    private fun startAll() {
        startForegroundService(Intent(this, SignalService::class.java))
        connection.text = "LIVE 1s"
        connection.setTextColor(Color.rgb(0, 230, 118))
        log.text = "Log: START HP · ${PhoneTradeHelper.appLabel(this)}"
        fullScan()
    }

    private fun stopAll() {
        try {
            stopService(Intent(this, SignalService::class.java))
        } catch (_: Exception) {
        }
        connection.text = "STOPPED"
        connection.setTextColor(Color.rgb(255, 152, 0))
        log.text = "Log: STOP"
    }

    private fun exitApp() {
        stopAll()
        tickJob?.cancel()
        scanJob?.cancel()
        scope.cancel()
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
                    priceChange.setTextColor(
                        if (ch >= 0) Color.rgb(0, 230, 118) else Color.rgb(239, 83, 80)
                    )
                    spreadLine.text = "Spread %.2f".format(t.spread)
                    highLow.text =
                        "H ${"%.2f".format(sessionHigh)}  L ${"%.2f".format(sessionLow)}"
                    connection.text = "LIVE 1s"
                    connection.setTextColor(Color.rgb(0, 230, 118))
                    fun bump(list: List<Candle>): List<Candle> {
                        if (list.isEmpty()) return list
                        val last = list.last()
                        return list.dropLast(1) + last.copy(
                            close = t.mid,
                            high = maxOf(last.high, t.mid),
                            low = minOf(last.low, t.mid)
                        )
                    }
                    m1 = bump(m1)
                    m5 = bump(m5)
                    m15 = bump(m15)
                    renderChart()
                } catch (e: Exception) {
                    connection.text = "OFFLINE"
                    connection.setTextColor(Color.rgb(255, 90, 90))
                    log.text = "Log: ${e.message}"
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
                val (sig, st) = SignalEngine.evaluate(s, AppPrefs.minScore(this@MainActivity))
                withContext(Dispatchers.Main) {
                    m1 = s.m1
                    m5 = s.m5
                    m15 = s.m15
                    lastPrice = s.price
                    if (s.m1.isNotEmpty()) {
                        sessionHigh = s.m1.maxOf { it.high }
                        sessionLow = s.m1.minOf { it.low }
                    }
                    price.text = "%.2f".format(s.price)
                    highLow.text =
                        "H ${"%.2f".format(sessionHigh)}  L ${"%.2f".format(sessionLow)}"
                    spreadLine.text = "Spread %.2f".format(s.spread)
                    ticker.text = SessionHelper.tickerText()
                    ticker.isSelected = true
                    applySignal(sig, st)
                    renderChart()
                    log.text = "Log: score ${st["score"]}% • ${PhoneTradeHelper.appLabel(this@MainActivity)}"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { log.text = "Log: ${e.message}" }
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
        signalDetail.text = st["steps"] ?: "M5 → Sweep → Wick → Tip → BOS"

        if (sig == null) {
            val w = st["watch"] ?: "WAIT"
            lamp.text = when {
                w.contains("BUY") -> "🟢"
                w.contains("SELL") -> "🔴"
                else -> "⚪"
            }
            signalState.text = w
            boxEntry.text = "Entry\n--"
            boxSl.text = "SL\n--"
            boxTp1.text = "TP1\n--"
            boxTp2.text = "TP2\n--"
        } else {
            lamp.text = if (sig.side == "BUY") "🟢" else "🔴"
            signalState.text = "${sig.entryType}\n${sig.state}"
            boxEntry.text = "Entry\n${"%.2f".format(sig.entry)}"
            boxSl.text = "SL\n${"%.2f".format(sig.sl)}"
            boxTp1.text = "TP1\n${"%.2f".format(sig.tp1)}"
            boxTp2.text = "TP2\n${"%.2f".format(sig.tp2)}"
            // Saat READY di layar: auto-isi clipboard agar siap tempel di MT5 HP
            if (sig.state.contains("READY")) {
                PhoneTradeHelper.copyAll(this, sig, currentLot())
            }
        }
    }

    private fun renderChart() {
        val data = when (timeframe) {
            "M5" -> m5
            "M15" -> m15
            else -> m1
        }
        chart.setLayers(AppPrefs.showEma(this), AppPrefs.showSr(this), AppPrefs.showFib(this))
        chart.setData(
            data,
            timeframe,
            if (lastPrice.isNaN()) null else lastPrice,
            lastSignal,
            if (lastBid.isNaN()) null else lastBid,
            if (lastAsk.isNaN()) null else lastAsk
        )
    }

    override fun onDestroy() {
        tickJob?.cancel()
        scanJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
