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
enum class PlayMode { SEQUENTIAL, REPEAT_ALL, REPEAT_ONE, SHUFFLE }

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

    // 播放模式：顺序播放 → 列表循环 → 单曲循环 → 随机播放（点随机按钮循环切换）
    private val _playMode = MutableStateFlow(PlayMode.REPEAT_ALL)
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    fun bind(controller: MediaController) {
        // 换绑前移除旧监听，避免重复绑定累积
        this.controller?.removeListener(playerListener)
        this.controller = controller
        controller.addListener(playerListener)
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            val idx = controller?.currentMediaItemIndex ?: return
            com.lianglei.nasmusic.util.CrashLogger.log("onMediaItemTransition: idx=$idx, reason=$reason, title=${_queue.value.getOrNull(idx)?.title}")
            if (controller?.mediaItemCount ?: 0 > 0) {
                _currentIndex.value = idx
                _durationMs.value = controller?.duration?.coerceAtLeast(0) ?: 0
            }
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
            val c = controller ?: return
            _positionMs.value = c.currentPosition
            _durationMs.value = c.duration.coerceAtLeast(0)
        }
    }

    /** Activity.onStop 时调用：释放引用，避免对已释放 controller 操作导致按钮失灵/进度归零 */
    fun unbind() {
        controller?.removeListener(playerListener)
        controller = null
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
        applyModeToController()
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

    /** 随机播放按钮：顺序播放 → 列表循环 → 单曲循环 → 随机播放，循环切换 */
    fun cyclePlayMode() {
        _playMode.value = when (_playMode.value) {
            PlayMode.SEQUENTIAL -> PlayMode.REPEAT_ALL
            PlayMode.REPEAT_ALL -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.SEQUENTIAL
        }
        applyModeToController()
    }

    private fun applyModeToController() {
        val c = controller ?: return
        when (_playMode.value) {
            PlayMode.SEQUENTIAL -> { c.repeatMode = Player.REPEAT_MODE_OFF; c.shuffleModeEnabled = false }
            PlayMode.REPEAT_ALL -> { c.repeatMode = Player.REPEAT_MODE_ALL; c.shuffleModeEnabled = false }
            PlayMode.REPEAT_ONE -> { c.repeatMode = Player.REPEAT_MODE_ONE; c.shuffleModeEnabled = false }
            PlayMode.SHUFFLE -> { c.repeatMode = Player.REPEAT_MODE_ALL; c.shuffleModeEnabled = true }
        }
    }

    /** 定时关闭到点：停止播放（配合 UI 层退出应用） */
    fun stopPlayback() {
        val c = controller ?: return
        c.pause()
    }

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
        applyModeToController()
        // Don't auto-play, just restore position
    }

    /** Call from a 500ms ticker in the UI. */
    fun tick() {
        val c = controller ?: return
        _positionMs.value = c.currentPosition
        _durationMs.value = c.duration.coerceAtLeast(0)
        // Sync current index in case onMediaItemTransition was missed
        // Only sync if controller actually has items (avoids resetting to 0 on rebind when service was killed)
        if (c.mediaItemCount > 0 && c.currentMediaItemIndex != _currentIndex.value) {
            com.lianglei.nasmusic.util.CrashLogger.log("tick: index sync ${_currentIndex.value} -> ${c.currentMediaItemIndex}")
            _currentIndex.value = c.currentMediaItemIndex
        }
    }
}
