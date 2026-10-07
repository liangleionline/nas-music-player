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
                    val proj = arrayOf("lyrics")
                    ctx.contentResolver.query(
                        android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        proj, "_id=?", arrayOf(song.id.toString()), null
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            val embedded = c.getString(0) ?: ""
                            if (embedded.isNotEmpty()) parseLrc(embedded) else emptyList()
                        } else emptyList()
                    } ?: emptyList()
                }
            } catch (e: Exception) { emptyList() }
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
            // Top bar: marquee title + artist + cast
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    MarqueeText(
                        text = song?.title ?: "—",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(song?.artist ?: "", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
                }
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Filled.Cast, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(28.dp))

            // Album cover with white frame
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.15f)).padding(12.dp)
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
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .offset(x = offset.dp)
    )
}

private fun fmtTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val s = ms / 1000
    return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}
