package com.lianglei.nasmusic.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianglei.nasmusic.data.FnApi
import com.lianglei.nasmusic.data.MusicSource
import com.lianglei.nasmusic.data.SourceManager
import com.lianglei.nasmusic.util.CrashLogger
import kotlinx.coroutines.launch

@Composable
fun FeiniuLoginScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf("https://nas.binarystar.space:2443") }
    var username by remember { mutableStateOf("lianglei") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) }
            Text("连接飞牛 NAS", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("NAS 地址") },
            placeholder = { Text("如 https://nas.example.com:244 或 192.168.1.100") },
            leadingIcon = { Icon(Icons.Filled.Dns, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("用户名") },
            leadingIcon = { Icon(Icons.Filled.Person, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            leadingIcon = { Icon(Icons.Filled.Lock, null) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (errorMsg.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(errorMsg, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                if (host.isBlank() || username.isBlank() || password.isBlank()) {
                    errorMsg = "请填写完整信息"
                    return@Button
                }
                loading = true
                errorMsg = ""
                scope.launch {
                    val result = FnApi.login(host.trim(), username.trim(), password)
                    if (result.isSuccess) {
                        CrashLogger.log("Feiniu login UI success, fetching tracks...")
                        SourceManager.setFnConnected(host.trim(), username.trim())
                        SourceManager.switchTo(MusicSource.FEINIU)
                        SourceManager.refreshFeiniuForce()
                        loading = false
                        Toast.makeText(context, "连接成功，已加载 ${SourceManager.songs.value.size} 首", Toast.LENGTH_LONG).show()
                        onSuccess()
                    } else {
                        loading = false
                        val msg = result.exceptionOrNull()?.message ?: "连接失败"
                        CrashLogger.e("Feiniu login UI failed: $msg")
                        errorMsg = msg
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text("连接", fontSize = 16.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "默认端口 5666，API 路径 /music/api/v1\n" +
                "请确保手机与 NAS 在同一局域网，或已配置外网访问",
            color = androidx.compose.ui.graphics.Color.Gray,
            fontSize = 12.sp
        )
    }
}
