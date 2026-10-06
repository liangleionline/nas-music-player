package com.lianglei.nasmusic.ui

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.lianglei.nasmusic.data.MusicRepository
import com.lianglei.nasmusic.data.Song
import com.lianglei.nasmusic.player.PlayerManager
import com.lianglei.nasmusic.ui.screens.*
import kotlinx.coroutines.launch

data class DrawerEntry(val label: String, val icon: ImageVector, val tint: Color, val route: String)

private val mainEntries = listOf(
    DrawerEntry("歌曲", Icons.Filled.MusicNote, Color(0xFF3CB371), "songs"),
    DrawerEntry("专辑", Icons.Filled.Album, Color(0xFFE74C3C), "albums"),
    DrawerEntry("艺术家", Icons.Filled.Mic, Color(0xFFF1C40F), "artists"),
    DrawerEntry("文件夹", Icons.Filled.Folder, Color(0xFF7E57C2), "folders"),
    DrawerEntry("歌单", Icons.Filled.QueueMusic, Color(0xFF1E88E5), "playlists"),
)
private val systemEntries = listOf(
    DrawerEntry("扫描音乐", Icons.Filled.FolderSpecial, Color(0xFF5C6BC0), "scan"),
    DrawerEntry("音乐库", Icons.Filled.LibraryMusic, Color(0xFFD4AC0D), "library"),
    DrawerEntry("统计", Icons.Filled.QueryStats, Color(0xFFE74C3C), "stats"),
    DrawerEntry("设置", Icons.Filled.Settings, Color(0xFF4CAF50), "settings"),
    DrawerEntry("关于", Icons.Filled.Info, Color(0xFF1E88E5), "about"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeRoot() {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { MusicRepository(context) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        songs = repo.loadSongs()
        loading = false
    }

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.hierarchy?.firstOrNull()?.route

    val currentIndex by PlayerManager.currentIndex.collectAsStateWithLifecycle()
    val isPlaying by PlayerManager.isPlaying.collectAsStateWithLifecycle()
    val queue by PlayerManager.queue.collectAsStateWithLifecycle()
    val current = queue.getOrNull(currentIndex)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                Spacer(Modifier.height(40.dp))
                Row(Modifier.padding(horizontal=20.dp)) {
                    listOf(Icons.Filled.Login, Icons.Filled.LightMode, Icons.Filled.Equalizer).forEach {
                        Icon(it, null, tint = Color(0xFF888), modifier=Modifier.padding(end=16.dp).size(22.dp))
                    }
                }
                Spacer(Modifier.height(20.dp))
                DrawerGroup(mainEntries) { route ->
                    scope.launch { drawerState.close() }
                    nav.navigate(route) { launchSingleTop = true }
                }
                Spacer(Modifier.height(12.dp))
                DrawerGroup(systemEntries) { route ->
                    scope.launch { drawerState.close() }
                    nav.navigate(route) { launchSingleTop = true }
                }
                Spacer(Modifier.weight(1f))
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(titleFor(currentRoute)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, null)
                        }
                    },
                    actions = {
                        when (currentRoute) {
                            "songs" -> Icon(Icons.Filled.Search, null)
                            "albums", "artists" -> Icon(Icons.Filled.ViewList, null)
                            "folders" -> Icon(Icons.Filled.Search, null)
                            else -> {}
                        }
                    }
                )
            },
            bottomBar = {
                MiniPlayer(
                    song = current,
                    isPlaying = isPlaying,
                    onClick = { nav.navigate("player") },
                    onQueue = { nav.navigate("queue") }
                )
            }
        ) { pad ->
            Box(Modifier.padding(pad)) {
                NavHost(nav, startDestination = "songs") {
                    composable("songs") { SongListScreen(songs, loading) { idx -> PlayerManager.playQueue(songs, idx) } }
                    composable("albums") { AlbumGridScreen(repo, songs) }
                    composable("artists") { ArtistListScreen(repo, songs) }
                    composable("folders") { FolderListScreen(repo, songs) }
                    composable("playlists") { PlaylistScreen() }
                    composable("scan") { ScanSourceScreen() }
                    composable("library") { PlaceholderScreen("音乐库") }
                    composable("stats") { PlaceholderScreen("统计") }
                    composable("settings") { PlaceholderScreen("设置") }
                    composable("about") { AboutScreen() }
                    composable("player") { PlayerScreen() }
                    composable("queue") { QueueScreen() }
                }
            }
        }
    }
}

private fun titleFor(route: String?) = when (route) {
    "songs" -> "歌曲"; "albums" -> "专辑"; "artists" -> "艺术家"
    "folders" -> "文件夹"; "playlists" -> "歌单"; "scan" -> "媒体来源"
    "player" -> ""; "queue" -> "播放队列"
    else -> "NAS Music"
}

@Composable
private fun DrawerGroup(entries: List<DrawerEntry>, onClick: (String) -> Unit) {
    entries.forEach { e ->
        Row(
            Modifier.fillMaxWidth().clickable { onClick(e.route) }.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(e.icon, null, tint = e.tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(20.dp))
            Text(e.label, fontSize = 16.sp)
        }
    }
}

@Composable
private fun MiniPlayer(song: Song?, isPlaying: Boolean, onClick: () -> Unit, onQueue: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp).clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = song?.let { "content://media/external/audio/albumart/${it.albumId}" },
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song?.title ?: "未在播放", fontWeight = FontWeight.SemiBold, maxLines = 1, fontSize = 14.sp)
                Text(song?.artist ?: "—", color = Color.Gray, maxLines = 1, fontSize = 12.sp)
            }
            IconButton(onClick = { PlayerManager.togglePlayPause() }) {
                Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    null, modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = onQueue) { Icon(Icons.Filled.QueueMusic, null) }
        }
    }
}
