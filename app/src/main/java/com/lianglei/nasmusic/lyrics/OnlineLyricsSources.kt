// SPDX-License-Identifier: GPL-3.0-only
// Provider request, transport decoding, and fallback behavior is derived from LDDC.
// 移植自 github.com/liangleionline/fn-music-tv core/lyrics（GPL-3.0），JSON 层改用 Android 内置 org.json。
package com.lianglei.nasmusic.lyrics

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

object DefaultLyricsSources {
    fun create(client: OkHttpClient): List<LyricsSource> {
        val http = OkHttpLyricsHttpClient(client)
        return listOf(
            NeteaseLyricsSource(http),
            QqMusicLyricsSource(http),
            KugouLyricsSource(http),
        )
    }
}

class QqMusicLyricsSource(
    private val http: LyricsHttpClient,
) : LyricsSource {
    override val id = LyricsSourceId.QqMusic

    override suspend fun search(query: LyricsSearchQuery): List<LyricsCandidate> {
        val root = parseObject(
            http.get(
                "https://c.y.qq.com/soso/fcgi-bin/client_search_cp",
                mapOf("w" to query.keyword, "format" to "json", "p" to "1", "n" to "20", "cr" to "1", "g_tk" to "5381"),
                QQ_HEADERS,
            ),
        )
        return root.obj("data")?.obj("song")?.arr("list").orEmpty().objects().mapNotNull { item ->
            val remoteId = item.str("songid") ?: return@mapNotNull null
            val mediaId = item.str("songmid") ?: return@mapNotNull null
            val title = decodeHtml(item.str("songname").orEmpty()).takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            LyricsCandidate(
                source = id,
                remoteId = remoteId,
                mediaId = mediaId,
                title = title,
                artists = item.arr("singer").orEmpty().objects()
                    .mapNotNull { it.str("name")?.let(::decodeHtml) },
                album = item.str("albumname")?.let(::decodeHtml),
                durationMs = item.long("interval")?.times(1_000L),
                instrumental = item.long("pure") == 1L,
            )
        }
    }

    override suspend fun fetch(candidate: LyricsCandidate): RawLyrics = try {
        fetchNativeQrc(candidate)
    } catch (cause: CancellationException) {
        throw cause
    } catch (_: Exception) {
        fetchLrc(candidate)
    }

    private suspend fun fetchNativeQrc(candidate: LyricsCandidate): RawLyrics {
        val songId = candidate.remoteId.toLongOrNull()
            ?: throw LyricsPayloadException("QQ candidate has no numeric song ID")
        val param = JSONObject()
            .put("albumName", encodeBase64(candidate.album.orEmpty()))
            .put("crypt", 1)
            .put("ct", 19)
            .put("cv", 2111)
            .put("interval", (candidate.durationMs ?: 0L) / 1_000L)
            .put("lrc_t", 0)
            .put("qrc", 1)
            .put("qrc_t", 0)
            .put("roma", 1)
            .put("roma_t", 0)
            .put("singerName", encodeBase64(candidate.artists.joinToString(" / ")))
            .put("songID", songId)
            .put("songName", encodeBase64(candidate.title))
            .put("trans", 1)
            .put("trans_t", 0)
            .put("type", 0)
        val comm = JSONObject().put("ct", 19).put("cv", 2111).put("uin", "0")
        val request = JSONObject()
            .put("module", "music.musichallSong.PlayLyricInfo")
            .put("method", "GetPlayLyricInfo")
            .put("param", param)
        val body = JSONObject().put("comm", comm).put("request", request).toString()
        val root = parseObject(http.post(QQ_MUSICU_URL, body, QQ_NATIVE_HEADERS))
        val data = root.obj("request")?.obj("data")
            ?: throw LyricsPayloadException("QQ QRC response has no data")
        val original = decodeQqNativeField(data.str("lyric"))
        if (original.isBlank()) throw LyricsPayloadException("QQ QRC lyrics are empty")
        val translation = data.str("trans")?.takeIf(String::isNotBlank)?.let { encrypted ->
            runCatching { decodeQqNativeField(encrypted) }.getOrNull()
        }
        val phonetic = data.str("roma")?.takeIf(String::isNotBlank)?.let { encrypted ->
            runCatching { decodeQqNativeField(encrypted) }.getOrNull()
        }
        return LyricsRawParser.parse(original, translation, phonetic).requireUsable("QQ QRC")
    }

    private suspend fun fetchLrc(candidate: LyricsCandidate): RawLyrics {
        val mediaId = candidate.mediaId ?: throw LyricsPayloadException("QQ candidate has no media ID")
        val root = parseObject(
            http.get(
                "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg",
                mapOf("songmid" to mediaId, "format" to "json", "nobase64" to "1", "g_tk" to "5381"),
                QQ_HEADERS,
            ),
        )
        val original = decodeHtml(decodeLyricField(root.str("lyric")))
        val translation = decodeHtml(decodeLyricField(root.str("trans")))
        return LyricsRawParser.parse(original, translation).requireUsable("QQ LRC")
    }

    private companion object {
        const val QQ_MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        val QQ_HEADERS = mapOf("Referer" to "https://y.qq.com/")
        val QQ_NATIVE_HEADERS = mapOf(
            "Cookie" to "tmeLoginType=-1;",
            "User-Agent" to "okhttp/3.14.9",
        )
    }
}

