package com.lianglei.nasmusic.player

import android.app.PendingIntent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lianglei.nasmusic.MainActivity
import com.lianglei.nasmusic.util.CrashLogger

class PlayerService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        CrashLogger.log("PlayerService onCreate")

        // content:// for local MediaStore, http(s) for Feiniu stream (auth injected per-request).
        // 外包一层 Media3 CacheDataSource（SimpleCache LRU，上限约 20 首）：
        // 流式拉取过的 NAS 音频自动落盘，二次播放命中缓存秒开，无需重新加载。
        val upstream = AuthHttpDataSourceFactory()
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(CacheManager.cache(this))
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(CacheManager.cache(this)))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val dataSourceFactory = DefaultDataSource.Factory(
            this,
            cacheDataSourceFactory
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)
            // v1.4.86: NAS 慢盘场景下 Media3 默认指数退避（3s→9s→27s…）会让用户干等近 1 分钟。
            // 改为固定 2s 快速重试 + 更多重试次数：首次中断/超时后能尽快续上，而不是越等越久。
            .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy() {
                override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long = 2_000L
                override fun getMinimumLoadableRetryCount(dataType: Int): Int = 10
            })

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                CrashLogger.e("Player error: ${error.errorCodeName}", error)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when (playbackState) {
                    androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                    androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                    androidx.media3.common.Player.STATE_READY -> "READY"
                    androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
                CrashLogger.log("Playback state: $stateName")
            }
        })

        val activityIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSession.Builder(this, player).setSessionActivity(pi).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.player?.release()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
