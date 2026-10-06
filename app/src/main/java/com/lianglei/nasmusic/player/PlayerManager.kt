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

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    fun bind(controller: MediaController) {
        this.controller = controller
        controller.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                _currentIndex.value = controller.currentMediaItemIndex
                _durationMs.value = controller.duration.coerceAtLeast(0)
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
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
        val c = controller ?: return
        _queue.value = songs
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
    fun seekTo(ms: Long) { controller?.seekTo(ms); _positionMs.value = ms }

    /** Call from a 500ms ticker in the UI. */
    fun tick() {
        val c = controller ?: return
        _positionMs.value = c.currentPosition
        _durationMs.value = c.duration.coerceAtLeast(0)
    }
}
