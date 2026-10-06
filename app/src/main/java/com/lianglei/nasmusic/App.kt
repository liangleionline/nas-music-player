package com.lianglei.nasmusic

import android.app.Application
import android.os.Environment
import android.util.Log
import com.lianglei.nasmusic.util.CrashLogger
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLogger.install(this)
        CrashLogger.log("App onCreate, versionName=0.4.1")
    }

    companion object {
        lateinit var instance: App
            private set

        /** Directory where crash / debug logs are written: /sdcard/Download */
        fun logDir(): File =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }
}
