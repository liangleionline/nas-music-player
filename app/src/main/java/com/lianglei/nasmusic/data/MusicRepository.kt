package com.lianglei.nasmusic.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.lianglei.nasmusic.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MusicRepository(private val ctx: Context) {

    suspend fun loadSongs(allowedFolders: Set<String> = emptySet(), blockedFolders: Set<String> = emptySet()): List<Song> = withContext(Dispatchers.IO) {
        // If no custom folders configured, show empty list
        if (allowedFolders.isEmpty()) {
            CrashLogger.log("No custom folders configured, returning empty list")
            return@withContext emptyList()
        }
        val list = mutableListOf<Song>()
        try {
            val collection = if (android.os.Build.VERSION.SDK_INT >= 33) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.MediaColumns.MIME_TYPE
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 5000"
            val sortOrder = MediaStore.Audio.Media.DEFAULT_SORT_ORDER
            ctx.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val title = c.getString(1) ?: "Unknown"
                    val artist = c.getString(2) ?: "<unknown>"
                    val album = c.getString(3) ?: "Unknown"
                    val albumId = c.getLong(4)
                    val duration = c.getLong(5)
                    val data = c.getString(6) ?: ""
                    val mime = c.getString(7) ?: ""
                    // Convert SAF URIs to path fragments for matching
                    fun safePath(uri: String): String {
                        // Extract path from content://...tree/primary%3AFoo%2FBar → Foo/Bar
                        return try {
                            uri.substringAfter("tree/")
                                .substringBefore('?')
                                .replace("%3A", "/")
                                .replace("%2F", "/")
                                .replace("%20", " ")
                                .replace("primary/", "")
                        } catch (e: Exception) { uri }
                    }
                    val allowedPaths = allowedFolders.map { safePath(it) }
                    val blockedPaths = blockedFolders.map { safePath(it) }
                    val inAllowed = allowedPaths.any { path ->
                        path.isNotEmpty() && (data.contains(path, ignoreCase = true) || data.endsWith(path, ignoreCase = true))
                    }
                    val inBlocked = blockedPaths.any { path ->
                        path.isNotEmpty() && data.contains(path, ignoreCase = true)
                    }
                    if (!inAllowed || inBlocked) continue
                    val folder = File(data).parentFile?.name ?: ""
                    val hq = mime.startsWith("audio/flac") ||
                            mime.startsWith("audio/wav") ||
                            mime.contains("ape") ||
                            mime.contains("alac")
                    list.add(Song(id, title, artist, album, albumId, duration, data, folder, hq))
                }
            }
            CrashLogger.log("MediaStore scan returned ${list.size} songs (allowed=$allowedFolders)")
        } catch (t: Throwable) {
            CrashLogger.e("MediaStore scan failed", t)
        }
        list
    }

    suspend fun buildAlbums(songs: List<Song>): List<Album> = withContext(Dispatchers.Default) {
        songs.groupBy { it.albumId }.map { (id, group) ->
            Album(id, group.first().album, group.first().artist, group.size, 0)
        }.sortedBy { it.title }
    }

    suspend fun buildArtists(songs: List<Song>): List<Artist> = withContext(Dispatchers.Default) {
        songs.groupBy { it.artist }.map { (name, g) -> Artist(name, g.size) }
            .sortedBy { it.name }
    }

    suspend fun buildFolders(songs: List<Song>): List<Folder> = withContext(Dispatchers.Default) {
        songs.groupBy { it.folder }.map { (name, g) -> Folder(name, g.first().data.substringBeforeLast('/'), g.size) }
            .sortedBy { it.name }
    }

    fun albumArtUri(albumId: Long): Uri =
        Uri.parse("content://media/external/audio/albumart/$albumId")
}
