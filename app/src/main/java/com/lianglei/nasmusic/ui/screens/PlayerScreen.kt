package com.lianglei.nasmusic.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.lianglei.nasmusic.R
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.lianglei.nasmusic.data.Song
import coil.transform.Transformation
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import com.lianglei.nasmusic.player.PlayMode
import com.lianglei.nasmusic.player.PlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

data class LrcLine(val time: Long, val text: String)

/**
 * 下采样模糊变换：多次 1/2 缩放（BoxBlur 近似高斯模糊）后放大回原尺寸。
 * Coil 2.6.0 的 coil-base 不含 BlurTransformation，且 Modifier.blur 的实时 RenderEffect
 * 在层矩阵动画时会在部分设备产生残影。此实现输出静态模糊位图——动画只移动位图层，零 RenderEffect。
 */
class DownscaleBlurTransformation(private val levels: Int = 5) : Transformation {
    override val cacheKey: String = "downscaleBlur-$levels"

    override suspend fun transform(input: Bitmap, size: coil.size.Size): Bitmap {
        if (input.width <= 32 || input.height <= 32) return input
        var w = input.width
        var h = input.height
        var current = input
        repeat(levels) {
            if (w <= 32 || h <= 32) return current
            w = (w + 1) / 2
            h = (h + 1) / 2
            val next = Bitmap.createScaledBitmap(current, w, h, true)
            if (current !== input) current.recycle()
            current = next
        }
        val out = Bitmap.createScaledBitmap(current, input.width, input.height, true)
        if (current !== input && current !== out) current.recycle()
        return out
    }
}

fun parseLrc(text: String): List<LrcLine> {
    val lines = mutableListOf<LrcLine>()
    text.lines().forEach { line ->
        val matcher = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]").findAll(line)
        val content = line.replace(Regex("\\[\\d+:\\d+([.:]\\d+)?]"), "").trim()
        if (content.isNotEmpty()) {
            matcher.forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val ms = if (m.groupValues[3].isNotEmpty()) m.groupValues[3].toLong() * 10 else 0L
                lines.add(LrcLine(min * 60000 + sec * 1000 + ms, content))
            }
        }
    }
    return lines.sortedBy { it.time }
}

/** 假名（平假名/片假名/半角片假名）判断 */
fun isKana(c: Char): Boolean =
    (c in '\u3040'..'\u30FF') || (c in '\uFF66'..'\uFF9F')

/**
 * 拆分"同一行混排 日文原文+翻译"的歌词行：
 * 按空格/斜杠/顿号等常见分隔符切段，含假名的段 = 日文原文，不含假名且含字母的段 = 翻译。
 * 返回 Pair(日文原文, 翻译)；已是单语种行（纯日文/纯中文/纯英文）返回 Pair(原文本, null) 保持原样。
 */
fun splitLrcLine(text: String): Pair<String, String?> {
    // 1) 空格/符号分隔拆分（覆盖绝大多数"日文 中文"混排格式）
    val parts = text.split(Regex("[ 　/／|｜·・,，、]+")).filter { it.isNotBlank() }
    if (parts.size >= 2) {
        val jp = parts.filter { it.any { c -> isKana(c) } }
        val trans = parts.filter { it.none { c -> isKana(c) } && it.any { c -> c.isLetter() } }
        if (jp.isNotEmpty() && trans.isNotEmpty()) {
            return jp.joinToString("") to trans.joinToString(" ")
        }
    }
    // 2) 无空格连续混排兜底：最后一个假名之后连续 ≥3 个汉字视为中文翻译
    //    （≥3 保护：日文行末尾的汉字词如"景色""未来"只有 2 字，不会误拆）
    var lastKana = -1
    text.forEachIndexed { i, c -> if (isKana(c)) lastKana = i }
    if (lastKana >= 0) {
        var cnStart = -1
        var cnCount = 0
        for (i in lastKana + 1 until text.length) {
            val c = text[i]
            if (c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF') {
                if (cnStart < 0) cnStart = i
                cnCount++
            } else if (cnStart >= 0) {
                break
            }
        }
        if (cnCount >= 3) {
            val jp = text.substring(0, cnStart).trim()
            val cn = text.substring(cnStart).trim()
            if (jp.isNotBlank() && cn.isNotBlank()) return jp to cn
        }
    }
    return text to null
}

