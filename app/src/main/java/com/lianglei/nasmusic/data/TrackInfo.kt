package com.lianglei.nasmusic.data

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 歌曲详细信息（播放页"更多 → 歌曲信息"弹窗展示） */
data class TrackInfo(
    val title: String,
    val artist: String,
    val album: String,
    val source: String,       // 本地 / 飞牛NAS / 群晖NAS …
    val playCount: Int,       // 播放次数（StatsStore）
    val durationMs: Long,
    val bitrateBps: Int,      // 0 = 未知
    val sampleRateHz: Int,    // 0 = 未知
    val fileSizeBytes: Long,  // 0 = 未知
    val format: String,       // mp3 / flac …
    val directory: String     // 所在目录
) {
    val durationText: String get() = fmtDuration(durationMs)
    val bitrateText: String get() = if (bitrateBps > 0) "${bitrateBps / 1000} kbps" else "—"
    val sampleRateText: String get() = if (sampleRateHz > 0) "$sampleRateHz Hz" else "—"
    val sizeText: String get() = if (fileSizeBytes > 0) fmtSize(fileSizeBytes) else "—"
    val formatText: String get() = if (format.isNotEmpty()) format.uppercase() else "—"

    companion object {
        fun fmtDuration(ms: Long): String {
            val totalSec = ms / 1000
            val m = totalSec / 60
            val s = totalSec % 60
            return String.format("%d:%02d", m, s)
        }
        fun fmtSize(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024f / 1024f / 1024f)
            bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024f / 1024f)
            bytes >= 1024L -> String.format("%.0f KB", bytes / 1024f)
            else -> "$bytes B"
        }
    }
}

/**
 * 组装歌曲信息。
 * 飞牛端字段在拉取曲目时已解析进 Song（year/bitrate/sampleRate/fileSize/format 均来自 audioSpec）；
 * 本地端比特率/采样率用 MediaMetadataRetriever 懒读（首次打开信息弹窗时才读，避免扫描卡顿）。
 */
suspend fun trackInfoOf(song: Song, context: Context): TrackInfo = withContext(Dispatchers.IO) {
    val isNas = song.data.startsWith("http://") || song.data.startsWith("https://")
    val source = if (isNas) "飞牛NAS" else "本地"
    val playCount = StatsStore.playCount(song.id)

    if (isNas) {
        TrackInfo(
            title = song.title,
            artist = song.artist,
            album = song.album,
            source = source,
            playCount = playCount,
            durationMs = song.duration,
            bitrateBps = song.bitrate,
            sampleRateHz = song.sampleRate,
            fileSizeBytes = song.fileSize,
            format = song.format,
            directory = song.folder.substringBeforeLast('/')
        )
    } else {
        var bitrate = song.bitrate
        var sampleRate = song.sampleRate
        var size = song.fileSize
        var format = song.format
        var dir = song.folder
        try {
            val f = File(song.data)
            if (size <= 0L) size = f.length()
            if (format.isEmpty()) format = f.extension.uppercase()
            dir = f.parent ?: dir
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(song.data)
                bitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                sampleRate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
            } finally {
                try { mmr.release() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            // 本地文件信息读取失败时保留已解析值，不抛错
        }
        TrackInfo(
            title = song.title,
            artist = song.artist,
            album = song.album,
            source = source,
            playCount = playCount,
            durationMs = song.duration,
            bitrateBps = bitrate,
            sampleRateHz = sampleRate,
            fileSizeBytes = size,
            format = format,
            directory = dir
        )
    }
}
