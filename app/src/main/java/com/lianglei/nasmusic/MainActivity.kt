package com.lianglei.nasmusic

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.lianglei.nasmusic.player.PlayerManager
import com.lianglei.nasmusic.player.PlayerService
import com.lianglei.nasmusic.ui.HomeRoot
import com.lianglei.nasmusic.ui.theme.NasMusicTheme
import com.lianglei.nasmusic.util.CrashLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var ticker: Job? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { CrashLogger.log("permission result: $it") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLogger.log("MainActivity onCreate")
        // Transparent status bar for immersive look
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        requestPermissionsIfNeeded()
        setContent { NasMusicTheme { HomeRoot() } }
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) perms.add(android.Manifest.permission.READ_MEDIA_AUDIO)
        else perms.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 33) perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(perms.toTypedArray())

        // All-files access (for folder scanning + Download log writes)
        if (Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (t: Throwable) { CrashLogger.e("all-files intent failed", t) }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlayerService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture.addListener({
            val c = controllerFuture.get()
            PlayerManager.bind(c)
        }, mainExecutor)
        ticker = lifecycleScope.launch {
            while (true) { delay(500); PlayerManager.tick() }
        }
    }

    override fun onStop() {
        ticker?.cancel()
        if (::controllerFuture.isInitialized) MediaController.releaseFuture(controllerFuture)
        super.onStop()
    }
}
