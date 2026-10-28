package com.lianglei.nasmusic.data

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val data: String,          // file path or stream URL
    val folder: String,         // parent folder name
    val isHighQuality: Boolean, // crude "HQ" badge: lossless codec
    val coverId: String = "",    // Feiniu cover ID (empty for local)
    val year: Int = 0,          // 发行年份（飞牛 track.year；本地 MediaStore YEAR）
    val bitrate: Int = 0,       // 比特率 bps（飞牛 audioSpec.bitrate；本地懒读）
    val sampleRate: Int = 0,    // 采样率 Hz（飞牛 audioSpec.sampleRate；本地懒读）
    val fileSize: Long = 0,     // 文件大小字节（飞牛 audioSpec.size；本地 MediaStore SIZE）
    val format: String = ""     // 文件格式（飞牛 audioSpec.format；本地 MIME 后缀）
) {
    val mediaUri: Uri
        get() = if (data.startsWith("http://") || data.startsWith("https://")) {
            Uri.parse(data)
        } else {
            Uri.withAppendedPath(
                Uri.parse("content://media/external/audio/media"), id.toString()
            )
        }
}
