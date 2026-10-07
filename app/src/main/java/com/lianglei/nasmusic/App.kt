package com.lianglei.nasmusic

import android.app.Application
import android.os.Environment
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.lianglei.nasmusic.data.FnApi
import com.lianglei.nasmusic.util.CrashLogger
import com.lianglei.nasmusic.util.FnLogger
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLogger.install(this)
        FnLogger.rotateIfLarge()
        com.lianglei.nasmusic.data.SourceManager.init(this)
        com.lianglei.nasmusic.player.PlayerManager.init(this)
        CrashLogger.log("App onCreate, versionName=1.2.2")
    }

    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request().newBuilder()
                if (FnApi.token.isNotEmpty()) {
                    req.header("Authorization", FnApi.token)
                }
                val resp = chain.proceed(req.build())
                CrashLogger.log("Coil: ${req.build().url} -> ${resp.code}")
                resp
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

        /** Directory where crash / debug logs are written: /sdcard/Download */
        fun logDir(): File =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }
}
