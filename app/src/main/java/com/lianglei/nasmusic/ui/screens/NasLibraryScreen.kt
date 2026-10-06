package com.lianglei.nasmusic.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lianglei.nasmusic.data.MusicSource
import com.lianglei.nasmusic.data.SourceManager

private data class NasEntry(val name: String, val icon: ImageVector, val enabled: Boolean, val tint: Color)

@Composable
fun NasLibraryScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val current by SourceManager.current.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Color(0xFFF2F3F5))) {
        // Top bar
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) }
            Text("音乐库", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        // Local player toggle button
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (current == MusicSource.LOCAL) Color(0xFF1F6FEB) else Color.White
            )
        ) {
            Row(
                Modifier.fillMaxWidth().clickable {
                    SourceManager.switchTo(MusicSource.LOCAL)
                    Toast.makeText(context, "已切换到本地播放器", Toast.LENGTH_SHORT).show()
                }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Smartphone, null,
                    tint = if (current == MusicSource.LOCAL) Color.White else Color.Gray
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "本地播放器",
                        fontWeight = FontWeight.Bold,
                        color = if (current == MusicSource.LOCAL) Color.White else Color.Black
                    )
                    Text(
                        if (current == MusicSource.LOCAL) "当前使用中" else "扫描手机本地音乐",
                        fontSize = 12.sp,
                        color = if (current == MusicSource.LOCAL) Color.White.copy(alpha = 0.8f) else Color.Gray
                    )
                }
            }
        }

        Text(
            "网络音乐源",
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            color = Color.Gray, fontSize = 13.sp
        )

        // NAS list
        val entries = listOf(
            NasEntry("飞牛 fnOS", Icons.Filled.LibraryMusic, true, Color(0xFF1F6FEB)),
            NasEntry("群晖 Synology", Icons.Filled.Storage, false, Color.Gray),
            NasEntry("威联通 QNAP", Icons.Filled.Dns, false, Color.Gray),
            NasEntry("绿联 UGREEN", Icons.Filled.Cloud, false, Color.Gray),
        )
        entries.forEach { e ->
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (e.enabled) {
                            SourceManager.switchTo(MusicSource.FEINIU)
                            Toast.makeText(context, "已切换到飞牛NAS（开发中）", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "${e.name} 敬请期待", Toast.LENGTH_SHORT).show()
                        }
                    }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(e.icon, null, tint = e.tint, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        Text(
                            if (e.enabled) "已接入" else "暂未支持",
                            color = Color.Gray, fontSize = 12.sp
                        )
                    }
                    if (current == MusicSource.FEINIU && e.enabled) {
                        Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF4CAF50))
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            "选择音乐源后，歌曲/专辑/艺术家列表将自动切换",
            Modifier.fillMaxWidth().padding(24.dp),
            color = Color.Gray, fontSize = 12.sp
        )
    }
}
