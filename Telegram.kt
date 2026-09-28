package com.xauusd.scalper

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Telegram {
    fun send(token: String, chatId: String, message: String): Pair<Boolean,String> {
        return try {
            val url = URL("https://api.telegram.org/bot$token/sendMessage")
            val body = "chat_id=${URLEncoder.encode(chatId, "UTF-8")}&text=${URLEncoder.encode(message, "UTF-8")}".toByteArray()
            val c = url.openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 10000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body) }
            val response = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
            Pair(c.responseCode in 200..299, response.take(300))
        } catch (e: Exception) { Pair(false, e.message ?: "Telegram connection error") }
    }
}
