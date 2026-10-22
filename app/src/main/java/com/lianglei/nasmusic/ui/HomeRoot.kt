package com.lianglei.nasmusic.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.lianglei.nasmusic.data.MusicSource
import com.lianglei.nasmusic.data.Song
import com.lianglei.nasmusic.data.SourceManager
import com.lianglei.nasmusic.player.PlayerManager
import com.lianglei.nasmusic.ui.screens.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class DrawerEntry(val label: String, val icon: ImageVector, val tint: Color, val route: String)

/** 主入口（Salt Player 同款：歌曲/专辑/艺术家/文件夹/歌单 在左侧抽屉） */
private val mainEntries = listOf(
    DrawerEntry("歌曲", Icons.Filled.MusicNote, Color(0xFF3CB371), "songs"),
    DrawerEntry("专辑", Icons.Filled.Album, Color(0xFFE74C3C), "albums"),
    DrawerEntry("艺术家", Icons.Filled.Mic, Color(0xFFF1C40F), "artists"),
    DrawerEntry("文件夹", Icons.Filled.Folder, Color(0xFF7E57C2), "folders"),
    DrawerEntry("歌单", Icons.Filled.QueueMusic, Color(0xFF1E88E5), "playlists"),
)

/** 抽屉系统项（含 NAS 一级入口） */
private val systemEntries = listOf(
    DrawerEntry("音乐库", Icons.Filled.Cloud, Color(0xFF1F6FEB), "library"),
    DrawerEntry("扫描音乐", Icons.Filled.FolderSpecial, Color(0xFF5C6BC0), "scan"),
    DrawerEntry("统计", Icons.Filled.QueryStats, Color(0xFFE74C3C), "stats"),
    DrawerEntry("设置", Icons.Filled.Settings, Color(0xFF4CAF50), "settings"),
    DrawerEntry("关于", Icons.Filled.Info, Color(0xFF1E88E5), "about"),
)

