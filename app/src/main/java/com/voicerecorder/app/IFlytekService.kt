package com.voicerecorder.app

import android.media.MediaMetadataRetriever
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object IFlytekService {

    private const val APP_ID = "c445a5c3"
    private const val ACCESS_KEY_ID = "daf89fdc869aea167373f5bf59d6c88d"
    private const val ACCESS_KEY_SECRET = "MjgwZTkyNzRhMzcwNTFjOGFkZGZlODZl"
    private const val BASE_URL = "https://office-api-ist-dx.iflyaisol.com"

    // 签名：TreeMap排序 → URLEncode拼接 → HmacSHA1 → Base64
    private fun buildSignature(params: TreeMap<String, String>): String {
        val sb = StringBuilder()
        for ((key, value) in params) {
            if (value.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("&")
                sb.append(key).append("=").append(URLEncoder.encode(value, "UTF-8"))
            }
        }
        val baseString = sb.toString()
        Log.d("IFlytek", "baseString=$baseString")
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(ACCESS_KEY_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.encodeToString(mac.doFinal(baseString.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun getDateTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss+0800", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        return sdf.format(Date())
    }

    private fun randomString(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..16).map { chars.random() }.joinToString("")
    }

    private fun getAudioDuration(file: File): Long {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(file.absolutePath)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 60000L
            r.release()
            d
        } catch (e: Exception) { 60000L }
    }

    private suspend fun uploadAudio(audioFile: File): String = withContext(Dispatchers.IO) {
        val params = TreeMap<String, String>()
        params["appId"] = APP_ID
        params["accessKeyId"] = ACCESS_KEY_ID
        params["dateTime"] = getDateTime()
        params["signatureRandom"] = randomString()
        params["fileSize"] = audioFile.length().toString()
        params["fileName"] = audioFile.name
        params["duration"] = getAudioDuration(audioFile).toString()
        params["language"] = "autodialect"

        // 签名放请求头
        val signature = buildSignature(params)

        // URL拼参数（URL编码）
        val urlSb = StringBuilder("$BASE_URL/v2/upload?")
        for ((key, value) in params) {
            urlSb.append(URLEncoder.encode(key, "UTF-8"))
                .append("=")
                .append(URLEncoder.encode(value, "UTF-8"))
                .append("&")
        }
        urlSb.deleteCharAt(urlSb.length - 1)
        Log.d("IFlytek", "Upload URL: $urlSb")
        Log.d("IFlytek", "Signature: $signature")

        val conn = URL(urlSb.toString()).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setRequestProperty("signature", signature)  // 签名放请求头！
        conn.connectTimeout = 60000
        conn.readTimeout = 60000
        conn.outputStream.use { it.write(audioFile.readBytes()) }

        val code = conn.responseCode
        val response = if (code == 200) conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                       else conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: "HTTP $code"
        Log.d("IFlytek", "Upload response: $response")

        val json = JSONObject(response)
        if (json.optString("code") != "000000") {
            throw Exception("上传失败：${json.optString("descInfo", json.optString("code"))}")
        }
        json.getJSONObject("content").getString("orderId")
    }

    private suspend fun queryResult(orderId: String): JSONObject = withContext(Dispatchers.IO) {
        val params = TreeMap<String, String>()
        params["accessKeyId"] = ACCESS_KEY_ID
        params["dateTime"] = getDateTime()
        params["signatureRandom"] = randomString()
        params["orderId"] = orderId
        params["resultType"] = "transfer"

        val signature = buildSignature(params)

        val urlSb = StringBuilder("$BASE_URL/v2/getResult?")
        for ((key, value) in params) {
            urlSb.append(URLEncoder.encode(key, "UTF-8"))
                .append("=")
                .append(URLEncoder.encode(value, "UTF-8"))
                .append("&")
        }
        urlSb.deleteCharAt(urlSb.length - 1)

        val conn = URL(urlSb.toString()).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("signature", signature)  // 签名放请求头！
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }

        val response = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        Log.d("IFlytek", "Query response: $response")
        JSONObject(response)
    }

    private fun parseTranscript(orderResult: String): String {
        val sb = StringBuilder()
        val lattice = JSONObject(orderResult).optJSONArray("lattice") ?: return ""
        for (i in 0 until lattice.length()) {
            val jsonStr = lattice.getJSONObject(i).optString("json_1best", "")
            if (jsonStr.isEmpty()) continue
            val rt = JSONObject(jsonStr).optJSONObject("st")?.optJSONArray("rt") ?: continue
            for (j in 0 until rt.length()) {
                val ws = rt.getJSONObject(j).optJSONArray("ws") ?: continue
                for (k in 0 until ws.length()) {
                    val cw = ws.getJSONObject(k).optJSONArray("cw") ?: continue
                    if (cw.length() > 0) {
                        val w = cw.getJSONObject(0).optString("w", "")
                        val wp = cw.getJSONObject(0).optString("wp", "")
                        if (wp != "g") sb.append(w)
                    }
                }
            }
        }
        return sb.toString().trim()
    }

    suspend fun transcribeAudio(audioFile: File): String = withContext(Dispatchers.IO) {
        val orderId = uploadAudio(audioFile)
        Log.d("IFlytek", "orderId=$orderId")
        repeat(30) { attempt ->
            delay(3000)
            val json = queryResult(orderId)
            if (json.optString("code") != "000000") {
                throw Exception("查询失败：${json.optString("descInfo")}")
            }
            val orderInfo = json.optJSONObject("content")?.optJSONObject("orderInfo") ?: return@repeat
            val status = orderInfo.optInt("status", 0)
            Log.d("IFlytek", "attempt=$attempt status=$status")
            when (status) {
                4 -> {
                    val orderResult = json.optJSONObject("content")?.optString("orderResult", "") ?: ""
                    if (orderResult.isEmpty()) return@withContext "（识别结果为空）"
                    return@withContext parseTranscript(orderResult).ifEmpty { "（识别结果为空）" }
                }
                -1 -> throw Exception("转写失败（failType=${orderInfo.optInt("failType")}），请重试")
                else -> { /* 处理中 */ }
            }
        }
        throw Exception("识别超时，请检查网络后重试")
    }
}
