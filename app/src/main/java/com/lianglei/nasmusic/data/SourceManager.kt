package com.lianglei.nasmusic.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class MusicSource { LOCAL, FEINIU }

object SourceManager {
    private const val PREFS = "nas_music_prefs"
    private const val KEY_SOURCE = "current_source"
    private const val KEY_FN_HOST = "fn_host"
    private const val KEY_FN_USER = "fn_username"
    private const val KEY_FN_BASE = "fn_base_url"
    private const val KEY_FN_TOKEN = "fn_token"
    private const val KEY_FN_LIB_UPDATED = "fn_library_updated_at"
    private const val PLAYLISTS_FILE = "feiniu_playlists.json"

    /**
     * 曲库缓存的"新鲜期"（v1.5.11）：冷启动时若缓存仍新，就**不再整库重扫 NAS**。
     *
     * 旧实现每次开屏都会把 100 条/页地重拉整个曲库，十几万首的库要连打上百个请求，
     * NAS 被占满的同时，界面上的封面请求只能排队——表现就是"刚打开没封面、像是在等什么"。
     * 需要立即同步时仍有入口：媒体库页「从服务器重新加载」、重新登录、切换音源后首次刷新。
     */
    private const val LIB_CACHE_TTL_MS = 24L * 60 * 60 * 1000

    private var prefs: SharedPreferences? = null

