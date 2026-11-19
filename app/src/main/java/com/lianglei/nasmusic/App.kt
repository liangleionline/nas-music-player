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
        // v1.5.9：一开播就让封面预取跑起来（仅在 WiFi/有线网络下真正发请求，见 CoverPrefetcher）
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            PlayerManager.isPlaying.collect { playing -> if (playing) CoverPrefetcher.start() }
        }
    }

    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
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
            // v1.5.9：给封面磁盘缓存 512MB 上限。十二万首级的曲库封面也就几百 MB，
            // 预取过的封面之后都从本地读，列表滚动不再逐张等网络。
            .diskCache(
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(512L * 1024 * 1024)
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
