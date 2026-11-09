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
    val format: String = "",    // 文件格式（飞牛 audioSpec.format；本地 MIME 后缀）
    val addedAt: Long = 0,      // 入库时间（飞牛 track.createdAt，秒级 Unix 时间戳；本地无此值）
    val albumAddedAt: Long = 0  // 专辑入库时间（飞牛 album.createdAt，秒级 Unix 时间戳）
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

/**
 * 专辑身份键（专辑列表/专辑详情/艺术家详情的分组依据）。
 *
 * 飞牛同一张专辑里不同曲目可能各自带自己的封面（coverId 不同），若用封面 ID 哈希当专辑 ID，
 * 一张专辑会被按曲目/艺术家拆成多个专辑卡片。因此改为按**专辑名**归并（去首尾空格 + 忽略大小写），
 * 让"按专辑查看"回到"一张专辑 = 该专辑里的所有歌"的语义。
 * 专辑名为空（无专辑归属的单曲）时退回封面 ID 哈希，避免所有无名曲目挤成一张专辑。
 */
fun albumKeyOf(album: String, coverId: String): Long {
    val key = album.trim().lowercase()
    return if (key.isNotEmpty()) key.hashCode().toLong() else coverId.hashCode().toLong()
}
