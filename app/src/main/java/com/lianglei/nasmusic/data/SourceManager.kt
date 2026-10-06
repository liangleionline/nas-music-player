package com.lianglei.nasmusic.data

import android.content.Context
import android.content.SharedPreferences
import com.lianglei.nasmusic.util.CrashLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
            CrashLogger.log("Restored source: FEINIU")
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

    suspend fun refreshFeiniu() {
        if (feiniuCache.isNotEmpty()) {
            _songs.value = feiniuCache
            return
        }
        if (!_fnConnected.value) {
            CrashLogger.log("refreshFeiniu called but not connected")
            return
        }
        _loading.value = true
        try {
            feiniuCache = FnApi.fetchAllTracks()
            _songs.value = feiniuCache
            CrashLogger.log("Feiniu tracks loaded: ${feiniuCache.size}")
            val pls = FnApi.fetchPlaylists()
            _playlists.value = pls
            CrashLogger.log("Feiniu playlists loaded: ${pls.size}")
        } catch (t: Throwable) {
            CrashLogger.e("Feiniu fetch failed", t)
        } finally {
            _loading.value = false
        }
    }

    suspend fun refreshFeiniuForce() {
        feiniuCache = emptyList()
        _playlists.value = emptyList()
        refreshFeiniu()
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
