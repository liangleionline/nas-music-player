package com.lianglei.nasmusic.data

import com.lianglei.nasmusic.util.CrashLogger
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.security.MessageDigest

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

    fun configure(host: String, useHttps: Boolean = false, port: Int = DEFAULT_PORT) {
        val scheme = if (useHttps) "https" else "http"
        baseUrl = "$scheme://$host:$port$API_PATH"
    }

    suspend fun login(host: String, username: String, password: String, deviceId: String): Result<String> {
        configure(host)
        return try {
            val body = JSONObject().apply {
                put("username", username)
                put("password", sha256Hex(password))
                put("deviceId", deviceId)
            }.toString()
            val resp = httpPost("$baseUrl/user/password-login", body, auth = false)
            val json = JSONObject(resp)
            if (json.optInt("code", -1) == 0) {
                val data = json.getJSONObject("data")
                token = data.getString("userToken")
                CrashLogger.log("Feiniu login success, token=${token.take(8)}...")
                Result.success(token)
            } else {
                Result.failure(Exception("Login failed: code=${json.optInt("code")} msg=${json.optString("msg")}"))
            }
        } catch (e: Exception) {
            CrashLogger.e("Feiniu login error", e)
            Result.failure(e)
        }
    }

    suspend fun fetchAllTracks(): List<Song> {
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
        return list
    }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun httpGet(url: String): String {
        val client = okhttp3.OkHttpClient()
        val req = okhttp3.Request.Builder().url(url).apply {
            if (token.isNotEmpty()) header("Authorization", "Bearer $token")
        }.build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun httpPost(url: String, body: String, auth: Boolean = true): String {
        val client = okhttp3.OkHttpClient()
        val mediaType = "application/json".toMediaType()
        val req = okhttp3.Request.Builder().url(url)
            .post(okhttp3.RequestBody.create(mediaType, body))
            .apply { if (auth && token.isNotEmpty()) header("Authorization", "Bearer $token") }
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }
}
