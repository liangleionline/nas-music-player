package com.lianglei.nasmusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lianglei.nasmusic.player.PlayerManager

@Composable
fun QueueScreen() {
    val queue by PlayerManager.queue.collectAsStateWithLifecycle()
    val idx by PlayerManager.currentIndex.collectAsStateWithLifecycle()
    val current = queue.getOrNull(idx)

    val bg = Brush.verticalGradient(listOf(Color(0xFF8B6914), Color(0xFF6B4E1A), Color(0xFF4A3728)))

    Column(Modifier.fillMaxSize().background(bg)) {
        // Top hint
        Text(
            "此处向下轻扫以返回播放界面",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))

        // Current song mini bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = current?.let {
                    if (it.coverId.isNotEmpty()) com.lianglei.nasmusic.data.FnApi.coverUrl(it.coverId)
                    else "content://media/external/audio/albumart/${it.albumId}"
                },
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(current?.title ?: "—", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${current?.artist ?: ""} - ${current?.album ?: ""}",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        // Header row
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${idx + 1} / ${queue.size}", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("播放队列", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("清除", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
                modifier = Modifier.weight(1f).clickable { PlayerManager.clearQueue() },
                textAlign = TextAlign.End)
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

        // Queue list
        LazyColumn(Modifier.weight(1f)) {
            items(queue) { s ->
                val i = queue.indexOf(s)
                val selected = i == idx
                Row(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Color.White.copy(alpha = 0.15f) else Color.Transparent)
                        .clickable { PlayerManager.playQueue(queue, i) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.title,
                            color = Color.White,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1, fontSize = 16.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${s.artist} - ${s.album}",
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1, fontSize = 13.sp
                        )
                    }
                    IconButton(onClick = { PlayerManager.removeFromQueue(i) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Remove, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // Bottom mode button
        Box(Modifier.padding(20.dp)) {
            AssistChip(
                onClick = {},
                label = { Text("随机播放模式", color = Color.White) },
                colors = AssistChipDefaults.assistChipColors(containerColor = Color.White.copy(alpha = 0.15f))
            )
        }
    }
}
