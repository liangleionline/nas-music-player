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

    fun switchTo(source: MusicSource) {
        _current.value = source
        CrashLogger.log("Source switched to $source")
        // Trigger reload in the caller / ViewModel
    }

    suspend fun refreshLocal(ctx: Context) {
        _loading.value = true
        try {
            val repo = MusicRepository(ctx.applicationContext)
            _songs.value = repo.loadSongs()
            CrashLogger.log("Local songs loaded: ${_songs.value.size}")
        } catch (t: Throwable) {
            CrashLogger.e("Local scan failed", t)
        } finally {
            _loading.value = false
        }
    }

    suspend fun refreshFeiniu() {
        _loading.value = true
        try {
            _songs.value = FnApi.fetchAllTracks()
            CrashLogger.log("Feiniu tracks loaded: ${_songs.value.size}")
        } catch (t: Throwable) {
            CrashLogger.e("Feiniu fetch failed", t)
        } finally {
            _loading.value = false
        }
    }
}
