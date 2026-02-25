package com.voicerecorder.app

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object IFlytekService {

    private const val APP_ID = "c445a5c3"
    private const val API_SECRET = "MjgwZTkyNzRhMzcwNTFjOGFkZGZlODZl"
    private const val UPLOAD_URL = "https://raasr.xfyun.cn/v2/api/upload"
    private const val RESULT_URL = "https://raasr.xfyun.cn/v2/api/getResult"
    private const val BOUNDARY = "----WebKitFormBoundary7MA4YWxkTrZu0gW"

    private fun buildSigna(ts: Long): String {
        val md5 = MessageDigest.getInstance("MD5")
            .digest((APP_ID + ts).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(API_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.encodeToString(mac.doFinal(md5.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun addField(out: DataOutputStream, name: String, value: String) {
        out.writeBytes("--$BOUNDARY\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        out.writeBytes("$value\r\n")
    }

    private suspend fun uploadFile(audioFile: File): String = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis() / 1000
        val signa = buildSigna(ts)
        val audioBase64 = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)

        val conn = URL(UPLOAD_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
        conn.connectTimeout = 60000
        conn.readTimeout = 60000

        DataOutputStream(conn.outputStream).use { out ->
            addField(out, "appId", APP_ID)
            addField(out, "signa", signa)
            addField(out, "ts", ts.toString())
            addField(out, "language", "zh_cn")
            addField(out, "pd", "general")

            // 音频 base64 作为文件字段
            out.writeBytes("--$BOUNDARY\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"fileBase64\"\r\n\r\n")
            out.write(audioBase64.toByteArray(Charsets.UTF_8))
            out.writeBytes("\r\n")

            out.writeBytes("--$BOUNDARY--\r\n")
        }

        val code = conn.responseCode
        val response = if (code == 200) {
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } else {
            conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: "HTTP $code"
        }
        Log.d("IFlytek", "Upload response: $response")

        val json = JSONObject(response)
        val respCode = json.optInt("code", -1)
        if (respCode != 0) {
            throw Exception("上传失败：${json.optString("descInfo", json.optString("desc", "code=$respCode"))}")
        }
        json.getString("orderId")
    }

    private suspend fun queryResult(orderId: String): JSONObject = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis() / 1000
        val signa = buildSigna(ts)

        val conn = URL(RESULT_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

        DataOutputStream(conn.outputStream).use { out ->
            addField(out, "appId", APP_ID)
            addField(out, "signa", signa)
            addField(out, "ts", ts.toString())
            addField(out, "orderId", orderId)
            addField(out, "resultType", "transfer")
            out.writeBytes("--$BOUNDARY--\r\n")
        }

        val response = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        Log.d("IFlytek", "Result response: $response")
        JSONObject(response)
    }

    private fun parseTranscript(content: JSONObject): String {
        val sb = StringBuilder()
        val lattice = content.optJSONArray("lattice") ?: return ""
        for (i in 0 until lattice.length()) {
            val jsonStr = lattice.getJSONObject(i).optString("json_1best", "")
            if (jsonStr.isEmpty()) continue
            val st = JSONObject(jsonStr).optJSONObject("st") ?: continue
            val rt = st.optJSONArray("rt") ?: continue
            for (j in 0 until rt.length()) {
                val ws = rt.getJSONObject(j).optJSONArray("ws") ?: continue
                for (k in 0 until ws.length()) {
                    val cw = ws.getJSONObject(k).optJSONArray("cw") ?: continue
                    if (cw.length() > 0) sb.append(cw.getJSONObject(0).optString("w", ""))
                }
            }
        }
        return sb.toString().trim()
    }

    suspend fun transcribeAudio(audioFile: File): String = withContext(Dispatchers.IO) {
        val orderId = uploadFile(audioFile)
        Log.d("IFlytek", "orderId=$orderId")

        repeat(20) { attempt ->
            delay(3000)
            val json = queryResult(orderId)
            val code = json.optInt("code", -1)
            if (code != 0) {
                throw Exception("查询失败：${json.optString("descInfo", "code=$code")}")
            }
            val content = json.optJSONObject("content")
            val state = content?.optInt("orderState", 0) ?: 0
            Log.d("IFlytek", "attempt=$attempt state=$state")

            when (state) {
                4 -> {
                    val text = parseTranscript(content!!)
                    return@withContext text.ifEmpty { "（识别结果为空，请确认录音有声音）" }
                }
                5 -> throw Exception("转写失败，请重试")
                else -> { /* 等待 */ }
            }
        }
        throw Exception("识别超时，请检查网络后重试")
    }
}
