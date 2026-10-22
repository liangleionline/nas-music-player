package com.lianglei.nasmusic.ui.screens

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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import coil.request.ImageRequest
import coil.transform.Transformation
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import com.lianglei.nasmusic.player.PlayerManager
import kotlinx.coroutines.Dispatchers
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
                    val embedded = readId3Uslt(file)
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
    val bgLoader = remember { ImageLoader(ctx) }
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
            // 新逻辑：onDragStart 记录起点值，onDragEnd 用"净位移方向"判定——
            //   净位移向左(<0) → 切歌词；净位移向右(>0) → 回封面；
            //   净位移 < 24dp（误触保护）→ 保持起点状态。
            // 方向驱动天然双向对称，与当前位置、中间态、协程时序全部无关。
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
                                    scope.launch(Dispatchers.Main.immediate) {
                                        // 跟手平移；下限 -(面板宽+左侧边距)，封面右边缘正好到屏幕 x=0
                                        slide.snapTo((slide.value + dragAmount).coerceIn(-panelWidth.toFloat() - edgePx, 0f))
                                    }
                                }
                            },
                            onDragEnd = {
                                scope.launch(Dispatchers.Main.immediate) {
                                    val limit = -panelWidth.toFloat() - edgePx
                                    val delta = slide.value - dragStart // 净位移：向左<0，向右>0
                                    val target = if (kotlin.math.abs(delta) < edgePx) {
                                        // 误触保护（<24dp）：保持起点状态
                                        if (dragStart < limit / 2f) 0f else limit
                                    } else {
                                        // 方向驱动：左滑切歌词、右滑回封面（对称）
                                        if (delta < 0f) limit else 0f
                                    }
                                    slide.animateTo(target, animationSpec = tween(260))
                                }
                            },
                            onDragCancel = {
                                scope.launch(Dispatchers.Main.immediate) {
                                    val limit = -panelWidth.toFloat() - edgePx
                                    val delta = slide.value - dragStart
                                    val target = if (kotlin.math.abs(delta) < edgePx) {
                                        if (dragStart < limit / 2f) 0f else limit
                                    } else {
                                        if (delta < 0f) limit else 0f
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
                        Text("暂无歌词", color = Color.White.copy(alpha = 0.5f), fontSize = 15.sp)
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

            // Bottom action row - 5 items evenly weighted so center aligns with play button（对齐 Salt：图标 32dp）
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                listOf(
                    Icons.Filled.Shuffle to {},
                    Icons.Filled.Timer to {},
                    Icons.Filled.Equalizer to {},
                    Icons.Filled.QueueMusic to { onOpenQueue() },
                    Icons.Filled.MoreVert to {}
                ).forEach { (icon, action) ->
                    IconButton(onClick = action, modifier = Modifier.weight(1f)) {
                        Icon(icon, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(32.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
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

private fun readId3Uslt(file: File): String {
    return try {
        file.inputStream().use { fis ->
            val header = ByteArray(10)
            if (fis.read(header) != 10) return ""
            if (!(header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte())) return ""
            val major = header[3].toInt() and 0xFF
            val tagSize = ((header[6].toInt() and 0x7F) shl 21) or ((header[7].toInt() and 0x7F) shl 14) or ((header[8].toInt() and 0x7F) shl 7) or (header[9].toInt() and 0x7F)
            val data = ByteArray(tagSize)
            if (fis.read(data) != tagSize) return ""
            var pos = 0
            while (pos + 10 <= data.size) {
                val frameId = String(data, pos, 4, Charsets.ISO_8859_1)
                val frameSize = if (major == 4) {
                    ((data[pos+4].toInt() and 0x7F) shl 21) or ((data[pos+5].toInt() and 0x7F) shl 14) or ((data[pos+6].toInt() and 0x7F) shl 7) or (data[pos+7].toInt() and 0x7F)
                } else {
                    ((data[pos+4].toInt() and 0xFF) shl 24) or ((data[pos+5].toInt() and 0xFF) shl 16) or ((data[pos+6].toInt() and 0xFF) shl 8) or (data[pos+7].toInt() and 0xFF)
                }
                if (frameSize <= 0 || pos + 10 + frameSize > data.size) break
                if (frameId == "USLT") {
                    val encoding = data[pos+10].toInt() and 0xFF
                    var p = pos + 14 // skip encoding(1) + language(3)
                    val nullLen = if (encoding == 1 || encoding == 2) 2 else 1
                    while (p + nullLen <= pos + 10 + frameSize) {
                        val isNull = if (nullLen == 2) data[p].toInt() == 0 && data[p+1].toInt() == 0 else data[p].toInt() == 0
                        if (isNull) { p += nullLen; break }
                        p += nullLen
                    }
                    val lyricsBytes = data.copyOfRange(p, pos + 10 + frameSize)
                    val hexPreview = lyricsBytes.take(12).joinToString(" ") { "%02X".format(it) }
                    com.lianglei.nasmusic.util.CrashLogger.log("USLT encoding=$encoding, first12hex=$hexPreview")
                    val result: String = when {
                        lyricsBytes.size >= 2 && lyricsBytes[0] == 0xFF.toByte() && lyricsBytes[1] == 0xFE.toByte() ->
                            String(lyricsBytes, 2, lyricsBytes.size - 2, Charsets.UTF_16LE)
                        lyricsBytes.size >= 2 && lyricsBytes[0] == 0xFE.toByte() && lyricsBytes[1] == 0xFF.toByte() ->
                            String(lyricsBytes, 2, lyricsBytes.size - 2, Charsets.UTF_16BE)
                        encoding == 1 -> String(lyricsBytes, Charsets.UTF_16)
                        encoding == 2 -> String(lyricsBytes, Charsets.UTF_16BE)
                        encoding == 3 -> String(lyricsBytes, Charsets.UTF_8)
                        else -> String(lyricsBytes, Charsets.UTF_8)
                    }
                    com.lianglei.nasmusic.util.CrashLogger.log("USLT decoded: ${result.take(200)}")
                    return result.trim()
                }
                pos += 10 + frameSize
            }
            ""
        }
    } catch (e: Exception) {
        com.lianglei.nasmusic.util.CrashLogger.e("readId3Uslt failed", e)
        ""
    }
}

private fun fmtTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val s = ms / 1000
    return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}

/** 取封面平均主色 */
private fun dominantColorOf(bmp: Bitmap): Color {
    var r = 0L; var g = 0L; var b = 0L; var n = 0L
    var x = 0
    while (x < bmp.width) {
        var y = 0
        while (y < bmp.height) {
            val p = bmp.getPixel(x, y)
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
            n++
            y += 2
        }
        x += 2
    }
    if (n == 0L) return Color(0xFF2A2A2E)
    return Color(r / n / 255f, g / n / 255f, b / n / 255f)
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
 * 迷幻烟雾背景：多个大径向渐变烟团，各自以不同频率极缓慢正弦漂移 + 呼吸，
 * blendMode=Plus 叠加（亮色烟雾感），颜色取自封面主色 + 固定白色烟团（灰蒙蒙效果）。
 * 渐变采用"环形三停"（中心 alpha=0、0.30r 处最浓、向外淡出）→ 中心不再出现亮点；
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
        val base = if (palette.isEmpty()) listOf(Color(0xFF3A3A3E), Color(0xFF8B5A2B)) else palette
        val cw = size.width
        val ch = size.height
        val cx0 = cw * 0.5f
        val cy0 = ch * 0.5f
        // 6 团：主色循环 + 1 团白色（灰蒙蒙烟雾）；
        // 圆心约束在屏幕外围环形轨道（距屏中心 0.38w~0.56w 缓慢呼吸），
        // 靠近中央时 sin 到达极值点自动反向 → 烟团中心永不进入屏幕中央区
        repeat(6) { i ->
            val c = if (i == 5) Color.White.copy(alpha = 0.32f) else base[i % base.size]
            // 各团角速度不同、奇偶团方向相反（避免同向绕圈机械化）；一圈约 7~15 分钟
            val dir = if (i % 2 == 0) 1f else -1f
            val theta = t * (0.25f + 0.06f * i) * dir + i * 1.0472f
            val rr = cw * (0.47f + 0.09f * sin(t * 0.9f + i * 1.9f))
            val cx = cx0 + rr * cos(theta)
            val cy = cy0 + rr * 0.95f * sin(theta)
            // 大烟团：半径 0.30~0.46 倍屏高（直径最大 ~0.92 屏高），弥散覆盖大半屏
            val r = ch * (0.38f + 0.08f * sin(t * 0.4f + i * 1.3f))
            val peak = if (i == 5) 0.28f else 0.26f
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to c.copy(alpha = 0.0f),
                    0.30f to c.copy(alpha = peak),
                    1.0f to c.copy(alpha = 0.0f),
                    center = Offset(cx, cy),
                    radius = r
                ),
                radius = r,
                center = Offset(cx, cy),
                blendMode = BlendMode.Plus
            )
        }
        // 中央实心羽化圆（封面主色 coverBase）：直接盖住多团尾巴在中央的叠加残留，
        // 光斑根除；中央底色 = 封面主色（与封面颜色直接呼应），边缘羽化无硬边
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    centerFill,
                    centerFill.copy(alpha = 0.88f),
                    centerFill.copy(alpha = 0f)
                ),
                center = Offset(cx0, cy0),
                radius = cw * 0.50f
            ),
            radius = cw * 0.50f,
            center = Offset(cx0, cy0)
        )
    }
}

/** 压暗到指定最大亮度（0~1），保证白字可读 */
private fun darkenToLuminance(c: Color, maxLum: Float): Color {
    val lum = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
    val f = if (lum > maxLum) maxLum / lum else 1f
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