    /** 曲库缓存上次成功同步的时间（毫秒），0 表示没同步过 */
    @Volatile private var feiniuUpdatedAt: Long = 0L

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        feiniuUpdatedAt = prefs?.getLong(KEY_FN_LIB_UPDATED, 0L) ?: 0L
        // Load cached Feiniu songs immediately so UI shows data instantly
        loadFeiniuCache()
        // v1.5.11：歌单同样走缓存，避免打开"歌单"页先看到"暂无歌单"、等整库刷新完才冒出来
        loadPlaylistsCache()
        // Restore persisted state
        val savedSource = prefs?.getString(KEY_SOURCE, "LOCAL") ?: "LOCAL"
        val savedToken = prefs?.getString(KEY_FN_TOKEN, "") ?: ""
        val savedBase = prefs?.getString(KEY_FN_BASE, "") ?: ""
        fnHost = prefs?.getString(KEY_FN_HOST, "") ?: ""
        fnUsername = prefs?.getString(KEY_FN_USER, "") ?: ""
        if (savedToken.isNotEmpty() && savedBase.isNotEmpty()) {
            FnApi.restoreSession(savedBase, savedToken)
            _fnConnected.value = true
        }
        if (savedSource == "FEINIU" && _fnConnected.value) {
            _current.value = MusicSource.FEINIU
            _songs.value = feiniuCache
        }
    }

    private fun save() {
        prefs?.edit()?.apply {
            putString(KEY_SOURCE, _current.value.name)
            putString(KEY_FN_HOST, fnHost)
            putString(KEY_FN_USER, fnUsername)
            putString(KEY_FN_BASE, FnApi.baseUrl)
            putString(KEY_FN_TOKEN, FnApi.token)
        }?.apply()
    }

    private val _current = MutableStateFlow(MusicSource.LOCAL)
    val current: StateFlow<MusicSource> = _current.asStateFlow()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _fnConnected = MutableStateFlow(false)
    val fnConnected: StateFlow<Boolean> = _fnConnected.asStateFlow()

    private val _playlists = MutableStateFlow<List<FnApi.FnPlaylist>>(emptyList())
    val playlists: StateFlow<List<FnApi.FnPlaylist>> = _playlists.asStateFlow()

    /** 歌单是否正在从服务器拉取：为空 + 正在拉取时，界面要显示"加载中"而不是"暂无歌单" */
    private val _playlistsLoading = MutableStateFlow(false)
    val playlistsLoading: StateFlow<Boolean> = _playlistsLoading.asStateFlow()

    @Volatile var fnHost: String = ""
        private set
    @Volatile var fnUsername: String = ""
        private set

    fun setFnConnected(host: String, username: String) {
        fnHost = host
        fnUsername = username
        _fnConnected.value = true
        save()
    }

    fun disconnectFeiniu() {
        feiniuCache = emptyList()
        _playlists.value = emptyList()
        _fnConnected.value = false
        fnHost = ""
        fnUsername = ""
        feiniuUpdatedAt = 0L
        appContext?.let { ctx ->
            runCatching { File(ctx.filesDir, PLAYLISTS_FILE).delete() }
            runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_FN_LIB_UPDATED).apply() }
        }
        FnApi.restoreSession("", "")
        switchTo(MusicSource.LOCAL)
        save()
    }

    private var localCache: List<Song> = emptyList()
    private var feiniuCache: List<Song> = emptyList()
    private var appContext: Context? = null

    private fun cacheFile(): File? = appContext?.let { File(it.filesDir, "feiniu_cache.json") }

    private fun playlistsCacheFile(): File? = appContext?.let { File(it.filesDir, PLAYLISTS_FILE) }

    private fun savePlaylistsCache() {
        try {
            val f = playlistsCacheFile() ?: return
            val arr = JSONArray()
            _playlists.value.forEach { p ->
                arr.put(JSONObject().apply {
                    put("guid", p.guid); put("name", p.name)
                    put("coverId", p.coverId); put("trackCount", p.trackCount)
                })
            }
            f.writeText(arr.toString())
        } catch (t: Throwable) { }
    }

    private fun loadPlaylistsCache() {
        try {
            val f = playlistsCacheFile() ?: return
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            val list = mutableListOf<FnApi.FnPlaylist>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val guid = o.optString("guid")
                if (guid.isEmpty()) continue
                list.add(FnApi.FnPlaylist(
                    guid = guid,
                    name = o.optString("name", "未命名"),
                    coverId = o.optString("coverId", ""),
                    trackCount = o.optInt("trackCount", 0)
                ))
            }
            _playlists.value = list
        } catch (t: Throwable) { }
    }

    private fun saveFeiniuCache() {
        try {
            val f = cacheFile() ?: return
            val arr = JSONArray()
            feiniuCache.forEach { s ->
                arr.put(JSONObject().apply {
                    put("id", s.id); put("title", s.title); put("artist", s.artist)
                    put("album", s.album); put("albumId", s.albumId); put("folder", s.folder)
                    put("duration", s.duration); put("data", s.data)
                    put("coverId", s.coverId); put("isHighQuality", s.isHighQuality)
                    put("year", s.year); put("bitrate", s.bitrate); put("sampleRate", s.sampleRate)
                    put("fileSize", s.fileSize); put("format", s.format)
                    put("addedAt", s.addedAt); put("albumAddedAt", s.albumAddedAt)
                    put("discNo", s.discNo); put("trackNo", s.trackNo)
                })
            }
            f.writeText(arr.toString())
        } catch (t: Throwable) { }
    }

    private fun loadFeiniuCache() {
        try {
            val f = cacheFile() ?: return
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            val list = mutableListOf<Song>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val albumName = o.optString("album")
                val coverIdStr = o.optString("coverId")
                list.add(Song(
                    id = o.optLong("id"), title = o.optString("title"),
                    artist = o.optString("artist"), album = albumName,
                    // 重算专辑身份：旧缓存里存的是"封面 ID 哈希"，会让同一张专辑按曲目拆开；
                    // 按专辑名重算后，升级后无需重新扫库也能立刻合并
                    albumId = albumKeyOf(albumName, coverIdStr),
                    duration = o.optLong("duration"),
                    data = o.optString("data"), folder = o.optString("folder"),
                    isHighQuality = o.optBoolean("isHighQuality"),
                    coverId = o.optString("coverId"),
                    year = o.optInt("year"), bitrate = o.optInt("bitrate"),
                    sampleRate = o.optInt("sampleRate"), fileSize = o.optLong("fileSize"),
                    format = o.optString("format"),
                    addedAt = o.optLong("addedAt"), albumAddedAt = o.optLong("albumAddedAt"),
                    discNo = o.optInt("discNo"), trackNo = o.optInt("trackNo")
                ))
            }
            feiniuCache = list
        } catch (t: Throwable) { }
    }

    fun switchTo(source: MusicSource) {
        if (_current.value == source) return
        _current.value = source
        _songs.value = when (source) {
            MusicSource.LOCAL -> localCache
            MusicSource.FEINIU -> feiniuCache
        }
        save()
    }

    suspend fun refreshLocal(ctx: Context) {
        if (_current.value == MusicSource.LOCAL && localCache.isNotEmpty()) {
            _songs.value = localCache
            return
        }
        _loading.value = true
        try {
            val scanPrefs = ctx.getSharedPreferences("scan_prefs", Context.MODE_PRIVATE)
            val allowed = scanPrefs.getStringSet("custom_folders", emptySet()) ?: emptySet()
            val blocked = scanPrefs.getStringSet("blocked_folders", emptySet()) ?: emptySet()
            val repo = MusicRepository(ctx.applicationContext)
            localCache = repo.loadSongs(allowed, blocked)
            if (_current.value == MusicSource.LOCAL) _songs.value = localCache
        } catch (t: Throwable) {
        } finally {
            _loading.value = false
        }
    }

    suspend fun refreshFeiniu(onProgress: (String) -> Unit = {}, force: Boolean = false) {
        if (!_fnConnected.value) {
            return
        }
        // 有缓存就先上屏，绝不因为网络慢让界面空着
        if (feiniuCache.isNotEmpty()) {
            _songs.value = feiniuCache
        } else {
            _loading.value = true
        }

        // v1.5.11：缓存还新鲜时不再整库重扫。
        // 旧实现每次冷启动都会 100 条/页地重拉整个曲库（十几万首要上百个请求），
        // 把 NAS 占满的同时界面上所有封面请求都在排队，于是"刚打开没封面、像是在等什么"。
        val cacheFresh = !force && feiniuCache.isNotEmpty() &&
                (System.currentTimeMillis() - feiniuUpdatedAt) in 0 until LIB_CACHE_TTL_MS
        if (cacheFresh) {
            // 歌单只有一个请求、且是独立页面，缺了就补一次（不能等整库扫描完再拉）
            if (_playlists.value.isEmpty()) {
                _playlistsLoading.value = true
                try {
                    val pls = FnApi.fetchPlaylists()
                    if (pls.isNotEmpty()) {
                        _playlists.value = pls
                        savePlaylistsCache()
                    }
                } catch (t: Throwable) {
                } finally {
                    _playlistsLoading.value = false
                }
            }
            return
        }

        try {
            onProgress("正在连接服务器...")
            // 先拿歌单：一次请求就够，没必要排在整库扫描后面（旧顺序是歌单最后才拉）
            _playlistsLoading.value = true
            runCatching { FnApi.fetchPlaylists() }.onSuccess { pls ->
                if (pls.isNotEmpty()) {
                    _playlists.value = pls
                    savePlaylistsCache()
                }
            }
            _playlistsLoading.value = false

            val scan = FnApi.fetchAllTracks(onProgress)
            feiniuCache = scan.songs
            _songs.value = feiniuCache
            if (scan.complete) {
                saveFeiniuCache()
                // 记录本次成功同步时间，下次冷启动据此判断能不能直接用缓存
                feiniuUpdatedAt = System.currentTimeMillis()
                prefs?.edit()?.putLong(KEY_FN_LIB_UPDATED, feiniuUpdatedAt)?.apply()
                if (scan.skipped > 0) {
                    onProgress("完成！共 ${scan.songs.size} 首（跳过 ${scan.skipped} 条无法解析的记录）")
                } else {
                    onProgress("完成！")
                }
            } else {
                // 没取全就不落长期缓存：否则 24h 内会一直用这份残缺曲库，用户以为已经同步好了
                onProgress("本次只同步到 ${scan.songs.size} 首（应为 ${scan.total} 首），暂不写入缓存，下次启动会重新扫描")
            }
        } catch (t: Throwable) {
            onProgress("同步失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            _loading.value = false
            _playlistsLoading.value = false
        }
    }

    suspend fun refreshLocalForce(ctx: Context) {
        localCache = emptyList()
        refreshLocal(ctx)
    }

    suspend fun refreshFeiniuForce(onProgress: (String) -> Unit = {}) {
        feiniuCache = emptyList()
        _playlists.value = emptyList()
        feiniuUpdatedAt = 0L
        refreshFeiniu(onProgress, force = true)
    }

    private val playlistCache = mutableMapOf<String, List<Song>>()

    suspend fun fetchPlaylistTracks(playlistGuid: String): List<Song> {
        playlistCache[playlistGuid]?.let {
            return it
        }
        _loading.value = true
        return try {
            val tracks = FnApi.fetchPlaylistTracks(playlistGuid)
            playlistCache[playlistGuid] = tracks
            tracks
        } catch (t: Throwable) {
            emptyList()
        } finally {
            _loading.value = false
        }
    }
}