@Composable
fun PlayerScreen(onOpenQueue: () -> Unit = {}, backgroundRunning: Boolean = true) {
    val queue by PlayerManager.queue.collectAsStateWithLifecycle()
    val idx by PlayerManager.currentIndex.collectAsStateWithLifecycle()
    val playing by PlayerManager.isPlaying.collectAsStateWithLifecycle()
    val pos by PlayerManager.positionMs.collectAsStateWithLifecycle()
    val dur by PlayerManager.durationMs.collectAsStateWithLifecycle()
    val song = queue.getOrNull(idx)

    val ctx = androidx.compose.ui.platform.LocalContext.current

    // 播放模式（随机按钮循环切换）+ 定时关闭状态
    val playMode by PlayerManager.playMode.collectAsStateWithLifecycle()
    var sleepUntil by remember { mutableStateOf(0L) }        // SystemClock.elapsedRealtime 截止点，0=未启用
    var showSleepDialog by remember { mutableStateOf(false) }
    var sleepH by remember { mutableStateOf(0) }
    var sleepM by remember { mutableStateOf(0) }
    var remainingSec by remember { mutableStateOf(0) }
    // "更多" → 歌曲信息底部弹窗（先弹一半，上划全屏）
    var showTrackInfo by remember { mutableStateOf(false) }

    // 定时关闭倒计时：每秒刷新；到点停止播放并退出应用
    LaunchedEffect(sleepUntil) {
        if (sleepUntil <= 0L) return@LaunchedEffect
        while (sleepUntil > 0L) {
            remainingSec = ((sleepUntil - SystemClock.elapsedRealtime()) / 1000L).toInt()
            if (remainingSec <= 0) break
            delay(1000)
        }
        if (sleepUntil > 0L) {
            sleepUntil = 0L
            remainingSec = 0
            PlayerManager.stopPlayback()
            (ctx as? Activity)?.finishAffinity()
        }
    }

    fun startSleepTimer() {
        val totalMs = ((sleepH * 3600L) + (sleepM * 60L)) * 1000L
        if (totalMs <= 0L) return
        sleepUntil = SystemClock.elapsedRealtime() + totalMs
        remainingSec = (totalMs / 1000L).toInt()
    }

    fun openSystemEqualizer() {
        val intent = Intent("android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL")
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(ctx, ctx.getString(R.string.eq_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    var lrcLines by remember(song?.id) { mutableStateOf(emptyList<LrcLine>()) }
    LaunchedEffect(song?.id) {
        if (song == null) { lrcLines = emptyList(); return@LaunchedEffect }
        lrcLines = withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val file = File(song.data)
                val lrcFile = File(file.parentFile, file.nameWithoutExtension + ".lrc")
                val parsed = if (lrcFile.exists()) {
                    parseLrc(lrcFile.readText())
                } else if (file.exists()) {
                    val embedded = readEmbeddedLyrics(file)
                    if (embedded.isNotEmpty()) parseLrc(embedded) else emptyList()
                } else emptyList()
                // 拆分行：同一行混排"日文原文+翻译"的拆成两行（日文行 + 翻译行，同一时间戳）
                parsed.flatMap { line ->
                    val (jp, trans) = splitLrcLine(line.text)
                    if (trans != null) listOf(LrcLine(line.time, jp), LrcLine(line.time, trans))
                    else listOf(line)
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    // Active lyric line + karaoke progress within the line
    val activeLrcIndex = remember(lrcLines, pos) {
        var active = -1
        lrcLines.forEachIndexed { i, line ->
            if (line.time <= pos) active = i
        }
        active
    }
    val activeLrc = lrcLines.getOrNull(activeLrcIndex)
    // 下一句起点：跳过同时间戳的翻译行（拆分后同一 time 有日文+翻译两行）
    val nextLrcStart = lrcLines.firstOrNull { it.time > (activeLrc?.time ?: 0L) }?.time
        ?: ((activeLrc?.time ?: 0L) + 4000L)
    val lineProgress = if (activeLrc != null && nextLrcStart > activeLrc.time) {
        ((pos - activeLrc.time).toFloat() / (nextLrcStart - activeLrc.time).toFloat()).coerceIn(0f, 1f)
    } else 0f

    // 按时间戳分组（同 time 的 日文行+翻译行 为一组），供歌词面板分行渲染
    val lyricGroups = remember(lrcLines) {
        lrcLines.groupBy { it.time }.entries.sortedBy { it.key }.map { it.value }
    }
    var activeGroupIdx = -1
    lyricGroups.forEachIndexed { i, g -> if (g.first().time <= pos) activeGroupIdx = i }
    val activeGroup = lyricGroups.getOrNull(activeGroupIdx) ?: emptyList()
    // 当前组的高亮行：优先含假名的日文行（卡拉OK高亮）；纯中文/纯英文歌词组取第一行
    val activeJp = activeGroup.firstOrNull { l -> l.text.any { c -> isKana(c) } } ?: activeGroup.firstOrNull()

    val coverModel = song?.let {
        if (it.coverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(it.coverId)
        else "content://media/external/audio/albumart/${it.albumId}"
    }

    // 从封面提取多个主色（KMeans，缩小位图 IO 线程执行），作为底色 + 烟雾动画调色板
    // 复用 App 级全局 ImageLoader（App 实现 ImageLoaderFactory），不每屏新建实例
    val bgLoader = ctx.imageLoader
    val palette by produceState<List<Color>>(initialValue = emptyList(), coverModel) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val result = bgLoader.execute(
                    ImageRequest.Builder(ctx).data(coverModel).size(160).build()
                )
                val bmp = (result.drawable as? BitmapDrawable)?.bitmap
                if (bmp != null) dominantColors(bmp, 4).map { saturateColor(it, 1.5f) } else emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
    val coverBase = palette.firstOrNull()?.let { darkenToLuminance(it, 0.5f) } ?: Color(0xFF2A2A2E)

    // 根容器拦截已上移到 HomeRoot 浮层（条件拦截：显示时拦截、隐藏时不拦截），
    // 内部 Slider/IconButton 等子节点不受影响，正常响应。
    Box(
        Modifier.fillMaxSize().background(coverBase)
    ) {
        // 迷幻烟雾背景：颜色取自封面主色（KMeans 提取的红/黑/白/黄等），
        // 6 个径向渐变烟团缓慢漂移 + 呼吸，Plus 叠加出灰蒙蒙烟雾感；
        // running=false（播放页隐藏）时帧循环暂停，零消耗。
        SmokeBackground(palette = palette, running = backgroundRunning, centerFill = coverBase)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.32f),
                        Color.Black.copy(alpha = 0.10f),
                        Color.Black.copy(alpha = 0.38f)
                    )
                )
            )
        )
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(start = 24.dp, end = 24.dp, top = 8.dp)) {
            // 顶部信息栏：标题（静态单行省略，避免跑马灯裁剪错乱）+ 歌手 + 投屏图标
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        song?.title ?: "—",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        song?.artist ?: "",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.Filled.Cast, null,
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.height(8.dp))

            // 封面/歌词 左右滑动切换（两块玻璃跟手平移：封面左滑出屏、歌词右滑入，反向亦然）
            // 面板占剩余全部高度(weight 1f)，歌词块垂直居中后中心自动落在屏幕 ~43%（与 Salt 一致），
            // 且任何屏高都不溢出、不挤压底部控件
            // 关键几何：面板原点在屏幕左侧 24dp（Column padding）处，封面 fillMaxWidth 铺满面板宽 W。
            // 若只滑出 W，封面右边缘会停在屏幕 x=24dp，露出与左侧边距同宽的封面条（用户指出的"留边"）。
            // 修复：滑出量 = W + edgePx（edgePx = 24dp 边距），封面右边缘到达屏幕 x=0，完全干净出屏；
            // 歌词面板 offset = slide + W + edgePx：初始在右侧一屏外，到位时正好 = 0 占据原位；
            // 切换阈值 = (W+edgePx)/2：从任何一端回滑半程即切换，行程对称
            val edgePx = with(LocalDensity.current) { 24.dp.toPx() }
            var panelWidth by remember { mutableStateOf(0) }
            val slide = remember { Animatable(0f) } // 0 = 显示封面；-(panelWidth+edgePx) = 显示歌词
            // v1.4.35 手势判定彻底重写（此前多版不稳定根因）：
            // 旧逻辑基于"当前位置/起点位置"判断切换，存在两类问题：
            // ① 位置判断不对称（v1.4.29/31/32 从封面端实际需半程才切）；
            // ② scope.launch(默认 Main) 协程队列让 onDragEnd 读取的 slide.value 可能不是最新值（v1.4.33/34）。
            // v1.4.44 改为严格单向手势（用户要求）：封面态仅"左滑"跟手并切歌词，右滑完全忽略；
            // 歌词态仅"右滑"跟手并切回封面，左滑完全忽略。实现：onDragStart 记录起点所在端，
            // onHorizontalDrag 只放行"该端唯一有效方向"，onDragEnd/onDragCancel 只按"起点端+净位移方向"判定。
            // snapTo/stop/animateTo 全部用 Dispatchers.Main.immediate：调用即同步执行，跟手无延迟。
            var dragStart by remember { mutableStateOf(0f) }
            val scope = rememberCoroutineScope()
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .onSizeChanged { panelWidth = it.width }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                scope.launch(Dispatchers.Main.immediate) { slide.stop() }
                                dragStart = slide.value
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                if (panelWidth > 0) {
                                    val lim = -panelWidth.toFloat() - edgePx
                                    val startIsLyric = dragStart < lim / 2f
                                    // 单向：歌词态只放行右滑(>0)，封面态只放行左滑(<0)，反向忽略（不跟手）
                                    val allowed = if (startIsLyric) dragAmount > 0f else dragAmount < 0f
                                    if (allowed) {
                                        scope.launch(Dispatchers.Main.immediate) {
                                            // 跟手平移；下限 -(面板宽+左侧边距)，封面右边缘正好到屏幕 x=0
                                            slide.snapTo((slide.value + dragAmount).coerceIn(lim, 0f))
                                        }
                                    }
                                }
                            },
                            onDragEnd = {
                                scope.launch(Dispatchers.Main.immediate) {
                                    val limit = -panelWidth.toFloat() - edgePx
                                    val delta = slide.value - dragStart // 净位移：向左<0，向右>0
                                    val startIsLyric = dragStart < limit / 2f
                                    // 单向切换：歌词态仅右滑(净位移>阈值)回封面，否则保持歌词；
                                    // 封面态仅左滑(净位移<-阈值)切歌词，否则保持封面
                                    val target = if (startIsLyric) {
                                        if (delta > edgePx) 0f else limit
                                    } else {
                                        if (delta < -edgePx) limit else 0f
                                    }
                                    slide.animateTo(target, animationSpec = tween(260))
                                }
                            },
                            onDragCancel = {
                                scope.launch(Dispatchers.Main.immediate) {
                                    val limit = -panelWidth.toFloat() - edgePx
                                    val delta = slide.value - dragStart
                                    val startIsLyric = dragStart < limit / 2f
                                    val target = if (startIsLyric) {
                                        if (delta > edgePx) 0f else limit
                                    } else {
                                        if (delta < -edgePx) limit else 0f
                                    }
                                    slide.animateTo(target, animationSpec = tween(260))
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                // 封面面板：slide=0 原位；左滑时向左平移出屏（无边框，直接显示封面图）
                // 位移用 graphicsLayer（GPU 合成），不用 offset{}（每帧重新布局更耗、易抖动）
                AsyncImage(
                    model = song?.let {
                        if (it.coverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(it.coverId)
                        else "content://media/external/audio/albumart/${it.albumId}"
                    },
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        .graphicsLayer { translationX = slide.value }
                        .clip(RoundedCornerShape(12.dp))
                )
                // 歌词面板：初始在右侧一屏外（slide+panelWidth+edgePx），滑到位后正好占据原位；透明背景，直接叠在渐变上
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight()
                        .graphicsLayer { translationX = slide.value + panelWidth + edgePx.roundToInt() },
                    contentAlignment = Alignment.Center
                ) {
                    if (lrcLines.isEmpty()) {
                        Text(stringResource(R.string.no_lyrics), color = Color.White.copy(alpha = 0.5f), fontSize = 15.sp)
                    } else {
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            // 分组渲染：当前句(组)前 3 组 + 当前组 + 后 3 组；
                            // 组内每行分行显示（日文原文 + 翻译各行独占一行，同 Salt 奥华子样式）
                            val from = (activeGroupIdx - 3).coerceAtLeast(0)
                            val to = (activeGroupIdx + 3).coerceAtMost(lyricGroups.size - 1)
                            for (gi in from..to) {
                                val g = lyricGroups[gi]
                                for (li in g.indices) {
                                    val line = g[li]
                                    val isJp = line.text.any { c -> isKana(c) }
                                    val isLastInGroup = li == g.lastIndex
                                    if (gi == activeGroupIdx && line === activeJp) {
                                        // 当前句的日文原文：卡拉OK高亮
                                        KaraokeText(
                                            text = line.text,
                                            progress = lineProgress,
                                            activeColor = Color.White,
                                            dimColor = Color.White.copy(alpha = 0.6f),
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    } else {
                                        Text(
                                            line.text,
                                            color = Color.White.copy(alpha = if (gi == activeGroupIdx) 0.55f else 0.38f),
                                            fontSize = if (isJp) 20.sp else 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    // 间距：日文行后小间距（翻译紧跟），组末行正常间距（组与组分开）
                                    Spacer(Modifier.height(if (!isLastInGroup && isJp) 6.dp else 27.dp))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // Progress bar
            Slider(
                value = pos.toFloat().coerceIn(0f, dur.coerceAtLeast(1L).toFloat()),
                onValueChange = { PlayerManager.seekTo(it.roundToLong()) },
                valueRange = 0f..dur.coerceAtLeast(1L).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fmtTime(pos), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text(fmtTime(dur), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))

            // Play controls（对齐 Salt：上一曲/下一曲 48dp，播放 72dp）
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { PlayerManager.prev() }) {
                    Icon(Icons.Filled.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(48.dp))
                }
                IconButton(onClick = { PlayerManager.togglePlayPause() }) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null,
                        tint = Color.White, modifier = Modifier.size(72.dp))
                }
                IconButton(onClick = { PlayerManager.next() }) {
                    Icon(Icons.Filled.SkipNext, null, tint = Color.White, modifier = Modifier.size(48.dp))
                }
            }
            Spacer(Modifier.height(12.dp))

            // Bottom action row - 5 items evenly weighted so center aligns with play button（对齐 Salt：图标 32dp，细线 Outlined 风格）
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 1) 播放模式：顺序播放(灰Repeat) → 列表循环(Repeat) → 单曲循环(RepeatOne) → 随机(Shuffle)
                val modeIcon = when (playMode) {
                    PlayMode.SEQUENTIAL -> Icons.Outlined.Repeat
                    PlayMode.REPEAT_ALL -> Icons.Outlined.Repeat
                    PlayMode.REPEAT_ONE -> Icons.Outlined.RepeatOne
                    PlayMode.SHUFFLE -> Icons.Outlined.Shuffle
                }
                val modeTint = if (playMode == PlayMode.SEQUENTIAL) Color.White.copy(alpha = 0.35f) else Color.White
                IconButton(onClick = { PlayerManager.cyclePlayMode() }, modifier = Modifier.weight(1f)) {
                    Icon(modeIcon, null, tint = modeTint, modifier = Modifier.size(32.dp))
                }
                // 2) 定时关闭：点击弹出时间选择器；已定时时再次点击取消
                IconButton(
                    onClick = {
                        if (sleepUntil > 0L) { sleepUntil = 0L; remainingSec = 0 }
                        else { sleepH = 0; sleepM = 0; showSleepDialog = true }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Timer, null,
                            tint = if (sleepUntil > 0L) Color.White else Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(32.dp)
                        )
                        if (sleepUntil > 0L) {
                            Text(
                                if (remainingSec >= 3600) "${remainingSec / 3600}h" else "${(remainingSec / 60).coerceAtLeast(1)}m",
                                color = Color.White, fontSize = 9.sp, lineHeight = 10.sp
                            )
                        }
                    }
                }
                // 3) 均衡器：跳系统均衡器设置页
                IconButton(onClick = { openSystemEqualizer() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Equalizer, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(32.dp))
                }
                // 4) 歌单
                IconButton(onClick = onOpenQueue, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.QueueMusic, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(32.dp))
                }
                // 5) 更多：歌曲信息等选项
                IconButton(onClick = { showTrackInfo = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.MoreVert, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // 定时关闭时间选择器
    if (showSleepDialog) {
        AlertDialog(
            onDismissRequest = { showSleepDialog = false },
            containerColor = Color(0xFF26282F),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            confirmButton = {
                TextButton(onClick = { startSleepTimer(); showSleepDialog = false }) {
                    Text(stringResource(R.string.ok), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSleepDialog = false }) {
                    Text(stringResource(R.string.cancel), color = Color.White.copy(alpha = 0.7f))
                }
            },
            title = { Text(stringResource(R.string.sleep_timer)) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        IconButton(onClick = { if (sleepH < 9) sleepH++ }) {
                            Icon(Icons.Outlined.Add, null, tint = Color.White)
                        }
                        Text(stringResource(R.string.hour_unit, sleepH), fontSize = 16.sp)
                        IconButton(onClick = { if (sleepH > 0) sleepH-- }) {
                            Icon(Icons.Outlined.Remove, null, tint = Color.White)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        IconButton(onClick = { if (sleepM < 59) sleepM += 5 }) {
                            Icon(Icons.Outlined.Add, null, tint = Color.White)
                        }
                        Text(stringResource(R.string.minute_unit, sleepM), fontSize = 16.sp)
                        IconButton(onClick = { if (sleepM > 0) sleepM -= 5 }) {
                            Icon(Icons.Outlined.Remove, null, tint = Color.White)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (sleepH == 0 && sleepM == 0) stringResource(R.string.sleep_hint_pick)
                        else stringResource(R.string.sleep_hint_stop),
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        )
    }
    // 歌曲信息底部弹窗（更多按钮）
    if (showTrackInfo && song != null) {
        TrackInfoSheet(song, ctx, onDismiss = { showTrackInfo = false })
    }
}

@Composable
private fun KaraokeText(
    text: String,
    progress: Float,
    activeColor: Color,
    dimColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight
) {
    val smooth by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 350),
        label = "karaoke"
    )
    val total = text.length
    val colored = (total * smooth).toInt().coerceIn(0, total)
    val styled = remember(text, colored, activeColor, dimColor) {
        buildAnnotatedString {
            append(text)
            addStyle(SpanStyle(color = dimColor), 0, total)
            if (colored > 0) addStyle(SpanStyle(color = activeColor), 0, colored)
        }
    }
    Text(
        styled,
        fontSize = fontSize,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 读取文件内嵌歌词，统一返回 LRC 格式文本（行带 [mm:ss.cc] 时间戳）。
 * 支持：
 *  - MP3/MP2（ID3v2.2/2.3/2.4）：USLT 帧（v2.2 为 ULT）、SYLT 同步歌词帧（v2.2 为 SLT）
 *  - FLAC：Vorbis Comment 的 LYRICS / UNSYNCEDLYRICS / SYNCEDLYRICS
 *  - M4A/MP4/AAC/M4B：iTunes 风格 ©lyr atom
 *  - OGG/OPUS：暂未支持（返回空）
 * 纯文本（无时间戳）歌词按行生成 4 秒间隔时间戳，保证面板可逐行显示。
 */
private fun readEmbeddedLyrics(file: File): String = try {
    when (file.extension.lowercase()) {
        "mp3", "mp2", "mpa" -> readId3Lyrics(file)
        "flac" -> readFlacLyrics(file)
        "m4a", "mp4", "aac", "m4b", "m4r" -> readM4aLyrics(file)
        "ogg", "opus", "oga" -> "" // OGG 容器歌词暂未支持
        else -> ""
    }
} catch (e: Exception) {
    com.lianglei.nasmusic.util.CrashLogger.e("readEmbeddedLyrics failed", e)
    ""
}

/** ID3 同步安全整数（28-bit） */
private fun syncsafeInt(b: ByteArray, off: Int): Int =
    ((b[off].toInt() and 0x7F) shl 21) or ((b[off + 1].toInt() and 0x7F) shl 14) or
        ((b[off + 2].toInt() and 0x7F) shl 7) or (b[off + 3].toInt() and 0x7F)

/** 32-bit 大端整数 */
private fun be32Int(b: ByteArray, off: Int): Int =
    ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
        ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

/** 32-bit 小端整数 */
private fun le32Int(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

/**
 * ID3 文本解码。encoding：0=ISO-8859-1，1=UTF-16(带BOM)，2=UTF-16BE，3=UTF-8。
 * 带 BOM 时优先按 BOM 判断实际字节序。
 */
private fun id3Decode(b: ByteArray, start: Int, end: Int, encoding: Int): String {
    if (start < 0 || end > b.size || start > end) return ""
    val s = b.copyOfRange(start, end)
    return when {
        s.size >= 2 && s[0] == 0xFF.toByte() && s[1] == 0xFE.toByte() ->
            String(s, 2, s.size - 2, Charsets.UTF_16LE)
        s.size >= 2 && s[0] == 0xFE.toByte() && s[1] == 0xFF.toByte() ->
            String(s, 2, s.size - 2, Charsets.UTF_16BE)
        encoding == 0 -> String(s, Charsets.ISO_8859_1)
        encoding == 1 -> String(s, Charsets.UTF_16)
        encoding == 2 -> String(s, Charsets.UTF_16BE)
        else -> String(s, Charsets.UTF_8)
    }
}

/** 返回 ID3 帧内 null 结尾字符串（descriptor/歌词行）结束后的位置；找不到返回 -1 */
private fun id3Terminator(b: ByteArray, start: Int, encoding: Int): Int {
    val nulLen = if (encoding == 1 || encoding == 2) 2 else 1
    var p = start
    while (p + nulLen <= b.size) {
        val isNull = if (nulLen == 2) b[p].toInt() == 0 && b[p + 1].toInt() == 0 else b[p].toInt() == 0
        if (isNull) return p + nulLen
        p += nulLen
    }
    return -1
}

/** 纯文本歌词 → 逐行 4 秒间隔的 LRC；已含时间戳的行原样保留 */
private fun plainToLrc(text: String): String {
    if (text.isBlank()) return ""
    val timeTag = Regex("\\[\\d+:\\d+([.:]\\d+)?]")
    val sb = StringBuilder()
    var t = 0L
    text.lines().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@forEach
        if (timeTag.containsMatchIn(line)) sb.append(line).append('\n')
        else {
            sb.append(fmtLrcTime(t)).append(line).append('\n')
            t += 4000L
        }
    }
    return sb.toString()
}

private fun fmtLrcTime(ms: Long): String {
    val m = ms / 60000
    val s = (ms % 60000) / 1000
    val cs = (ms % 1000) / 10
    return String.format(java.util.Locale.US, "[%02d:%02d.%02d]", m, s, cs)
}

/** 读取 MP3 的 ID3v2 内嵌歌词（USLT/ULT + SYLT/SLT），返回 LRC 文本 */
private fun readId3Lyrics(file: File): String {
    file.inputStream().use { fis ->
        val header = ByteArray(10)
        if (fis.read(header) != 10) return ""
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return ""
        val major = header[3].toInt() and 0xFF
        val flags = header[5].toInt() and 0xFF
        val tagSize = syncsafeInt(header, 6)
        if (tagSize <= 0) return ""
        val data = ByteArray(tagSize)
        if (fis.read(data) != tagSize) return ""
        var pos = 0
        // 跳过 Extended header（v2.3：4 字节大端 size 不含自身；v2.4：4 字节 syncsafe size 含自身）
        if (flags and 0x40 != 0 && pos + 10 <= data.size) {
            val extSize = if (major >= 4) syncsafeInt(data, 0) else be32Int(data, 0)
            if (extSize > 0) pos += if (major >= 4) extSize else 4 + extSize
        }
        val frameIdLen = if (major == 2) 3 else 4
        val frameHeaderLen = if (major == 2) 6 else 10
        var uslt = ""
        val sylt = StringBuilder()
        while (pos + frameHeaderLen <= data.size) {
            val frameId = String(data, pos, frameIdLen, Charsets.ISO_8859_1)
            val frameSize = if (major == 2) {
                ((data[pos + 3].toInt() and 0xFF) shl 16) or ((data[pos + 4].toInt() and 0xFF) shl 8) or (data[pos + 5].toInt() and 0xFF)
            } else if (major >= 4) syncsafeInt(data, pos + 4) else be32Int(data, pos + 4)
            if (frameSize <= 0 || pos + frameHeaderLen + frameSize > data.size) break
            val body = data.copyOfRange(pos + frameHeaderLen, pos + frameHeaderLen + frameSize)
            val isUslt = if (major == 2) frameId == "ULT" else frameId == "USLT"
            val isSylt = if (major == 2) frameId == "SLT" else frameId == "SYLT"
            if (isUslt && body.size >= 5) {
                val enc = body[0].toInt() and 0xFF
                val descEnd = id3Terminator(body, 4, enc) // 跳过 encoding(1)+language(3)+descriptor
                if (descEnd in 4..body.size) {
                    val lyrics = id3Decode(body, descEnd, body.size, enc).trim()
                    if (lyrics.isNotEmpty()) { uslt = lyrics; break }
                }
            } else if (isSylt && body.size >= 7) {
                val enc = body[0].toInt() and 0xFF
                var p = id3Terminator(body, 6, enc) // 跳过 encoding(1)+language(3)+format(1)+contentType(1)+descriptor
                if (p < 0) p = 7
                // 内容区：[text \0][time: 4 字节大端毫秒] 反复
                while (p < body.size) {
                    val textEnd = id3Terminator(body, p, enc)
                    if (textEnd < 0 || textEnd > body.size) break
                    val text = id3Decode(body, p, textEnd, enc).trim()
                    if (text.isNotEmpty() && textEnd + 4 <= body.size) {
                        sylt.append(fmtLrcTime(be32Int(body, textEnd).toLong() and 0xFFFFFFFFL)).append(text).append('\n')
                    }
                    p = textEnd + 4
                }
            }
            pos += frameHeaderLen + frameSize
        }
        if (uslt.isNotEmpty()) return plainToLrc(uslt)
        return plainToLrc(sylt.toString())
    }
}

/** 读取 FLAC 的 Vorbis Comment 歌词（LYRICS/UNSYNCEDLYRICS/SYNCEDLYRICS），返回 LRC 文本 */
private fun readFlacLyrics(file: File): String {
    file.inputStream().use { fis ->
        val magic = ByteArray(4)
        if (fis.read(magic) != 4 || String(magic, Charsets.ISO_8859_1) != "fLaC") return ""
        var lyrics = ""
        while (true) {
            val bh = ByteArray(4)
            if (fis.read(bh) != 4) break
            val type = bh[0].toInt() and 0x7F
            val len = ((bh[1].toInt() and 0xFF) shl 16) or ((bh[2].toInt() and 0xFF) shl 8) or (bh[3].toInt() and 0xFF)
            if (len <= 0 || len > 16 * 1024 * 1024) break
            val payload = ByteArray(len)
            if (fis.read(payload) != len) break
            if (type == 4) { // VORBIS_COMMENT
                var p = 0
                if (p + 4 > payload.size) break
                val vendorLen = le32Int(payload, p); p += 4
                if (vendorLen < 0 || vendorLen > payload.size - p) break
                p += vendorLen
                if (p + 4 > payload.size) break
                val count = le32Int(payload, p); p += 4
                if (count < 0 || count > 1024) break
                repeat(count) {
                    if (p + 4 > payload.size) return@repeat
                    val clen = le32Int(payload, p); p += 4
                    if (clen < 0 || clen > payload.size - p) return@repeat
                    val keyVal = String(payload, p, clen, Charsets.UTF_8)
                    p += clen
                    val idx = keyVal.indexOf('=')
                    if (idx > 0) {
                        val key = keyVal.substring(0, idx).uppercase()
                        if ((key == "LYRICS" || key == "UNSYNCEDLYRICS" || key == "SYNCEDLYRICS") && lyrics.isEmpty()) {
                            lyrics = keyVal.substring(idx + 1)
                        }
                    }
                }
                if (lyrics.isNotEmpty()) break
            }
            if (bh[0].toInt() and 0x80 != 0) break // last metadata block
        }
        return plainToLrc(lyrics)
    }
}

/** 在 [start,end) 内查找指定类型 box，返回其 payload 区（起始、结束）；找不到返回 null */
private fun findM4aBox(raf: java.io.RandomAccessFile, start: Long, end: Long, type: String): Pair<Long, Long>? {
    var p = start
    while (p + 8 <= end) {
        raf.seek(p)
        var size = raf.readInt().toLong() and 0xFFFFFFFFL
        val typeBytes = ByteArray(4)
        raf.readFully(typeBytes)
        val t = String(typeBytes, Charsets.ISO_8859_1)
        val headerLen = if (size == 1L) {
            size = raf.readLong()
            16L
        } else 8L
        val boxEnd = if (size == 0L) end else p + size
        if (size != 0L && boxEnd > end) return null
        if (t == type) return Pair(p + headerLen, boxEnd)
        if (size == 0L) return null
        p = boxEnd
    }
    return null
}

/** 读取 M4A/MP4 的 iTunes ©lyr 内嵌歌词，返回 LRC 文本 */
private fun readM4aLyrics(file: File): String {
    java.io.RandomAccessFile(file, "r").use { raf ->
        val len = raf.length()
        val moov = findM4aBox(raf, 0L, len, "moov") ?: return ""
        val udta = findM4aBox(raf, moov.first, moov.second, "udta") ?: return ""
        val meta = findM4aBox(raf, udta.first, udta.second, "meta") ?: return ""
        // meta 是 fullbox：version/flags 占 4 字节，子 box 从 +4 开始
        val ilst = findM4aBox(raf, meta.first + 4, meta.second, "ilst") ?: return ""
        val lyr = findM4aBox(raf, ilst.first, ilst.second, "\u00A9lyr") ?: return ""
        // ©lyr 的 payload 是 data box：[type(4)][locale(4)] + 文本
        val data = findM4aBox(raf, lyr.first, lyr.second, "data")
        val textStart = if (data != null) data.first + 8 else lyr.first + 8
        val textEnd = if (data != null) data.second else lyr.second
        if (textStart >= textEnd) return ""
        val n = (textEnd - textStart).toInt().coerceAtMost(4 * 1024 * 1024)
        val bytes = ByteArray(n)
        raf.seek(textStart)
        raf.readFully(bytes)
        return plainToLrc(String(bytes, Charsets.UTF_8))
    }
}

private fun fmtTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val s = ms / 1000
    return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}

/** KMeans 提取 k 个主色（输入为缩小后的封面位图），按像素占比降序返回 */
fun dominantColors(bmp: Bitmap, k: Int): List<Color> {
    val pts = ArrayList<IntArray>()
    var x = 0
    while (x < bmp.width) {
        var y = 0
        while (y < bmp.height) {
            val p = bmp.getPixel(x, y)
            pts.add(intArrayOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF))
            y++
        }
        x++
    }
    if (pts.isEmpty()) return listOf(Color(0xFF2A2A2E))
    val centers = ArrayList<IntArray>()
    for (i in 0 until k) centers.add(pts[(pts.size * i) / k].copyOf())
    val counts = IntArray(k)
    repeat(24) {
        val sums = Array(k) { LongArray(3) }
        counts.fill(0)
        for (p in pts) {
            var best = 0
            var bestD = Int.MAX_VALUE
            for (ci in centers.indices) {
                val c = centers[ci]
                val d = (p[0] - c[0]) * (p[0] - c[0]) + (p[1] - c[1]) * (p[1] - c[1]) + (p[2] - c[2]) * (p[2] - c[2])
                if (d < bestD) { bestD = d; best = ci }
            }
            val s = sums[best]
            s[0] = s[0] + p[0].toLong()
            s[1] = s[1] + p[1].toLong()
            s[2] = s[2] + p[2].toLong()
            counts[best]++
        }
        for (ci in centers.indices) {
            if (counts[ci] > 0) {
                val cen = centers[ci]
                cen[0] = (sums[ci][0] / counts[ci]).toInt()
                cen[1] = (sums[ci][1] / counts[ci]).toInt()
                cen[2] = (sums[ci][2] / counts[ci]).toInt()
            }
        }
    }
    return centers.indices.sortedByDescending { counts[it] }.map { ci ->
        val c = centers[ci]
        Color(c[0] / 255f, c[1] / 255f, c[2] / 255f)
    }
}

/**
 * 迷幻烟雾背景：全屏连续线性渐变（封面主色提亮至最低亮度 0.42 后取 3 色），
 * 色带方向由 phase 驱动绕屏幕中心极缓慢旋转（首尾同色无缝循环），
 * 整屏每个像素都被渐变覆盖 → 几何上不存在圆斑/暗洞；
 * 中央叠一层低 alpha 提亮色柔光，恒亮于底色。
 * phase 由真实帧时间差值驱动（帧率无关），running=false 时暂停（播放页隐藏零消耗），
 * 收起再弹出时从当前相位续走，不跳变。
 */
@Composable
fun SmokeBackground(palette: List<Color>, running: Boolean, centerFill: Color = Color(0xFF2A2A2E)) {
    val phase = remember { Animatable(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - last).toFloat() / 1e9f   // 秒（帧差，float 精确）
            last = now
            // 每秒推进 0.06 → 主频团约 40~90s 一个来回，呼吸约 4 分钟（烟雾缭绕级缓慢）
            phase.snapTo((phase.value + dt * 0.06f) % 1000f)
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val t = phase.value
        val cw = size.width
        val ch = size.height
        // 调色板：封面主色统一提亮到最低亮度 0.42（保留色相），任何封面都不会产生暗色
        val cs = if (palette.isEmpty())
            listOf(Color(0xFF6A6A6E), Color(0xFF9A7A4B), Color(0xFF5E7A6A))
        else palette.take(3).map { lightenIfDark(it, 0.42f) }
        // 3 主色线性插值 → 13 个平滑停靠点（首尾同色无缝循环，c0→c1→c2→c0）：
        // 每段颜色差更小、过渡更细，配合大幅放大的渐变跨度 → 彩条（banding）弱化
        val cols = ArrayList<Color>(13)
        for (i in 0 until 13) {
            val pos = i / 12f * 3f
            val seg = (pos.toInt()).coerceIn(0, 2)   // i=12 → pos=3.0 → seg 钳制到 2，收尾回到 cs[0]（首尾同色闭环）
            val k = pos - seg
            val s = (seg + 1) % 3
            val c0 = cs[seg]
            val c1 = cs[s]
            cols.add(
                Color(
                    c0.red + (c1.red - c0.red) * k,
                    c0.green + (c1.green - c0.green) * k,
                    c0.blue + (c1.blue - c0.blue) * k
                ).copy(alpha = 0.55f)
            )
        }
        // 全屏线性渐变：跨度大幅放大（±1.6 倍对角线）→ 同一色带覆盖范围远大于屏幕，
        // 屏幕只截取渐变的极小一段，颜色随位置变化极缓，肉眼看不到色阶断层；
        // phase 驱动渐变方向极缓慢旋转形成流动感
        val ang = t * 0.05f
        val R = maxOf(cw, ch) * 1.6f
        val dx = cos(ang) * R
        val dy = sin(ang) * R
        drawRect(
            brush = Brush.linearGradient(
                colors = cols,
                start = Offset(cw * 0.5f - dx, ch * 0.5f - dy),
                end = Offset(cw * 0.5f + dx, ch * 0.5f + dy)
            ),
            size = size
        )
        // 中央柔和光晕：亮色低 alpha，给中央一点封面色氛围（恒亮于底色，非实心非暗色）
        val centerCol = lightenIfDark(centerFill, 0.52f)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    centerCol.copy(alpha = 0.25f),
                    centerCol.copy(alpha = 0.10f),
                    centerCol.copy(alpha = 0f)
                ),
                center = Offset(cw * 0.5f, ch * 0.5f),
                radius = cw * 0.55f
            ),
            radius = cw * 0.55f,
            center = Offset(cw * 0.5f, ch * 0.5f)
        )
    }
}

/** 压暗到指定最大亮度（0~1），保证白字可读 */
private fun darkenToLuminance(c: Color, maxLum: Float): Color {
    val lum = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
    val f = if (lum > maxLum) maxLum / lum else 1f
    return Color(c.red * f, c.green * f, c.blue * f)
}

/** 暗色提亮：低于 minLum 时按比例提升 RGB（保留色相），让暗封面的烟雾团也能"发光"而非黑洞 */
private fun lightenIfDark(c: Color, minLum: Float): Color {
    val lum = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
    if (lum >= minLum) return c
    val f = minLum / lum
    return Color(c.red * f, c.green * f, c.blue * f)
}

/** HSV 饱和度增强：让烟雾颜色更鲜明、可分辨（红就是红、黄就是黄），避免多团叠加后被冲灰 */
private fun saturateColor(c: Color, factor: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(
        android.graphics.Color.rgb(
            (c.red * 255f).toInt().coerceIn(0, 255),
            (c.green * 255f).toInt().coerceIn(0, 255),
            (c.blue * 255f).toInt().coerceIn(0, 255)
        ),
        hsv
    )
    hsv[1] = (hsv[1] * factor).coerceAtMost(1f)
    val rgb = android.graphics.Color.HSVToColor(hsv)
    return Color(
        ((rgb shr 16) and 0xFF) / 255f,
        ((rgb shr 8) and 0xFF) / 255f,
        (rgb and 0xFF) / 255f
    )
}

// ── 歌曲信息底部弹窗（v1.4.62 重写）：不再使用 material3 ModalBottomSheet ──
// material3 1.2.1 的弹窗在独立窗口(ModalBottomSheetWindow)中组合，其返回键处理无法从外部定制：
//   · shouldDismissOnBackPress=true（默认）：API33+ 注册 OnBackInvokedCallback，全屏状态下按返回只
//     partialExpand() 收回半屏，再按一次才收起（"返回键只收一半"）；
//   · false：窗口不处理返回，但焦点窗口会把返回事件吞掉，Activity 层 BackHandler 永远收不到（v1.4.61
//     彻底失效）。
// 故改为自实现面板：直接在页面组合内绘制（无独立窗口），返回键必然到达 Activity 的
// OnBackPressedDispatcher，一次返回即平滑收起关闭；交互复刻原弹窗：半屏弹出 → 内容上滑展开全屏 →
// 遮罩/返回键一次收起。
@Composable
private fun TrackInfoSheet(song: Song, ctx: android.content.Context, onDismiss: () -> Unit) {
    var info by remember { mutableStateOf<com.lianglei.nasmusic.data.TrackInfo?>(null) }
    LaunchedEffect(song.id) {
        info = com.lianglei.nasmusic.data.trackInfoOf(song, ctx)
    }
    var expanded by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    val sheetFraction by animateFloatAsState(
        targetValue = when { dismissing -> 0f; expanded -> 1f; else -> 0.45f },
        animationSpec = tween(300),
        label = "sheetFraction"
    )
    LaunchedEffect(sheetFraction) {
        if (dismissing && sheetFraction <= 0.01f) onDismiss()
    }
    val scrollState = rememberScrollState()
    // 滚动联动（复刻 ModalBottomSheet）：内容滚到顶后继续上拉 → 面板展开全屏；
    // 全屏且内容滚到顶后下拉 → 面板收回半屏
    val nestedScroll = remember(scrollState) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (!expanded && source == NestedScrollSource.Drag && dy < 0f && scrollState.value <= 0) {
                    expanded = true
                    return Offset(0f, dy)
                }
                return Offset.Zero
            }
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (expanded && source == NestedScrollSource.Drag && dy > 0f && scrollState.value <= 0) {
                    expanded = false
                    return Offset(0f, dy)
                }
                return Offset.Zero
            }
        }
    }
    fun dismiss() { dismissing = true }
    // 返回键：面板在页面组合内（无独立窗口），返回事件必然到达 Activity 的 OnBackPressedDispatcher，
    // 一次返回直接平滑收起关闭（面板动画到 0 后触发 onDismiss）
    BackHandler { dismiss() }
    Box(Modifier.fillMaxSize()) {
        // 遮罩：点击一次直接收起
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    alpha = if (dismissing) sheetFraction * 0.45f
                    else (0.30f + 0.15f * sheetFraction).coerceIn(0f, 0.5f)
                }
                .background(Color.Black)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { dismiss() }
        )
        // 面板：底部对齐，半屏 45% 屏高 → 全屏 100%
        Surface(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(sheetFraction)
                .nestedScroll(nestedScroll)
                .verticalScroll(scrollState),
            color = Color(0xFF26282F),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 32.dp)) {
                val it = info
                // 拖拽把手
                Box(
                    Modifier.align(Alignment.CenterHorizontally)
                        .width(36.dp).height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.3f))
                )
                Spacer(Modifier.height(18.dp))
                Text(stringResource(R.string.track_info_title), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (it == null) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.loading), color = Color.White.copy(alpha = 0.6f))
                    }
                } else {
                    TrackInfoRow(stringResource(R.string.info_label_title), it.title)
                    TrackInfoRow(stringResource(R.string.info_label_artist), it.artist)
                    TrackInfoRow(stringResource(R.string.info_label_album), it.album)
                    TrackInfoRow(stringResource(R.string.info_label_source), it.source)
                    TrackInfoRow(stringResource(R.string.info_label_playcount), it.playCount.toString())
                    TrackInfoRow(stringResource(R.string.info_label_duration), it.durationText)
                    TrackInfoRow(stringResource(R.string.info_label_bitrate), it.bitrateText)
                    TrackInfoRow(stringResource(R.string.info_label_samplerate), it.sampleRateText)
                    TrackInfoRow(stringResource(R.string.info_label_size), it.sizeText)
                    TrackInfoRow(stringResource(R.string.info_label_format), it.formatText)
                    TrackInfoRow(stringResource(R.string.info_label_dir), it.directory.ifEmpty { "—" })
                }
            }
        }
    }
}

@Composable
private fun TrackInfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, modifier = Modifier.width(76.dp))
        Text(value, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}
