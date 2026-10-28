package com.lianglei.nasmusic.player

import android.content.Context
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 播放缓存（Media3 SimpleCache，LRU）。
 * 播放器流式拉取过的 NAS 音频会自动落盘；下次播放同一首命中缓存直接读本地，不再重新加载。
 * 容量上限约 200MB ≈ 20 首 320kbps 歌曲，超出后自动淘汰最久未用的。
 * 进程内单例；设置页可查询已用容量并一键清空。
 */
object CacheManager {
    /** 约 20 首 320kbps 歌曲的容量上限（LRU 自动淘汰最旧） */
    private const val MAX_CACHE_BYTES = 200L * 1024 * 1024

    @Volatile
    private var simpleCache: SimpleCache? = null
    private val lock = Any()

    fun cache(context: Context): SimpleCache = simpleCache ?: synchronized(lock) {
        simpleCache ?: SimpleCache(
            File(context.cacheDir, "audio_cache"),
            LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES)
        ).also { simpleCache = it }
    }

    /** 当前已用缓存字节数（SimpleCache 内部线程安全） */
    fun cacheSizeBytes(): Long = simpleCache?.cacheSpace ?: 0L

    /** 清空全部缓存（在 IO 线程调用）。释放实例后删除整个缓存目录，
     *  下次 cache() 会在同一位置重建全新 SimpleCache，可继续缓存新歌。 */
    fun clear(context: Context) {
        val c = simpleCache
        if (c != null) {
            c.release()
            simpleCache = null
        }
        File(context.cacheDir, "audio_cache").deleteRecursively()
    }
}
