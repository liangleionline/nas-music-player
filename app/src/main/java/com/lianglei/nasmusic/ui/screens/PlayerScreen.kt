package com.lianglei.nasmusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lianglei.nasmusic.player.PlayerManager
import java.io.File
import java.util.Locale
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

    val bg = Brush.verticalGradient(listOf(Color(0xFF8B6914), Color(0xFF6B4E1A), Color(0xFF4A3728)))
    val ctx = androidx.compose.ui.platform.LocalContext.current

    val lrcLines = remember(song?.id) {
        if (song == null) emptyList()
        else {
            try {
                val file = File(song.data)
                val lrcFile = File(file.parentFile, file.nameWithoutExtension + ".lrc")
                if (lrcFile.exists()) {
                    parseLrc(lrcFile.readText())
                } else {
                    if (file.exists()) {
                        // Direct ID3 USLT parser (MMR doesn't support it on many devices)
                        val embedded = readId3Uslt(file)
                        com.lianglei.nasmusic.util.CrashLogger.log("ID3 USLT for ${song.title}: len=${embedded.length}, preview=${embedded.take(100)}")
                        if (embedded.isNotEmpty()) parseLrc(embedded) else emptyList()
                    } else {
                        emptyList()
                    }
                }
            } catch (e: Exception) {
                com.lianglei.nasmusic.util.CrashLogger.e("LRC load failed", e)
                emptyList()
            }
        }
    }

    // Find active lyric line
    val activeLrcIndex = remember(lrcLines, pos) {
        var active = -1
        lrcLines.forEachIndexed { i, line ->
            if (line.time <= pos) active = i
        }
        active
    }

    Box(Modifier.fillMaxSize().background(bg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(20.dp))
            // Top bar: marquee title + artist + cast
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    MarqueeText(
                        text = song?.title ?: "—",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(song?.artist ?: "", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                }
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Filled.Cast, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(32.dp))

            // Album cover with subtle frame
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.08f)).padding(16.dp)
            ) {
                AsyncImage(
                    model = song?.let {
                        if (it.coverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(it.coverId)
                        else "content://media/external/audio/albumart/${it.albumId}"
                    },
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                )
            }
            Spacer(Modifier.height(28.dp))

            // Lyrics area: prev / current / next
            Column(Modifier.fillMaxWidth().height(100.dp), horizontalAlignment = Alignment.Start) {
                if (lrcLines.isEmpty()) {
                    Text("暂无歌词", color = Color.White.copy(alpha = 0.5f), fontSize = 15.sp)
                } else {
                    val prev = lrcLines.getOrNull(activeLrcIndex - 1)
                    val active = lrcLines.getOrNull(activeLrcIndex)
                    val next = lrcLines.getOrNull(activeLrcIndex + 1)
                    Text(prev?.text ?: "", color = Color.White.copy(alpha = 0.35f), fontSize = 14.sp, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                    Text(active?.text ?: "", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                    Text(next?.text ?: "", color = Color.White.copy(alpha = 0.35f), fontSize = 14.sp, maxLines = 1)
                }
            }

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

            // Bottom action row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shuffle, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(26.dp))
                Icon(Icons.Filled.Timer, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(26.dp))
                Icon(Icons.Filled.Equalizer, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(26.dp))
                IconButton(onClick = onOpenQueue) {
                    Icon(Icons.Filled.QueueMusic, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(26.dp))
                }
                Icon(Icons.Filled.MoreVert, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun MarqueeText(
    text: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    maxLines: Int = 1
) {
    var offset by remember { mutableStateOf(0f) }
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(500)
        val anim = android.animation.ValueAnimator.ofFloat(0f, -400f).apply {
            duration = 4000
            interpolator = android.view.animation.LinearInterpolator()
        }
        anim.addUpdateListener { offset = it.animatedValue as Float }
        anim.start()
        kotlinx.coroutines.delay(4500)
        offset = 0f
    }
    Text(
        text = text,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        maxLines = 1,
        softWrap = false,
        overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
        modifier = Modifier
            .fillMaxWidth()
            .offset(x = offset.dp)
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
                    // skip content descriptor (null-terminated)
                    while (p < pos + 10 + frameSize && data[p].toInt() != 0) p++
                    p++ // skip null
                    val lyricsBytes = data.copyOfRange(p, pos + 10 + frameSize)
                    return when (encoding) {
                        0 -> String(lyricsBytes, Charsets.ISO_8859_1)
                        1 -> String(lyricsBytes, Charsets.UTF_16)
                        2 -> String(lyricsBytes, Charsets.UTF_16BE)
                        3 -> String(lyricsBytes, Charsets.UTF_8)
                        else -> String(lyricsBytes, Charsets.UTF_8)
                    }.trim()
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
