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

    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Load cached Feiniu songs immediately so UI shows data instantly
        loadFeiniuCache()
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
        FnApi.restoreSession("", "")
        switchTo(MusicSource.LOCAL)
        save()
    }

    private var localCache: List<Song> = emptyList()
    private var feiniuCache: List<Song> = emptyList()
    private var appContext: Context? = null

    private fun cacheFile(): File? = appContext?.let { File(it.filesDir, "feiniu_cache.json") }

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
                list.add(Song(
                    id = o.optLong("id"), title = o.optString("title"),
                    artist = o.optString("artist"), album = o.optString("album"),
                    albumId = o.optLong("albumId"),
                    duration = o.optLong("duration"),
                    data = o.optString("data"), folder = o.optString("folder"),
                    isHighQuality = o.optBoolean("isHighQuality"),
                    coverId = o.optString("coverId"),
                    year = o.optInt("year"), bitrate = o.optInt("bitrate"),
                    sampleRate = o.optInt("sampleRate"), fileSize = o.optLong("fileSize"),
                    format = o.optString("format")
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

    suspend fun refreshFeiniu(onProgress: (String) -> Unit = {}) {
        if (!_fnConnected.value) {
            return
        }
        // If we have cached data, show it immediately and refresh in background
        if (feiniuCache.isNotEmpty()) {
            _songs.value = feiniuCache
        } else {
            _loading.value = true
        }
        try {
            onProgress("正在连接服务器...")
            feiniuCache = FnApi.fetchAllTracks(onProgress)
            _songs.value = feiniuCache
            saveFeiniuCache()
            onProgress("正在获取歌单列表...")
            val pls = FnApi.fetchPlaylists()
            _playlists.value = pls
            onProgress("完成！")
        } catch (t: Throwable) {
        } finally {
            _loading.value = false
        }
    }

    suspend fun refreshLocalForce(ctx: Context) {
        localCache = emptyList()
        refreshLocal(ctx)
    }

    suspend fun refreshFeiniuForce(onProgress: (String) -> Unit = {}) {
        feiniuCache = emptyList()
        _playlists.value = emptyList()
        refreshFeiniu(onProgress)
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
