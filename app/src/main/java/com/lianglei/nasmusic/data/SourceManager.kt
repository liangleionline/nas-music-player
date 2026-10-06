package com.lianglei.nasmusic.data

import android.content.Context
import com.lianglei.nasmusic.util.CrashLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which music library the app is currently browsing. */
enum class MusicSource { LOCAL, FEINIU }

/**
 * Process-wide source switch. The UI reads [songs] and shows either
 * MediaStore local songs or Feiniu NAS tracks, depending on [current].
 */
object SourceManager {
    private val _current = MutableStateFlow(MusicSource.LOCAL)
    val current: StateFlow<MusicSource> = _current.asStateFlow()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _fnConnected = MutableStateFlow(false)
    val fnConnected: StateFlow<Boolean> = _fnConnected.asStateFlow()

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
        _fnConnected.value = false
        fnHost = ""
        fnUsername = ""
        switchTo(MusicSource.LOCAL)
    }

    // In-memory caches so switching sources back does not re-fetch.
    private var localCache: List<Song> = emptyList()
    private var feiniuCache: List<Song> = emptyList()

    fun switchTo(source: MusicSource) {
        if (_current.value == source) return
        _current.value = source
        CrashLogger.log("Source switched to $source")
        // Serve from cache immediately if available.
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
        } catch (t: Throwable) {
            CrashLogger.e("Feiniu fetch failed", t)
        } finally {
            _loading.value = false
        }
    }

    /** Call after a fresh login to force a re-fetch. */
    suspend fun refreshFeiniuForce() {
        feiniuCache = emptyList()
        refreshFeiniu()
    }
}
