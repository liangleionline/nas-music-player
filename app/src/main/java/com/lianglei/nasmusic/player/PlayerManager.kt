package com.lianglei.nasmusic.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.lianglei.nasmusic.data.Song
import com.lianglei.nasmusic.data.StatsStore
import com.lianglei.nasmusic.util.CrashLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    // ── 播放计数（统计页数据源）：同一首歌累计播放满 15 秒计一次 ──
    private var playedMsInTrack = 0L
    private var countedTrackId = -1L
    // tick 节流计数：每 60 次（约 30s）持久化一次播放位置
    private var tickCount = 0

    // ── NAS 断线自动重连 + 失败重试：最多自动重试 5 次，仍失败弹出询问 ──
    private const val MAX_RETRIES = 5
    private var retryCount = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _retryPrompt = MutableStateFlow(false)
    /** true = 自动重试 5 次已耗尽，等待用户在弹窗里选择"继续重试"或"切回本地" */
    val retryPrompt: StateFlow<Boolean> = _retryPrompt.asStateFlow()

    fun bind(controller: MediaController) {
        // 换绑前移除旧监听，避免重复绑定累积
        this.controller?.removeListener(playerListener)
        this.controller = controller
        controller.addListener(playerListener)
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            val idx = controller?.currentMediaItemIndex ?: return
            CrashLogger.log("onMediaItemTransition: idx=$idx, reason=$reason, title=${_queue.value.getOrNull(idx)?.title}")
            if (controller?.mediaItemCount ?: 0 > 0) {
                _currentIndex.value = idx
                _durationMs.value = controller?.duration?.coerceAtLeast(0) ?: 0
            }
            // 切歌后重新累计播放时长（同曲循环不触发 transition，靠 countedTrackId 防重复计数）
            playedMsInTrack = 0L
            // 换歌即保存播放进度（v1.4.82）：下次启动从这里继续播放列表
            saveState()
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
        override fun onPlayerError(error: PlaybackException) {
            CrashLogger.e("onPlayerError: code=${error.errorCodeName} msg=${error.message}", error)
            val c = controller ?: return
            val cur = _currentIndex.value
            val song = _queue.value.getOrNull(cur)
            val isNas = song?.data?.startsWith("http://") == true ||
                    song?.data?.startsWith("https://") == true
            if (isNas && retryCount < MAX_RETRIES) {
                retryCount++
                val n = retryCount
                CrashLogger.log("NAS playback failed, auto-retry $n/$MAX_RETRIES (title=${song?.title})")
                scope.launch {
                    delay(1500)
                    if (c.playbackState == Player.STATE_IDLE) c.prepare()
                    c.play()
                }
            } else {
                // 本地错误或 5 次自动重试已耗尽 → 弹窗询问用户
                retryCount = 0
                _retryPrompt.value = true
                CrashLogger.log("Playback failed (${if (isNas) "retries exhausted" else "local source"}), prompting user")
            }
        }
    }

    /** 弹窗选择"继续重试"：重置计数并立即重试一次 */
    fun retryNow() {
        _retryPrompt.value = false
        retryCount = 0
        val c = controller ?: return
        CrashLogger.log("User chose to retry")
        scope.launch {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    /** 弹窗选择"切回本地"：停止 NAS 播放，自动切换到本地音源 */
    fun switchToLocalAfterFailure() {
        _retryPrompt.value = false
        retryCount = 0
        CrashLogger.log("User chose to switch back to local source")
        com.lianglei.nasmusic.data.SourceManager.switchTo(com.lianglei.nasmusic.data.MusicSource.LOCAL)
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
        // v1.4.87 幂等（轻量）：只按 size + 起始曲目 id 判断，避免 12309 首歌全列表 equals 比较。
        val cur = _queue.value
        val sameQueue = cur.size == songs.size &&
                songs.isNotEmpty() && cur.firstOrNull()?.id == songs.first().id
        if (sameQueue && _currentIndex.value == startIndex) {
            com.lianglei.nasmusic.util.CrashLogger.log("playQueue: same queue+index, skip rebuild")
            if (c.playbackState != Player.STATE_READY) { c.prepare(); c.play() } else if (!c.isPlaying) { c.play() }
            return
        }
        _queue.value = songs
        com.lianglei.nasmusic.util.CrashLogger.log("playQueue: ${songs.size} songs, start=$startIndex, title=${songs.getOrNull(startIndex)?.title}, uri=${songs.getOrNull(startIndex)?.mediaUri}")
        val items = songs.map { mediaItemOf(it) }
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()
        applyModeToController()
        c.play()
        // 点歌即保存（v1.4.82）：下次启动从这首歌继续
        saveState()
    }

    private fun mediaItemOf(s: Song): MediaItem = MediaItem.Builder()
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

    /**
     * "下一首播放"（歌曲列表加号，v1.4.47）：
     * 把 [song] 放到当前播放曲目的下一首位置。
     * - 队列中已存在该歌曲（按 id 匹配）→ 先移除再插入到下一首位置（相当于立刻提到下一首）；
     * - 队列中不存在 → 直接插入到下一首位置；
     * - 当前无播放曲目（currentIndex < 0）→ 追加到队列末尾；
     * - v1.4.48：队列为空时添加第一首 → 自动开始播放。
     * 每次点击都执行此逻辑：最后点击的歌就是接下来最先播放的一首。
     * 返回：0 = 播放器未就绪（controller 为空）；1 = 新加入；2 = 已在队列中被提升；
     * 3 = 空队列添加第一首并已开始播放。
     */
    fun playNext(song: Song): Int {
        val c = controller ?: return 0
        val q = _queue.value.toMutableList()
        val emptyQueue = q.isEmpty()
        val cur = _currentIndex.value
        val existed = q.indexOfFirst { it.id == song.id }
        val target = if (cur in q.indices) cur + 1 else q.size
        if (existed >= 0) {
            if (existed == target) return 2 // 已经在下一首位置，无需操作
            q.removeAt(existed)
            val adjusted = if (existed < target) target - 1 else target
            q.add(adjusted, song)
            c.removeMediaItem(existed)
            c.addMediaItem(adjusted, mediaItemOf(song))
        } else {
            q.add(target, song)
            c.addMediaItem(target, mediaItemOf(song))
        }
        _queue.value = q
        // 被移除项在当前曲目之前时，Media3 会把当前索引前移一位，立即同步，避免 UI 短暂错位
        _currentIndex.value = c.currentMediaItemIndex
        if (emptyQueue) {
            // 空队列添加第一首：直接开始播放（先确保选中这首）
            c.prepare()
            if (c.currentMediaItemIndex < 0) c.seekTo(0, 0L)
            c.play()
            return 3
        }
        return if (existed >= 0) 2 else 1
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
        // 保存完整队列（songId 列表）：下次启动按 id 重建队列，精确恢复到当时正在播的列表
        val ids = org.json.JSONArray(_queue.value.map { it.id.toString() }).toString()
        p.edit()
            .putString("queueIds", ids)
            .putInt("index", c.currentMediaItemIndex)
            .putLong("position", c.currentPosition)
            .putString("source", com.lianglei.nasmusic.data.SourceManager.current.value.name)
            .apply()
    }

    fun restoreState(songs: List<Song>) {
        val p = prefs ?: return
        val src = p.getString("source", "")
        if (src != com.lianglei.nasmusic.data.SourceManager.current.value.name) return
        var idx = p.getInt("index", -1)
        val pos = p.getLong("position", 0L)
        val savedIds = p.getString("queueIds", "")?.takeIf { it.isNotBlank() }
        val restored: List<Song> = if (savedIds != null) {
            val ids = mutableListOf<String>()
            try {
                val arr = org.json.JSONArray(savedIds)
                for (i in 0 until arr.length()) ids.add(arr.getString(i))
            } catch (t: Throwable) {
                com.lianglei.nasmusic.util.CrashLogger.e("restore queueIds parse failed", t)
            }
            if (ids.isEmpty()) emptyList()
            else {
                // 按 songId 从当前曲库重建队列：仍在库里的保留，已删除的歌自动跳过
                val byId = songs.associateBy { it.id.toString() }
                ids.mapNotNull { byId[it] }
            }
        } else {
            // 旧版本格式（无 queueIds）：退回"全曲库 + index"
            songs
        }
        if (restored.isEmpty()) return
        idx = if (idx in restored.indices) idx else 0
        com.lianglei.nasmusic.util.CrashLogger.log("Restoring playback: queue=${restored.size}, idx=$idx, pos=$pos")
        playQueueSilent(restored, idx, pos)
    }

    private fun playQueueSilent(songs: List<Song>, startIndex: Int, position: Long) {
        val c = controller ?: return
        _queue.value = songs
        val items = songs.map { mediaItemOf(it) }
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
            CrashLogger.log("tick: index sync ${_currentIndex.value} -> ${c.currentMediaItemIndex}")
            _currentIndex.value = c.currentMediaItemIndex
        }
        // 每 ~30s 持久化一次当前播放位置（进程被杀时最多丢失 30s 进度）
        tickCount++
        if (tickCount % 60 == 0) saveState()
        // 播放计数：同一首歌累计播放满 15 秒计一次（统计页"按播放次数"数据源）
        if (c.isPlaying) {
            val idx = _currentIndex.value
            val song = _queue.value.getOrNull(idx)
            if (song != null) {
                playedMsInTrack += 500
                if (playedMsInTrack >= 15000L && countedTrackId != song.id) {
                    countedTrackId = song.id
                    StatsStore.recordPlay(song.id)
                    CrashLogger.log("Play recorded: id=${song.id} title=${song.title} total=${StatsStore.playCount(song.id)}")
                }
            }
        }
    }
}
