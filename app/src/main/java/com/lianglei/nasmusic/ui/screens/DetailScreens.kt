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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.lianglei.nasmusic.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lianglei.nasmusic.data.Song
import com.lianglei.nasmusic.ui.suppressClickOnRightSwipe

private fun artUri(albumId: Long) = "content://media/external/audio/albumart/$albumId"

/** "下一首播放"加号：与歌曲列表页同一入口，统一反馈文案 */
private fun addNextWithToast(song: Song, context: android.content.Context) {
    com.lianglei.nasmusic.player.PlayerManager.playNext(song)
    android.widget.Toast.makeText(context, context.getString(R.string.toast_added_next), android.widget.Toast.LENGTH_SHORT).show()
}

/** Reusable song row matching the Salt Player list style. */
@Composable
fun SongRow(song: Song, onClick: () -> Unit, onAddNext: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().suppressClickOnRightSwipe(onClick = onClick)
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
        // "下一首播放"加号：与歌曲列表页行为一致（已存在→提到下一首；空队列→加入并开始播放）
        if (onAddNext != null) {
            IconButton(onClick = onAddNext, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Add, stringResource(R.string.add_next), tint = Color.Gray, modifier = Modifier.size(20.dp))
            }
        }
        Icon(Icons.Filled.MoreVert, "more", tint = Color.Gray, modifier = Modifier.padding(8.dp))
    }
}

