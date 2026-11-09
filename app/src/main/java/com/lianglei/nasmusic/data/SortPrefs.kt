package com.lianglei.nasmusic.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 列表排序偏好持久化（v1.5.0）。
 *
 * 每个列表用独立的 key（songs / albums / artists / folders），各自记住"用哪个排序项 + 正序还是倒序"，
 * 下次启动进页面直接沿用上次的选择，不会退回默认排序。
 */
object SortPrefs {
    const val SONGS = "songs"
    const val ALBUMS = "albums"
    const val ARTISTS = "artists"
    const val FOLDERS = "folders"

    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("ui_sort", Context.MODE_PRIVATE)
    }

    /** 上次选择的排序项下标；未保存过返回 0（各列表的第一项，即"添加时间"或"名称"） */
    fun mode(key: String): Int = prefs?.getInt("${key}_mode", 0) ?: 0

    /** 上次选择的方向：true = 逆序，false = 正序 */
    fun desc(key: String): Boolean = prefs?.getBoolean("${key}_desc", false) ?: false

    fun save(key: String, mode: Int, desc: Boolean) {
        prefs?.edit()?.putInt("${key}_mode", mode)?.putBoolean("${key}_desc", desc)?.apply()
    }
}
