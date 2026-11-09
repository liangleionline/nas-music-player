package com.lianglei.nasmusic

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.lianglei.nasmusic.data.FnApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        com.lianglei.nasmusic.data.SourceManager.init(this)
        com.lianglei.nasmusic.data.StatsStore.init(this)
        com.lianglei.nasmusic.data.SortPrefs.init(this)
        com.lianglei.nasmusic.player.PlayerManager.init(this)
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
            .crossfade(true)
            .build()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
