package com.lianglei.nasmusic.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import com.lianglei.nasmusic.data.MusicSource
import com.lianglei.nasmusic.data.MusicStore
import com.lianglei.nasmusic.data.Song
import com.lianglei.nasmusic.data.SourceManager
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
    var searchTrigger by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val currentSource by SourceManager.current.collectAsStateWithLifecycle()
    val songs by SourceManager.songs.collectAsStateWithLifecycle()
    val loading by SourceManager.loading.collectAsStateWithLifecycle()
    val playlists by SourceManager.playlists.collectAsStateWithLifecycle()

    LaunchedEffect(currentSource) {
        when (currentSource) {
            MusicSource.LOCAL -> SourceManager.refreshLocal(context)
            MusicSource.FEINIU -> SourceManager.refreshFeiniu()
        }
    }

    LaunchedEffect(songs.size) {
        if (songs.isNotEmpty() && PlayerManager.queue.value.isEmpty()) {
            PlayerManager.restoreState(songs)
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.hierarchy?.firstOrNull()?.route

    var showPlayer by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }

    var lastBackPress by remember { mutableStateOf(0L) }
    val rootRoutes = setOf("songs", "albums", "artists", "folders", "playlists")
    androidx.activity.compose.BackHandler(enabled = showPlayer || showQueue) {
        if (showQueue) { showQueue = false } else { showPlayer = false }
    }
    androidx.activity.compose.BackHandler(enabled = !showPlayer && !showQueue && currentRoute in rootRoutes) {
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2000) {
            (context as? android.app.Activity)?.finish()
        } else {
            lastBackPress = now
            android.widget.Toast.makeText(context, "再按一次退出", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(showPlayer) {
        val window = (context as? android.app.Activity)?.window ?: return@LaunchedEffect
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !showPlayer
    }

    val title = when {
        currentRoute == "songs" -> "歌曲"
        currentRoute == "albums" -> "专辑"
        currentRoute == "artists" -> "艺术家"
        currentRoute == "folders" -> "文件夹"
        currentRoute == "playlists" -> "歌单"
        currentRoute == "scan" -> "媒体来源"
        currentRoute?.startsWith("folder/") == true -> backStack?.arguments?.getString("name") ?: ""
        currentRoute?.startsWith("artist/") == true -> backStack?.arguments?.getString("name") ?: ""
        currentRoute?.startsWith("album/") == true -> {
            val albumId = backStack?.arguments?.getString("albumId")?.toLongOrNull() ?: 0L
            songs.find { it.albumId == albumId }?.album ?: ""
        }
        currentRoute?.startsWith("playlist/") == true -> {
            val guid = backStack?.arguments?.getString("guid")
            playlists.find { it.guid == guid }?.name ?: "歌单"
        }
        else -> "NAS Music"
    }

    val currentIndex by PlayerManager.currentIndex.collectAsStateWithLifecycle()
    val isPlaying by PlayerManager.isPlaying.collectAsStateWithLifecycle()
    val buffering by PlayerManager.buffering.collectAsStateWithLifecycle()
    val queue by PlayerManager.queue.collectAsStateWithLifecycle()
    val current = queue.getOrNull(currentIndex)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(180.dp)) {
                Spacer(Modifier.height(40.dp))
                Row(Modifier.padding(horizontal=20.dp)) {
                    listOf(Icons.Filled.Login, Icons.Filled.LightMode, Icons.Filled.Equalizer).forEach {
                        Icon(it, null, tint = Color(0xFF888), modifier=Modifier.padding(end=16.dp).size(22.dp))
                    }
                }
                Spacer(Modifier.height(20.dp))
                DrawerGroup(mainEntries) { route ->
                    scope.launch { drawerState.close() }
                    nav.navigate(route) {
                        launchSingleTop = true
                        popUpTo(0) { inclusive = true }
                    }
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
            containerColor = Color(0xFFF2F3F5),
            topBar = {
                val isDetail = currentRoute?.startsWith("artist/") == true ||
                        currentRoute?.startsWith("album/") == true ||
                        currentRoute?.startsWith("folder/") == true ||
                        currentRoute?.startsWith("playlist/") == true
                CenterAlignedTopAppBar(
                    title = { Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontSize = 18.sp) },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (isDetail) nav.popBackStack()
                            else scope.launch { drawerState.open() }
                        }) {
                            Icon(if (isDetail) Icons.Filled.ArrowBack else Icons.Filled.Menu, null)
                        }
                    },
                    actions = {
                        when {
                            currentRoute == "songs" ->
                                IconButton(onClick = { searchTrigger = !searchTrigger }) {
                                    Icon(Icons.Filled.Search, null)
                                }
                            currentRoute == "albums" || currentRoute == "artists" || currentRoute?.startsWith("artist/") == true ->
                                Icon(Icons.Filled.ViewList, null)
                            else -> {}
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.Black,
                        navigationIconContentColor = Color.Black,
                        actionIconContentColor = Color.Black
                    )
                )
            },
            bottomBar = {
                if (!showPlayer && !showQueue) {
                    MiniPlayer(
                        song = current,
                        isPlaying = isPlaying,
                        buffering = buffering,
                        onClick = { showPlayer = true },
                        onQueue = { showQueue = true }
                    )
                }
            }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                NavHost(nav, startDestination = "songs") {
                    composable("songs") { SongListScreen(songs, loading, searchTrigger) { idx -> PlayerManager.playQueue(songs, idx) } }
                    composable("albums") { AlbumGridScreen(songs, onOpenAlbum = { albumId -> nav.navigate("album/$albumId") }) }
                    composable("artists") { ArtistListScreen(songs, onOpenArtist = { name -> if (name.isNotBlank()) nav.navigate("artist/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")) }) }
                    composable("folders") { FolderListScreen(songs, onOpenFolder = { name -> if (name.isNotBlank()) nav.navigate("folder/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")) }) }
                    composable("playlists") { PlaylistScreen(playlists = playlists, onOpenPlaylist = { guid -> nav.navigate("playlist/$guid") }) }
                    composable("scan") { ScanSourceScreen() }
                    composable("library") { NasLibraryScreen(onBack = { nav.popBackStack() }, onOpenFeiniuLogin = { nav.navigate("fn-login") }) }
                    composable("fn-login") { FeiniuLoginScreen(onBack = { nav.popBackStack() }, onSuccess = { nav.popBackStack("library", false) }) }
                    composable("stats") { PlaceholderScreen("统计") }
                    composable("settings") { PlaceholderScreen("设置") }
                    composable("about") { AboutScreen() }
                    composable("artist/{name}") { backStackEntry ->
                        val name = backStackEntry.arguments?.getString("name") ?: ""
                        ArtistDetailScreen(name, songs, onPlay = { idx -> PlayerManager.playQueue(songs, idx) }, onOpenAlbum = { albumId -> nav.navigate("album/$albumId") })
                    }
                    composable("album/{albumId}") { backStackEntry ->
                        val albumId = backStackEntry.arguments?.getString("albumId")?.toLongOrNull() ?: 0L
                        AlbumDetailScreen(albumId, songs, onPlay = { idx -> PlayerManager.playQueue(songs, idx) }, onOpenArtist = { name -> if (name.isNotBlank()) nav.navigate("artist/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")) })
                    }
                    composable("folder/{name}") { backStackEntry ->
                        val name = backStackEntry.arguments?.getString("name") ?: ""
                        FolderDetailScreen(name, songs) { idx -> PlayerManager.playQueue(songs, idx) }
                    }
                    composable("playlist/{guid}") { backStackEntry ->
                        val guid = backStackEntry.arguments?.getString("guid") ?: ""
                        var playlistSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
                        LaunchedEffect(guid, currentSource) {
                            playlistSongs = if (currentSource == MusicSource.FEINIU) {
                                SourceManager.fetchPlaylistTracks(guid)
                            } else {
                                songs
                            }
                        }
                        val plName = playlists.find { it.guid == guid }?.name ?: "歌单"
                        PlaylistDetailScreen(plName, playlistSongs) { idx -> PlayerManager.playQueue(playlistSongs, idx) }
                    }
                }

                // Overlay player & queue on top, keeping underlying screen composed
                AnimatedVisibility(
                    visible = showPlayer,
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it }
                ) {
                    PlayerScreen(onOpenQueue = { showQueue = true })
                }
                AnimatedVisibility(
                    visible = showQueue,
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it }
                ) {
                    QueueScreen(onClose = { showQueue = false })
                }
            }
        }
    }
}

@Composable
private fun DrawerGroup(entries: List<DrawerEntry>, onClick: (String) -> Unit) {
    entries.forEach { e ->
        Row(
            Modifier.fillMaxWidth().clickable { onClick(e.route) }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(e.icon, null, tint = e.tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(20.dp))
            Text(e.label, fontSize = 16.sp)
        }
    }
}

@Composable
private fun MiniPlayer(song: Song?, isPlaying: Boolean, buffering: Boolean, onClick: () -> Unit, onQueue: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp).clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = song?.let {
                    if (it.coverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(it.coverId)
                    else "content://media/external/audio/albumart/${it.albumId}"
                },
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song?.title ?: "未在播放", fontWeight = FontWeight.SemiBold, maxLines = 1, fontSize = 14.sp)
                Text(song?.artist ?: "—", color = Color.Gray, maxLines = 1, fontSize = 12.sp)
            }
            if (buffering) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { PlayerManager.togglePlayPause() }) {
                    Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        null, modifier = Modifier.size(28.dp))
                }
            }
            IconButton(onClick = onQueue) { Icon(Icons.Filled.QueueMusic, null) }
        }
    }
}
