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

    // 从讯飞控制台获取
    private const val APP_ID = "c445a5c3"
    private const val ACCESS_KEY_ID = "daf89fdc869aea167373f5bf59d6c88d"   // APIKey
    private const val ACCESS_KEY_SECRET = "MjgwZTkyNzRhMzcwNTFjOGFkZGZlODZl" // APISecret

    private const val BASE_URL = "https://office-api-ist-dx.iflyaisol.com"

    /**
     * 生成签名：
     * 1. 所有参数按key自然排序（TreeMap）
     * 2. 非空value做URLEncode，拼成 key=value&key=value 字符串
     * 3. HmacSHA1(baseString, accessKeySecret) 后 Base64 编码
     */
    private fun buildSignature(params: Map<String, String>): String {
        val sortedParams = TreeMap(params)
        sortedParams.remove("signature")
        val sb = StringBuilder()
        for ((key, value) in sortedParams) {
            if (value.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("&")
                sb.append(key).append("=").append(URLEncoder.encode(value, "UTF-8"))
            }
        }
        val baseString = sb.toString()
        Log.d("IFlytek", "baseString: $baseString")
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(ACCESS_KEY_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val signBytes = mac.doFinal(baseString.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signBytes, Base64.NO_WRAP)
    }

    /** 生成 dateTime 字符串，格式：yyyy-MM-dd'T'HH:mm:ss+0800 */
    private fun getDateTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss+0800", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        return sdf.format(Date())
    }

    /** 生成随机16位字符串 */
    private fun randomString(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..16).map { chars.random() }.joinToString("")
    }

    /** 获取音频时长（毫秒） */
    private fun getAudioDuration(file: File): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 60000L
            retriever.release()
            duration
        } catch (e: Exception) {
            60000L // 默认60秒
        }
    }

    /** 拼接带签名的完整URL */
    private fun buildUrl(endpoint: String, params: Map<String, String>): String {
        val signature = buildSignature(params)
        val sb = StringBuilder("$BASE_URL$endpoint?")
        for ((key, value) in params) {
            sb.append(URLEncoder.encode(key, "UTF-8"))
                .append("=")
                .append(URLEncoder.encode(value, "UTF-8"))
                .append("&")
        }
        sb.append("signature=").append(URLEncoder.encode(signature, "UTF-8"))
        return sb.toString()
    }

    /**
     * 上传音频文件
     * - Content-Type: application/octet-stream
     * - 请求体：音频二进制数据
     * - 参数：URL query string
     * - signature：请求头
     */
    private suspend fun uploadAudio(audioFile: File): String = withContext(Dispatchers.IO) {
        val dateTime = getDateTime()
        val signatureRandom = randomString()
        val duration = getAudioDuration(audioFile)
        val fileSize = audioFile.length()
        val fileName = audioFile.name

        val params = TreeMap<String, String>()
        params["appId"] = APP_ID
        params["accessKeyId"] = ACCESS_KEY_ID
        params["dateTime"] = dateTime
        params["signatureRandom"] = signatureRandom
        params["fileSize"] = fileSize.toString()
        params["fileName"] = fileName
        params["duration"] = duration.toString()
        params["language"] = "autodialect"

        val signature = buildSignature(params)

        // 拼接URL参数
        val urlSb = StringBuilder("$BASE_URL/v2/upload?")
        for ((key, value) in params) {
            urlSb.append(URLEncoder.encode(key, "UTF-8"))
                .append("=")
                .append(URLEncoder.encode(value, "UTF-8"))
                .append("&")
        }
        urlSb.append("signature=").append(URLEncoder.encode(signature, "UTF-8"))
        val urlStr = urlSb.toString()
        Log.d("IFlytek", "Upload URL: $urlStr")

        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.connectTimeout = 60000
        conn.readTimeout = 60000

        // 请求体：音频二进制数据
        conn.outputStream.use { it.write(audioFile.readBytes()) }

        val responseCode = conn.responseCode
        val response = if (responseCode == 200) {
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } else {
            conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: "HTTP Error $responseCode"
        }
        Log.d("IFlytek", "Upload response: $response")

        val json = JSONObject(response)
        val code = json.optString("code", "")
        if (code != "000000") {
            throw Exception("上传失败：${json.optString("descInfo", "code=$code")}")
        }
        json.getJSONObject("content").getString("orderId")
    }

    /**
     * 查询转写结果
     * - Content-Type: application/json
     * - 请求体：{}
     * - 参数：URL query string
     * - signature：放在URL中
     */
    private suspend fun queryResult(orderId: String): JSONObject = withContext(Dispatchers.IO) {
        val dateTime = getDateTime()
        val signatureRandom = randomString()

        val params = TreeMap<String, String>()
        params["accessKeyId"] = ACCESS_KEY_ID
        params["dateTime"] = dateTime
        params["signatureRandom"] = signatureRandom
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
        urlSb.append("signature=").append(URLEncoder.encode(signature, "UTF-8"))
        val urlStr = urlSb.toString()
        Log.d("IFlytek", "Query URL: $urlStr")

        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }

        val response = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        Log.d("IFlytek", "Query response: $response")
        JSONObject(response)
    }

    /** 解析转写文字 */
    private fun parseTranscript(orderResult: String): String {
        val sb = StringBuilder()
        val resultJson = JSONObject(orderResult)
        val lattice = resultJson.optJSONArray("lattice") ?: return ""
        for (i in 0 until lattice.length()) {
            val jsonStr = lattice.getJSONObject(i).optString("json_1best", "")
            if (jsonStr.isEmpty()) continue
            val st = JSONObject(jsonStr).optJSONObject("st") ?: continue
            val rt = st.optJSONArray("rt") ?: continue
            for (j in 0 until rt.length()) {
                val ws = rt.getJSONObject(j).optJSONArray("ws") ?: continue
                for (k in 0 until ws.length()) {
                    val cw = ws.getJSONObject(k).optJSONArray("cw") ?: continue
                    if (cw.length() > 0) {
                        val w = cw.getJSONObject(0).optString("w", "")
                        val wp = cw.getJSONObject(0).optString("wp", "")
                        if (wp != "g") sb.append(w) // 跳过分段标识
                    }
                }
            }
        }
        return sb.toString().trim()
    }

    /** 主入口：上传并轮询结果 */
    suspend fun transcribeAudio(audioFile: File): String = withContext(Dispatchers.IO) {
        val orderId = uploadAudio(audioFile)
        Log.d("IFlytek", "orderId=$orderId")

        // 轮询，最多等90秒（30次 × 3秒）
        repeat(30) { attempt ->
            delay(3000)
            val json = queryResult(orderId)
            val code = json.optString("code", "")
            if (code != "000000") {
                throw Exception("查询失败：${json.optString("descInfo", "code=$code")}")
            }
            val content = json.optJSONObject("content") ?: return@repeat
            val orderInfo = content.optJSONObject("orderInfo") ?: return@repeat
            val status = orderInfo.optInt("status", 0)
            Log.d("IFlytek", "attempt=$attempt status=$status")

            when (status) {
                4 -> { // 完成
                    val orderResult = content.optString("orderResult", "")
                    if (orderResult.isEmpty()) return@withContext "（识别结果为空，请确认录音有声音）"
                    val text = parseTranscript(orderResult)
                    return@withContext text.ifEmpty { "（识别结果为空）" }
                }
                -1 -> {
                    val failType = orderInfo.optInt("failType", 0)
                    throw Exception("转写失败（failType=$failType），请重试")
                }
                else -> { /* 处理中，继续等待 */ }
            }
        }
        throw Exception("识别超时（90秒），请检查网络后重试")
    }
}
