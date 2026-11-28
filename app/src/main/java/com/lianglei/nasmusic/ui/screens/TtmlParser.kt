package com.lianglei.nasmusic.ui.screens

import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/**
 * TTML（Apple Music 风格 lyric-ttml-internal / 标准 TTML）歌词解析。
 *
 * 典型结构：
 * ```
 * <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="...#metadata">
 *   <head><metadata><ttm:agent type="person" xml:id="v1"/>...</metadata></head>
 *   <body><div>
 *     <p begin="00:00:02.000" end="00:00:05.000" ttm:agent="v1">
 *       <span begin="00:00:02.000" end="00:00:02.400">与</span>...
 *     </p>
 *   </div></body>
 * </tt>
 * ```
 * 对唱通过 `ttm:agent="v1/v2"` 区分；逐字时间放在 `<span begin/end>`。
 * 解析结果统一成 [LrcLine]（words 为逐字时间，agent 为演唱者 id），
 * 后续渲染层与普通 LRC 共用一套面板。
 */

/** 文本是否像 TTML：头部同时出现 `<tt` 与 ttml 命名空间，避免误伤普通 LRC。 */
fun looksLikeTtml(raw: String): Boolean {
    val head = raw.trimStart().take(4000)
    return head.contains("<tt", ignoreCase = true) && head.contains("ttml", ignoreCase = true)
}

/**
 * 歌词自动分发：TTML 走 XML 解析；否则按 LRC 解析；
 * LRC 解析不出任何时间行时（纯文本 USLT/FLAC 歌词），按行补 4 秒间隔兜底。
 */
fun parseLyricsAuto(raw: String): List<LrcLine> {
    if (raw.isBlank()) return emptyList()
    if (looksLikeTtml(raw)) return parseTtml(raw)
    val lrc = parseLrc(raw)
    if (lrc.isNotEmpty()) return lrc
    return parseLrc(plainToLrc(raw))
}

private const val TTM_METADATA_NS = "http://www.w3.org/ns/ttml#metadata"

fun parseTtml(raw: String): List<LrcLine> {
    val parser = Xml.newPullParser()
    parser.setInput(raw.reader())

    val result = mutableListOf<LrcLine>()
    var inP = false
    var pBegin = -1L
    var pAgent: String? = null
    val text = StringBuilder()
    val words = mutableListOf<LrcWord>()
    var spanBegin = -1L
    var spanEnd = -1L

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "p" -> {
                    inP = true
                    pBegin = parseTtmlTime(parser.getAttributeValue(null, "begin"))
                    pAgent = parser.getAttributeValue(TTM_METADATA_NS, "agent")
                        ?: parser.getAttributeValue(null, "ttm:agent")
                        ?: parser.getAttributeValue(null, "agent")
                    text.setLength(0)
                    words.clear()
                }
                "span" -> {
                    spanBegin = parseTtmlTime(parser.getAttributeValue(null, "begin"))
                    spanEnd = parseTtmlTime(parser.getAttributeValue(null, "end"))
                }
                "br" -> if (inP) text.append(' ')
            }

            XmlPullParser.TEXT -> if (inP) {
                val t = parser.text
                if (!t.isNullOrEmpty()) {
                    text.append(t)
                    if (spanBegin >= 0) {
                        val w = t.trim()
                        if (w.isNotEmpty()) {
                            val end = if (spanEnd >= 0) spanEnd else spanBegin + 300L
                            words.add(LrcWord(spanBegin, end.coerceAtLeast(spanBegin + 1L), w))
                        }
                    }
                }
            }

            XmlPullParser.END_TAG -> when (parser.name) {
                "span" -> {
                    spanBegin = -1L
                    spanEnd = -1L
                }
                "p" -> {
                    val content = text.toString().trim().replace(Regex("\\s+"), " ")
                    if (content.isNotEmpty()) {
                        val start = if (pBegin >= 0) pBegin else words.firstOrNull()?.startMs ?: 0L
                        result.add(LrcLine(start, content, words.toList(), pAgent))
                    }
                    inP = false
                }
            }
        }
        event = parser.next()
    }
    return result.sortedBy { it.time }
}

/**
 * 解析 TTML 时间：`HH:MM:SS.mmm`、`MM:SS.mmm`、`SS.mmm`、`SSs`、`HH:MM:SS:FF`（帧按 30fps 近似）。
 * 无法解析返回 -1。
 */
fun parseTtmlTime(s: String?): Long {
    if (s.isNullOrBlank()) return -1L
    val v = s.trim()
    if (!v.contains(':')) {
        val num = v.removeSuffix("s").removeSuffix("S").toDoubleOrNull() ?: return -1L
        return (num * 1000).toLong()
    }
    val parts = v.split(':')
    return when (parts.size) {
        3 -> {
            val h = parts[0].toLongOrNull() ?: 0L
            val m = parts[1].toLongOrNull() ?: 0L
            val sec = parts[2].toDoubleOrNull() ?: 0.0
            (h * 3600 + m * 60) * 1000L + (sec * 1000).toLong()
        }
        2 -> {
            val m = parts[0].toLongOrNull() ?: 0L
            val sec = parts[1].toDoubleOrNull() ?: 0.0
            m * 60000L + (sec * 1000).toLong()
        }
        4 -> {
            val h = parts[0].toLongOrNull() ?: 0L
            val m = parts[1].toLongOrNull() ?: 0L
            val sec = parts[2].toLongOrNull() ?: 0L
            val frames = parts[3].toDoubleOrNull() ?: 0.0
            (h * 3600 + m * 60 + sec) * 1000L + (frames / 30.0 * 1000).toLong()
        }
        else -> -1L
    }
}
