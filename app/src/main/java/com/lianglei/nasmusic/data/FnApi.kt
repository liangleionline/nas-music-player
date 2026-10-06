package com.lianglei.nasmusic.data

import android.content.Context
import com.lianglei.nasmusic.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Feiniu (fnOS) music API client.
 * Base URL: http://<host>:5666/music/api/v1/
 * Auth: POST user/password-login -> Bearer token
 */
object FnApi {
    private const val DEFAULT_PORT = 5666
    private const val API_PATH = "/music/api/v1"

    @Volatile var baseUrl: String = ""
        private set
    @Volatile var token: String = ""
        private set
    @Volatile var host: String = ""
        private set

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun configure(h: String, useHttps: Boolean = false, port: Int = DEFAULT_PORT) {
        host = h
        val scheme = if (useHttps) "https" else "http"
        baseUrl = "$scheme://$h:$port$API_PATH"
    }

    suspend fun login(hostInput: String, username: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        configure(hostInput)
        try {
            val deviceId = UUID.randomUUID().toString()
            val bodyObj = JSONObject().apply {
                put("username", username)
                put("password", sha256Hex(password))
                put("deviceId", deviceId)
            }
            val mediaType = "application/json".toMediaType()
            val req = Request.Builder()
                .url("$baseUrl/user/password-login")
                .post(bodyObj.toString().toRequestBody(mediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                CrashLogger.log("Feiniu login resp: ${resp.code} $text")
                val json = JSONObject(text)
                if (json.optInt("code", -1) == 0) {
                    val data = json.getJSONObject("data")
                    token = data.getString("userToken")
                    CrashLogger.log("Feiniu login OK, token=${token.take(10)}...")
                    Result.success(token)
                } else {
                    val msg = json.optString("msg", "unknown error")
                    CrashLogger.e("Feiniu login failed: code=${json.optInt("code")} msg=$msg")
                    Result.failure(Exception("登录失败: $msg"))
                }
            }
        } catch (e: Exception) {
            CrashLogger.e("Feiniu login exception", e)
            Result.failure(e)
        }
    }

    suspend fun fetchAllTracks(): List<Song> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Song>()
        var page = 1
        while (true) {
            val resp = httpGet("$baseUrl/track/list?page=$page&size=100&sort=createdAt,desc")
            val json = JSONObject(resp)
            if (json.optInt("code", -1) != 0) break
            val data = json.optJSONObject("data") ?: break
            val arr = data.optJSONArray("list") ?: break
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val guid = t.getString("guid")
                val title = t.optString("title", "Unknown")
                val artist = t.optJSONArray("artists")?.let { a ->
                    if (a.length() > 0) a.getJSONObject(0).optString("name", "") else ""
                } ?: ""
                val album = t.optJSONObject("album")?.optString("name", "") ?: ""
                val duration = t.optLong("duration", 0)
                val coverId = t.optString("coverId", "")
                list.add(Song(
                    id = guid.hashCode().toLong(),
                    title = title,
                    artist = artist,
                    album = album,
                    albumId = coverId.hashCode().toLong(),
                    duration = duration,
                    data = "$baseUrl/track/stream?guid=$guid",
                    folder = "飞牛NAS",
                    isHighQuality = t.optJSONObject("audioSpec")?.optString("code", "")?.contains("flac", true) == true
                ))
            }
            val total = data.optInt("total", 0)
            if (list.size >= total || arr.length() == 0) break
            page++
        }
        CrashLogger.log("Feiniu fetched ${list.size} tracks")
        list
    }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun httpGet(url: String): String {
        val req = Request.Builder().url(url).apply {
            if (token.isNotEmpty()) header("Authorization", "Bearer $token")
        }.build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }
}
