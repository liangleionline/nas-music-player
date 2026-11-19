package com.lianglei.nasmusic

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.lianglei.nasmusic.data.CoverPrefetcher
import com.lianglei.nasmusic.data.FnApi
import com.lianglei.nasmusic.player.PlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.OkHttpClient

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        com.lianglei.nasmusic.data.SourceManager.init(this)
        com.lianglei.nasmusic.data.StatsStore.init(this)
        com.lianglei.nasmusic.data.SortPrefs.init(this)
        PlayerManager.init(this)
        CoverPrefetcher.init(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            // v1.5.11：开屏就开始热封面，不再等"开始播放"才动手
            // （预取内部仍然只在 WiFi/有线时才真正发请求，见 CoverPrefetcher）
            kotlinx.coroutines.delay(3_000)
            CoverPrefetcher.start()
        }
        scope.launch {
            // 登录/切到飞牛音源后也补一次（此时曲库缓存才刚可用）
            com.lianglei.nasmusic.data.SourceManager.current.collect { src ->
                if (src == com.lianglei.nasmusic.data.MusicSource.FEINIU) CoverPrefetcher.start()
            }
        }
        scope.launch {
            // 播放中继续补：曲库有变动（新增专辑）时能把新封面也补上
            PlayerManager.isPlaying.collect { playing -> if (playing) CoverPrefetcher.start() }
        }
    }

    override fun newImageLoader(): ImageLoader {
        // v1.5.11：默认 OkHttp 的 Dispatcher 对同一域名只放 5 个并发请求，
        // 封面全部来自同一个 NAS 域名 → 列表里十几张封面要排队，看起来就是"一张张慢慢冒"。
        // 放宽到 8（预取固定占 3 路，界面仍留 5 路），局域网内足够。
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 8
        }
        val client = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .addInterceptor(Interceptor { chain ->
                val req = chain.request().newBuilder()
                if (FnApi.token.isNotEmpty()) {
                    req.header("Authorization", FnApi.token)
                }
                chain.proceed(req.build())
            })
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            // v1.5.15：封面磁盘缓存上限 512MB → 1GB。
            // 根因：Coil 的磁盘缓存存的是"封面原图字节"（不是请求里 160px 的解码尺寸），
            // 一万两千多张封面平均每张 50~60KB 就要 700MB 左右。512MB 顶满后预取器被判"预算用尽"提前收工，
            // 设置页就停在 8960/12267 不动了。
            .diskCache(
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(1024L * 1024 * 1024)
                    .build()
            )
            .crossfade(true)
            .build()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
