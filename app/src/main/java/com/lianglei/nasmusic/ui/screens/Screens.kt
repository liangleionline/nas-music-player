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

/** 歌曲列表排序项（歌曲页 / 文件夹内歌曲列表共用），下标即排序模式。每一项都支持正序与倒序。 */
val SongSortLabels = listOf(
    R.string.sort_added,
    R.string.sort_by_title,
    R.string.sort_by_artist,
    R.string.sort_by_album,
    R.string.sort_by_duration
)

/**
 * 歌曲排序：index 与 [SongSortLabels] 一一对应。
 * 0 = 添加时间：按库里的真实入库时间 createdAt 排序，正序 = 最近添加的在最前（与升级前默认视图一致），
 *     逆序 = 最早添加的在最前。没有可用时间戳（如本地媒体库）时退回列表原样顺序，同样支持正逆序。
 */
fun sortSongs(songs: List<Song>, mode: Int, desc: Boolean): List<Song> {
    if (mode == 0) {
        val timed = songs.filter { it.addedAt > 0 }
        // 至少两条且时间戳有区分度，才用时间排序；否则保持原样，避免"伪排序"
        if (timed.size > 1 && timed.map { it.addedAt }.distinct().size > 1) {
            val unknown = songs.filter { it.addedAt <= 0 }
            return if (desc) timed.sortedBy { it.addedAt } + unknown
            else timed.sortedByDescending { it.addedAt } + unknown
        }
        return if (desc) songs.reversed() else songs
    }
    val base = when (mode) {
        1 -> songs.sortedBy { it.title.lowercase() }
        2 -> songs.sortedBy { it.artist.lowercase() }
        3 -> songs.sortedBy { it.album.lowercase() }
        4 -> songs.sortedBy { it.duration }
        else -> songs
    }
    return if (desc) base.reversed() else base
}

/** 专辑排序项：添加时间 / 名称 / 发行年份 / 歌曲数量 */
val AlbumSortLabels = listOf(
    R.string.sort_added,
    R.string.sort_by_name,
    R.string.sort_by_year,
    R.string.sort_by_count
)

/**
 * 专辑分组排序。分组值 = (albumId, 该专辑全部歌曲)。
 * 0 = 添加时间：用专辑自身的入库时间 createdAt（缺失时退回该专辑最早的曲目入库时间），
 *     正序 = 最近添加的在最前，逆序 = 最早添加的在最前。
 * 年份、时间未知（<= 0）的专辑无论正序还是倒序都排在末尾，避免"未知值"抢占列表头部。
 */
fun sortAlbums(
    albums: List<Pair<Long, List<Song>>>,
    mode: Int,
    desc: Boolean
): List<Pair<Long, List<Song>>> {
    fun yearOf(g: List<Song>) = g.firstOrNull { it.year > 0 }?.year ?: 0
    fun nameOf(g: List<Song>) = (g.firstOrNull()?.album ?: "").lowercase()
    fun addedOf(g: List<Song>): Long =
        g.firstOrNull { it.albumAddedAt > 0 }?.albumAddedAt
            ?: (g.map { it.addedAt }.filter { it > 0 }.minOrNull() ?: 0L)
    return when (mode) {
        0 -> {
            val known = albums.filter { addedOf(it.second) > 0 }
            val unknown = albums.filter { addedOf(it.second) <= 0 }
            if (known.size > 1 && known.map { addedOf(it.second) }.distinct().size > 1) {
                if (desc) known.sortedBy { addedOf(it.second) } + unknown
                else known.sortedByDescending { addedOf(it.second) } + unknown
            } else {
                if (desc) albums.reversed() else albums
            }
        }
        1 -> albums.sortedBy { nameOf(it.second) }.let { if (desc) it.reversed() else it }
        2 -> {
            val known = albums.filter { yearOf(it.second) > 0 }.sortedBy { yearOf(it.second) }
            val unknown = albums.filter { yearOf(it.second) <= 0 }
            if (desc) known.reversed() + unknown else known + unknown
        }
        3 -> albums.sortedBy { it.second.size }.let { if (desc) it.reversed() else it }
        else -> albums
    }
}

/** 艺术家排序项：名称 / 歌曲数量 */
val ArtistSortLabels = listOf(R.string.sort_by_name, R.string.sort_by_count)

/**
 * 排序状态（内存 + 持久化）：按 [key] 读完上次的选择，[choose] 时立即写回，
 * 这样退出应用再进来还保持用户上次的排序方式。
 */
class SortStateHolder(private val key: String) {
    var mode by mutableStateOf(com.lianglei.nasmusic.data.SortPrefs.mode(key))
        private set
    var desc by mutableStateOf(com.lianglei.nasmusic.data.SortPrefs.desc(key))
        private set

    fun choose(index: Int, descending: Boolean) {
        mode = index
        desc = descending
        com.lianglei.nasmusic.data.SortPrefs.save(key, index, descending)
    }
}

@Composable
fun rememberSortState(key: String): SortStateHolder = remember(key) { SortStateHolder(key) }

