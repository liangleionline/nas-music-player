package com.lianglei.nasmusic.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lianglei.nasmusic.R
import com.lianglei.nasmusic.data.UpdateCenter

/**
 * 全局更新提示（v1.5.17）：冷启动自动检查 / 设置页手动检查后，若有新版本就在这里弹窗。
 * 只在 [com.lianglei.nasmusic.ui.HomeRoot] 里挂一次，所有页面共用同一个弹窗。
 */
@Composable
fun UpdatePromptHost(context: Context) {
    val available by UpdateCenter.available.collectAsStateWithLifecycle()
    val percent by UpdateCenter.percent.collectAsStateWithLifecycle()
    val message by UpdateCenter.message.collectAsStateWithLifecycle()

    message?.let { msg ->
        LaunchedEffect(msg) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            UpdateCenter.clearMessage()
        }
    }

    val info = available ?: return
    val downloading = percent != null

    AlertDialog(
        // 下载中不允许点外部关掉，避免留下半个安装包
        onDismissRequest = { if (!downloading) UpdateCenter.dismiss() },
        title = { Text(stringResource(R.string.update_available_title, info.version)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.update_current_version, UpdateCenter.currentVersion()),
                    color = Color.Gray, fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                if (downloading) {
                    Text(stringResource(R.string.update_downloading, percent ?: 0), fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (percent ?: 0) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    val notes = info.notes.trim().take(800)
                    Text(
                        notes.ifBlank { stringResource(R.string.update_notes_empty) },
                        fontSize = 12.sp,
                        modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = { UpdateCenter.downloadAndInstall(context, info) }
            ) { Text(stringResource(R.string.update_now)) }
        },
        dismissButton = {
            if (!downloading) {
                TextButton(onClick = { UpdateCenter.dismiss() }) {
                    Text(stringResource(R.string.update_later))
                }
            }
        }
    )
}
