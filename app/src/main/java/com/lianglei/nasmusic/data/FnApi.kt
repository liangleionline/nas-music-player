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

    /** Restore a saved session after process restart. */
    fun restoreSession(base: String, tok: String) {
        baseUrl = base
        token = tok
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
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

    suspend fun fetchAllTracks(onProgress: (String) -> Unit = {}): List<Song> = withContext(Dispatchers.IO) {
        FnLogger.log("=== fetchAllTracks (app 1.4.50 build 131) ===")
        onProgress("开始连接飞牛音乐服务器...")
        val list = mutableListOf<Song>()
        var page = 1
        while (true) {
            val url = "$baseUrl/track/list?page=$page&size=100&sort=createdAt,desc"
            onProgress("正在获取第 $page 页歌曲列表...")
            FnLogger.request("GET", url, null, token)
            val resp = httpGet(url)
            FnLogger.response(if (resp.isNotEmpty()) 200 else 0, resp)
            val json = JSONObject(resp)
            if (json.optInt("code", -1) != 0) break
            val data = json.optJSONObject("data") ?: break
            val arr = data.optJSONArray("list") ?: break
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                if (i == 0) FnLogger.log("track path probe: path='${extractTrackPath(t)}' keys=${t.keys().asSequence().toList().joinToString(",")}")
                val s = parseTrack(t)
                list.add(s)
                if (i % 20 == 0) onProgress("已加载 ${list.size} 首: ${s.title} - ${s.artist}")
            }
            val total = data.optInt("total", 0)
            onProgress("第 $page 页完成，共 ${list.size}/$total 首")
            if (list.size >= total || arr.length() == 0) break
            page++
        }
        onProgress("歌曲加载完成，共 ${list.size} 首")
        FnLogger.log("Fetched ${list.size} tracks total")
        list
    }

    /** 解析单个 track JSON → Song（fetchAllTracks / fetchPlaylistTracks 共用，字段口径一致） */
    private fun parseTrack(t: JSONObject): Song {
        val guid = t.getString("guid")
        val title = t.optString("title", "Unknown")
        val artist = t.optJSONArray("artists")?.let { a ->
            if (a.length() > 0) a.getJSONObject(0).optString("name", "") else ""
        } ?: ""
        val album = t.optJSONObject("album")?.optString("name", "") ?: ""
        val duration = t.optLong("duration", 0)
        val coverId = t.optString("coverId", "")
        val path = extractTrackPath(t)
        val spec = t.optJSONObject("audioSpec")
        val isFlac = spec?.optString("code", "")?.contains("flac", true) == true ||
                spec?.optString("format", "")?.contains("flac", true) == true
        return Song(
            id = guid.hashCode().toLong(),
            title = title,
            artist = artist,
            album = album,
            albumId = coverId.hashCode().toLong(),
            duration = duration,
            data = "$baseUrl/track/stream?guid=$guid",
            folder = if (path.isNotEmpty()) path else "飞牛NAS",
            isHighQuality = isFlac,
            coverId = coverId,
            year = t.optInt("year", 0),
            bitrate = spec?.optInt("bitrate", 0) ?: 0,
            sampleRate = spec?.optInt("sampleRate", 0) ?: 0,
            fileSize = spec?.optLong("size", 0L) ?: 0L,
            format = spec?.optString("format", "") ?: ""
        )
    }

    /** 探测 track 对象里的文件路径字段（feature/fn-folder-browser 分支）。
     * 优先级：① audioSpec.path（真实文件系统路径，已从真实服务器响应确认）；
     * ② 常见字段名；③ 扫描所有字段中"含 / 且不像 URL 的字符串"；④ 从 url 字段剥出路径。
     * 命中后统一剥离 NAS 系统前缀（/vol1/{用户}/Resources/Media/Music/），
     * 让文件夹树直接以音乐库内的分类目录为根。返回空串表示服务器未提供路径。 */
    private fun extractTrackPath(t: JSONObject): String {
        // ① audioSpec.path
        val spec = t.optJSONObject("audioSpec")
        val specPath = spec?.optString("path", "")?.trim()
        if (!specPath.isNullOrEmpty()) return relPathOf(specPath)
        // ② 常见字段名
        for (k in listOf("path", "relativePath", "sourcePath", "filePath", "audioPath", "dirPath")) {
            val v = t.optString(k, "").trim()
            if (v.isNotEmpty()) return relPathOf(v)
        }
        // ③ 扫描所有字段值：字符串且含 '/'、非 URL、非时间戳/编号
        for (k in t.keys().asSequence().toList()) {
            val v = t.opt(k)
            if (v is String) {
                val s = v.trim()
                if (s.contains("/") && !s.startsWith("http") && !s.startsWith("ftp") && s.length in 5..500) {
                    FnLogger.log("track path probe: matched key='$k' value='$s'")
                    return relPathOf(s)
                }
            }
        }
        // ④ 从 url 里剥出路径（必须是真实 http(s) URL 且带 / 路径段）
        val url = t.optString("url", "")
        if (url.startsWith("http") && url.contains("/")) {
            val noQuery = url.substringBefore("?")
            val hostless = noQuery.substringAfter("://", "")
            if (hostless.isNotEmpty()) {
                val pathPart = hostless.substringAfter("/", "")
                if (pathPart.isNotEmpty()) {
                    FnLogger.log("track path probe: derived from url '$url' -> '/$pathPart'")
                    return "/$pathPart"
                }
            }
        }
        return ""
    }

    /** 剥离 NAS 系统前缀，保留音乐库内相对路径（如 "1.中文/歌手 - 歌名.mp3"）。 */
    private fun relPathOf(p: String): String {
        val marker = "/Resources/Media/Music/"
        val idx = p.indexOf(marker)
        return if (idx >= 0) p.substring(idx + marker.length) else p.trim('/')
    }

    data class FnPlaylist(val guid: String, val name: String, val coverId: String, val trackCount: Int)

    suspend fun fetchPlaylists(): List<FnPlaylist> = withContext(Dispatchers.IO) {
        FnLogger.log("=== fetchPlaylists ===")
        val url = "$baseUrl/playlist/list?page=1&size=100"
        FnLogger.request("GET", url, null, token)
        val resp = httpGet(url)
        FnLogger.response(if (resp.isNotEmpty()) 200 else 0, resp)
        val json = JSONObject(resp)
        if (json.optInt("code", -1) != 0) return@withContext emptyList()
        val arr = json.optJSONObject("data")?.optJSONArray("list") ?: return@withContext emptyList()
        val result = mutableListOf<FnPlaylist>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            result.add(FnPlaylist(
                guid = p.optString("guid", ""),
                name = p.optString("name", "未命名"),
                coverId = p.optString("coverId", ""),
                trackCount = p.optInt("trackCount", 0)
            ))
        }
        FnLogger.log("Fetched ${result.size} playlists")
        result
    }

    fun coverUrl(coverId: String): String = "$baseUrl/static/cover?coverId=$coverId"

    suspend fun fetchPlaylistTracks(playlistGuid: String): List<Song> = withContext(Dispatchers.IO) {
        FnLogger.log("=== fetchPlaylistTracks: $playlistGuid ===")
        val list = mutableListOf<Song>()
        var page = 1
        while (true) {
            val url = "$baseUrl/track/playlist-detail/list?playlistGUID=$playlistGuid&page=$page&size=100&sort=trackAddedAt,desc"
            FnLogger.request("GET", url, null, token)
            val resp = httpGet(url)
            FnLogger.response(if (resp.isNotEmpty()) 200 else 0, resp)
            val json = JSONObject(resp)
            if (json.optInt("code", -1) != 0) break
            val data = json.optJSONObject("data") ?: break
            val arr = data.optJSONArray("list") ?: break
            for (i in 0 until arr.length()) {
                list.add(parseTrack(arr.getJSONObject(i)))
            }
            val total = data.optInt("total", 0)
            if (list.size >= total || arr.length() == 0) break
            page++
        }
        FnLogger.log("Playlist tracks fetched: ${list.size}")
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

    /**
     * HTTP Range 请求读取指定字节区间（用于读取歌曲文件头部/尾部标签区解析内嵌歌词）。
     * start/end 为包含边界；start 传负数（如 -1048576）表示 suffix range 读文件末尾 |start| 字节。
     * 重要：某些服务器忽略 Range 头直接返回整个文件。若全量下载，10MB 级文件在远程 NAS 上极易
     * 触发 30s readTimeout 导致"歌词时有时无"。因此无论响应是 206 还是 200，一律最多只读取
     * 前 4MB 即停止（ID3/FLAC 标签区在头部；M4A 由调用方用尾部请求兜底）。
     * Range 请求失败时退化为普通 GET（同样限读 4MB）；全部失败返回 null（记录日志便于排查）。
     */
    suspend fun fetchRange(url: String, start: Long, end: Long): ByteArray? {
        var last: Exception? = null
        repeat(2) { attempt ->
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val range = if (start < 0) "bytes=$start" else "bytes=$start-$end"
                    val req = Request.Builder().url(url)
                        .header("Range", range)
                        .apply {
                            if (token.isNotEmpty()) header("Authorization", token)
                        }
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val code = resp.code
                        val body = if (resp.isSuccessful && resp.body != null) readLimited(resp.body!!, 4L * 1024 * 1024) else null
                        FnLogger.log("fetchRange attempt=${attempt + 1} code=$code req='$range' bytes=${body?.size ?: 0}")
                        body
                    }
                }
                if (bytes != null) return bytes
            } catch (e: Exception) {
                last = e
                FnLogger.error("fetchRange attempt=${attempt + 1} failed", e)
            }
        }
        // 兜底：Range 不被支持（如 416）时退化为普通 GET，同样限读前 4MB（ID3/FLAC 标签区在头部）
        try {
            val bytes = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(url).apply {
                    if (token.isNotEmpty()) header("Authorization", token)
                }.build()
                client.newCall(req).execute().use { resp ->
                    val code = resp.code
                    val b = if (resp.isSuccessful && resp.body != null) readLimited(resp.body!!, 4L * 1024 * 1024) else null
                    FnLogger.log("fetchRange fallback GET code=$code bytes=${b?.size ?: 0}")
                    b
                }
            }
            if (bytes != null) return bytes
        } catch (e: Exception) {
            FnLogger.error("fetchRange fallback GET failed", e)
        }
        FnLogger.error("fetchRange giving up ($start-$end)", last ?: Exception("no body"))
        return null
    }
}

/** 从响应体最多读取 max 字节即返回，防止服务器忽略 Range 头全量返回时下载整个文件拖垮超时 */
private fun readLimited(body: okhttp3.ResponseBody, max: Long): ByteArray? = try {
    val source = body.source()
    val buf = okio.Buffer()
    var got = 0L
    while (got < max) {
        val n = source.read(buf, minOf(max - got, 16L * 1024))
        if (n == -1L) break
        got += n
    }
    buf.readByteArray()
} catch (e: Exception) {
    FnLogger.error("readLimited failed (max=$max)", e)
    null
}
