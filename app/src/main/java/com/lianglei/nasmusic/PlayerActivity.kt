package com.lianglei.nasmusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.lifecycleScope
import com.lianglei.nasmusic.player.PlayerManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lianglei.nasmusic.ui.screens.PlayerScreen
import com.lianglei.nasmusic.ui.screens.QueueScreen
import com.lianglei.nasmusic.ui.theme.NasMusicTheme

/**
 * 播放页独立 Activity（v1.4.81）。
 *
 * 背景：播放页曾是 HomeRoot 的全屏 Compose 浮层，弹出动画每帧要 GPU 重新合成整棵
 * 渲染树（1080p 全屏渐变+控件），弱 GPU（TV 盒子 Mali-450）上必掉帧——这就是
 * "其他播放器不卡、我们卡"的根源：它们用的是系统窗口动画（内容渲染一次，滑动由
 * SurfaceFlinger 做图层变换）。
 *
 * 现在播放页改成独立 Activity：
 *  - 弹出：startActivity + overridePendingTransition(slide_in_bottom) → 系统窗口动画，
 *    流畅性与设备合成器一致；
 *  - 收起：返回键 → finish + overridePendingTransition(0, slide_out_bottom)；
 *  - 播放由 PlayerService（前台服务 + MediaController）承载，Activity 切换不影响播放。
 *
 * 队列页浮层从 HomeRoot 迁移至此（v1.4.82 恢复滑入动画）。
 */
class PlayerActivity : ComponentActivity() {
    private var ticker: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        // 返回键：finish + 下滑退出动画（与弹出动画对称）。
        // 播放页内的 Compose BackHandler（如歌曲信息弹窗）优先级更高，会先收弹窗。
        onBackPressedDispatcher.addCallback(this) {
            finish()
            overridePendingTransition(0, R.anim.slide_out_bottom)
        }

        setContent {
            NasMusicTheme {
                var showQueue by remember {
                    mutableStateOf(intent?.getBooleanExtra("showQueue", false) == true)
                }
                Box(Modifier.fillMaxSize()) {
                    PlayerScreen(onOpenQueue = { showQueue = true })
                    // 队列页全屏浮层（迁移自 HomeRoot；v1.4.82 恢复滑入动画）
                    val queueSlide = remember { Animatable(1f) }
                    val queueIntercept = remember { derivedStateOf { queueSlide.value < 0.98f } }
                    LaunchedEffect(showQueue) {
                        if (showQueue) queueSlide.animateTo(0f, tween(280))
                        else queueSlide.animateTo(1f, tween(280))
                    }
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            translationY = queueSlide.value * size.height
                        }.then(
                            if (queueIntercept.value)
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { }
                            else Modifier
                        )
                    ) {
                        QueueScreen(onClose = { showQueue = false })
                    }
                }
            }
        }
    }

    // 进度 ticker：播放页是独立 Activity，MainActivity 退到后台会取消它的 ticker，
    // 必须由本页自己驱动 positionMs → 进度条/歌词才能在停留时持续滚动（v1.4.84 修复）。
    // 与 MainActivity 相同的 500ms 节律，controller 为空时 tick() 内部直接返回。
    override fun onStart() {
        super.onStart()
        ticker = lifecycleScope.launch {
            while (true) { delay(500); PlayerManager.tick() }
        }
    }

    override fun onStop() {
        ticker?.cancel()
        super.onStop()
    }
}