private val DRAWER_WIDTH = 224.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeRoot() {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    var searchTrigger by remember { mutableStateOf(false) }

    // 推动式抽屉：0=关闭 1=完全展开，内容随动画右移
    val drawerAnim = remember { Animatable(0f) }
    val drawerWidthPx = with(LocalDensity.current) { DRAWER_WIDTH.toPx() }
    // 关键：用 derivedStateOf 只在阈值翻转时重组。
    // 直接读 drawerAnim.value（快照状态）会让 HomeRoot 在拖动中每帧重组 → 卡顿
    val isDrawerOpen by remember { derivedStateOf { drawerAnim.value > 0.5f } }
    fun toggleDrawer(open: Boolean) {
        scope.launch {
            drawerAnim.animateTo(
                if (open) 1f else 0f,
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
            )
        }
    }
    var dragging by remember { mutableStateOf(false) }
    // 回弹：按「相对起手的拖动位移」判定，而不是看最终位置——
    // 从全开往回拉只需超过抽屉宽度 15% 即关闭，避免"拉不回来"
    fun settleDrawer(startValue: Float, totalX: Float) {
        scope.launch {
            val threshold = 0.15f * drawerWidthPx
            val target = if (startValue > 0.5f) {
                if (totalX < -threshold) 0f else 1f
            } else {
                if (totalX > threshold) 1f else 0f
            }
            drawerAnim.animateTo(target, tween(200))
        }
    }
    fun cancelSettle() {
        scope.launch {
            drawerAnim.animateTo(if (drawerAnim.value > 0.5f) 1f else 0f, tween(200))
        }
    }

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
    androidx.activity.compose.BackHandler(enabled = isDrawerOpen) {
        toggleDrawer(false)
    }
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

    Box(
        Modifier.fillMaxSize().background(Color(0xFFEDEDEF))
    ) {
        // ===== 左侧抽屉（固定，被推露出来）=====
        Surface(
            modifier = Modifier.width(DRAWER_WIDTH).fillMaxHeight().align(Alignment.CenterStart).zIndex(0f)
                // 抽屉展开时：在抽屉面板上拖动即可关闭（关闭时面板被内容层盖住，不会误触）
                .pointerInput(Unit) {
                    var startValue = 0f
                    var startX = 0f
                    var totalX = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { start ->
                            dragging = true
                            startValue = drawerAnim.value
                            startX = start.x
                            totalX = 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            totalX += dragAmount
                            // Main.immediate：在事件回调内同步更新，抽屉逐帧贴合手指（不排队等帧）
                            scope.launch(Dispatchers.Main.immediate) {
                                drawerAnim.snapTo((startValue + (change.position.x - startX) / drawerWidthPx).coerceIn(0f, 1f))
                            }
                        },
                        onDragEnd = { if (dragging) { dragging = false; settleDrawer(startValue, totalX) } },
                        onDragCancel = { if (dragging) { dragging = false; cancelSettle() } }
                    )
                },
            color = Color(0xFFFCFCFD),
            shadowElevation = 6.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(28.dp))
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("NAS Music", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                // NAS 状态卡：当前音源 + 一键切换/进入
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF4FC))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (currentSource == MusicSource.FEINIU) Icons.Filled.CloudDone else Icons.Filled.Cloud,
                                null, tint = Color(0xFF1F6FEB), modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (currentSource == MusicSource.FEINIU) "音源：飞牛NAS" else "音源：本地音乐",
                                fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    if (currentSource == MusicSource.FEINIU) {
                                        SourceManager.switchTo(MusicSource.LOCAL)
                                    } else {
                                        toggleDrawer(false)
                                        nav.navigate("library") { launchSingleTop = true }
                                    }
                                }
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.SwapHoriz, null, tint = Color(0xFF1F6FEB), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (currentSource == MusicSource.FEINIU) "切回本地音乐" else "打开 NAS 音乐库",
                                color = Color(0xFF1F6FEB), fontSize = 13.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("媒体库", fontSize = 12.sp, color = Color(0xFF8C8C8C), modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
                DrawerGroup(mainEntries) { route ->
                    toggleDrawer(false)
                    nav.navigate(route) {
                        launchSingleTop = true
                        popUpTo(0) { inclusive = true }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("系统", fontSize = 12.sp, color = Color(0xFF8C8C8C), modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
                DrawerGroup(systemEntries) { route ->
                    toggleDrawer(false)
                    nav.navigate(route) { launchSingleTop = true }
                }
                Spacer(Modifier.weight(1f))
            }
        }

        // ===== 右侧主内容：抽屉展开时整体右移（推动式）=====
        // 手势覆盖整个内容区：全屏宽度任意位置右滑开抽屉、左滑关闭（播放器/队列页除外）
        // 绝对跟手映射：起手时快照，拖动位置直接换算，逐帧贴合手指
        // RenderLayer：把整个内容页缓存为单一图层，拖动时只做 GPU 位移，不再逐帧重放绘制指令（去卡顿）
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(1f)
                .graphicsLayer {
                    translationX = drawerAnim.value * drawerWidthPx
                }
                .pointerInput(showPlayer, showQueue) {
                    if (showPlayer || showQueue) return@pointerInput
                    var startValue = 0f
                    var startX = 0f
                    var totalX = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { start ->
                            dragging = true
                            startValue = drawerAnim.value
                            startX = start.x
                            totalX = 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            totalX += dragAmount
                            // Main.immediate：在事件回调内同步更新，抽屉逐帧贴合手指（不排队等帧）
                            scope.launch(Dispatchers.Main.immediate) {
                                drawerAnim.snapTo((startValue + (change.position.x - startX) / drawerWidthPx).coerceIn(0f, 1f))
                            }
                        },
                        onDragEnd = { if (dragging) { dragging = false; settleDrawer(startValue, totalX) } },
                        onDragCancel = { if (dragging) { dragging = false; cancelSettle() } }
                    )
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
                                else toggleDrawer(!isDrawerOpen)
                            }) {
                                Icon(
                                    if (isDetail) Icons.Filled.ArrowBack
                                    else if (isDrawerOpen) Icons.Filled.Close
                                    else Icons.Filled.Menu,
                                    null
                                )
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
                    MiniPlayer(
                        song = current,
                        isPlaying = isPlaying,
                        buffering = buffering,
                        onClick = { showPlayer = true },
                        onQueue = { showQueue = true }
                    )
                }
            ) { pad ->
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        NavHost(
                            nav,
                            startDestination = "songs",
                            enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) },
                            exitTransition = { slideOutHorizontally(targetOffsetX = { -it / 4 }, animationSpec = tween(300)) },
                            popEnterTransition = { slideInHorizontally(initialOffsetX = { -it / 4 }, animationSpec = tween(300)) },
                            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) }
                        ) {
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
                    }

                    // Fullscreen overlay - covers entire window including status bar
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
}

@Composable
private fun DrawerGroup(entries: List<DrawerEntry>, onClick: (String) -> Unit) {
    entries.forEach { e ->
        Row(
            Modifier.fillMaxWidth().clickable { onClick(e.route) }.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(e.icon, null, tint = e.tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(20.dp))
            Text(e.label, fontSize = 15.sp)
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
