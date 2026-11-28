// SPDX-License-Identifier: GPL-3.0-only
// 在线歌词匹配桥接层：三源协调器 + 指纹磁盘缓存 + RawLyrics 转本项目 LrcLine。
package com.lianglei.nasmusic.lyrics

import android.content.Context
import com.lianglei.nasmusic.data.Song
import com.lianglei.nasmusic.ui.screens.LrcLine
import com.lianglei.nasmusic.ui.screens.LrcWord
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 在线匹配结果：lines 已是本项目歌词模型，sourceKey 用于左下角来源标签 */
data class OnlineMatch(val lines: List<LrcLine>, val sourceKey: String)

object OnlineLyricsMatcher {
    private const val PROTOCOL_VERSION = "nas-lyrics-1"
    private const val NEGATIVE_CACHE_TTL_MS = 5 * 60 * 1000L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .connectTimeout(1200, TimeUnit.MILLISECONDS)
            .readTimeout(1800, TimeUnit.MILLISECONDS)
            .build()
    }

    private val coordinator: LyricsMatchCoordinator by lazy {
        LyricsMatchCoordinator(DefaultLyricsSources.create(client))
    }

    /**
     * 匹配一首歌的在线歌词。命中成功缓存直接返回；未找到写 5 分钟负缓存；
     * 网络/解析失败不写缓存，返回 null 让上层回退本地歌词。
     */
    suspend fun match(context: Context, song: Song): OnlineMatch? {
        val fp = fingerprint(song)
        readCache(context, fp)?.let { cached ->
            if (cached.lines.isEmpty()) return null // 负缓存：近期已确认无匹配
            return OnlineMatch(cached.lines, cached.sourceKey)
        }

        val request = LyricsMatchRequest(
            localId = song.id.toString(),
            title = song.title,
            artists = listOf(song.artist).filter { it.isNotBlank() },
            album = song.album.takeIf { it.isNotBlank() },
            durationMs = song.duration.takeIf { it > 0 },
        )
        return when (val result = coordinator.match(request)) {
            is LyricsMatchResult.Found -> {
                val matched = result.lyrics
                val lines = matched.lyrics.toLrcLines()
                if (lines.isEmpty()) {
                    writeNegative(context, fp)
                    null
                } else {
                    val sourceKey = when (matched.source) {
                        LyricsSourceId.Netease -> "netease"
                        LyricsSourceId.QqMusic -> "qq"
                        LyricsSourceId.Kugou -> "kugou"
                    }
                    writeSuccess(context, fp, lines, sourceKey)
                    OnlineMatch(lines, sourceKey)
                }
            }
            LyricsMatchResult.NotFound -> {
                writeNegative(context, fp)
                null
            }
            LyricsMatchResult.NetworkFailure,
            LyricsMatchResult.InvalidResponse,
            -> null
        }
    }

    /** RawLyrics → 本项目 LrcLine：主歌词（含逐字）+ 翻译行（同时间、无逐字） */
    private fun RawLyrics.toLrcLines(): List<LrcLine> {
        val out = ArrayList<LrcLine>()
        lines.forEach { line ->
            val main = line.text.trim()
            if (main.isNotEmpty()) {
                val words = line.words.mapNotNull { w ->
                    val t = w.text.trim()
                    if (t.isNotEmpty() && w.endMs > w.startMs) {
                        LrcWord(w.startMs, w.endMs, t)
                    } else {
                        null
                    }
                }
                out.add(LrcLine(line.startMs.coerceAtLeast(0), main, words))
            }
            val trans = line.translation?.trim()
            if (!trans.isNullOrEmpty() && trans != main) {
                out.add(LrcLine(line.startMs.coerceAtLeast(0), trans))
            }
        }
        return out.sortedBy { it.time }
    }

    private fun fingerprint(song: Song): String {
        val value = listOf(
            song.id.toString(),
            LyricsCandidateScorer.normalize(song.title),
            LyricsCandidateScorer.normalize(song.artist),
            LyricsCandidateScorer.normalize(song.album),
            song.duration.toString(),
            PROTOCOL_VERSION,
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, "lyrics_online").apply { if (!exists()) mkdirs() }

    private data class Cached(val lines: List<LrcLine>, val sourceKey: String)

    private fun readCache(context: Context, fp: String): Cached? {
        return try {
        val file = File(cacheDir(context), "$fp.json")
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        if (json.optString("fp") != fp) return null
        if (json.optLong("expires", 0L) <= System.currentTimeMillis()) return null
        val sourceKey = json.optString("source", "")
        val arr = json.optJSONArray("lines") ?: JSONArray()
        val lines = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val text = o.optString("text")
                if (text.isBlank()) continue
                val wordsArr = o.optJSONArray("words")
                val words = if (wordsArr != null) {
                    buildList {
                        for (j in 0 until wordsArr.length()) {
                            val w = wordsArr.optJSONObject(j) ?: continue
                            val wt = w.optString("t")
                            if (wt.isNotBlank()) {
                                add(LrcWord(w.optLong("s"), w.optLong("e"), wt))
                            }
                        }
                    }
                } else {
                    emptyList()
                }
                add(LrcLine(o.optLong("t"), text, words))
            }
        }
        Cached(lines, sourceKey)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeSuccess(context: Context, fp: String, lines: List<LrcLine>, sourceKey: String) {
        try {
            val arr = JSONArray()
            lines.forEach { line ->
                val o = JSONObject()
                    .put("t", line.time)
                    .put("text", line.text)
                if (line.words.isNotEmpty()) {
                    val wa = JSONArray()
                    line.words.forEach { w ->
                        wa.put(JSONObject().put("s", w.startMs).put("e", w.endMs).put("t", w.text))
                    }
                    o.put("words", wa)
                }
                arr.put(o)
            }
            val json = JSONObject()
                .put("fp", fp)
                .put("expires", Long.MAX_VALUE)
                .put("source", sourceKey)
                .put("lines", arr)
            File(cacheDir(context), "$fp.json").writeText(json.toString())
        } catch (_: Exception) {
            // 缓存写失败不影响本次歌词展示
        }
    }

    private fun writeNegative(context: Context, fp: String) {
        try {
            val json = JSONObject()
                .put("fp", fp)
                .put("expires", System.currentTimeMillis() + NEGATIVE_CACHE_TTL_MS)
                .put("source", "")
                .put("lines", JSONArray())
            File(cacheDir(context), "$fp.json").writeText(json.toString())
        } catch (_: Exception) {
        }
    }
}
