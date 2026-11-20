package com.lianglei.nasmusic.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
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
            Triple("http", trimmed, DEFAULT_PORT)
        }
        baseUrl = "$scheme://$parsedHost:$port$API_PATH"
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
            val bodyStr = bodyObj.toString()
            val url = "$baseUrl/user/password-login"

            val mediaType = "application/json".toMediaType()
            val req = Request.Builder()
                .url(url)
                .post(bodyStr.toRequestBody(mediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                val json = JSONObject(text)
                if (json.optInt("code", -1) == 0) {
                    val data = json.getJSONObject("data")
                    token = data.getString("userToken")
                    Result.success(token)
                } else {
                    val msg = json.optString("msg", "unknown error")
                    Result.failure(Exception("登录失败: $msg"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 一次曲库同步的结果：完整与否要能被上层看见，避免把"扫了一半"当成成功缓存。 */
    data class TrackScan(
        val songs: List<Song>,
        val total: Int,
        val skipped: Int,
        val aborted: Boolean,
        val error: String? = null
    ) {
        /** 是否取全了：被跳过的少数坏记录不算失败，中止或明显缺页才算 */
        val complete: Boolean get() = !aborted && (total == 0 || songs.size + skipped >= total)
    }

    /**
     * 拉取整库曲目（分页）。
     *
     * v1.5.22 起按"已消费行数"驱动分页并带降级：
     * 1. 单页失败先重试 3 次（退避），仍失败就把每页窗口 100 → 20 → 5 → 1 递减——
     *    深分页偶发超时/服务端报错时，小窗口通常能过去；
     * 2. 退化到每页 1 首还失败，说明是某一条记录本身有问题（服务端序列化报错），
     *    就跳过该条继续往下，最多跳 50 条，避免整个同步卡死在一页上；
     * 3. 所有失败原因都写进日志，不再是静默停在"正在获取第 N 页"。
     */
    suspend fun fetchAllTracks(
        onProgress: (String) -> Unit = {},
        onLog: (String) -> Unit = {}
    ): TrackScan = withContext(Dispatchers.IO) {
        onProgress("开始连接飞牛音乐服务器...")
        onLog("开始拉取曲库：baseUrl=$baseUrl")
        val songs = mutableListOf<Song>()
        var cursor = 0          // 已从服务端消费的行数（含被跳过的坏记录）
        var total = 0
        var size = 100
        var skipped = 0
        var aborted = false
        var lastError: String? = null

        while (true) {
            if (total > 0 && cursor >= total) break
            // page 是 1 基整页序号；只有 cursor 能被 size 整除时换算才精确
            if (size > 1 && cursor % size != 0) size = 1
            val page = cursor / size + 1
            onProgress(
                if (total > 0) "正在获取第 $page 页（每页 $size 首，已加载 ${songs.size}/$total 首）..."
                else "正在获取第 $page 页（每页 $size 首，已加载 ${songs.size} 首）..."
            )

            val json = try {
                fetchTrackPage(page, size, onLog)
            } catch (t: Throwable) {
                lastError = t.message?.take(160) ?: t.javaClass.simpleName
                onLog("页 $page(每页 $size) 三次尝试仍失败：${t.javaClass.simpleName}: ${t.message}")
                null
            }

            if (json == null) {
                when {
                    size > 20 -> {
                        size = 20
                        onLog("降级：每页 100 → 20")
                        onProgress("第 $page 页失败（$lastError），改为每页 20 首重试...")
                        continue
                    }
                    size > 5 -> {
                        size = 5
                        onLog("降级：每页 → 5")
                        onProgress("第 $page 页仍失败（$lastError），改为每页 5 首重试...")
                        continue
                    }
                    size > 1 -> {
                        size = 1
                        onLog("降级：每页 → 1（逐条）")
                        onProgress("第 $page 页仍失败（$lastError），改为每页 1 首逐条重试...")
                        continue
                    }
                    skipped < 50 -> {
                        skipped++
                        cursor++
                        onLog("跳过 cursor=$cursor 的记录（累计 $skipped）：$lastError")
                        onProgress("第 $page 条记录取不到（$lastError），跳过 1 条继续（累计 $skipped 条）")
                        continue
                    }
                    else -> {
                        aborted = true
                        onLog("终止：连续跳过 $skipped 条仍失败，$lastError")
                        onProgress("连续跳过 $skipped 条记录仍失败（$lastError），本次同步中止")
                        break
                    }
                }
            }

            val data = json.optJSONObject("data")
            if (data == null) {
                aborted = true
                lastError = "服务端未返回 data 字段"
                onProgress("第 $page 页无数据（$lastError），本次同步中止")
                break
            }
            val arr = data.optJSONArray("list")
            if (arr == null || arr.length() == 0) break

            for (i in 0 until arr.length()) {
                // 单条记录解析失败（字段缺失/类型异常）不能中断整次同步，计入跳过继续往下
                val t = arr.optJSONObject(i)
                if (t == null) {
                    skipped++
                    continue
                }
                val s = try {
                    parseTrack(t)
                } catch (e: Exception) {
                    skipped++
                    lastError = "第 ${cursor + i + 1} 条解析失败：${e.message?.take(80) ?: e.javaClass.simpleName}"
                    null
                } ?: continue
                songs.add(s)
                if (i % 20 == 0) onProgress("已加载 ${songs.size} 首: ${s.title} - ${s.artist}")
            }
            cursor += arr.length()
            data.optInt("total", 0).takeIf { it > 0 }?.let { total = it }
            onProgress("第 $page 页完成，共 ${songs.size}/$total 首")
            if (arr.length() < size) break        // 最后一页
        }

        onProgress(
            buildString {
                append("歌曲加载完成，共 ${songs.size} 首")
                if (total > 0) append("/$total")
                if (skipped > 0) append("，跳过 $skipped 条")
                if (aborted) append("，未取全")
            } + ")"
        )
        val result = TrackScan(songs, total, skipped, aborted, lastError)
        onLog(
            "结束：songs=${songs.size} total=$total skipped=$skipped aborted=$aborted " +
                "complete=${result.complete} lastError=${lastError ?: "-"}"
        )
        if (result.complete) onProgress("曲库同步完整。") else onProgress("曲库同步不完整：${lastError ?: "部分页未取到"}")
        result
    }

    /** 取一页歌曲列表：单页内重试 3 次（退避），仍失败抛出最后一次异常。全过程写日志。 */
    private fun fetchTrackPage(page: Int, size: Int, onLog: (String) -> Unit = {}): JSONObject {
        val url = "$baseUrl/track/list?page=$page&size=$size&sort=createdAt,desc"
        var last: Exception? = null
        repeat(3) { attempt ->
            val t0 = System.currentTimeMillis()
            try {
                val text = httpGet(url)
                val ms = System.currentTimeMillis() - t0
                onLog("GET page=$page size=$size → ${text.length}B ${ms}ms")
                val json = JSONObject(text)
                val code = json.optInt("code", -1)
                if (code != 0) {
                    val msg = json.optString("msg", "")
                    onLog("  响应 code=$code msg=$msg")
                    throw IOException("服务端 code=$code ${msg.take(80)}")
                }
                val data = json.optJSONObject("data")
                onLog("  ok total=${data?.optInt("total", 0) ?: 0} list=${data?.optJSONArray("list")?.length() ?: 0}")
                return json
            } catch (t: Exception) {
                last = t
                val ms = System.currentTimeMillis() - t0
                onLog("  第 ${attempt + 1} 次失败 ${ms}ms：${t.javaClass.simpleName}: ${t.message}")
                if (attempt < 2) {
                    try {
                        Thread.sleep(400L * (attempt + 1))
                    } catch (ignored: InterruptedException) {
                    }
                }
            }
        }
        throw last ?: IOException("未知错误")
    }

    /** 解析单个 track JSON → Song（fetchAllTracks / fetchPlaylistTracks 共用，字段口径一致）。
     *  容错：服务端个别记录缺 guid / 字段为 null 时不能让整次同步炸掉，尽量把能用的信息解析出来。 */
    private fun parseTrack(t: JSONObject): Song {
        val guid = t.optString("guid", "").let { if (it == "null") "" else it }
        val title = t.optString("title", "Unknown").let { if (it == "null") "Unknown" else it }
        val artist = try {
            t.optJSONArray("artists")?.let { a ->
                if (a.length() > 0) a.optJSONObject(0)?.optString("name", "") ?: "" else ""
            } ?: ""
        } catch (e: Exception) {
            ""
        }
        val album = t.optJSONObject("album")?.optString("name", "") ?: ""
        // 入库时间：track.createdAt / album.createdAt，均为秒级 Unix 时间戳（见 docs/fn-nas-api.md）
        val albumObj = t.optJSONObject("album")
        val addedAt = t.optLong("createdAt", 0L)
        val albumAddedAt = albumObj?.optLong("createdAt", 0L) ?: 0L
        val duration = t.optLong("duration", 0)
        val coverId = t.optString("coverId", "")
        val path = extractTrackPath(t)
        val spec = t.optJSONObject("audioSpec")
        val isFlac = spec?.optString("code", "")?.contains("flac", true) == true ||
                spec?.optString("format", "")?.contains("flac", true) == true
        // 没有 guid 也要能进曲库（只是不能播放）：用 路径/标题 生成一个稳定 id
        val idKey = if (guid.isNotEmpty()) guid else "track:${path}:$title:$album"
        return Song(
            id = idKey.hashCode().toLong(),
            title = title,
            artist = artist,
            album = album,
            albumId = albumKeyOf(album, coverId),
            duration = duration,
            data = if (guid.isNotEmpty()) "$baseUrl/track/stream?guid=$guid" else "",
            folder = if (path.isNotEmpty()) path else "飞牛NAS",
            isHighQuality = isFlac,
            coverId = coverId,
            year = t.optInt("year", 0),
            bitrate = spec?.optInt("bitrate", 0) ?: 0,
            sampleRate = spec?.optInt("sampleRate", 0) ?: 0,
            fileSize = spec?.optLong("size", 0L) ?: 0L,
            format = spec?.optString("format", "") ?: "",
            addedAt = addedAt,
            albumAddedAt = albumAddedAt,
            // 碟号 / 音轨号：服务端入库时已解析好标签（真实响应里可能为 null → optInt 取 0）
            discNo = t.optInt("discNo", 0),
            trackNo = t.optInt("trackNo", 0)
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
        val url = "$baseUrl/playlist/list?page=1&size=100"
        val resp = httpGet(url)
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
        result
    }

    fun coverUrl(coverId: String): String = "$baseUrl/static/cover?coverId=$coverId"

    suspend fun fetchPlaylistTracks(playlistGuid: String): List<Song> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Song>()
        var page = 1
        while (true) {
            val url = "$baseUrl/track/playlist-detail/list?playlistGUID=$playlistGuid&page=$page&size=100&sort=trackAddedAt,desc"
            val resp = httpGet(url)
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
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code} ${body.take(120).replace('\n', ' ')}")
            }
            if (body.isBlank()) throw IOException("HTTP ${resp.code} 空响应")
            return body
        }
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
                        body
                    }
                }
                if (bytes != null) return bytes
            } catch (e: Exception) {
                last = e
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
                    b
                }
            }
            if (bytes != null) return bytes
        } catch (e: Exception) {
        }
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
    null
}