class NeteaseLyricsSource(
    private val http: LyricsHttpClient,
) : LyricsSource {
    override val id = LyricsSourceId.Netease

    override suspend fun search(query: LyricsSearchQuery): List<LyricsCandidate> {
        val root = parseObject(
            http.get(
                "https://music.163.com/api/search/get/web",
                mapOf("s" to query.keyword, "type" to "1", "limit" to "20", "offset" to "0"),
                NETEASE_HEADERS,
            ),
        )
        return root.obj("result")?.arr("songs").orEmpty().objects().mapNotNull { song ->
            val remoteId = song.str("id") ?: return@mapNotNull null
            val title = song.str("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val artists = (song.arr("artists") ?: song.arr("ar")).orEmpty().objects()
                .mapNotNull { it.str("name") }
            val album = (song.obj("album") ?: song.obj("al"))?.str("name")
            LyricsCandidate(
                source = id,
                remoteId = remoteId,
                title = title,
                artists = artists,
                album = album,
                durationMs = song.long("duration") ?: song.long("dt"),
            )
        }
    }

    override suspend fun fetch(candidate: LyricsCandidate): RawLyrics {
        val root = parseObject(
            http.get(
                "https://music.163.com/api/song/lyric",
                mapOf("id" to candidate.remoteId, "lv" to "1", "kv" to "1", "tv" to "1", "yv" to "1", "rv" to "1"),
                NETEASE_HEADERS,
            ),
        )
        val yrc = root.obj("yrc")?.str("lyric").orEmpty()
        val lrc = root.obj("lrc")?.str("lyric").orEmpty()
        val original = yrc.ifBlank { lrc }
        val translation = root.obj("tlyric")?.str("lyric")
        val phonetic = root.obj("romalrc")?.str("lyric")
        return LyricsRawParser.parse(original, translation, phonetic).requireUsable("Netease lyrics")
    }

    private companion object {
        val NETEASE_HEADERS = mapOf("Referer" to "https://music.163.com/")
    }
}

class KugouLyricsSource(
    private val http: LyricsHttpClient,
) : LyricsSource {
    override val id = LyricsSourceId.Kugou

    override suspend fun search(query: LyricsSearchQuery): List<LyricsCandidate> {
        val root = parseObject(
            http.get(
                "https://songsearch.kugou.com/song_search_v2",
                mapOf(
                    "keyword" to query.keyword,
                    "page" to "1",
                    "pagesize" to "20",
                    "platform" to "WebFilter",
                    "userid" to "-1",
                    "clientver" to "2000",
                ),
            ),
        )
        return root.obj("data")?.arr("lists").orEmpty().objects().mapNotNull { item ->
            val remoteId = item.str("ID") ?: return@mapNotNull null
            val title = item.str("SongName")?.let(::stripMarkup)?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val durationSeconds = item.long("Duration") ?: item.long("SQDuration") ?: item.long("HQDuration")
            LyricsCandidate(
                source = id,
                remoteId = remoteId,
                title = title,
                artists = item.str("SingerName").orEmpty().split("、", "/", "&")
                    .map(String::trim).filter(String::isNotBlank),
                album = item.str("AlbumName")?.let(::stripMarkup),
                durationMs = durationSeconds?.times(1_000L),
                fileHash = item.str("FileHash") ?: item.str("SQFileHash") ?: item.str("HQFileHash"),
            )
        }
    }

    override suspend fun fetch(candidate: LyricsCandidate): RawLyrics {
        val lyricCandidate = findLyricCandidate(candidate)
        return try {
            download(lyricCandidate, "krc").requireUsable("Kugou KRC")
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
            download(lyricCandidate, "lrc").requireUsable("Kugou LRC")
        }
    }

    private suspend fun findLyricCandidate(candidate: LyricsCandidate): JSONObject {
        val search = parseObject(
            http.get(
                "https://lyrics.kugou.com/search",
                buildMap {
                    put("ver", "1")
                    put("man", "yes")
                    put("client", "pc")
                    put("keyword", (candidate.artists.firstOrNull()?.plus(" - ") ?: "") + candidate.title)
                    candidate.durationMs?.let { put("duration", it.toString()) }
                    candidate.fileHash?.let { put("hash", it) }
                },
            ),
        )
        return search.arr("candidates").orEmpty().objects()
            .filter { it.str("id") != null && it.str("accesskey") != null }
            .minWithOrNull(
                compareBy<JSONObject> { row ->
                    val duration = row.long("duration")
                    if (duration == null || candidate.durationMs == null) Long.MAX_VALUE / 2
                    else kotlin.math.abs(duration - candidate.durationMs)
                }.thenByDescending { it.long("score") ?: 0L },
            ) ?: throw LyricsPayloadException("Kugou returned no lyric candidates")
    }

    private suspend fun download(candidate: JSONObject, format: String): RawLyrics {
        val body = parseObject(
            http.get(
                "https://lyrics.kugou.com/download",
                mapOf(
                    "ver" to "1",
                    "client" to "pc",
                    "id" to candidate.str("id")!!,
                    "accesskey" to candidate.str("accesskey")!!,
                    "fmt" to format,
                    "charset" to "utf8",
                ),
            ),
        )
        val encoded = body.str("content").orEmpty()
        if (encoded.isBlank()) throw LyricsPayloadException("Kugou lyrics are empty")
        val decoded = when {
            format == "krc" && body.long("contenttype") != 2L -> decryptKrc(encoded)
            else -> decodeBase64(encoded)
        }
        return LyricsRawParser.parse(decoded)
    }
}

private fun parseObject(value: String): JSONObject = try {
    JSONObject(value)
} catch (cause: Exception) {
    throw LyricsPayloadException("Invalid provider JSON", cause)
}

private fun JSONObject.obj(name: String): JSONObject? = optJSONObject(name)
private fun JSONObject.arr(name: String): JSONArray? = optJSONArray(name)
private fun JSONObject.str(name: String): String? {
    val v = opt(name) ?: return null
    if (v == JSONObject.NULL) return null
    return when (v) {
        is String -> v.takeIf { it.isNotEmpty() }
        is Number, is Boolean -> v.toString()
        else -> null
    }
}
private fun JSONObject.long(name: String): Long? {
    val v = opt(name) ?: return null
    if (v == JSONObject.NULL) return null
    return when (v) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }
}
private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

