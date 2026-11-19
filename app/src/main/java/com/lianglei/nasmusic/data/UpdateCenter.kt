package com.lianglei.nasmusic.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.lianglei.nasmusic.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 检查更新（v1.5.17）。
 *
 * 数据源就是本仓库的最新 Release：`GET api.github.com/.../releases/latest`，
 * 拿 `tag_name` 与本机 `versionName` 比大小，取资产里那个 `.apk` 的直链下载。
 * - APP 每次冷启动会自动查一次（失败静默，不打扰使用）
 * - 设置页「检查更新」按钮手动查，无论有没有更新都会给一句提示
 * - 有更新时弹窗，用户点「立即更新」才下载，下完直接拉起系统安装器
 */
object UpdateCenter {
    private const val API = "https://api.github.com/repos/liangleionline/nas-music-player/releases/latest"
    private const val UA = "NAS-Music-Android"

    data class Info(
        val version: String,
        val apkUrl: String,
        val sizeBytes: Long,
        val notes: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 非空表示有待更新版本（弹窗数据） */
    private val _available = MutableStateFlow<Info?>(null)
    val available: StateFlow<Info?> = _available.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /** 一次性提示文案（已是最新 / 检查失败 / 下载失败…），由 UI 弹 Toast 后清空 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 下载进度 0..100；null 表示当前没在下载 */
    private val _percent = MutableStateFlow<Int?>(null)
    val percent: StateFlow<Int?> = _percent.asStateFlow()

    @Volatile
    private var downloadedApk: File? = null

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    fun clearMessage() {
        _message.value = null
    }

    fun dismiss() {
        if (_percent.value == null) _available.value = null
    }

    /** 冷启动自动检查：失败不提示 */
    fun checkOnLaunch(ctx: Context) = check(ctx, manual = false)

    /** 设置页按钮：手动检查，给结果提示 */
    fun check(ctx: Context, manual: Boolean = true) {
        if (_checking.value) return
        _checking.value = true
        scope.launch {
            val latest = runCatching { fetchLatest() }.getOrNull()
            _checking.value = false
            when {
                latest == null -> if (manual) _message.value = "检查更新失败，请检查网络后重试"
                isNewer(latest.version, currentVersion()) -> {
                    downloadedApk = null
                    _available.value = latest
                }
                else -> if (manual) _message.value = "已是最新版本（v${currentVersion()}）"
            }
        }
    }

    /** 下载最新 APK 并拉起系统安装器 */
    fun downloadAndInstall(ctx: Context, info: Info) {
        if (_percent.value != null) return
        _percent.value = 0
        val appCtx = ctx.applicationContext
        scope.launch {
            val ok = runCatching { download(appCtx, info) }.getOrDefault(false)
            _percent.value = null
            if (!ok) {
                _message.value = "下载失败，请稍后重试"
                return@launch
            }
            install(appCtx)
        }
    }

    private fun fetchLatest(): Info? {
        val request = Request.Builder()
            .url(API)
            .header("User-Agent", UA)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v").trim()
            if (tag.isBlank()) return null
            var url = ""
            var size = 0L
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        url = a.optString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
            }
            if (url.isBlank()) return null
            return Info(tag, url, size, json.optString("body", ""))
        }
    }

    private fun download(ctx: Context, info: Info): Boolean {
        val dir = File(ctx.filesDir, "update").apply { mkdirs() }
        val target = File(dir, "NAS-Music-v${info.version}.apk")
        if (target.exists() && target.length() > 0L &&
            (info.sizeBytes <= 0L || target.length() == info.sizeBytes)
        ) {
            downloadedApk = target
            return true
        }
        val request = Request.Builder()
            .url(info.apkUrl)
            .header("User-Agent", UA)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val body = resp.body ?: return false
            val total = if (body.contentLength() > 0) body.contentLength() else info.sizeBytes
            val tmp = File(dir, "download.tmp")
            tmp.delete()
            var read = 0L
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0L) {
                            _percent.value = ((read * 100) / total).toInt().coerceIn(0, 99)
                        }
                    }
                }
            }
            if (tmp.length() <= 0L) {
                tmp.delete()
                return false
            }
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
        if (!target.exists() || target.length() <= 0L) return false
        downloadedApk = target
        return true
    }

    private fun install(ctx: Context) {
        val apk = downloadedApk ?: return
        if (!apk.exists()) {
            _message.value = "安装包已丢失，请重新下载"
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        }.getOrNull()
        if (uri == null) {
            _message.value = "无法准备安装包"
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }.onFailure {
            _message.value = "无法拉起安装器，请在系统设置中允许本应用安装应用"
        }
    }

    /** 按点分段比较版本号，任何一段更大就算新版本 */
    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split('.').map { it.toIntOrNull() ?: 0 }
        val c = current.split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(l.size, c.size)
        for (i in 0 until n) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
