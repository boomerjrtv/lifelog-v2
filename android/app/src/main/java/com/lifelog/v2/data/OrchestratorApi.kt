package com.lifelog.v2.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

class OrchestratorApi(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "OrchestratorApi"
    }

    private val baseUrl: String get() = settingsRepository.serverUrl.trimEnd('/')
    private val apiKey: String get() = settingsRepository.apiKey

    suspend fun transcribe(pcm16: ByteArray, sampleRate: Int = 16000): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Wrap PCM16 as WAV
            val wav = pcm16ToWav(pcm16, sampleRate)
            val wavBody = wav.toRequestBody("audio/wav".toMediaType())
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("audio", "audio.wav", wavBody)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/stt")
                .post(multipart)
                .apply { if (apiKey.isNotBlank()) addHeader("X-API-Key", apiKey) }
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val text = json.getString("text")
                Log.i(TAG, "STT result: $text")
                Result.success(text)
            } else {
                Log.e(TAG, "STT error: ${response.code} $responseBody")
                Result.failure(Exception("STT error: ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "STT failed", e)
            Result.failure(e)
        }
    }

    suspend fun chat(text: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("text", text)
                put("session_id", "phone_session")
            }
            val body = jsonBody.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/chat/text")
                .post(body)
                .apply { if (apiKey.isNotBlank()) addHeader("X-API-Key", apiKey) }
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val reply = json.getString("text")
                Log.i(TAG, "Chat reply: $reply")
                Result.success(reply)
            } else {
                Log.e(TAG, "Chat error: ${response.code}")
                Result.failure(Exception("Chat error: ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chat failed", e)
            Result.failure(e)
        }
    }

    suspend fun chatWithAudio(text: String): Result<ByteArray?> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("text", text)
                put("session_id", "phone_session")
            }
            val body = jsonBody.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/chat")
                .post(body)
                .apply { if (apiKey.isNotBlank()) addHeader("X-API-Key", apiKey) }
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val contentType = response.header("Content-Type", "")
                if (contentType?.contains("audio") == true) {
                    val audio = response.body?.bytes()
                    val reply = response.header("X-Reply", "")
                    Log.i(TAG, "Chat audio reply: ${audio?.size} bytes, text: $reply")
                    Result.success(audio)
                } else {
                    val responseBody = response.body?.string() ?: ""
                    val json = JSONObject(responseBody)
                    val reply = json.getString("text")
                    Log.i(TAG, "Chat text reply: $reply")
                    // Return null audio but success — caller reads text separately
                    Result.success(null)
                }
            } else {
                Result.failure(Exception("Chat error: ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "ChatWithAudio failed", e)
            Result.failure(e)
        }
    }

    private fun pcm16ToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val totalSize = 36 + dataSize

        val out = ByteArrayOutputStream()
        // RIFF header
        out.write("RIFF".toByteArray())
        writeLE32(out, totalSize)
        out.write("WAVE".toByteArray())
        // fmt chunk
        out.write("fmt ".toByteArray())
        writeLE32(out, 16)
        writeLE16(out, 1) // PCM
        writeLE16(out, channels)
        writeLE32(out, sampleRate)
        writeLE32(out, byteRate)
        writeLE16(out, blockAlign)
        writeLE16(out, bitsPerSample)
        // data chunk
        out.write("data".toByteArray())
        writeLE32(out, dataSize)
        out.write(pcm)

        return out.toByteArray()
    }

    private fun writeLE16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun writeLE32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }
}
