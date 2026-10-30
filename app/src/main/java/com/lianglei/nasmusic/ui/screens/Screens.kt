package com.lianglei.nasmusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import com.lianglei.nasmusic.ui.suppressClickOnRightSwipe
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.lianglei.nasmusic.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.lianglei.nasmusic.BuildConfig
import com.lianglei.nasmusic.data.MusicRepository
import com.lianglei.nasmusic.data.Song
import com.lianglei.nasmusic.player.PlayerManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun artUri(albumId: Long) = "content://media/external/audio/albumart/$albumId"

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SongListScreen(songs: List<Song>, loading: Boolean, searchTrigger: Boolean = false, onPlay: (Int) -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSortSheet by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(searchTrigger) {
        searching = searchTrigger
        if (!searchTrigger) searchQuery = ""
    }

    val sorted = remember(songs, sortMode) {
        when (sortMode) {
            0 -> songs
            1 -> songs.sortedBy { it.title }
            2 -> songs.sortedBy { it.artist }
            3 -> songs.sortedBy { it.album }
            4 -> songs.sortedBy { it.duration }
            5 -> songs.sortedByDescending { it.duration }
            else -> songs
        }
    }
    val filtered = remember(sorted, searchQuery) {
        if (searchQuery.isBlank()) sorted
        else sorted.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.album.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Shuffle, null, tint = Color.Gray)
            Spacer(Modifier.width(12.dp))
            Text("${filtered.size}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showSortSheet = true }) {
                Icon(Icons.Filled.Sort, null, tint = Color.Gray)
            }
        }
        if (searching) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.search_placeholder), fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { s ->
                Row(
                    Modifier.fillMaxWidth().suppressClickOnRightSwipe { onPlay(filtered.indexOf(s)) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = if (s.coverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(s.coverId) else artUri(s.albumId),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, maxLines = 1, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (s.isHighQuality) {
                                Box(Modifier.background(Color(0xFFE8DCC8), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp)) {
                                    Text("HQ", color = Color(0xFF8B6F47), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("${s.artist} - ${s.album}", color = Color.Gray, maxLines = 1, fontSize = 12.sp)
                        }
                    }
                    IconButton(
                        onClick = {
                            val r = com.lianglei.nasmusic.player.PlayerManager.playNext(s)
                            val msg = when (r) {
                                3 -> context.getString(R.string.toast_added_started)
                                2 -> context.getString(R.string.toast_moved_next)
                                1 -> context.getString(R.string.toast_added_next)
                                else -> context.getString(R.string.toast_player_not_ready)
                            }
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(36.dp)
                    ) { Icon(Icons.Filled.Add, stringResource(R.string.add_next), tint = Color.Gray, modifier = Modifier.size(20.dp)) }
                    Icon(Icons.Filled.MoreVert, "more", tint = Color.Gray, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }

    if (showSortSheet) {
        ModalBottomSheet(onDismissRequest = { showSortSheet = false }, sheetState = sheetState) {
            Column(Modifier.padding(20.dp).padding(bottom = 24.dp)) {
                Text(stringResource(R.string.sort_title), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(16.dp))
                val options = listOf(
                    stringResource(R.string.sort_custom),
                    stringResource(R.string.sort_by_title),
                    stringResource(R.string.sort_by_artist),
                    stringResource(R.string.sort_by_album),
                    stringResource(R.string.sort_dur_short),
                    stringResource(R.string.sort_dur_long)
                )
                options.forEachIndexed { idx, label ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            sortMode = idx
                            showSortSheet = false
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, fontSize = 15.sp, color = if (sortMode == idx) MaterialTheme.colorScheme.primary else Color.Black)
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumGridScreen(songs: List<Song>, onOpenAlbum: (Long) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Fixed(2), contentPadding = PaddingValues(12.dp)) {
        items(songs.groupBy { it.albumId }.entries.toList()) { (albumId, group) ->
            val coverId = group.first().coverId
            Column(Modifier.padding(8.dp).suppressClickOnRightSwipe { onOpenAlbum(albumId) }) {
                AsyncImage(
                    model = if (coverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(coverId) else artUri(albumId),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.height(8.dp))
                Text(group.first().album, maxLines = 1, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(stringResource(R.string.song_count, group.size), color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun ArtistListScreen(songs: List<Song>, onOpenArtist: (String) -> Unit) {
    val groups = songs.groupBy { it.artist }.entries.filter { it.key.isNotBlank() }.sortedBy { it.key }
    LazyColumn {
        items(groups) { (artist, group) ->
            val coverId = group.first().coverId
            Row(Modifier.fillMaxWidth().padding(16.dp).suppressClickOnRightSwipe { onOpenArtist(artist) }, verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = if (coverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(coverId) else artUri(group.first().albumId),
                    contentDescription = null,
                    modifier = Modifier.size(52.dp).clip(CircleShape)
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(artist, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Text(stringResource(R.string.song_count, group.size), color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun FolderListScreen(songs: List<Song>, onOpenFolder: (String) -> Unit) {
    val groups = songs.groupBy { it.folder }.entries.sortedBy { it.key }
    LazyColumn {
        item {
            Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SdStorage, null, tint = Color.Gray)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(stringResource(R.string.internal_storage), fontWeight = FontWeight.Medium)
                        Text("/storage/emulated/0", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        }
        items(groups) { (folder, group) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).suppressClickOnRightSwipe { onOpenFolder(folder) }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Folder, null, tint = Color(0xFF7E57C2), modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(folder, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Text(stringResource(R.string.song_count, group.size), color = Color.Gray, fontSize = 12.sp)
                }
                Icon(Icons.Filled.MoreVert, null, tint = Color.Gray)
            }
        }
    }
}

/**
 * 飞牛文件夹树浏览（feature/fn-folder-browser 分支尝试实现）。
 * Song.folder 保存服务器返回的完整文件路径（如 /vol1/media/music/周杰伦/夜曲.mp3），
 * 路径按 '/' 分段，最后一段视为文件名、其余为目录层级；逐层下钻显示目录与歌曲。
 * 无路径的歌（folder == "飞牛NAS"）归入顶层歌曲列表（功能降级为平铺）。
 */
@Composable
fun FolderBrowserScreen(
    songs: List<Song>,
    currentPath: String,
    onOpenFolder: (String) -> Unit,
    onPlay: (List<Song>, Int) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 若歌曲全部仍是旧缓存的"飞牛NAS"（无路径），自动触发一次强制刷新以拿到服务器真实路径
    var autoRefreshed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoRefreshed && songs.isNotEmpty() && songs.all { it.folder == "飞牛NAS" }
            && com.lianglei.nasmusic.data.SourceManager.fnConnected.value
            && !com.lianglei.nasmusic.data.SourceManager.loading.value) {
            autoRefreshed = true
            com.lianglei.nasmusic.util.CrashLogger.log("FolderBrowser: no paths in cached songs, force refresh Feiniu")
            com.lianglei.nasmusic.data.SourceManager.refreshFeiniuForce()
        }
    }
    val currentSegs = remember(currentPath) {
        currentPath.trim('/').split('/').filter { it.isNotEmpty() }
    }
    // 每首歌的目录段 = 路径去掉最后一段（文件名）
    fun dirSegsOf(folder: String): List<String> {
        if (folder.isBlank() || folder == "飞牛NAS") return emptyList()
        val segs = folder.trim('/').split('/').filter { it.isNotEmpty() }
        return if (segs.size <= 1) emptyList() else segs.dropLast(1)
    }
    // 当前目录的子目录（去重 + 歌曲计数）
    val children = remember(songs, currentPath) {
        val map = LinkedHashMap<String, Pair<List<String>, Int>>()
        for (s in songs) {
            val dirs = dirSegsOf(s.folder)
            if (dirs.size <= currentSegs.size || dirs.take(currentSegs.size) != currentSegs) continue
            val name = dirs[currentSegs.size]
            val segs = currentSegs + name
            val prev = map[name]
            map[name] = segs to (if (prev == null) 1 else prev.second + 1)
        }
        map.entries.sortedBy { it.key }.map { it.value }
    }
    // 当前目录下的歌曲
    val curSongs = remember(songs, currentPath) {
        songs.filter { dirSegsOf(it.folder) == currentSegs }
    }
    Column(Modifier.fillMaxSize()) {
        // 路径面包屑
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (currentSegs.isEmpty()) stringResource(R.string.folder) else currentSegs.joinToString(" / "),
                fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2
            )
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.dir_count, children.size, curSongs.size), color = Color.Gray, fontSize = 12.sp)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            // 子目录
            items(children.size) { i ->
                val (segs, count) = children[i]
                Row(
                    Modifier.fillMaxWidth().suppressClickOnRightSwipe { onOpenFolder(segs.joinToString("/")) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Folder, null, tint = Color(0xFF7E57C2), modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(segs.last(), fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Text(stringResource(R.string.song_count, count), color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
            // 当前目录歌曲
            items(curSongs, key = { it.id }) { s ->
                Row(
                    Modifier.fillMaxWidth().suppressClickOnRightSwipe { onPlay(curSongs, curSongs.indexOf(s)) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = if (s.coverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(s.coverId) else artUri(s.albumId),
                        contentDescription = null,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, maxLines = 1, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (s.isHighQuality) {
                                Box(Modifier.background(Color(0xFFE8DCC8), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp)) {
                                    Text("HQ", color = Color(0xFF8B6F47), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("${s.artist} - ${s.album}", color = Color.Gray, maxLines = 1, fontSize = 12.sp)
                        }
                    }
                    IconButton(
                        onClick = {
                            val r = com.lianglei.nasmusic.player.PlayerManager.playNext(s)
                            val msg = when (r) {
                                3 -> context.getString(R.string.toast_added_started)
                                2 -> context.getString(R.string.toast_moved_next)
                                1 -> context.getString(R.string.toast_added_next)
                                else -> context.getString(R.string.toast_player_not_ready)
                            }
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(36.dp)
                    ) { Icon(Icons.Filled.Add, stringResource(R.string.add_next), tint = Color.Gray, modifier = Modifier.size(20.dp)) }
                    Icon(Icons.Filled.MoreVert, "more", tint = Color.Gray, modifier = Modifier.padding(8.dp))
                }
            }
            if (children.isEmpty() && curSongs.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_songs_in_dir), color = Color.Gray, fontSize = 13.sp)
                    }
                }
            }
            if (currentSegs.isEmpty() && children.isEmpty() && curSongs.isNotEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                        Text(
                            stringResource(R.string.no_dir_structure),
                            color = Color(0xFFB08968), fontSize = 12.sp, lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistScreen(
    playlists: List<com.lianglei.nasmusic.data.FnApi.FnPlaylist>,
    onOpenPlaylist: (String) -> Unit = {}
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (playlists.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_playlists), color = Color.Gray)
            }
            return@Column
        }
        LazyColumn {
            items(playlists) { p ->
                Row(
                    Modifier.fillMaxWidth().suppressClickOnRightSwipe { onOpenPlaylist(p.guid) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (p.coverId.isNotEmpty()) {
                        AsyncImage(
                            model = com.lianglei.nasmusic.data.FnApi.coverUrl(p.coverId),
                            contentDescription = null,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp))
                        )
                    } else {
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFE0E2E6)))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(p.name, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                        if (p.trackCount > 0) {
                            Text(stringResource(R.string.song_count, p.trackCount), color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceholderRow(title: String, sub: String, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().suppressClickOnRightSwipe { onClick() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFE0E2E6)))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Text(sub, color = Color.Gray, fontSize = 12.sp)
        }
    }
}

@Composable
fun ScanSourceScreen() {
    val context = LocalContext.current
    val currentSource by com.lianglei.nasmusic.data.SourceManager.current.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var scanLog by remember { mutableStateOf(listOf<String>()) }
    var showDialog by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("scan_prefs", android.content.Context.MODE_PRIVATE) }
    var skipShort by remember { mutableStateOf(prefs.getBoolean("skip_short", true)) }
    var customFolders by remember { mutableStateOf(prefs.getStringSet("custom_folders", setOf())?.toList() ?: emptyList()) }
    var blockedFolders by remember { mutableStateOf(prefs.getStringSet("blocked_folders", setOf())?.toList() ?: emptyList()) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val path = it.toString()
            customFolders = customFolders + path
            prefs.edit().putStringSet("custom_folders", customFolders.toSet()).apply()
        }
    }

    // NAS mode: just a refresh button
    if (currentSource == com.lianglei.nasmusic.data.MusicSource.FEINIU) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            item {
                Spacer(Modifier.height(40.dp))
                Card(
                    Modifier.fillMaxWidth().clickable(enabled = !scanning) {
                        scanning = true
                        scope.launch {
                            com.lianglei.nasmusic.data.SourceManager.refreshFeiniuForce { msg ->
                                scanLog = scanLog + msg
                            }
                            scanning = false
                            showDialog = true
                        }
                    }
                ) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Refresh, null, tint = Color(0xFF1F6FEB))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.reload_from_server), color = Color(0xFF1F6FEB), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    } else {
        // Local mode: folder scanner
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            item { Spacer(Modifier.height(20.dp)) }
            item {
                Card(
                    Modifier.fillMaxWidth().clickable(enabled = !scanning) {
                        scanning = true
                        scanLog = emptyList()
                        scope.launch {
                            try {
                                scanLog = scanLog + context.getString(R.string.scan_starting)
                                com.lianglei.nasmusic.data.SourceManager.refreshLocalForce(context)
                                val count = com.lianglei.nasmusic.data.SourceManager.songs.value.size
                                scanLog = scanLog + context.getString(R.string.scan_done, count)
                            } catch (t: Throwable) {
                                scanLog = scanLog + context.getString(R.string.scan_error, t.message ?: "")
                            }
                            scanning = false
                            showDialog = true
                        }
                    }
                ) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Refresh, null, tint = Color(0xFF1F6FEB))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.scan_start_btn), color = Color(0xFF1F6FEB), fontWeight = FontWeight.Medium)
                    }
                }
            }

            // Custom folders
            item {
                Text(stringResource(R.string.custom_folders), color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
            }
            items(customFolders) { folder ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, null, tint = Color.Gray)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(folder.takeLast(40), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        }
                        Icon(Icons.Filled.Close, null, tint = Color.Gray, modifier = Modifier.clickable {
                            customFolders = customFolders - folder
                            prefs.edit().putStringSet("custom_folders", customFolders.toSet()).apply()
                        })
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.clickable { launcher.launch(null) }.padding(16.dp)) {
                        Icon(Icons.Filled.CreateNewFolder, null, tint = Color(0xFF1F6FEB))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.add_custom_folder), color = Color(0xFF1F6FEB))
                    }
                }
            }

            // Settings
            item {
                Text(stringResource(R.string.settings), color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
            }
            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.skip_short_audio), Modifier.weight(1f))
                        Switch(checked = skipShort, onCheckedChange = {
                            skipShort = it
                            prefs.edit().putBoolean("skip_short", it).apply()
                        })
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.clickable {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        context.startActivity(intent)
                    }.padding(16.dp)) {
                        Text(stringResource(R.string.manage_permission), Modifier.weight(1f))
                        Icon(Icons.Filled.OpenInNew, null, tint = Color.Gray)
                    }
                }
            }

            // Blocked folders
            item {
                Text(stringResource(R.string.blocked_folders), color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
            }
            items(blockedFolders) { folder ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.FolderOff, null, tint = Color.Gray)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(folder.takeLast(40), fontSize = 14.sp)
                        }
                        Icon(Icons.Filled.Close, null, tint = Color.Gray, modifier = Modifier.clickable {
                            blockedFolders = blockedFolders - folder
                            prefs.edit().putStringSet("blocked_folders", blockedFolders.toSet()).apply()
                        })
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.clickable { launcher.launch(null) }.padding(16.dp)) {
                        Icon(Icons.Filled.Add, null, tint = Color(0xFF1F6FEB))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.add_blocked_folder), color = Color(0xFF1F6FEB))
                    }
                }
            }
        }
    }

    // Scanning progress dialog
    if (showDialog || scanning) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(if (scanning) stringResource(R.string.scanning_dialog) else stringResource(R.string.scan_complete_dialog)) },
            text = {
                Column(
                    Modifier.height(200.dp).verticalScroll(rememberScrollState())
                ) {
                    scanLog.takeLast(30).forEach { Text(it, fontSize = 11.sp, maxLines = 1) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showDialog = false },
                    enabled = !scanning
                ) { Text(stringResource(R.string.ok)) }
            }
        )
    }
}

