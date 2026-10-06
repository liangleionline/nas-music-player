package com.lianglei.nasmusic.util

import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated logger for Feiniu NAS connection debugging.
 * Writes to /sdcard/Download/fn-nas.log so the user can pull it off and send back.
 */
object FnLogger {
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(msg: String) {
        android.util.Log.i("FnNAS", msg)
        append(msg)
    }

    fun error(msg: String, t: Throwable? = null) {
        android.util.Log.e("FnNAS", msg, t)
        append("ERROR: $msg" + (t?.let { "\n" + android.util.Log.getStackTraceString(it) } ?: ""))
    }

    fun request(method: String, url: String, body: String?, token: String?) {
        append("→ $method $url")
        if (!token.isNullOrEmpty()) append("  Authorization: ${token.take(12)}...")
        if (!body.isNullOrEmpty()) append("  Body: $body")
    }

    /** Call on app start: keep only the tail of the log to avoid unbounded growth. */
    fun rotateIfLarge(maxBytes: Long = 500_000L) {
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val f = File(dir, "fn-nas.log")
            if (f.exists() && f.length() > maxBytes) {
                f.writeText("=== rotated at ${tsFmt.format(Date())} ===\n")
            }
        } catch (_: Throwable) {}
    }

    fun response(code: Int, body: String) {
        append("← $code")
        append("  Body: ${body.take(2000)}")
    }

    private fun append(line: String) {
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, "fn-nas.log").appendText(tsFmt.format(Date()) + "  " + line + "\n")
        } catch (_: Throwable) {}
    }
}
