package com.voicerecorder.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object IFlytekService {

    private const val API_KEY = "sk-avlzbdvmsqmkcyezwhtvygllpkzbnjpcswbttswiishevoto"
    private const val TRANSCRIPTION_URL = "https://api.siliconflow.cn/v1/audio/transcriptions"
    private const val CHAT_URL = "https://api.siliconflow.cn/v1/chat/completions"
    private const val BOUNDARY = "----FormBoundary7MA4YWxkTrZu0gW"

    // 语音转文字
    suspend fun transcribeAudio(audioFile: File): String = withContext(Dispatchers.IO) {
        val conn = URL(TRANSCRIPTION_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $API_KEY")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
        conn.connectTimeout = 60000
        conn.readTimeout = 60000

        DataOutputStream(conn.outputStream).use { out ->
            // file 字段
            out.writeBytes("--$BOUNDARY\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${audioFile.name}\"\r\n")
            out.writeBytes("Content-Type: audio/m4a\r\n\r\n")
            out.write(audioFile.readBytes())
            out.writeBytes("\r\n")
            // model 字段
            out.writeBytes("--$BOUNDARY\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
            out.writeBytes("FunAudioLLM/SenseVoiceSmall\r\n")
            out.writeBytes("--$BOUNDARY--\r\n")
        }

        val code = conn.responseCode
        val response = if (code == 200) conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                       else conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: "HTTP $code"
        Log.d("SiliconFlow", "Transcription response: $response")

        if (code != 200) throw Exception("识别失败：$response")
        JSONObject(response).optString("text", "").ifEmpty { "（识别结果为空）" }
    }

    // AI智能总结（调用DeepSeek大模型）
    suspend fun generateSummary(transcript: String): String = withContext(Dispatchers.IO) {
        val requestBody = JSONObject().apply {
            put("model", "deepseek-ai/DeepSeek-V3")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "你是一个专业的会议记录助手，请对用户提供的录音文字内容进行智能总结，包括：核心内容概述、关键要点、重要结论。用简洁清晰的中文输出，使用适当的分段和符号。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "请对以下录音内容进行AI总结：\n\n$transcript")
                })
            })
            put("max_tokens", 1000)
        }

        val conn = URL(CHAT_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $API_KEY")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 30000
        conn.readTimeout = 30000
        conn.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val response = if (code == 200) conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                       else conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: "HTTP $code"
        Log.d("SiliconFlow", "Summary response: $response")

        if (code != 200) throw Exception("总结失败：$response")
        JSONObject(response)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "")
            ?.ifEmpty { "（总结结果为空）" }
            ?: "（解析失败）"
    }
}
