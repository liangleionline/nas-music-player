package com.lianglei.nasmusic.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 外观主题模式（v1.5.30）：跟随系统 / 白天 / 夜间三选一，持久化到本地，
 * 切换后通过 StateFlow 通知主题层即时重绘，不需要重建 Activity。
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

object ThemePrefs {
    private const val PREFS = "ui_theme"
    private const val KEY_MODE = "theme_mode"

    private var prefs: SharedPreferences? = null
    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _mode.value = fromName(prefs?.getString(KEY_MODE, null))
    }

    fun setMode(mode: ThemeMode) {
        prefs?.edit()?.putString(KEY_MODE, mode.name)?.apply()
        _mode.value = mode
    }

    /** 按所选模式解析当前是否应为深色；systemDark 取系统当前深浅态 */
    fun resolveDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
    }

    private fun fromName(name: String?): ThemeMode =
        ThemeMode.entries.firstOrNull { it.name == name } ?: ThemeMode.SYSTEM
}
