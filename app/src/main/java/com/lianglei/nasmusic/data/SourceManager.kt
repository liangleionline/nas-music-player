package com.lianglei.nasmusic.data

import android.content.Context
import com.lianglei.nasmusic.util.CrashLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which music library the app is currently browsing. */
enum class MusicSource { LOCAL, FEINIU }

object SourceManager {
    private val _current = MutableStateFlow(MusicSource.LOCAL)
    val current: StateFlow<MusicSource> = _current.asStateFlow()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _fnConnected = MutableStateFlow(false)
    val fnConnected: StateFlow<Boolean> = _fnConnected.asStateFlow()

    // Feiniu playlists
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
    }

    fun disconnectFeiniu() {
        feiniuCache = emptyList()
        _playlists.value = emptyList()
        _fnConnected.value = false
        fnHost = ""
        fnUsername = ""
        switchTo(MusicSource.LOCAL)
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
        _loading.value = true
        try {
            feiniuCache = FnApi.fetchAllTracks()
            _songs.value = feiniuCache
            CrashLogger.log("Feiniu tracks loaded: ${feiniuCache.size}")
            // Also fetch playlists
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

    /** Fetch tracks for a specific playlist. Does NOT overwrite global songs. */
    suspend fun fetchPlaylistTracks(playlistGuid: String): List<Song> {
        _loading.value = true
        return try {
            val tracks = FnApi.fetchPlaylistTracks(playlistGuid)
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
