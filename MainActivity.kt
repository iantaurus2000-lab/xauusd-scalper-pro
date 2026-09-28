package com.xauusd.scalper

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.widget.*
import kotlinx.coroutines.*
import kotlin.math.abs

class MainActivity : Activity() {
    private var apiKey: String = ""
    private lateinit var token: String
    private lateinit var chat: String
    private lateinit var price: TextView
    private lateinit var change: TextView
    private lateinit var spread: TextView
    private lateinit var high: TextView
    private lateinit var low: TextView
    private lateinit var connection: TextView
    private lateinit var lamp: TextView
    private lateinit var state: TextView
    private lateinit var detail: TextView
    private lateinit var confidence: TextView
    private lateinit var m5Bias: TextView
    private lateinit var m1State: TextView
    private lateinit var log: TextView
    private lateinit var chart: CandleChartView
    private lateinit var menuPanel: View
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timeframe = "M1"
    private var m1: List<Candle> = emptyList()
    private var m5: List<Candle> = emptyList()
    private var m15: List<Candle> = emptyList()
    private var lastPrice = Double.NaN
    private var lastSignal: SignalResult? = null
    private var emaOn = true
    private var srOn = true
    private var fibOn = true
    private var autoRefresh = true
    private var lastHistory = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i ?: return
            val p = i.getDoubleExtra("price", Double.NaN)
            if (!p.isNaN()) updatePrice(p)
            i.getStringExtra("bias")?.let { m5Bias.text = "M5 $it" }
            i.getStringExtra("signal")?.let { signalText ->
                if (signalText != "NO SIGNAL") {
                    detail.text = signalText
                    saveHistory(signalText)
                }
            }
            i.getStringExtra("log")?.let { log.text = "Log: $it" }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        price = findViewById(R.id.price); change = findViewById(R.id.change); spread = findViewById(R.id.spread)
        high = findViewById(R.id.dayHigh); low = findViewById(R.id.dayLow); connection = findViewById(R.id.connection)
        lamp = findViewById(R.id.signalLamp); state = findViewById(R.id.signalState); detail = findViewById(R.id.signalDetail)
        confidence = findViewById(R.id.confidence); m5Bias = findViewById(R.id.m5Bias); m1State = findViewById(R.id.m1State)
        log = findViewById(R.id.log); chart = findViewById(R.id.chart); menuPanel = findViewById(R.id.menuPanel)
        loadPrefs()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
        findViewById<ImageButton>(R.id.menu).setOnClickListener { toggleMenu() }
        findViewById<Button>(R.id.tfM1).setOnClickListener { timeframe="M1"; renderChart() }
        findViewById<Button>(R.id.tfM5).setOnClickListener { timeframe="M5"; renderChart() }
        findViewById<Button>(R.id.tfM15).setOnClickListener { timeframe="M15"; renderChart() }
        findViewById<Button>(R.id.indEma).setOnClickListener { emaOn=!emaOn; renderChart() }
        findViewById<Button>(R.id.indSR).setOnClickListener { srOn=!srOn; renderChart() }
        findViewById<Button>(R.id.indFib).setOnClickListener { fibOn=!fibOn; renderChart() }
        findViewById<Button>(R.id.start).setOnClickListener { savePrefs(); startBot() }
        findViewById<Button>(R.id.stop).setOnClickListener { stopService(Intent(this, SignalService::class.java)); connection.text="STOPPED"; connection.setTextColor(Color.LTGRAY) }
        setupMenu()
        refreshAll(true)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, IntentFilter(SignalService.ACTION), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, IntentFilter(SignalService.ACTION))
        }
        startUiRefreshLoop()
    }
    override fun onPause() { unregisterReceiver(receiver); super.onPause() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun startUiRefreshLoop() {
        scope.launch {
            while (isActive) {
                delay(30_000)
                if (autoRefresh) refreshAll()
            }
        }
    }

    private fun toggleMenu() { menuPanel.visibility = if (menuPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
    private fun closeMenu() { menuPanel.visibility = View.GONE }
    private fun loadPrefs(){
        apiKey=prefs.getString("api","")?:""; token=prefs.getString("token","")?:""; chat=prefs.getString("chat","")?:""
        autoRefresh=prefs.getBoolean("auto_refresh",true); emaOn=prefs.getBoolean("ema",true); srOn=prefs.getBoolean("sr",true); fibOn=prefs.getBoolean("fib",true)
    }
    private fun savePrefs(){ prefs.edit().putString("api",apiKey).putString("token",token).putString("chat",chat).putBoolean("auto_refresh",autoRefresh).putBoolean("ema",emaOn).putBoolean("sr",srOn).putBoolean("fib",fibOn).apply() }

    private fun startBot(){
        val i=Intent(this,SignalService::class.java).apply{putExtra("token",token);putExtra("chat",chat)}
        startForegroundService(i); connection.text="LIVE"; connection.setTextColor(Color.rgb(70,230,150)); log.text="Log: signal engine aktif (biquote)"
    }

    private fun refreshAll(initial:Boolean=false){
        scope.launch(Dispatchers.IO){
            try {
                val s=Market.snapshot(lastPrice); val ev=SignalEngine.evaluate(s)
                withContext(Dispatchers.Main){
                    m1=s.m1; m5=s.m5; m15=s.m15; lastPrice=s.price
                    connection.text="LIVE"; connection.setTextColor(Color.rgb(70,230,150)); updatePrice(s.price)
                    high.text="H %.2f".format(s.m1.takeLast(60).maxOf{it.high}); low.text="L %.2f".format(s.m1.takeLast(60).minOf{it.low})
                    applySignal(ev.first,ev.second); renderChart(); log.text="Log: Data OK • ${s.m1.last().time}"
                }
            }catch(e:Exception){ withContext(Dispatchers.Main){ connection.text="ERROR"; connection.setTextColor(Color.rgb(255,90,90)); log.text="Log: ${e.message}"; if(initial) showApiError(e.message?:"Market error") } }
        }
    }

    private fun updatePrice(p:Double){
        val old=lastPrice; price.text="%.2f".format(p)
        if(!old.isNaN()){ val d=p-old; change.text="%+.2f".format(d); change.setTextColor(if(d>=0) Color.rgb(70,230,150) else Color.rgb(255,90,100)) }
        chart.setCurrentPrice(p)
    }

        private fun applySignal(sig:SignalResult?, st:Map<String,String>){
        lastSignal = sig
        m5Bias.text = "M5 ${st["bias"] ?: "NEUTRAL"}"
        m1State.text = "M1 ${st["entryState"] ?: st["watch"] ?: "WAIT"}"
        if (sig == null) {
            val watch = st["watch"] ?: "WAIT"
            when {
                watch.startsWith("BUY") -> {
                    lamp.text = "🟢"
                    state.text = "BUY WATCH"
                    state.setTextColor(Color.rgb(0, 220, 120))
                    confidence.text = "Confidence 3/5"
                }
                watch.startsWith("SELL") -> {
                    lamp.text = "🔴"
                    state.text = "SELL WATCH"
                    state.setTextColor(Color.rgb(255, 80, 80))
                    confidence.text = "Confidence 3/5"
                }
                else -> {
                    lamp.text = "⚪"
                    state.text = "WAIT"
                    state.setTextColor(Color.rgb(150, 160, 175))
                    confidence.text = "Confidence 0/5"
                }
            }
            detail.text = "Wick ${st["wick"] ?: "WAITING"}  •  Sweep ${st["sweep"] ?: "WAITING"}  •  BOS ${st["bos"] ?: "WAITING"}"
        } else {
            if (sig.side == "BUY") {
                lamp.text = "🟢"
                state.text = "BUY"
                state.setTextColor(Color.rgb(0, 255, 130))
            } else {
                lamp.text = "🔴"
                state.text = "SELL"
                state.setTextColor(Color.rgb(255, 70, 80))
            }
            confidence.text = "Confidence ${sig.confidence}/5"
            detail.text = "${sig.side}  Entry ${"%.2f".format(sig.entry)}  |  SL ${"%.2f".format(sig.sl)}  |  TP1 ${"%.2f".format(sig.tp1)}  |  TP2 ${"%.2f".format(sig.tp2)}"
        }
    } else {
            state.text="${sig.side} • ${sig.state}"; confidence.text="${sig.confidence}/5"; detail.text="Entry %.2f • SL %.2f • TP1 %.2f • TP2 %.2f".format(sig.entry,sig.sl,sig.tp1,sig.tp2)
            lamp.text="●"; lamp.setTextColor(if(sig.side=="BUY") Color.rgb(0,255,120) else Color.rgb(255,60,70))
        }
    }

    private fun renderChart(){ val data=when(timeframe){"M5"->m5;"M15"->m15;else->m1}; chart.setLayers(emaOn,srOn,fibOn); chart.setData(data,timeframe,if(lastPrice.isNaN())null else lastPrice,lastSignal) }

    private fun setupMenu(){
        findViewById<Button>(R.id.menuMarket).setOnClickListener { closeMenu(); showMarketDialog() }
        findViewById<Button>(R.id.menuChart).setOnClickListener { closeMenu(); showChartDialog() }
        findViewById<Button>(R.id.menuSignal).setOnClickListener { closeMenu(); showSignalDialog() }
        findViewById<Button>(R.id.menuSettings).setOnClickListener { closeMenu(); showSettingsDialog() }
        findViewById<Button>(R.id.menuTelegram).setOnClickListener { closeMenu(); showTelegramDialog() }
        findViewById<Button>(R.id.menuApi).setOnClickListener { closeMenu(); showApiDialog() }
        findViewById<Button>(R.id.menuStrategy).setOnClickListener { closeMenu(); showStrategyDialog() }
        findViewById<Button>(R.id.menuRisk).setOnClickListener { closeMenu(); showRiskDialog() }
        findViewById<Button>(R.id.menuHistory).setOnClickListener { closeMenu(); showHistoryDialog() }
        findViewById<Button>(R.id.menuLog).setOnClickListener { closeMenu(); showLogDialog() }
        findViewById<Button>(R.id.menuNotification).setOnClickListener { closeMenu(); showNotificationDialog() }
        findViewById<Button>(R.id.menuClose).setOnClickListener { closeMenu() }
    }

    private fun baseBox(): LinearLayout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(8,4,8,4) }
    private fun label(text:String)=TextView(this).apply{ this.text=text; setTextColor(Color.rgb(150,160,174)); textSize=11f; setPadding(4,8,4,4) }
    private fun input(hint:String,value:String="")=EditText(this).apply{ this.hint=hint; setText(value); setTextColor(Color.WHITE); setHintTextColor(Color.rgb(120,130,145)); setSingleLine(true); setPadding(12,8,12,8) }
    private fun rowButton(text:String, action:()->Unit)=Button(this).apply{ this.text=text; setOnClickListener{action()} }

    private fun showMarketDialog(){
        val box=baseBox(); val p=TextView(this).apply{ text="XAU/USD\n\nPRICE   ${if(lastPrice.isNaN())"--" else "%.2f".format(lastPrice)}\n${change.text}\n\n${high.text}     ${low.text}\n\nM5: ${m5Bias.text}\nM1: ${m1State.text}\n\nData: biquote.io (gratis)\nLast candle: ${m1.lastOrNull()?.time?:"--"}"; setTextColor(Color.WHITE); textSize=14f; setPadding(4,8,4,8) }
        box.addView(p); box.addView(rowButton("↻  REFRESH MARKET"){ refreshAll(); p.text="Refresh diminta…" })
        AlertDialog.Builder(this).setTitle("📊 Market / Quotes").setView(box).setPositiveButton("TUTUP",null).show()
    }

    private fun showChartDialog(){
        val box=baseBox(); box.addView(label("TIMEFRAME")); val tf=LinearLayout(this); listOf("M1","M5","M15").forEach{t-> val b=rowButton(t){timeframe=t;renderChart()}; tf.addView(b,LinearLayout.LayoutParams(0,44,1f))}; box.addView(tf)
        val ema=CheckBox(this).apply{text="EMA 20 / 50";isChecked=emaOn}; val sr=CheckBox(this).apply{text="Support / Resistance";isChecked=srOn}; val fib=CheckBox(this).apply{text="Fibonacci";isChecked=fibOn}; listOf(ema,sr,fib).forEach{it.setTextColor(Color.WHITE);box.addView(it)}
        box.addView(label("Chart utama tetap tampil di halaman depan. Gunakan tombol di sini untuk mengatur layer."))
        AlertDialog.Builder(this).setTitle("📈 Chart & Indicators").setView(box).setPositiveButton("TERAPKAN"){_,_->emaOn=ema.isChecked;srOn=sr.isChecked;fibOn=fib.isChecked;savePrefs();renderChart()}.setNegativeButton("BATAL",null).show()
    }

    private fun showSignalDialog(){
        val s=lastSignal; val box=baseBox(); val tv=TextView(this).apply{setTextColor(Color.WHITE);textSize=14f;text=if(s==null) "STATUS: ${state.text}\n\nBias: ${m5Bias.text}\nWick: waiting\nSweep: waiting\nBOS: waiting\n\nBelum ada entry aktif." else "${s.side} • ${s.state}\n\nEntry: %.2f\nSL: %.2f\nTP1: %.2f\nTP2: %.2f\nConfidence: %d/5\n\nSetup: ${s.setup}\nReason: ${s.reason}".format(s.entry,s.sl,s.tp1,s.tp2,s.confidence)};box.addView(tv);box.addView(rowButton("↻  SCAN SEKARANG"){refreshAll()})
        AlertDialog.Builder(this).setTitle("🎯 Signal Center").setView(box).setPositiveButton("TUTUP",null).show()
    }

    private fun showApiDialog(){
        val box=baseBox()
        box.addView(label("SUMBER DATA SAAT INI"))
        box.addView(label("biquote.io — 100% GRATIS\nTidak perlu API Key\nUpdate ~20 detik\nSumber: MetaTrader 5 feed"))
        box.addView(label("\nTelegram Bot (opsional)"))
        val tInput = input("Bot Token", token)
        val cInput = input("Chat ID", chat)
        box.addView(tInput); box.addView(cInput)
        AlertDialog.Builder(this).setTitle("🔑 Data & Telegram")
            .setView(box)
            .setPositiveButton("SIMPAN"){_,_->
                token = tInput.text.toString().trim()
                chat = cInput.text.toString().trim()
                savePrefs()
                refreshAll()
            }
            .setNeutralButton("TEST MARKET"){_,_->refreshAll()}
            .setNegativeButton("TUTUP",null).show()
    }

    private fun showTelegramDialog(){
        val box=baseBox(); val t=input("Bot Token",token); t.inputType=0x81; val c=input("Chat ID",chat); c.inputType=2; box.addView(label("BOT TOKEN"));box.addView(t);box.addView(label("CHAT ID"));box.addView(c)
        AlertDialog.Builder(this).setTitle("📱 Telegram").setView(box).setNeutralButton("TEST",null).setPositiveButton("SIMPAN"){_,_->token=t.text.toString().trim();chat=c.text.toString().trim();savePrefs()}.setNegativeButton("BATAL",null).create().also{d->d.setOnShowListener{d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener{scope.launch(Dispatchers.IO){val ok=Telegram.send(t.text.toString().trim(),c.text.toString().trim(),"🟢 XAUUSD SCALPER PRO\nTest Telegram berhasil.");withContext(Dispatchers.Main){Toast.makeText(this@MainActivity,if(ok.first)"Telegram OK" else "Telegram gagal",Toast.LENGTH_LONG).show()}}}};d.show()}
    }

    private fun showSettingsDialog(){
        val box=baseBox(); val ar=Switch(this).apply{text="Auto refresh market";isChecked=autoRefresh}; val e=Switch(this).apply{text="EMA 20 / 50";isChecked=emaOn}; val sr=Switch(this).apply{text="Support / Resistance";isChecked=srOn}; val f=Switch(this).apply{text="Fibonacci";isChecked=fibOn}; listOf(ar,e,sr,f).forEach{it.setTextColor(Color.WHITE);box.addView(it)}
        AlertDialog.Builder(this).setTitle("⚙ Settings").setView(box).setPositiveButton("SIMPAN"){_,_->autoRefresh=ar.isChecked;emaOn=e.isChecked;srOn=sr.isChecked;fibOn=f.isChecked;savePrefs();renderChart()}.setNegativeButton("BATAL",null).show()
    }

    private fun showStrategyDialog(){
        val box=baseBox(); box.addView(label("STRATEGY FLOW")); box.addView(TextView(this).apply{text="M5 Bias → Liquidity Sweep → Wick Rejection → BOS → Wick-Tip Entry → Confidence";setTextColor(Color.WHITE);textSize=13f})
        val wick=Switch(this).apply{text="Wick-Tip Entry Mode";isChecked=prefs.getBoolean("wick_mode",true)}; val bos=Switch(this).apply{text="Require BOS confirmation";isChecked=prefs.getBoolean("require_bos",true)}; listOf(wick,bos).forEach{it.setTextColor(Color.WHITE);box.addView(it)}
        AlertDialog.Builder(this).setTitle("🧠 Strategy").setView(box).setPositiveButton("SIMPAN"){_,_->prefs.edit().putBoolean("wick_mode",wick.isChecked).putBoolean("require_bos",bos.isChecked).apply()}.setNegativeButton("BATAL",null).show()
    }

    private fun showRiskDialog(){
        val box=baseBox(); val r=input("Risk % per signal",prefs.getString("risk_pct","1.0")?:"1.0"); val rr1=input("TP1 R multiple",prefs.getString("rr1","1.0")?:"1.0"); val rr2=input("TP2 R multiple",prefs.getString("rr2","1.8")?:"1.8"); box.addView(label("RISK MANAGEMENT — signal only"));box.addView(r);box.addView(rr1);box.addView(rr2)
        AlertDialog.Builder(this).setTitle("💰 Risk Management").setView(box).setPositiveButton("SIMPAN"){_,_->prefs.edit().putString("risk_pct",r.text.toString()).putString("rr1",rr1.text.toString()).putString("rr2",rr2.text.toString()).apply()}.setNegativeButton("BATAL",null).show()
    }

    private fun saveHistory(text:String){ if(text==lastHistory)return;lastHistory=text;val old=prefs.getString("history","")?:"";val new=(text+"\n---\n"+old).take(5000);prefs.edit().putString("history",new).apply() }
    private fun showHistoryDialog(){
        val tv=TextView(this).apply{text=prefs.getString("history","")?.ifBlank{"Belum ada signal."}?:"Belum ada signal.";setTextColor(Color.WHITE);textSize=12f;setPadding(10,10,10,10)}
        AlertDialog.Builder(this).setTitle("📋 Signal History").setView(tv).setNeutralButton("HAPUS"){_,_->prefs.edit().remove("history").apply()}.setPositiveButton("TUTUP",null).show()
    }
    private fun showLogDialog(){ AlertDialog.Builder(this).setTitle("📜 Log").setMessage(log.text.toString()).setPositiveButton("TUTUP",null).setNeutralButton("REFRESH"){_,_->refreshAll()}.show() }
    private fun showNotificationDialog(){
        val box=baseBox(); val vib=Switch(this).apply{text="Vibration";isChecked=prefs.getBoolean("vibration",true)}; val sound=Switch(this).apply{text="Sound";isChecked=prefs.getBoolean("sound",true)}; listOf(vib,sound).forEach{it.setTextColor(Color.WHITE);box.addView(it)};box.addView(rowButton("🔔  TEST ALARM"){testAlarm(vib.isChecked)})
        AlertDialog.Builder(this).setTitle("🔔 Notification & Alarm").setView(box).setPositiveButton("SIMPAN"){_,_->prefs.edit().putBoolean("vibration",vib.isChecked).putBoolean("sound",sound.isChecked).apply()}.setNegativeButton("BATAL",null).show()
    }
    private fun testAlarm(vibrate:Boolean){
        if(vibrate){val v=getSystemService(VIBRATOR_SERVICE) as Vibrator;if(Build.VERSION.SDK_INT>=26)v.vibrate(VibrationEffect.createOneShot(350,VibrationEffect.DEFAULT_AMPLITUDE))else @Suppress("DEPRECATION") v.vibrate(350)}
        if(Build.VERSION.SDK_INT>=26){val nm=getSystemService(NotificationManager::class.java);val channel=NotificationChannel("alerts","XAUUSD Signal Alerts",NotificationManager.IMPORTANCE_HIGH);nm.createNotificationChannel(channel);val n=androidx.core.app.NotificationCompat.Builder(this,"alerts").setSmallIcon(R.drawable.ic_xau_logo).setContentTitle("XAUUSD SIGNAL TEST").setContentText("BUY / SELL / WAIT alarm aktif").setAutoCancel(true).build();if(Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)nm.notify(99,n)}
    }
    private fun showApiError(s:String){AlertDialog.Builder(this).setTitle("Market data error").setMessage(s+"\n\nSumber: biquote.io (gratis). Coba lagi atau cek koneksi internet.").setPositiveButton("REFRESH"){_,_->refreshAll()}.setNegativeButton("TUTUP",null).show()}
}
