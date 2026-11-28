package com.lianglei.nasmusic.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 在线歌词自动匹配开关（v1.5.36）：默认开启。
 * 开启后播放页优先在线匹配逐字歌词（网易云/QQ音乐/酷狗），失败自动回退内嵌/同目录歌词；
 * 关闭后只使用本地歌词（.ttml/.lrc 侧车 + 内嵌标签）。
 */
object LyricMatchPrefs {
    private const val PREFS = "lyric_match"
    private const val KEY_ENABLED = "auto_match_enabled"

    private var prefs: SharedPreferences? = null
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _enabled.value = prefs?.getBoolean(KEY_ENABLED, true) ?: true
    }

    fun setEnabled(ctx: Context, value: Boolean) {
        if (prefs == null) init(ctx)
        prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        _enabled.value = value
    }
}
