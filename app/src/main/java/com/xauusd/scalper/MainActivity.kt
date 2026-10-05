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
import android.widget.ScrollView
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
    private var lastSnap: MarketSnapshot? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        try {
            setContentView(R.layout.activity_main)
            bindViews()
            wireButtons()
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            ticker.isSelected = true
            refreshResultsBar()
            log.text = "Log v5.14 Pro · AI engine"
            handleIntent(intent)
            startPriceLoop()
            fullScan()
        } catch (e: Exception) {
            Toast.makeText(this, "Init: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun bindViews() {
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
    }

    private fun wireButtons() {
        findViewById<Button>(R.id.tfM1).setOnClickListener { timeframe = "M1"; renderChart() }
        findViewById<Button>(R.id.tfM5).setOnClickListener { timeframe = "M5"; renderChart() }
        findViewById<Button>(R.id.tfM15).setOnClickListener { timeframe = "M15"; renderChart() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startAll() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopAll() }
        findViewById<Button>(R.id.btnMenu).setOnClickListener {
            try { showMainMenu() } catch (e: Exception) {
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btnCopyAll).setOnClickListener { onSiapOrder() }
        findViewById<Button>(R.id.btnOpenMt5).setOnClickListener { onSiapOrder() }
        findViewById<Button>(R.id.btnCopyEntry).setOnClickListener { copyPrice(lastSignal?.entry, "Entry") }
        findViewById<Button>(R.id.btnCopySl).setOnClickListener { copyPrice(lastSignal?.sl, "SL") }
        findViewById<Button>(R.id.btnCopyTp).setOnClickListener { copyPrice(lastSignal?.tp1, "TP1") }
        findViewById<Button>(R.id.btnMarkWin).setOnClickListener { markResult("WIN") }
        findViewById<Button>(R.id.btnMarkLoss).setOnClickListener { markResult("LOSS") }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "OPEN_TRADE") onSiapOrder()
    }

    private fun dlg(): AlertDialog.Builder =
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun currentLot(): Double {
        val sig = lastSignal ?: return AppPrefs.autoLot(this)
        return try {
            RiskHelper.autoLot(this, sig.entry, sig.sl).takeIf { it >= 0.01 }
                ?: AppPrefs.autoLot(this)
        } catch (_: Exception) {
            AppPrefs.autoLot(this)
        }
    }

    private fun onSiapOrder() {
        val sig = lastSignal
        if (sig == null) {
            Toast.makeText(this, "Belum ada sinyal READY", Toast.LENGTH_SHORT).show()
            return
        }
        PhoneTradeHelper.copyAll(this, sig, currentLot())
        PhoneTradeHelper.openTradingApp(this)
        Toast.makeText(this, "Disalin + buka MT5", Toast.LENGTH_SHORT).show()
    }

    private fun copyPrice(v: Double?, label: String) {
        if (v == null) {
            Toast.makeText(this, "Belum ada " + label, Toast.LENGTH_SHORT).show()
            return
        }
        val text = "%.2f".format(v)
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, label + " " + text, Toast.LENGTH_SHORT).show()
    }

    private fun markResult(result: String) {
        val sig = lastSignal ?: run {
            Toast.makeText(this, "Tidak ada sinyal", Toast.LENGTH_SHORT).show()
            return
        }
        val time = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())
        ResultsTracker.add(this, TradeResult(sig.side, sig.entry, sig.sl, sig.tp1, result, time))
        refreshResultsBar()
    }

    private fun refreshResultsBar() {
        resultsBar.text = ResultsTracker.stats(this).lines().take(3).joinToString(" | ")
            .ifBlank { "Belum ada hasil entry." }
    }

    /** MENU UTAMA PROFESIONAL */
    private fun showMainMenu() {
        val alarm = if (AppPrefs.alarmOn(this)) "ON" else "OFF"
        val items = arrayOf(
            "1. AI Scan Sinyal + Analisa",
            "2. Scan engine sekarang",
            "3. SIAP ORDER (salin + MT5)",
            "4. Indikator & Chart",
            "5. Alarm & Notifikasi [" + alarm + "]",
            "6. Min score / sensitivitas",
            "7. Hasil entry (winrate)",
            "8. Risk management",
            "9. Telegram sinyal",
            "10. Pilih app trading",
            "11. Tentang engine",
            "12. Stop monitor",
            "13. Keluar APK"
        )
        dlg().setTitle("XAUUSD PRO MENU")
            .setItems(items) { _, which ->
                try {
                    when (which) {
                        0 -> runAiScanDialog()
                        1 -> fullScan()
                        2 -> onSiapOrder()
                        3 -> showIndicatorMenu()
                        4 -> showAlarmMenu()
                        5 -> showScoreMenu()
                        6 -> showResultsDialog()
                        7 -> showRisk()
                        8 -> showTelegram()
                        9 -> {
                            PhoneTradeHelper.clearPreferred(this)
                            PhoneTradeHelper.openTradingApp(this, forcePick = true)
                        }
                        10 -> showAboutEngine()
                        11 -> stopAll()
                        12 -> exitApp()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun runAiScanDialog() {
        Toast.makeText(this, "AI menganalisa market...", Toast.LENGTH_SHORT).show()
        scope.launch(Dispatchers.IO) {
            try {
                val s = lastSnap ?: Market.snapshot(lastPrice)
                val (sig, st) = SignalEngine.evaluate(s, AppPrefs.minScore(this@MainActivity))
                val fc = AiAnalyst.forecast(s)
                withContext(Dispatchers.Main) {
                    lastSnap = s
                    m1 = s.m1; m5 = s.m5; m15 = s.m15
                    applySignal(sig, st)
                    renderChart()
                    showAiResultDialog(fc, sig, st)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showAiResultDialog(fc: AiAnalyst.Forecast, sig: SignalResult?, st: Map<String, String>) {
        val body = buildString {
            append(fc.fullText)
            appendLine()
            appendLine("===== ENGINE SIGNAL =====")
            if (sig != null) {
                appendLine(sig.side + " " + sig.entryType + " " + sig.state)
                appendLine("Entry " + "%.2f".format(sig.entry))
                appendLine("SL " + "%.2f".format(sig.sl))
                appendLine("TP1 " + "%.2f".format(sig.tp1) + "  TP2 " + "%.2f".format(sig.tp2))
                appendLine("Score " + st["score"] + "%  " + st["steps"])
            } else {
                appendLine("Watch: " + (st["watch"] ?: "WAIT"))
                appendLine("Score build: " + (st["score"] ?: "0") + "%")
                appendLine(st["steps"] ?: "")
            }
        }
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            text = body
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(40, 30, 40, 30)
            setTextIsSelectable(true)
        }
        scroll.addView(tv)
        dlg().setTitle("AI Analisa Market")
            .setView(scroll)
            .setPositiveButton("SIAP ORDER") { _, _ -> onSiapOrder() }
            .setNeutralButton("Salin teks") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("AI", body))
                Toast.makeText(this, "Analisa disalin", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showIndicatorMenu() {
        val ema = if (AppPrefs.showEma(this)) "ON" else "OFF"
        val sr = if (AppPrefs.showSr(this)) "ON" else "OFF"
        val fib = if (AppPrefs.showFib(this)) "ON" else "OFF"
        dlg().setTitle("Indikator & Chart")
            .setItems(
                arrayOf(
                    "EMA 20/50: " + ema,
                    "Support/Resistance: " + sr,
                    "Fibonacci: " + fib,
                    "Semua ON",
                    "Semua OFF",
                    "Kembali"
                )
            ) { _, i ->
                when (i) {
                    0 -> { AppPrefs.setEma(this, !AppPrefs.showEma(this)); renderChart(); showIndicatorMenu() }
                    1 -> { AppPrefs.setSr(this, !AppPrefs.showSr(this)); renderChart(); showIndicatorMenu() }
                    2 -> { AppPrefs.setFib(this, !AppPrefs.showFib(this)); renderChart(); showIndicatorMenu() }
                    3 -> {
                        AppPrefs.setEma(this, true); AppPrefs.setSr(this, true); AppPrefs.setFib(this, true)
                        renderChart(); Toast.makeText(this, "Indikator ON", Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        AppPrefs.setEma(this, false); AppPrefs.setSr(this, false); AppPrefs.setFib(this, false)
                        renderChart(); Toast.makeText(this, "Indikator OFF", Toast.LENGTH_SHORT).show()
                    }
                    5 -> showMainMenu()
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showAlarmMenu() {
        val on = AppPrefs.alarmOn(this)
        dlg().setTitle("Alarm & Notifikasi")
            .setMessage(
                "Alarm gabungan (suara + getar): " + if (on) "ON" else "OFF" +
                    "\n\nSaat ENTRY READY, notifikasi hanya 1x per level harga.\n" +
                    "Tombol SIAP ORDER di notifikasi membuka MT5."
            )
            .setPositiveButton(if (on) "Matikan Alarm" else "Nyalakan Alarm") { _, _ ->
                AppPrefs.setAlarm(this, !on)
                Toast.makeText(this, "Alarm " + if (!on) "ON" else "OFF", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("TES Alarm") { _, _ ->
                startForegroundService(Intent(this, SignalService::class.java).setAction("TEST_NOTIF"))
            }
            .setNegativeButton("Kembali") { _, _ -> showMainMenu() }
            .show()
    }

    private fun showScoreMenu() {
        dlg().setTitle("Sensitivitas sinyal")
            .setItems(
                arrayOf(
                    "60% - Sering (agresif)",
                    "68% - Seimbang (default)",
                    "75% - Selektif",
                    "85% - Sangat ketat"
                )
            ) { _, i ->
                AppPrefs.setMinScore(this, listOf(60, 68, 75, 85)[i])
                fullScan()
                Toast.makeText(this, "Min score diset", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Kembali") { _, _ -> showMainMenu() }
            .show()
    }

    private fun showResultsDialog() {
        dlg().setTitle("Hasil entry")
            .setMessage(ResultsTracker.stats(this))
            .setPositiveButton("OK", null)
            .setNeutralButton("Hapus") { _, _ ->
                ResultsTracker.clear(this)
                refreshResultsBar()
            }
            .setNegativeButton("Kembali") { _, _ -> showMainMenu() }
            .show()
    }

    private fun showAboutEngine() {
        val msg =
            "XAUUSD PRO SCALPER v5.14\n\n" +
            "Pipeline:\n" +
            "M5 Bias - Sweep - Wick - Tip (M5-2) - BOS\n" +
            "EMA / RSI / MACD / ATR / Spread\n" +
            "Pola: Hammer, Engulf, Pinbar, Doji...\n\n" +
            "AI prediksi candle berikutnya dari\n" +
            "2 bar sebelum candle terakhir.\n\n" +
            "Order: semi-auto SIAP ORDER ke MT5.\n" +
            "Repo: github.com/iantaurus2000-lab/xauusd-scalper-pro"
        dlg().setTitle("Engine & AI")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showTelegram() {
        val pad = (16 * resources.displayMetrics.density).toInt()
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
        dlg().setTitle("Telegram")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                TelegramHelper.save(this, tokenIn.text.toString().trim(), chatIn.text.toString().trim())
                Toast.makeText(this, "Tersimpan", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showRisk() {
        val pad = (16 * resources.displayMetrics.density).toInt()
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
        dlg().setTitle("Risk")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                RiskHelper.save(
                    this,
                    bal.text.toString().toDoubleOrNull() ?: 1000.0,
                    risk.text.toString().toDoubleOrNull() ?: 1.0,
                    3.0, 1
                )
                Toast.makeText(this, "Tersimpan", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun startAll() {
        try {
            startForegroundService(Intent(this, SignalService::class.java))
            connection.text = "LIVE 1s"
            connection.setTextColor(Color.rgb(0, 230, 118))
            log.text = "Log: START v5.14"
            fullScan()
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun stopAll() {
        try { stopService(Intent(this, SignalService::class.java)) } catch (_: Exception) {}
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
                        if (ch >= 0) Color.rgb(0, 230, 118) else Color.rgb(255, 138, 128)
                    )
                    spreadLine.text = "Spread " + "%.2f".format(t.spread)
                    highLow.text = "H " + "%.2f".format(sessionHigh) + "  L " + "%.2f".format(sessionLow)
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
                    m1 = bump(m1); m5 = bump(m5); m15 = bump(m15)
                    renderChart()
                } catch (e: Exception) {
                    connection.text = "OFFLINE"
                    connection.setTextColor(Color.rgb(255, 90, 90))
                    log.text = "Log: " + e.message
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
                    lastSnap = s
                    m1 = s.m1; m5 = s.m5; m15 = s.m15
                    lastPrice = s.price
                    if (s.m1.isNotEmpty()) {
                        sessionHigh = s.m1.maxOf { it.high }
                        sessionLow = s.m1.minOf { it.low }
                    }
                    price.text = "%.2f".format(s.price)
                    highLow.text = "H " + "%.2f".format(sessionHigh) + "  L " + "%.2f".format(sessionLow)
                    spreadLine.text = "Spread " + "%.2f".format(s.spread)
                    ticker.text = SessionHelper.tickerText()
                    ticker.isSelected = true
                    applySignal(sig, st)
                    renderChart()
                    log.text = "Log: " + (st["score"] ?: "0") + "% · " + (st["pattern"] ?: "-")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { log.text = "Log: " + e.message }
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
        m5Bias.text = "M5 " + (st["bias"] ?: "-")
        m1State.text = (st["pattern"] ?: "-") + " · " + (st["watch"] ?: "WAIT")
        confidence.text = stars(starN.coerceIn(0, 5)) + "  " + score + "%"
        signalDetail.text = st["steps"] ?: "M5 · Sweep · Wick · Tip · BOS · EMA · RSI · MACD"

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
            val head = if (sig.side == "BUY") "🟢 XAUUSD SCALPING" else "🔴 XAUUSD SCALPING"
            lamp.text = if (sig.side == "BUY") "🟢" else "🔴"
            signalState.text = head + "\n" + sig.entryType + " · " + sig.state
            boxEntry.text = "Entry\n" + "%.2f".format(sig.entry)
            boxSl.text = "SL\n" + "%.2f".format(sig.sl)
            boxTp1.text = "TP1\n" + "%.2f".format(sig.tp1)
            boxTp2.text = "TP2\n" + "%.2f".format(sig.tp2)
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
            data, timeframe,
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