@Composable
fun PlaceholderScreen(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.placeholder_suffix, name), color = Color.Gray)
    }
}

/** 设置页：播放缓存用量展示 + 一键清空（v1.4.56 新增） */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var cacheBytes by remember { mutableStateOf(com.lianglei.nasmusic.player.CacheManager.cacheSizeBytes()) }
    var clearing by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        // 顶栏：返回 + 标题
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
            Text(stringResource(R.string.settings), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        // 缓存行
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.DeleteSweep, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.play_cache), fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.cache_usage, formatBytes(cacheBytes)),
                    color = Color.Gray, fontSize = 12.sp
                )
            }
            Button(
                onClick = {
                    clearing = true
                    scope.launch {
                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.lianglei.nasmusic.player.CacheManager.clear(context)
                        }
                        cacheBytes = com.lianglei.nasmusic.player.CacheManager.cacheSizeBytes()
                        clearing = false
                    }
                },
                enabled = !clearing && cacheBytes > 0,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(if (clearing) stringResource(R.string.clearing) else stringResource(R.string.clear_cache), fontSize = 13.sp)
            }
        }

        // 后台播放保护：跳转系统电池/后台限制设置（v1.4.88，三星等系统会冻结后台进程导致播放被退出）
        Row(
            Modifier.fillMaxWidth().clickable { openBatterySettings(context) }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.BatteryFull, null, tint = Color(0xFF1F6FEB), modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.bg_protect_title), fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.bg_protect_desc), color = Color.Gray, fontSize = 12.sp)
            }
            Icon(Icons.Filled.KeyboardArrowRight, null, tint = Color.Gray)
        }
    }
}

