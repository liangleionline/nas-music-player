package com.lianglei.nasmusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
fun PlayerScreen() {
    val queue by PlayerManager.queue.collectAsStateWithLifecycle()
    val idx by PlayerManager.currentIndex.collectAsStateWithLifecycle()
    val playing by PlayerManager.isPlaying.collectAsStateWithLifecycle()
    val pos by PlayerManager.positionMs.collectAsStateWithLifecycle()
    val dur by PlayerManager.durationMs.collectAsStateWithLifecycle()
    val song = queue.getOrNull(idx)
    var showLyrics by remember { mutableStateOf(false) }

    val bg = Brush.verticalGradient(listOf(Color(0xFF6B5D4F), Color(0xFFB8AE9E), Color(0xFF8E8577)))

    // Load lyrics from LRC file next to song
    val lrcLines = remember(song?.id) {
        if (song == null) emptyList()
        else {
            try {
                val file = File(song.data)
                val lrcFile = File(file.parentFile, file.nameWithoutExtension + ".lrc")
                if (lrcFile.exists()) parseLrc(lrcFile.readText()) else emptyList()
            } catch (e: Exception) { emptyList() }
        }
    }

    Box(Modifier.fillMaxSize().background(bg)) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(song?.title ?: "—", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(song?.artist ?: "", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
                Icon(Icons.Filled.Cast, null, tint = Color.White)
            }
            Spacer(Modifier.height(20.dp))

            if (!showLyrics) {
                AsyncImage(
                    model = song?.let {
                        if (it.coverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(it.coverId)
                        else "content://media/external/audio/albumart/${it.albumId}"
                    },
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { showLyrics = true }
                )
            } else {
                // Lyrics view
                Column(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.2f))
                        .clickable { showLyrics = false }
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp)
                ) {
                    if (lrcLines.isEmpty()) {
                        Text("暂无歌词", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp,
                            modifier = Modifier.fillMaxWidth().wrapContentSize(Alignment.Center))
                    } else {
                        lrcLines.forEach { line ->
                            val isActive = line.time <= pos && (lrcLines.getOrNull(lrcLines.indexOf(line) + 1)?.time ?: Long.MAX_VALUE) > pos
                            Text(
                                line.text,
                                color = if (isActive) Color.White else Color.White.copy(alpha = 0.5f),
                                fontSize = if (isActive) 16.sp else 14.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))

            // progress
            Slider(
                value = pos.toFloat().coerceIn(0f, dur.coerceAtLeast(1L).toFloat()),
                onValueChange = { PlayerManager.seekTo(it.roundToLong()) },
                valueRange = 0f..dur.coerceAtLeast(1L).toFloat(),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White)
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fmtTime(pos), color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(fmtTime(dur), color = Color.White, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { PlayerManager.prev() }) {
                    Icon(Icons.Filled.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(40.dp))
                }
                IconButton(onClick = { PlayerManager.togglePlayPause() }) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null,
                        tint = Color.White, modifier = Modifier.size(64.dp))
                }
                IconButton(onClick = { PlayerManager.next() }) {
                    Icon(Icons.Filled.SkipNext, null, tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(Icons.Filled.Shuffle, Icons.Filled.Timer, Icons.Filled.Equalizer,
                       Icons.Filled.QueueMusic, Icons.Filled.MoreVert).forEach {
                    Icon(it, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun fmtTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val s = ms / 1000
    return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}
