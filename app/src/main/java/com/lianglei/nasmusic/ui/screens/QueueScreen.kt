package com.lianglei.nasmusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

    Column(Modifier.fillMaxSize().background(Color(0xFFECE7DF))) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("${idx + 1} / ${queue.size}", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("播放队列", fontWeight = FontWeight.Bold)
            Text("清除", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(queue) { s ->
                val i = queue.indexOf(s)
                val selected = i == idx
                Row(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Color(0xFFFFFFFF) else Color.Transparent)
                        .clickable { PlayerManager.playQueue(queue, i) }
                        .padding(14.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1, fontSize = 15.sp)
                        Spacer(Modifier.height(2.dp))
                        Text("${s.artist} - ${s.album}", color = Color.Gray, maxLines = 1, fontSize = 12.sp)
                    }
                }
            }
        }
        Box(Modifier.padding(16.dp)) {
            AssistChip(onClick = {}, label = { Text("随机播放模式") })
        }
    }
}
