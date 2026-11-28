// SPDX-License-Identifier: GPL-3.0-only
// 移植自 github.com/liangleionline/fn-music-tv core/lyrics（GPL-3.0），包名适配本项目。
// 不依赖第三方歌词解析库：在线源返回统一的 RawLyrics，由 LyricsRawParser 解析各家格式。
package com.lianglei.nasmusic.lyrics

data class LyricsMatchRequest(
    val localId: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationMs: Long? = null,
)

enum class LyricsSourceId { QqMusic, Kugou, Netease }

enum class LyricsContentQuality(internal val rank: Int) {
    Basic(0),
    Translated(1),
    WordTimed(2),
}

data class LyricsCandidate(
    val source: LyricsSourceId,
    val remoteId: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationMs: Long? = null,
    val mediaId: String? = null,
    val fileHash: String? = null,
    val accessKey: String? = null,
    val instrumental: Boolean = false,
)

/** 在线歌词解析后的统一中间模型（时间均为毫秒） */
data class RawWord(val startMs: Long, val endMs: Long, val text: String)

data class RawLine(
    val startMs: Long,
    val text: String,
    val words: List<RawWord> = emptyList(),
    val translation: String? = null,
)

data class RawLyrics(val lines: List<RawLine>) {
    fun usable(): Boolean = lines.any { it.text.isNotBlank() }
    val wordTimedLineCount: Int get() = lines.count { it.words.size >= 2 }
    val translatedLineCount: Int get() = lines.count { !it.translation.isNullOrBlank() }

    /** 时长差过大（如现场版错位）时压平逐字时间，只保留整行 */
    fun flattenWords(): RawLyrics = copy(
        lines = lines.map { it.copy(words = emptyList()) },
    )
}

data class MatchedLyrics(
    val source: LyricsSourceId,
    val candidate: LyricsCandidate,
    val score: Double,
    val lyrics: RawLyrics,
    val quality: LyricsContentQuality = LyricsContentQuality.Basic,
)

sealed interface LyricsMatchResult {
    data class Found(val lyrics: MatchedLyrics) : LyricsMatchResult
    data object NotFound : LyricsMatchResult
    data object NetworkFailure : LyricsMatchResult
    data object InvalidResponse : LyricsMatchResult
}

data class LyricsSearchQuery(
    val keyword: String,
    val request: LyricsMatchRequest,
)

interface LyricsSource {
    val id: LyricsSourceId
    suspend fun search(query: LyricsSearchQuery): List<LyricsCandidate>
    suspend fun fetch(candidate: LyricsCandidate): RawLyrics
}

class LyricsTransportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class LyricsPayloadException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
