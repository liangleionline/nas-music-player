package com.lianglei.nasmusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.res.stringResource
import com.lianglei.nasmusic.PlayerActivity
import com.lianglei.nasmusic.R
import coil.compose.AsyncImage
import com.lianglei.nasmusic.data.MusicSource
import com.lianglei.nasmusic.data.Song
import com.lianglei.nasmusic.data.SourceManager
import com.lianglei.nasmusic.player.PlayerManager
import com.lianglei.nasmusic.ui.screens.*
import kotlinx.coroutines.launch

data class DrawerEntry(val labelRes: Int, val icon: ImageVector, val tint: Color, val route: String)

/** 主入口（Salt Player 同款：歌曲/专辑/艺术家/文件夹/歌单 在左侧抽屉） */
private val mainEntries = listOf(
    DrawerEntry(R.string.songs, Icons.Filled.MusicNote, Color(0xFF3CB371), "songs"),
    DrawerEntry(R.string.albums, Icons.Filled.Album, Color(0xFFE74C3C), "albums"),
    DrawerEntry(R.string.artists, Icons.Filled.Mic, Color(0xFFF1C40F), "artists"),
    DrawerEntry(R.string.folders, Icons.Filled.Folder, Color(0xFF7E57C2), "folders"),
    DrawerEntry(R.string.playlists, Icons.Filled.QueueMusic, Color(0xFF1E88E5), "playlists"),
)

/** 抽屉系统项（含 NAS 一级入口） */
private val systemEntries = listOf(
    DrawerEntry(R.string.library_title, Icons.Filled.Cloud, Color(0xFF1F6FEB), "library"),
    DrawerEntry(R.string.scan_music, Icons.Filled.FolderSpecial, Color(0xFF5C6BC0), "scan"),
    DrawerEntry(R.string.stats, Icons.Filled.QueryStats, Color(0xFFE74C3C), "stats"),
    DrawerEntry(R.string.settings, Icons.Filled.Settings, Color(0xFF4CAF50), "settings"),
    DrawerEntry(R.string.about, Icons.Filled.Info, Color(0xFF1E88E5), "about"),
)

