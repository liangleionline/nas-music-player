package com.lianglei.nasmusic.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 曲库同步日志（v1.5.23）：把每一次分页请求、耗时、返回码、字节数、异常全部记下来，
 * 扫描结束时落到 Download/NASMusic/ 下，方便把文件发给开发者定位。
 *
 * 只记录请求 URL / 状态 / 异常，不写 token。
 */
object ScanLog {
    private val sb = StringBuilder()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var began = false

    @Synchronized
    fun begin(reason: String) {
        sb.setLength(0)
        began = true
        sb.append("# NAS Music 曲库同步日志\n")
        sb.append("# 时间: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        sb.append("# 设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append("  Android ").append(Build.VERSION.SDK_INT).append('\n')
        sb.append("# 原因: ").append(reason).append('\n')
        sb.append("# baseUrl: ").append(FnApi.baseUrl).append('\n')
        sb.append("# token: ").append(if (FnApi.token.isEmpty()) "(空)" else "(已设置，长度 ${FnApi.token.length})").append('\n')
        sb.append("# ----------------------------------------------------\n")
    }

    @Synchronized
    fun line(msg: String) {
        if (!began) begin("未显式开始")
        sb.append(stamp.format(Date())).append(' ').append(msg).append('\n')
    }

    @Synchronized
    fun hasContent(): Boolean = sb.length > 0

    /**
     * 写盘：Android 10+ 走 MediaStore 存到公共 Download 目录（无需存储权限）；
     * 更低版本直接写公共目录，权限不足时退回应用专属目录（路径会返回给调用方）。
     * 返回给用户看的路径，失败返回 null。
     */
    @Synchronized
    fun dump(ctx: Context): String? {
        if (sb.isEmpty()) return null
        val name = "nas-music-scan-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log"
        val text = sb.toString()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NASMusic")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    ctx.contentResolver.update(uri, done, null, null)
                    "Download/NASMusic/$name"
                } else {
                    writeFallback(ctx, name, text)
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "NASMusic"
                )
                if (dir.exists() || dir.mkdirs()) {
                    File(dir, name).writeText(text)
                    "Download/NASMusic/$name"
                } else {
                    writeFallback(ctx, name, text)
                }
            }
        } catch (t: Throwable) {
            writeFallback(ctx, name, text)
        }
    }

    private fun writeFallback(ctx: Context, name: String, text: String): String? = try {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "logs").apply { mkdirs() }
        val f = File(dir, name)
        f.writeText(text)
        f.absolutePath
    } catch (t: Throwable) {
        null
    }
}
