package com.lianglei.nasmusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.customview.widget.ViewDragHelper
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
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
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

/** 迷你播放条高度：v1.5.18 起播放条固定在 HomeRoot 顶层，Scaffold 只留同高占位 */
private val MINI_PLAYER_HEIGHT = 56.dp

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

    // v1.5.17：全局更新弹窗（冷启动自动检查 / 设置页手动检查都会走到这里）
    UpdatePromptHost(context)

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
        // v1.5.8：恢复交给 PlayerManager 自己做幂等 + 重试（它还会在 controller 绑定、曲库变化时补一次），
        // UI 这边只负责"曲库一到就通知一声"，不再用 queue.isEmpty() 做一次性门槛。
        if (songs.isNotEmpty()) PlayerManager.restoreState(songs)
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

    Box(Modifier.fillMaxSize()) {
    AndroidView(
        factory = { ctx ->
            // v1.5.14 推动式：右侧内容容器，抽屉滑动时按同一比例右移
            val contentView = ComposeView(ctx).apply {
                // 底色跟随系统深浅色，避免 Compose 首帧绘制前透出窗口默认白
                val night = (ctx.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                setBackgroundColor(if (night) 0xFF000000.toInt() else 0xFFF2F3F5.toInt())
                setContent {
                    HomeContent(
                        nav = nav,
                        searchTriggerState = searchTriggerState,
                        drawerOpenState = drawerOpenState,
                        onOpenDrawer = { drawerLayoutRef?.openDrawer(GravityCompat.START) },
                        onCloseDrawer = { drawerLayoutRef?.closeDrawer(GravityCompat.START) }
                    )
                }
            }
            SwipeDrawerLayout(ctx).apply {
                // 推动式抽屉：无遮罩，内容被抽屉推开
                setScrimColor(android.graphics.Color.TRANSPARENT)
                addDrawerListener(object : DrawerLayout.DrawerListener {
                    override fun onDrawerStateChanged(state: Int) {}
                    // 抽屉露出多少，右侧内容就同步右移多少（1:1，位移量 = 抽屉宽度）
                    override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                        contentView.translationX = drawerWidthPx * slideOffset
                    }
                    override fun onDrawerOpened(drawerView: View) {
                        drawerOpenState.value = true
                        contentView.translationX = drawerWidthPx
                    }
                    override fun onDrawerClosed(drawerView: View) {
                        drawerOpenState.value = false
                        contentView.translationX = 0f
                    }
                })

                // 右侧主内容（Compose）
                addView(
                    contentView,
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

    // v1.5.18 固定播放条：迷你播放条画在抽屉层之上（页面被推开时它不跟着走），
    // 全局只有这一份，抽屉开合/页面切换都影响不到它的位置。
    val queue by PlayerManager.queue.collectAsStateWithLifecycle()
    val currentIndex by PlayerManager.currentIndex.collectAsStateWithLifecycle()
    val isPlaying by PlayerManager.isPlaying.collectAsStateWithLifecycle()
    val buffering by PlayerManager.buffering.collectAsStateWithLifecycle()
    MiniPlayer(
        song = queue.getOrNull(currentIndex),
        isPlaying = isPlaying,
        buffering = buffering,
        onClick = {
            context.startActivity(android.content.Intent(context, PlayerActivity::class.java))
            (context as? android.app.Activity)?.overridePendingTransition(R.anim.slide_in_bottom, 0)
        },
        onQueue = {
            context.startActivity(
                android.content.Intent(context, PlayerActivity::class.java).apply { putExtra("showQueue", true) }
            )
            (context as? android.app.Activity)?.overridePendingTransition(R.anim.slide_in_bottom, 0)
        },
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
    )
    }
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
    val playlistsLoading by SourceManager.playlistsLoading.collectAsStateWithLifecycle()

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
        containerColor = MaterialTheme.colorScheme.background,
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
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            // v1.5.18：真正的播放条移到 HomeRoot 顶层（固定不随抽屉推动），
            // 这里只留同高的占位，保证列表底部内边距（innerPadding）与之前完全一致，
            // 歌曲列表最后一行不会被播放条盖住。
            Spacer(Modifier.height(MINI_PLAYER_HEIGHT))
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
                    composable("songs") {
                        SongListScreen(
                            songs, loading, searchTrigger,
                            onPlay = { list, idx -> PlayerManager.playQueue(list, idx); openPlayer() },
                            onShuffle = { list -> PlayerManager.shufflePlay(list); openPlayer() }
                        )
                    }
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
                    composable("playlists") { PlaylistScreen(playlists = playlists, loading = playlistsLoading, onOpenPlaylist = { guid -> nav.navigate("playlist/$guid") }) }
                    composable("scan") { ScanSourceScreen() }
                    composable("library") { NasLibraryScreen(onBack = { nav.popBackStack() }, onOpenFeiniuLogin = { nav.navigate("fn-login") }) }
                    composable("fn-login") { FeiniuLoginScreen(onBack = { nav.popBackStack() }, onSuccess = { nav.popBackStack("library", false) }) }
                    composable("stats") { StatsScreen(songs, onBack = { nav.popBackStack() }, onPlay = { list, idx -> PlayerManager.playQueue(list, idx); openPlayer() }) }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                    composable("about") { AboutScreen() }
                    composable("artist/{name}") { backStackEntry ->
                        val name = backStackEntry.arguments?.getString("name") ?: ""
                        ArtistDetailScreen(name, songs, onPlay = { list, idx -> PlayerManager.playQueue(list, idx); openPlayer() }, onOpenAlbum = { albumId -> nav.navigate("album/$albumId") })
                    }
                    composable("album/{albumId}") { backStackEntry ->
                        val albumId = backStackEntry.arguments?.getString("albumId")?.toLongOrNull() ?: 0L
                        AlbumDetailScreen(albumId, songs, onPlay = { list, idx -> PlayerManager.playQueue(list, idx); openPlayer() }, onOpenArtist = { name -> if (name.isNotBlank()) nav.navigate("artist/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")) })
                    }
                    composable("folder/{name}") { backStackEntry ->
                        val name = backStackEntry.arguments?.getString("name") ?: ""
                        FolderDetailScreen(name, songs) { list, idx -> PlayerManager.playQueue(list, idx); openPlayer() }
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
                        // v1.5.11：歌单曲目要联网拉，拉取期间不要一上来就显示空列表
                        var playlistLoading by remember { mutableStateOf(currentSource == MusicSource.FEINIU) }
                        LaunchedEffect(guid, currentSource) {
                            if (currentSource == MusicSource.FEINIU) {
                                playlistSongs = SourceManager.fetchPlaylistTracks(guid)
                                playlistLoading = false
                            } else {
                                playlistSongs = songs
                                playlistLoading = false
                            }
                        }
                        val plName = playlists.find { it.guid == guid }?.name ?: stringResource(R.string.default_playlist)
                        if (playlistLoading && playlistSongs.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            PlaylistDetailScreen(plName, playlistSongs) { idx -> PlayerManager.playQueue(playlistSongs, idx); openPlayer() }
                        }
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

    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Column(
            // v1.5.19：抽屉内容可纵向滚动（小屏/大字号下条目不会挤不下），
            // 纵向手势由 Compose 消费，横向仍交给 DrawerLayout 关闭，两者互不抢
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(28.dp))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("NAS Music", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            // NAS 状态卡：当前音源 + 一键切换/进入（贴合内容宽度，右侧不留大块空白）
            Card(
                Modifier.wrapContentWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
            Text(stringResource(R.string.drawer_media_lib), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
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
            Text(stringResource(R.string.drawer_system), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
            DrawerGroup(systemEntries) { route ->
                onCloseDrawer()
                if (route != currentRoute) {
                    nav.navigate(route) { launchSingleTop = true }
                }
            }
            // 抽屉底部让出固定在底部的播放条高度，滚到最底也能看到最后一项
            Spacer(Modifier.height(MINI_PLAYER_HEIGHT))
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
private fun MiniPlayer(
    song: Song?,
    isPlaying: Boolean,
    buffering: Boolean,
    onClick: () -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().height(MINI_PLAYER_HEIGHT).padding(horizontal = 12.dp).clickable(onClick = onClick),
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
                Text(song?.artist ?: "—", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, fontSize = 12.sp)
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
 * 全屏跟手手势 DrawerLayout（v1.5.13）：
 *
 * 1. **跟手拉出**：DrawerLayout 内部用 ViewDragHelper 做拖动，但它的"边缘触发区"默认只有
 *    左缘 20dp。把该拖动器的边缘宽度补成整屏宽后，屏幕任意位置起手的右滑都会被它接管，
 *    抽屉跟着手指实时平移（推动式，内容同步被推开），不再"抬手后补一段系统动画"。
 * 2. **1/3 松手判定**：原生判定是 50%（外加速度阈值）。这里在抬手时读一次抽屉当前的
 *    露出比例：≥ 1/3 就确保展开，< 1/3 就确保弹回，覆盖原生结论（同一帧内改判，
 *    位置连续，不会看到反向跳）。
 * 3. **关闭手势保留**：抽屉打开后，面板外的左滑仍然直接关闭（原有操作习惯不变）。
 */
class SwipeDrawerLayout @JvmOverloads constructor(
    context: Context
) : DrawerLayout(context) {
    private var touchStartX = 0f
    private var touchStartY = 0f
    private val slop: Float get() = 24f * resources.displayMetrics.density

    /** 本次手势起手时抽屉是否已展开 */
    private var openedAtDown = false
    /** 本次手势是否应完全让给右侧页面（抽屉已展开且起手在页面区域） */
    private var gestureGoesToContent = false
    /** 本次手势起手点是否在"抽屉已展开时的右侧页面区域" */
    private var pageAreaAtDown = false
    /** 是否正在手动驱动"页面区域左滑关闭"的跟手位移 */
    private var manualDrag = false
    /** 手动拖动过程中抽屉露出的比例（1 = 完全展开，0 = 完全收起） */
    private var manualFrac = 1f

    init {
        // 开合状态一变就重算"边缘触发区"：收起=0（原生不抓边缘，开抽屉走手动判定），
        // 展开=只有抽屉宽度（右侧页面上的手势一律不归抽屉，避免上下滚动时把抽屉/页面带偏）
        addDrawerListener(object : DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) = applyEdgeSize()
            override fun onDrawerOpened(drawerView: View) = applyEdgeSize()
            override fun onDrawerClosed(drawerView: View) = applyEdgeSize()
            override fun onDrawerStateChanged(newState: Int) = applyEdgeSize()
        })
    }

    /** DrawerLayout 私有的左侧拖动器（ViewDragHelper）。取不到时全部退回原生行为。 */
    private val leftDragger: ViewDragHelper?
        get() = runCatching {
            val f = DrawerLayout::class.java.getDeclaredField("mLeftDragger")
            f.isAccessible = true
            f.get(this) as? ViewDragHelper
        }.getOrNull()

    /**
     * 边缘触发宽度：抽屉展开时缩回抽屉自身宽度（只有抽屉面板内的左滑才算关闭手势）；
     * 抽屉收起时**关闭原生边缘捕获**——开抽屉的手势改由 [dispatchTouchEvent] 里的手动
     * 判定接管（横向为主、位移超过 slop 才算）。
     *
     * v1.5.28 修复：之前收起态把边缘宽度补成整屏宽，ViewDragHelper 会把列表里每一次摁住
     * 都当成边缘起手，手指轻微右漂就把抽屉抓走拉开（表现为摁住一条列表项，整屏被推到
     * 最右、抽屉一直拉到边缘）。
     */
    private fun applyEdgeSize() {
        val w = width
        if (w <= 0) return
        val drawerW = leftDrawer()?.width ?: 0
        val edge = if (isDrawerOpen(GravityCompat.START) && drawerW > 0) drawerW else 0
        runCatching { leftDragger?.setEdgeSize(edge) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        applyEdgeSize()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        applyEdgeSize()
    }

    /**
     * 抽屉展开后，右侧主页面上的手势完全不交给抽屉：不拦截，让 Compose 列表正常上下滚动。
     * 画面上不会再有"页面被带得右移一下又瞬移回来"的抖动。
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                openedAtDown = isDrawerOpen(GravityCompat.START)
                val drawerW = leftDrawer()?.width ?: 0
                gestureGoesToContent = openedAtDown && ev.x > drawerW
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val blocked = gestureGoesToContent
                gestureGoesToContent = false
                if (blocked) return false
            }
        }
        if (gestureGoesToContent) return false
        return super.onInterceptTouchEvent(ev)
    }

    /** 左侧抽屉当前露出的比例：0 = 完全收起，1 = 完全展开。 */
    private fun drawerFraction(): Float {
        val d = leftDrawer() ?: return 0f
        val w = d.width
        if (w <= 0) return 0f
        val left = d.left + d.translationX
        return ((left + w) / w).coerceIn(0f, 1f)
    }

    private fun leftDrawer(): View? {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as? DrawerLayout.LayoutParams ?: continue
            if ((lp.gravity and Gravity.LEFT) == Gravity.LEFT) return child
        }
        return null
    }

    private fun draggingNow(): Boolean =
        runCatching { leftDragger?.viewDragState == ViewDragHelper.STATE_DRAGGING }.getOrDefault(false)

    /** DrawerLayout 里那个"非抽屉"的普通内容子 View（右侧主页面容器） */
    private fun contentView(): View? {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as? DrawerLayout.LayoutParams ?: continue
            if (lp.gravity == Gravity.NO_GRAVITY) return child
        }
        return null
    }

    /**
     * 跟手位移：抽屉按 1:1 往左退出屏幕，右侧页面同步往右挪同样距离。
     * frac = 1 表示抽屉完全展开（抽屉 translationX = 0，页面 translationX = 抽屉宽度）。
     */
    private fun applyManualFraction(frac: Float) {
        val d = leftDrawer() ?: return
        val w = d.width.toFloat()
        if (w <= 0f) return
        manualFrac = frac.coerceIn(0f, 1f)
        d.translationX = -w * (1f - manualFrac)
        contentView()?.translationX = w * manualFrac
    }

    /** 松手结算：打开拖过 1/3 就展开，关闭拖过 2/3 才收起；位移量都从当前值连续过渡，不跳变。 */
    private fun settleManualDrag() {
        val start = manualFrac
        val target = if (openedAtDown) {
            if (start > 2f / 3f) 1f else 0f
        } else {
            if (start >= 1f / 3f) 1f else 0f
        }
        ValueAnimator.ofFloat(start, target).apply {
            duration = 200L
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyManualFraction(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    manualDrag = false
                    val d = leftDrawer() ?: return
                    val w = d.width.toFloat()
                    if (target >= 1f) {
                        // 回到完全展开：先同步 DrawerLayout 内部开合状态，再把位移归位，
                        // 否则抽屉看着是开的、内部仍记为收起，后续点按页面不会正常收起
                        if (!isDrawerOpen(GravityCompat.START)) openDrawer(GravityCompat.START, false)
                        d.translationX = 0f
                        contentView()?.translationX = w
                    } else {
                        // 立即切到原生"关闭"状态（不播动画）：先让 DrawerLayout 把自己的
                        // 偏移挪到 -w，下一帧再清掉我们加的 translationX，避免闪一下抽屉
                        closeDrawer(GravityCompat.START, false)
                        contentView()?.translationX = 0f
                        post { leftDrawer()?.translationX = 0f }
                    }
                }
            })
            start()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            touchStartX = ev.x
            touchStartY = ev.y
            manualDrag = false
            // 抽屉展开、且手指落在抽屉面板右侧的页面区域：纵向手势给页面滚动，
            // 横向左滑则由本类接管，跟手把抽屉往回推
            pageAreaAtDown = isDrawerOpen(GravityCompat.START) &&
                ev.x > (leftDrawer()?.width ?: 0)
            return super.dispatchTouchEvent(ev)
        }

        if (ev.actionMasked == MotionEvent.ACTION_CANCEL && manualDrag) {
            settleManualDrag()
            return true
        }

        if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
            if (!manualDrag &&
                getDrawerLockMode(GravityCompat.START) == DrawerLayout.LOCK_MODE_UNLOCKED
            ) {
                val dx = ev.x - touchStartX
                val dy = ev.y - touchStartY
                val horizontal = kotlin.math.abs(dx) > slop &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f
                // 展开态：起于页面区域的左滑 → 跟手关闭；收起态：页面区域的右滑 → 跟手打开。
                // 纵向滚动、摁住不动（位移没超过 slop）都不接管，手势留给列表。
                val wantClose = pageAreaAtDown && dx < -slop
                val wantOpen = !openedAtDown && dx > slop
                if (horizontal && (wantClose || wantOpen)) {
                    manualDrag = true
                    // 先给页面一个 CANCEL，让列表停住，避免它和抽屉位移抢手势
                    val cancel = MotionEvent.obtain(ev)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
            }
            if (manualDrag) {
                val w = (leftDrawer()?.width ?: 0).toFloat()
                if (w > 0f) {
                    // 关闭：frac = 1 + dx/w（dx 为负）；打开：frac = dx/w
                    val frac = if (openedAtDown) 1f + (ev.x - touchStartX) / w
                              else (ev.x - touchStartX) / w
                    applyManualFraction(frac)
                }
                return true
            }
            return super.dispatchTouchEvent(ev)
        }

        if (ev.actionMasked != MotionEvent.ACTION_UP) {
            return super.dispatchTouchEvent(ev)
        }

        if (manualDrag) {
            settleManualDrag()
            return true
        }

        // 抬手前先记下"抽屉被拖到了哪里"。super 会按原生规则开始结算动画，
        // 我们在下一帧按 1/3 结论纠正，位置连续所以看不出反向。
        // 只有"从关到开"的手势才套用 1/3 规则；抽屉本来就是展开的（用户在拖关闭），
        // 交给原生判定（拖过半 / 有甩动速度就关），否则会把手势强行拉回展开。
        val frac = drawerFraction()
        val dragged = draggingNow() || (frac > 0f && frac < 1f)
        val wantOpen = frac >= 1f / 3f
        val closingFromOpen = openedAtDown
        val result = super.dispatchTouchEvent(ev)

        if (closingFromOpen) {
            // 抽屉本来就是展开的：拖动关闭已交原生结算，页面纵向滚动也已完全放行。
            // 这里只兜底"位移不足 slop 的轻扫"（跟手拖动没被触发时也能关掉抽屉）。
            if (pageAreaAtDown) {
                val dx = ev.x - touchStartX
                val dy = ev.y - touchStartY
                if (dx < -slop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f &&
                    getDrawerLockMode(GravityCompat.START) == DrawerLayout.LOCK_MODE_UNLOCKED
                ) closeDrawer(GravityCompat.START)
            }
            return result
        }

        if (dragged) {
            post {
                if (wantOpen) {
                    if (!isDrawerOpen(GravityCompat.START) &&
                        getDrawerLockMode(GravityCompat.START) == DrawerLayout.LOCK_MODE_UNLOCKED
                    ) openDrawer(GravityCompat.START)
                } else {
                    if (isDrawerOpen(GravityCompat.START)) closeDrawer(GravityCompat.START)
                }
            }
        } else {
            // 没进入拖动：保留"抽屉已打开时左滑直接关闭"的原有交互
            val dx = ev.x - touchStartX
            val dy = ev.y - touchStartY
            if (isDrawerOpen(GravityCompat.START) &&
                getDrawerLockMode(GravityCompat.START) == DrawerLayout.LOCK_MODE_UNLOCKED &&
                dx < -slop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f
            ) closeDrawer(GravityCompat.START)
        }
        return result
    }
}
