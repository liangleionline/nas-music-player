package com.lianglei.nasmusic.ui.screens

import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.lianglei.nasmusic.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val focusManager = LocalFocusManager.current

    val prefs = remember { context.getSharedPreferences("fn_login_history", android.content.Context.MODE_PRIVATE) }
    var history by remember { mutableStateOf(prefs.getStringSet("history", emptySet())?.toList() ?: emptyList()) }

    var host by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
    val logLines = remember { mutableStateListOf<String>() }
    val logScroll = rememberScrollState()

    fun doLogin() {
        if (host.isBlank() || username.isBlank() || password.isBlank()) {
            errorMsg = context.getString(R.string.fn_please_fill)
            return
        }
        focusManager.clearFocus()
        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow((context as? android.app.Activity)?.currentFocus?.windowToken, 0)
        loading = true
        errorMsg = ""
        logLines.clear()
        logLines.add(context.getString(R.string.fn_connecting, host.trim()))
        scope.launch {
            logLines.add(context.getString(R.string.fn_verifying))
            val result = FnApi.login(host.trim(), username.trim(), password)
            if (result.isSuccess) {
                // Save to history
                val entry = "${host.trim()}|${username.trim()}"
                history = (listOf(entry) + history.filter { it != entry }).take(5)
                prefs.edit().putStringSet("history", history.toSet()).apply()

                logLines.add(context.getString(R.string.fn_success_fetching))
                CrashLogger.log("Feiniu login UI success, fetching tracks...")
                SourceManager.setFnConnected(host.trim(), username.trim())
                SourceManager.switchTo(MusicSource.FEINIU)
                SourceManager.refreshFeiniuForce { msg ->
                    logLines.add(msg)
                    scope.launch { logScroll.animateScrollTo(logScroll.maxValue) }
                }
                logLines.add(context.getString(R.string.fn_done, SourceManager.songs.value.size))
                loading = false
                Toast.makeText(context, context.getString(R.string.fn_loaded_toast, SourceManager.songs.value.size), Toast.LENGTH_LONG).show()
                onSuccess()
            } else {
                loading = false
                val msg = result.exceptionOrNull()?.message ?: context.getString(R.string.fn_failed)
                logLines.add(context.getString(R.string.fn_login_failed, msg))
                CrashLogger.e("Feiniu login UI failed: $msg")
                errorMsg = msg
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) }
            Text(stringResource(R.string.fn_title), fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }

        Spacer(Modifier.height(24.dp))

        // History entries
        if (history.isNotEmpty()) {
            Text(stringResource(R.string.fn_history), color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            history.forEach { entry ->
                val parts = entry.split("|")
                val h = parts.getOrNull(0) ?: ""
                val u = parts.getOrNull(1) ?: ""
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                        host = h
                        username = u
                        // Auto-trigger login
                        doLogin()
                    }
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.History, null, tint = Color.Gray)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(h, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(u, color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text(stringResource(R.string.fn_addr_label)) },
            placeholder = { Text(stringResource(R.string.fn_addr_placeholder)) },
            leadingIcon = { Icon(Icons.Filled.Dns, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.fn_user_label)) },
            leadingIcon = { Icon(Icons.Filled.Person, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.fn_pwd_label)) },
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
            onClick = { doLogin() },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(stringResource(R.string.fn_connect_btn), fontSize = 16.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        if (loading && logLines.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Column(Modifier.verticalScroll(logScroll)) {
                    logLines.takeLast(15).forEach { line ->
                        Text(line, fontSize = 11.sp, color = Color.DarkGray, maxLines = 1)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.fn_hint),
            color = Color.Gray,
            fontSize = 12.sp
        )
    }
}
