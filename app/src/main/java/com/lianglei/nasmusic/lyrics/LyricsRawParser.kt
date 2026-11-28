// SPDX-License-Identifier: GPL-3.0-only
// 自包含在线歌词解析：标准 LRC / 增强 LRC / 网易 YRC / QQ QRC / 酷狗 KRC，
// 不依赖第三方歌词库。翻译侧车（标准 LRC）按最近时间对齐到主歌词行。
package com.lianglei.nasmusic.lyrics

import kotlin.math.abs

object LyricsRawParser {

    // 行首一个或多个时间标签：[mm:ss.xx] 或 [起ms,时长ms]
    private val LINE_HEADER = Regex("""^\s*((?:\[\d+[,:]\d+(?:\.\d+)?\])+)""")
    private val HEADER_TOKEN = Regex("""\[([^\]]+)]""")
    // 逐字标记：QQ/YRC 的 (起ms,时长ms)、酷狗 KRC 的 <起ms,时长ms>
    private val WORD_COMMA = Regex("""\((\d+),(\d+)\)""")
    private val WORD_ANGLE_MS = Regex("""<(\d+),(\d+)>""")
    // 增强 LRC 的 <mm:ss.xx>（绝对时间）
    private val WORD_ANGLE_TS = Regex("""<(\d+):(\d{1,2})(?:\.(\d{1,3}))?>""")
    // QQ 原生 QRC 的 XML 外壳
    private val QQ_XML = Regex(
        """<Lyric_1\b[^>]*\bLyricContent="(.*?)"\s*/>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /** 解析主歌词；translation 为可选翻译 LRC，phonetic 暂不使用（本项目无注音字段） */
    fun parse(original: String, translation: String? = null, phonetic: String? = null): RawLyrics {
        val main = parseMain(extractQqXml(original))
        val trans = translation?.takeIf { it.isNotBlank() }?.let { parseMain(it) }
        val aligned = if (trans != null) alignTranslation(main, trans) else main
        return RawLyrics(aligned)
    }

    private fun parseMain(raw: String): List<RawLine> {
        val out = ArrayList<RawLine>()
        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val headerMatch = LINE_HEADER.find(line) ?: return@forEach
            val body = line.substring(headerMatch.range.last + 1)
            val headers = HEADER_TOKEN.findAll(headerMatch.groupValues[1]).map { it.groupValues[1] }.toList()
            val parsed = parseBody(body, headers.firstOrNull())
            headers.forEach { token ->
                val (start, _) = parseHeaderTime(token)
                out.add(RawLine(start, parsed.text, parsed.words))
            }
        }
        return out.sortedBy { it.startMs }
    }

    private data class ParsedBody(val text: String, val words: List<RawWord>)

    private fun parseBody(body: String, firstHeader: String?): ParsedBody {
        val (lineStart, lineDur) = firstHeader?.let { parseHeaderTime(it) } ?: (0L to 0L)
        val lineEnd = if (lineDur > 0) lineStart + lineDur else null
        data class Marker(val start: Long, val end: Long?, val range: IntRange)
        data class Resolved(val start: Long, val end: Long, val range: IntRange)
        val markers = ArrayList<Marker>()
        WORD_COMMA.findAll(body).forEach { m ->
            markers.add(Marker(m.groupValues[1].toLong(), m.groupValues[1].toLong() + m.groupValues[2].toLong(), m.range))
        }
        WORD_ANGLE_MS.findAll(body).forEach { m ->
            markers.add(Marker(m.groupValues[1].toLong(), m.groupValues[1].toLong() + m.groupValues[2].toLong(), m.range))
        }
        WORD_ANGLE_TS.findAll(body).forEach { m ->
            val start = parseMmSs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            markers.add(Marker(start, null, m.range))
        }
        markers.sortBy { it.range.first }
        if (markers.isEmpty()) {
            return ParsedBody(body.trim(), emptyList())
        }
        // 绝对时间标记（增强 LRC）的结束时间取下一个标记起点，最后一个取行尾或估算
        val resolved = markers.mapIndexed { i, marker ->
            val end = marker.end ?: when {
                i + 1 < markers.size -> markers[i + 1].start
                lineEnd != null -> lineEnd
                else -> marker.start + 500
            }
            Resolved(marker.start, end, marker.range)
        }
        val words = ArrayList<RawWord>()
        resolved.forEachIndexed { i, marker ->
            val textEnd = if (i + 1 < resolved.size) resolved[i + 1].range.first else body.length
            val text = body.substring(marker.range.last + 1, textEnd).trim()
            if (text.isNotEmpty() && marker.end > marker.start) {
                words.add(RawWord(marker.start, marker.end, text))
            }
        }
        val joined = words.joinToString("") { it.text }
        val text = joined.ifBlank { body.trim() }
        return ParsedBody(text, words)
    }

    private fun parseHeaderTime(token: String): Pair<Long, Long> {
        return if (token.contains(',')) {
            val parts = token.split(',')
            val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val dur = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            start to dur
        } else {
            // [mm:ss.xx] 或 [mm:ss]，小数在秒段里
            val parts = token.split(':')
            val mm = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val secParts = parts.getOrNull(1).orEmpty().split('.')
            val ss = secParts.getOrNull(0)?.toLongOrNull() ?: 0L
            val frac = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
            (mm * 60_000L + ss * 1000L + frac) to 0L
        }
    }

    private fun parseMmSs(mm: String, ss: String, frac: String?): Long {
        val m = mm.toLongOrNull() ?: 0L
        val s = ss.toLongOrNull() ?: 0L
        val f = frac?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
        return m * 60_000L + s * 1000L + f
    }

    private fun alignTranslation(main: List<RawLine>, trans: List<RawLine>): List<RawLine> {
        if (trans.isEmpty()) return main
        val used = mutableSetOf<Int>()
        return main.map { line ->
            val best = trans.indices
                .filterNot { it in used }
                .minByOrNull { abs(trans[it].startMs - line.startMs) }
            if (best != null && abs(trans[best].startMs - line.startMs) <= MAX_SIDECAR_DELTA_MS) {
                used.add(best)
                val t = trans[best].text.trim()
                if (t.isNotEmpty() && t != line.text) line.copy(translation = t) else line
            } else {
                line
            }
        }
    }

    private fun extractQqXml(value: String): String {
        val matched = QQ_XML.find(value)?.groupValues?.get(1)
        return if (matched != null) decodeXmlEntities(matched) else value
    }

    private fun decodeXmlEntities(value: String): String = value
        .replace("&#10;", "\n")
        .replace("&#13;", "\r")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace(Regex("&#(\\d+);")) { match ->
            match.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) }.orEmpty()
        }

    private const val MAX_SIDECAR_DELTA_MS = 1_500L
}
