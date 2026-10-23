package com.lianglei.nasmusic

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowInsetsControllerCompat
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
    private var bound = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { CrashLogger.log("permission result: $it") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLogger.log("MainActivity onCreate")
        // True edge-to-edge
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        // Light status bar icons (dark text on light background)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
        // 排除左侧 40dp 的系统返回手势区，让抽屉边缘右滑不被系统手势截走（API 29+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.decorView.post {
                try {
                    val density = resources.displayMetrics.density
                    val edge = (40 * density).toInt()
                    window.decorView.setSystemGestureExclusionRects(
                        listOf(android.graphics.Rect(0, 0, edge, window.decorView.height))
                    )
                } catch (t: Throwable) { CrashLogger.e("gesture exclusion failed", t) }
            }
        }
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
        // v1.4.44 修复"切后台再回来进度归零/按钮失灵"：
        // 旧实现 onStop 时 releaseFuture + unbind 置空 controller，回前台重新异步连接；
        // 若连接失败/耗时，controller 持续为 null → tick() 不更新进度(停在0)、
        // togglePlayPause 直接 return(按钮按不动)。现在连接在 Activity 存活期保持：
        // onStart 仅首次/重建时建立连接，onStop 只停 ticker，onDestroy 才释放。
        if (!bound) {
            val token = SessionToken(this, ComponentName(this, PlayerService::class.java))
            controllerFuture = MediaController.Builder(this, token).buildAsync()
            controllerFuture.addListener({
                try {
                    PlayerManager.bind(controllerFuture.get())
                    bound = true
                } catch (t: Throwable) {
                    CrashLogger.e("media controller bind failed", t)
                }
            }, mainExecutor)
        }
        ticker = lifecycleScope.launch {
            while (true) { delay(500); PlayerManager.tick() }
        }
    }

    override fun onStop() {
        // 只停进度轮询，不释放 controller 连接：播放服务常驻，回前台直接恢复进度与按钮
        ticker?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        ticker?.cancel()
        if (::controllerFuture.isInitialized) MediaController.releaseFuture(controllerFuture)
        PlayerManager.unbind()
        bound = false
        super.onDestroy()
    }
}
