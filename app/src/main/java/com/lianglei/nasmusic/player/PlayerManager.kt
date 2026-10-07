package com.lianglei.nasmusic.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.lianglei.nasmusic.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide observable player state. The Activity wires the MediaController
 * into [bind], and all Compose screens read from the exposed flows.
 */
object PlayerManager {
    private var controller: MediaController? = null

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    fun bind(controller: MediaController) {
        this.controller = controller
        controller.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val idx = controller.currentMediaItemIndex
                com.lianglei.nasmusic.util.CrashLogger.log("onMediaItemTransition: idx=$idx, reason=$reason, title=${_queue.value.getOrNull(idx)?.title}")
                _currentIndex.value = idx
                _durationMs.value = controller.duration.coerceAtLeast(0)
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                _buffering.value = playbackState == Player.STATE_BUFFERING
            }
            override fun onPositionDiscontinuity(
                oldPos: Player.PositionInfo, newPos: Player.PositionInfo, reason: Int
            ) {
                _positionMs.value = controller.currentPosition
                _durationMs.value = controller.duration.coerceAtLeast(0)
            }
        })
    }

    fun playQueue(songs: List<Song>, startIndex: Int) {
        val c = controller ?: run {
            com.lianglei.nasmusic.util.CrashLogger.e("playQueue called but controller is null")
            return
        }
        _queue.value = songs
        com.lianglei.nasmusic.util.CrashLogger.log("playQueue: ${songs.size} songs, start=$startIndex, title=${songs.getOrNull(startIndex)?.title}, uri=${songs.getOrNull(startIndex)?.mediaUri}")
        val items = songs.map { s ->
            MediaItem.Builder()
                .setUri(s.mediaUri)
                .setMediaId(s.id.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(s.title)
                        .setArtist(s.artist)
                        .setAlbumTitle(s.album)
                        .build()
                )
                .build()
        }
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()
        c.play()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE ||
                c.playbackState == Player.STATE_ENDED) c.prepare()
            c.play()
        }
    }

    fun next() = controller?.seekToNext().let {}
    fun prev() = controller?.seekToPrevious().let {}
    fun seekTo(ms: Long) { controller?.seekTo(ms); _positionMs.value = ms; saveState() }

    fun removeFromQueue(index: Int) {
        controller?.removeMediaItem(index)
        val newList = _queue.value.toMutableList()
        if (index in newList.indices) newList.removeAt(index)
        _queue.value = newList
    }

    fun clearQueue() {
        controller?.stop()
        controller?.clearMediaItems()
        _queue.value = emptyList()
        _currentIndex.value = -1
    }

    private var prefs: android.content.SharedPreferences? = null
    fun init(ctx: android.content.Context) {
        prefs = ctx.getSharedPreferences("player_state", android.content.Context.MODE_PRIVATE)
    }

    fun saveState() {
        val p = prefs ?: return
        val c = controller ?: return
        p.edit()
            .putInt("index", c.currentMediaItemIndex)
            .putLong("position", c.currentPosition)
            .putString("source", com.lianglei.nasmusic.data.SourceManager.current.value.name)
            .apply()
    }

    fun restoreState(songs: List<Song>) {
        val p = prefs ?: return
        val idx = p.getInt("index", -1)
        val pos = p.getLong("position", 0L)
        val src = p.getString("source", "")
        if (idx >= 0 && idx < songs.size && src == com.lianglei.nasmusic.data.SourceManager.current.value.name) {
            com.lianglei.nasmusic.util.CrashLogger.log("Restoring playback: idx=$idx, pos=$pos")
            playQueueSilent(songs, idx, pos)
        }
    }

    private fun playQueueSilent(songs: List<Song>, startIndex: Int, position: Long) {
        val c = controller ?: return
        _queue.value = songs
        val items = songs.map { s ->
            MediaItem.Builder().setUri(s.mediaUri).setMediaId(s.id.toString())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).build())
                .build()
        }
        c.setMediaItems(items, startIndex, position)
        c.prepare()
        // Don't auto-play, just restore position
    }

    /** Call from a 500ms ticker in the UI. */
    fun tick() {
        val c = controller ?: return
        _positionMs.value = c.currentPosition
        _durationMs.value = c.duration.coerceAtLeast(0)
        // Sync current index in case onMediaItemTransition was missed
        if (c.currentMediaItemIndex != _currentIndex.value) {
            com.lianglei.nasmusic.util.CrashLogger.log("tick: index sync ${_currentIndex.value} -> ${c.currentMediaItemIndex}")
            _currentIndex.value = c.currentMediaItemIndex
        }
    }
}
