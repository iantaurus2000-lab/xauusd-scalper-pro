package com.xauusd.scalper

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object TelegramHelper {
    private const val PREF = "telegram_cfg"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun save(ctx: Context, token: String, chatId: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("token", token.trim())
            .putString("chat", chatId.trim())
            .apply()
    }

    fun token(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("token", "") ?: ""

    fun chatId(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("chat", "") ?: ""

    fun isConfigured(ctx: Context) =
        token(ctx).isNotBlank() && chatId(ctx).isNotBlank()

    /** Kirim sinyal entry — dipanggil sekali per setup */
    fun sendSignal(ctx: Context, sig: SignalResult, price: Double): Boolean {
        val tok = token(ctx)
        val chat = chatId(ctx)
        if (tok.isBlank() || chat.isBlank()) return false
        val text = buildString {
            appendLine("XAUUSD ${sig.entryType}")
            appendLine("State: ${sig.state}")
            appendLine("Price: ${"%.2f".format(price)}")
            appendLine("Entry: ${"%.2f".format(sig.entry)}")
            appendLine("SL: ${"%.2f".format(sig.sl)}")
            appendLine("TP1: ${"%.2f".format(sig.tp1)}")
            appendLine("TP2: ${"%.2f".format(sig.tp2)}")
            appendLine("Score: ${sig.confidence}/5")
            appendLine(sig.reason)
            appendLine(SessionHelper.sessionLabel())
        }
        return send(tok, chat, text)
    }

    fun send(token: String, chatId: String, text: String): Boolean {
        return try {
            val q = URLEncoder.encode(text, "UTF-8")
            val url = "https://api.telegram.org/bot$token/sendMessage?chat_id=$chatId&text=$q"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }
}
