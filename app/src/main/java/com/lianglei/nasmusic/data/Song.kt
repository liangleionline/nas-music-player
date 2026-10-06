package com.lianglei.nasmusic.data

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val data: String,          // file path
    val folder: String,        // parent folder name
    val isHighQuality: Boolean // crude "HQ" badge: lossless codec
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

data class Album(
    val albumId: Long,
    val title: String,
    val artist: String,
    val songCount: Int,
    val year: Int
)

data class Artist(
    val name: String,
    val songCount: Int
)

data class Folder(
    val name: String,
    val path: String,
    val songCount: Int
)
