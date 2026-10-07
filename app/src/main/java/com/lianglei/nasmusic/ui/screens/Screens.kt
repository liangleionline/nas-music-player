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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lianglei.nasmusic.data.MusicRepository
import com.lianglei.nasmusic.data.Song
import com.lianglei.nasmusic.player.PlayerManager
import kotlinx.coroutines.launch

private fun artUri(albumId: Long) = "content://media/external/audio/albumart/$albumId"

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SongListScreen(songs: List<Song>, loading: Boolean, searchTrigger: Boolean = false, onPlay: (Int) -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSortSheet by remember { mutableStateOf(false) }

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
                placeholder = { Text("搜索歌曲、专辑、艺术家", fontSize = 13.sp) },
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
                    Modifier.fillMaxWidth().clickable { onPlay(filtered.indexOf(s)) }
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
                    Icon(Icons.Filled.Add, "add", tint = Color.Gray, modifier = Modifier.padding(8.dp))
                    Icon(Icons.Filled.MoreVert, "more", tint = Color.Gray, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }

    if (showSortSheet) {
        ModalBottomSheet(onDismissRequest = { showSortSheet = false }, sheetState = sheetState) {
            Column(Modifier.padding(20.dp).padding(bottom = 24.dp)) {
                Text("排序", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(16.dp))
                val options = listOf("自定义", "标题", "艺术家", "专辑", "时长（短→长）", "时长（长→短）")
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
            Column(Modifier.padding(8.dp).clickable { onOpenAlbum(albumId) }) {
                AsyncImage(
                    model = if (coverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(coverId) else artUri(albumId),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.height(8.dp))
                Text(group.first().album, maxLines = 1, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("${group.size} 首", color = Color.Gray, fontSize = 12.sp)
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
            Row(Modifier.fillMaxWidth().padding(16.dp).clickable { onOpenArtist(artist) }, verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = if (coverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(coverId) else artUri(group.first().albumId),
                    contentDescription = null,
                    modifier = Modifier.size(52.dp).clip(CircleShape)
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(artist, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Text("${group.size} 首", color = Color.Gray, fontSize = 12.sp)
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
                        Text("内部存储", fontWeight = FontWeight.Medium)
                        Text("/storage/emulated/0", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        }
        items(groups) { (folder, group) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).clickable { onOpenFolder(folder) }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Folder, null, tint = Color(0xFF7E57C2), modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(folder, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Text("${group.size} 首", color = Color.Gray, fontSize = 12.sp)
                }
                Icon(Icons.Filled.MoreVert, null, tint = Color.Gray)
            }
        }
    }
}

@Composable
fun PlaylistScreen(
    playlists: List<com.lianglei.nasmusic.data.FnApi.FnPlaylist>,
    onOpenPlaylist: (String) -> Unit = {}
) {
    com.lianglei.nasmusic.util.CrashLogger.log("PlaylistScreen rendered, playlists=${playlists.size}")
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (playlists.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无歌单", color = Color.Gray)
            }
            return@Column
        }
        LazyColumn {
            items(playlists) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenPlaylist(p.guid) }.padding(vertical = 12.dp),
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
                            Text("${p.trackCount} 首", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceholderRow(title: String, sub: String, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        Text("从飞牛服务器重新加载", color = Color(0xFF1F6FEB), fontWeight = FontWeight.Medium)
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
                                scanLog = scanLog + "正在扫描本地音乐文件..."
                                com.lianglei.nasmusic.data.SourceManager.refreshLocalForce(context)
                                val count = com.lianglei.nasmusic.data.SourceManager.songs.value.size
                                scanLog = scanLog + "扫描完成，共 $count 首歌曲"
                            } catch (t: Throwable) {
                                scanLog = scanLog + "错误: ${t.message}"
                            }
                            scanning = false
                            showDialog = true
                        }
                    }
                ) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Refresh, null, tint = Color(0xFF1F6FEB))
                        Spacer(Modifier.width(12.dp))
                        Text("开始扫描本地音乐", color = Color(0xFF1F6FEB), fontWeight = FontWeight.Medium)
                    }
                }
            }

            // Custom folders
            item {
                Text("自定义文件夹", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
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
                        Text("添加自定义文件夹", color = Color(0xFF1F6FEB))
                    }
                }
            }

            // Settings
            item {
                Text("设置", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
            }
            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("不扫描 60 秒以下音频", Modifier.weight(1f))
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
                        Text("管理外部存储权限", Modifier.weight(1f))
                        Icon(Icons.Filled.OpenInNew, null, tint = Color.Gray)
                    }
                }
            }

            // Blocked folders
            item {
                Text("被屏蔽的文件夹", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
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
                        Text("添加屏蔽文件夹", color = Color(0xFF1F6FEB))
                    }
                }
            }
        }
    }

    // Scanning progress dialog
    if (showDialog || scanning) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(if (scanning) "正在扫描..." else "扫描完成") },
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
                ) { Text("确定") }
            }
        )
    }
}

@Composable
fun PlaceholderScreen(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("$name （待实现）", color = Color.Gray)
    }
}

@Composable
fun AboutScreen() {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("NAS Music Player", fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(Modifier.height(4.dp))
        Text("Version 0.7.4", color = Color.Gray, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))

        Text("关于本程序", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "一款基于 Android Jetpack Compose 开发的开源音乐播放器，支持本地音乐扫描播放，同时支持通过飞牛 NAS 官方音乐接口流式播放 NAS 中的音乐库。",
            color = Color.DarkGray, fontSize = 13.sp
        )
        Spacer(Modifier.height(20.dp))

        Text("开源引用", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text("• Media3 / ExoPlayer — 媒体播放引擎 (Apache 2.0)", color = Color.DarkGray, fontSize = 12.sp)
        Text("• Compose Material3 — UI 组件库 (Apache 2.0)", color = Color.DarkGray, fontSize = 12.sp)
        Text("• Coil — 图片加载 (Apache 2.0)", color = Color.DarkGray, fontSize = 12.sp)
        Text("• Salt Player / SaltUI — UI 设计参考 (GPL-3.0)", color = Color.DarkGray, fontSize = 12.sp)
        Text("• SaltAudioTag — 音频标签读取参考 (GPL-3.0)", color = Color.DarkGray, fontSize = 12.sp)
        Text("• fn-music-tv — 飞牛音乐 API 参考", color = Color.DarkGray, fontSize = 12.sp)
        Spacer(Modifier.height(20.dp))

        Text("开源协议", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "本程序以 GNU General Public License v3.0 (GPL-3.0) 协议开源发布。\n" +
            "你可以自由使用、修改和分发本程序，但衍生作品必须同样采用 GPL-3.0 协议开源。\n" +
            "源代码托管于 GitHub: liangleionline/nas-music-player",
            color = Color.DarkGray, fontSize = 12.sp
        )
    }
}
