package com.lianglei.nasmusic.data

import android.content.Context
import com.lianglei.nasmusic.util.CrashLogger
import org.json.JSONObject

/**
 * 播放次数持久化（SharedPreferences + JSON map<songId, count>）。
 * 统计页按播放次数排序的数据源；歌曲信息弹窗也展示单曲播放次数。
 * 计数规则见 PlayerManager：同一首歌累计播放满 15 秒计一次。
 */
object StatsStore {
    private const val PREFS = "stats_prefs"
    private const val KEY_COUNTS = "play_counts_json"
    private var prefs: android.content.SharedPreferences? = null
    private var counts = HashMap<Long, Int>()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        try {
            val raw = prefs?.getString(KEY_COUNTS, null) ?: return
            val o = JSONObject(raw)
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                counts[k.toLong()] = o.optInt(k, 0)
            }
        } catch (t: Throwable) { CrashLogger.e("StatsStore.load failed", t) }
    }

    private fun save() {
        try {
            val o = JSONObject()
            counts.forEach { (k, v) -> o.put(k.toString(), v) }
            prefs?.edit()?.putString(KEY_COUNTS, o.toString())?.apply()
        } catch (t: Throwable) { CrashLogger.e("StatsStore.save failed", t) }
    }

    fun recordPlay(songId: Long) {
        counts[songId] = (counts[songId] ?: 0) + 1
        save()
    }

    fun playCount(songId: Long): Int = counts[songId] ?: 0

    /** 全部播放次数（songId → count），统计页使用 */
    fun allCounts(): Map<Long, Int> = counts
}