/**
 * 通用排序底部弹层：与歌曲页同一套交互。
 * 点未选中的项 → 切到该排序项（保持当前方向）；点已选中的项 → 切换正序 / 倒序。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SortOptionsSheet(
    options: List<Int>,
    selected: Int,
    descending: Boolean,
    onChoose: (index: Int, desc: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.sort_title), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.sort_dir_hint), color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            options.forEachIndexed { idx, labelRes ->
                val active = selected == idx
                Row(
                    Modifier.fillMaxWidth().clickable {
                        onChoose(idx, if (active) !descending else descending)
                    }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(labelRes),
                        fontSize = 15.sp,
                        color = if (active) MaterialTheme.colorScheme.primary else Color.Black
                    )
                    Spacer(Modifier.weight(1f))
                    if (active) {
                        Icon(
                            if (descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                            contentDescription = stringResource(R.string.sort_title),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SongListScreen(
    songs: List<Song>,
    loading: Boolean,
    searchTrigger: Boolean = false,
    onPlay: (List<Song>, Int) -> Unit,
    onShuffle: (List<Song>) -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    // 排序方式持久化：下次进页面沿用上次的选择
    val sort = rememberSortState(com.lianglei.nasmusic.data.SortPrefs.SONGS)
    var showSortSheet by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(searchTrigger) {
        searching = searchTrigger
        if (!searchTrigger) searchQuery = ""
    }

    // 搜索展开时按返回键先退出搜索框并清空关键字，而不是直接触发"再按一次退出"
    androidx.activity.compose.BackHandler(enabled = searching) {
        searching = false
        searchQuery = ""
    }

    val filtered = remember(songs, sort.mode, sort.desc, searchQuery) {
        val sorted = sortSongs(songs, sort.mode, sort.desc)
        if (searchQuery.isBlank()) sorted
        else sorted.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.album.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // 左上角随机播放：点击即从当前列表（含搜索过滤后的结果）随机起播，并切到随机模式
            Box(
                Modifier.size(32.dp).clickable { onShuffle(filtered) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Shuffle,
                    stringResource(R.string.shuffle),
                    tint = Color.Gray,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("${filtered.size}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showSortSheet = true }) {
                Icon(Icons.Filled.Sort, stringResource(R.string.sort_title), tint = Color.Gray)
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
                    Modifier.fillMaxWidth().suppressClickOnRightSwipe { onPlay(filtered, filtered.indexOf(s)) }
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

@Composable
fun AlbumGridScreen(songs: List<Song>, onOpenAlbum: (Long) -> Unit) {
    val sort = rememberSortState(com.lianglei.nasmusic.data.SortPrefs.ALBUMS)
    var showSortSheet by remember { mutableStateOf(false) }
    val albums = remember(songs, sort.mode, sort.desc) {
        sortAlbums(songs.groupBy { it.albumId }.entries.map { it.key to it.value }, sort.mode, sort.desc)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${albums.size}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showSortSheet = true }) {
                Icon(Icons.Filled.Sort, stringResource(R.string.sort_title), tint = Color.Gray)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(albums) { (albumId, group) ->
                // 合并后组内各曲目封面可能不同，取第一个非空封面作为专辑封面
                val coverId = group.firstOrNull { it.coverId.isNotEmpty() }?.coverId ?: ""
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
    if (showSortSheet) {
        SortOptionsSheet(
            options = AlbumSortLabels,
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

@Composable
fun ArtistListScreen(songs: List<Song>, onOpenArtist: (String) -> Unit) {
    val sort = rememberSortState(com.lianglei.nasmusic.data.SortPrefs.ARTISTS) // 0 = 名称，1 = 歌曲数量
    var showSortSheet by remember { mutableStateOf(false) }
    val groups = remember(songs, sort.mode, sort.desc) {
        val base = songs.groupBy { it.artist }.entries.filter { it.key.isNotBlank() }
        val sorted = when (sort.mode) {
            1 -> base.sortedBy { it.value.size }
            else -> base.sortedBy { it.key.lowercase() }
        }
        if (sort.desc) sorted.reversed() else sorted
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${groups.size}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showSortSheet = true }) {
                Icon(Icons.Filled.Sort, stringResource(R.string.sort_title), tint = Color.Gray)
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(groups) { (artist, group) ->
                val coverId = group.firstOrNull { it.coverId.isNotEmpty() }?.coverId ?: ""
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
    if (showSortSheet) {
        SortOptionsSheet(
            options = ArtistSortLabels,
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
    // 当前目录下的歌曲（复用歌曲页排序，排序方式持久化）
    val sort = rememberSortState(com.lianglei.nasmusic.data.SortPrefs.FOLDERS)
    var showSortSheet by remember { mutableStateOf(false) }
    val rawSongs = remember(songs, currentPath) {
        songs.filter { dirSegsOf(it.folder) == currentSegs }
    }
    val curSongs = remember(rawSongs, sort.mode, sort.desc) {
        sortSongs(rawSongs, sort.mode, sort.desc)
    }
    Column(Modifier.fillMaxSize()) {
        // 路径面包屑 + 排序按钮：与歌曲/专辑/艺术家页统一——16dp 内边距的标题行 +
        // 右侧标准 IconButton（默认 48dp 点击区、24dp 图标），图标右缩进与其它页面完全一致
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (currentSegs.isEmpty()) stringResource(R.string.folder) else currentSegs.joinToString(" / "),
                fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.dir_count, children.size, curSongs.size), color = Color.Gray, fontSize = 12.sp)
            IconButton(onClick = { showSortSheet = true }) {
                Icon(Icons.Filled.Sort, stringResource(R.string.sort_title), tint = Color.Gray)
            }
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