private val DRAWER_WIDTH = 200.dp

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

    val searchTriggerState = remember { mutableStateOf(false) }
    val drawerOpenState = remember { mutableStateOf(false) }
    var drawerLayoutRef by remember { mutableStateOf<DrawerLayout?>(null) }
    var lastBackPress by remember { mutableStateOf(0L) }

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

    BackHandler(enabled = drawerOpen) { drawerLayoutRef?.closeDrawer(GravityCompat.START) }
    BackHandler(enabled = currentRoute in rootRoutes) {
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2000) {
            (context as? android.app.Activity)?.finish()
        } else {
            lastBackPress = now
            android.widget.Toast.makeText(context, context.getString(R.string.exit_prompt), android.widget.Toast.LENGTH_SHORT).show()
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
    searchTriggerState: MutableState<Boolean>,
    drawerOpenState: MutableState<Boolean>,
    onOpenDrawer: () -> Unit,
    onCloseDrawer: () -> Unit
) {
    val context = LocalContext.current
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.hierarchy?.firstOrNull()?.route
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

    // 打开播放页：独立 Activity + 系统窗口动画（v1.4.81）。
    // 播放由 PlayerService（前台服务）承载，Activity 切换不影响播放。
    fun openPlayer(showQueue: Boolean = false) {
        context.startActivity(
            android.content.Intent(context, PlayerActivity::class.java)
                .apply { if (showQueue) putExtra("showQueue", true) }
        )
        (context as? android.app.Activity)?.overridePendingTransition(R.anim.slide_in_bottom, 0)
    }

    val isDetail = currentRoute?.startsWith("artist/") == true ||
            currentRoute?.startsWith("album/") == true ||
            currentRoute?.startsWith("folder/") == true ||
            currentRoute?.startsWith("fbrowse/") == true ||
            currentRoute?.startsWith("playlist/") == true

    val title = when {
        currentRoute == "songs" -> stringResource(R.string.songs)
        currentRoute == "albums" -> stringResource(R.string.albums)
        currentRoute == "artists" -> stringResource(R.string.artists)
        currentRoute == "folders" -> stringResource(R.string.folders)
        currentRoute == "playlists" -> stringResource(R.string.playlists)
        currentRoute == "scan" -> stringResource(R.string.media_source)
        currentRoute?.startsWith("folder/") == true -> backStack?.arguments?.getString("name") ?: ""
        currentRoute?.startsWith("fbrowse/") == true -> {
            (backStack?.arguments?.getString("path") ?: "").trim('/').split('/').filter { it.isNotEmpty() }.lastOrNull() ?: stringResource(R.string.folder)
        }
        currentRoute?.startsWith("artist/") == true -> backStack?.arguments?.getString("name") ?: ""
        currentRoute?.startsWith("album/") == true -> {
            val albumId = backStack?.arguments?.getString("albumId")?.toLongOrNull() ?: 0L
            songs.find { it.albumId == albumId }?.album ?: ""
        }
        currentRoute?.startsWith("playlist/") == true -> {
            val guid = backStack?.arguments?.getString("guid")
            playlists.find { it.guid == guid }?.name ?: stringResource(R.string.default_playlist)
        }
        else -> "NAS Music"
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
        containerColor = Color(0xFFF2F3F5),
        topBar = {
            // 顶栏始终显示：播放页/队列页是 Scaffold 之上的全屏浮层（见文件末尾），天然覆盖顶栏，
            // 不再用条件隐藏——否则点歌瞬间顶栏先消失、列表上跳
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
            // 迷你播放栏始终显示；点击打开播放页（独立 Activity），队列按钮直接进队列页
            MiniPlayer(
                song = current,
                isPlaying = isPlaying,
                buffering = buffering,
                onClick = { openPlayer() },
                onQueue = { openPlayer(showQueue = true) }
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(pad)) {
                NavHost(
                    nav,
                    startDestination = "songs",
                    // 推拉门式 1:1 同步转场：新页面从右全宽滑入、旧页面以同速向左全宽退出，
                    // 同一时刻两页各占半屏、无重叠；clipToBounds 保证退出页面在 Content 边界被
                    // 裁剪，不会滑到左侧抽屉图层之上
                    modifier = Modifier.clipToBounds(),
                    enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) },
                    exitTransition = { slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(300)) },
                    popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(300)) },
                    popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) }
                ) {
                    composable("songs") { SongListScreen(songs, loading, searchTrigger) { idx -> PlayerManager.playQueue(songs, idx); openPlayer() } }
                    composable("albums") { AlbumGridScreen(songs, onOpenAlbum = { albumId -> nav.navigate("album/$albumId") }) }
                    composable("artists") { ArtistListScreen(songs, onOpenArtist = { name -> if (name.isNotBlank()) nav.navigate("artist/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")) }) }
                    composable("folders") {
                        if (currentSource == MusicSource.FEINIU) {
                            FolderBrowserScreen(
                                songs = songs,
                                currentPath = "",
                                onOpenFolder = { path -> if (path.isNotBlank()) nav.navigate("fbrowse/" + java.net.URLEncoder.encode(path, "UTF-8").replace("+", "%20")) },
                                onPlay = { list, idx -> PlayerManager.playQueue(list, idx); openPlayer() }
                            )
                        } else {
                            FolderListScreen(songs, onOpenFolder = { name -> if (name.isNotBlank()) nav.navigate("folder/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")) })
                        }
                    }
                    composable("playlists") { PlaylistScreen(playlists = playlists, onOpenPlaylist = { guid -> nav.navigate("playlist/$guid") }) }
                    composable("scan") { ScanSourceScreen() }
                    composable("library") { NasLibraryScreen(onBack = { nav.popBackStack() }, onOpenFeiniuLogin = { nav.navigate("fn-login") }) }
                    composable("fn-login") { FeiniuLoginScreen(onBack = { nav.popBackStack() }, onSuccess = { nav.popBackStack("library", false) }) }
                    composable("stats") { StatsScreen(songs, onBack = { nav.popBackStack() }, onPlay = { idx -> PlayerManager.playQueue(songs, idx); openPlayer() }) }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                    composable("about") { AboutScreen() }
                    composable("artist/{name}") { backStackEntry ->
                        val name = backStackEntry.arguments?.getString("name") ?: ""
                        ArtistDetailScreen(name, songs, onPlay = { idx -> PlayerManager.playQueue(songs, idx); openPlayer() }, onOpenAlbum = { albumId -> nav.navigate("album/$albumId") })
                    }
                    composable("album/{albumId}") { backStackEntry ->
                        val albumId = backStackEntry.arguments?.getString("albumId")?.toLongOrNull() ?: 0L
                        AlbumDetailScreen(albumId, songs, onPlay = { idx -> PlayerManager.playQueue(songs, idx); openPlayer() }, onOpenArtist = { name -> if (name.isNotBlank()) nav.navigate("artist/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")) })
                    }
                    composable("folder/{name}") { backStackEntry ->
                        val name = backStackEntry.arguments?.getString("name") ?: ""
                        FolderDetailScreen(name, songs) { idx -> PlayerManager.playQueue(songs, idx); openPlayer() }
                    }
                    composable("fbrowse/{path}") { backStackEntry ->
                        val path = backStackEntry.arguments?.getString("path") ?: ""
                        FolderBrowserScreen(
                            songs = songs,
                            currentPath = path,
                            onOpenFolder = { p -> if (p.isNotBlank()) nav.navigate("fbrowse/" + java.net.URLEncoder.encode(p, "UTF-8").replace("+", "%20")) },
                            onPlay = { list, idx -> PlayerManager.playQueue(list, idx); openPlayer() }
                        )
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
                        val plName = playlists.find { it.guid == guid }?.name ?: stringResource(R.string.default_playlist)
                        PlaylistDetailScreen(plName, playlistSongs) { idx -> PlayerManager.playQueue(playlistSongs, idx); openPlayer() }
                    }
                }
            }
        }
    }

    // NAS 断线自动重连耗尽后的询问弹窗（v1.4.58）：
    // 已自动重试 5 次仍失败 → 用户选择"继续重试"或"切回本地"
    val retryPrompt by PlayerManager.retryPrompt.collectAsStateWithLifecycle()
    if (retryPrompt) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = Color(0xFF26282F),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            confirmButton = {
                TextButton(onClick = { PlayerManager.retryNow() }) {
                    Text(stringResource(R.string.retry_btn), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { PlayerManager.switchToLocalAfterFailure() }) {
                    Text(stringResource(R.string.switch_local_btn), color = Color.White.copy(alpha = 0.7f))
                }
            },
            title = { Text(stringResource(R.string.playback_failed_title)) },
            text = {
                Text(stringResource(R.string.playback_failed_msg), fontSize = 14.sp)
            }
        )
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
            // NAS 状态卡：当前音源 + 一键切换/进入（贴合内容宽度，右侧不留大块空白）
            Card(
                Modifier.wrapContentWidth().padding(horizontal = 12.dp, vertical = 12.dp),
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
                            if (currentSource == MusicSource.FEINIU)
                                stringResource(R.string.source_prefix, stringResource(R.string.source_feiniu))
                            else
                                stringResource(R.string.source_prefix, stringResource(R.string.source_local)),
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
                            if (currentSource == MusicSource.FEINIU)
                                stringResource(R.string.switch_local_music)
                            else
                                stringResource(R.string.open_nas_library),
                            color = Color(0xFF1F6FEB), fontSize = 13.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.drawer_media_lib), fontSize = 12.sp, color = Color(0xFF8C8C8C), modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
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
            Text(stringResource(R.string.drawer_system), fontSize = 12.sp, color = Color(0xFF8C8C8C), modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
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
            Text(stringResource(e.labelRes), fontSize = 15.sp)
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
                Text(song?.title ?: stringResource(R.string.not_playing), fontWeight = FontWeight.SemiBold, maxLines = 1, fontSize = 14.sp)
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