/** Song list header row: shuffle icon + count. */
@Composable
fun SongListHeader(count: Int) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Shuffle, null, tint = Color.Gray)
        Spacer(Modifier.width(12.dp))
        Text("$count", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** Artist detail page: circular avatar + artist name + songs + albums by this artist. */
@Composable
fun ArtistDetailScreen(artistName: String, songs: List<Song>, onPlay: (List<Song>, Int) -> Unit, onOpenAlbum: (Long) -> Unit = {}) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
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
            SongListHeader(artistSongs.size)
        }
        items(artistSongs.size) { i ->
            val s = artistSongs[i]
            SongRow(
                s,
                onClick = { onPlay(artistSongs, i) },
                onAddNext = { addNextWithToast(s, ctx) }
            )
        }
        item {
            Text(stringResource(R.string.album_title), Modifier.padding(16.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        items(artistAlbums.size) { i ->
            val (albumId, group) = artistAlbums.elementAt(i)
            val albumCoverId = group.firstOrNull { it.coverId.isNotEmpty() }?.coverId ?: ""
            val albumCover = if (albumCoverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(albumCoverId) else artUri(albumId)
            Row(
                Modifier.fillMaxWidth().suppressClickOnRightSwipe { onOpenAlbum(albumId) }
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
                    Text(stringResource(R.string.artist_songs_line, group.first().artist, group.size), color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
    }
}

/** 专辑内曲目分组结果：discNo = 0 表示"没有碟号"，渲染成 ＃ 组 */
data class AlbumDiscGroup(val discNo: Int, val songs: List<Song>)

/** 生效的碟号：曲目自带的标签值优先，其次专辑页懒读到的本地标签，缺省 0（无碟号） */
private fun discOfSong(song: Song, local: Map<Long, Pair<Int, Int>>): Int =
    if (song.discNo > 0) song.discNo else local[song.id]?.first ?: 0

/** 生效的音轨号：同上 */
private fun trackOfSong(song: Song, local: Map<Long, Pair<Int, Int>>): Int =
    if (song.trackNo > 0) song.trackNo else local[song.id]?.second ?: 0

/** 文件名（标题为空时的兜底排序键）：本地取文件路径末段，飞牛取 folder 里的文件名段 */
private fun fileNameOf(song: Song): String {
    val p = if (song.data.startsWith("http")) song.folder else song.data
    val name = p.substringAfterLast('/').substringAfterLast('\\').trim()
    return name.ifBlank { song.title.trim() }
}

/**
 * 专辑详情分组排序（v1.5.6）：
 * ① 有碟号的按碟号升序分成 CD1、CD2…，没有碟号的统一进最后的 ＃ 组；
 * ② 组内先按音轨号升序，没有音轨号的排在有号的之后；
 * ③ 没有音轨号（或音轨号相同）时按标题排序，标题为空的按文件名排序。
 */
fun albumGroupsOf(songs: List<Song>, localTags: Map<Long, Pair<Int, Int>>): List<AlbumDiscGroup> {
    val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
    val byDisc = LinkedHashMap<Int, MutableList<Song>>()
    songs.forEach { s -> byDisc.getOrPut(discOfSong(s, localTags)) { mutableListOf() }.add(s) }
    fun keyOf(s: Song): String = s.title.trim().ifBlank { fileNameOf(s) }
    val cmp = Comparator<Song> { a, b ->
        val ta = trackOfSong(a, localTags).let { if (it > 0) it else Int.MAX_VALUE }
        val tb = trackOfSong(b, localTags).let { if (it > 0) it else Int.MAX_VALUE }
        if (ta != tb) ta.compareTo(tb) else {
            val c = collator.compare(keyOf(a), keyOf(b))
            if (c != 0) c else a.id.compareTo(b.id)
        }
    }
    // 碟号升序在前，无碟号（0）统一收尾
    val discs = byDisc.keys.filter { it > 0 }.sorted() + byDisc.keys.filter { it <= 0 }.sorted()
    return discs.map { d -> AlbumDiscGroup(d, byDisc.getValue(d).sortedWith(cmp)) }
}

/** Album detail page: cover + title/artist/sample rate + stats + songs + participating artists. */
@Composable
fun AlbumDetailScreen(albumId: Long, songs: List<Song>, onPlay: (List<Song>, Int) -> Unit, onOpenArtist: (String) -> Unit = {}) {
    val albumSongs = remember(songs, albumId) { songs.filter { it.albumId == albumId } }
    val totalMs = remember(albumSongs) { albumSongs.sumOf { it.duration } }
    val first = albumSongs.firstOrNull()
    val artists = remember(albumSongs) { albumSongs.groupBy { it.artist }.entries }
    val coverId = albumSongs.firstOrNull { it.coverId.isNotEmpty() }?.coverId ?: ""
    val coverModel = if (coverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(coverId) else artUri(albumId)
    // 采样率：飞牛端 audioSpec.sampleRate 已在拉取时解析；本地端首次进入用 MediaMetadataRetriever 懒读
    var sampleRate by remember(albumSongs) { mutableStateOf(first?.sampleRate ?: 0) }
    LaunchedEffect(albumSongs) {
        val f = albumSongs.firstOrNull() ?: return@LaunchedEffect
        if (f.sampleRate > 0 || f.data.startsWith("http://") || f.data.startsWith("https://")) {
            sampleRate = f.sampleRate
            return@LaunchedEffect
        }
        sampleRate = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(f.data)
                    mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
                } finally { try { mmr.release() } catch (_: Throwable) {} }
            } catch (_: Throwable) { 0 }
        }
    }
    val yearText = if ((first?.year ?: 0) > 0) "${first?.year}" else "—"
    val sampleRateText = if (sampleRate > 0) "$sampleRate Hz" else "—"

    // 碟号/音轨号：飞牛歌曲拉取时已带 track.discNo / track.trackNo；
    // 本地文件（MediaStore 无这两列）进专辑页时懒读标签补全，读完重新分组。
    var localTags by remember(albumSongs) { mutableStateOf(emptyMap<Long, Pair<Int, Int>>()) }
    LaunchedEffect(albumSongs) {
        val need = albumSongs.filter { it.discNo == 0 && it.trackNo == 0 && !it.data.startsWith("http") }
        if (need.isEmpty()) return@LaunchedEffect
        localTags = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val out = HashMap<Long, Pair<Int, Int>>()
            need.forEach { s ->
                try {
                    val mmr = android.media.MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(s.data)
                        val track = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                            ?.trim()?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
                        val disc = if (android.os.Build.VERSION.SDK_INT >= 30)
                            mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                                ?.trim()?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
                        else 0
                        if (track > 0 || disc > 0) out[s.id] = disc to track
                    } finally { try { mmr.release() } catch (_: Throwable) {} }
                } catch (_: Throwable) { }
            }
            out
        }
    }
    val discGroups = remember(albumSongs, localTags) { albumGroupsOf(albumSongs, localTags) }
    // 播放队列顺序必须与页面展示顺序一致（分组标题不计入队列）
    val shownSongs = remember(discGroups) { discGroups.flatMap { it.songs } }
    val groupStarts = remember(discGroups) {
        var acc = 0
        discGroups.map { g -> val start = acc; acc += g.songs.size; start }
    }
    val showDiscHeaders = discGroups.size > 1

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
                    Text(sampleRateText, color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.SpaceAround) {
                StatColumn("${albumSongs.size}", stringResource(R.string.songs))
                StatColumn(fmtDuration(totalMs), stringResource(R.string.duration))
                StatColumn(yearText, stringResource(R.string.year))
            }
        }
        item { Divider(Modifier.padding(horizontal = 20.dp)) }
        discGroups.forEachIndexed { gi, group ->
            if (showDiscHeaders) {
                item(key = "disc-${group.discNo}") {
                    Text(
                        text = if (group.discNo > 0) stringResource(R.string.album_disc_cd, group.discNo)
                        else stringResource(R.string.album_disc_unknown),
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 2.dp),
                        color = Color.Gray, fontWeight = FontWeight.Bold, fontSize = 13.sp
                    )
                }
            }
            items(group.songs.size, key = { "song-${group.songs[it].id}" }) { i ->
                val s = group.songs[i]
                val tn = trackOfSong(s, localTags)
                Row(
                    Modifier.fillMaxWidth().suppressClickOnRightSwipe { onPlay(shownSongs, groupStarts[gi] + i) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (tn > 0) "$tn" else "${i + 1}",
                        color = Color.Gray, fontSize = 13.sp, modifier = Modifier.width(28.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontSize = 15.sp, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (s.isHighQuality) {
                                Box(Modifier.background(Color(0xFFE8DCC8), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp)) {
                                    Text("HQ", color = Color(0xFF8B6F47), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(s.artist, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.contrib_artists),
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                color = Color(0xFF1F6FEB), fontWeight = FontWeight.Medium
            )
        }
        items(artists.size) { i ->
            val (name, group) = artists.elementAt(i)
            val artCoverId = group.firstOrNull()?.coverId ?: ""
            Row(
                Modifier.suppressClickOnRightSwipe { onOpenArtist(name) }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = if (artCoverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(artCoverId) else artUri(group.first().albumId),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(CircleShape)
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(name, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Text(stringResource(R.string.song_count, group.size), color = Color.Gray, fontSize = 12.sp)
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

/** Folder detail page: songs in this folder, same list layout + same sorting as the songs page. */
@Composable
fun FolderDetailScreen(folderName: String, songs: List<Song>, onPlay: (List<Song>, Int) -> Unit) {
    // 与文件夹浏览页共用同一份排序偏好（都是"文件夹里的歌曲列表"）
    val sort = rememberSortState(com.lianglei.nasmusic.data.SortPrefs.FOLDERS)
    var showSortSheet by remember { mutableStateOf(false) }
    val folderSongs = remember(songs, folderName) { songs.filter { it.folder == folderName } }
    val shown = remember(folderSongs, sort.mode, sort.desc) { sortSongs(folderSongs, sort.mode, sort.desc) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${shown.size}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showSortSheet = true }) {
                Icon(Icons.Filled.Sort, stringResource(R.string.sort_title), tint = Color.Gray)
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(shown.size) { i ->
                val s = shown[i]
                SongRow(
                    s,
                    onClick = { onPlay(shown, i) },
                    onAddNext = { addNextWithToast(s, ctx) }
                )
            }
        }
    }
    if (showSortSheet) {
        SortOptionsSheet(
            options = SongSortLabels,
            selected = sort.mode,
            descending = sort.desc,
            onChoose = { idx, desc ->
                sort.choose(idx, desc)
                showSortSheet = false
            },
            onDismiss = { showSortSheet = false }
        )
    }
}

/** Playlist detail page: placeholder playlist songs (show all songs for now). */
@Composable
fun PlaylistDetailScreen(name: String, songs: List<Song>, onPlay: (Int) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        SongListHeader(songs.size)
        LazyColumn(Modifier.weight(1f)) {
            items(songs.size) { i ->
                val s = songs[i]
                SongRow(
                    s,
                    onClick = { onPlay(i) },
                    onAddNext = { addNextWithToast(s, ctx) }
                )
            }
        }
    }
}
