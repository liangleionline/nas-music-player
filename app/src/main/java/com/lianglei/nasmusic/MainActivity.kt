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
import com.lianglei.nasmusic.player.PlayerManager
import com.lianglei.nasmusic.ui.HomeRoot
import com.lianglei.nasmusic.ui.theme.NasMusicTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var ticker: Job? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // True edge-to-edge
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        // 排除左侧 40dp 的系统返回手势区，让抽屉边缘右滑不被系统手势截走（API 29+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.decorView.post {
                try {
                    val density = resources.displayMetrics.density
                    val edge = (40 * density).toInt()
                    window.decorView.setSystemGestureExclusionRects(
                        listOf(android.graphics.Rect(0, 0, edge, window.decorView.height))
                    )
                } catch (t: Throwable) { }
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

        // All-files access (for folder scanning)
        if (Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (t: Throwable) { }
        }
    }

    override fun onStart() {
        super.onStart()
        // v1.4.44 修复"切后台再回来进度归零/按钮失灵"：
        // 旧实现 onStop 时 releaseFuture + unbind 置空 controller，回前台重新异步连接；
        // 若连接失败/耗时，controller 持续为 null → tick() 不更新进度(停在0)、
        // togglePlayPause 直接 return(按钮按不动)。现在连接在 Activity 存活期保持：
        // onStart 仅首次/重建时建立连接，onStop 只停 ticker，onDestroy 才释放。
        // v1.5.26：连接改为进程级、幂等（PlayerManager.ensureBound）。播放页是独立
        // Activity 且自己不建连接，进程被回收后若只恢复栈顶的播放页，就没有任何页面
        // 连得上服务 → 播放页拿不到队列、整屏空白，退回主页才恢复。
        PlayerManager.ensureBound(this)
        ticker = lifecycleScope.launch {
            while (true) { delay(500); PlayerManager.tick() }
        }
    }

    override fun onStop() {
        // 只停进度轮询，不释放 controller 连接：播放服务常驻，回前台直接恢复进度与按钮
        ticker?.cancel()
        // v1.5.8：退到后台/被系统回收前立刻落盘一次进度（同步 commit），
        // 避免"装新版 APK 时进程被直接 force-stop"只留下 30 秒前的旧快照
        PlayerManager.saveState()
        super.onStop()
    }

    override fun onDestroy() {
        ticker?.cancel()
        // v1.5.26：不再解绑连接。连接是进程级共享的，播放页（PlayerActivity）可能
        // 还在前台；在本页销毁时 release 会让播放页立刻失去数据源变成空白页。
        super.onDestroy()
    }
}
