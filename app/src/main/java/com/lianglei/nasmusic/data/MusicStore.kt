package com.lianglei.nasmusic.data

import android.content.Context
import com.lianglei.nasmusic.util.CrashLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide in-memory music library so detail screens (artist/album/folder/playlist)
 * can read the already-scanned list without re-querying MediaStore.
 */
object MusicStore {
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    suspend fun refresh(ctx: Context) {
        val repo = MusicRepository(ctx.applicationContext)
        _songs.value = repo.loadSongs()
        CrashLogger.log("MusicStore refreshed: ${_songs.value.size} songs")
    }
}
