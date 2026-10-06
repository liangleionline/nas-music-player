package com.lianglei.nasmusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lianglei.nasmusic.data.Song

private fun artUri(albumId: Long) = "content://media/external/audio/albumart/$albumId"

/** Reusable song row matching the Salt Player list style. */
@Composable
fun SongRow(song: Song, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = if (song.coverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(song.coverId) else artUri(song.albumId),
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.isHighQuality) {
                    Box(Modifier.background(Color(0xFFE8DCC8), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp)) {
                        Text("HQ", color = Color(0xFF8B6F47), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text("${song.artist} - ${song.album}", color = Color.Gray, maxLines = 1, fontSize = 12.sp)
            }
        }
        Icon(Icons.Filled.Add, "add", tint = Color.Gray, modifier = Modifier.padding(8.dp))
        Icon(Icons.Filled.MoreVert, "more", tint = Color.Gray, modifier = Modifier.padding(8.dp))
    }
}

/** Song list header row: shuffle icon + count, sort icon, filter icon. */
@Composable
fun SongListHeader(count: Int) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Shuffle, null, tint = Color.Gray)
        Spacer(Modifier.width(12.dp))
        Text("$count", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.Sort, null, tint = Color.Gray)
        Spacer(Modifier.width(20.dp))
        Icon(Icons.Filled.FilterList, null, tint = Color.Gray)
    }
}

/** Artist detail page: circular avatar + artist name + songs + albums by this artist. */
@Composable
fun ArtistDetailScreen(artistName: String, songs: List<Song>, onPlay: (Int) -> Unit, onOpenAlbum: (Long) -> Unit = {}) {
    val artistSongs = remember(songs, artistName) { songs.filter { it.artist == artistName } }
    val artistAlbums = remember(artistSongs) { artistSongs.groupBy { it.albumId }.entries }
    val coverId = artistSongs.firstOrNull()?.coverId ?: ""
    val coverModel = if (coverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(coverId) else artUri(artistSongs.firstOrNull()?.albumId ?: 0L)

    LazyColumn(Modifier.fillMaxSize()) {
        item { Spacer(Modifier.height(40.dp)) }
        item {
            AsyncImage(
                model = coverModel, contentDescription = null,
                modifier = Modifier.fillMaxWidth().wrapContentSize(Alignment.Center)
                    .size(180.dp).clip(CircleShape)
            )
        }
        item {
            Text(
                artistName, Modifier.fillMaxWidth().padding(top = 16.dp),
                fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shuffle, null, tint = Color.Gray)
                Spacer(Modifier.width(12.dp))
                Text("${artistSongs.size}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                Text("折叠", color = Color.Gray, fontSize = 14.sp)
            }
        }
        items(artistSongs.size) { i ->
            SongRow(artistSongs[i]) { onPlay(songs.indexOf(artistSongs[i])) }
        }
        item {
            Text("专辑", Modifier.padding(16.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        items(artistAlbums.size) { i ->
            val (albumId, group) = artistAlbums.elementAt(i)
            val albumCoverId = group.firstOrNull()?.coverId ?: ""
            val albumCover = if (albumCoverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(albumCoverId) else artUri(albumId)
            Row(
                Modifier.fillMaxWidth().clickable { onOpenAlbum(albumId) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = albumCover, contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(group.first().album, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1)
                    Text("${group.first().artist} · ${group.size} 首", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
    }
}

/** Album detail page: cover + title/artist/sample rate + stats + songs + participating artists. */
@Composable
fun AlbumDetailScreen(albumId: Long, songs: List<Song>, onPlay: (Int) -> Unit) {
    val albumSongs = remember(songs, albumId) { songs.filter { it.albumId == albumId } }
    val totalMs = remember(albumSongs) { albumSongs.sumOf { it.duration } }
    val first = albumSongs.firstOrNull()
    val artists = remember(albumSongs) { albumSongs.groupBy { it.artist }.entries }
    val coverId = first?.coverId ?: ""
    val coverModel = if (coverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(coverId) else artUri(albumId)

    LazyColumn(Modifier.fillMaxSize().background(Color(0xFFF5F2EC))) {
        item { Spacer(Modifier.height(24.dp)) }
        item {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = coverModel, contentDescription = null,
                    modifier = Modifier.size(150.dp).clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.width(20.dp))
                Column {
                    Text(first?.album ?: "", fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 2)
                    Spacer(Modifier.height(6.dp))
                    Text(first?.artist ?: "", color = Color.Gray, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("44100 Hz", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.SpaceAround) {
                StatColumn("${albumSongs.size}", "歌曲")
                StatColumn(fmtDuration(totalMs), "时长")
                StatColumn("—", "年份")
            }
        }
        item { Divider(Modifier.padding(horizontal = 20.dp)) }
        items(albumSongs.size) { i ->
            Row(
                Modifier.fillMaxWidth().clickable { onPlay(songs.indexOf(albumSongs[i])) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${i + 1}", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.width(28.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(albumSongs[i].title, fontSize = 15.sp, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (albumSongs[i].isHighQuality) {
                            Box(Modifier.background(Color(0xFFE8DCC8), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp)) {
                                Text("HQ", color = Color(0xFF8B6F47), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(albumSongs[i].artist, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
        item {
            Text(
                "参与创作的艺术家",
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                color = Color(0xFF1F6FEB), fontWeight = FontWeight.Medium
            )
        }
        items(artists.size) { i ->
            val (name, group) = artists.elementAt(i)
            val artCoverId = group.firstOrNull()?.coverId ?: ""
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = if (artCoverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(artCoverId) else artUri(group.first().albumId),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(CircleShape)
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(name, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Text("${group.size} 首", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun StatColumn(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.Gray, fontSize = 12.sp)
    }
}

private fun fmtDuration(ms: Long): String {
    if (ms <= 0) return "00:00"
    val s = ms / 1000
    return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)
}

/** Folder detail page: songs in this folder, same list layout. */
@Composable
fun FolderDetailScreen(folderName: String, songs: List<Song>, onPlay: (Int) -> Unit) {
    val folderSongs = remember(songs, folderName) { songs.filter { it.folder == folderName } }
    Column(Modifier.fillMaxSize()) {
        SongListHeader(folderSongs.size)
        LazyColumn(Modifier.weight(1f)) {
            items(folderSongs.size) { i ->
                SongRow(folderSongs[i]) { onPlay(songs.indexOf(folderSongs[i])) }
            }
        }
    }
}

/** Playlist detail page: placeholder playlist songs (show all songs for now). */
@Composable
fun PlaylistDetailScreen(name: String, songs: List<Song>, onPlay: (Int) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SongListHeader(songs.size)
        LazyColumn(Modifier.weight(1f)) {
            items(songs.size) { i ->
                SongRow(songs[i]) { onPlay(i) }
            }
        }
    }
}
