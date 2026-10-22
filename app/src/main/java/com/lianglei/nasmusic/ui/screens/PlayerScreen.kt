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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import com.lianglei.nasmusic.player.PlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class LrcLine(val time: Long, val text: String)

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

@Composable
fun PlayerScreen(onOpenQueue: () -> Unit = {}) {
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
                if (lrcFile.exists()) {
                    parseLrc(lrcFile.readText())
                } else if (file.exists()) {
                    val embedded = readId3Uslt(file)
                    if (embedded.isNotEmpty()) parseLrc(embedded) else emptyList()
                } else emptyList()
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
    val nextLrcStart = lrcLines.getOrNull(activeLrcIndex + 1)?.time ?: (activeLrc?.time ?: 0L) + 4000L
    val lineProgress = if (activeLrc != null && nextLrcStart > activeLrc.time) {
        ((pos - activeLrc.time).toFloat() / (nextLrcStart - activeLrc.time).toFloat()).coerceIn(0f, 1f)
    } else 0f

    val coverModel = song?.let {
        if (it.coverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(it.coverId)
        else "content://media/external/audio/albumart/${it.albumId}"
    }

    // 从封面提取主色作为底色
    val baseColor by produceState<Color?>(initialValue = null, coverModel) {
        value = try {
            val result = ImageLoader(ctx).execute(
                ImageRequest.Builder(ctx).data(coverModel).size(64).build()
            )
            val bmp = (result.drawable as? BitmapDrawable)?.bitmap
            bmp?.let { dominantColorOf(it) }
        } catch (e: Exception) {
            null
        }
    }
    val coverBase = baseColor?.let { darkenToLuminance(it, 0.5f) } ?: Color(0xFF2A2A2E)

    // 根容器拦截点击（空操作）：阻止事件穿透到下层 NavHost 列表，
    // 否则点击封面/歌词等空白区域会命中下层歌曲行，误触发播放/切歌。
    // 内部 Slider/IconButton 等子节点不受影响，正常响应。
    Box(
        Modifier.fillMaxSize().background(coverBase)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { }
    ) {
        // Frosted glass background: blurred album cover + dark scrim
        AsyncImage(
            model = coverModel,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().scale(1.15f)
                .blur(radius = 36.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
        )
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
            Spacer(Modifier.height(16.dp))

            // 封面/歌词 左右滑动切换（两块玻璃跟手平移：封面左滑出屏、歌词右滑入，反向亦然）
            // 面板高度 = 屏幕 64%，歌词块垂直居中后中心落在屏幕 ~43%（与 Salt 一致）
            val panelHeight = (LocalConfiguration.current.screenHeightDp * 0.64f).dp
            var panelWidth by remember { mutableStateOf(0) }
            val slide = remember { Animatable(0f) } // 0 = 显示封面；-panelWidth = 显示歌词
            val scope = rememberCoroutineScope()
            Box(
                Modifier.fillMaxWidth().height(panelHeight)
                    .onSizeChanged { panelWidth = it.width }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { scope.launch { slide.stop() } },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                if (panelWidth > 0) {
                                    scope.launch {
                                        // 下限多留 24dp 出屏余量，保证封面完全滑出不留边
                                        slide.snapTo((slide.value + dragAmount).coerceIn(-panelWidth.toFloat() - 24f, 0f))
                                    }
                                }
                            },
                            onDragEnd = {
                                scope.launch {
                                    val target = if (slide.value < -panelWidth / 2f) -panelWidth.toFloat() - 24f else 0f
                                    slide.animateTo(target, animationSpec = tween(260))
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    val target = if (slide.value < -panelWidth / 2f) -panelWidth.toFloat() - 24f else 0f
                                    slide.animateTo(target, animationSpec = tween(260))
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                // 封面面板：slide=0 原位；左滑时向左平移出屏（无边框，直接显示封面图）
                AsyncImage(
                    model = song?.let {
                        if (it.coverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(it.coverId)
                        else "content://media/external/audio/albumart/${it.albumId}"
                    },
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        .offset { IntOffset(slide.value.roundToInt(), 0) }
                        .clip(RoundedCornerShape(12.dp))
                )
                // 歌词面板：初始在右侧屏外（slide+panelWidth），滑到位后占据原位；透明背景，直接叠在渐变上
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight()
                        .offset { IntOffset(slide.value.roundToInt() + panelWidth, 0) },
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
                            // 当前行前 3 行
                            for (i in (activeLrcIndex - 3) until activeLrcIndex) {
                                val t = lrcLines.getOrNull(i)?.text
                                if (!t.isNullOrEmpty()) {
                                    Text(t, color = Color.White.copy(alpha = 0.38f), fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(27.dp))
                                }
                            }
                            KaraokeText(
                                text = activeLrc?.text ?: "",
                                progress = lineProgress,
                                activeColor = Color.White,
                                dimColor = Color.White.copy(alpha = 0.6f),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(27.dp))
                            // 当前行后 3 行
                            for (i in (activeLrcIndex + 1)..(activeLrcIndex + 3)) {
                                val t = lrcLines.getOrNull(i)?.text
                                if (!t.isNullOrEmpty()) {
                                    Text(t, color = Color.White.copy(alpha = 0.38f), fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(27.dp))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Spacer(Modifier.weight(1f))

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
            Spacer(Modifier.height(16.dp))

            // Play controls
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { PlayerManager.prev() }) {
                    Icon(Icons.Filled.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(42.dp))
                }
                IconButton(onClick = { PlayerManager.togglePlayPause() }) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null,
                        tint = Color.White, modifier = Modifier.size(64.dp))
                }
                IconButton(onClick = { PlayerManager.next() }) {
                    Icon(Icons.Filled.SkipNext, null, tint = Color.White, modifier = Modifier.size(42.dp))
                }
            }
            Spacer(Modifier.height(20.dp))

            // Bottom action row - 5 items evenly weighted so center aligns with play button
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                listOf(
                    Icons.Filled.Shuffle to {},
                    Icons.Filled.Timer to {},
                    Icons.Filled.Equalizer to {},
                    Icons.Filled.QueueMusic to { onOpenQueue() },
                    Icons.Filled.MoreVert to {}
                ).forEach { (icon, action) ->
                    IconButton(onClick = action, modifier = Modifier.weight(1f)) {
                        Icon(icon, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(24.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
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

/** 压暗到指定最大亮度（0~1），保证白字可读 */
private fun darkenToLuminance(c: Color, maxLum: Float): Color {
    val lum = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
    val f = if (lum > maxLum) maxLum / lum else 1f
    return Color(c.red * f, c.green * f, c.blue * f)
}
