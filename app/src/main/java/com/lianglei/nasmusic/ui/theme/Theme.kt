package com.lianglei.nasmusic.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lianglei.nasmusic.data.ThemeMode
import com.lianglei.nasmusic.data.ThemePrefs

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    onPrimary = Color.White,
    background = Color(0xFFF2F3F5),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEFF1F5),
    onBackground = Color(0xFF1B1B1F),
    onSurface = Color(0xFF1B1B1F),
    onSurfaceVariant = Color(0xFF6B6B70),
    error = Color(0xFFE74C3C)
)

/**
 * 深色方案按 Salt Player 参考效果图取色：
 * 页面纯黑底 #000000；卡片 / 抽屉面板 / 迷你播放条 #181818；凸起弹窗 #26282F；
 * 主文字近白 #FCFCFC；次要文字灰 #9E9EA3。品牌蓝保持 #1F6FEB。
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF1F6FEB),
    onPrimary = Color.White,
    background = Color(0xFF000000),
    surface = Color(0xFF181818),
    surfaceVariant = Color(0xFF181818),
    onBackground = Color(0xFFFCFCFC),
    onSurface = Color(0xFFFCFCFC),
    onSurfaceVariant = Color(0xFF9E9EA3),
    error = Color(0xFFEF6B5E)
)

@Composable
fun NasMusicTheme(content: @Composable () -> Unit) {
    val mode by ThemePrefs.mode.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val dark = ThemePrefs.resolveDark(mode, systemDark)
    val scheme = if (dark) DarkColors else LightColors

    // 状态栏图标跟随最终生效的深浅模式：浅色深图标，夜间浅图标
    val view = LocalView.current
    LaunchedEffect(dark) {
        (view.context as? Activity)?.window?.let { window ->
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !dark
        }
    }

    MaterialTheme(colorScheme = scheme) {
        // 未显式指定颜色的 Text / Icon 统一跟随 onSurface，避免深色模式下默认黑字落在黑底上
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
}
