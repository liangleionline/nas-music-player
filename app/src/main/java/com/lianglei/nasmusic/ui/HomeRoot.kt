package com.lianglei.nasmusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import coil.compose.AsyncImage
import com.lianglei.nasmusic.data.MusicSource
import com.lianglei.nasmusic.data.Song
import com.lianglei.nasmusic.data.SourceManager
import com.lianglei.nasmusic.player.PlayerManager
import com.lianglei.nasmusic.ui.screens.*
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

/**
 * 主入口（v1.4.15 重构：改用 Google 官方 DrawerLayout）。
 *
 * 背景：v1.4.1~v1.4.14 自绘 Compose 抽屉（Animatable + translationX + 快照位图），
 * 真机上连续出现 卡顿 → 崩溃 → 白屏 → 抖动重影。根因都是“自绘多层合成”的对齐问题。
 * v1.4.15 放弃自绘，改用 AndroidX DrawerLayout（View 系统级组件）：
 *   - 单层 View 平移，系统级合成，天然无重影/白屏，跟手流畅；
 *   - setDrawerEdgeSize(全屏宽) → 全屏任意位置右滑开抽屉（保留用户要求的手势范围）；
 *   - 抽屉面板上左滑关闭（DrawerLayout 原生跟手）；
 *   - 内容/抽屉仍用 ComposeView 承载，UI 层代码全部保留。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeRoot() {
    val nav = rememberNavController()
    val context = LocalContext.current
    val drawerWidthPx = with(LocalDensity.current) { DRAWER_WIDTH.toPx() }

    val showPlayerState = remember { mutableStateOf(false) }
    val showQueueState = remember { mutableStateOf(false) }
    val searchTriggerState = remember { mutableStateOf(false) }
    val drawerOpenState = remember { mutableStateOf(false) }
    var drawerLayoutRef by remember { mutableStateOf<DrawerLayout?>(null) }
    var lastBackPress by remember { mutableStateOf(0L) }

    val showPlayer by showPlayerState
    val showQueue by showQueueState
    val drawerOpen by drawerOpenState

    val currentSource by SourceManager.current.collectAsStateWithLifecycle()
    val songs by SourceManager.songs.collectAsStateWithLifecycle()
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
    val rootRoutes = setOf("songs", "albums", "artists", "folders", "playlists")

    // 播放器/队列页打开时锁定抽屉，禁止边缘手势误触
    LaunchedEffect(showPlayer, showQueue) {
        drawerLayoutRef?.setDrawerLockMode(
            if (showPlayer || showQueue) DrawerLayout.LOCK_MODE_LOCKED_CLOSED
            else DrawerLayout.LOCK_MODE_UNLOCKED
        )
    }
    LaunchedEffect(showPlayer) {
        val window = (context as? android.app.Activity)?.window ?: return@LaunchedEffect
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !showPlayer
    }

    BackHandler(enabled = drawerOpen) { drawerLayoutRef?.closeDrawer(GravityCompat.START) }
    BackHandler(enabled = showPlayer || showQueue) {
        if (showQueue) showQueueState.value = false else showPlayerState.value = false
    }
    BackHandler(enabled = !showPlayer && !showQueue && currentRoute in rootRoutes) {
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2000) {
            (context as? android.app.Activity)?.finish()
        } else {
            lastBackPress = now
            android.widget.Toast.makeText(context, "再按一次退出", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    AndroidView(
        factory = { ctx ->
            SwipeDrawerLayout(ctx).apply {
                // 推动式抽屉：无遮罩，内容被抽屉推开
                setScrimColor(android.graphics.Color.TRANSPARENT)
                addDrawerListener(object : DrawerLayout.DrawerListener {
                    override fun onDrawerStateChanged(state: Int) {}
                    override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
                    override fun onDrawerOpened(drawerView: View) { drawerOpenState.value = true }
                    override fun onDrawerClosed(drawerView: View) { drawerOpenState.value = false }
                })

                // 右侧主内容（Compose）
                addView(
                    ComposeView(ctx).apply {
                        setBackgroundColor(0xFFF2F3F5.toInt())
                        setContent {
                            HomeContent(
                                nav = nav,
                                showPlayerState = showPlayerState,
                                showQueueState = showQueueState,
                                searchTriggerState = searchTriggerState,
                                drawerOpenState = drawerOpenState,
                                onOpenDrawer = { openDrawer(GravityCompat.START) },
                                onCloseDrawer = { closeDrawer(GravityCompat.START) }
                            )
                        }
                    },
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )

                // 左侧抽屉面板（Compose，被推开露出）
                addView(
                    ComposeView(ctx).apply {
                        setContent {
                            DrawerPanel(
                                nav = nav,
                                onCloseDrawer = { closeDrawer(GravityCompat.START) }
                            )
                        }
                    },
                    DrawerLayout.LayoutParams(drawerWidthPx.toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
                        .apply { gravity = Gravity.START }
                )
            }
        },
        update = { drawerLayoutRef = it },
        modifier = Modifier.fillMaxSize()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    nav: NavHostController,
    showPlayerState: MutableState<Boolean>,
    showQueueState: MutableState<Boolean>,
    searchTriggerState: MutableState<Boolean>,
    drawerOpenState: MutableState<Boolean>,
    onOpenDrawer: () -> Unit,
    onCloseDrawer: () -> Unit
) {
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.hierarchy?.firstOrNull()?.route
    val showPlayer by showPlayerState
    val showQueue by showQueueState
    val drawerOpen by drawerOpenState
    val searchTrigger by searchTriggerState

    val currentSource by SourceManager.current.collectAsStateWithLifecycle()
    val songs by SourceManager.songs.collectAsStateWithLifecycle()
    val loading by SourceManager.loading.collectAsStateWithLifecycle()
    val playlists by SourceManager.playlists.collectAsStateWithLifecycle()
    val currentIndex by PlayerManager.currentIndex.collectAsStateWithLifecycle()
    val isPlaying by PlayerManager.isPlaying.collectAsStateWithLifecycle()
    val buffering by PlayerManager.buffering.collectAsStateWithLifecycle()
    val queue by PlayerManager.queue.collectAsStateWithLifecycle()
    val current = queue.getOrNull(currentIndex)

    val isDetail = currentRoute?.startsWith("artist/") == true ||
            currentRoute?.startsWith("album/") == true ||
            currentRoute?.startsWith("folder/") == true ||
            currentRoute?.startsWith("playlist/") == true

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

    Scaffold(
        containerColor = Color(0xFFF2F3F5),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isDetail) nav.popBackStack()
                        else if (drawerOpen) onCloseDrawer()
                        else onOpenDrawer()
                    }) {
                        Icon(
                            if (isDetail) Icons.Filled.ArrowBack
                            else if (drawerOpen) Icons.Filled.Close
                            else Icons.Filled.Menu,
                            null
                        )
                    }
                },
                actions = {
                    when {
                        currentRoute == "songs" ->
                            IconButton(onClick = { searchTriggerState.value = !searchTriggerState.value }) {
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
                onClick = { showPlayerState.value = true },
                onQueue = { showQueueState.value = true }
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
                PlayerScreen(onOpenQueue = { showQueueState.value = true })
            }
            AnimatedVisibility(
                visible = showQueue,
                enter = slideInVertically { it },
                exit = slideOutVertically { it }
            ) {
                QueueScreen(onClose = { showQueueState.value = false })
            }
        }
    }
}

@Composable
private fun DrawerPanel(
    nav: NavHostController,
    onCloseDrawer: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentSource by SourceManager.current.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.hierarchy?.firstOrNull()?.route

    Surface(color = Color(0xFFFCFCFD), shadowElevation = 6.dp) {
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
                                    onCloseDrawer()
                                    if ("library" != currentRoute) nav.navigate("library") { launchSingleTop = true }
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
                onCloseDrawer()
                // 已在目标页则不再导航（避免 NavHost 转场动画覆盖产生重影）
                if (route != currentRoute) {
                    nav.navigate(route) {
                        launchSingleTop = true
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("系统", fontSize = 12.sp, color = Color(0xFF8C8C8C), modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
            DrawerGroup(systemEntries) { route ->
                onCloseDrawer()
                if (route != currentRoute) {
                    nav.navigate(route) { launchSingleTop = true }
                }
            }
            Spacer(Modifier.weight(1f))
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

/**
 * 全屏手势 DrawerLayout：
 * 在 dispatchTouchEvent 的最起点做"纯观察"（先于 super、绝不消费、绝不拦截），
 * 因此能看到所有触摸（包括被 ComposeView 消化的事件），同时完全不影响事件分发——
 * 汉堡键、列表滚动、点击播放等下层交互不受任何干扰。
 * 左缘 20dp 内由 DrawerLayout 原生边缘手势跟手；其余全屏区域右滑开抽屉、左滑关抽屉（系统动画）。
 */
class SwipeDrawerLayout @JvmOverloads constructor(
    context: Context
) : DrawerLayout(context) {
    private var touchStartX = 0f
    private var touchStartY = 0f
    private val slop: Float get() = 24f * resources.displayMetrics.density

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = ev.x
                touchStartY = ev.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = ev.x - touchStartX
                val dy = ev.y - touchStartY
                if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                    if (getDrawerLockMode(GravityCompat.START) == DrawerLayout.LOCK_MODE_UNLOCKED) {
                        if (isDrawerOpen(GravityCompat.START)) {
                            if (dx < 0) closeDrawer(GravityCompat.START)
                        } else if (touchStartX > slop && dx > 0) {
                            openDrawer(GravityCompat.START)
                        }
                    }
                }
            }
        }
        // 观察完交给 super：拦截、分发、Compose 处理全部照旧，一次都不碰
        return super.dispatchTouchEvent(ev)
    }
}
