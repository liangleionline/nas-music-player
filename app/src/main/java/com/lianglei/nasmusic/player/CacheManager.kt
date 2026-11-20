package com.lianglei.nasmusic.player

import android.content.Context
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 音乐缓存（Media3 SimpleCache，LRU）。
 *
 * 播放器流式拉取过的 NAS 音频会自动落盘；下次播放同一首命中缓存直接读本地，不再重新加载。
 * 上限由用户在设置页自定（单位 MB，默认 200MB），改动即时生效：
 * 淘汰器 [DynamicLruEvictor] 的上限是可变的，不需要重建 SimpleCache，也不会打断正在播放的音轨。
 *
 * 进程内单例；设置页可查询已用容量、清空缓存、调整上限。
 */
object CacheManager {
    private const val PREFS = "cache_prefs"
    private const val KEY_LIMIT_MB = "audio_limit_mb"

    const val DEFAULT_LIMIT_MB = 200
    const val MIN_LIMIT_MB = 50
    const val MAX_LIMIT_MB = 4096

    /** 当前生效的上限（MB） */
    @Volatile
    var limitMb: Int = DEFAULT_LIMIT_MB
        private set

    @Volatile
    private var evictor: DynamicLruEvictor? = null

    @Volatile
    private var simpleCache: SimpleCache? = null
    private val lock = Any()

    /** 应用启动时读一次用户设置的上限 */
    fun init(ctx: Context) {
        limitMb = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LIMIT_MB, DEFAULT_LIMIT_MB)
            .coerceIn(MIN_LIMIT_MB, MAX_LIMIT_MB)
    }

    /** 设置页改上限：持久化 + 就地调整淘汰器（超出部分由淘汰器在后续写入时按 LRU 清掉） */
    fun setLimitMb(ctx: Context, mb: Int) {
        val v = mb.coerceIn(MIN_LIMIT_MB, MAX_LIMIT_MB)
        limitMb = v
        evictor?.maxBytes = v.toLong() * 1024L * 1024L
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LIMIT_MB, v).apply()
    }

    fun limitBytes(): Long = limitMb.toLong() * 1024L * 1024L

    fun cache(context: Context): SimpleCache = simpleCache ?: synchronized(lock) {
        simpleCache ?: run {
            val ev = DynamicLruEvictor(limitBytes())
            evictor = ev
            SimpleCache(File(context.cacheDir, "audio_cache"), ev).also { simpleCache = it }
        }
    }

    /** 当前已用缓存字节数（SimpleCache 内部线程安全） */
    fun cacheSizeBytes(): Long = runCatching { simpleCache?.cacheSpace ?: 0L }.getOrDefault(0L)

    /** 上限调小后立刻按 LRU 清到新上限（在 IO 线程调用） */
    fun trimToLimit() {
        val c = simpleCache ?: return
        val ev = evictor ?: return
        ev.trimToLimit(c)
    }

    /**
     * 清空全部缓存（在 IO 线程调用）。
     *
     * v1.5.25 修正：这里**不再 release** SimpleCache 实例。
     * 原因：PlayerService 在 onCreate 时就把 `CacheManager.cache(this)` 塞进了 CacheDataSource.Factory，
     * 用户清缓存时 release 掉实例后，播放器手里那份引用变成"已关闭的 cache"，
     * 之后点任何歌都会卡在 buffering、自动重试 5 次仍失败（报 Cache is closed），
     * 只有彻底退出 APP（Service 重建、重新取实例）才能恢复。
     * 改为逐个删除 key：占用归零、正在使用的实例继续可用。
     *
     * 同时清掉 `lyrics/` 目录：内嵌歌词是按歌曲 URL 永久缓存的，不清就会一直显示改之前的旧词。
     */
    @androidx.media3.common.util.UnstableApi
    fun clear(context: Context) {
        val c = simpleCache
        if (c != null) {
            // 两轮：第一轮删得掉的删掉，正在播放的那条可能被占用，第二轮再试一次
            repeat(2) {
                val keys = runCatching { c.keys.toList() }.getOrNull().orEmpty()
                if (keys.isEmpty()) return@repeat
                keys.forEach { k -> runCatching { c.removeResource(k) } }
            }
            // 未被索引引用的临时分片（上次写入被打断留下的 .tmp）
            runCatching {
                File(context.cacheDir, "audio_cache").listFiles()?.forEach { f ->
                    if (f.name.endsWith(".tmp")) runCatching { f.delete() }
                }
            }
        } else {
            File(context.cacheDir, "audio_cache").deleteRecursively()
        }
        File(context.cacheDir, "lyrics").deleteRecursively()
    }
}

/**
 * 上限可运行时调整的 LRU 淘汰器。
 *
 * Media3 自带的 LeastRecentlyUsedCacheEvictor 上限是 final 字段，改上限只能重建 SimpleCache，
 * 而 SimpleCache 已被播放器持有，重建会打断正在播放的音轨。这里把上限做成可变字段：
 * 任何时候改 [maxBytes]，下一次写入 / 主动 [trimToLimit] 就会按最久未用顺序清到新上限。
 */
private class DynamicLruEvictor(@Volatile var maxBytes: Long) : CacheEvictor {
    /** 只记录"哪些 key 还在、谁最久没用"，实际占用一律以 cache.cacheSpace 为准；accessOrder 让 get 刷新顺序 */
    private val lru = LinkedHashMap<String, Boolean>(16, 0.75f, true)

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() {}

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != androidx.media3.common.C.LENGTH_UNSET.toLong()) trim(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        synchronized(lru) { lru[span.key] = true }
        trim(cache, 0L)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        // 注意：这里不碰 cache 的接口，避免和 SimpleCache 的锁形成交叉等待
        synchronized(lru) { lru.remove(span.key) }
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        // accessOrder 的 get 会把该 key 挪到队尾（最近使用）
        synchronized(lru) { lru[oldSpan.key] }
    }

    /** 主动清到当前上限（用户刚把上限改小时调用） */
    fun trimToLimit(cache: Cache) = trim(cache, 0L)

    /**
     * 空间不够就按最久未用的 key 逐个删。
     * 占用以 [Cache.getCacheSpace] 实时值为准：即使初始化时漏掉了历史上的 span，也不会算错。
     */
    private fun trim(cache: Cache, required: Long) {
        repeat(256) {
            val space = runCatching { cache.cacheSpace }.getOrDefault(0L)
            if (space + required <= maxBytes) return
            val victim = synchronized(lru) { lru.keys.firstOrNull() }
                ?: runCatching { cache.keys.firstOrNull() }.getOrNull()
                ?: return
            runCatching { cache.removeResource(victim) }
            // 删除没生效（异步或正被读取）就收手，等下一次写入再继续清
            val after = runCatching { cache.cacheSpace }.getOrDefault(space)
            if (after >= space) return
        }
    }
}
