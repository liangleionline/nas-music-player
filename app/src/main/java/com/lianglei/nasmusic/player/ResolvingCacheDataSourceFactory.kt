package com.lianglei.nasmusic.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource

/**
 * 每次真的要读数据时才向 [CacheManager] 拿"当前"的 SimpleCache，而不是在 PlayerService.onCreate
 * 里取一次就固定下来。
 *
 * 为什么需要它：旧实现是 `CacheDataSource.Factory().setCache(CacheManager.cache(this))`，
 * 用户"清空音乐缓存"后（或有任何重建），播放器手里的 factory 仍指向那个旧实例；
 * 一旦旧实例被 release，后续播放会一直访问已关闭的 cache（Cache is closed），
 * 表现为点歌后无限 buffering、重试 5 次失败，必须彻底退出 APP 才恢复。
 * 现在每首歌构建数据源时都重新解析一次，清空缓存 / 换实例都不会再踩到过期引用。
 */
@UnstableApi
class ResolvingCacheDataSourceFactory(
    private val context: Context,
    private val upstream: DataSource.Factory
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        val cache = CacheManager.cache(context)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
    }
}
