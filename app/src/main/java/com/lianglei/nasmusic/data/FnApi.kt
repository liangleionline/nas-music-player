package com.lianglei.nasmusic.data

import com.lianglei.nasmusic.util.FnLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Feiniu (fnOS) music API client.
 * Accepts user input like:
 *   - 192.168.1.100            (defaults to http, port 5666)
 *   - nas.example.com           (defaults to http, port 5666)
 *   - https://nas.example.com:244  (explicit scheme + port)
 * API base: <scheme>://<host>:<port>/music/api/v1/
 */
object FnApi {
    private const val DEFAULT_PORT = 5666
    private const val API_PATH = "/music/api/v1"

    @Volatile var baseUrl: String = ""
        private set
    @Volatile var token: String = ""
        private set

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Parse user input and configure base URL. Handles full URLs or bare hosts. */
    fun configure(input: String) {
        val trimmed = input.trim().trimEnd('/')
        val (scheme, parsedHost, port) = try {
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }
            val uri = URI(withScheme)
            Triple(uri.scheme ?: "http", uri.host ?: trimmed, if (uri.port > 0) uri.port else DEFAULT_PORT)
        } catch (e: Exception) {
            FnLogger.error("Failed to parse host: $input", e)
            Triple("http", trimmed, DEFAULT_PORT)
        }
        baseUrl = "$scheme://$parsedHost:$port$API_PATH"
        FnLogger.log("Configured baseUrl=$baseUrl (input=$input)")
    }

    suspend fun login(hostInput: String, username: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        configure(hostInput)
        FnLogger.log("=== login attempt ===")
        FnLogger.log("hostInput=$hostInput, username=$username, baseUrl=$baseUrl")
        try {
            val deviceId = UUID.randomUUID().toString()
            val bodyObj = JSONObject().apply {
                put("username", username)
                put("password", sha256Hex(password))
                put("deviceId", deviceId)
            }
            val bodyStr = bodyObj.toString()
            val url = "$baseUrl/user/password-login"
            FnLogger.request("POST", url, bodyStr, null)

            val mediaType = "application/json".toMediaType()
            val req = Request.Builder()
                .url(url)
                .post(bodyStr.toRequestBody(mediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                FnLogger.response(resp.code, text)
                val json = JSONObject(text)
                if (json.optInt("code", -1) == 0) {
                    val data = json.getJSONObject("data")
                    token = data.getString("userToken")
                    FnLogger.log("Login OK, token=${token.take(12)}...")
                    Result.success(token)
                } else {
                    val msg = json.optString("msg", "unknown error")
                    FnLogger.error("Login failed: code=${json.optInt("code")} msg=$msg")
                    Result.failure(Exception("登录失败: $msg"))
                }
            }
        } catch (e: Exception) {
            FnLogger.error("Login exception", e)
            Result.failure(e)
        }
    }

    suspend fun fetchAllTracks(): List<Song> = withContext(Dispatchers.IO) {
        FnLogger.log("=== fetchAllTracks ===")
        val list = mutableListOf<Song>()
        var page = 1
        while (true) {
            val url = "$baseUrl/track/list?page=$page&size=100&sort=createdAt,desc"
            FnLogger.request("GET", url, null, token)
            val resp = httpGet(url)
            FnLogger.response(if (resp.isNotEmpty()) 200 else 0, resp)
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
        FnLogger.log("Fetched ${list.size} tracks total")
        list
    }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun httpGet(url: String): String {
        val req = Request.Builder().url(url).apply {
            // Feiniu API expects raw token in Authorization header, NOT "Bearer <token>"
            if (token.isNotEmpty()) header("Authorization", token)
        }.build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }
}
