package com.lianglei.nasmusic.data

import android.content.Context
import android.content.SharedPreferences
import com.lianglei.nasmusic.util.CrashLogger
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
            CrashLogger.log("Restored Feiniu session: host=$fnHost, token=${savedToken.take(8)}")
        }
        if (savedSource == "FEINIU" && _fnConnected.value) {
            _current.value = MusicSource.FEINIU
            _songs.value = feiniuCache
            CrashLogger.log("Restored source: FEINIU, cache=${feiniuCache.size} songs")
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
                })
            }
            f.writeText(arr.toString())
            CrashLogger.log("Feiniu cache saved: ${feiniuCache.size} songs to ${f.absolutePath}")
        } catch (t: Throwable) { CrashLogger.e("saveFeiniuCache failed", t) }
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
                    coverId = o.optString("coverId")
                ))
            }
            feiniuCache = list
            CrashLogger.log("Feiniu cache loaded: ${list.size} songs")
        } catch (t: Throwable) { CrashLogger.e("loadFeiniuCache failed", t) }
    }

    fun switchTo(source: MusicSource) {
        if (_current.value == source) return
        _current.value = source
        CrashLogger.log("Source switched to $source")
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
            val repo = MusicRepository(ctx.applicationContext)
            localCache = repo.loadSongs()
            if (_current.value == MusicSource.LOCAL) _songs.value = localCache
            CrashLogger.log("Local songs loaded: ${localCache.size}")
        } catch (t: Throwable) {
            CrashLogger.e("Local scan failed", t)
        } finally {
            _loading.value = false
        }
    }

    suspend fun refreshFeiniu(onProgress: (String) -> Unit = {}) {
        if (!_fnConnected.value) {
            CrashLogger.log("refreshFeiniu called but not connected")
            return
        }
        // If we have cached data, show it immediately and refresh in background
        if (feiniuCache.isNotEmpty()) {
            _songs.value = feiniuCache
            CrashLogger.log("Showing Feiniu cache (${feiniuCache.size} songs), refreshing in background...")
        } else {
            _loading.value = true
        }
        try {
            onProgress("正在连接服务器...")
            feiniuCache = FnApi.fetchAllTracks(onProgress)
            _songs.value = feiniuCache
            saveFeiniuCache()
            CrashLogger.log("Feiniu tracks loaded: ${feiniuCache.size}")
            onProgress("正在获取歌单列表...")
            val pls = FnApi.fetchPlaylists()
            _playlists.value = pls
            CrashLogger.log("Feiniu playlists loaded: ${pls.size}")
            onProgress("完成！")
        } catch (t: Throwable) {
            CrashLogger.e("Feiniu fetch failed", t)
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
            CrashLogger.log("Playlist cache hit: $playlistGuid (${it.size} songs)")
            return it
        }
        _loading.value = true
        return try {
            val tracks = FnApi.fetchPlaylistTracks(playlistGuid)
            playlistCache[playlistGuid] = tracks
            CrashLogger.log("Playlist tracks loaded: ${tracks.size}")
            tracks
        } catch (t: Throwable) {
            CrashLogger.e("Playlist tracks fetch failed", t)
            emptyList()
        } finally {
            _loading.value = false
        }
    }
}
