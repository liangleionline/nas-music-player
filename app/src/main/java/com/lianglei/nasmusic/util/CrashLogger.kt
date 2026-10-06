package com.lianglei.nasmusic.util

import android.content.Context
import android.util.Log
import com.lianglei.nasmusic.App
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes debug logs and uncaught-exception stack traces to /sdcard/Download
 * so the user can pull them off the phone and send them back for diagnosis.
 */
object CrashLogger {
    private const val TAG = "NasMusic"
    private const val LOG_FILE = "nas-music.log"
    private const val CRASH_FILE = "nas-music-crash.log"
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                writeCrash("Uncaught on ${thread.name}:\n$sw")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun log(msg: String) {
        Log.i(TAG, msg)
        append(LOG_FILE, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        append(LOG_FILE, "ERROR: $msg" + (t?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
    }

    private fun writeCrash(body: String) {
        append(CRASH_FILE, "===== CRASH @ ${tsFmt.format(Date())} =====\n$body\n")
    }

    @Synchronized
    private fun append(name: String, body: String) {
        try {
            val dir = App.logDir()
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            f.appendText(tsFmt.format(Date()) + "  " + body + "\n")
        } catch (_: Throwable) {
            // no storage permission yet; drop silently
        }
    }
}