private fun JSONArray.objects(): List<JSONObject> = buildList {
    for (i in 0 until length()) optJSONObject(i)?.let { add(it) }
}

private fun RawLyrics.requireUsable(label: String): RawLyrics =
    takeIf(RawLyrics::usable) ?: throw LyricsPayloadException("$label are empty")

private fun decodeLyricField(value: String?): String {
    val raw = value.orEmpty()
    if (raw.isBlank() || raw.contains('[')) return raw
    return runCatching { decodeBase64(raw) }.getOrDefault(raw)
}

private fun decodeQqNativeField(value: String?): String {
    val raw = value.orEmpty()
    if (raw.isBlank()) return ""
    return if (raw.length % 2 == 0 && raw.all(Char::isHexDigit)) decryptQrc(raw) else decodeLyricField(raw)
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun decryptQrc(value: String): String = try {
    val encrypted = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(QRC_KEY, "DESede"))
    inflate(cipher.doFinal(encrypted))
} catch (cause: Exception) {
    throw LyricsPayloadException("Invalid QQ QRC payload", cause)
}

private fun decryptKrc(value: String): String = try {
    val encoded = decodeBase64Bytes(value)
    if (encoded.size < KRC_HEADER.size || !encoded.copyOfRange(0, KRC_HEADER.size).contentEquals(KRC_HEADER)) {
        throw LyricsPayloadException("Invalid Kugou KRC header")
    }
    val encrypted = encoded.copyOfRange(KRC_HEADER.size, encoded.size)
    val decoded = ByteArray(encrypted.size) { index ->
        (encrypted[index].toInt() xor KRC_KEY[index % KRC_KEY.size].toInt()).toByte()
    }
    inflate(decoded)
} catch (cause: LyricsPayloadException) {
    throw cause
} catch (cause: Exception) {
    throw LyricsPayloadException("Invalid Kugou KRC payload", cause)
}

private fun inflate(value: ByteArray): String = InflaterInputStream(ByteArrayInputStream(value)).use { stream ->
    stream.readBytes().toString(Charsets.UTF_8)
}

private fun encodeBase64(value: String): String =
    Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

private fun decodeBase64(value: String): String = decodeBase64Bytes(value).toString(Charsets.UTF_8)

private fun decodeBase64Bytes(value: String): ByteArray = try {
    Base64.getDecoder().decode(value)
} catch (cause: IllegalArgumentException) {
    throw LyricsPayloadException("Invalid base64 lyric payload", cause)
}

private fun decodeHtml(value: String): String = value
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace(Regex("&#(\\d+);")) { match ->
        match.groupValues[1].toIntOrNull()?.let { codePoint -> String(Character.toChars(codePoint)) }.orEmpty()
    }

private fun stripMarkup(value: String): String = decodeHtml(value.replace(Regex("<[^>]+>"), "")).trim()

private val QRC_KEY = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII)
private val KRC_HEADER = byteArrayOf('k'.code.toByte(), 'r'.code.toByte(), 'c'.code.toByte(), '1'.code.toByte())
private val KRC_KEY = byteArrayOf(
    0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
    0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
)
