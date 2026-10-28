package com.lianglei.nasmusic.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.lianglei.nasmusic.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lianglei.nasmusic.data.MusicSource
import com.lianglei.nasmusic.data.SourceManager
import com.lianglei.nasmusic.ui.suppressClickOnRightSwipe

private data class NasEntry(val nameRes: Int, val icon: ImageVector, val enabled: Boolean, val tint: Color)

@Composable
fun NasLibraryScreen(
    onBack: () -> Unit,
    onOpenFeiniuLogin: () -> Unit = {}
) {
    val context = LocalContext.current
    val current by SourceManager.current.collectAsStateWithLifecycle()
    val fnConnected by SourceManager.fnConnected.collectAsStateWithLifecycle()
    var showDisconfirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Color(0xFFF2F3F5))) {
        // Top bar
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) }
            Text(stringResource(R.string.library_title), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        // Local player toggle button
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (current == MusicSource.LOCAL) Color(0xFF1F6FEB) else Color.White
            )
        ) {
            Row(
                Modifier.fillMaxWidth().suppressClickOnRightSwipe {
                    SourceManager.switchTo(MusicSource.LOCAL)
                    Toast.makeText(context, context.getString(R.string.toast_switched_local), Toast.LENGTH_SHORT).show()
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
                        stringResource(R.string.local_player),
                        fontWeight = FontWeight.Bold,
                        color = if (current == MusicSource.LOCAL) Color.White else Color.Black
                    )
                    Text(
                        if (current == MusicSource.LOCAL) stringResource(R.string.current_in_use) else stringResource(R.string.scan_local_music),
                        fontSize = 12.sp,
                        color = if (current == MusicSource.LOCAL) Color.White.copy(alpha = 0.8f) else Color.Gray
                    )
                }
            }
        }

        Text(
            stringResource(R.string.network_source),
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            color = Color.Gray, fontSize = 13.sp
        )

        // Feiniu card — behaves differently depending on connection state.
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().suppressClickOnRightSwipe {
                    if (fnConnected) {
                        // Already connected: just switch to Feiniu source (serves from cache).
                        SourceManager.switchTo(MusicSource.FEINIU)
                        Toast.makeText(context, context.getString(R.string.toast_switched_feiniu), Toast.LENGTH_SHORT).show()
                    } else {
                        // Not connected: open login form.
                        onOpenFeiniuLogin()
                    }
                }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.LibraryMusic, null,
                    tint = Color(0xFF1F6FEB),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.fn_os), fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Text(
                        when {
                            fnConnected && current == MusicSource.FEINIU -> stringResource(R.string.fn_connected_in_use, "${SourceManager.fnUsername}@${SourceManager.fnHost}")
                            fnConnected -> stringResource(R.string.fn_connected, "${SourceManager.fnUsername}@${SourceManager.fnHost}")
                            else -> stringResource(R.string.fn_click_login)
                        },
                        color = Color.Gray, fontSize = 12.sp
                    )
                }
                if (fnConnected) {
                    IconButton(onClick = { showDisconfirm = true }) {
                        Icon(Icons.Filled.Logout, null, tint = Color.Gray)
                    }
                } else if (current == MusicSource.FEINIU) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF4CAF50))
                }
            }
        }

        // Other NAS entries (disabled)
        val others = listOf(
            NasEntry(R.string.nas_synology, Icons.Filled.Storage, false, Color.Gray),
            NasEntry(R.string.nas_qnap, Icons.Filled.Dns, false, Color.Gray),
            NasEntry(R.string.nas_ugreen, Icons.Filled.Cloud, false, Color.Gray),
        )
        others.forEach { e ->
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().suppressClickOnRightSwipe {
                        Toast.makeText(context, context.getString(R.string.coming_soon, context.getString(e.nameRes)), Toast.LENGTH_SHORT).show()
                    }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(e.icon, null, tint = e.tint, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(e.nameRes), fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        Text(stringResource(R.string.not_supported), color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.source_switch_hint),
            Modifier.fillMaxWidth().padding(24.dp),
            color = Color.Gray, fontSize = 12.sp
        )
    }

    if (showDisconfirm) {
        AlertDialog(
            onDismissRequest = { showDisconfirm = false },
            title = { Text(stringResource(R.string.logout_confirm_title)) },
            text = { Text(stringResource(R.string.logout_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisconfirm = false
                    SourceManager.disconnectFeiniu()
                    Toast.makeText(context, context.getString(R.string.toast_logged_out), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.logout_btn), color = Color(0xFFD32F2F)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisconfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