/** 打开系统「电池 / 后台限制」设置页：优先直达本应用电池设置，失败降级到应用详情页 */
private fun openBatterySettings(context: Context) {
    val pkg = context.packageName
    try {
        // 带包名参数的电池优化页（AOSP Settings$AppBatteryUsageActivity，One UI 显示单应用电池设置，含「不受限制/优化/限制」）
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .putExtra("android.intent.extra.PACKAGE_NAME", pkg)
        )
    } catch (e: Exception) {
        try {
            // 兜底：应用详情页（内含「电池」入口）
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            )
        } catch (ignored: Exception) {
        }
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1024L * 1024L -> String.format("%.1f MB", b / 1024f / 1024f)
    b >= 1024L -> String.format("%.0f KB", b / 1024f)
    else -> "$b B"
}

/** 统计页：按播放次数从高到低排列歌曲列表（v1.4.58） */
@Composable
fun StatsScreen(songs: List<Song>, onBack: () -> Unit, onPlay: (Int) -> Unit) {
    // 进入页面时从 StatsStore 读取播放次数并降序排序
    var stats by remember { mutableStateOf(emptyList<Pair<Song, Int>>()) }
    LaunchedEffect(songs) {
        val counts = com.lianglei.nasmusic.data.StatsStore.allCounts()
        stats = songs.filter { (counts[it.id] ?: 0) > 0 }
            .map { it to (counts[it.id] ?: 0) }
            .sortedByDescending { it.second }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFFF5F2EC))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
            Text(stringResource(R.string.stats), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        if (stats.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.stats_no_record), color = Color.Gray)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                item {
                    Text(
                        stringResource(R.string.stats_total, stats.size),
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = Color.Gray, fontSize = 12.sp
                    )
                }
                items(stats.size) { i ->
                    val (s, count) = stats[i]
                    Row(
                        Modifier.fillMaxWidth().suppressClickOnRightSwipe { onPlay(songs.indexOf(s)) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${i + 1}", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.width(28.dp))
                        Spacer(Modifier.width(8.dp))
                        AsyncImage(
                            model = if (s.coverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(s.coverId)
                            else "content://media/external/audio/albumart/${s.albumId}",
                            contentDescription = null,
                            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, maxLines = 1, fontSize = 15.sp)
                            Spacer(Modifier.height(3.dp))
                            Text("${s.artist} - ${s.album}", color = Color.Gray, maxLines = 1, fontSize = 12.sp)
                        }
                        // 播放次数徽章
                        Box(
                            Modifier.background(Color(0xFFE8DCC8), RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(stringResource(R.string.count_times, count), color = Color(0xFF8B6F47), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AboutScreen() {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("NAS Music Player", fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(Modifier.height(4.dp))
        Text("Version ${BuildConfig.VERSION_NAME}", color = Color.Gray, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))

        Text(stringResource(R.string.about_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.about_desc),
            color = Color.DarkGray, fontSize = 13.sp
        )
        Spacer(Modifier.height(20.dp))

        Text(stringResource(R.string.open_source_refs), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.ref_media3), color = Color.DarkGray, fontSize = 12.sp)
        Text(stringResource(R.string.ref_compose), color = Color.DarkGray, fontSize = 12.sp)
        Text(stringResource(R.string.ref_coil), color = Color.DarkGray, fontSize = 12.sp)
        Text(stringResource(R.string.ref_salt), color = Color.DarkGray, fontSize = 12.sp)
        Text(stringResource(R.string.ref_saltaudio), color = Color.DarkGray, fontSize = 12.sp)
        Text(stringResource(R.string.ref_fnmusic), color = Color.DarkGray, fontSize = 12.sp)
        Spacer(Modifier.height(20.dp))

        Text(stringResource(R.string.license_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.license_text),
            color = Color.DarkGray, fontSize = 12.sp
        )
    }
}
